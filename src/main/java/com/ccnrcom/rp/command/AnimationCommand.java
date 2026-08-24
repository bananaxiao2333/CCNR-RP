/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.command;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.util.Permissions;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** /rp animation play <id> [selector]（P7，调试/手动触发）。 */
final class AnimationCommand {
    private static final SuggestionProvider<CommandSourceStack> SEQ =
            (ctx, builder) -> SharedSuggestionProvider.suggest(ids(), builder);

    private AnimationCommand() {}

    static void register(LiteralCommandNode<CommandSourceStack> rp) {
        rp.addChild(Commands.literal("animation")
                .requires(RpCommand.admin(Permissions.ADMIN_ANIMATION))
                .then(Commands.literal("play")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests(SEQ)
                                .then(Commands.argument("targets", StringArgumentType.greedyString())
                                        .executes(ctx -> play(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "id"),
                                                StringArgumentType.getString(ctx, "targets"))))
                                .executes(ctx -> play(ctx.getSource(), StringArgumentType.getString(ctx, "id"), "@a"))))
                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                .build());
    }

    private static Iterable<String> ids() {
        List<String> out = new ArrayList<>();
        if (CCNRRPMod.animationEngine != null) {
            // 从 defaults 读取全量 id 不现实；至少提供常见钩子绑定
            out.add("spawn_intro");
            out.add("player_death");
            out.add("game_end");
            out.add("event_start_alarm");
            out.add("level_up");
        }
        return out;
    }

    private static int play(CommandSourceStack source, String id, String targetsArg) {
        if (CCNRRPMod.animationEngine == null) {
            return 0;
        }
        List<ServerPlayer> targets = new ArrayList<>();
        for (ServerPlayer p : source.getServer().getPlayerList().getPlayers()) {
            if (targetsArg.equals("@a")
                    || targetsArg.equals("@p") && p == source.getEntity()
                    || targetsArg.equals(p.getGameProfile().getName())) {
                targets.add(p);
            }
        }
        boolean ok = CCNRRPMod.animationEngine.play(id, targets, Map.of());
        source.sendSuccess(
                () -> Component.translatable(
                        ok ? "ccnr_rp.animation.played" : "ccnr_rp.animation.missing", id, targets.size()),
                false);
        return ok ? 1 : 0;
    }

    private static int list(CommandSourceStack source) {
        if (CCNRRPMod.animationEngine != null) {
            source.sendSuccess(
                    () -> Component.translatable("ccnr_rp.animation.count", CCNRRPMod.animationEngine.count()), false);
        }
        return 1;
    }
}
