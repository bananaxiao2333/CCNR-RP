/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.ccnrcom.rp.status.CharacterStatus;
import com.ccnrcom.rp.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** 客户端角色数据镜像（由 S2C 包维护，供角色管理界面与招募列表读取）。 */
public final class ClientCharacterState {
    private static final List<JsonObject> characters = new ArrayList<>();
    private static final List<JsonObject> factions = new ArrayList<>();
    private static final List<JsonObject> professions = new ArrayList<>();
    private static JsonObject settings = new JsonObject();
    private static JsonObject serverConfig = new JsonObject();
    /** 等级曲线（服务端同步，客户端仅用于展示换算；未同步时回退服务端默认值）。 */
    private static double levelBase = 100.0;

    private static double levelPow = 2.0;
    /** 玩家头顶悬浮标签开关（服务端同步，默认开）。 */
    private static boolean nametagEnabled = true;
    /** 玩家头顶悬浮标签阵营徽章大小（服务端同步，默认 9；0=不显示徽章）。 */
    private static int nametagBadgeSize = 9;
    /** 玩家头顶悬浮标签离头顶高度（格，服务端同步，默认 0.9）。 */
    private static double nametagOffset = 0.9;

    private static boolean isAdmin = false;
    private static boolean autoOpenPending = false;
    private static boolean panelLocked = false;
    private static final List<JsonObject> managerEvents = new ArrayList<>();
    private static final List<JsonObject> managerPhases = new ArrayList<>();
    private static final List<JsonObject> managerWaves = new ArrayList<>();
    /** 部署人数限制规则（limits.json rules，K 面板展示用）。 */
    private static final List<JsonObject> deployLimits = new ArrayList<>();
    /** 当前在职统计（occupancy：professions/factions → 人数，K 面板展示用）。 */
    private static JsonObject occupancy = new JsonObject();
    /** 已上传音乐名（管理面板补全提示）。 */
    private static final List<String> musicList = new ArrayList<>();
    /** CMDCam 已保存场景名（管理面板 CMDCam 场景输入项补全提示；未装/读取失败=空）。 */
    private static final List<String> camScenes = new ArrayList<>();

    private static final List<JsonObject> managerSequences = new ArrayList<>();
    private static final List<String> activeEvents = new ArrayList<>();
    // 用户维度（经验随用户走 / 支援开关）
    private static long userXp = 0;
    private static int userLevel = 0;
    private static boolean anySupportRevive = false;
    /** 是否已在本连接内武装过入服自动开面板（每登录一次，防每次列表同步反复弹面板）。 */
    private static boolean autoOpenArmed = false;
    // 用户级身份（v2：删除角色实体后唯一身份）
    private static CharacterStatus userStatus = CharacterStatus.OBSERVING;
    private static String userProfessionId = "";
    private static String userFactionId = "";
    private static long userCooldownUntil = 0;
    // 经验规则集（管理面板「经验规则」页展示；服务端 RulesStateS2C 下发）
    private static final List<JsonObject> xpRules = new ArrayList<>();
    // 全玩家头顶标签（uuid -> 档案摘要；旁观者视角渲染其他玩家阵营/职业/等级）
    private static final java.util.Map<String, PlayerTag> playerTags = new java.util.HashMap<>();

    private ClientCharacterState() {}

    /** 其他玩家头顶标签（客户端镜像）。 */
    public record PlayerTag(String name, String professionId, String factionId, int level) {}

    /** 更新全玩家头顶标签（服务端 PlayerTagsS2C 下发）。 */
    public static synchronized void setPlayerTags(String payload) {
        playerTags.clear();
        if (payload == null || payload.isBlank()) {
            return;
        }
        try {
            JsonObject root = JsonUtil.GSON.fromJson(payload, JsonObject.class);
            if (root == null) {
                return;
            }
            for (var e : root.entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                playerTags.put(
                        e.getKey(),
                        new PlayerTag(
                                str(o, "name", e.getKey()),
                                str(o, "professionId", ""),
                                str(o, "factionId", ""),
                                o.has("level") && o.get("level").isJsonPrimitive()
                                        ? o.get("level").getAsInt()
                                        : 0));
            }
        } catch (Exception ignored) {
            // 标签数据异常仅丢弃本次更新
        }
    }

