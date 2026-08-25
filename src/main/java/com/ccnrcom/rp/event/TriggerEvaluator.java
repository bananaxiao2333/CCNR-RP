/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.event;

import com.ccnrcom.rp.event.EventModels.Trigger;
import com.ccnrcom.rp.event.EventModels.TriggerContext;

/** 触发器求值器（纯逻辑）。 */
public final class TriggerEvaluator {

    private TriggerEvaluator() {}

    public static boolean evaluate(Trigger t, TriggerContext ctx) {
        return switch (t.type()) {
            case ON_PHASE_START -> ctx.phaseStartedThisTick()
                    && t.param("phase", "").equals(ctx.currentPhaseId());
            case ON_PHASE_END -> ctx.phaseEndedThisTick()
                    && t.param("phase", "").equals(ctx.endedPhaseId());
            case ON_TIME -> ctx.day() == t.paramLong("day", -1) && ctx.tickOfDay() == t.paramLong("tickOfDay", -1);
            case PERIODIC -> {
                long s = t.paramLong("seconds", 0);
                yield s > 0 && ctx.secondsSinceStart() > 0 && ctx.secondsSinceStart() % s == 0;
            }
            case CONDITION -> evaluateCondition(t, ctx);
        };
    }

    private static boolean evaluateCondition(Trigger t, TriggerContext ctx) {
        String type = t.param("cond", "");
        int actual =
                switch (type) {
                    case "DEAD_COUNT" -> ctx.deadCount();
                    case "ALIVE_COUNT" -> ctx.aliveCount();
                    case "SCOREBOARD" -> ctx.scoreboard().getOrDefault(t.param("objective", ""), 0);
                    default -> Integer.MIN_VALUE;
                };
        long expected = t.paramLong("value", Long.MIN_VALUE);
        String op = t.param("op", "==");
        return switch (op) {
            case ">=" -> actual >= expected;
            case "<=" -> actual <= expected;
            case ">" -> actual > expected;
            case "<" -> actual < expected;
            default -> actual == expected;
        };
    }
}
