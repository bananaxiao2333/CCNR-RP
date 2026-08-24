/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.animation;

import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 动画钩子（P5 先占位，P7 接入真正引擎）。
 * 钩子契约（公共）：player_spawn / player_death / game_end / event_start / level_up。
 * P7 实现后这些方法将转发到 AnimationEngine，参数化渲染序列。
 */
public final class AnimationHooks {
    private static final Logger LOGGER = LogManager.getLogger();

    private AnimationHooks() {}

    public static void levelUp(ServerPlayer player, int level) {
        if (player != null) {
            LOGGER.info(
                    "[CCNR-RP] 钩子 level_up -> {} 等级 {}", player.getGameProfile().getName(), level);
        }
    }

    public static void playerSpawn(ServerPlayer player, String charName) {
        LOGGER.info(
                "[CCNR-RP] 钩子 player_spawn -> {} ({})", player.getGameProfile().getName(), charName);
    }

    public static void playerDeath(ServerPlayer player, String charName) {
        LOGGER.info(
                "[CCNR-RP] 钩子 player_death -> {} ({})",
                player == null ? "?" : player.getGameProfile().getName(),
                charName);
    }

    public static void eventStart(String eventId, java.util.List<ServerPlayer> targets) {
        LOGGER.info("[CCNR-RP] 钩子 event_start -> {} ({} 个目标)", eventId, targets.size());
    }

    public static void gameEnd(java.util.List<ServerPlayer> targets) {
        LOGGER.info("[CCNR-RP] 钩子 game_end -> {} 个目标", targets.size());
    }
}
