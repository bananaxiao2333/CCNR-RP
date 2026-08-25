/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.animation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 动画序列模型与解析（纯逻辑，无 MC 依赖）。
 * 步骤类型：TITLE / SUBTITLE(并入 TITLE) / ACTIONBAR / FADE / CAMERA(视场角+抖动) / PARTICLE / SOUND / GROUP(串并行) / CAMS(CMDCam 场景，param=scene)。
 * durationTicks 一律 >0 且 ≤ 12000（10 分钟上限），非法值钳制。
 */
public final class AnimationModels {

    public static final Set<String> TYPES =
            Set.of("TITLE", "ACTIONBAR", "FADE", "CAMERA", "PARTICLE", "SOUND", "GROUP", "CAMS");

    /** 动画步骤。 */
    public record Step(String type, Map<String, String> params, List<Step> children) {

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

        public long durationTicks() {
            long d = paramLong("durationTicks", 0);
            return Math.max(1, Math.min(d, 12000));
        }
    }

    /** 动画序列。 */
    public record Sequence(String id, List<Step> steps) {}

    private AnimationModels() {}

    /** 解析全部序列；返回错误（未知类型/缺字段，带 path）。 */
    public static List<String> parseAll(JsonObject root, Map<String, Sequence> out) {
        List<String> errors = new ArrayList<>();
        if (root.has("sequences")) {
            JsonObject seqObj = root.getAsJsonObject("sequences");
            for (Map.Entry<String, JsonElement> e : seqObj.entrySet()) {
                if (!e.getValue().isJsonObject()) {
                    errors.add("sequences." + e.getKey() + ": 不是对象");
                    continue;
                }
                Optional<Sequence> seq = parseSequence(e.getKey(), e.getValue().getAsJsonObject(), errors);
                seq.ifPresent(s -> out.put(s.id(), s));
            }
        }
        return errors;
    }

    public static Optional<Sequence> parseSequence(String id, JsonObject o, List<String> errors) {
        if (!o.has("steps") || !o.get("steps").isJsonArray()) {
            errors.add("sequences." + id + ": 缺少 steps 数组");
            return Optional.empty();
        }
        List<Step> steps = new ArrayList<>();
        JsonArray arr = o.getAsJsonArray("steps");
        for (int i = 0; i < arr.size(); i++) {
            Step s = parseStep(arr.get(i), "sequences." + id + ".steps[" + i + "]", errors);
            if (s != null) {
                steps.add(s);
            }
        }
        return Optional.of(new Sequence(id, steps));
    }

    public static Step parseStep(JsonElement el, String path, List<String> errors) {
        if (!el.isJsonObject()) {
            errors.add(path + ": 不是对象");
            return null;
        }
        JsonObject o = el.getAsJsonObject();
        String type = o.has("type") ? o.get("type").getAsString() : "";
        if (!TYPES.contains(type)) {
            errors.add(path + ": 未知步骤类型 '" + type + "'");
            return null;
        }
        Map<String, String> params = new LinkedHashMap<>();
        o.entrySet().forEach(e -> {
            if (!e.getKey().equals("type") && !e.getKey().equals("steps")) {
                params.put(
                        e.getKey(),
                        e.getValue().isJsonPrimitive()
                                ? e.getValue().getAsString()
                                : e.getValue().toString());
            }
        });
        List<Step> children = new ArrayList<>();
        if (o.has("steps") && o.get("steps").isJsonArray()) {
            JsonArray arr = o.getAsJsonArray("steps");
            for (int i = 0; i < arr.size(); i++) {
                Step c = parseStep(arr.get(i), path + ".steps[" + i + "]", errors);
                if (c != null) {
                    children.add(c);
                }
            }
        }
        return new Step(type, params, children);
    }
}
