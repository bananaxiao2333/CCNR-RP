/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.spawn;

import java.util.EnumSet;
import java.util.Set;

/**
 * 部署行为 flag（只定义行为，不定义预设/来源）：
 * 自部署 / 管理员刷人 / 复活波 / 强制征召 / 手动部署全部收敛到
 * {@link SpawnFramework#deploy(net.minecraft.server.level.ServerPlayer, String, SpawnModels.Wave, Set)}，
 * 行为差异一律由本枚举控制。
 */
public enum DeployFlag {
    /** 取消开局黑屏（入场电影）。 */
    SKIP_CINEMATIC,
    /** 强制部署不论存活（跳过单在场守卫；管理员刷人/征召部署用）。 */
    FORCE_DEPLOY,
    /** 关闭部署音乐（入场电影音乐置空）。 */
    NO_MUSIC,
    /** 不刷额外提示（安静部署：不广播部署成功文案）。 */
    QUIET,
    /** 临时身份部署（征召兵：不写用户库角色，推送征召身份给客户端）。 */
    TEMP,
    /** 跳过部署人数限制（管理员刷人/强制征召等系统强制操作；玩家自部署/重新部署不设此 flag）。 */
    LIMIT_SKIP;

    public static Set<DeployFlag> of(DeployFlag... flags) {
        return flags.length == 0 ? EnumSet.noneOf(DeployFlag.class) : EnumSet.of(flags[0], flags);
    }
}
