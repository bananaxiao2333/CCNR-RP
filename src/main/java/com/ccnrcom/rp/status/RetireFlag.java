/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.status;

import java.util.EnumSet;
import java.util.Set;

/**
 * 退场行为 flag（只定义行为，不定义原因）：
 * 普通死亡 / 判死 / 下班(退役) / 征召结束全部收敛到
 * {@link StatusManager#retire(String, net.minecraft.server.level.ServerPlayer, String, Set)}，
 * 共用同一状态迁移 + 同一结算函数 + 同一逐行绿/红。
 */
public enum RetireFlag {
    /** 生成遗体（在线判死/处决；自然死亡由 Corpse 模组自动生成，不需要本 flag）。 */
    SPAWN_CORPSE,
    /** 离线结算（结算结果挂起，上线补发）。 */
    OFFLINE,
    /** 跳过结算（退役/下班场景：只做状态迁移与冷却，不结算经验）。 */
    SKIP_SETTLE;

    public static Set<RetireFlag> of(RetireFlag... flags) {
        return flags.length == 0 ? EnumSet.noneOf(RetireFlag.class) : EnumSet.of(flags[0], flags);
    }
}
