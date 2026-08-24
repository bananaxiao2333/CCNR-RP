/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccnrcom.rp.event.EventModels.GamePhase;
import com.ccnrcom.rp.event.PhaseClock.Transition;
import java.util.List;
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
}
