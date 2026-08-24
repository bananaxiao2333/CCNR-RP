/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.event;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccnrcom.rp.event.EventModels.Trigger;
import com.ccnrcom.rp.event.EventModels.Trigger.Type;
import com.ccnrcom.rp.event.EventModels.TriggerContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** P6 验收：触发器求值（五类正/反例）。 */
class TriggerEvaluatorTest {

    private TriggerContext ctx(boolean phaseStart, boolean phaseEnd, String phase, String ended, long seconds) {
        return new TriggerContext(phase, ended, 100, 3L, 1200L, seconds, 2, 5, Map.of("obj", 7), phaseStart, phaseEnd);
    }

    @Test
    void onPhaseStartEvaluates() {
        Trigger t = new Trigger(Type.ON_PHASE_START, Map.of("phase", "danger"));
        assertTrue(TriggerEvaluator.evaluate(t, ctx(true, false, "danger", "prep", 10)));
        assertFalse(TriggerEvaluator.evaluate(t, ctx(false, false, "danger", "prep", 10)));
        assertFalse(TriggerEvaluator.evaluate(t, ctx(true, false, "other", "prep", 10)));
    }

    @Test
    void onPhaseEndEvaluates() {
        Trigger t = new Trigger(Type.ON_PHASE_END, Map.of("phase", "prep"));
        assertTrue(TriggerEvaluator.evaluate(t, ctx(false, true, "danger", "prep", 10)));
        assertFalse(TriggerEvaluator.evaluate(t, ctx(false, false, "danger", "prep", 10)));
    }

    @Test
    void onTimeEvaluatesExactTick() {
        Trigger t = new Trigger(Type.ON_TIME, Map.of("day", "3", "tickOfDay", "1200"));
        assertTrue(TriggerEvaluator.evaluate(t, ctx(false, false, "x", "y", 10)));
        Trigger t2 = new Trigger(Type.ON_TIME, Map.of("day", "3", "tickOfDay", "100"));
        assertFalse(TriggerEvaluator.evaluate(t2, ctx(false, false, "x", "y", 10)));
    }

    @Test
    void periodicAligns() {
        Trigger t = new Trigger(Type.PERIODIC, Map.of("seconds", "30"));
        assertTrue(TriggerEvaluator.evaluate(t, ctx(false, false, "x", "y", 60)));
        assertFalse(TriggerEvaluator.evaluate(t, ctx(false, false, "x", "y", 45)));
        assertFalse(TriggerEvaluator.evaluate(t, ctx(false, false, "x", "y", 0)));
    }

    @Test
    void conditionsCompareCorrectly() {
        assertTrue(TriggerEvaluator.evaluate(
                new Trigger(Type.CONDITION, Map.of("type", "DEAD_COUNT", "op", ">=", "value", "2")),
                ctx(false, false, "x", "y", 10)));
        assertFalse(TriggerEvaluator.evaluate(
                new Trigger(Type.CONDITION, Map.of("type", "DEAD_COUNT", "op", "<", "value", "2")),
                ctx(false, false, "x", "y", 10)));
        assertTrue(TriggerEvaluator.evaluate(
                new Trigger(Type.CONDITION, Map.of("type", "ALIVE_COUNT", "op", "==", "value", "5")),
                ctx(false, false, "x", "y", 10)));
        assertTrue(TriggerEvaluator.evaluate(
                new Trigger(Type.CONDITION, Map.of("type", "SCOREBOARD", "objective", "obj", "op", ">", "value", "5")),
                ctx(false, false, "x", "y", 10)));
    }
}
