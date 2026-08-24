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
                return;
            }
        }
        characters.add(data);
    }

    public static synchronized void remove(String charId) {
        characters.removeIf(c -> c.get("id").getAsString().equals(charId));
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

    public static synchronized void clear() {
        characters.clear();
        selected = "";
    }
}