    /** 某玩家头顶标签（无则 null）。 */
    public static synchronized PlayerTag playerTag(String uuid) {
        return playerTags.get(uuid);
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    public static synchronized void setList(String payload) {
        JsonObject root = JsonUtil.GSON.fromJson(payload, JsonObject.class);
        if (root == null) {
            return;
        }
        factions.clear();
        if (root.has("factions")) {
            for (JsonElement e : root.getAsJsonArray("factions")) {
                factions.add(e.getAsJsonObject());
            }
        }
        professions.clear();
        if (root.has("professions")) {
            for (JsonElement e : root.getAsJsonArray("professions")) {
                professions.add(e.getAsJsonObject());
            }
        }
        deployLimits.clear();
        if (root.has("limits") && root.get("limits").isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray("limits")) {
                if (e.isJsonObject()) {
                    deployLimits.add(e.getAsJsonObject());
                }
            }
        }
        occupancy = root.has("occupancy") && root.get("occupancy").isJsonObject()
                ? root.getAsJsonObject("occupancy")
                : new JsonObject();
        if (root.has("settings") && root.get("settings").isJsonObject()) {
            settings = root.getAsJsonObject("settings");
        }
        isAdmin = root.has("admin") && root.get("admin").getAsBoolean();
        if (root.has("userXp")) {
            userXp = root.get("userXp").getAsLong();
        }
        if (root.has("userLevel")) {
            userLevel = root.get("userLevel").getAsInt();
        }
        anySupportRevive =
                root.has("anySupportRevive") && root.get("anySupportRevive").getAsBoolean();
        if (root.has("status")) {
            userStatus = CharacterStatus.parse(root.get("status").getAsString());
        }
        if (root.has("professionId")) {
            userProfessionId = root.get("professionId").getAsString();
        }
        if (root.has("factionId")) {
            userFactionId = root.get("factionId").getAsString();
        }
        if (root.has("cooldownUntil")) {
            userCooldownUntil = root.get("cooldownUntil").getAsLong();
        }
        panelLocked = false;
        // 等级曲线跟随服务端（服务端调参，客户端不读本地 serverconfig）
        if (root.has("levelBase")) {
            levelBase = root.get("levelBase").getAsDouble();
        }
        if (root.has("levelPow")) {
            levelPow = root.get("levelPow").getAsDouble();
        }
        // 玩家头顶悬浮标签配置跟随服务端（服务端权威）
        if (root.has("nametagEnabled")) {
            nametagEnabled = root.get("nametagEnabled").getAsBoolean();
        }
        if (root.has("nametagBadgeSize")) {
            nametagBadgeSize = root.get("nametagBadgeSize").getAsInt();
        }
        if (root.has("nametagOffset")) {
            nametagOffset = root.get("nametagOffset").getAsDouble();
        }
        // 入服自动开面板不在列表同步时武装——等素材同步完成后由 armAutoOpenPanel() 武装
        // （同步完成前部署被禁用，面板提前打开无意义）。
    }

    /** 素材同步完成后武装自动开面板（每连接仅一次；受服务端设置 openPanelOnJoin 控制）。 */
    public static synchronized void armAutoOpenPanel() {
        if (!autoOpenArmed) {
            autoOpenArmed = true;
            autoOpenPending = bool("openPanelOnJoin", true) && !panelLocked;
        }
    }

    /** 登出重置：下次连接可再次武装自动开面板；清除征召身份。 */
    public static synchronized void resetForJoin() {
        autoOpenArmed = false;
        autoOpenPending = false;
        conscript = null;
    }

    /** 征召兵在场身份（JSON：professionId/factionId）；null=未以征召兵身份在场。 */
    private static JsonObject conscript = null;

    public static synchronized void setConscript(String payload) {
        if (payload == null || payload.isBlank()) {
            conscript = null;
        } else {
            try {
                JsonObject o = JsonUtil.GSON.fromJson(payload, JsonObject.class);
                conscript = o != null && o.has("professionId") ? o : null;
            } catch (Exception e) {
                conscript = null;
            }
        }
        refreshPanelLocked(); // 征召在场 = 面板锁定（非观察者）
    }

    /** 当前征召兵身份（null=未以征召兵身份在场）。 */
    public static synchronized JsonObject conscript() {
        return conscript;
    }

