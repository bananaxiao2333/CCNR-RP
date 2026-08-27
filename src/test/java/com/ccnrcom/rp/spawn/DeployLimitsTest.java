/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 部署人数限制纯逻辑：规则解析、职业/阵营上限解析、部署前检测。 */
class DeployLimitsTest {

    private static JsonObject rule(String id, String type, String target, int limit) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("type", type);
        o.addProperty("target", target);
        o.addProperty("limit", limit);
        return o;
    }

    private static JsonObject root(JsonObject... rules) {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (JsonObject r : rules) {
            arr.add(r);
        }
        root.add("rules", arr);
        return root;
    }

    @Test
    void parseReadsRulesAndSkipsInvalid() {
        JsonObject bad = new JsonObject();
        bad.addProperty("id", "bad");
        bad.addProperty("type", "UNKNOWN");
        bad.addProperty("limit", 5);
        List<DeployLimits.Rule> rules = DeployLimits.parse(root(
                rule("g1", "GLOBAL", "", 50),
                rule("f1", "FACTION", "qdf", 10),
                rule("p1", "PROFESSION", "s4_guard", 5),
                bad));
        assertEquals(3, rules.size());
        assertEquals(DeployLimits.Type.GLOBAL, rules.get(0).type());
        assertEquals(50, rules.get(0).limit());
        assertEquals("qdf", rules.get(1).target());
    }

    @Test
    void professionLimitPrefersSpecificOverGlobal() {
        List<DeployLimits.Rule> rules =
                DeployLimits.parse(root(rule("g1", "GLOBAL", "", 20), rule("p1", "PROFESSION", "medic", 3)));
        assertEquals(3, DeployLimits.professionLimit(rules, "medic"));
        assertEquals(20, DeployLimits.professionLimit(rules, "s4_guard")); // 未配置职业 → GLOBAL 兜底
        assertEquals(-1, DeployLimits.professionLimit(List.of(), "s4_guard")); // 无规则 → 不限
    }

    @Test
    void factionLimitOnlySpecific() {
        List<DeployLimits.Rule> rules = DeployLimits.parse(root(rule("f1", "FACTION", "qdf", 10)));
        assertEquals(10, DeployLimits.factionLimit(rules, "qdf"));
        assertEquals(-1, DeployLimits.factionLimit(rules, "madison"));
    }

    @Test
    void checkRejectsWhenFull() {
        List<DeployLimits.Rule> rules = DeployLimits.parse(root(
                rule("g1", "GLOBAL", "", 20), rule("f1", "FACTION", "qdf", 5), rule("p1", "PROFESSION", "medic", 2)));
        // 职业满 → 拒绝
        assertTrue(DeployLimits.check(rules, "medic", "qdf", 2, 3).isPresent());
        // 阵营满 → 拒绝（职业未满）
        var d = DeployLimits.check(rules, "medic", "qdf", 1, 5);
        assertTrue(d.isPresent());
        assertEquals("ccnr_rp.spawn.limit.faction_full", d.get().key());
        // 空位足 → 允许
        assertTrue(DeployLimits.check(rules, "medic", "qdf", 1, 3).isEmpty());
        // GLOBAL 兜底满（medic 未配置 → GLOBAL 20；用另一个职业测 GLOBAL）
        assertTrue(DeployLimits.check(rules, "s4_guard", "madison", 20, 1).isPresent());
    }

    @Test
    void zeroLimitForbidsDeployment() {
        // 上限 0 = 禁止部署：即使 0 在职也拒绝（0 不再表示不限）
        List<DeployLimits.Rule> profRules = DeployLimits.parse(root(rule("p1", "PROFESSION", "medic", 0)));
        assertTrue(DeployLimits.check(profRules, "medic", "qdf", 0, 0).isPresent());
        // 阵营上限 0 同样禁止
        List<DeployLimits.Rule> facRules = DeployLimits.parse(root(rule("f1", "FACTION", "qdf", 0)));
        assertTrue(DeployLimits.check(facRules, "medic", "qdf", 0, 0).isPresent());
        // GLOBAL 上限 0 = 未配置专属规则的职业全部禁止
        List<DeployLimits.Rule> globalRules = DeployLimits.parse(root(rule("g1", "GLOBAL", "", 0)));
        assertTrue(DeployLimits.check(globalRules, "s4_guard", "madison", 0, 0).isPresent());
    }
}
