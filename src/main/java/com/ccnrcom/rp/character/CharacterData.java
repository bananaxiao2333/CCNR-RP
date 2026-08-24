/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.character;

import com.ccnrcom.rp.status.CharacterStatus;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 角色数据（纯数据记录）。 */
public record CharacterData(
        String id,
        String playerUuid,
        String name,
        String factionId,
        String professionId,
        String background,
        String skin,
        String skinHash,
        CharacterStatus status,
        long xp,
        long dutySeconds,
        long cooldownUntil,
        Map<String, Integer> tasks,
        String evacuation,
        long createdAt) {

    private static final String[] KEYS = {
        "id",
        "playerUuid",
        "name",
        "factionId",
        "professionId",
        "background",
        "skin",
        "skinHash",
        "status",
        "xp",
        "dutySeconds",
        "cooldownUntil",
        "tasks",
        "evacuation",
        "createdAt"
    };

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("playerUuid", playerUuid);
        o.addProperty("name", name);
        o.addProperty("factionId", factionId);
        o.addProperty("professionId", professionId);
        o.addProperty("background", background == null ? "" : background);
        if (skin != null) {
            o.addProperty("skin", skin);
        }
        if (skinHash != null) {
            o.addProperty("skinHash", skinHash);
        }
        o.addProperty("status", status.name().toLowerCase(java.util.Locale.ROOT));
        o.addProperty("xp", xp);
        o.addProperty("dutySeconds", dutySeconds);
        o.addProperty("cooldownUntil", cooldownUntil);
        JsonObject t = new JsonObject();
        tasks.forEach((k, v) -> t.addProperty(k, v));
        o.add("tasks", t);
        o.addProperty("evacuation", evacuation == null ? "none" : evacuation);
        o.addProperty("createdAt", createdAt);
        return o;
    }

    public static CharacterData fromJson(JsonObject o) {
        JsonObject tasks =
                o.has("tasks") && o.get("tasks").isJsonObject() ? o.getAsJsonObject("tasks") : new JsonObject();
        Map<String, Integer> taskMap = new LinkedHashMap<>();
        tasks.entrySet().forEach(e -> taskMap.put(e.getKey(), e.getValue().getAsInt()));
        return new CharacterData(
                str(o, "id", UUID.randomUUID().toString()),
                str(o, "playerUuid", ""),
                str(o, "name", "?"),
                str(o, "factionId", ""),
                str(o, "professionId", ""),
                str(o, "background", ""),
                o.has("skin") ? str(o, "skin", null) : null,
                o.has("skinHash") ? str(o, "skinHash", null) : null,
                CharacterStatus.parse(str(o, "status", "observing")),
                num(o, "xp", 0),
                num(o, "dutySeconds", 0),
                num(o, "cooldownUntil", 0),
                taskMap,
                str(o, "evacuation", "none"),
                num(o, "createdAt", 0));
    }

    public CharacterData withStatus(CharacterStatus newStatus) {
        return new CharacterData(
                id,
                playerUuid,
                name,
                factionId,
                professionId,
                background,
                skin,
                skinHash,
                newStatus,
                xp,
                dutySeconds,
                cooldownUntil,
                tasks,
                evacuation,
                createdAt);
    }

    public CharacterData withCooldown(long cooldown) {
        return new CharacterData(
                id,
                playerUuid,
                name,
                factionId,
                professionId,
                background,
                skin,
                skinHash,
                status,
                xp,
                dutySeconds,
                cooldown,
                tasks,
                evacuation,
                createdAt);
    }

    /** 强制更换名字与职业（序列 FORCE_PICK 使用）。 */
    public CharacterData withRole(String newName, String newProfessionId) {
        return new CharacterData(
                id,
                playerUuid,
                newName,
                factionId,
                newProfessionId,
                background,
                skin,
                skinHash,
                status,
                xp,
                dutySeconds,
                cooldownUntil,
                tasks,
                evacuation,
                createdAt);
    }

    public CharacterData withSkin(String skinFile, String hash) {
        return new CharacterData(
                id,
                playerUuid,
                name,
                factionId,
                professionId,
                background,
                skinFile,
                hash,
                status,
                xp,
                dutySeconds,
                cooldownUntil,
                tasks,
                evacuation,
                createdAt);
    }

    public CharacterData withXpDuty(long newXp, long newDutySeconds) {
        return new CharacterData(
                id,
                playerUuid,
                name,
                factionId,
                professionId,
                background,
                skin,
                skinHash,
                status,
                newXp,
                newDutySeconds,
                cooldownUntil,
                tasks,
                evacuation,
                createdAt);
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static long num(JsonObject o, String key, long def) {
        return o.has(key) ? o.get(key).getAsLong() : def;
    }
}
