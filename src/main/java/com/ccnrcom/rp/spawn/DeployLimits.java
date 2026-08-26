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
import java.util.Optional;

/**
 * 部署人数限制（纯逻辑，无 MC 依赖）：管理员可在管理面板「限制」页配置规则——
 * FACTION（某阵营在职人数上限）/ PROFESSION（某职业在职人数上限）/ GLOBAL（通用角色上限，职业无专属时兜底）。
 * 部署前由 {@link SpawnFramework#deploy} 统一检测：职业维度超限或阵营维度超限即拒绝。
 */
public final class DeployLimits {

    public enum Type {
        FACTION,
        PROFESSION,
        GLOBAL
    }

    /** 限制规则：id 用于 CRUD；GLOBAL 的 target 为空串。 */
    public record Rule(String id, Type type, String target, int limit) {
        public Rule {
            target = target == null ? "" : target;
            limit = Math.max(0, limit);
        }
    }

    private DeployLimits() {}

    /** 解析 limits.json 的 rules 数组；畸形条目跳过不崩服。 */
    public static List<Rule> parse(JsonObject root) {
        List<Rule> out = new ArrayList<>();
        if (root == null || !root.has("rules") || !root.get("rules").isJsonArray()) {
            return out;
        }
        JsonArray arr = root.getAsJsonArray("rules");
        for (int i = 0; i < arr.size(); i++) {
            JsonElement e = arr.get(i);
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            String typeStr = str(o, "type", "").toUpperCase(java.util.Locale.ROOT);
            Type type;
            try {
                type = Type.valueOf(typeStr);
            } catch (Exception ex) {
                continue; // 未知类型跳过
            }
            String id = str(o, "id", "");
            if (id.isBlank()) {
                id = type.name().toLowerCase(java.util.Locale.ROOT) + "-" + i;
            }
            int limit = num(o, "limit", -1);
            if (limit < 0) {
                continue; // 缺少有效上限跳过
            }
            out.add(new Rule(id, type, str(o, "target", ""), limit));
        }
        return out;
    }

    /** 职业维度有效上限：PROFESSION 专属规则优先，否则 GLOBAL 兜底；无则返回 -1（不限）。 */
    public static int professionLimit(List<Rule> rules, String professionId) {
        if (rules == null || professionId == null) {
            return -1;
        }
        int global = -1;
        for (Rule r : rules) {
            if (r.type() == Type.PROFESSION && r.target().equals(professionId)) {
                return r.limit();
            }
            if (r.type() == Type.GLOBAL) {
                global = r.limit();
            }
        }
        return global;
    }

    /** 阵营维度有效上限：FACTION 专属规则；无则 -1（不限）。 */
    public static int factionLimit(List<Rule> rules, String factionId) {
        if (rules == null || factionId == null) {
            return -1;
        }
        for (Rule r : rules) {
            if (r.type() == Type.FACTION && r.target().equals(factionId)) {
                return r.limit();
            }
        }
        return -1;
    }

    /** 拒绝结果：key=语言键；target=超限目标（职业/阵营 id）；limit=上限值。 */
    public record Denial(String key, String target, int limit) {}

    /**
     * 部署检测：职业在职数超职业上限，或阵营在职数超阵营上限 → 拒绝。
     * 返回拒绝原因（空=允许）。profCount/facCount 为「不含部署者本人」的在职数。
     */
    public static Optional<Denial> check(
            List<Rule> rules, String professionId, String factionId, int profCount, int facCount) {
        int profLimit = professionLimit(rules, professionId);
        if (profLimit >= 0 && profCount >= profLimit) {
            return Optional.of(new Denial("ccnr_rp.spawn.limit.profession_full", professionId, profLimit));
        }
        int facLimit = factionLimit(rules, factionId);
        if (facLimit >= 0 && facCount >= facLimit) {
            return Optional.of(new Denial("ccnr_rp.spawn.limit.faction_full", factionId, facLimit));
        }
        return Optional.empty();
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static int num(JsonObject o, String key, int def) {
        try {
            return o.has(key) ? o.get(key).getAsInt() : def;
        } catch (Exception e) {
            return def;
        }
    }
}
