/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.command;

import com.ccnrcom.rp.CCNRRPMod;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/** /rp 命令参数补全建议器（Tab 补全列表）。 */
public final class RpSuggest {

    private RpSuggest() {}

    /** 从动态来源补全（每次请求实时取）。 */
    public static SuggestionProvider<CommandSourceStack> from(Supplier<? extends Iterable<String>> values) {
        return (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
            for (String v : values.get()) {
                if (v != null && v.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(v);
                }
            }
            return builder.buildFuture();
        };
    }

    /** 在线玩家名。 */
    public static SuggestionProvider<CommandSourceStack> players() {
        return (ctx, builder) -> {
            if (ctx.getSource().getServer() != null) {
                for (ServerPlayer p :
                        ctx.getSource().getServer().getPlayerList().getPlayers()) {
                    builder.suggest(p.getGameProfile().getName());
                }
            }
            return builder.buildFuture();
        };
    }

    /** 阵营 id。 */
    public static SuggestionProvider<CommandSourceStack> factions() {
        return from(() -> CCNRRPMod.factions != null
                ? CCNRRPMod.factions.graph().factions().keySet()
                : List.of());
    }

    /** 职业 id。 */
    public static SuggestionProvider<CommandSourceStack> professions() {
        return from(() -> CCNRRPMod.factions != null ? CCNRRPMod.factions.professionIds() : List.of());
    }

    /** 事件 id。 */
    public static SuggestionProvider<CommandSourceStack> events() {
        return from(() -> CCNRRPMod.eventManager != null
                ? CCNRRPMod.eventManager.events().stream().map(e -> e.id()).toList()
                : List.of());
    }

    /** 刷新波 id。 */
    public static SuggestionProvider<CommandSourceStack> waves() {
        return from(() -> CCNRRPMod.spawnFramework != null
                ? CCNRRPMod.spawnFramework.waves().stream().map(w -> w.id()).toList()
                : List.of());
    }

    /** mode id（多模式登记列表）。 */
    public static SuggestionProvider<CommandSourceStack> modes() {
        return from(() -> CCNRRPMod.modes != null
                ? CCNRRPMod.modes.modes().stream().map(m -> m.id()).toList()
                : List.of());
    }

    /** 阶段 id。 */
    public static SuggestionProvider<CommandSourceStack> phases() {
        return from(() -> CCNRRPMod.eventManager != null
                ? CCNRRPMod.eventManager.clock().phases().stream()
                        .map(p -> p.id())
                        .toList()
                : List.of());
    }

    /** 通用调试占位（无实际来源时返回空）。 */
    public static SuggestionProvider<CommandSourceStack> none() {
        return (ctx, builder) -> builder.buildFuture();
    }
}
