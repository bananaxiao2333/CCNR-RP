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
    private static String selected = "";
    private static JsonObject settings = new JsonObject();
    private static JsonObject serverConfig = new JsonObject();
    /** 等级曲线（服务端同步，客户端仅用于展示换算；未同步时回退服务端默认值）。 */
    private static double levelBase = 100.0;

    private static double levelPow = 2.0;
    private static boolean isAdmin = false;
    private static boolean autoOpenPending = false;
    private static boolean panelLocked = false;
    private static final List<JsonObject> managerEvents = new ArrayList<>();
    private static final List<JsonObject> managerPhases = new ArrayList<>();
    private static final List<JsonObject> managerWaves = new ArrayList<>();
    /** 已上传音乐名（管理面板补全提示）。 */
    private static final List<String> musicList = new ArrayList<>();

    private static final List<JsonObject> managerSequences = new ArrayList<>();
    private static final List<String> activeEvents = new ArrayList<>();
    // 用户维度（经验随用户走 / 创建冷却 / 支援开关 / 角色上限）
    private static long userXp = 0;
    private static int userLevel = 0;
    private static boolean anySupportRevive = false;
    private static long createCooldownUntil = 0;
    private static int maxCharacters = 5;
    /** 是否已在本连接内武装过入服自动开面板（每登录一次，防每次列表同步反复弹面板）。 */
    private static boolean autoOpenArmed = false;
    // 用户级身份（v2：删除角色实体后唯一身份）
    private static CharacterStatus userStatus = CharacterStatus.OBSERVING;
    private static String userProfessionId = "";
    private static String userFactionId = "";
    private static long userCooldownUntil = 0;
    // 结算明细逐行（右下角逐行渲染，绿色加分/红色减分）
    private static List<String[]> xpLines = new ArrayList<>();
    private static long xpLinesUntil = 0;

    private ClientCharacterState() {}

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
        createCooldownUntil = root.has("createCooldownUntil")
                ? root.get("createCooldownUntil").getAsLong()
                : 0;
        if (root.has("maxCharacters")) {
            maxCharacters = root.get("maxCharacters").getAsInt();
        }
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

    /** 创建角色冷却剩余毫秒（0 = 可创建）。 */
    public static synchronized long createRemainingMs() {
        return Math.max(0, createCooldownUntil - System.currentTimeMillis());
    }

    public static synchronized int maxCharacters() {
        return maxCharacters;
    }

    /** 服务端同步的等级曲线基数（仅展示换算用）。 */
    public static synchronized double levelBase() {
        return levelBase;
    }

    /** 服务端同步的等级曲线指数（仅展示换算用）。 */
    public static synchronized double levelPow() {
        return levelPow;
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

    /** 结算明细逐行（每条 "sign|value|key|args"，客户端拆分渲染）。 */
    public static synchronized void setXpLines(java.util.List<String> lines) {
        xpLines.clear();
        if (lines != null) {
            for (String l : lines) {
                if (l == null) {
                    continue;
                }
                String[] p = l.split("\\|", -1);
                xpLines.add(p);
            }
        }
        xpLinesUntil = System.currentTimeMillis() + 6000; // 结算结果展示 6 秒
    }

    public static synchronized java.util.List<String[]> xpLines() {
        if (xpLinesUntil <= 0 || System.currentTimeMillis() > xpLinesUntil) {
            return java.util.List.of();
        }
        return java.util.List.copyOf(xpLines);
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

    public static synchronized void upsert(JsonObject data) {
        String id = data.get("id").getAsString();
        for (int i = 0; i < characters.size(); i++) {
            if (characters.get(i).get("id").getAsString().equals(id)) {
                characters.set(i, data);
                refreshPanelLocked();
                return;
            }
        }
        characters.add(data);
        refreshPanelLocked();
    }

    public static synchronized void remove(String charId) {
        characters.removeIf(c -> c.get("id").getAsString().equals(charId));
        refreshPanelLocked();
    }

    public static synchronized List<JsonObject> list() {
        return List.copyOf(characters);
    }

    public static synchronized JsonObject find(String charId) {
        return characters.stream()
                .filter(c -> c.get("id").getAsString().equals(charId))
                .findFirst()
                .orElse(null);
    }

    public static synchronized String selected() {
        return selected;
    }

    public static synchronized void select(String charId) {
        selected = charId;
    }

    public static synchronized void setXp(String charId, long xp, int level) {
        JsonObject c = find(charId);
        if (c != null) {
            c.addProperty("xp", xp);
            c.addProperty("level", level);
        }
    }

    /** serverconfig 当前值（管理面板程序化设定；key → 数值）。 */
    public static synchronized JsonObject serverConfig() {
        return serverConfig;
    }

    public static synchronized boolean settingBool(String key, boolean def) {
        return bool(key, def);
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
        characters.clear();
        selected = "";
    }
}
