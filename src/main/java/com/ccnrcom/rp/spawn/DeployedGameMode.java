/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.spawn;

/**
 * 部署游戏模式判据（**纯逻辑，无 MC import**，可直接 JUnit 测）。2.26.14 起。
 *
 * <p>回答两个问题，都只有一个出处（docs/01 §9.3「同一件事只留一个判据」）：
 * <ol>
 *   <li>{@link #target(boolean)}：管理面板全局开关 {@code deployAdventureMode}（settings.json）为真时，
 *       部署落位应下发**冒险**（在场玩家不能破坏/放置方块），否则**生存**（原行为，默认值）。</li>
 *   <li>{@link #shouldSwitch(String, String)}：运行中旧值 → 新值该怎么迁移。**只在生存 ↔ 冒险之间迁移**；
 *       创造、旁观以及任何其它模式一律不动。</li>
 * </ol>
 *
 * <p><b>为什么"只在两种模式间迁移"</b>：本 mod 刻意不接管在场玩家的游戏模式
 * （{@code StatusManager} 的 2 秒轮询只强制"未部署玩家旁观"，注释明写不干预玩家/管理员的模式选择）。
 * 一个"部署模式"开关若把创造模式的管理员也改掉，就从一个部署规则越权成了模式警察。
 * 边界收在"生存 ↔ 冒险"后，开关的意思始终是"我们发什么模式"，而不是"你必须是什么模式"。
 *
 * <p><b>为什么要有迁移判据（而不是只在部署时写一次）</b>：docs/01 §9.5 要求"运行时切换必须完成旧模式清理、
 * 新模式初始化和必要的状态重放"。原版会把游戏模式随 playerdata 持久化，所以开关关掉后若不做重放，
 * 已经部署成冒险的玩家会一直卡在冒险里（没有任何兜底会纠正他）——那是个假开关。
 */
public final class DeployedGameMode {

    /** {@code net.minecraft.world.level.GameType.SURVIVAL} 的模式名（此处只存名字，避免引入 MC 依赖）。 */
    public static final String SURVIVAL = "SURVIVAL";

    /** {@code net.minecraft.world.level.GameType.ADVENTURE} 的模式名。 */
    public static final String ADVENTURE = "ADVENTURE";

    private DeployedGameMode() {}

    /** 开关 → 部署落位应下发的模式名（关闭 = 生存，维持既有体验，docs/01 §9.5）。 */
    public static String target(boolean adventureMode) {
        return adventureMode ? ADVENTURE : SURVIVAL;
    }

    /**
     * 是否应把 {@code current} 迁移到 {@code target}。
     *
     * <p>受本开关管辖的只有生存与冒险两种模式：只有它们是"本 mod 发出去的部署模式"。因此
     * <ul>
     *   <li>{@code current} 是创造/旁观/其它 → 返回 false（不碰：可能是管理员的显式选择，或部署中的旁观态）；</li>
     *   <li>{@code current} 已经等于 {@code target} → 返回 false（幂等：重复调用不再重复 setGameMode）。</li>
     * </ul>
     */
    public static boolean shouldSwitch(String current, String target) {
        if (current == null || target == null) {
            return false;
        }
        boolean governed = SURVIVAL.equals(current) || ADVENTURE.equals(current);
        return governed && !target.equals(current);
    }
}
