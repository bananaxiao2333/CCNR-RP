/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.faction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccnrcom.rp.faction.FactionModels.Faction;
import com.ccnrcom.rp.faction.FactionModels.FactionGroup;
import com.ccnrcom.rp.faction.FactionModels.ParseResult;
import com.ccnrcom.rp.faction.FactionModels.RelationRule;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 入场电影阵营关系行合并（CinematicRelations.collapse）：按「阵营组 + 关系」合并可合并项。 */
class CinematicRelationsTest {

    private static final List<Faction> FACTIONS = List.of(
            new Faction("a", "A", "#FFFFFF", ""),
            new Faction("b", "B", "#FFFFFF", ""),
            new Faction("c", "C", "#FFFFFF", ""),
            new Faction("d", "D", "#FFFFFF", ""));

    private static List<JsonObject> collapse(List<FactionGroup> groups, List<RelationRule> rules, String myFactionId) {
        ParseResult r = FactionGraph.parse(FACTIONS, groups, rules);
        assertTrue(r.success(), () -> r.errors().toString());
        return CinematicRelations.collapse(r.graph(), myFactionId);
    }

    @Test
    void mergesUniformGroupToOneEntry() {
        // 组 g1=[b,c] 两个成员对玩家 a 都友好 → 合并为一条「g1 friendly」
        List<JsonObject> out = collapse(
                List.of(new FactionGroup("g1", List.of("b", "c")), new FactionGroup("g2", List.of("d"))),
                List.of(
                        new RelationRule(List.of("a"), List.of("b", "c"), RelationType.FRIENDLY),
                        new RelationRule(List.of("a"), List.of("d"), RelationType.HOSTILE)),
                "a");
        assertEquals(2, out.size());
        assertEquals("g1", out.get(0).get("name").getAsString());
        assertEquals("friendly", out.get(0).get("type").getAsString());
        assertEquals("g2", out.get(1).get("name").getAsString());
        assertEquals("hostile", out.get(1).get("type").getAsString());
    }

    @Test
    void mixedGroupFallsBackToPerFaction() {
        // 组 g1=[b,c] 内 b 敌对、c 友好（关系混杂）→ 不整组合并，逐一按阵营名显示
        List<JsonObject> out = collapse(
                List.of(new FactionGroup("g1", List.of("b", "c")), new FactionGroup("g2", List.of("d"))),
                List.of(
                        new RelationRule(List.of("a"), List.of("b"), RelationType.HOSTILE),
                        new RelationRule(List.of("a"), List.of("c"), RelationType.FRIENDLY),
                        new RelationRule(List.of("a"), List.of("d"), RelationType.FRIENDLY)),
                "a");
        assertEquals(3, out.size());
        assertEquals("B", out.get(0).get("name").getAsString());
        assertEquals("hostile", out.get(0).get("type").getAsString());
        assertEquals("C", out.get(1).get("name").getAsString());
        assertEquals("friendly", out.get(1).get("type").getAsString());
        // 单成员组 g2 关系一致 → 仍按组显示
        assertEquals("g2", out.get(2).get("name").getAsString());
        assertEquals("friendly", out.get(2).get("type").getAsString());
    }

    @Test
    void neutralDroppedAndUngroupedFactionStandalone() {
        // 无组；a→b 敌对、a→c 中立（不显示）
        List<JsonObject> out =
                collapse(List.of(), List.of(new RelationRule(List.of("a"), List.of("b"), RelationType.HOSTILE)), "a");
        assertEquals(1, out.size());
        assertEquals("B", out.get(0).get("name").getAsString());
        assertEquals("hostile", out.get(0).get("type").getAsString());
    }

    @Test
    void mergesUniformGroupToDisplayName() {
        // 组 g1 设置外显名称 "North"：合并条目用外显名称而非 id
        List<JsonObject> out = collapse(
                List.of(new FactionGroup("g1", "North", List.of("b", "c"))),
                List.of(new RelationRule(List.of("a"), List.of("b", "c"), RelationType.FRIENDLY)),
                "a");
        assertEquals(1, out.size());
        assertEquals("North", out.get(0).get("name").getAsString());
        assertEquals("friendly", out.get(0).get("type").getAsString());
    }

    @Test
    void allNeutralYieldsEmpty() {
        assertEquals(0, collapse(List.of(), List.of(), "a").size());
    }
}
