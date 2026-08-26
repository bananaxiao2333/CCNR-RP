/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.faction;

import com.ccnrcom.rp.faction.FactionModels.Faction;
import com.ccnrcom.rp.faction.FactionModels.FactionGroup;
import com.ccnrcom.rp.faction.FactionModels.ParseResult;
import com.ccnrcom.rp.faction.FactionModels.RelationEdge;
import com.ccnrcom.rp.faction.FactionModels.RelationRule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 阵营关系图（纯逻辑，无 MC 依赖）。
 *
 * <p>解析与查询规则（多对多 + 从上到下优先级）：
 * <ul>
 *   <li>自身关系恒为 FRIENDLY（同阵营友好）。</li>
 *   <li>每条规则 from/to 为 id 列表（阵营或组），生效范围 = 笛卡尔积；关系双向对称。</li>
 *   <li>优先级：关系列表**从上到下**，先声明（靠前）的规则命中即生效；重复声明后者被忽略并 WARN。</li>
 *   <li>未命中任何规则的阵营对默认 NEUTRAL。</li>
 * </ul>
 */
public final class FactionGraph {
    private final Map<String, Faction> factions;
    private final Map<String, FactionGroup> groups;
    private final List<RelationRule> rules;
    private final List<String> warnings;

    private FactionGraph(
            Map<String, Faction> factions,
            Map<String, FactionGroup> groups,
            List<RelationRule> rules,
            List<String> warnings) {
        this.factions = factions;
        this.groups = groups;
        this.rules = rules;
        this.warnings = warnings;
    }

    public static ParseResult parse(
            List<Faction> factionList, List<FactionGroup> groupList, List<RelationRule> ruleList) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        Map<String, Faction> factions = new LinkedHashMap<>();
        for (Faction f : factionList) {
            if (factions.containsKey(f.id())) {
                warnings.add("阵营重复声明 id='" + f.id() + "'，后者覆盖");
            }
            factions.put(f.id(), f);
        }

        Map<String, FactionGroup> groups = new LinkedHashMap<>();
        for (FactionGroup g : groupList) {
            if (groups.containsKey(g.id())) {
                warnings.add("阵营组重复声明 id='" + g.id() + "'，后者覆盖");
            }
            for (String m : g.memberIds()) {
                if (!factions.containsKey(m)) {
                    errors.add("阵营组 '" + g.id() + "' 的成员 '" + m + "' 不存在");
                }
            }
            groups.put(g.id(), g);
        }

        // 命名空间冲突检查（阵营与组共享 id 命名空间）
        for (String id : factions.keySet()) {
            if (groups.containsKey(id)) {
                errors.add("id '" + id + "' 同时是阵营与组");
            }
        }
        List<RelationRule> rules = new ArrayList<>(ruleList);
        for (int i = 0; i < ruleList.size(); i++) {
            RelationRule r = ruleList.get(i);
            if (r.type() == null) {
                errors.add("relations[" + i + "]: 无效类型 null");
                continue;
            }
            if (r.from() == null || r.from().isEmpty() || r.to() == null || r.to().isEmpty()) {
                errors.add("relations[" + i + "]: from/to 不能为空");
                continue;
            }
            for (String side : r.from()) {
                if (!known(side, factions, groups)) {
                    errors.add("relations[" + i + "]: 未知的 from 项 '" + side + "'");
                }
            }
            for (String side : r.to()) {
                if (!known(side, factions, groups)) {
                    errors.add("relations[" + i + "]: 未知的 to 项 '" + side + "'");
                }
            }
        }
        if (!errors.isEmpty()) {
            return ParseResult.failure(errors);
        }