    /** 用户经验/等级更新（结算推送）。 */
    public static synchronized void setUserXp(long xp, int level) {
        userXp = xp;
        userLevel = level;
    }

    public static synchronized long userXp() {
        return userXp;
    }

    public static synchronized int userLevel() {
        return userLevel;
    }

    public static synchronized boolean anySupportRevive() {
        return anySupportRevive;
    }

    /** 服务端同步的等级曲线基数（仅展示换算用）。 */
    public static synchronized double levelBase() {
        return levelBase;
    }

    /** 服务端同步的等级曲线指数（仅展示换算用）。 */
    public static synchronized double levelPow() {
        return levelPow;
    }

    /** 玩家头顶悬浮标签开关（服务端同步）。 */
    public static synchronized boolean nametagEnabled() {
        return nametagEnabled;
    }

    /** 玩家头顶悬浮标签阵营徽章大小（服务端同步；0=不显示徽章）。 */
    public static synchronized int nametagBadgeSize() {
        return nametagBadgeSize;
    }

    /** 玩家头顶悬浮标签离头顶高度（格，服务端同步）。 */
    public static synchronized double nametagOffset() {
        return nametagOffset;
    }

    // ---------- 用户级身份（v2） ----------

    public static synchronized CharacterStatus userStatus() {
        return userStatus;
    }

    public static synchronized String userProfessionId() {
        return userProfessionId;
    }

    public static synchronized String userFactionId() {
        return userFactionId;
    }

    public static synchronized long userCooldownUntil() {
        return userCooldownUntil;
    }

    public static synchronized boolean isDeployed() {
        return userStatus == CharacterStatus.ALIVE;
    }

    /** 用户级身份更新（部署/状态变化推送）。 */
    public static synchronized void setUserStatus(CharacterStatus status) {
        if (status != null) {
            userStatus = status;
        }
    }

    public static synchronized void setUserRole(String professionId, String factionId) {
        userProfessionId = professionId == null ? "" : professionId;
        userFactionId = factionId == null ? "" : factionId;
    }

    /** 经验规则集（管理面板展示用）。 */
    public static synchronized void setXpRules(String payload) {
        xpRules.clear();
        if (payload == null || payload.isBlank()) {
            return;
        }
        try {
            JsonObject root = JsonUtil.GSON.fromJson(payload, JsonObject.class);
            if (root != null && root.has("rules") && root.get("rules").isJsonArray()) {
                for (var el : root.getAsJsonArray("rules")) {
                    if (el.isJsonObject()) {
                        xpRules.add(el.getAsJsonObject());
                    }
                }
            }
        } catch (Exception ignored) {
            // 规则数据异常仅丢弃本次更新
        }
    }

    public static synchronized java.util.List<JsonObject> xpRules() {
        return java.util.List.copyOf(xpRules);
    }

    /** 面板锁（v2：已取消打开限制，恒为 false，K 面板任意时刻可开）。 */
    public static synchronized boolean panelLocked() {
        return panelLocked;
    }

    private static void refreshPanelLocked() {
        panelLocked = false;
    }

    private static boolean bool(String key, boolean def) {
        try {
            if (!settings.has(key)) {
                return def;
            }
            return settings.get(key).getAsBoolean();
        } catch (Exception e) {
            return def;
        }
    }

    /** 字符串设置项当前值（缺省按 ManagerSettings 默认值，与服务端一致）。 */
    public static synchronized String settingString(String key, String def) {
        try {
            if (!settings.has(key)) {
                return def;
            }
            return settings.get(key).getAsString();
        } catch (Exception e) {
            return def;
        }
    }

    public static synchronized List<JsonObject> factions() {
        return List.copyOf(factions);
    }

    public static synchronized List<JsonObject> professions() {
        return List.copyOf(professions);
    }

    public static synchronized List<JsonObject> professionsOf(String factionId) {
        return professions.stream()
                .filter(p ->
                        p.has("factionId") && p.get("factionId").getAsString().equals(factionId))
                .toList();
    }

    /** 职位显示名（无则回退原始 id，避免空串）。 */
    public static synchronized String professionName(String professionId) {
        for (JsonObject p : professions) {
            String pid = p.has("id") && !p.get("id").isJsonNull() ? p.get("id").getAsString() : "";
            if (pid.equals(professionId)) {
                String n = p.has("name") && !p.get("name").isJsonNull()
                        ? p.get("name").getAsString()
                        : "";
                return n.isBlank() ? professionId : n;
            }
        }
        return professionId;
    }

