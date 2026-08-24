/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.experience;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** P5 验收：等级曲线边界。 */
class LevelCurveTest {

    private static final LevelCurve CURVE = new LevelCurve(100.0, 2.0);

    @Test
    void levelThresholds() {
        assertEquals(0, CURVE.level(0));
        assertEquals(0, CURVE.level(99));
        assertEquals(1, CURVE.level(100));
        assertEquals(2, CURVE.level(400));
        assertEquals(3, CURVE.level(900));
    }

    @Test
    void xpForLevelIsMonotonic() {
        long prev = 0;
        for (int l = 1; l <= 50; l++) {
            long v = CURVE.xpForLevel(l);
            assertEquals(true, v > prev);
            prev = v;
        }
    }

    @Test
    void negativeXpGivesLevelZero() {
        assertEquals(0, CURVE.level(-5));
    }

    @Test
    void canLevelUpDetectsThreshold() {
        assertEquals(true, CURVE.canLevelUp(1000, 2));
        assertEquals(false, CURVE.canLevelUp(100, 2));
    }
}
