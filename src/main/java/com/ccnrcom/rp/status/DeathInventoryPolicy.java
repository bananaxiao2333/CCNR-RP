/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.status;

/**
 * 死亡背包处置策略（纯逻辑，无 MC import，可脱机 JUnit 测）。
 *
 * <p>存在意义（issue #1 根因）：本 mod 从不处理死亡时的背包，一切依赖"原版会爆出"——而原版在三种情况下**不会**爆出：
 * <ul>
 *   <li>{@code keepInventory=true}（整合包常见：装了遗体 mod 时常开，用来兜底）；</li>
 *   <li>死亡瞬间处于旁观者模式——{@code ServerPlayer.die} 里 {@code if (!isSpectator()) dropAllDeathLoot(...)}
 *       直接跳过；而本 mod 恰恰会把"未部署玩家"强制切成旁观者（观察者轮询 + 登录规则）；</li>
 *   <li>离线判死（掉线判死）——玩家没死在世界里，原版掉落流程根本不执行。</li>
 * </ul>
 * 装了遗体 mod（Corpse）时由它 {@code removeDrops()} 把物品收进遗体，所以"死亡即清空背包"过去是遗体 mod 的副作用；
 * 遗体 mod 被移除后这个副作用消失，于是出现"死亡后不会清理背包"。
 *
 * <p>本策略决定：什么时候必须由本 mod **显式爆出**背包（补位原版不掉的路径），什么时候交给原版/遗体 mod（不重复处理）。
 */
public final class DeathInventoryPolicy {

    private DeathInventoryPolicy() {}

    /**
     * 是否必须由本 mod 显式爆出并清空背包。
     *
     * @param corpseAvailable  遗体 mod 是否可用（可用时由它收纳物品，本 mod 不插手）
     * @param offlineDeath     是否为离线判死（玩家不在世界里，原版掉落流程不会跑）
     * @param spectatorAtDeath 死亡瞬间是否旁观者（原版 die() 会跳过死亡掉落）
     * @param keepInventory    世界规则是否保留背包
     * @return true=本 mod 必须显式爆出（原版路径不会处理）；false=交给原版/遗体 mod
     */
    public static boolean dropExplicitly(
            boolean corpseAvailable, boolean offlineDeath, boolean spectatorAtDeath, boolean keepInventory) {
        if (corpseAvailable) {
            return false; // 遗体 mod 负责收纳（它会 removeDrops 并把物品放进遗体）
        }
        return offlineDeath || spectatorAtDeath || keepInventory;
    }
}
