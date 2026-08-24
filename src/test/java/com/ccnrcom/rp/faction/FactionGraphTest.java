/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.faction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccnrcom.rp.faction.FactionModels.Faction;
import com.ccnrcom.rp.faction.FactionModels.FactionGroup;
import com.ccnrcom.rp.faction.FactionModels.ParseResult;
import com.ccnrcom.rp.faction.FactionModels.RelationRule;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** P1 验收：FactionGraph 解析/批量/优先级/非法输入。 */
class FactionGraphTest {

    private static final List<Faction> FACTIONS = List.of(
            new Faction("a", "A", "#FFFFFF", ""),
            new Faction("b", "B", "#FFFFFF", ""),
            new Faction("c", "C", "#FFFFFF", ""),
            new Faction("d", "D", "#FFFFFF", ""));

    private FactionGraph graph;

    @BeforeEach
    void setUp() {
        ParseResult r = FactionGraph.parse(
                FACTIONS,
                List.of(new FactionGroup("g1", List.of("a", "b")), new FactionGroup("g2", List.of("c", "d"))),
                List.of());
        assertTrue(r.success(), () -> r.errors().toString());
        graph = r.graph();
    }

    @Test
    void selfRelationIsFriendly() {
        assertEquals(RelationType.FRIENDLY, graph.resolve("a", "a"));
    }

    @Test
    void unstatedRelationDefaultsNeutral() {
        assertEquals(RelationType.NEUTRAL, graph.resolve("a", "c"));
    }

    @Test
    void singleFactionRuleWorksBothDirections() {
        ParseResult r =
                FactionGraph.parse(FACTIONS, List.of(), List.of(new RelationRule("a", "b", RelationType.HOSTILE)));
        assertTrue(r.success());
        assertEquals(RelationType.HOSTILE, r.graph().resolve("a", "b"));
        assertEquals(RelationType.HOSTILE, r.graph().resolve("b", "a"));
    }

    @Test
    void groupTimesGroupCoversEveryPairBothWays() {
        ParseResult r = FactionGraph.parse(
                FACTIONS,
                List.of(new FactionGroup("g1", List.of("a", "b")), new FactionGroup("g2", List.of("c", "d"))),
                List.of(new RelationRule("g1", "g2", RelationType.FRIENDLY)));
        assertTrue(r.success());
        FactionGraph g = r.graph();
        for (String x : List.of("a", "b")) {
            for (String y : List.of("c", "d")) {
                assertEquals(RelationType.FRIENDLY, g.resolve(x, y), x + " vs " + y);
                assertEquals(RelationType.FRIENDLY, g.resolve(y, x), y + " vs " + x);
            }
        }
        // 组内成员不受组×组规则影响
        assertEquals(RelationType.NEUTRAL, g.resolve("a", "b"));
    }

    @Test
    void groupInternalRuleAppliesToMembers() {
        ParseResult r = FactionGraph.parse(
                FACTIONS,
                List.of(new FactionGroup("g1", List.of("a", "b"))),
                List.of(new RelationRule("g1", "g1", RelationType.HOSTILE)));
        assertTrue(r.success());
        assertEquals(RelationType.HOSTILE, r.graph().resolve("a", "b"));
        assertEquals(RelationType.HOSTILE, r.graph().resolve("b", "a"));
    }

    @Test
    void singleRuleOverridesGroupRule() {
        ParseResult r = FactionGraph.parse(
                FACTIONS,
                List.of(new FactionGroup("g1", List.of("a", "b")), new FactionGroup("g2", List.of("c", "d"))),
                List.of(
                        new RelationRule("g1", "g2", RelationType.FRIENDLY),
                        new RelationRule("a", "c", RelationType.HOSTILE)));
        assertTrue(r.success());
        assertEquals(RelationType.HOSTILE, r.graph().resolve("a", "c"));
        assertEquals(RelationType.FRIENDLY, r.graph().resolve("b", "d"));
    }

    @Test
    void duplicateDeclarationWarnsAndLastWins() {
        ParseResult r = FactionGraph.parse(
                FACTIONS,
                List.of(),
                List.of(
                        new RelationRule("a", "b", RelationType.FRIENDLY),
                        new RelationRule("b", "a", RelationType.HOSTILE)));
        assertTrue(r.success());
        assertEquals(RelationType.HOSTILE, r.graph().resolve("a", "b"));
        assertFalse(r.warnings().isEmpty());
    }

    @Test
    void unknownIdFails() {
        ParseResult r =
                FactionGraph.parse(FACTIONS, List.of(), List.of(new RelationRule("a", "x", RelationType.FRIENDLY)));
        assertFalse(r.success());
        assertTrue(r.errors().get(0).contains("unknown") || r.errors().get(0).contains("relations[0]"));
    }

    @Test
    void mixedFactionGroupRuleFails() {
        ParseResult r =
                FactionGraph.parse(FACTIONS, List.of(), List.of(new RelationRule("a", "g1", RelationType.FRIENDLY)));
        assertFalse(r.success());
    }

    @Test
    void invalidRelationTypeFails() {
        assertFalse(FactionGraph.parse(FACTIONS, List.of(), List.of(new RelationRule("a", "b", null)))
                .success());
    }

    @Test
    void unknownMemberInGroupFails() {
        ParseResult r = FactionGraph.parse(FACTIONS, List.of(new FactionGroup("g1", List.of("a", "zzz"))), List.of());
        assertFalse(r.success());
    }

    @Test
    void resolveUnknownThrows() {
        assertThrows(IllegalArgumentException.class, () -> graph.resolve("a", "nope"));
    }
}
