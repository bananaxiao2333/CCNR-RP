/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccnrcom.rp.status.DeathInventoryPolicy.DeathHandling;
import com.ccnrcom.rp.status.DeathInventoryPolicy.Disposal;
import org.junit.jupiter.api.Test;

/**
 * 死亡背包处置策略（issue #1 回归）：确认"原版不会爆出背包"的两条路径都被本 mod 补位，
 * 而原版正常会爆出的路径不被重复处理（避免二次掉落）；
 * 以及世界规则 {@code keepInventory=true}（"死亡不掉落"）**反转**补位逻辑：不爆出、不生成遗体、直接删除。
 */
class DeathInventoryPolicyTest {

    /** 便捷构造：keepInventory=false、非离线、非旁观（原版会正常爆出）。 */
    private static DeathHandling vanillaDeath(boolean corpseAvailable, boolean offline, boolean spectator) {
        return DeathInventoryPolicy.forDeath(corpseAvailable, offline, spectator, false);
    }

    // ---------- 死亡路径：爆出与否 ----------

    @Test
    void corpseModAlwaysOwnsTheInventory() {
        // 装了遗体 mod：由它 removeDrops 收进遗体，本 mod 一律不插手（旁观/离线都不重复处理）
        assertFalse(vanillaDeath(true, false, false).dropExplicitly());
        assertFalse(vanillaDeath(true, false, true).dropExplicitly());
        assertFalse(vanillaDeath(true, true, false).dropExplicitly());
        // 但背包处置仍是"留在世界里"（由遗体收纳）
        assertEquals(Disposal.DROP, vanillaDeath(true, true, true).disposal());
        assertFalse(vanillaDeath(true, true, true).suppressCorpse());
    }

    @Test
    void spectatorDeathIsNeverDroppedByVanilla() {
        // 根因：ServerPlayer.die 里 if (!isSpectator()) dropAllDeathLoot —— 旁观者死亡原版不掉
        assertTrue(vanillaDeath(false, false, true).dropExplicitly());
    }

    @Test
    void offlineDeathHasNoVanillaDropPath() {
        assertTrue(vanillaDeath(false, true, false).dropExplicitly());
    }

    @Test
    void survivalDeathWithKeepInventoryFalseStaysWithVanilla() {
        // 原版会爆出：不重复处理（否则等于把物品掉两次/绕过消失诅咒等原版语义）
        assertFalse(vanillaDeath(false, false, false).dropExplicitly());
    }

    // ---------- 世界规则 keepInventory=true：规则优先，推翻补位 ----------

    @Test
    void keepInventoryNeverDropsEvenWhenVanillaWouldNot() {
        // 这是 2.26.10 修的反向缺陷：过去 keepInventory=true 被当成"原版不掉 → 本 mod 必须补爆"，
        // 等于用一个补丁推翻玩家/管理员显式设置的世界规则（规则写着不掉落，死一次满地装备）。
        for (boolean offline : new boolean[] {false, true}) {
            for (boolean spectator : new boolean[] {false, true}) {
                for (boolean corpse : new boolean[] {false, true}) {
                    DeathHandling h = DeathInventoryPolicy.forDeath(corpse, offline, spectator, true);
                    assertFalse(
                            h.dropExplicitly(),
                            "keepInventory=true 时任何组合都不得爆出 (offline=" + offline + " spectator=" + spectator + " corpse="
                                    + corpse + ")");
                }
            }
        }
    }

    @Test
    void keepInventoryDeletesInsteadOfLeavingItOnTheObserver() {
        // 不爆出 ≠ 留着：本 mod 的观察者契约要求空背包，且重新部署时一定会清空并按岗位发装备，
        // 所以"留着"只会变成"死后还抱着上一局的枪、到下次部署才无声消失"。故直接删除。
        assertEquals(
                Disposal.DELETE,
                DeathInventoryPolicy.forDeath(false, false, false, true).disposal());
        assertEquals(
                Disposal.DELETE,
                DeathInventoryPolicy.forDeath(false, true, true, true).disposal());
    }

    @Test
    void keepInventoryAlsoSuppressesTheCorpse() {
        // 用户定调："一点装备都不留" —— 连遗体也不生成（Corpse 的事件不可取消，靠实体入世界时拦下）
        assertTrue(DeathInventoryPolicy.forDeath(false, false, false, true).suppressCorpse());
        assertTrue(DeathInventoryPolicy.forDeath(true, false, false, true).suppressCorpse());
    }

    @Test
    void keepInventoryFalseKeepsEveryExistingBehaviour() {
        // 反向断言：规则关闭时，三个决定与 2.26.9 之前逐字一致（本改动不得影响默认行为）
        DeathHandling survival = vanillaDeath(false, false, false);
        assertEquals(Disposal.DROP, survival.disposal());
        assertFalse(survival.dropExplicitly());
        assertFalse(survival.suppressCorpse());

        DeathHandling spectator = vanillaDeath(false, false, true);
        assertEquals(Disposal.DROP, spectator.disposal());
        assertTrue(spectator.dropExplicitly());
        assertFalse(spectator.suppressCorpse());
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
