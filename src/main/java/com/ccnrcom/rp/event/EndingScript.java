/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.event;

import com.google.gson.JsonObject;
import java.util.Optional;

/**
 * 结局剧本（P15 §4.6）：{@code ending.json} 的解析模型——{@code /rp end} 触发时播动画、通报、
 * 结算并复位。纯逻辑（仅 Gson），无 MC 依赖。
 *
 * <pre>{@code
 * {
 *   "version": 1,
 *   "animation": "game_end",
 *   "notify": {"titleKey": "ccnr_rp.mode.invasion.end", "subtitleKey": "...", "actionbarKey": "..."},
 *   "resetToPhase": "approach",
 *   "reward": {"xp": 20, "applyTo": "@a"}
 * }
 * }</pre>
 */
public final class EndingScript {

    /** 结局剧本（字段全部可缺省；缺省表示「该步不做」）。 */
    public record Script(
            String animation,
            String titleKey,
            String subtitleKey,
            String actionbarKey,
            String resetToPhase,
            long rewardXp,
            String applyTo) {

        public boolean hasNotify() {
            return !isBlank(titleKey) || !isBlank(subtitleKey) || !isBlank(actionbarKey);
        }

        public boolean hasReward() {
            return rewardXp > 0;
        }
    }

    private EndingScript() {}

    /** 解析结局剧本；缺文件/空对象返回空。resetToPhase 缺省为 ""（= 第 0 幕）。 */
    public static Optional<Script> parse(JsonObject root) {
        if (root == null || root.size() == 0) {
            return Optional.empty();
        }
        String animation = str(root, "animation", "");
        String resetToPhase = str(root, "resetToPhase", "");
        long rewardXp = num(root.has("reward") ? root.getAsJsonObject("reward") : new JsonObject(), "xp", 0);
        String applyTo = str(root.has("reward") ? root.getAsJsonObject("reward") : new JsonObject(), "applyTo", "@a");
        String titleKey = "";
        String subtitleKey = "";
        String actionbarKey = "";
        if (root.has("notify") && root.get("notify").isJsonObject()) {
            JsonObject notify = root.getAsJsonObject("notify");
            titleKey = str(notify, "titleKey", "");
            subtitleKey = str(notify, "subtitleKey", "");
            actionbarKey = str(notify, "actionbarKey", "");
        }
        return Optional.of(new Script(animation, titleKey, subtitleKey, actionbarKey, resetToPhase, rewardXp, applyTo));
    }

    private static String str(JsonObject o, String key, String def) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static long num(JsonObject o, String key, long def) {
        try {
            return o != null && o.has(key) ? o.get(key).getAsLong() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
