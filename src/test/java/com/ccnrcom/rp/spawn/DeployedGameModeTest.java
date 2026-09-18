/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 部署模式开关的纯逻辑回归（2.26.14）：目标模式取值 + 迁移判据。
 *
 * <p>为什么值得单测：这条判据同时决定"部署发什么模式"和"运行时切换改谁"，一旦放宽
 * （例如把创造模式也纳入迁移、或迁移不幂等反复 setGameMode），后果是管理员被降级成冒险
 * 或每 tick 重复写模式——两者都不会在编译期暴露。
 */
class DeployedGameModeTest {

    @Test
    void targetIsSurvivalByDefault() {
        // 开关关闭 = 原行为（docs/01 §9.5「默认值应维持现有体验与兼容性」）
        assertEquals(DeployedGameMode.SURVIVAL, DeployedGameMode.target(false));
    }

    @Test
    void targetIsAdventureWhenSwitchOn() {
        assertEquals(DeployedGameMode.ADVENTURE, DeployedGameMode.target(true));
    }

    @Test
    void survivalMigratesToAdventureAndBack() {
        assertTrue(DeployedGameMode.shouldSwitch(DeployedGameMode.SURVIVAL, DeployedGameMode.ADVENTURE));
        assertTrue(DeployedGameMode.shouldSwitch(DeployedGameMode.ADVENTURE, DeployedGameMode.SURVIVAL));
    }

    @Test
    void sameModeIsIdempotent() {
        // 幂等：已经等于目标就不再 setGameMode（重复写模式会让客户端反复收到模式包）
        assertFalse(DeployedGameMode.shouldSwitch(DeployedGameMode.SURVIVAL, DeployedGameMode.SURVIVAL));
        assertFalse(DeployedGameMode.shouldSwitch(DeployedGameMode.ADVENTURE, DeployedGameMode.ADVENTURE));
    }

    @Test
    void otherModesAreNeverTouched() {
        // 创造/旁观/生存之外的一切都不归本开关管：管理员的创造模式、部署中与观察者的旁观都不能被改
        for (String other : new String[] {"CREATIVE", "SPECTATOR", "creative", ""}) {
            assertFalse(DeployedGameMode.shouldSwitch(other, DeployedGameMode.ADVENTURE), "不应迁移: " + other);
            assertFalse(DeployedGameMode.shouldSwitch(other, DeployedGameMode.SURVIVAL), "不应迁移: " + other);
        }
    }

    @Test
    void nullInputsAreNotSwitched() {
        assertFalse(DeployedGameMode.shouldSwitch(null, DeployedGameMode.ADVENTURE));
        assertFalse(DeployedGameMode.shouldSwitch(DeployedGameMode.SURVIVAL, null));
        assertFalse(DeployedGameMode.shouldSwitch(null, null));
    }
}