    /** 职位所属阵营显示名（无则回退阵营 id，再回退空串——用于邀请显示，避免露出内部 ID）。 */
    public static synchronized String factionNameOf(String professionId) {
        for (JsonObject p : professions) {
            String pid = p.has("id") && !p.get("id").isJsonNull() ? p.get("id").getAsString() : "";
            if (pid.equals(professionId)) {
                String fid = p.has("factionId") && !p.get("factionId").isJsonNull()
                        ? p.get("factionId").getAsString()
                        : "";
                for (JsonObject f : factions) {
                    String f2 = f.has("id") && !f.get("id").isJsonNull()
                            ? f.get("id").getAsString()
                            : "";
                    if (fid.equals(f2)) {
                        String n = f.has("name") && !f.get("name").isJsonNull()
                                ? f.get("name").getAsString()
                                : "";
                        return n.isBlank() ? fid : n;
                    }
                }
                return fid;
            }
        }
        return "";
    }

    public static synchronized JsonObject find(String charId) {
        return characters.stream()
                .filter(c -> c.get("id").getAsString().equals(charId))
                .findFirst()
                .orElse(null);
    }

    /** serverconfig 当前值（管理面板程序化设定；key → 数值）。 */
    public static synchronized JsonObject serverConfig() {
        return serverConfig;
    }

    public static synchronized boolean settingBool(String key, boolean def) {
        return bool(key, def);
    }

    /** 管理面板开关键列表（程序化渲染用；键来自服务端 settings.json 下发）。 */
    public static synchronized List<String> settingKeys() {
        return com.ccnrcom.rp.config.ManagerSettings.keys();
    }

    public static synchronized boolean isAdmin() {
        return isAdmin;
    }

