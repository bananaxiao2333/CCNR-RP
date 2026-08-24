/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.animation;

import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;

/**
 * 动画钩子（公共契约）：player_spawn / player_death / game_end / event_start / level_up。
 * P7 起转发到 AnimationEngine（引擎未就绪时静默跳过）。
 */
public final class AnimationHooks {

    private AnimationHooks() {}

    private static AnimationEngine engine() {
        return com.ccnrcom.rp.CCNRRPMod.animationEngine;
    }

    public static void levelUp(ServerPlayer player, int level) {
        if (player != null && engine() != null) {
            engine().playHook("level_up", List.of(player), Map.of("level", String.valueOf(level)));
        }
    }

    public static void playerSpawn(ServerPlayer player, String charName) {
        if (player != null && engine() != null) {
            engine().playHook("player_spawn", List.of(player), Map.of("name", charName == null ? "" : charName));
        }
    }

    public static void playerDeath(ServerPlayer player, String charName) {
        if (engine() != null) {
            engine().playHook(
                            "player_death",
                            player == null ? List.of() : List.of(player),
                            Map.of("name", charName == null ? "" : charName));
        }
    }

    public static void eventStart(String eventId, List<ServerPlayer> targets) {
        if (engine() != null) {
            engine().playHook("event_start", targets, Map.of("event", eventId));
        }
    }

    public static void gameEnd(List<ServerPlayer> targets) {
        if (engine() != null) {
            engine().playHook("game_end", targets, Map.of());
        }
    }
}
