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
import com.ccnrcom.rp.faction.FactionModels.RelationEdge;
import com.ccnrcom.rp.faction.FactionModels.RelationRule;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** P1 验收：FactionGraph 多对多解析/从上到下优先级/组展开/非法输入。 */
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
    void manyToManyRuleCoversCartesianProduct() {
        // from:[a,b] × to:[c,d] → a-c, a-d, b-c, b-d 全部友好
        ParseResult r = FactionGraph.parse(
                FACTIONS,
                List.of(),
                List.of(new RelationRule(List.of("a", "b"), List.of("c", "d"), RelationType.FRIENDLY)));
        assertTrue(r.success(), () -> r.errors().toString());
        FactionGraph g = r.graph();
        for (String x : List.of("a", "b")) {
            for (String y : List.of("c", "d")) {
                assertEquals(RelationType.FRIENDLY, g.resolve(x, y), x + " vs " + y);
                assertEquals(RelationType.FRIENDLY, g.resolve(y, x), y + " vs " + x);
            }
        }
        // 非组合（a-b、c-d）不受影响
        assertEquals(RelationType.NEUTRAL, g.resolve("a", "b"));
        assertEquals(RelationType.NEUTRAL, g.resolve("c", "d"));
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
    void internalRuleConnectsAllPairsInList() {
        // 内部关系：单列表内两两互设（from=to 同一列表）
        ParseResult r = FactionGraph.parse(
                FACTIONS, List.of(), List.of(new RelationRule(List.of("a", "b", "c"), RelationType.FRIENDLY)));
        assertTrue(r.success(), () -> r.errors().toString());
        FactionGraph g = r.graph();
        assertEquals(RelationType.FRIENDLY, g.resolve("a", "b"));
        assertEquals(RelationType.FRIENDLY, g.resolve("a", "c"));
        assertEquals(RelationType.FRIENDLY, g.resolve("b", "c"));
        // 列表外不受影响
        assertEquals(RelationType.NEUTRAL, g.resolve("a", "d"));
        // 边数 = C(3,2) = 3
        assertEquals(3, g.edges().size(), () -> g.edges().toString());
    }

    @Test
    void jsonOmittedToIsInternalRelation() {
        // 配置省略 to = 内部关系（列表内两两互设）
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        com.google.gson.JsonArray fa = new com.google.gson.JsonArray();
        for (String id : List.of("a", "b", "c", "d")) {
            com.google.gson.JsonObject f = new com.google.gson.JsonObject();
            f.addProperty("id", id);
            fa.add(f);
        }
        root.add("factions", fa);
        com.google.gson.JsonArray ra = new com.google.gson.JsonArray();
        com.google.gson.JsonObject rel = new com.google.gson.JsonObject();
        com.google.gson.JsonArray from = new com.google.gson.JsonArray();
        from.add("a");
        from.add("b");
        from.add("c");
        rel.add("from", from);
        rel.addProperty("type", "friendly"); // 无 to
        ra.add(rel);
        root.add("relations", ra);
        ParseResult r = FactionManager.parse(root);
        assertTrue(r.success(), () -> r.errors().toString());
        assertEquals(RelationType.FRIENDLY, r.graph().resolve("a", "b"));
        assertEquals(RelationType.FRIENDLY, r.graph().resolve("b", "c"));
        assertEquals(RelationType.NEUTRAL, r.graph().resolve("a", "d"));
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
    void firstDeclaredRuleWinsOverLaterGroupRule() {
        // 从上到下优先级：先声明（靠前）的规则优先——具体规则在前，组规则在后
        ParseResult r = FactionGraph.parse(
                FACTIONS,
                List.of(new FactionGroup("g1", List.of("a", "b")), new FactionGroup("g2", List.of("c", "d"))),
                List.of(
                        new RelationRule("a", "c", RelationType.HOSTILE),
                        new RelationRule("g1", "g2", RelationType.FRIENDLY)));
        assertTrue(r.success());
        assertEquals(RelationType.HOSTILE, r.graph().resolve("a", "c"));
        assertEquals(RelationType.FRIENDLY, r.graph().resolve("b", "d"));
    }

    @Test
    void duplicateDeclarationFirstWinsAndWarns() {
        ParseResult r = FactionGraph.parse(
                FACTIONS,
                List.of(),
                List.of(
                        new RelationRule("a", "b", RelationType.FRIENDLY),
                        new RelationRule("b", "a", RelationType.HOSTILE)));
        assertTrue(r.success());
        // 从上到下优先级：先声明的 FRIENDLY 生效，后声明的被忽略
        assertEquals(RelationType.FRIENDLY, r.graph().resolve("a", "b"));
        assertFalse(r.warnings().isEmpty());
    }

    @Test
    void unknownIdFails() {
        ParseResult r =
                FactionGraph.parse(FACTIONS, List.of(), List.of(new RelationRule("a", "x", RelationType.FRIENDLY)));
        assertFalse(r.success());
        assertTrue(r.errors().get(0).contains("relations[0]"));
    }

    @Test
    void mixedFactionAndGroupRuleAllowed() {
        // 多对多：单阵营与组混合合法（组自动展开）
        ParseResult r = FactionGraph.parse(
                FACTIONS,
                List.of(new FactionGroup("g1", List.of("a", "b"))),
                List.of(new RelationRule("a", "g1", RelationType.HOSTILE)));
        assertTrue(r.success(), () -> r.errors().toString());
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
    void emptySideFails() {
        ParseResult r = FactionGraph.parse(
                FACTIONS, List.of(), List.of(new RelationRule(List.of(), List.of("b"), RelationType.FRIENDLY)));
        assertFalse(r.success());
    }

    @Test
    void edgesReturnsDeclaredPairsWithPriorityType() {
        ParseResult r = FactionGraph.parse(
                FACTIONS,
                List.of(),
                List.of(
                        new RelationRule(List.of("a", "b"), List.of("c", "d"), RelationType.FRIENDLY),
                        new RelationRule("a", "c", RelationType.HOSTILE)));
        assertTrue(r.success(), () -> r.errors().toString());
        List<RelationEdge> edges = r.graph().edges();
        // a-c 先声明命中 → 友好（第一条规则），其余 a-d/b-c/b-d 友好
        RelationEdge ac = edges.stream()
                .filter(e -> (e.a().equals("a") && e.b().equals("c")))
                .findFirst()
                .orElse(null);
        assertEquals(RelationType.FRIENDLY, ac.type());
        assertEquals(4, edges.size(), () -> edges.toString());
    }

    @Test
    void resolveUnknownThrows() {
        assertThrows(IllegalArgumentException.class, () -> graph.resolve("a", "nope"));
    }

    @Test
    void factionMusicParsesFromConfig() {
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        com.google.gson.JsonArray fa = new com.google.gson.JsonArray();
        com.google.gson.JsonObject f = new com.google.gson.JsonObject();
        f.addProperty("id", "x");
        f.addProperty("name", "X");
        f.addProperty("music", "audio/x.ogg");
        fa.add(f);
        root.add("factions", fa);
        ParseResult r = FactionManager.parse(root);
        assertTrue(r.success(), () -> r.errors().toString());
        assertEquals("audio/x.ogg", r.graph().factions().get("x").music());
    }

    @Test
    void factionMusicDefaultsEmpty() {
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        com.google.gson.JsonArray fa = new com.google.gson.JsonArray();
        com.google.gson.JsonObject f = new com.google.gson.JsonObject();
        f.addProperty("id", "x");
        f.addProperty("name", "X");
        fa.add(f);
        root.add("factions", fa);
        ParseResult r = FactionManager.parse(root);
        assertTrue(r.success(), () -> r.errors().toString());
        assertEquals("", r.graph().factions().get("x").music());
    }

    @Test
    void jsonArrayRelationsParse() {
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        com.google.gson.JsonArray fa = new com.google.gson.JsonArray();
        for (String id : List.of("a", "b", "c", "d")) {
            com.google.gson.JsonObject f = new com.google.gson.JsonObject();
            f.addProperty("id", id);
            fa.add(f);
        }
        root.add("factions", fa);
        com.google.gson.JsonArray ra = new com.google.gson.JsonArray();
        com.google.gson.JsonObject rel = new com.google.gson.JsonObject();
        com.google.gson.JsonArray from = new com.google.gson.JsonArray();
        from.add("a");
        from.add("b");
        com.google.gson.JsonArray to = new com.google.gson.JsonArray();
        to.add("c");
        rel.add("from", from);
        rel.add("to", to);
        rel.addProperty("type", "hostile");
        ra.add(rel);
        root.add("relations", ra);
        ParseResult r = FactionManager.parse(root);
        assertTrue(r.success(), () -> r.errors().toString());
        assertEquals(RelationType.HOSTILE, r.graph().resolve("a", "c"));
        assertEquals(RelationType.HOSTILE, r.graph().resolve("b", "c"));
        assertEquals(RelationType.NEUTRAL, r.graph().resolve("a", "d"));
    }
}
