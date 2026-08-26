/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.experience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 规则模型：校验（事件存在性/表达式语法与类型）与 JSON 往返。 */
class ExperienceRuleTest {

    private static final ExperienceRule OK =
            new ExperienceRule("r1", true, "character_alive", "aliveSeconds >= 60", "1", "值班");

    @Test
    void validRulePasses() {
        assertTrue(ExperienceRule.validate(OK).isEmpty());
    }

    @Test
    void unknownEventRejected() {
        ExperienceRule r = new ExperienceRule("r1", true, "no_such_event", "", "1", "t");
        List<String> errs = ExperienceRule.validate(r);
        assertFalse(errs.isEmpty());
        assertTrue(errs.get(0).contains("未知事件"));
    }

    @Test
    void badValueExprRejected() {
        ExperienceRule r = new ExperienceRule("r1", true, "character_alive", "", "1 +", "t");
        List<String> errs = ExperienceRule.validate(r);
        assertFalse(errs.isEmpty());
        assertTrue(errs.stream().anyMatch(e -> e.contains("数值表达式")));
    }

    @Test
    void conditionMustBeBoolean() {
        ExperienceRule r = new ExperienceRule("r1", true, "character_alive", "1", "1", "t");
        List<String> errs = ExperienceRule.validate(r);
        assertFalse(errs.isEmpty());
        assertTrue(errs.stream().anyMatch(e -> e.contains("判断表达式")));
    }

    @Test
    void emptyConditionAlwaysActivates() {
        ExperienceRule r = new ExperienceRule("r1", true, "character_alive", "", "1", "t");
        assertTrue(ExperienceRule.validate(r).isEmpty());
    }

    @Test
    void fromMissingFieldsThrows() {
        JsonObject o = new JsonObject();
        o.addProperty("id", "x");
        assertThrows(IllegalArgumentException.class, () -> ExperienceRule.from(o)); // 缺 eventId
    }

    @Test
    void readAllToleratesBadEntries() {
        JsonObject root = new JsonObject();
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        arr.add(ExperienceRule.toJson(List.of(OK)).getAsJsonArray("rules").get(0)); // 合法
        JsonObject bad = new JsonObject();
        bad.addProperty("id", "dup");
        bad.addProperty("eventId", "character_alive");
        bad.addProperty("valueExpr", "1");
        arr.add(bad);
        arr.add(bad); // 重复 id：第二条被拒（保留第一条）
        JsonObject notObj = new JsonObject();
        arr.add("not an object");
        root.add("rules", arr);

        List<String> errors = new ArrayList<>();
        List<ExperienceRule> out = ExperienceRule.readAll(root, errors);
        assertEquals(2, out.size()); // OK + 第一条 dup（重复 id 保留首条）
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("重复")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("不是对象")));
    }

    @Test
    void jsonRoundtrip() {
        List<ExperienceRule> rules = List.of(OK, new ExperienceRule("r2", false, "character_death", "", "-10", "阵亡"));
        JsonObject root = ExperienceRule.toJson(rules);
        assertEquals(1, root.get("version").getAsInt());
        List<ExperienceRule> back = ExperienceRule.readAll(root, new ArrayList<>());
        assertEquals(rules, back);
    }

    @Test
    void defaultRulesAreValid() {
        for (ExperienceRule r : ExperienceRule.DEFAULT_RULES) {
            assertTrue(ExperienceRule.validate(r).isEmpty(), "默认规则应合法: " + r.id());
        }
    }
}
