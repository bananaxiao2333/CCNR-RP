/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.faction;

import com.ccnrcom.rp.faction.FactionModels.Faction;
import com.ccnrcom.rp.faction.FactionModels.FactionGroup;
import com.ccnrcom.rp.faction.FactionModels.ParseResult;
import com.ccnrcom.rp.faction.FactionModels.RelationRule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阵营关系图（纯逻辑，无 MC 依赖）。
 *
 * <p>解析与查询规则：
 * <ul>
 *   <li>自身关系恒为 FRIENDLY（同阵营/同组内默认友好）。</li>
 *   <li>优先级：单点（阵营×阵营）&gt; 组×组 &gt; 组内；同优先级重复声明后者覆盖并 WARN。</li>
 *   <li>未声明关系默认 NEUTRAL。</li>
 *   <li>关系是对称的：声明 (a,b) 同时作用于 (b,a)。</li>
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
        List<String> factionIds = new ArrayList<>();
        for (Faction f : factionList) {
            if (factions.containsKey(f.id())) {
                warnings.add("阵营重复声明 id='" + f.id() + "'，后者覆盖");
            }
            factions.put(f.id(), f);
            factionIds.add(f.id());
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
            boolean fromOk = factions.containsKey(r.from()) || groups.containsKey(r.from());
            boolean toOk = factions.containsKey(r.to()) || groups.containsKey(r.to());
            if (!fromOk || !toOk) {
                errors.add("relations[" + i + "]: 未知的 from/to（'" + r.from() + "' 或 '" + r.to() + "'）");
            }
        }
        if (!errors.isEmpty()) {
            return ParseResult.failure(errors);
        }

        // 同优先级重复声明 → 后者覆盖并 WARN（记录位置）
        for (int i = 0; i < rules.size(); i++) {
            RelationRule r = rules.get(i);
            for (int j = i + 1; j < rules.size(); j++) {
                RelationRule s = rules.get(j);
                if (sameRule(r, s) && !r.type().equals(s.type())) {
                    warnings.add("relations[" + j + "] 覆盖了 relations[" + i + "] 的声明");
                }
            }
        }
        return new ParseResult(new FactionGraph(factions, groups, rules, warnings), List.of(), warnings);
    }

    private static boolean sameRule(RelationRule a, RelationRule b) {
        return a.from().equals(b.from()) && a.to().equals(b.to()) || a.from().equals(b.to()) && a.to().equals(b.from());
    }

    public RelationType resolve(String a, String b) {
        if (!factions.containsKey(a) || !factions.containsKey(b)) {
            throw new IllegalArgumentException("未知阵营: " + a + " / " + b);
        }
        if (a.equals(b)) {
            return RelationType.FRIENDLY;
        }
        // 同优先级重复声明后者覆盖：按优先级从声明末端向前查找
        // 1) 单点声明（阵营×阵营）
        for (int i = rules.size() - 1; i >= 0; i--) {
            RelationRule r = rules.get(i);
            if (matchesPair(r, a, b) && !groups.containsKey(r.from()) && !groups.containsKey(r.to())) {
                return r.type();
            }
        }
        // 2) 组/混合规则：任一侧为组即按成员展开（覆盖双向往返；组内规则天然归此层）
        for (int i = rules.size() - 1; i >= 0; i--) {
            RelationRule r = rules.get(i);
            if (groups.containsKey(r.from()) || groups.containsKey(r.to())) {
                if (sideMatches(r.from(), a) && sideMatches(r.to(), b)
                        || sideMatches(r.from(), b) && sideMatches(r.to(), a)) {
                    return r.type();
                }
            }
        }
        return RelationType.NEUTRAL;
    }

    /** 侧匹配：直接命中或作为组成员命中。 */
    private boolean sideMatches(String side, String id) {
        return side.equals(id) || inGroup(side, id);
    }

    private boolean matchesPair(RelationRule r, String a, String b) {
        return r.from().equals(a) && r.to().equals(b) || r.from().equals(b) && r.to().equals(a);
    }

    private boolean inGroup(String groupId, String factionId) {
        FactionGroup g = groups.get(groupId);
        return g != null && g.memberIds().contains(factionId);
    }

    public Map<String, Faction> factions() {
        return Collections.unmodifiableMap(factions);
    }

    public Map<String, FactionGroup> groups() {
        return Collections.unmodifiableMap(groups);
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }
}