    /** 管理器状态（ManagerStateS2C）更新。 */
    public static synchronized void setManager(String payload) {
        JsonObject root = JsonUtil.GSON.fromJson(payload, JsonObject.class);
        if (root == null) {
            return;
        }
        if (root.has("settings") && root.get("settings").isJsonObject()) {
            settings = root.getAsJsonObject("settings");
        }
        if (root.has("serverConfig") && root.get("serverConfig").isJsonObject()) {
            serverConfig = root.getAsJsonObject("serverConfig");
        }
        isAdmin = root.has("admin") && root.get("admin").getAsBoolean();
        managerEvents.clear();
        managerPhases.clear();
        managerWaves.clear();
        managerSequences.clear();
        copyArray(root, "events", managerEvents);
        copyArray(root, "phases", managerPhases);
        copyArray(root, "waves", managerWaves);
        copyArray(root, "sequences", managerSequences);
        camScenes.clear();
        if (root.has("camScenes") && root.get("camScenes").isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray("camScenes")) {
                if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                    camScenes.add(e.getAsString());
                }
            }
        }
        CharacterManagementScreen.refreshIfOpen();
        RpAdminScreen.refreshIfOpen();
    }

    private static void copyArray(JsonObject root, String key, List<JsonObject> out) {
        if (root.has(key) && root.get(key).isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray(key)) {
                if (e.isJsonObject()) {
                    out.add(e.getAsJsonObject());
                }
            }
        }
    }

    /** 职业 loadout（职位装备，客户端镜像；无则 null）——立绘/预览用。 */
    public static synchronized JsonObject professionLoadout(String professionId) {
        for (JsonObject p : professions) {
            String pid = p.has("id") && !p.get("id").isJsonNull() ? p.get("id").getAsString() : "";
            if (pid.equals(professionId) && p.has("loadout") && p.get("loadout").isJsonObject()) {
                return p.getAsJsonObject("loadout");
            }
        }
        return null;
    }

    public static synchronized List<JsonObject> managerEvents() {
        return List.copyOf(managerEvents);
    }

    public static synchronized List<JsonObject> managerPhases() {
        return List.copyOf(managerPhases);
    }

    public static synchronized List<JsonObject> managerWaves() {
        return List.copyOf(managerWaves);
    }

    // ---------- 部署人数限制（K 面板展示） ----------

    /** 部署人数限制规则列表（limits.json rules 原始 JSON，客户端展示用）。 */
    public static synchronized List<JsonObject> deployLimits() {
        return List.copyOf(deployLimits);
    }

    /** 当前在职统计（occupancy JSON：professions/factions → 人数）。 */
    public static synchronized JsonObject occupancy() {
        return occupancy;
    }

    /** 指定职业当前在职人数（无统计时 0）。 */
    public static synchronized int professionOccupied(String professionId) {
        return occNum("professions", professionId);
    }

    /** 指定阵营当前在职人数（无统计时 0）。 */
    public static synchronized int factionOccupied(String factionId) {
        return occNum("factions", factionId);
    }

    /** 职业维度有效上限（PROFESSION 专属或 GLOBAL 兜底；无则 -1=不限）。 */
    public static synchronized int professionLimit(String professionId) {
        int global = -1;
        for (JsonObject r : deployLimits) {
            String type = str(r, "type", "").toUpperCase(java.util.Locale.ROOT);
            if ("PROFESSION".equals(type) && professionId.equals(str(r, "target", ""))) {
                return num(r, "limit", -1);
            }
            if ("GLOBAL".equals(type)) {
                global = num(r, "limit", -1);
            }
        }
        return global;
    }

    /** 阵营维度有效上限（FACTION 专属；无则 -1=不限）。 */
    public static synchronized int factionLimit(String factionId) {
        for (JsonObject r : deployLimits) {
            String type = str(r, "type", "").toUpperCase(java.util.Locale.ROOT);
            if ("FACTION".equals(type) && factionId.equals(str(r, "target", ""))) {
                return num(r, "limit", -1);
            }
        }
        return -1;
    }

    private static int occNum(String group, String id) {
        try {
            if (occupancy.has(group) && occupancy.get(group).isJsonObject()) {
                JsonObject g = occupancy.getAsJsonObject(group);
                if (g.has(id)) {
                    return g.get(id).getAsInt();
                }
            }
        } catch (Exception e) {
            // 畸形统计忽略
        }
        return 0;
    }

    private static int num(JsonObject o, String key, int def) {
        try {
            return o.has(key) ? o.get(key).getAsInt() : def;
        } catch (Exception e) {
            return def;
        }
    }

    public static synchronized List<JsonObject> managerSequences() {
        return List.copyOf(managerSequences);
    }

    /** 已上传音乐名列表（JSON 数组字符串载荷）。 */
    public static synchronized void setMusicList(String payload) {
        musicList.clear();
        try {
            JsonArray a = JsonUtil.GSON.fromJson(payload, JsonArray.class);
            if (a != null) {
                for (JsonElement e : a) {
                    if (e.isJsonPrimitive()) {
                        musicList.add(e.getAsString());
                    }
                }
            }
        } catch (Exception ignored) {
            // 解析失败保持空列表
        }
    }

    public static synchronized List<String> musicList() {
        return List.copyOf(musicList);
    }

    /** CMDCam 已保存场景名列表（管理面板补全提示数据源）。 */
    public static synchronized List<String> camScenes() {
        return List.copyOf(camScenes);
    }

    /** 激活事件横幅（EventStateS2C）。 */
    public static synchronized void setActiveEvents(String payload) {
        activeEvents.clear();
        JsonObject root = JsonUtil.GSON.fromJson(payload, JsonObject.class);
        if (root == null) {
            return;
        }
        if (root.has("events") && root.get("events").isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray("events")) {
                activeEvents.add(e.getAsString());
            }
        }
    }

    public static synchronized List<String> activeEvents() {
        return List.copyOf(activeEvents);
    }

    /** 入服自动打开面板是否处于待定状态（未消费也未取消）。 */
    public static synchronized boolean isAutoOpenPending() {
        return autoOpenPending;
    }

    /** 取消入服自动打开面板（用户已打开其他界面/状态不再允许时）。 */
    public static synchronized void cancelAutoOpenPanel() {
        autoOpenPending = false;
    }

    /** 消耗入服自动打开面板标记（仅在实际打开面板时调用）。 */
    public static synchronized void consumeAutoOpenPanel() {
        autoOpenPending = false;
    }

    public static synchronized void clear() {
        playerTags.clear();
    }
}
