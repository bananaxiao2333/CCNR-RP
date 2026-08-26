/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.command;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.experience.ExperienceService.SettleSummary;
import com.ccnrcom.rp.util.Permissions;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** /rp settle / rp xp / rp level（经验系统 v3：规则引擎结算，经验随用户走）。 */
final class XpCommand {

    private XpCommand() {}

    static void register(LiteralCommandNode<CommandSourceStack> rp) {
        rp.addChild(Commands.literal("settle")
                .executes(ctx -> RpCommand.usageHint(ctx.getSource(), "ccnr_rp.command.usage.xp"))
                .requires(RpCommand.admin(Permissions.ADMIN_SETTLE))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(RpSuggest.players())
                        .executes(ctx -> settle(ctx.getSource(), StringArgumentType.getString(ctx, "player"))))
                .then(Commands.literal("all").executes(ctx -> settleAll(ctx.getSource())))
                .build());
        rp.addChild(Commands.literal("xp")
                .executes(ctx -> RpCommand.usageHint(ctx.getSource(), "ccnr_rp.command.usage.xp"))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(RpSuggest.players())
                        .executes(ctx -> info(ctx.getSource(), StringArgumentType.getString(ctx, "player"), true)))
                .then(Commands.literal("add")
                        .requires(RpCommand.admin(Permissions.ADMIN_SETTLE))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(RpSuggest.players())
                                .then(Commands.argument(
                                                "value", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                        .then(Commands.argument("title", StringArgumentType.greedyString())
                                                .executes(ctx -> addScore(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "player"),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(
                                                                ctx, "value"),
                                                        StringArgumentType.getString(ctx, "title")))))))
                .build());
        rp.addChild(Commands.literal("level")
                .executes(ctx -> RpCommand.usageHint(ctx.getSource(), "ccnr_rp.command.usage.xp"))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(RpSuggest.players())
                        .executes(ctx -> info(ctx.getSource(), StringArgumentType.getString(ctx, "player"), false)))
                .build());
    }

    private static int settle(CommandSourceStack source, String playerName) {
        ServerPlayer target = source.getServer().getPlayerList().getPlayerByName(playerName);
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.error.player_not_found", playerName), false);
            return 0;
        }
        if (CCNRRPMod.experience == null || CCNRRPMod.users == null) {
            return 0;
        }
        SettleSummary s = CCNRRPMod.experience.settleUser(target.getUUID().toString(), true);
        if (s == null) {
            return 0;
        }
        source.sendSuccess(
                () -> Component.translatable(
                        "ccnr_rp.xp.settle.result",
                        playerName,
                        String.valueOf(s.gain()),
                        String.valueOf(s.newXp()),
                        String.valueOf(s.newLevel())),
                false);
        return 1;
    }

    /** /rp xp add <玩家> <数值> <标题>：向玩家待结算列表添加自定义记分条目（数值可为负，标题可含空格）。 */
    private static int addScore(CommandSourceStack source, String playerName, int value, String title) {
        ServerPlayer target = source.getServer().getPlayerList().getPlayerByName(playerName);
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.error.player_not_found", playerName), false);
            return 0;
        }
        if (CCNRRPMod.experience == null || CCNRRPMod.users == null) {
            return 0;
        }
        String err = CCNRRPMod.experience.addManualScore(target.getUUID().toString(), title, value);
        if (!err.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(err, playerName), false);
            return 0;
        }
        source.sendSuccess(
                () -> Component.translatable("ccnr_rp.xp.add.ok", playerName, title, String.valueOf(value)), false);
        return 1;
    }

    private static int settleAll(CommandSourceStack source) {
        if (CCNRRPMod.experience == null || CCNRRPMod.users == null) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.xp.settle.all", 0), false);
            return 0;
        }
        List<SettleSummary> results = CCNRRPMod.experience.settleAll(null);
        source.sendSuccess(() -> Component.translatable("ccnr_rp.xp.settle.all", results.size()), false);
        return 1;
    }

    private static int info(CommandSourceStack source, String playerName, boolean showXp) {
        ServerPlayer target = source.getServer().getPlayerList().getPlayerByName(playerName);
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.error.player_not_found", playerName), false);
            return 0;
        }
        if (CCNRRPMod.users == null) {
            return 0;
        }
        String uuid = target.getUUID().toString();
        String name = target.getName().getString();
        if (showXp) {
            source.sendSuccess(
                    () -> Component.translatable(
                            "ccnr_rp.xp.info",
                            name,
                            String.valueOf(CCNRRPMod.users.userXp(uuid)),
                            String.valueOf(CCNRRPMod.users.level(uuid))),
                    false);
        } else {
            source.sendSuccess(
                    () -> Component.translatable(
                            "ccnr_rp.xp.level.info", name, String.valueOf(CCNRRPMod.users.level(uuid))),
                    false);
        }
        return 1;
    }
}
