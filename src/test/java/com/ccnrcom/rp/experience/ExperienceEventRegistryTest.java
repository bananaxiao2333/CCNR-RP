/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.experience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccnrcom.rp.experience.ExperienceEventRegistry.EventDef;
import com.ccnrcom.rp.experience.ExprParser.Kind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 事件注册表：内置事件、参数完整性、静态类型映射、试算默认值。 */
class ExperienceEventRegistryTest {

    @Test
    void hasThreeBuiltinEvents() {
        assertEquals(
                List.of("character_alive", "character_kill", "character_death"),
                ExperienceEventRegistry.EVENTS.stream().map(EventDef::id).toList());
    }

    @Test
    void aliveParamsComplete() {
        EventDef d = ExperienceEventRegistry.byId("character_alive").orElseThrow();
        List<String> names =
                d.params().stream().map(ExperienceEventRegistry.Param::name).toList();
        assertTrue(names.containsAll(
                List.of("uuid", "playerName", "professionId", "factionId", "aliveSeconds", "intervalSeconds")));
    }

    @Test
    void paramKindsMapLongToNum() {
        Map<String, Kind> kinds = ExperienceEventRegistry.paramKinds("character_alive");
        assertEquals(Kind.NUM, kinds.get("aliveSeconds"));
        assertEquals(Kind.STR, kinds.get("playerName"));
        assertTrue(ExperienceEventRegistry.paramKinds("no_such").isEmpty());
    }

    @Test
    void defaultSamplePrefilled() {
        Map<String, Object> sample = ExperienceEventRegistry.defaultSample("character_kill");
        assertEquals("", sample.get("victimType"));
        Map<String, Object> alive = ExperienceEventRegistry.defaultSample("character_alive");
        assertEquals(60L, alive.get("intervalSeconds"));
    }
}
