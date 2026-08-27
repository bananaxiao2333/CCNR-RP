/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.experience;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ccnrcom.rp.faction.FactionGraph;
import com.ccnrcom.rp.faction.FactionModels.Faction;
import com.ccnrcom.rp.faction.FactionModels.ParseResult;
import com.ccnrcom.rp.faction.FactionModels.RelationRule;
import com.ccnrcom.rp.faction.RelationType;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 击杀事件广播关系参数：relationName 纯逻辑（友好/中立/敌对/空阵营/未知阵营）。 */
class KillRelationTest {

    private static final List<Faction> FACTIONS = List.of(
            new Faction("a", "A", "#FFFFFF", ""),
            new Faction("b", "B", "#FFFFFF", ""),
            new Faction("c", "C", "#FFFFFF", ""));

    private static FactionGraph graphOf(RelationRule... rules) {
        ParseResult r = FactionGraph.parse(FACTIONS, List.of(), List.of(rules));
        if (!r.success()) {
            throw new AssertionError(r.errors());
        }
        return r.graph();
    }

    @Test
    void friendlyRelation() {
        FactionGraph g = graphOf(new RelationRule("a", "b", RelationType.FRIENDLY));
        assertEquals("friendly", ExperienceService.relationName(g, "a", "b"));
        assertEquals("friendly", ExperienceService.relationName(g, "b", "a"));
    }

    @Test
    void hostileRelation() {
        FactionGraph g = graphOf(new RelationRule("a", "b", RelationType.HOSTILE));
        assertEquals("hostile", ExperienceService.relationName(g, "a", "b"));
    }

    @Test
    void unstatedDefaultsNeutral() {
        FactionGraph g = graphOf();
        assertEquals("neutral", ExperienceService.relationName(g, "a", "c"));
    }

    @Test
    void selfIsFriendly() {
        FactionGraph g = graphOf();
        assertEquals("friendly", ExperienceService.relationName(g, "a", "a"));
    }

    @Test
    void emptyOrUnknownSidesReturnEmpty() {
        FactionGraph g = graphOf();
        assertEquals("", ExperienceService.relationName(g, "", "b"));
        assertEquals("", ExperienceService.relationName(g, "a", null));
        assertEquals("", ExperienceService.relationName(g, null, null));
        assertEquals("", ExperienceService.relationName(g, "a", "unknown"));
        assertEquals("", ExperienceService.relationName(null, "a", "b"));
    }
}
