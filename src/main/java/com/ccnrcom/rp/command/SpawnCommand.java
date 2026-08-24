/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.command;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.spawn.SpawnModels.Wave;
import com.ccnrcom.rp.util.Permissions;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** /rp spawn（P8）。 */
final class SpawnCommand {

    private SpawnCommand() {}

    static void register(LiteralCommandNode<CommandSourceStack> rp) {
        rp.addChild(Commands.literal("spawn")
                .requires(RpCommand.admin(Permissions.ADMIN_SPAWN))
                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                .then(Commands.literal("trigger")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> trigger(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
                .then(Commands.literal("enable")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("on", BoolArgumentType.bool())
                                        .executes(ctx -> enable(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "id"),
                                                BoolArgumentType.getBool(ctx, "on"))))))
                .build());
    }

    private static int list(CommandSourceStack source) {
        if (CCNRRPMod.spawnFramework == null) {
            return 0;
        }
        for (Wave w : CCNRRPMod.spawnFramework.waves()) {
            source.sendSuccess(
                    () -> Component.translatable(
                            "ccnr_rp.spawn.list.item", w.id(), w.mode().name(), w.enabled(), w.count(), w.teamIds()),
                    false);
        }
        return 1;
    }

    private static int trigger(CommandSourceStack source, String id) {
        if (CCNRRPMod.spawnFramework == null) {
            return 0;
        }
        CCNRRPMod.spawnFramework.triggerWave(id);
        source.sendSuccess(() -> Component.translatable("ccnr_rp.spawn.triggered", id), false);
        return 1;
    }

    private static int enable(CommandSourceStack source, String id, boolean on) {
        if (CCNRRPMod.spawnFramework == null) {
            return 0;
        }
        boolean ok = CCNRRPMod.spawnFramework.setEnabled(id, on);
        source.sendSuccess(
                () -> Component.translatable(ok ? "ccnr_rp.spawn.enabled" : "ccnr_rp.spawn.not_found", id, on), false);
        return ok ? 1 : 0;
    }
}
