/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.character;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.config.CCNRRPConfig;
import com.ccnrcom.rp.network.RpChannels;
import com.ccnrcom.rp.network.RpPackets;
import com.ccnrcom.rp.status.CharacterStatus;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** 角色服务（服务端）：CRUD 规则、皮肤上传与分发、与客户端的同步。 */
public final class CharacterService {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int MAX_SKIN_BYTES = 256 * 1024;
    private static final int MAX_SKIN_DIMENSION = 512;
    private static final int PART_SIZE = 32 * 1024;

    private final CharacterStore store;
    private final Path skinDir;
    private final Map<UUID, String> selected = new HashMap<>();
    private final Map<UUID, SkinUpload> uploads = new HashMap<>();

    public CharacterService(MinecraftServer server) {
        net.minecraft.world.level.storage.LevelResource dir =
                new net.minecraft.world.level.storage.LevelResource("ccnr_rp");
        this.store = new CharacterStore(server.getWorldPath(dir));
        this.skinDir = server.getWorldPath(dir).resolve("skins");
        store.load();
    }

    public CharacterStore store() {
        return store;
    }

    public Path skinDir() {
        return skinDir;
    }

    private record SkinUpload(String charId, byte[] parts, int received) {}

    // ---------- 入站 ----------

    public static void onRequestList(ServerPlayer player) {
        if (player != null) {
            service().sendList(player);
        }
    }

    public static void onCreate(
            ServerPlayer player, String name, String factionId, String professionId, String background) {
        if (player == null) {
            return;
        }
        CharacterService svc = service();
        List<ValidationIssue> errors = svc.validateCreate(player, name, factionId, professionId);
        if (!errors.isEmpty()) {
            errors.forEach(v -> RpChannels.sendTo(player, new RpPackets.ErrorS2C(v.key(), v.args())));
            return;
        }
        CharacterData c = svc.store.create(
                player.getUUID().toString(),
                name.trim(),
                factionId,
                professionId,
                background == null ? "" : background.trim(),
                CCNRRPConfig.MAX_CHARACTERS_PER_PLAYER.get(),
                UUID::randomUUID);
        svc.store.save();
        RpChannels.sendTo(player, new RpPackets.ErrorS2C("ccnr_rp.character.create.ok", c.name()));
        svc.sendList(player);
    }

    public static void onSelect(ServerPlayer player, String charId) {
        CharacterService svc = service();
        if (svc.owns(player, charId)) {
            svc.selected.put(player.getUUID(), charId);
            svc.sendList(player);
        }
    }

    public static void onDelete(ServerPlayer player, String charId) {
        CharacterService svc = service();
        if (!svc.owns(player, charId)) {
            svc.sendError(player, "ccnr_rp.character.error.ownership");
            return;
        }
        svc.selected.remove(player.getUUID());
        svc.store.delete(charId);
        svc.store.save();
        RpChannels.sendTo(player, new RpPackets.CharacterRemoveS2C(charId));
    }

    /** 自刷新部署入口（GUI/命令）。 */
    public static void onDeploy(ServerPlayer player, String charId) {
        if (player == null) {
            return;
        }
        var c = service().store.find(charId).orElse(null);
        if (c != null && c.status() == com.ccnrcom.rp.status.CharacterStatus.ALIVE) {
            // 存活角色禁止自刷新部署（防自杀逃逸，服务端硬校验）
            service().sendError(player, "ccnr_rp.spawn.error.self_deploy", charId);
            return;
        }
        if (CCNRRPMod.spawnFramework == null || !CCNRRPMod.spawnFramework.deploySelf(player, charId)) {
            service().sendError(player, "ccnr_rp.spawn.error.self_deploy", charId);
            return;
        }
        service().sendError(player, "ccnr_rp.spawn.deployed", charId);
        // 部署成功 → 客户端入场电影（黑屏→阵营图标→打字档案→淡出）
        service().sendCinematic(player, charId);
    }

    // ---------- 管理器（管理员）----------

