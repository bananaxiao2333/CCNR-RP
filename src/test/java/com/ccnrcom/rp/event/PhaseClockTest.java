/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccnrcom.rp.event.EventModels.GamePhase;
import com.ccnrcom.rp.event.EventModels.Trigger;
import com.ccnrcom.rp.event.PhaseClock.Transition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** P6 验收：阶段时钟推进与切换。 */
class PhaseClockTest {

    private static PhaseClock clock() {
        return new PhaseClock(
                List.of(new GamePhase("prep", 0, 30), new GamePhase("danger", 1, 45), new GamePhase("end", 2, 15)));
    }

    @Test
    void autoAdvanceAfterDuration() {
        PhaseClock c = clock();
        Transition t = null;
        for (int i = 0; i < 30 * 1200; i++) {
            t = c.tick();
        }
        assertTrue(t.changed());
        assertEquals("danger", t.started());
        assertEquals("prep", t.ended());
        assertEquals("danger", c.phaseId());
        assertEquals(0, c.ticksInPhase());
    }

    @Test
    void manualSetAndAdvance() {
        PhaseClock c = clock();
        Transition t = c.advance();
        assertTrue(t.changed());
        assertEquals("danger", t.started());
        assertEquals(1, c.index());
        Transition t2 = c.set(2);
        assertEquals("end", t2.started());
        assertEquals("danger", t2.ended());
    }

    @Test
    void noChangeAtSameIndex() {
        PhaseClock c = clock();
        assertFalse(c.set(0).changed());
    }

    @Test
    void clampBeyondLastPhase() {
        PhaseClock c = clock();
        c.set(99);
        assertEquals("end", c.phaseId());
    }

    @Test
    void conditionDrivenPhaseSkipsDurationAutoAdvance() {
        Trigger adv = new Trigger(Trigger.Type.CONDITION, Map.of("cond", "ALIVE_COUNT", "op", "<=", "value", "1"));
        PhaseClock c = new PhaseClock(List.of(new GamePhase("a", 0, 30, adv, List.of()), new GamePhase("b", 1, 30)));
        // 远超 30 分钟时长：条件驱动阶段不按时长自动推进
        Transition t = null;
        for (int i = 0; i < 30 * 1200 + 1; i++) {
            t = c.tick();
        }
        assertFalse(t.changed());
        assertEquals("a", c.phaseId());
        // 手动 advance 仍生效
        assertTrue(c.advance().changed());
        assertEquals("b", c.phaseId());
    }
    // ---------- ticksRemaining（左侧对局状态面板的"本幕剩余"倒计时） ----------

    /** 时长驱动幕：剩余 = limit - 已过 tick，随时间减少、到点归零。 */
    @Test
    void ticksRemainingCountsDownForDurationDrivenPhase() {
        PhaseClock c = clock();
        long full = 30 * 1200L;
        assertEquals(full, c.ticksRemaining());
        c.tick(1200L);
        assertEquals(full - 1200L, c.ticksRemaining());
    }

    /** 条件驱动幕（advanceOn 非空）：不按时长推进 → 没有倒计时（返回 -1，界面不显示该行）。 */
    @Test
    void ticksRemainingIsMinusOneForConditionDrivenPhase() {
        PhaseClock c = new PhaseClock(List.of(new GamePhase(
                "round",
                0,
                0,
                new com.ccnrcom.rp.event.EventModels.Trigger(
                        com.ccnrcom.rp.event.EventModels.Trigger.Type.CONDITION,
                        java.util.Map.of("cond", "ALIVE_COUNT", "op", ">=", "value", "4")),
                List.of())));
        assertEquals(-1, c.ticksRemaining());
        c.tick(100000L);
        assertEquals(-1, c.ticksRemaining());
    }

    /** 无阶段：没有倒计时（不抛异常）。 */
    @Test
    void ticksRemainingIsMinusOneWithoutPhases() {
        assertEquals(-1, new PhaseClock(List.of()).ticksRemaining());
    }
}
