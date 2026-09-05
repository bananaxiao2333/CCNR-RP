/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.character;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.network.RpChannels;
import com.ccnrcom.rp.network.RpPackets;
import com.ccnrcom.rp.status.CharacterStatus;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 用户/职位服务（服务端）：以 UserService 的单用户档案为唯一身份，负责：
 * - 用户档案列表同步（K 面板，S2C CharacterListS2C）；
 * - 部署入口 onDeployPosition（自刷新部署，职位维度的校验走 UserService + FactionManager）；
 * - 管理器（阵营/职业/事件/阶段/波）的管理员操作（与角色库无关）。
 * 多角色角色库、皮肤上传与绑定已全部移除。
 */
public final class CharacterService {
    private final MinecraftServer server;
    private final java.util.Map<java.util.UUID, MusicUpload> musicUploads = new java.util.HashMap<>();

    private record MusicUpload(String name, byte[] parts, int received) {}

    // ---- 头顶悬浮标签异步广播（独立线程构建 payload，回主线程发送；合并去重防高并发阻塞） ----

    /** 标签广播专用线程（单线程串行构建，避免并发重复构建）。 */
    private static final java.util.concurrent.ExecutorService TAG_BUILDER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ccnr-rp-nametag-broadcast");
                t.setDaemon(true);
                return t;
            });

    /** 待发送的标签 payload（构建完成待主线程发送；null=无待发）。 */
    private volatile String pendingTagPayload;

    /** 触发一次全服标签刷新（合并：构建中/待发中再触发则复用最近一次结果）。 */
    public void refreshPlayerTags() {
        TAG_BUILDER.execute(() -> {
            try {
                String payload = playerTagsJson().toString();
                pendingTagPayload = payload;
                if (server != null) {
                    server.execute(this::flushTagPayload);
                }
            } catch (Exception ignored) {
                // 构建失败丢弃本次刷新（下次状态变化会重试）
            }
        });
    }

    /** 主线程发送待发标签（每人一个包，payload 复用）。 */
    private void flushTagPayload() {
        String payload = pendingTagPayload;
        if (payload == null || server == null) {
            return;
        }
        pendingTagPayload = null;
        for (net.minecraft.server.level.ServerPlayer p :
                new java.util.ArrayList<>(server.getPlayerList().getPlayers())) {
            RpChannels.sendTo(p, new RpPackets.PlayerTagsS2C(payload));
        }
    }

    // ---- 配置变更全服广播（异步：主线程快照 → 后台构建纯数据 → 回主线程发包；docs/01 §9.4） ----

    /** 配置广播后台构建线程（有界单线程 daemon；只构建纯数据，发包一律回主线程）。 */
    private static final java.util.concurrent.ExecutorService BROADCASTER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ccnr-rp-config-broadcast");
                t.setDaemon(true);
                return t;
            });

    /** 待发送的全服配置广播载荷（后台组装完成，主线程发送；null=无待发；连续变更后者覆盖前者）。 */
    private volatile java.util.List<PendingPush> pendingBroadcast;

    /** 主线程快照的逐玩家字段（用户表为普通 HashMap 且玩家访问只能在主线程，后台线程只读快照）。 */
    private record UserSnapshot(
            String uuid,
            boolean admin,
            boolean panelLocked,
            long userXp,
            int userLevel,
            boolean anySupportRevive,
            String status,
            String professionId,
            String factionId,
            long cooldownUntil) {}

    /** 单玩家的全服广播载荷（后台组装，主线程发送；tags 全服共享同一 payload）。 */
    private record PendingPush(
            String uuid, String listPayload, String managerPayload, String musicPayload, String tagsPayload) {}

    /** 服务端停止时关闭广播构建线程（docs/01 §9.4 对称清理）。 */
    public static void shutdownBroadcaster() {
        BROADCASTER.shutdown();
    }

    /**
     * 配置保存成功后的全员同步（docs/01 §9.2 服务端权威）：
     * 编辑者（有编辑权限的管理员）**立即同步全量刷新**（sendList+sendManagerState，所见即所得，不依赖异步广播），
     * 其余玩家走异步广播（后台构建纯数据 + 回主线程发包，多人不卡服）。供各配置保存入口统一调用。
     */
    public static void broadcastConfigAll(ServerPlayer editor) {
        CharacterService s = CCNRRPMod.characters;
        if (s == null || editor == null) {
            return;
        }
        s.sendList(editor); // 编辑者立即拿到全量数据（含本次改动）
        s.sendManagerState(editor);
        s.broadcastToAll(editor.getUUID().toString()); // 其余玩家异步广播（跳过编辑者，避免重复）
    }

    public CharacterService(MinecraftServer server) {
        this.server = server;
    }

    /** 观察者：地位 OBSERVING 且未被征召。 */
    public static boolean isObserver(ServerPlayer player) {
        if (player == null || CCNRRPMod.users == null) {
            return false;
        }
        String uuid = player.getUUID().toString();
        boolean active = CCNRRPMod.users.status(uuid) != CharacterStatus.OBSERVING;
        return !active && !com.ccnrcom.rp.sequence.SequenceEngine.isConscripted(uuid);
    }

    private static CharacterService service() {
        CharacterService s = CCNRRPMod.characters;
        if (s == null) {
            throw new IllegalStateException("角色服务未初始化");
        }
        return s;
    }

    public void sendError(ServerPlayer player, String key, String... args) {
        RpChannels.sendTo(player, new RpPackets.ErrorS2C(key, args));
    }

    // ---------- 入站：用户档案列表 ----------

    public static void onRequestList(ServerPlayer player) {
        if (player != null) {
            service().sendList(player);
        }
    }

    /** 用户开关：「以任何支援身份复活」（K 面板切换）。 */
    public static void onUserAnySupport(ServerPlayer player, boolean on) {
        if (player == null) {
            return;
        }
        CCNRRPMod.users.setAnySupportRevive(player.getUUID().toString(), on);
        service().sendError(player, on ? "ccnr_rp.user.any_support.on" : "ccnr_rp.user.any_support.off");
        service().sendList(player);
    }

    // ---------- 部署入口（自刷新） ----------

    /**
     * 自刷新部署（GUI / 命令）：以「职位」为维度部署到游戏内，状态机为 OBSERVING → ALIVE。
     * 校验：素材同步完成 → 观察态 → 复活冷却结束 → 职位定义存在 → 用户等级 ≥ 职位解锁等级，
     * 然后交给 SpawnFramework.deployPosition 落地。
     */
    public static void onDeployPosition(ServerPlayer player, String professionId) {
        if (player == null || professionId == null || professionId.isBlank()) {
            return;
        }
        CharacterService svc = service();
        String uuid = player.getUUID().toString();
        if (!com.ccnrcom.rp.assets.AssetLibrary.isSynced(player)) {
            svc.sendError(player, "ccnr_rp.gui.asset.syncing");
            return;
        }
        if (CCNRRPMod.users == null) {
            svc.sendError(player, "ccnr_rp.error.invalid_argument", "用户服务未就绪");
            return;
        }
        if (CCNRRPMod.users.status(uuid) != CharacterStatus.OBSERVING) {
            svc.sendError(player, "ccnr_rp.spawn.error.self_deploy");
            return;
        }
        if (CCNRRPMod.users.onCooldown(uuid)) {
            svc.sendError(player, "ccnr_rp.spawn.error.self_deploy");
            return;
        }
        if (CCNRRPMod.factions == null) {
            svc.sendError(player, "ccnr_rp.error.invalid_argument", "配置管理器未就绪");
            return;
        }
        var def = CCNRRPMod.factions.findProfession(professionId).orElse(null);
        if (def == null) {
            svc.sendError(player, "ccnr_rp.character.error.profession", professionId);
            return;
        }
        int required = com.ccnrcom.rp.faction.FactionProfessions.unlockLevel(def);
        int userLevel = CCNRRPMod.users.level(uuid);
        if (userLevel < required) {
            svc.sendError(
                    player,
                    "ccnr_rp.spawn.error.level",
                    com.ccnrcom.rp.faction.FactionProfessions.idsSafeName(def),
                    String.valueOf(required),
                    String.valueOf(userLevel));
            return;
        }
        if (CCNRRPMod.spawnFramework == null || !CCNRRPMod.spawnFramework.deployPosition(player, professionId)) {
            svc.sendError(player, "ccnr_rp.spawn.error.self_deploy");
            return;
        }
        // 部署成功：以 UserService 写回角色/状态（幂等）
        CCNRRPMod.users.setRole(uuid, professionId, com.ccnrcom.rp.faction.FactionProfessions.factionId(def));
        CCNRRPMod.users.setStatus(uuid, CharacterStatus.ALIVE);
        CCNRRPMod.users.setCooldown(uuid, 0);
        CCNRRPMod.users.save();
        svc.sendError(player, "ccnr_rp.spawn.deployed", com.ccnrcom.rp.faction.FactionProfessions.idsSafeName(def));
        svc.sendList(player);
    }

    /**
     * 重新部署（GUI 确认后）：玩家在场（ALIVE）时直接重新部署为选定职位——不处死、不留遗体、不结算死亡经验；
     * 统一 deploy() 清背包 → 发放新职位装备 → 传送到部署点 → 入场电影 → ALIVE（冷却清零）。
     * 校验：素材同步 → 在场状态 → 职位存在 → 等级达标，然后交给 SpawnFramework.redeploy 落地。
     */
    public static void onKillDeploy(ServerPlayer player, String professionId) {
        if (player == null || professionId == null || professionId.isBlank()) {
            return;
        }
        CharacterService svc = service();
        String uuid = player.getUUID().toString();
        if (!com.ccnrcom.rp.assets.AssetLibrary.isSynced(player)) {
            svc.sendError(player, "ccnr_rp.gui.asset.syncing");
            return;
        }
        if (CCNRRPMod.users == null) {
            svc.sendError(player, "ccnr_rp.error.invalid_argument", "用户服务未就绪");
            return;
        }
        if (CCNRRPMod.users.status(uuid) != CharacterStatus.ALIVE) {
            svc.sendError(player, "ccnr_rp.spawn.error.alive_only");
            return;
        }
        if (CCNRRPMod.factions == null) {
            svc.sendError(player, "ccnr_rp.error.invalid_argument", "配置管理器未就绪");
            return;
        }
        var def = CCNRRPMod.factions.findProfession(professionId).orElse(null);
        if (def == null) {
            svc.sendError(player, "ccnr_rp.character.error.profession", professionId);
            return;
        }
        int required = com.ccnrcom.rp.faction.FactionProfessions.unlockLevel(def);
        int userLevel = CCNRRPMod.users.level(uuid);
        if (userLevel < required) {
            svc.sendError(
                    player,
                    "ccnr_rp.spawn.error.level",
                    com.ccnrcom.rp.faction.FactionProfessions.idsSafeName(def),
                    String.valueOf(required),
                    String.valueOf(userLevel));
            return;
        }
        if (CCNRRPMod.spawnFramework == null) {
            svc.sendError(player, "ccnr_rp.error.invalid_argument", "刷新框架未就绪");
            return;
        }
        // 直接重新部署（不处死、不留遗体、不结算死亡经验）
        if (!CCNRRPMod.spawnFramework.redeploy(player, professionId)) {
            svc.sendError(player, "ccnr_rp.spawn.error.self_deploy");
            return;
        }
        svc.sendList(player);
    }

    // ---------- 管理器（管理员）----------

    public static void onManagerRequest(ServerPlayer player) {
        if (!com.ccnrcom.rp.util.Permissions.canAdmin(player, com.ccnrcom.rp.util.Permissions.ADMIN_FACTION)) {
            service().sendError(player, "ccnr_rp.command.no_permission");
            return;
        }
        sendManagerState(player);
    }

    /** 管理面板程序化设定：设置 serverconfig 项（CCNRRPConfig），成功后刷新客户端。 */
    public static void onServerConfigSet(ServerPlayer player, String key, String value) {
        if (!com.ccnrcom.rp.util.Permissions.canAdmin(player, com.ccnrcom.rp.util.Permissions.ADMIN_FACTION)) {
            service().sendError(player, "ccnr_rp.command.no_permission");
            return;
        }
        java.util.List<String> errors = com.ccnrcom.rp.config.CCNRRPConfig.set(key, value);
        if (!errors.isEmpty()) {
            service().sendError(player, "ccnr_rp.error.invalid_argument", String.join("; ", errors));
            return;
        }
        // 等级曲线/头顶标签等 serverconfig 全服生效：编辑者立即全量刷新，其余玩家异步广播
        broadcastConfigAll(player);
    }

    public static void onManagerSet(ServerPlayer player, String key, String value) {
        if (!com.ccnrcom.rp.util.Permissions.canAdmin(player, com.ccnrcom.rp.util.Permissions.ADMIN_FACTION)) {
            service().sendError(player, "ccnr_rp.command.no_permission");
            return;
        }
        if (CCNRRPMod.managerSettings == null) {
            service().sendError(player, "ccnr_rp.error.invalid_argument", "管理器未就绪");
            return;
        }
        List<String> errors = CCNRRPMod.managerSettings.set(key, value);
        if (!errors.isEmpty()) {
            service().sendError(player, "ccnr_rp.error.invalid_argument", String.join("; ", errors));
            return;
        }
        // serverconfig 全服生效：编辑者立即全量刷新，其余玩家异步广播
        broadcastConfigAll(player);
    }

    /**
     * 管理端变更后同步全员（异步）：主线程快照（玩家/用户表/Level 只读一次）→ 后台构建纯数据
     * （各配置文件只读一次，不再逐玩家读盘/序列化）→ 回主线程发包（docs/01 §9.4：发包回主线程）。
     * 连续多次变更时合并为最新一次（与头顶标签刷新同语义的 overload 保护）。
     */
    /** 全服广播（异步）。 */
    private void broadcastToAll() {
        broadcastToAll(null);
    }

    /** 全服广播（异步；skipUuid 非空时跳过该玩家——编辑者已由 broadcastConfigAll 同步刷新）。 */
    private void broadcastToAll(String skipUuid) {
        if (server == null || CCNRRPMod.users == null) {
            return;
        }
        List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());
        if (players.isEmpty()) {
            return;
        }
        // 主线程快照：逐玩家字段（用户表非线程安全）+ 全服共享数据（在职统计/场景名/头顶标签）
        List<UserSnapshot> snaps = new ArrayList<>(players.size());
        for (ServerPlayer p : players) {
            if (skipUuid != null && skipUuid.equals(p.getUUID().toString())) {
                continue;
            }
            snaps.add(snapshot(p));
        }
        JsonObject occupancy = occupancyJson();
        List<String> camScenes = com.ccnrcom.rp.cmdcam.CamSceneBridge.savedSceneNames(server.overworld());
        String tagsPayload = playerTagsJson().toString();
        BROADCASTER.execute(() -> {
            try {
                JsonObject sharedList = buildSharedListRoot(occupancy);
                JsonObject sharedMgr = buildSharedManagerRoot();
                String music = musicListJson();
                List<PendingPush> pending = new ArrayList<>(snaps.size());
                for (UserSnapshot s : snaps) {
                    JsonObject list = sharedList.deepCopy();
                    applyUserListFields(list, s);
                    JsonObject mgr = sharedMgr.deepCopy();
                    applyManagerUserFields(mgr, s, camScenes);
                    pending.add(new PendingPush(s.uuid(), list.toString(), mgr.toString(), music, tagsPayload));
                }
                pendingBroadcast = pending;
                if (server != null) {
                    server.execute(this::flushBroadcast);
                }
            } catch (Exception ignored) {
                // 构建失败丢弃本次广播（下次配置变更会重试）
            }
        });
    }

    /** 主线程发送待发的全服配置广播（每人 list+manager+music+tags，逐人校验在线）。 */
    private void flushBroadcast() {
        List<PendingPush> pending = pendingBroadcast;
        if (pending == null || server == null) {
            return;
        }
        pendingBroadcast = null;
        for (PendingPush pp : pending) {
            net.minecraft.server.level.ServerPlayer p =
                    server.getPlayerList().getPlayer(java.util.UUID.fromString(pp.uuid()));
            if (p == null
                    || p.connection == null
                    || p.connection.connection == null
                    || !p.connection.connection.isConnected()) {
                continue;
            }
            RpChannels.sendTo(p, new RpPackets.CharacterListS2C(pp.listPayload()));
            RpChannels.sendTo(p, new RpPackets.ManagerStateS2C(pp.managerPayload()));
            RpChannels.sendTo(p, new RpPackets.MusicListS2C(pp.musicPayload()));
            RpChannels.sendTo(p, new RpPackets.PlayerTagsS2C(pp.tagsPayload()));
        }
        com.ccnrcom.rp.assets.AssetLibrary.broadcastManifest();
    }

    /** 主线程快照逐玩家字段（用户表 HashMap 非线程安全 + 玩家访问，必须在主线程完成）。 */
    private static UserSnapshot snapshot(ServerPlayer player) {
        String uuid = player.getUUID().toString();
        return new UserSnapshot(
                uuid,
                com.ccnrcom.rp.util.Permissions.canAdmin(player, com.ccnrcom.rp.util.Permissions.ADMIN_FACTION),
                !isObserver(player),
                CCNRRPMod.users.userXp(uuid),
                CCNRRPMod.users.level(uuid),
                CCNRRPMod.users.anySupportRevive(uuid),
                CCNRRPMod.users.status(uuid).name().toLowerCase(java.util.Locale.ROOT),
                CCNRRPMod.users.professionId(uuid),
                CCNRRPMod.users.factionId(uuid),
                CCNRRPMod.users.cooldownUntil(uuid));
    }

    /** 在职统计快照（主线程构建：用户表非线程安全，后台只读快照嵌入共享载荷）。 */
    private static JsonObject occupancyJson() {
        JsonObject occ = new JsonObject();
        JsonObject occProf = new JsonObject();
        if (CCNRRPMod.users != null && CCNRRPMod.factions != null) {
            for (String pid : CCNRRPMod.factions.professionIds()) {
                occProf.addProperty(pid, CCNRRPMod.users.aliveCountByProfession(pid));
            }
            JsonObject occFac = new JsonObject();
            for (String fid : CCNRRPMod.factions.graph().factions().keySet()) {
                occFac.addProperty(fid, CCNRRPMod.users.aliveCountByFaction(fid));
            }
            occ.add("professions", occProf);
            occ.add("factions", occFac);
        }
        return occ;
    }

    private static void sendManagerState(ServerPlayer player) {
        if (CCNRRPMod.managerSettings == null) {
            return;
        }
        JsonObject pay = buildSharedManagerRoot();
        applyManagerUserFields(
                pay, snapshot(player), com.ccnrcom.rp.cmdcam.CamSceneBridge.savedSceneNames(player.level()));
        RpChannels.sendTo(player, new RpPackets.ManagerStateS2C(pay.toString()));
        RpChannels.sendTo(player, new RpPackets.MusicListS2C(musicListJson()));
    }

    /** 管理器状态共享部分（纯数据构建，后台线程只读；各配置文件只读一次，不再逐玩家读盘）。 */
    private static JsonObject buildSharedManagerRoot() {
        JsonObject pay = new JsonObject();
        if (CCNRRPMod.managerSettings != null) {
            pay.add("settings", CCNRRPMod.managerSettings.toJson());
        }
        JsonArray eva = new JsonArray();
        com.ccnrcom.rp.util.ConfigCrud.items("events.json", "events").forEach(eva::add);
        pay.add("events", eva);
        JsonArray pha = new JsonArray();
        com.ccnrcom.rp.util.ConfigCrud.items("phases.json", "phases").forEach(pha::add);
        pay.add("phases", pha);
        JsonArray wav = new JsonArray();
        com.ccnrcom.rp.util.ConfigCrud.items("spawn_waves.json", "waves").forEach(wav::add);
        pay.add("waves", wav);
        JsonArray lim = new JsonArray();
        com.ccnrcom.rp.util.ConfigCrud.items("limits.json", "rules").forEach(lim::add);
        pay.add("limits", lim);
        // serverconfig 程序化设定（管理面板「设定」标签）
        pay.add("serverConfig", com.ccnrcom.rp.config.CCNRRPConfig.values());
        return pay;
    }

    /** 管理器状态逐玩家字段：admin + CMDCam 场景名补全列表。 */
    private static void applyManagerUserFields(JsonObject pay, UserSnapshot s, List<String> camScenes) {
        pay.addProperty("admin", s.admin());
        JsonArray cams = new JsonArray();
        for (String c : camScenes) {
            cams.add(c);
        }
        pay.add("camScenes", cams);
    }

    public static void onManagerCrud(ServerPlayer player, String kind, String action, String payload) {
        if (!com.ccnrcom.rp.util.Permissions.canAdmin(player, com.ccnrcom.rp.util.Permissions.ADMIN_FACTION)) {
            service().sendError(player, "ccnr_rp.command.no_permission");
            return;
        }
        if (CCNRRPMod.factions == null) {
            service().sendError(player, "ccnr_rp.error.invalid_argument", "配置管理器未就绪");
            return;
        }
        JsonObject p;
        try {
            p = com.ccnrcom.rp.util.JsonUtil.GSON.fromJson(payload, JsonObject.class);
        } catch (Exception e) {
            service().sendError(player, "ccnr_rp.error.invalid_argument", "载荷解析失败");
            return;
        }
        if (p == null) {
            service().sendError(player, "ccnr_rp.error.invalid_argument", "载荷为空");
            return;
        }
        List<String> errors;
        switch (kind) {
            case "profession" -> {
                String id = str(p, "id", "");
                if ("delete".equals(action)) {
                    errors = CCNRRPMod.factions.deleteProfession(id);
                } else {
                    // 表单未编辑的字段（自部署/装备 loadout/无线电禁用）从现有定义继承，
                    // 保存 = 表单输出覆盖到原数据上，防止空值把原字段清空（如装备被 emptyLoadout 覆盖）
                    com.ccnrcom.rp.faction.FactionProfessions.ProfessionSave save =
                            com.ccnrcom.rp.faction.FactionProfessions.resolveSave(
                                    p, CCNRRPMod.factions.findProfession(id).orElse(null));
                    errors = CCNRRPMod.factions.upsertProfession(
                            save.id(),
                            save.name(),
                            save.factionId(),
                            save.selfDeploy(),
                            save.unlockLevel(),
                            save.loadout(),
                            save.music(),
                            save.profile(),
                            save.cmdcamScene(),
                            save.radio(),
                            save.radioDisabled());
                }
            }
            case "faction" -> {
                String id = str(p, "id", "");
                String name = str(p, "name", id);
                int tier = p.has("tier") ? p.get("tier").getAsInt() : 2;
                boolean black = bool(p, "cinematicBlackScreen", true);
                boolean compact = bool(p, "cinematicCompact", false);
                switch (action) {
                    case "create" -> errors = CCNRRPMod.factions.createFaction(
                            id,
                            name,
                            str(p, "color", "#FFFFFF"),
                            str(p, "description", ""),
                            str(p, "icon", "hex"),
                            tier,
                            str(p, "music", ""),
                            str(p, "cmdcamScene", ""),
                            black,
                            compact);
                    case "update" -> errors = CCNRRPMod.factions.updateFaction(
                            id,
                            name,
                            str(p, "color", "#FFFFFF"),
                            str(p, "description", ""),
                            str(p, "icon", "hex"),
                            tier,
                            str(p, "music", ""),
                            str(p, "cmdcamScene", ""),
                            black,
                            compact);
                    case "delete" -> errors = CCNRRPMod.factions.deleteFaction(id);
                    default -> errors = List.of("未知操作: " + action);
                }
            }
            case "group" -> {
                String id = str(p, "id", "");
                java.util.List<String> members = new java.util.ArrayList<>();
                if (p.has("memberIds") && p.get("memberIds").isJsonArray()) {
                    for (com.google.gson.JsonElement e : p.getAsJsonArray("memberIds")) {
                        members.add(e.getAsString());
                    }
                }
                switch (action) {
                    case "create" -> errors = CCNRRPMod.factions.createGroup(id, members);
                    case "update" -> errors = CCNRRPMod.factions.updateGroup(id, members);
                    case "delete" -> errors = CCNRRPMod.factions.deleteGroup(id);
                    default -> errors = List.of("未知操作: " + action);
                }
            }
                // 无线电编辑（独立弹窗保存）：kind=radio-faction（阵营 radio）/ radio-profession（职业 radio + radioDisabled）
            case "radio-faction" -> {
                String id = str(p, "id", "");
                com.google.gson.JsonObject radio =
                        p.has("radio") && p.get("radio").isJsonObject() ? p.getAsJsonObject("radio") : null;
                errors = CCNRRPMod.factions.setFactionRadio(id, radio);
            }
            case "radio-profession" -> {
                String id = str(p, "id", "");
                com.google.gson.JsonObject radio =
                        p.has("radio") && p.get("radio").isJsonObject() ? p.getAsJsonObject("radio") : null;
                boolean radioDisabled =
                        p.has("radioDisabled") && p.get("radioDisabled").getAsBoolean();
                var def = CCNRRPMod.factions.findProfession(id).orElse(null);
                if (def == null) {
                    errors = List.of("未找到职业: " + id);
                } else {
                    errors = CCNRRPMod.factions.upsertProfession(
                            str(def, "id", id),
                            com.ccnrcom.rp.faction.FactionProfessions.idsSafeName(def),
                            com.ccnrcom.rp.faction.FactionProfessions.factionId(def),
                            com.ccnrcom.rp.faction.FactionProfessions.selfDeploy(def),
                            com.ccnrcom.rp.faction.FactionProfessions.unlockLevel(def),
                            com.ccnrcom.rp.faction.FactionProfessions.loadout(def),
                            com.ccnrcom.rp.faction.FactionProfessions.music(def),
                            com.ccnrcom.rp.faction.FactionProfessions.profile(def),
                            com.ccnrcom.rp.faction.FactionProfessions.cmdcamScene(def),
                            radio,
                            radioDisabled);
                }
            }
            case "event" -> {
                String id = str(p, "id", "");
                errors = crudArray("events.json", "events", action, id, p, o -> {
                    if (!o.has("enabled")) {
                        o.addProperty("enabled", true);
                    }
                    if (!o.has("durationSeconds")) {
                        o.addProperty("durationSeconds", 0);
                    }
                    if (!o.has("triggers")) {
                        o.add("triggers", new JsonArray());
                    }
                    if (!o.has("tasks")) {
                        o.add("tasks", new JsonArray());
                    }
                });
            }
            case "phase" -> {
                String id = str(p, "id", "");
                errors = crudArray("phases.json", "phases", action, id, p, o -> {
                    if (!o.has("order")) {
                        o.addProperty("order", 0);
                    }
                    if (!o.has("durationMinutes")) {
                        o.addProperty("durationMinutes", 30);
                    }
                });
            }
            case "wave" -> {
                String id = str(p, "id", "");
                errors = crudArray("spawn_waves.json", "waves", action, id, p, o -> {
                    if (!o.has("mode")) {
                        o.addProperty("mode", "BOTH");
                    }
                    if (!o.has("enabled")) {
                        o.addProperty("enabled", true);
                    }
                    if (!o.has("count")) {
                        o.addProperty("count", 1);
                    }
                    if (!o.has("minLevel")) {
                        o.addProperty("minLevel", 0);
                    }
                    if (!o.has("teamIds")) {
                        o.add("teamIds", new JsonArray());
                    }
                    if (!o.has("professionIds")) {
                        o.add("professionIds", new JsonArray());
                    }
                    if (!o.has("factionIds")) {
                        o.add("factionIds", new JsonArray());
                    }
                    if (!o.has("recruitTimeoutSeconds")) {
                        o.addProperty("recruitTimeoutSeconds", 60);
                    }
                });
            }
            case "limit" -> {
                String id = str(p, "id", "");
                errors = crudArray("limits.json", "rules", action, id, p, o -> {
                    if (!o.has("type")) {
                        o.addProperty("type", "GLOBAL");
                    }
                    if (!o.has("target")) {
                        o.addProperty("target", "");
                    }
                    if (!o.has("limit")) {
                        o.addProperty("limit", 1);
                    }
                });
            }
            default -> errors = List.of("未知类型: " + kind);
        }
        if (!errors.isEmpty()) {
            service().sendError(player, "ccnr_rp.error.invalid_argument", String.join("; ", errors));
            return;
        }
        if ("event".equals(kind) || "phase".equals(kind)) {
            if (CCNRRPMod.eventManager != null) {
                CCNRRPMod.eventManager.reload();
            }
        } else if ("wave".equals(kind)) {
            if (CCNRRPMod.spawnFramework != null) {
                CCNRRPMod.spawnFramework.reload();
            }
        }
        service().sendError(player, "ccnr_rp.manager.crud.ok", kind, action);
        broadcastConfigAll(player); // 编辑者立即全量刷新，其余玩家异步广播
    }

    /** 管理端影响预检（管理员）：统计用户（按当前职位/阵营）与波/事件/阶段引用。 */
    public static void onManagerImpact(ServerPlayer player, String kind, String action, String payloadJson) {
        if (player == null) {
            return;
        }
        if (!com.ccnrcom.rp.util.Permissions.canAdmin(player, com.ccnrcom.rp.util.Permissions.ADMIN_FACTION)) {
            service().sendError(player, "ccnr_rp.command.no_permission");
            return;
        }
        JsonObject p;
        try {
            p = com.ccnrcom.rp.util.JsonUtil.GSON.fromJson(payloadJson, JsonObject.class);
        } catch (Exception e) {
            p = null;
        }
        if (p == null) {
            service().sendError(player, "ccnr_rp.error.invalid_argument", "载荷解析失败");
            return;
        }
        String id = str(p, "id", "");
        List<String> lines = new ArrayList<>();
        List<String> users = new ArrayList<>();
        List<String> waves = new ArrayList<>();
        List<String> profs = new ArrayList<>();
        List<String> events = new ArrayList<>();
        List<String> phases = new ArrayList<>();
        boolean rename = "update".equals(action);
        if ("profession".equals(kind)) {
            if (updateInvolvesIdChange("profession", id, p)) {
                rename = true;
            }
            for (String uuid : CCNRRPMod.users.uuids()) {
                if (id.equals(CCNRRPMod.users.professionId(uuid))) {
                    users.add(uuid);
                }
            }
            waves = referenceWaves("profession", id);
            profs = List.of();
            stepsReferencing("profession", id, events, phases, waves);
        } else if ("faction".equals(kind)) {
            for (String uuid : CCNRRPMod.users.uuids()) {
                if (id.equals(CCNRRPMod.users.factionId(uuid))) {
                    users.add(uuid);
                }
            }
            for (String pid : CCNRRPMod.factions.professionIds()) {
                var d = CCNRRPMod.factions.findProfession(pid).orElse(null);
                if (d != null && id.equals(com.ccnrcom.rp.faction.FactionProfessions.factionId(d))) {
                    profs.add(com.ccnrcom.rp.faction.FactionProfessions.idsSafeName(d));
                }
            }
            waves = referenceWaves("faction", id);
            stepsReferencing("faction", id, events, phases, waves);
        } else if ("wave".equals(kind)) {
            for (var e : com.ccnrcom.rp.util.ConfigCrud.items("events.json", "events")) {
                JsonObject hooks = e.has("hooks") && e.get("hooks").isJsonObject() ? e.getAsJsonObject("hooks") : null;
                if (hooks != null && id.equals(str(hooks, "spawnWave", ""))) {
                    events.add(str(e, "id", "?"));
                }
                if (id.equals(str(e, "spawnWave", ""))) {
                    events.add(str(e, "id", "?"));
                }
            }
            stepsReferencing("wave", id, events, phases, waves);
        } else if ("event".equals(kind)) {
            stepsReferencing("event", id, events, phases, waves);
        } else if ("phase".equals(kind)) {
            stepsReferencing("phase", id, events, phases, waves);
        }
        if (!users.isEmpty()) {
            lines.add("用户(" + users.size() + "): " + String.join(", ", users.subList(0, Math.min(6, users.size())))
                    + (users.size() > 6 ? "…" : ""));
        }
        if (!profs.isEmpty()) {
            lines.add("职业(" + profs.size() + "): " + String.join(", ", profs.subList(0, Math.min(6, profs.size())))
                    + (profs.size() > 6 ? "…" : ""));
        }
        if (!waves.isEmpty()) {
            lines.add("刷新波(" + waves.size() + "): " + String.join(", ", waves.subList(0, Math.min(6, waves.size())))
                    + (waves.size() > 6 ? "…" : ""));
        }
        if (!events.isEmpty()) {
            lines.add("事件(" + events.size() + "): " + String.join(", ", events.subList(0, Math.min(6, events.size())))
                    + (events.size() > 6 ? "…" : ""));
        }
        if (!phases.isEmpty()) {
            lines.add("阶段(" + phases.size() + "): " + String.join(", ", phases.subList(0, Math.min(6, phases.size())))
                    + (phases.size() > 6 ? "…" : ""));
        }
        if (rename && id.length() > 0) {
            // 重命名提示
        }
        JsonObject resp = new JsonObject();
        resp.addProperty("kind", kind);
        resp.addProperty("action", action);
        resp.addProperty("id", id);
        JsonArray arr = new JsonArray();
        for (String l : lines) {
            arr.add(l);
        }
        resp.add("lines", arr);
        RpChannels.sendTo(player, new RpPackets.ManagerImpactS2C(resp.toString()));
    }

    private static boolean updateInvolvesIdChange(String kind, String id, JsonObject p) {
        String oldId = str(p, "_oldId", "");
        return !oldId.isBlank() && !oldId.equals(id);
    }

    private static List<String> referenceWaves(String kind, String id) {
        List<String> out = new ArrayList<>();
        for (JsonObject w : com.ccnrcom.rp.util.ConfigCrud.items("spawn_waves.json", "waves")) {
            JsonArray arr = w.has("professionIds") && w.get("professionIds").isJsonArray()
                    ? w.getAsJsonArray("professionIds")
                    : w.has("factionIds") && w.get("factionIds").isJsonArray() ? w.getAsJsonArray("factionIds") : null;
            if ("faction".equals(kind)
                    && w.has("factionIds")
                    && w.get("factionIds").isJsonArray()) {
                arr = w.getAsJsonArray("factionIds");
            }
            if (arr == null) {
                continue;
            }
            for (var e : arr) {
                if (id.equals(e.getAsString())) {
                    out.add(str(w, "id", "?"));
                    break;
                }
            }
        }
        return out;
    }

    private static void stepsReferencing(
            String kind, String id, List<String> events, List<String> phases, List<String> waves) {
        scanSteps(com.ccnrcom.rp.util.ConfigCrud.items("events.json", "events"), "ev", kind, id, events, phases, waves);
        scanSteps(com.ccnrcom.rp.util.ConfigCrud.items("phases.json", "phases"), "ph", kind, id, events, phases, waves);
        scanSteps(
                com.ccnrcom.rp.util.ConfigCrud.items("spawn_waves.json", "waves"),
                "wv",
                kind,
                id,
                events,
                phases,
                waves);
    }

    private static void scanSteps(
            List<JsonObject> items,
            String prefix,
            String kind,
            String id,
            List<String> events,
            List<String> phases,
            List<String> waves) {
        for (JsonObject item : items) {
            if (!item.has("sequence") || !item.get("sequence").isJsonArray()) {
                continue;
            }
            for (var e : item.getAsJsonArray("sequence")) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject s = e.getAsJsonObject();
                String type = str(s, "type", "");
                boolean hit = false;
                if ("profession".equals(kind) && "FORCE_PICK".equals(type)) {
                    hit = csvContains(str(s, "professions", ""), id);
                } else if ("faction".equals(kind) && "FORCE_PICK".equals(type)) {
                    hit = id.equals(str(s, "faction", ""));
                } else if ("wave".equals(kind) && "WAVE".equals(type)) {
                    hit = id.equals(str(s, "wave", ""));
                }
                if (!hit) {
                    continue;
                }
                String name = str(item, "id", "?");
                if ("ev".equals(prefix)) {
                    events.add(name);
                } else if ("ph".equals(prefix)) {
                    phases.add(name);
                } else {
                    waves.add(name);
                }
                break;
            }
        }
    }

    private static boolean csvContains(String csv, String id) {
        if (csv == null || csv.isBlank()) {
            return false;
        }
        for (String t : csv.split(",")) {
            if (id.equals(t.trim())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> crudArray(
            String file,
            String arrayKey,
            String action,
            String id,
            JsonObject payload,
            java.util.function.Consumer<JsonObject> fillDefaults) {
        if ("delete".equals(action)) {
            return com.ccnrcom.rp.util.ConfigCrud.delete(file, arrayKey, id);
        }
        JsonObject item = payload.deepCopy();
        item.addProperty("id", id);
        fillDefaults.accept(item);
        return com.ccnrcom.rp.util.ConfigCrud.upsert(file, arrayKey, item);
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        return o.has(key)
                        && o.get(key).isJsonPrimitive()
                        && o.get(key).getAsJsonPrimitive().isBoolean()
                ? o.get(key).getAsBoolean()
                : def;
    }

    /** 管理端操作「刷给自己」：把所选职业的装备与人物身份一起赋予执行者（含阵营/状态/疏散重置）。 */
    public static void onAdminSelfProfession(ServerPlayer player, String professionId) {
        if (player == null || CCNRRPMod.factions == null) {
            return;
        }
        if (!com.ccnrcom.rp.util.Permissions.canAdmin(player, com.ccnrcom.rp.util.Permissions.ADMIN_PROFESSION)) {
            service().sendError(player, "ccnr_rp.command.no_permission");
            return;
        }
        var def = CCNRRPMod.factions.findProfession(professionId).orElse(null);
        if (def == null) {
            service().sendError(player, "ccnr_rp.character.error.profession", professionId);
            return;
        }
        if (CCNRRPMod.spawnFramework == null || CCNRRPMod.users == null) {
            service().sendError(player, "ccnr_rp.error.invalid_argument", "部署框架未就绪");
            return;
        }
        // 统一部署入口（FORCE_DEPLOY 强制部署不论存活；SKIP_CINEMATIC/NO_MUSIC 不播入场动画与音乐；QUIET 不广播）
        // 会完成：装备发放 + 传送 + 用户身份（职位/阵营/ALIVE/冷却清零/evac 重置）+ 客户端档案刷新
        boolean ok = CCNRRPMod.spawnFramework.deploy(
                player,
                professionId,
                CCNRRPMod.spawnFramework.defaultSelfWave(),
                com.ccnrcom.rp.spawn.DeployFlag.of(
                        com.ccnrcom.rp.spawn.DeployFlag.FORCE_DEPLOY,
                        com.ccnrcom.rp.spawn.DeployFlag.SKIP_CINEMATIC,
                        com.ccnrcom.rp.spawn.DeployFlag.NO_MUSIC,
                        com.ccnrcom.rp.spawn.DeployFlag.QUIET,
                        com.ccnrcom.rp.spawn.DeployFlag.LIMIT_SKIP));
        if (!ok) {
            service().sendError(player, "ccnr_rp.spawn.error.self_deploy");
            return;
        }
        service()
                .sendError(
                        player,
                        "ccnr_rp.admin.profession.applied",
                        com.ccnrcom.rp.faction.FactionProfessions.idsSafeName(def),
                        com.ccnrcom.rp.faction.FactionProfessions.factionId(def));
    }

    /** 管理端操作「全量保存职业装备」：把管理员当前背包/护甲/副手（含 NBT）存为所选职业的 loadout。 */
    public static void onAdminSaveProfessionFull(ServerPlayer player, String professionId) {
        if (player == null || CCNRRPMod.factions == null) {
            return;
        }
        if (!com.ccnrcom.rp.util.Permissions.canAdmin(player, com.ccnrcom.rp.util.Permissions.ADMIN_PROFESSION)) {
            service().sendError(player, "ccnr_rp.command.no_permission");
            return;
        }
        var mgr = CCNRRPMod.factions;
        var def = mgr.findProfession(professionId).orElse(null);
        if (def == null) {
            service().sendError(player, "ccnr_rp.character.error.profession", professionId);
            return;
        }
        com.google.gson.JsonObject loadout = com.ccnrcom.rp.profession.LoadoutManager.capture(player, true);
        // 只更新 loadout 字段（单字段接管）：不触碰音乐/项目简历/CMDCam 场景/无线电等基础配置
        var errors = mgr.setProfessionLoadout(professionId, loadout);
        if (!errors.isEmpty()) {
            service().sendError(player, "ccnr_rp.profession.error.config", String.join("; ", errors));
            return;
        }
        int slots = loadout.getAsJsonArray("inventory").size()
                + loadout.getAsJsonArray("armor").size()
                + (loadout.has("offhand")
                                && loadout.get("offhand").isJsonObject()
                                && loadout.getAsJsonObject("offhand").size() > 0
                        ? 1
                        : 0);
        broadcastConfigAll(player); // 装备 loadout 属配置数据：编辑者立即全量刷新，其余玩家异步广播
        service().sendError(player, "ccnr_rp.profession.saved_full", String.valueOf(slots), professionId);
    }

    /** 管理端操作「设置阵营出生点」：写入该阵营的出生点列表与分布规则（SPREAD/SINGLE）。 */
    public static void onAdminFactionSpawn(ServerPlayer player, String factionId, String rule, String pointsJson) {
        if (player == null || CCNRRPMod.factions == null) {
            return;
        }
        if (!com.ccnrcom.rp.util.Permissions.canAdmin(player, com.ccnrcom.rp.util.Permissions.ADMIN_FACTION)) {
            service().sendError(player, "ccnr_rp.command.no_permission");
            return;
        }
        List<com.ccnrcom.rp.faction.FactionManager.SpawnPoint> pts = new java.util.ArrayList<>();
        try {
            com.google.gson.JsonArray arr =
                    com.google.gson.JsonParser.parseString(pointsJson).getAsJsonArray();
            for (com.google.gson.JsonElement e : arr) {
                com.google.gson.JsonObject o = e.getAsJsonObject();
                pts.add(new com.ccnrcom.rp.faction.FactionManager.SpawnPoint(
                        o.has("x") ? o.get("x").getAsDouble() : 0,
                        o.has("y") ? o.get("y").getAsDouble() : 64,
                        o.has("z") ? o.get("z").getAsDouble() : 0,
                        o.has("dim") && !o.get("dim").isJsonNull()
                                ? o.get("dim").getAsString()
                                : "minecraft:overworld"));
            }
        } catch (Exception ex) {
            service().sendError(player, "ccnr_rp.profession.error.config", "出生点数据无效");
            return;
        }
        var errors = CCNRRPMod.factions.setFactionSpawn(factionId, rule, pts);
        if (!errors.isEmpty()) {
            service().sendError(player, "ccnr_rp.profession.error.config", String.join("; ", errors));
            return;
        }
        broadcastConfigAll(player); // 部署点属配置数据：编辑者立即全量刷新，其余玩家异步广播
        service().sendError(player, "ccnr_rp.gui.admin.spawn.saved", factionId, String.valueOf(pts.size()));
    }

    /** 管理端操作「设置职业部署点」：写入该职业的部署点列表与分布规则（SPREAD/SINGLE）。 */
    public static void onAdminProfessionSpawn(
            ServerPlayer player, String professionId, String rule, String pointsJson) {
        if (player == null || CCNRRPMod.factions == null) {
            return;
        }
        if (!com.ccnrcom.rp.util.Permissions.canAdmin(player, com.ccnrcom.rp.util.Permissions.ADMIN_FACTION)) {
            service().sendError(player, "ccnr_rp.command.no_permission");
            return;
        }
        List<com.ccnrcom.rp.faction.FactionManager.SpawnPoint> pts = new java.util.ArrayList<>();
        try {
            com.google.gson.JsonArray arr =
                    com.google.gson.JsonParser.parseString(pointsJson).getAsJsonArray();
            for (com.google.gson.JsonElement e : arr) {
                com.google.gson.JsonObject o = e.getAsJsonObject();
                pts.add(new com.ccnrcom.rp.faction.FactionManager.SpawnPoint(
                        o.has("x") ? o.get("x").getAsDouble() : 0,
                        o.has("y") ? o.get("y").getAsDouble() : 64,
                        o.has("z") ? o.get("z").getAsDouble() : 0,
                        o.has("dim") && !o.get("dim").isJsonNull()
                                ? o.get("dim").getAsString()
                                : "minecraft:overworld"));
            }
        } catch (Exception ex) {
            service().sendError(player, "ccnr_rp.profession.error.config", "部署点数据无效");
            return;
        }
        var errors = CCNRRPMod.factions.setProfessionSpawn(professionId, rule, pts);
        if (!errors.isEmpty()) {
            service().sendError(player, "ccnr_rp.profession.error.config", String.join("; ", errors));
            return;
        }
        broadcastConfigAll(player); // 职业部署点属配置数据：编辑者立即全量刷新，其余玩家异步广播
        service().sendError(player, "ccnr_rp.gui.admin.spawn.saved", professionId, String.valueOf(pts.size()));
    }

    /** 管理端操作「传送到部署点/复活点」（复活点管理每行「传送」按钮）：按维度解析 Level 后传送，便于就地检查。 */
    public static void onAdminTeleport(ServerPlayer player, double x, double y, double z, String dim) {
        if (player == null) {
            return;
        }
        if (!com.ccnrcom.rp.util.Permissions.canAdmin(player, com.ccnrcom.rp.util.Permissions.ADMIN_FACTION)) {
            service().sendError(player, "ccnr_rp.command.no_permission");
            return;
        }
        net.minecraft.resources.ResourceLocation dimLoc = net.minecraft.resources.ResourceLocation.tryParse(dim);
        net.minecraft.server.level.ServerLevel level = dimLoc == null
                ? null
                : player.server.getLevel(net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION, dimLoc));
        if (level == null) {
            level = player.server.overworld();
        }
        player.teleportTo(level, x + 0.5, y, z + 0.5, 0, 0);
    }

    // ---------- 音乐管理（管理员上传） ----------

    public static void onMusicPart(ServerPlayer player, RpPackets.MusicUploadPartC2S msg) {
        if (player == null
                || !com.ccnrcom.rp.util.Permissions.canAdmin(player, com.ccnrcom.rp.util.Permissions.ADMIN_FACTION)) {
            return;
        }
        CharacterService svc = service();
        if (com.ccnrcom.rp.music.MusicStore.validateName(msg.name) != null) {
            svc.musicUploads.remove(player.getUUID());
            svc.sendError(player, "ccnr_rp.gui.admin.music.fail", "文件名非法");
            return;
        }
        MusicUpload up = svc.musicUploads.getOrDefault(player.getUUID(), new MusicUpload(msg.name, new byte[0], 0));
        if (!up.name().equals(msg.name)) {
            up = new MusicUpload(msg.name, new byte[0], 0);
        }
        if (up.parts().length + msg.data.length > com.ccnrcom.rp.music.MusicStore.MAX_BYTES) {
            svc.musicUploads.remove(player.getUUID());
            svc.sendError(player, "ccnr_rp.gui.admin.music.fail", "分片超限");
            return;
        }
        byte[] next = new byte[up.parts().length + msg.data.length];
        System.arraycopy(up.parts(), 0, next, 0, up.parts().length);
        System.arraycopy(msg.data, 0, next, up.parts().length, msg.data.length);
        svc.musicUploads.put(player.getUUID(), new MusicUpload(msg.name, next, msg.index + 1));
    }

    public static void onMusicCommit(ServerPlayer player, RpPackets.MusicUploadCommitC2S msg) {
        if (player == null
                || !com.ccnrcom.rp.util.Permissions.canAdmin(player, com.ccnrcom.rp.util.Permissions.ADMIN_FACTION)) {
            return;
        }
        CharacterService svc = service();
        MusicUpload up = svc.musicUploads.remove(player.getUUID());
        if (up == null || !up.name().equals(msg.name) || up.parts().length != msg.expectedSize) {
            svc.sendError(player, "ccnr_rp.gui.admin.music.fail", "数据不完整");
            return;
        }
        String nameError = com.ccnrcom.rp.music.MusicStore.validateName(up.name());
        if (nameError != null) {
            svc.sendError(player, "ccnr_rp.gui.admin.music.fail", nameError);
            return;
        }
        String contentError = com.ccnrcom.rp.music.MusicStore.validate(up.parts());
        if (contentError != null) {
            svc.sendError(player, "ccnr_rp.gui.admin.music.fail", contentError);
            return;
        }
        try {
            com.ccnrcom.rp.music.MusicStore.save(up.name(), up.parts());
            RpChannels.sendToAll(new RpPackets.MusicListS2C(musicListJson()));
            com.ccnrcom.rp.assets.AssetLibrary.broadcastManifest();
            svc.sendError(player, "ccnr_rp.gui.admin.music.ok", up.name());
        } catch (Exception e) {
            svc.sendError(player, "ccnr_rp.gui.admin.music.fail", e.getMessage());
        }
    }

    public static String musicListJson() {
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        for (String name : com.ccnrcom.rp.music.MusicStore.list()) {
            a.add(name);
        }
        return a.toString();
    }

    // ---------- 用户档案列表（S2C） ----------

    /** 阵营出生点 → 客户端 JSON（配置存在则返回对象，否则 null）。 */
    private static com.google.gson.JsonObject factionSpawnJson(String factionId) {
        if (CCNRRPMod.factions == null) {
            return null;
        }
        var fs = CCNRRPMod.factions.factionSpawn(factionId);
        if (fs == null) {
            return null;
        }
        com.google.gson.JsonObject sp = new com.google.gson.JsonObject();
        sp.addProperty("rule", fs.rule());
        com.google.gson.JsonArray pts = new com.google.gson.JsonArray();
        for (var p : fs.points()) {
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("x", p.x());
            o.addProperty("y", p.y());
            o.addProperty("z", p.z());
            o.addProperty("dim", p.dim());
            pts.add(o);
        }
        sp.add("points", pts);
        return sp;
    }

    /** 职业部署点（复活点）→ 客户端 JSON（配置存在则返回对象，否则 null）——管理面板复活点弹窗回显依赖。 */
    private static com.google.gson.JsonObject professionSpawnJson(JsonObject def) {
        var fs = com.ccnrcom.rp.faction.FactionProfessions.spawn(def);
        if (fs == null) {
            return null;
        }
        com.google.gson.JsonObject sp = new com.google.gson.JsonObject();
        sp.addProperty("rule", fs.rule());
        com.google.gson.JsonArray pts = new com.google.gson.JsonArray();
        for (var p : fs.points()) {
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("x", p.x());
            o.addProperty("y", p.y());
            o.addProperty("z", p.z());
            o.addProperty("dim", p.dim());
            pts.add(o);
        }
        sp.add("points", pts);
        return sp;
    }

    /**
     * 构建并下发用户档案列表（K 面板/客户端全量初始化）：
     * {factions, professions(含解锁等级/装备/音乐/简历), settings, admin, userXp, userLevel,
     *  anySupportRevive, status, professionId, factionId, cooldownUntil, levelBase, levelPow, panelLocked}。
     * 单人同步路径（登录/请求/部署）：共享部分主线程构建一次，逐玩家只补少量字段。
     */
    public void sendList(ServerPlayer player) {
        JsonObject root = buildSharedListRoot(occupancyJson());
        applyUserListFields(root, snapshot(player));
        RpChannels.sendTo(player, new RpPackets.CharacterListS2C(root.toString()));
        // 全玩家头顶标签数据（旁观者视角显示其他玩家的阵营/职业/等级）
        sendPlayerTags(player);
    }

    /** 档案列表共享部分（纯数据构建：阵营/职业/关系/限制/设置/等级曲线等，全服同一份）。 */
    private static JsonObject buildSharedListRoot(JsonObject occupancy) {
        JsonObject root = new JsonObject();
        JsonArray fa = new JsonArray();
        if (CCNRRPMod.factions != null) {
            CCNRRPMod.factions.graph().factions().values().forEach(f -> {
                JsonObject o = new JsonObject();
                o.addProperty("id", f.id());
                o.addProperty("name", f.name());
                o.addProperty("color", f.color());
                o.addProperty("icon", f.icon());
                o.addProperty("tier", f.tier());
                o.addProperty("description", f.description());
                o.addProperty("music", f.music());
                o.addProperty("cmdcamScene", f.cmdcamScene() == null ? "" : f.cmdcamScene());
                o.addProperty("cinematicBlackScreen", f.cinematicBlackScreen());
                o.addProperty("cinematicCompact", f.cinematicCompact());
                com.google.gson.JsonObject facRadio = CCNRRPMod.factions.factionRadio(f.id());
                if (com.ccnrcom.rp.faction.FactionProfessions.hasRadioLines(facRadio)) {
                    o.add("radio", facRadio.deepCopy());
                }
                JsonObject spawn = factionSpawnJson(f.id());
                if (spawn != null) {
                    o.add("spawn", spawn);
                }
                fa.add(o);
            });
        }
        root.add("factions", fa);
        // 阵营组（管理面板组编辑器枚举用）：{id, members[]}，服务端顶层数组，客户端只读列举
        JsonArray grps = new JsonArray();
        if (CCNRRPMod.factions != null) {
            for (com.ccnrcom.rp.faction.FactionModels.FactionGroup g :
                    CCNRRPMod.factions.graph().groups().values()) {
                JsonObject o = new JsonObject();
                o.addProperty("id", g.id());
                JsonArray members = new JsonArray();
                g.memberIds().forEach(members::add);
                o.add("members", members);
                grps.add(o);
            }
        }
        root.add("groups", grps);
        // 关系测定图数据：已解析的阵营对边（a<b 去重，含生效类型；白=中立/红=敌对/绿=友好）
        JsonArray rela = new JsonArray();
        if (CCNRRPMod.factions != null) {
            for (com.ccnrcom.rp.faction.FactionModels.RelationEdge e :
                    CCNRRPMod.factions.graph().edges()) {
                JsonObject o = new JsonObject();
                o.addProperty("a", e.a());
                o.addProperty("b", e.b());
                o.addProperty("type", e.type().name().toLowerCase(java.util.Locale.ROOT));
                rela.add(o);
            }
        }
        root.add("relations", rela);
        // 原始关系规则（管理面板编辑用）：{from[], to?, type}，含内部关系（省略 to）与组引用
        JsonArray relRules = new JsonArray();
        if (CCNRRPMod.factions != null) {
            for (com.ccnrcom.rp.faction.FactionModels.RelationRule rr :
                    CCNRRPMod.factions.graph().rules()) {
                JsonObject o = new JsonObject();
                JsonArray from = new JsonArray();
                rr.from().forEach(from::add);
                JsonArray to = new JsonArray();
                rr.to().forEach(to::add);
                o.add("from", from);
                o.add("to", to);
                o.addProperty("type", rr.type().name().toLowerCase(java.util.Locale.ROOT));
                relRules.add(o);
            }
        }
        root.add("relationRules", relRules);
        JsonArray pa = new JsonArray();
        if (CCNRRPMod.factions != null) {
            for (String pid : CCNRRPMod.factions.professionIds()) {
                CCNRRPMod.factions.findProfession(pid).ifPresent(def -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("id", pid);
                    o.addProperty("name", com.ccnrcom.rp.faction.FactionProfessions.idsSafeName(def));
                    o.addProperty("factionId", com.ccnrcom.rp.faction.FactionProfessions.factionId(def));
                    o.addProperty("unlockLevel", com.ccnrcom.rp.faction.FactionProfessions.unlockLevel(def));
                    o.addProperty("music", com.ccnrcom.rp.faction.FactionProfessions.music(def));
                    o.addProperty("profile", com.ccnrcom.rp.faction.FactionProfessions.profile(def));
                    o.addProperty("cmdcamScene", com.ccnrcom.rp.faction.FactionProfessions.cmdcamScene(def));
                    JsonObject profRadio = com.ccnrcom.rp.faction.FactionProfessions.radio(def);
                    if (com.ccnrcom.rp.faction.FactionProfessions.hasRadioLines(profRadio)) {
                        o.add("radio", profRadio.deepCopy());
                    }
                    o.addProperty("radioDisabled", com.ccnrcom.rp.faction.FactionProfessions.radioDisabled(def));
                    o.add("loadout", com.ccnrcom.rp.faction.FactionProfessions.loadout(def));
                    JsonObject profSpawn = professionSpawnJson(def);
                    if (profSpawn != null) {
                        o.add("spawn", profSpawn);
                    }
                    pa.add(o);
                });
            }
        }
        root.add("professions", pa);
        // 部署人数限制规则（K 面板「限制与在职」展示用；在职统计由主线程快照传入，不在此读用户表）
        JsonArray lim = new JsonArray();
        com.ccnrcom.rp.util.ConfigCrud.items("limits.json", "rules").forEach(lim::add);
        root.add("limits", lim);
        root.add("occupancy", occupancy);
        JsonObject st = new JsonObject();
        if (CCNRRPMod.managerSettings != null) {
            st = CCNRRPMod.managerSettings.toJson();
        }
        root.add("settings", st);
        root.addProperty("levelBase", com.ccnrcom.rp.config.CCNRRPConfig.LEVEL_BASE.get());
        root.addProperty("levelPow", com.ccnrcom.rp.config.CCNRRPConfig.LEVEL_POW.get());
        // 玩家头顶悬浮标签配置（服务端权威：开关 + 徽章大小 + 高度）
        root.addProperty("nametagEnabled", com.ccnrcom.rp.config.CCNRRPConfig.NAMETAG_ENABLED.get());
        root.addProperty("nametagBadgeSize", com.ccnrcom.rp.config.CCNRRPConfig.NAMETAG_BADGE_SIZE.get());
        root.addProperty("nametagOffset", com.ccnrcom.rp.config.CCNRRPConfig.NAMETAG_OFFSET.get());
        // 击杀友好提示距聊天区上方的额外间距（服务端权威，客户端遵从）
        root.addProperty("killNoticeOffset", com.ccnrcom.rp.config.CCNRRPConfig.KILL_NOTICE_OFFSET.get());
        return root;
    }

    /** 档案列表逐玩家字段（主线程快照值写入；后台线程组装载荷时调用）。 */
    private static void applyUserListFields(JsonObject root, UserSnapshot s) {
        root.addProperty("admin", s.admin());
        root.addProperty("panelLocked", s.panelLocked());
        root.addProperty("userXp", s.userXp());
        root.addProperty("userLevel", s.userLevel());
        root.addProperty("anySupportRevive", s.anySupportRevive());
        root.addProperty("status", s.status());
        root.addProperty("professionId", s.professionId());
        root.addProperty("factionId", s.factionId());
        root.addProperty("cooldownUntil", s.cooldownUntil());
    }

    /** 全玩家档案摘要（头顶标签用）：{uuid: {name, professionId, factionId, level}}。只下发非观察者（已部署）玩家。 */
    private JsonObject playerTagsJson() {
        JsonObject root = new JsonObject();
        if (CCNRRPMod.users == null) {
            return root;
        }
        for (String uid : CCNRRPMod.users.uuids()) {
            // 观察者（未部署）玩家不下发头顶标签：客户端只显示非观察者玩家的阵营/职业/等级
            if (CCNRRPMod.users.status(uid) != com.ccnrcom.rp.status.CharacterStatus.ALIVE) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("name", playerName(uid));
            o.addProperty("professionId", CCNRRPMod.users.professionId(uid));
            o.addProperty("factionId", CCNRRPMod.users.factionId(uid));
            o.addProperty("level", CCNRRPMod.users.level(uid));
            root.add(uid, o);
        }
        return root;
    }

    private String playerName(String uid) {
        if (server != null) {
            net.minecraft.server.level.ServerPlayer p =
                    server.getPlayerList().getPlayer(java.util.UUID.fromString(uid));
            if (p != null) {
                return p.getName().getString();
            }
        }
        return uid.substring(0, Math.min(8, uid.length()));
    }

    /** 发送全玩家头顶标签给指定玩家。 */
    public void sendPlayerTags(net.minecraft.server.level.ServerPlayer player) {
        if (player != null) {
            RpChannels.sendTo(
                    player, new RpPackets.PlayerTagsS2C(playerTagsJson().toString()));
        }
    }

    /**
     * 广播全玩家头顶标签给所有在线玩家（登录/登出/部署/死亡/复活等状态变化后调用）。
     * 异步：独立线程构建 payload（人多时不阻塞主线程），构建完成后回主线程发送网络包。
     */
    public void broadcastPlayerTags() {
        refreshPlayerTags();
    }
}
