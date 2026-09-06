/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 事件/阶段/触发器领域模型与 JSON 解析（纯逻辑，无 MC 依赖）。 */
public final class EventModels {

    /** 游戏阶段（内嵌行为序列：阶段开始时执行）。advanceOn=条件驱动触发器（非空时不按时长自动推进）。 */
    public record GamePhase(String id, int order, long durationMinutes, Trigger advanceOn, List<JsonObject> steps) {
        public GamePhase(String id, int order, long durationMinutes) {
            this(id, order, durationMinutes, null, List.of());
        }

        public GamePhase(String id, int order, long durationMinutes, List<JsonObject> steps) {
            this(id, order, durationMinutes, null, steps);
        }

        /** 是否条件驱动（advanceOn 非空）——不按时长推进，由触发器命中时 advance。 */
        public boolean conditionDriven() {
            return advanceOn != null;
        }
    }

    /** 触发器（type + 参数）。 */
    public record Trigger(Type type, Map<String, String> params) {
        public enum Type {
            ON_PHASE_START,
            ON_PHASE_END,
            ON_TIME,
            PERIODIC,
            CONDITION
        }

        public String param(String key, String def) {
            return params.getOrDefault(key, def);
        }

        public long paramLong(String key, long def) {
            try {
                return Long.parseLong(param(key, String.valueOf(def)));
            } catch (NumberFormatException e) {
                return def;
            }
        }
    }

    /** 事件中的任务（供经验结算）。 */
    public record Task(String id, int xp) {}

    /** 事件定义（内嵌行为序列：事件开始时执行）。 */
    public record EventDefinition(
            String id,
            boolean enabled,
            List<Trigger> triggers,
            List<Task> tasks,
            String startAnimation,
            String spawnWave,
            String startSequence,
            String notifyTitleKey,
            long durationSeconds,
            EventState state,
            List<JsonObject> steps) {

        public EventDefinition(
                String id,
                boolean enabled,
                List<Trigger> triggers,
                List<Task> tasks,
                String startAnimation,
                String spawnWave,
                String startSequence,
                String notifyTitleKey,
                long durationSeconds,
                EventState state) {
            this(
                    id,
                    enabled,
                    triggers,
                    tasks,
                    startAnimation,
                    spawnWave,
                    startSequence,
                    notifyTitleKey,
                    durationSeconds,
                    state,
                    List.of());
        }

        public EventDefinition withState(EventState s) {
            return new EventDefinition(
                    id,
                    enabled,
                    triggers,
                    tasks,
                    startAnimation,
                    spawnWave,
                    startSequence,
                    notifyTitleKey,
                    durationSeconds,
                    s,
                    steps);
        }
    }

    public enum EventState {
        SCHEDULED,
        RUNNING,
        SETTLED;
    }

    /** 触发器求值上下文（快照式纯数据）。 */
    public record TriggerContext(
            String currentPhaseId,
            String endedPhaseId,
            long ticksInPhase,
            long day,
            long tickOfDay,
            long secondsSinceStart,
            int deadCount,
            int aliveCount,
            Map<String, Integer> scoreboard,
            boolean phaseStartedThisTick,
            boolean phaseEndedThisTick) {}

    private EventModels() {}

    private static final Set<String> CONDITION_TYPES = Set.of("DEAD_COUNT", "ALIVE_COUNT", "SCOREBOARD");
    private static final Set<String> OPS = Set.of(">=", "<=", ">", "<", "==");

    /** 解析升级版：返回错误列表（空=成功）。 */
    public static List<String> parseEvents(JsonObject root) {
        List<String> errors = new ArrayList<>();
        JsonArray arr = root.has("events") ? root.getAsJsonArray("events") : new JsonArray();
        for (int i = 0; i < arr.size(); i++) {
            if (!arr.get(i).isJsonObject()) {
                errors.add("events[" + i + "]: 不是对象");
                continue;
            }
            JsonObject o = arr.get(i).getAsJsonObject();
            if (!o.has("id")) {
                errors.add("events[" + i + "]: 缺少 id");
            }
            if (o.has("triggers")) {
                JsonArray ts = o.getAsJsonArray("triggers");
                for (int j = 0; j < ts.size(); j++) {
                    List<String> e =
                            validateTrigger(ts.get(j).getAsJsonObject(), "events[" + i + "].triggers[" + j + "]");
                    errors.addAll(e);
                }
            }
        }
        return errors;
    }

