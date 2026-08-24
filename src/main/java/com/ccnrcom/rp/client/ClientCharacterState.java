/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

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
    private static boolean isAdmin = false;
    private static boolean autoOpenPending = false;
    private static boolean panelLocked = false;
    private static final List<JsonObject> managerEvents = new ArrayList<>();
    private static final List<JsonObject> managerPhases = new ArrayList<>();
    private static final List<JsonObject> managerWaves = new ArrayList<>();
    private static final List<JsonObject> managerSequences = new ArrayList<>();
    private static final List<String> activeEvents = new ArrayList<>();

    private ClientCharacterState() {}

    public static synchronized void setList(String payload) {
        characters.clear();
        JsonObject root = JsonUtil.GSON.fromJson(payload, JsonObject.class);
        if (root.has("selected")) {
            selected = root.get("selected").getAsString();
        }
        if (root.has("characters")) {
            JsonArray a = root.getAsJsonArray("characters");
            for (int i = 0; i < a.size(); i++) {
                characters.add(a.get(i).getAsJsonObject());
            }
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
        refreshPanelLocked();
        autoOpenPending = bool("openPanelOnJoin", true) && !panelLocked;
    }

    /** 面板锁：有存活角色时禁止打开 K 面板。 */
    public static synchronized boolean panelLocked() {
        return panelLocked;
    }

    /** 由角色列表实时推导面板锁：死亡/观察后立即解锁，不依赖新的全量列表包。 */
    private static void refreshPanelLocked() {
        panelLocked = false;
        for (JsonObject c : characters) {
            String st = c.has("status") ? c.get("status").getAsString() : "";
            if ("alive".equals(st)) {
                panelLocked = true;
                return;
            }
        }
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

    /** 消耗入服自动打开面板标记（仅一次）。 */
    public static synchronized boolean consumeAutoOpenPanel() {
        boolean v = autoOpenPending;
        autoOpenPending = false;
        return v;
    }

    public static synchronized void clear() {
        characters.clear();
        selected = "";
    }
}
