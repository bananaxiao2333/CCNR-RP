/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.status;

/**
 * 死亡背包处置策略（纯逻辑，无 MC import，可脱机 JUnit 测）。
 *
 * <p>存在意义（issue #1 根因）：本 mod 从不处理死亡时的背包，一切依赖"原版会爆出"——而原版在两种情况下**不会**爆出：
 * <ul>
 *   <li>死亡瞬间处于旁观者模式——{@code ServerPlayer.die} 里 {@code if (!isSpectator()) dropAllDeathLoot(...)}
 *       直接跳过；而本 mod 恰恰会把"未部署玩家"强制切成旁观者（观察者轮询 + 登录规则）；</li>
 *   <li>离线判死（掉线判死）——玩家没死在世界里，原版掉落流程根本不执行。</li>
 * </ul>
 * 装了遗体 mod（Corpse）时由它 {@code removeDrops()} 把物品收进遗体，所以"死亡即清空背包"过去是遗体 mod 的副作用；
 * 遗体 mod 被移除后这个副作用消失，于是出现"死亡后不会清理背包"。
 *
 * <p><b>世界规则 {@code keepInventory} 反转了这件事（2.26.10）</b>：当初把 {@code keepInventory=true} 也算作
 * "原版不会掉、所以本 mod 必须补爆"，等于用一个补丁**推翻玩家/管理员显式设置的世界规则**——规则写着"死亡不掉落"，
 * 结果死一次满地装备。现在规则优先：{@code keepInventory=true} 时本 mod 既不爆出、也不留遗体，背包直接删除
 * （详见 {@link #forDeath}）。这一条与"观察者必须空背包"的既有契约也一致。
 *
 * <p>本策略决定两件事，且判据只在这里写一次：死亡路径由 {@link #forDeath} 一次给出背包处置 / 是否显式爆出 /
 * 是否抑制遗体；非死亡的一切退场由 {@link #disposalOnObserving} 判定为直接删除。
 */
public final class DeathInventoryPolicy {

    private DeathInventoryPolicy() {}

    /**
     * 判定一次**死亡路径**（自然死亡 / 掉线判死）的背包与遗体处置。
     *
     * <p><b>世界规则优先于一切</b>：{@code keepInventory=true}（"死亡不掉落"）时本 mod **既不爆出背包、
     * 也不生成遗体**，而是把背包**直接删除**。为什么是删除而不是"留在身上"：本 mod 的观察者契约要求
     * "切观察者必须空背包"，且 keepInventory 在本 mod 的流程里不可能意味着"留着下一局接着用"
     * （重新部署时 {@code SpawnFramework} 一定会清空背包并按岗位发装备）。若只"不爆出"而把物品留在
     * 观察者身上，结果是玩家死后仍抱着上一局的枪、到下次部署时才无声消失——比直接删除更难预期。
     *
     * <p>否则：由遗体 mod 收纳（{@code dropExplicitly=false}，它会 {@code removeDrops()} 收进遗体）；
     * 未装遗体 mod 时补位原版不会掉的两种情况——离线判死（没有原版掉落流程）与死亡瞬间是旁观者
     * （{@code ServerPlayer.die} 里 {@code if (!isSpectator()) dropAllDeathLoot} 直接跳过）。
     * 后一种在本 mod 里很常见（未部署玩家被强制切成旁观者），不补位就会静默丢失整包装备。
     *
     * @param corpseAvailable  遗体 mod 是否可用（可用时由它收纳物品，本 mod 不插手）
     * @param offlineDeath     是否为离线判死（玩家不在世界里，原版掉落流程不会跑）
     * @param spectatorAtDeath 死亡瞬间是否旁观者（原版 die() 会跳过死亡掉落）
     * @param keepInventory    世界规则 {@code keepInventory}（"死亡不掉落"）
     */
    public static DeathHandling forDeath(
            boolean corpseAvailable, boolean offlineDeath, boolean spectatorAtDeath, boolean keepInventory) {
        if (keepInventory) {
            // 世界规则说了算：不爆出、不留遗体，背包直接删除（观察者必须空背包）
            return new DeathHandling(Disposal.DELETE, false, true);
        }
        boolean explicit = !corpseAvailable && (offlineDeath || spectatorAtDeath);
        return new DeathHandling(Disposal.DROP, explicit, false);
    }

    /** 一次死亡的背包与遗体处置决定（三个决定同源判据，避免调用方各写一套）。 */
    public record DeathHandling(Disposal disposal, boolean dropExplicitly, boolean suppressCorpse) {}

    /** 进入观察者时的背包处置方式。 */
    public enum Disposal {
        /** 爆到地上：物品留在世界里，玩家能找回（遗体收纳或落地）。 */
        DROP,
        /** 直接删除：不产生任何掉落物。 */
        DELETE
    }

    /**
     * 进入观察者时该用哪种背包处置（纯逻辑，可脱机单测）。
     *
     * <p><b>规则：只有死亡把物品爆到地上，其他一切切观察者的路径一律直接删除。</b>
     * 这里说的"死亡路径"只有两种——<b>自然死亡</b>（{@code reason="death"}）与<b>掉线判死</b>
     * （{@code RetireFlag.OFFLINE}）：它们的物品要留在世界里（遗体收纳 / 落地），玩家能找回。
     *
     * <p>其余全部属于 DELETE：管理员 {@code /rp kill} 与 {@code /rp retire}（reason 分别是
     * {@code command}/{@code retire}，语义是"退场"而不是"死在场上"）、疏散结算、旁观者兜底轮询、
     * DEAD 与登录归一化——观察者不该把上一局的装备留在世界里，也就不该在脚下掉一地。
     *
     * @param naturalDeath 自然死亡（reason=death）
     * @param offlineDeath 掉线判死（RetireFlag.OFFLINE）
     */
    public static Disposal disposalOnObserving(boolean naturalDeath, boolean offlineDeath) {
        return (naturalDeath || offlineDeath) ? Disposal.DROP : Disposal.DELETE;
    }
}
