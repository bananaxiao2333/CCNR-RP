/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.status;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 死亡背包处置策略（issue #1 回归）：确认"原版不会爆出背包"的三条路径都被本 mod 补位，
 * 而原版正常会爆出的路径不被重复处理（避免二次掉落）。
 */
class DeathInventoryPolicyTest {

    @Test
    void corpseModAlwaysOwnsTheInventory() {
        // 装了遗体 mod：由它 removeDrops 收进遗体，本 mod 一律不插手（即使 keepInventory/旁观/离线）
        assertFalse(DeathInventoryPolicy.dropExplicitly(true, false, false, false));
        assertFalse(DeathInventoryPolicy.dropExplicitly(true, false, true, false));
        assertFalse(DeathInventoryPolicy.dropExplicitly(true, false, false, true));
        assertFalse(DeathInventoryPolicy.dropExplicitly(true, true, true, true));
    }

    @Test
    void vanillaDropsWhenServerKeepsInventory() {
        // 根因：整合包 keepInventory=true 时原版不掉 → 必须本 mod 显式爆出
        assertTrue(DeathInventoryPolicy.dropExplicitly(false, false, false, true));
    }

    @Test
    void spectatorDeathIsNeverDroppedByVanilla() {
        // 根因：ServerPlayer.die 里 if (!isSpectator()) dropAllDeathLoot —— 旁观者死亡原版不掉
        assertTrue(DeathInventoryPolicy.dropExplicitly(false, false, true, false));
    }

    @Test
    void offlineDeathHasNoVanillaDropPath() {
        assertTrue(DeathInventoryPolicy.dropExplicitly(false, true, false, false));
    }

    @Test
    void survivalDeathWithKeepInventoryFalseStaysWithVanilla() {
        // 原版会爆出：不重复处理（否则等于把物品掉两次/绕过消失诅咒等原版语义）
        assertFalse(DeathInventoryPolicy.dropExplicitly(false, false, false, false));
    }
}
