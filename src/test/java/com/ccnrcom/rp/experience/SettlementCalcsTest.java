/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.experience;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ccnrcom.rp.experience.SettlementCalcs.EvacuationMethod;
import com.ccnrcom.rp.experience.SettlementCalcs.Result;
import com.ccnrcom.rp.experience.SettlementCalcs.Weights;
import org.junit.jupiter.api.Test;

/** P5 验收：结算计算（来源加权 / 混合 / 疏散系数）。 */
class SettlementCalcsTest {

    private static final Weights W = Weights.DEFAULT;

    @Test
    void dutyOnly() {
        Result r = SettlementCalcs.calculate(600, 0, EvacuationMethod.NONE, W);
        assertEquals(60, r.dutyXp());
        assertEquals(60, r.totalXp());
        assertEquals(0, r.evacXp());
    }

    @Test
    void taskXpAdded() {
        Result r = SettlementCalcs.calculate(0, 150, EvacuationMethod.NONE, W);
        assertEquals(150, r.taskXp());
        assertEquals(150, r.totalXp());
    }

    @Test
    void evacuationWeights() {
        assertEquals(200, W.evacXp(EvacuationMethod.SAFE_RESCUE));
        assertEquals(50, W.evacXp(EvacuationMethod.DIED));
        assertEquals(0, W.evacXp(EvacuationMethod.OBSERVING_END));
        assertEquals(150, W.evacXp(EvacuationMethod.STAY_BEHIND));
        assertEquals(0, W.evacXp(EvacuationMethod.NONE));
    }

    @Test
    void mixedSettlement() {
        Result r = SettlementCalcs.calculate(1800, 100, EvacuationMethod.SAFE_RESCUE, W);
        assertEquals(180 + 100 + 200, r.totalXp());
        assertEquals("SAFE_RESCUE", r.evacNote());
    }

    @Test
    void zeroDeltasZeroXp() {
        assertEquals(
                0, SettlementCalcs.calculate(0, 0, EvacuationMethod.NONE, W).totalXp());
    }
}
