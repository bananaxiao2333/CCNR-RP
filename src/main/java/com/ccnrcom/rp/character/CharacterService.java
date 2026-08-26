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
        service().sendList(player); // 等级曲线等客户端展示值同步
        sendManagerState(player);
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
        service().broadcastToAll();
    }

    /** 管理端变更后同步全员：用户档案 + 管理器状态（阵营/职业/事件/阶段/波）。 */
    private void broadcastToAll() {
        if (server == null) {
            return;
        }
        List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());
        for (ServerPlayer p : players) {
            sendList(p);
            sendManagerState(p);
        }
        com.ccnrcom.rp.assets.AssetLibrary.broadcastManifest();
    }

    private static void sendManagerState(ServerPlayer player) {
        if (CCNRRPMod.managerSettings == null) {
            return;
        }
        JsonObject pay = new JsonObject();
        pay.add("settings", CCNRRPMod.managerSettings.toJson());
        pay.addProperty(
                "admin",
                com.ccnrcom.rp.util.Permissions.canAdmin(player, com.ccnrcom.rp.util.Permissions.ADMIN_FACTION));
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
        // CMDCam 已保存场景名（管理面板 CMDCam 场景输入项补全提示；未装/读取失败=空列表）
        JsonArray cams = new JsonArray();
        for (String s : com.ccnrcom.rp.cmdcam.CamSceneBridge.savedSceneNames(player.level())) {
            cams.add(s);
        }
        pay.add("camScenes", cams);
        RpChannels.sendTo(player, new RpPackets.ManagerStateS2C(pay.toString()));
        RpChannels.sendTo(player, new RpPackets.MusicListS2C(musicListJson()));
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
                String name = str(p, "name", id);
                String factionId = str(p, "factionId", "");
                boolean selfDeploy = p.has("selfDeploy") && p.get("selfDeploy").getAsBoolean();
                int unlockLevel = com.ccnrcom.rp.faction.FactionProfessions.unlockLevel(p);
                String music = str(p, "music", "");
                String profile = str(p, "profile", "");
                String cmdcamScene = str(p, "cmdcamScene", "");
                if ("delete".equals(action)) {
                    errors = CCNRRPMod.factions.deleteProfession(id);
                } else {
                    errors = CCNRRPMod.factions.upsertProfession(
                            id, name, factionId, selfDeploy, unlockLevel, null, music, profile, cmdcamScene);
                }
            }
            case "faction" -> {
                String id = str(p, "id", "");
                String name = str(p, "name", id);
                int tier = p.has("tier") ? p.get("tier").getAsInt() : 2;
                switch (action) {
                    case "create" -> errors = CCNRRPMod.factions.createFaction(
                            id,
                            name,
                            str(p, "color", "#FFFFFF"),
                            str(p, "description", ""),
                            str(p, "icon", "hex"),
                            tier,
                            str(p, "music", ""),
                            str(p, "cmdcamScene", ""));
                    case "update" -> errors = CCNRRPMod.factions.updateFaction(
                            id,
                            name,
                            str(p, "color", "#FFFFFF"),
                            str(p, "description", ""),
                            str(p, "icon", "hex"),
                            tier,
                            str(p, "music", ""),
                            str(p, "cmdcamScene", ""));
                    case "delete" -> errors = CCNRRPMod.factions.deleteFaction(id);
                    default -> errors = List.of("未知操作: " + action);
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
                    if (!o.has("settleOnEnd")) {
                        o.addProperty("settleOnEnd", true);
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
        service().broadcastToAll();
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
        var errors = mgr.upsertProfession(
                professionId,
                com.ccnrcom.rp.faction.FactionProfessions.idsSafeName(def),
                com.ccnrcom.rp.faction.FactionProfessions.factionId(def),
                com.ccnrcom.rp.faction.FactionProfessions.selfDeploy(def),
                com.ccnrcom.rp.faction.FactionProfessions.unlockLevel(def),
                loadout);
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
        service().sendList(player);
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
        service().sendList(player);
        service().sendError(player, "ccnr_rp.gui.admin.spawn.saved", professionId, String.valueOf(pts.size()));
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

    /**
     * 构建并下发用户档案列表（K 面板/客户端全量初始化）：
     * {factions, professions(含解锁等级/装备/音乐/简历), settings, admin, userXp, userLevel,
     *  anySupportRevive, status, professionId, factionId, cooldownUntil, levelBase, levelPow, panelLocked}。
     */
    public void sendList(ServerPlayer player) {
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
                JsonObject spawn = factionSpawnJson(f.id());
                if (spawn != null) {
                    o.add("spawn", spawn);
                }
                fa.add(o);
            });
        }
        root.add("factions", fa);
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
                    o.add("loadout", com.ccnrcom.rp.faction.FactionProfessions.loadout(def));
                    pa.add(o);
                });
            }
        }
        root.add("professions", pa);
        // 部署人数限制规则 + 当前在职统计（K 面板「限制与在职」展示用）
        JsonArray lim = new JsonArray();
        com.ccnrcom.rp.util.ConfigCrud.items("limits.json", "rules").forEach(lim::add);
        root.add("limits", lim);
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
        root.add("occupancy", occ);
        JsonObject st = new JsonObject();
        if (CCNRRPMod.managerSettings != null) {
            st = CCNRRPMod.managerSettings.toJson();
        }
        root.add("settings", st);
        root.addProperty("levelBase", com.ccnrcom.rp.config.CCNRRPConfig.LEVEL_BASE.get());
        root.addProperty("levelPow", com.ccnrcom.rp.config.CCNRRPConfig.LEVEL_POW.get());
        root.addProperty(
                "admin",
                com.ccnrcom.rp.util.Permissions.canAdmin(player, com.ccnrcom.rp.util.Permissions.ADMIN_FACTION));
        root.addProperty("panelLocked", !isObserver(player));
        String uuid = player.getUUID().toString();
        root.addProperty("userXp", CCNRRPMod.users.userXp(uuid));
        root.addProperty("userLevel", CCNRRPMod.users.level(uuid));
        root.addProperty("anySupportRevive", CCNRRPMod.users.anySupportRevive(uuid));
        root.addProperty("status", CCNRRPMod.users.status(uuid).name().toLowerCase(java.util.Locale.ROOT));
        root.addProperty("professionId", CCNRRPMod.users.professionId(uuid));
        root.addProperty("factionId", CCNRRPMod.users.factionId(uuid));
        root.addProperty("cooldownUntil", CCNRRPMod.users.cooldownUntil(uuid));
        RpChannels.sendTo(player, new RpPackets.CharacterListS2C(root.toString()));
        // 全玩家头顶标签数据（旁观者视角显示其他玩家的阵营/职业/等级）
        sendPlayerTags(player);
    }

    /** 全玩家档案摘要（头顶标签用）：{uuid: {name, professionId, factionId, level}}。 */
    private JsonObject playerTagsJson() {
        JsonObject root = new JsonObject();
        if (CCNRRPMod.users == null) {
            return root;
        }
        for (String uid : CCNRRPMod.users.uuids()) {
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

    /** 广播全玩家头顶标签给所有在线玩家（登录/登出/部署变更后调用）。 */
    public void broadcastPlayerTags() {
        if (server == null) {
            return;
        }
        String payload = playerTagsJson().toString();
        for (net.minecraft.server.level.ServerPlayer p :
                new java.util.ArrayList<>(server.getPlayerList().getPlayers())) {
            RpChannels.sendTo(p, new RpPackets.PlayerTagsS2C(payload));
        }
    }
}
