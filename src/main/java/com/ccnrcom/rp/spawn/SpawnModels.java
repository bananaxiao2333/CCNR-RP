/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.spawn;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** 刷新波定义与解析（纯逻辑，无 MC 依赖）。 */
public final class SpawnModels {

    public enum Mode {
        SELF_DEPLOY,
        RESURRECTION,
        BOTH;

        public boolean selfDeployAllowed() {
            return this == SELF_DEPLOY || this == BOTH;
        }

        public boolean resurrectionAllowed() {
            return this == RESURRECTION || this == BOTH;
        }
    }

    /** 刷新波定义（内嵌行为序列：波触发时执行）。 */
    public record Wave(
            String id,
            Mode mode,
            boolean enabled,
            List<String> teamIds,
            List<String> professionIds,
            List<String> factionIds,
            int count,
            int minLevel,
            String deployAtType,
            double x,
            double y,
            double z,
            String dim,
            int recruitTimeoutSeconds,
            String cmdcamScene,
            List<JsonObject> steps) {

        public Wave(
                String id,
                Mode mode,
                boolean enabled,
                List<String> teamIds,
                List<String> professionIds,
                List<String> factionIds,
                int count,
                int minLevel,
                String deployAtType,
                double x,
                double y,
                double z,
                String dim,
                int recruitTimeoutSeconds) {
            this(
                    id,
                    mode,
                    enabled,
                    teamIds,
                    professionIds,
                    factionIds,
                    count,
                    minLevel,
                    deployAtType,
                    x,
                    y,
                    z,
                    dim,
                    recruitTimeoutSeconds,
                    "",
                    List.of());
        }

        public boolean matchesProfession(String professionId, String factionId) {
            boolean profOk = professionIds == null || professionIds.isEmpty() || professionIds.contains(professionId);
            boolean factionOk = factionIds == null || factionIds.isEmpty() || factionIds.contains(factionId);
            return profOk && factionOk;
        }
    }

    /** 选人候选（纯数据）。 */
    public record Candidate(
            String charId,
            String playerUuid,
            String name,
            String status,
            long cooldownUntil,
            int level,
            String professionId,
            String factionId,
            boolean professionSelfDeploy,
            boolean online,
            boolean anySupportRevive) {

        public boolean dead() {
            return "dead".equals(status);
        }

        public boolean observing() {
            return "observing".equals(status);
        }
    }

    /** 选人结果。 */
    public record Selection(List<Candidate> deploy, List<Candidate> recruit) {}

    private SpawnModels() {}

    public static List<String> parseWaves(JsonObject root, List<Wave> out) {
        List<String> errors = new ArrayList<>();
        if (!root.has("waves") || !root.get("waves").isJsonArray()) {
            return List.of("spawn_waves.json: 缺少 waves 数组");
        }
        JsonArray arr = root.getAsJsonArray("waves");
        for (int i = 0; i < arr.size(); i++) {
            if (!arr.get(i).isJsonObject()) {
                errors.add("waves[" + i + "]: 不是对象");
                continue;
            }
            JsonObject o = arr.get(i).getAsJsonObject();
            if (!o.has("id")) {
                errors.add("waves[" + i + "]: 缺少 id");
                continue;
            }
            Mode mode = Mode.RESURRECTION;
            try {
                mode = Mode.valueOf(str(o, "mode", "RESURRECTION"));
            } catch (Exception e) {
                errors.add("waves[" + i + "]: 无效 mode '" + str(o, "mode", "") + "'");
                continue;
            }
            if (!o.has("deployAt")) {
                errors.add("waves[" + i + "]: 缺少 deployAt");
                continue;
            }
            JsonObject d = o.getAsJsonObject("deployAt");
            String type = str(d, "type", "WORLD_SPAWN");
            List<String> teamIds = strList(o, "teamIds");
            List<String> professions = strList(o, "professionIds");
            List<String> factions = strList(o, "factionIds");
            out.add(new Wave(
                    str(o, "id", "?"),
                    mode,
                    !o.has("enabled") || o.get("enabled").getAsBoolean(),
                    teamIds,
                    professions,
                    factions,
                    (int) num(o, "count", 4),
                    (int) num(o, "minLevel", 0),
                    type,
                    num(d, "x", 0),
                    num(d, "y", 64),
                    num(d, "z", 0),
                    str(d, "dim", "minecraft:overworld"),
                    (int) num(o, "recruitTimeoutSeconds", 60),
                    str(o, "cmdcamScene", ""),
                    parseSteps(o)));
        }

        return errors;
    }

    /** 内嵌行为序列（sequence 数组）。 */
    public static List<JsonObject> parseSteps(JsonObject o) {
        List<JsonObject> out = new ArrayList<>();
        if (o.has("sequence") && o.get("sequence").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("sequence")) {
                if (e.isJsonObject()) {
                    out.add(e.getAsJsonObject());
                }
            }
        }
        return out;
    }

    private static List<String> strList(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray(key)) {
                out.add(e.getAsString());
            }
        }
        return out;
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static long num(JsonObject o, String key, long def) {
        try {
            return o.has(key) ? o.get(key).getAsLong() : def;
        } catch (Exception e) {
            return def; // 畸形配置（非数字）不崩服
        }
    }
}