    /** 请求管理器状态（管理员）。 */
    public static void onManagerRequest(ServerPlayer player) {
        if (!com.ccnrcom.rp.util.Permissions.canAdmin(player, com.ccnrcom.rp.util.Permissions.ADMIN_FACTION)) {
            service().sendError(player, "ccnr_rp.command.no_permission");
            return;
        }
        sendManagerState(player);
    }

    /** 设置项修改（管理员）。 */
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
        sendManagerState(player);
        service().sendList(player);
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
        JsonArray seq = new JsonArray();
        com.ccnrcom.rp.util.ConfigCrud.items("sequences.json", "sequences").forEach(seq::add);
        pay.add("sequences", seq);
        RpChannels.sendTo(player, new RpPackets.ManagerStateS2C(pay.toString()));
    }

    /** 管理器 CRUD（管理员）：kind=faction|profession，action=create|update|delete。 */
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
                String music = str(p, "music", "");
                String profile = str(p, "profile", "");
                if ("delete".equals(action)) {
                    errors = CCNRRPMod.factions.deleteProfession(id);
                } else {
                    errors = CCNRRPMod.factions.upsertProfession(id, name, factionId, selfDeploy, null, music, profile);
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
                            tier);
                    case "update" -> errors = CCNRRPMod.factions.updateFaction(
                            id,
                            name,
                            str(p, "color", "#FFFFFF"),
                            str(p, "description", ""),
                            str(p, "icon", "hex"),
                            tier);
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
            case "sequence" -> {
                String id = str(p, "id", "");
                errors = crudArray("sequences.json", "sequences", action, id, p, o -> {
                    if (!o.has("steps")) {
                        o.add("steps", new JsonArray());
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
            default -> errors = List.of("未知类型: " + kind);
        }
        if (!errors.isEmpty()) {
            service().sendError(player, "ccnr_rp.error.invalid_argument", String.join("; ", errors));
            return;
        }
        // 热重载对应系统
        if ("event".equals(kind) || "phase".equals(kind)) {
            if (CCNRRPMod.eventManager != null) {
                CCNRRPMod.eventManager.reload();
            }
        } else if ("wave".equals(kind)) {
            if (CCNRRPMod.spawnFramework != null) {
                CCNRRPMod.spawnFramework.reload();
            }
        } else if ("sequence".equals(kind)) {
            if (CCNRRPMod.sequenceEngine != null) {
                CCNRRPMod.sequenceEngine.reload();
            }
            if (CCNRRPMod.eventManager != null) {
                CCNRRPMod.eventManager.reload();
            }
        }
        service().sendError(player, "ccnr_rp.manager.crud.ok", kind, action);
        service().sendList(player);
        sendManagerState(player);
    }

    /** 通用数组段 CRUD：delete 删除；否则用 item 补齐缺省字段后 upsert。 */
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

    /** 转生/退役（强制保留角色）：判死 + 遗体，档案保留。 */
    public static void onRetire(ServerPlayer player, String charId) {
        CharacterService svc = service();
        Optional<CharacterData> c = svc.store.find(charId);
        if (c.isEmpty() || !c.get().playerUuid().equals(player.getUUID().toString())) {
            svc.sendError(player, "ccnr_rp.character.error.ownership");
            return;
        }
        CharacterData data = c.get();
        if (data.status() == com.ccnrcom.rp.status.CharacterStatus.DEAD) {
            svc.sendError(player, "ccnr_rp.character.error.status", data.name(), "DEAD");
            return;
        }
        com.ccnrcom.rp.status.StatusManager.retire(data, player);
        svc.sendError(player, "ccnr_rp.character.retire.ok", data.name());
    }

    /** 组装部署入场数据：名字/职业/阵营(图标+等级)/简历/阵营关系（图谱 resolve，非中立才列出）。 */
    private void sendCinematic(ServerPlayer player, String charId) {
        Optional<CharacterData> c = store.find(charId);
        if (c.isEmpty()) {
            return;
        }
        CharacterData data = c.get();
        JsonObject en = new JsonObject();
        en.addProperty("name", data.name());
        String profName = data.professionId();
        String music = "";
        if (CCNRRPMod.factions != null) {
            var profDef = CCNRRPMod.factions.findProfession(data.professionId()).orElse(null);
            if (profDef != null) {
                profName = com.ccnrcom.rp.faction.FactionProfessions.idsSafeName(profDef);
                music = com.ccnrcom.rp.faction.FactionProfessions.music(profDef);
            }
            var graph = CCNRRPMod.factions.graph();
            var f = graph.factions().get(data.factionId());
            if (f != null) {
                en.addProperty("factionName", f.name());
                en.addProperty("icon", f.icon());
                en.addProperty("tier", f.tier());
                JsonArray rel = new JsonArray();
                for (var other : graph.factions().values()) {
                    if (other.id().equals(f.id())) {
                        continue;
                    }
                    var type = graph.resolve(f.id(), other.id());
                    if (type != null && type != com.ccnrcom.rp.faction.RelationType.NEUTRAL) {
                        JsonObject o = new JsonObject();
                        o.addProperty("name", other.name());
                        o.addProperty("type", type.name().toLowerCase(java.util.Locale.ROOT));
                        rel.add(o);
                    }
                }
                en.add("relations", rel);
            }
        }
        en.addProperty("professionName", profName);
        en.addProperty("music", music);
        if (!en.has("factionName")) {
            en.addProperty("factionName", data.factionId());
            en.addProperty("icon", "hex");
            en.addProperty("tier", 2);
        }
        String resume = data.background() == null ? "" : data.background();
        if (CCNRRPMod.factions != null) {
            var pd = CCNRRPMod.factions.findProfession(data.professionId()).orElse(null);
            if (pd != null
                    && !com.ccnrcom.rp.faction.FactionProfessions.profile(pd).isBlank()) {
                resume = com.ccnrcom.rp.faction.FactionProfessions.profile(pd);
            }
        }
        en.addProperty("background", resume);
        RpChannels.sendTo(player, new RpPackets.CinematicS2C(en.toString()));
    }

    public static void onObserve(ServerPlayer player, String charId) {
        CharacterService svc = service();
        Optional<CharacterData> c = svc.store.find(charId);
        if (c.isEmpty() || !c.get().playerUuid().equals(player.getUUID().toString())) {
            svc.sendError(player, "ccnr_rp.character.error.ownership");
            return;
        }
        CharacterData data = c.get();
        if (data.status() == CharacterStatus.ALIVE) {
            svc.updateCharacter(data.withStatus(CharacterStatus.OBSERVING), player);
            svc.sendError(player, "ccnr_rp.character.observe.ok", data.name());
        } else if (data.status() == CharacterStatus.OBSERVING) {
            svc.sendError(player, "ccnr_rp.character.error.status", data.name(), "观察状态无需切换".toString());
        } else {
            svc.sendError(player, "ccnr_rp.character.error.status", data.name(), "死亡角色不可切换观察");
        }
    }

    public static void onActivate(ServerPlayer player, String charId) {
        CharacterService svc = service();
        Optional<CharacterData> c = svc.store.find(charId);
        if (c.isEmpty() || !c.get().playerUuid().equals(player.getUUID().toString())) {
            svc.sendError(player, "ccnr_rp.character.error.ownership");
            return;
        }
        CharacterData data = c.get();
        Optional<CharacterData> alive = svc.store.findAlive(player.getUUID().toString());
        if (data.status() != CharacterStatus.OBSERVING) {
            svc.sendError(
                    player,
                    "ccnr_rp.character.error.status",
                    data.name(),
                    data.status().name());
            return;
        }
        if (alive.isPresent()) {
            svc.sendError(
                    player, "ccnr_rp.character.error.alive_exists", alive.get().name());
            return;
        }
        svc.updateCharacter(data.withStatus(CharacterStatus.ALIVE), player);
        svc.sendError(player, "ccnr_rp.character.activate.ok", data.name());
    }

    public static void onSkinPart(ServerPlayer player, RpPackets.SkinUploadPartC2S msg) {
        if (player == null) {
            return;
        }
        CharacterService svc = service();
        SkinUpload up = svc.uploads.getOrDefault(player.getUUID(), new SkinUpload(msg.charId, new byte[0], 0));
        if (!up.charId().equals(msg.charId)) {
            up = new SkinUpload(msg.charId, new byte[0], 0);
        }
        byte[] next = new byte[up.parts().length + msg.data.length];
        System.arraycopy(up.parts(), 0, next, 0, up.parts().length);
        System.arraycopy(msg.data, 0, next, up.parts().length, msg.data.length);
        svc.uploads.put(player.getUUID(), new SkinUpload(msg.charId, next, msg.index + 1));
    }

    public static void onSkinCommit(ServerPlayer player, RpPackets.SkinUploadCommitC2S msg) {
        if (player == null) {
            return;
        }
        CharacterService svc = service();
        SkinUpload up = svc.uploads.remove(player.getUUID());
        if (up == null || !up.charId().equals(msg.charId) || up.parts().length != msg.expectedSize) {
            svc.sendError(player, "ccnr_rp.character.error.skin", "数据不完整");
            return;
        }
        Optional<CharacterData> c = svc.store.find(msg.charId);
        if (c.isEmpty() || !c.get().playerUuid().equals(player.getUUID().toString())) {
            svc.sendError(player, "ccnr_rp.character.error.ownership");
            return;
        }
        String error = svc.validateSkin(up.parts());
        if (error != null) {
            svc.sendError(player, "ccnr_rp.character.error.skin", error);
            return;
        }
        try {
            Files.createDirectories(svc.skinDir);
            String fileName = c.get().id() + ".png";
            Files.write(svc.skinDir.resolve(fileName), up.parts());
            String hash = sha256(up.parts());
            CharacterData updated = c.get().withSkin(fileName, hash);
            svc.store.update(updated);
            svc.store.save();
            // 在全服广播皮肤（其他玩家用于招募列表头像等）
            RpChannels.sendToAll(new RpPackets.SkinSyncS2C(c.get().id(), up.parts(), hash));
            svc.sendError(player, "ccnr_rp.character.skin.ok", fileName);
        } catch (Exception e) {
            LOGGER.error("[CCNR-RP] 皮肤写入失败", e);
            svc.sendError(player, "ccnr_rp.character.error.skin", e.toString());
        }
    }

    // ---------- 内部 ----------

    private static CharacterService service() {
        CharacterService s = CCNRRPMod.characters;
        if (s == null) {
            throw new IllegalStateException("角色服务未初始化");
        }
        return s;
    }

    private boolean owns(ServerPlayer player, String charId) {
        return store.find(charId)
                .map(c -> c.playerUuid().equals(player.getUUID().toString()))
                .orElse(false);
    }

    /** 创建校验（结构化错误：键+参数，客户端直接翻译，不再泄漏原始键）。 */
    record ValidationIssue(String key, String... args) {}

    private List<ValidationIssue> validateCreate(
            ServerPlayer player, String name, String factionId, String professionId) {
        List<ValidationIssue> errors = new ArrayList<>();
        if (name == null || name.trim().length() < 1 || name.trim().length() > 32) {
            errors.add(new ValidationIssue("ccnr_rp.character.error.name"));
        } else if (!name.matches("[\\p{L} ]+")) {
            errors.add(new ValidationIssue("ccnr_rp.character.error.name_chars"));
        }
        if (CCNRRPMod.factions == null || !CCNRRPMod.factions.graph().factions().containsKey(factionId)) {
            errors.add(new ValidationIssue("ccnr_rp.character.error.faction", factionId));
        } else {
            var def = CCNRRPMod.factions.findProfession(professionId);
            if (def.isEmpty()) {
                errors.add(new ValidationIssue("ccnr_rp.character.error.profession", professionId));
            } else if (!factionId.equals(com.ccnrcom.rp.faction.FactionProfessions.factionId(def.get()))) {
                errors.add(new ValidationIssue("ccnr_rp.character.error.profession", professionId));
            }
        }
        if (store.countOf(player.getUUID().toString()) >= CCNRRPConfig.MAX_CHARACTERS_PER_PLAYER.get()) {
            errors.add(new ValidationIssue(
                    "ccnr_rp.character.error.count", String.valueOf(CCNRRPConfig.MAX_CHARACTERS_PER_PLAYER.get())));
        }
        return errors;
    }

    private String validateSkin(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_SKIN_BYTES) {
            return "大小超限（≤ " + MAX_SKIN_BYTES + " 字节）";
        }
        if (bytes.length < 8 || bytes[0] != (byte) 0x89 || bytes[1] != 'P' || bytes[2] != 'N' || bytes[3] != 'G') {
            return "不是 PNG 文件";
        }
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) {
                return "PNG 解码失败";
            }
            if (img.getWidth() > MAX_SKIN_DIMENSION || img.getHeight() > MAX_SKIN_DIMENSION) {
                return "尺寸超限（≤ " + MAX_SKIN_DIMENSION + "×" + MAX_SKIN_DIMENSION + "）";
            }
        } catch (Exception e) {
            return "PNG 解码失败: " + e.getMessage();
        }
        return null;
    }

    private void updateCharacter(CharacterData updated, ServerPlayer owner) {
        updateAndBroadcast(updated, owner);
    }

    /** 更新角色并广播（公共入口：状态机/经验/刷新框架等复用）。 */
    public static void updateAndBroadcast(CharacterData updated, ServerPlayer ownerOrNull) {
        service().store().update(updated);
        service().store().save();
        if (ownerOrNull != null) {
            RpChannels.sendTo(ownerOrNull, new RpPackets.CharacterUpdateS2C(updated.toJson()));
        }
    }

    public void sendList(ServerPlayer player) {
        JsonObject root = new JsonObject();
        JsonArray a = new JsonArray();
        for (CharacterData c : store.ofPlayer(player.getUUID().toString())) {
            a.add(c.toJson());
        }
        root.add("characters", a);
        root.addProperty("selected", selected.getOrDefault(player.getUUID(), ""));
        // 附带阵容/职业数据供客户端 GUI（阵营下拉/职业下拉使用）
        JsonArray fa = new JsonArray();
        if (CCNRRPMod.factions != null) {
            CCNRRPMod.factions.graph().factions().values().forEach(f -> {
                JsonObject o = new JsonObject();
                o.addProperty("id", f.id());
                o.addProperty("name", f.name());
                o.addProperty("color", f.color());
                o.addProperty("icon", f.icon());
                o.addProperty("tier", f.tier());
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
                    o.addProperty("selfDeploy", com.ccnrcom.rp.faction.FactionProfessions.selfDeploy(def));
                    o.addProperty("music", com.ccnrcom.rp.faction.FactionProfessions.music(def));
                    o.addProperty("profile", com.ccnrcom.rp.faction.FactionProfessions.profile(def));
                    pa.add(o);
                });
            }
        }
        root.add("professions", pa);
        // 管理器设置 + 权限（客户端 K 面板与管理界面使用）
        JsonObject st = new JsonObject();
        st.addProperty("forceObserving", true);
        st.addProperty("openPanelOnJoin", true);
        st.addProperty("forceRetain", true);
        if (CCNRRPMod.managerSettings != null) {
            st = CCNRRPMod.managerSettings.toJson();
        }
        root.add("settings", st);
        root.addProperty(
                "admin",
                com.ccnrcom.rp.util.Permissions.canAdmin(player, com.ccnrcom.rp.util.Permissions.ADMIN_FACTION));
        // 面板锁：有存活（非观察者）角色时禁止打开 K 面板（防自爆逃逸，服务端判定）
        root.addProperty(
                "panelLocked", store.findAlive(player.getUUID().toString()).isPresent());
        RpChannels.sendTo(player, new RpPackets.CharacterListS2C(root.toString()));
    }

    public void sendError(ServerPlayer player, String key, String... args) {
        RpChannels.sendTo(player, new RpPackets.ErrorS2C(key, args));
    }

    public static String sha256(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