    public static Trigger parseTrigger(JsonObject o, String path) {
        Trigger.Type t;
        try {
            t = Trigger.Type.valueOf(str(o, "type", "").toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            return null;
        }
        Map<String, String> params = new LinkedHashMap<>();
        o.entrySet().forEach(e -> {
            if ("type".equals(e.getKey())) {
                return; // 外层类型不入参数表（CONDITION 子类型用独立 cond 字段）
            }
            params.put(e.getKey(), e.getValue().getAsString());
        });
        return new Trigger(t, params);
    }

    private static List<String> validateTrigger(JsonObject o, String path) {
        List<String> errors = new ArrayList<>();
        Trigger tr = parseTrigger(o, path);
        if (tr == null) {
            errors.add(path + ": 无效 type");
            return errors;
        }
        switch (tr.type()) {
            case ON_PHASE_START, ON_PHASE_END -> {
                if (!tr.param("phase", "").isEmpty() && !tr.param("phase", "").matches("[a-zA-Z0-9_-]+")) {
                    errors.add(path + ": 无效 phase 名");
                }
            }
            case ON_TIME -> {
                if (tr.param("day", "").isEmpty() || tr.param("tickOfDay", "").isEmpty()) {
                    errors.add(path + ": ON_TIME 需要 day 与 tickOfDay");
                }
            }
            case PERIODIC -> {
                if (tr.paramLong("seconds", -1) <= 0) {
                    errors.add(path + ": PERIODIC 需要正数 seconds");
                }
            }
            case CONDITION -> {
                String type = tr.param("cond", "");
                if (!CONDITION_TYPES.contains(type)) {
                    errors.add(path + ": 无效 CONDITION 类型 " + type);
                }
                String op = tr.param("op", "");
                if (!OPS.contains(op)) {
                    errors.add(path + ": 无效比较符 " + op);
                }
                if (tr.param("value", "").isEmpty()) {
                    errors.add(path + ": CONDITION 缺少 value");
                }
            }
            default -> {}
        }
        return errors;
    }

    public static List<GamePhase> parsePhases(JsonObject root) {
        List<GamePhase> out = new ArrayList<>();
        if (root.has("phases")) {
            for (JsonElement e : root.getAsJsonArray("phases")) {
                JsonObject o = e.getAsJsonObject();
                out.add(new GamePhase(
                        str(o, "id", "?"),
                        (int) num(o, "order", out.size()),
                        num(o, "durationMinutes", 30),
                        parseAdvanceOn(o),
                        parseSteps(o)));
            }
        }
        return out;
    }

    /** 阶段条件驱动触发器（advanceOn）；非对象或缺参数时返回 null（回退按时长推进）。 */
    private static Trigger parseAdvanceOn(JsonObject o) {
        if (!o.has("advanceOn") || !o.get("advanceOn").isJsonObject()) {
            return null;
        }
        return parseTrigger(o.getAsJsonObject("advanceOn"), "advanceOn");
    }

    /** 内嵌行为序列（sequence 数组：{type, ...参数}）。 */
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

    public static Optional<EventDefinition> parseEvent(JsonObject o) {
        if (!o.has("id")) {
            return Optional.empty();
        }
        List<Trigger> triggers = new ArrayList<>();
        if (o.has("triggers")) {
            for (JsonElement e : o.getAsJsonArray("triggers")) {
                Trigger t = parseTrigger(e.getAsJsonObject(), "triggers[]");
                if (t != null) {
                    triggers.add(t);
                }
            }
        }
        List<Task> tasks = new ArrayList<>();
        if (o.has("tasks")) {
            for (JsonElement e : o.getAsJsonArray("tasks")) {
                JsonObject t = e.getAsJsonObject();
                tasks.add(new Task(str(t, "id", "task"), (int) num(t, "xp", 50)));
            }
        }
        return Optional.of(new EventDefinition(
                str(o, "id", "?"),
                !o.has("enabled") || o.get("enabled").getAsBoolean(),
                triggers,
                tasks,
                str(o, "startAnimation", ""),
                str(o, "spawnWave", ""),
                str(o, "startSequence", ""),
                str(o, "notifyTitleKey", ""),
                num(o, "durationSeconds", 0),
                EventState.SCHEDULED,
                parseSteps(o)));
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
