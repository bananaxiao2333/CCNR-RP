/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.spawn;

import com.ccnrcom.rp.status.CharacterStatus;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** 刷新波定义与解析（纯逻辑，无 MC 依赖）。 */
public final class SpawnModels {

    public enum Mode {
        /** 存活人员可收到（持久化值沿用 SELF_DEPLOY 兼容旧配置）。 */
        SELF_DEPLOY,
        /** 死亡人员可收到。 */
        RESURRECTION,
        /** 皆可收到。 */
        BOTH;

        /** 是否允许存活玩家收到本波邀请。 */
        public boolean aliveReceiveAllowed() {
            return this == SELF_DEPLOY || this == BOTH;
        }

        /** 是否允许死亡/观察玩家收到本波邀请。 */
        public boolean deadReceiveAllowed() {
            return this == RESURRECTION || this == BOTH;
        }

        /** 是否可作为自部署落点（存活可收到即认为存活可用）。 */
        public boolean selfDeployAllowed() {
            return aliveReceiveAllowed();
        }

        /** 兼容旧版管理面板持久化的枚举值（v2.15.x 前 Modes 数组含 "RECRUIT"）：语义 = 仅死亡/观察可收到。 */
        public static Mode legacyRecruit() {
            return RESURRECTION;
        }
    }

    /** 征召接受者的部署方式（docs/09 §4.3「判断条件 = 观察者角色」的显式化）：观察者/死亡 → 临时征召；存活 → 正式转职。 */
    public enum ConscriptDeployMode {
        TEMP,
        FORMAL,
        SKIP;

        /** 按当前角色状态判定部署方式；null（异常）→ SKIP（不部署）。 */
        public static ConscriptDeployMode of(CharacterStatus status) {
            if (status == null) {
                return SKIP;
            }
            return switch (status) {
                case ALIVE -> FORMAL;
                case OBSERVING, DEAD -> TEMP;
            };
        }
    }

    /** 通用波名额分配（纯逻辑）：存活征召与观察者选岗各占一半，总数不超过 count。 */
    public record WaveQuota(int aliveShare, int pickTarget, boolean hasAlive, boolean hasPick) {

        /**
         * 拆分名额：count<=0 → 空分配（整波跳过，与指定类型波一致）；否则存活征召先取 ceil(count/2)，
         * 剩余归观察者选岗；某一通道无候选时该通道不发邀请（名额不转移，防超招）。
         */
        public static WaveQuota split(int count, int alivePoolSize, boolean hasObservers) {
            if (count <= 0) {
                return new WaveQuota(0, 0, false, false);
            }
            int aliveShare = alivePoolSize > 0 ? Math.min(alivePoolSize, (count + 1) / 2) : 0;
            int pickTarget = Math.max(0, count - aliveShare);
            return new WaveQuota(aliveShare, pickTarget, aliveShare > 0, pickTarget > 0 && hasObservers);
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
            String modeStr = str(o, "mode", "RESURRECTION");
            Mode mode;
            if ("RECRUIT".equalsIgnoreCase(modeStr)) {
                // 兼容旧版管理面板持久化的 "RECRUIT"（v2.15.x 前 Modes 数组值）：映射为 RESURRECTION（仅死亡可收到）
                mode = Mode.legacyRecruit();
                errors.add("waves[" + i + "]: 旧模式 'RECRUIT' 已映射为 RESURRECTION（兼容存量配置）");
            } else {
                try {
                    mode = Mode.valueOf(modeStr);
                } catch (Exception e) {
                    errors.add("waves[" + i + "]: 无效 mode '" + modeStr + "'");
                    continue;
                }
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
