/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.status;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** P4 验收：状态机迁移表全路径 + 幂等 + 非法拒绝。 */
class StatusMachineTest {

    @Test
    void observingToAliveAllowed() {
        assertTrue(StatusMachine.transition(CharacterStatus.OBSERVING, CharacterStatus.ALIVE)
                .isEmpty());
    }

    @Test
    void aliveToDeadAndObservingAllowed() {
        assertTrue(StatusMachine.transition(CharacterStatus.ALIVE, CharacterStatus.DEAD)
                .isEmpty());
        assertTrue(StatusMachine.transition(CharacterStatus.ALIVE, CharacterStatus.OBSERVING)
                .isEmpty());
    }

    @Test
    void deadToAliveAllowedOnly() {
        assertTrue(StatusMachine.transition(CharacterStatus.DEAD, CharacterStatus.ALIVE)
                .isEmpty());
        assertTrue(StatusMachine.transition(CharacterStatus.DEAD, CharacterStatus.OBSERVING)
                .isPresent());
    }

    @Test
    void observingToDeadRejected() {
        assertTrue(StatusMachine.transition(CharacterStatus.OBSERVING, CharacterStatus.DEAD)
                .isPresent());
    }

    @Test
    void sameStateIsIdempotentNoop() {
        assertTrue(StatusMachine.transition(CharacterStatus.ALIVE, CharacterStatus.ALIVE)
                .isEmpty());
        assertTrue(StatusMachine.transition(CharacterStatus.DEAD, CharacterStatus.DEAD)
                .isEmpty());
        assertTrue(StatusMachine.transition(CharacterStatus.OBSERVING, CharacterStatus.OBSERVING)
                .isEmpty());
    }

    @Test
    void selfDeployableRules() {
        assertTrue(StatusMachine.selfDeployable(CharacterStatus.OBSERVING, true));
        assertFalse(StatusMachine.selfDeployable(CharacterStatus.OBSERVING, false));
        assertFalse(StatusMachine.selfDeployable(CharacterStatus.ALIVE, true));
        assertFalse(StatusMachine.selfDeployable(CharacterStatus.DEAD, true));
    }

    @Test
    void waveEligibleRules() {
        // 阴间池：DEAD 恒可被复活波选中
        assertTrue(StatusMachine.waveEligible(CharacterStatus.DEAD, true));
        assertTrue(StatusMachine.waveEligible(CharacterStatus.DEAD, false));
        // 阳间池：仅非自部署类型的观察者
        assertTrue(StatusMachine.waveEligible(CharacterStatus.OBSERVING, false));
        assertFalse(StatusMachine.waveEligible(CharacterStatus.OBSERVING, true));
        // 在场角色不可重复部署
        assertFalse(StatusMachine.waveEligible(CharacterStatus.ALIVE, false));
    }
}
