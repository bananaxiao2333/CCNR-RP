/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccnrcom.rp.status.DeathInventoryPolicy.Disposal;
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

    // ---------- 进入观察者的背包处置（"只有死亡爆一地，其他直接删"） ----------

    @Test
    void naturalDeathDropsOnGround() {
        assertEquals(Disposal.DROP, DeathInventoryPolicy.disposalOnObserving(true, false));
    }

    @Test
    void offlineDeathDropsOnGround() {
        // 掉线判死也是"死在场上"：物品要留在世界里（遗体收纳 / 落地），玩家能找回
        assertEquals(Disposal.DROP, DeathInventoryPolicy.disposalOnObserving(false, true));
        assertEquals(Disposal.DROP, DeathInventoryPolicy.disposalOnObserving(true, true));
    }

    @Test
    void everyOtherObserverPathDeletesSilently() {
        // /rp kill（reason=command）、/rp retire（reason=retire）、疏散、旁观兜底、归一化
        // —— 都不是死亡路径：直接删除，不在脚下掉一地
        assertEquals(Disposal.DELETE, DeathInventoryPolicy.disposalOnObserving(false, false));
    }

    @Test
    void deathPathsAreTheOnlyOnesThatDrop() {
        // 反向断言：把"如何判定死亡路径"钉死，防止以后有人顺手把 /rp kill 也算成死亡而开始掉一地
        for (boolean natural : new boolean[] {false, true}) {
            for (boolean offline : new boolean[] {false, true}) {
                Disposal d = DeathInventoryPolicy.disposalOnObserving(natural, offline);
                assertEquals(
                        (natural || offline) ? Disposal.DROP : Disposal.DELETE,
                        d,
                        "natural=" + natural + " offline=" + offline);
            }
        }
    }
}
