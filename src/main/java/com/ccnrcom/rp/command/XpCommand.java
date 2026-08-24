/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.command;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.character.CharacterData;
import com.ccnrcom.rp.experience.SettlementCalcs.EvacuationMethod;
import com.ccnrcom.rp.util.Permissions;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** /rp settle / rp xp / rp level / rp evac（P5）。 */
final class XpCommand {

    private XpCommand() {}

    static void register(LiteralCommandNode<CommandSourceStack> rp) {
        rp.addChild(Commands.literal("settle")
                .executes(ctx -> RpCommand.usageHint(ctx.getSource(), "ccnr_rp.command.usage.xp"))
                .requires(RpCommand.admin(Permissions.ADMIN_SETTLE))
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> settle(ctx.getSource(), StringArgumentType.getString(ctx, "player"))))
                .then(Commands.literal("all").executes(ctx -> settleAll(ctx.getSource())))
                .build());
        rp.addChild(Commands.literal("xp")
                .executes(ctx -> RpCommand.usageHint(ctx.getSource(), "ccnr_rp.command.usage.xp"))
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> info(ctx.getSource(), StringArgumentType.getString(ctx, "player"), true)))
                .build());
        rp.addChild(Commands.literal("level")
                .executes(ctx -> RpCommand.usageHint(ctx.getSource(), "ccnr_rp.command.usage.xp"))
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> info(ctx.getSource(), StringArgumentType.getString(ctx, "player"), false)))
                .build());
        rp.addChild(Commands.literal("evac")
                .executes(ctx -> RpCommand.usageHint(ctx.getSource(), "ccnr_rp.command.usage.xp"))
                .requires(RpCommand.admin(Permissions.ADMIN_SETTLE))
                .then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("method", StringArgumentType.word())
                                .executes(ctx -> evac(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "player"),
                                        StringArgumentType.getString(ctx, "method")))))
                .build());
    }

    private static int settle(CommandSourceStack source, String playerName) {
        ServerPlayer target = source.getServer().getPlayerList().getPlayerByName(playerName);
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.error.player_not_found", playerName), false);
            return 0;
        }
        List<List<String>> results =
                CCNRRPMod.experience.settleAll(target.getUUID().toString());
        results.forEach(v -> source.sendSuccess(
                () -> Component.translatable(
                        "ccnr_rp.xp.settle.result",
                        v.get(0),
                        v.get(1),
                        v.get(4),
                        v.get(5),
                        v.get(6),
                        v.get(2),
                        v.get(3)),
                false));
        return 1;
    }

    private static int settleAll(CommandSourceStack source) {
        List<List<String>> results = CCNRRPMod.experience.settleAll(null);
        source.sendSuccess(() -> Component.translatable("ccnr_rp.xp.settle.all", results.size()), false);
        return 1;
    }

    private static int info(CommandSourceStack source, String playerName, boolean showXp) {
        ServerPlayer target = source.getServer().getPlayerList().getPlayerByName(playerName);
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.error.player_not_found", playerName), false);
            return 0;
        }
        for (CharacterData c :
                CCNRRPMod.characters.store().ofPlayer(target.getUUID().toString())) {
            source.sendSuccess(
                    () -> Component.translatable(
                            showXp ? "ccnr_rp.xp.info" : "ccnr_rp.xp.level.info", c.name(), c.xp(), levelOf(c.xp())),
                    false);
        }
        return 1;
    }

    private static int levelOf(long xp) {
        try {
            return new com.ccnrcom.rp.experience.LevelCurve(
                            com.ccnrcom.rp.config.CCNRRPConfig.LEVEL_BASE.get(),
                            com.ccnrcom.rp.config.CCNRRPConfig.LEVEL_POW.get())
                    .level(xp);
        } catch (Exception e) {
            return 0;
        }
    }

    private static int evac(CommandSourceStack source, String playerName, String methodName) {
        ServerPlayer target = source.getServer().getPlayerList().getPlayerByName(playerName);
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.error.player_not_found", playerName), false);
            return 0;
        }
        EvacuationMethod method;
        try {
            method = EvacuationMethod.valueOf(methodName.toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.xp.error.method", methodName), false);
            return 0;
        }
        boolean any = false;
        for (CharacterData c :
                CCNRRPMod.characters.store().ofPlayer(target.getUUID().toString())) {
            if (c.status() != com.ccnrcom.rp.status.CharacterStatus.DEAD) {
                com.ccnrcom.rp.experience.ExperienceService.setEvacuation(c.id(), method);
                any = true;
            }
        }
        source.sendSuccess(() -> Component.translatable("ccnr_rp.xp.evac.set", playerName, method.name()), false);
        return any ? 1 : 0;
    }
}