        // 重复声明（后者被前者覆盖）：后者被忽略并 WARN（从上到下优先级）
        for (int i = 0; i < rules.size(); i++) {
            for (int j = i + 1; j < rules.size(); j++) {
                if (covers(rules.get(i), rules.get(j), factions, groups)) {
                    warnings.add("relations[" + j + "] 已被 relations[" + i + "] 覆盖（从上到下优先级，忽略）");
                }
            }
        }
        return new ParseResult(new FactionGraph(factions, groups, rules, warnings), List.of(), warnings);
    }

    private static boolean known(String id, Map<String, Faction> factions, Map<String, FactionGroup> groups) {
        return factions.containsKey(id) || groups.containsKey(id);
    }

    /**
     * 判断规则 a 的生效阵营对集合是否覆盖规则 b（用于重复声明 WARN）。
     * 覆盖 = b 展开出的每个阵营对（双向）都在 a 展开出的阵营对集合中。
     */
    private static boolean covers(
            RelationRule a, RelationRule b, Map<String, Faction> factions, Map<String, FactionGroup> groups) {
        Set<String> froms = expand(a.from(), factions, groups);
        Set<String> tos = expand(a.to(), factions, groups);
        for (String x : expand(b.from(), factions, groups)) {
            for (String y : expand(b.to(), factions, groups)) {
                if (x.equals(y)) {
                    continue;
                }
                boolean hit = (froms.contains(x) && tos.contains(y)) || (froms.contains(y) && tos.contains(x));
                if (!hit) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 侧展开为阵营 id 集合（组展开为成员）；静态版供解析期校验使用。 */
    private static Set<String> expand(
            List<String> side, Map<String, Faction> factions, Map<String, FactionGroup> groups) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : side) {
            if (factions.containsKey(s)) {
                out.add(s);
            } else {
                FactionGroup g = groups.get(s);
                if (g != null) {
                    out.addAll(g.memberIds());
                }
            }
        }
        return out;
    }

    /**
     * 查询 a ↔ b 的生效关系。
     * 从上到下扫描规则：先命中的规则生效；未命中默认 NEUTRAL；自身 FRIENDLY。
     */
    public RelationType resolve(String a, String b) {
        if (!factions.containsKey(a) || !factions.containsKey(b)) {
            throw new IllegalArgumentException("未知阵营: " + a + " / " + b);
        }
        if (a.equals(b)) {
            return RelationType.FRIENDLY;
        }
        for (RelationRule r : rules) {
            if (matchesPair(r, a, b)) {
                return r.type();
            }
        }
        return RelationType.NEUTRAL;
    }

    /** 规则是否命中 a↔b（任一方向；from 侧命中一方、to 侧命中另一方）。 */
    private boolean matchesPair(RelationRule r, String a, String b) {
        return (sideHit(r.from(), a) && sideHit(r.to(), b)) || (sideHit(r.from(), b) && sideHit(r.to(), a));
    }

    /** 侧命中：id 直接命中或作为组成员命中。 */
    private boolean sideHit(List<String> side, String id) {
        for (String s : side) {
            if (s.equals(id)) {
                return true;
            }
            FactionGroup g = groups.get(s);
            if (g != null && g.memberIds().contains(id)) {
                return true;
            }
        }
        return false;
    }

    /** 侧展开为阵营 id 集合（组展开为成员）。 */
    private Set<String> expand(List<String> side) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : side) {
            if (factions.containsKey(s)) {
                out.add(s);
            } else {
                FactionGroup g = groups.get(s);
                if (g != null) {
                    out.addAll(g.memberIds());
                }
            }
        }
        return out;
    }

    /**
     * 关系测定图的边：所有「有规则生效」的阵营对（a&lt;b 去重），类型 = 从上到下首个命中规则的类型。
     * 未声明关系的阵营对不产生边。
     */
    public List<RelationEdge> edges() {
        Map<String, RelationEdge> byPair = new LinkedHashMap<>();
        for (RelationRule r : rules) {
            Set<String> froms = expand(r.from());
            Set<String> tos = expand(r.to());
            for (String x : froms) {
                for (String y : tos) {
                    if (x.equals(y)) {
                        continue;
                    }
                    String key = x.compareTo(y) < 0 ? x + "|" + y : y + "|" + x;
                    byPair.putIfAbsent(
                            key, new RelationEdge(x.compareTo(y) < 0 ? x : y, x.compareTo(y) < 0 ? y : x, r.type()));
                }
            }
        }
        return new ArrayList<>(byPair.values());
    }

    public Map<String, Faction> factions() {
        return Collections.unmodifiableMap(factions);
    }

    public Map<String, FactionGroup> groups() {
        return Collections.unmodifiableMap(groups);
    }

    public List<RelationRule> rules() {
        return List.copyOf(rules);
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }
}
