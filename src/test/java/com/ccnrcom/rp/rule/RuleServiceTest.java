/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/** RuleService 幕作用域规则：应用/查询/幕解除/冲突覆盖。 */
class RuleServiceTest {

    @Test
    void limitProfessionsAppliesAndClearsOnPhaseEnd() {
        RuleService rs = new RuleService();
        JsonObject action = new JsonObject();
        action.addProperty("rule", "limitProfessions");
        action.addProperty("phase", "danger");
        action.add("professions", gsonArr("s4_guard", "sci"));
        rs.apply(action, "danger");

        assertTrue(rs.limitProfessionsActive("danger"));
        assertTrue(rs.isProfessionAllowed("danger", "s4_guard"));
        assertFalse(rs.isProfessionAllowed("danger", "scp_939"));
        assertTrue(rs.isProfessionAllowed("calm", "scp_939")); // 其他幕不受影响

        rs.onPhaseEnd("danger");
        assertFalse(rs.limitProfessionsActive("danger"));
        assertTrue(rs.isProfessionAllowed("danger", "scp_939"));
    }

    @Test
    void limitFactionsScopeAndFactionQuery() {
        RuleService rs = new RuleService();
        rs.apply("fight", "limitFactions", null, java.util.List.of("qdf"), null, false, "");
        assertTrue(rs.limitFactionsActive("fight"));
        assertTrue(rs.isFactionAllowed("fight", "qdf"));
        assertFalse(rs.isFactionAllowed("fight", "mtf"));
        assertTrue(rs.isFactionAllowed("buy", "mtf"));
    }

    @Test
    void zoneToggleLastWinsPerPhase() {
        RuleService rs = new RuleService();
        rs.apply("assault", "zoneToggle", null, null, "capture_a", true, "");
        rs.apply("assault", "zoneToggle", null, null, "capture_a", false, "");
        assertFalse(rs.zoneEnabled("assault", "capture_a"));
        assertFalse(rs.zoneEnabled("assault", "z_unknown")); // 未登记视为关

        rs.apply("assault", "zoneToggle", null, null, "capture_b", true, "");
        assertTrue(rs.zoneEnabled("assault", "capture_b"));
    }

    @Test
    void recruitModeOverridesPerPhase() {
        RuleService rs = new RuleService();
        assertEquals(RuleService.RecruitMode.NONE, rs.recruitMode("buy"));
        rs.apply("buy", "recruitMode", null, null, null, false, "SELF_DEPLOY");
        assertEquals(RuleService.RecruitMode.SELF_DEPLOY, rs.recruitMode("buy"));
        assertEquals(RuleService.RecruitMode.NONE, rs.recruitMode("fight"));
        rs.apply("buy", "recruitMode", null, null, null, false, "BOTH");
        assertEquals(RuleService.RecruitMode.BOTH, rs.recruitMode("buy"));
    }

    @Test
    void resetClearsAll() {
        RuleService rs = new RuleService();
        rs.apply("a", "zoneToggle", null, null, "z1", true, "");
        rs.apply("b", "recruitMode", null, null, null, false, "BOTH");
        rs.reset();
        assertFalse(rs.zoneEnabled("a", "z1"));
        assertEquals(RuleService.RecruitMode.NONE, rs.recruitMode("b"));
    }

    private static com.google.gson.JsonArray gsonArr(String... values) {
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (String v : values) {
            arr.add(v);
        }
        return arr;
    }
}
