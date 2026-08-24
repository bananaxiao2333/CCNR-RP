/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.command;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.event.EventModels.EventDefinition;
import com.ccnrcom.rp.util.Permissions;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** /rp event / rp phase / rp gameover（P6）。 */
final class EventCommand {

    private EventCommand() {}

    static void register(LiteralCommandNode<CommandSourceStack> rp) {
        rp.addChild(Commands.literal("event")
                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                .then(Commands.literal("trigger")
                        .requires(RpCommand.admin(Permissions.ADMIN_EVENT))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> trigger(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
                .then(Commands.literal("end")
                        .requires(RpCommand.admin(Permissions.ADMIN_EVENT))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> end(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
                .then(Commands.literal("enable")
                        .requires(RpCommand.admin(Permissions.ADMIN_EVENT))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("on", BoolArgumentType.bool())
                                        .executes(ctx -> enable(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "id"),
                                                BoolArgumentType.getBool(ctx, "on"))))))
                .build());
        rp.addChild(Commands.literal("phase")
                .requires(RpCommand.admin(Permissions.ADMIN_PHASE))
                .then(Commands.literal("list").executes(ctx -> phaseList(ctx.getSource())))
                .then(Commands.literal("set")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> phaseSet(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
                .then(Commands.literal("advance").executes(ctx -> phaseAdvance(ctx.getSource())))
                .build());
        rp.addChild(Commands.literal("gameover")
                .requires(RpCommand.admin(Permissions.ADMIN_PHASE))
                .executes(ctx -> {
                    if (CCNRRPMod.eventManager != null) {
                        CCNRRPMod.eventManager.gameOver();
                        ctx.getSource().sendSuccess(() -> Component.translatable("ccnr_rp.event.gameover"), true);
                        return 1;
                    }
                    return 0;
                })
                .build());
    }

    private static int list(CommandSourceStack source) {
        if (CCNRRPMod.eventManager == null) {
            return 0;
        }
        for (EventDefinition e : CCNRRPMod.eventManager.events()) {
            source.sendSuccess(
                    () -> Component.translatable(
                            "ccnr_rp.event.list.item",
                            e.id(),
                            e.enabled(),
                            e.state().name(),
                            e.triggers().size(),
                            e.tasks().size()),
                    false);
        }
        return 1;
    }

    private static int trigger(CommandSourceStack source, String id) {
        if (CCNRRPMod.eventManager == null) {
            return 0;
        }
        boolean ok = CCNRRPMod.eventManager.triggerEvent(id);
        source.sendSuccess(
                () -> Component.translatable(ok ? "ccnr_rp.event.triggered" : "ccnr_rp.event.not_runnable", id), false);
        return ok ? 1 : 0;
    }

    private static int end(CommandSourceStack source, String id) {
        if (CCNRRPMod.eventManager == null) {
            return 0;
        }
        boolean ok = CCNRRPMod.eventManager.endEvent(id);
        source.sendSuccess(
                () -> Component.translatable(ok ? "ccnr_rp.event.ended" : "ccnr_rp.event.not_runnable", id), false);
        return ok ? 1 : 0;
    }

    private static int enable(CommandSourceStack source, String id, boolean on) {
        if (CCNRRPMod.eventManager == null) {
            return 0;
        }
        boolean ok = CCNRRPMod.eventManager.setEnabled(id, on);
        source.sendSuccess(
                () -> Component.translatable(ok ? "ccnr_rp.event.enabled" : "ccnr_rp.event.not_found", id, on), false);
        return ok ? 1 : 0;
    }

    private static int phaseList(CommandSourceStack source) {
        if (CCNRRPMod.eventManager == null) {
            return 0;
        }
        CCNRRPMod.eventManager
                .clock()
                .phases()
                .forEach(p -> source.sendSuccess(
                        () -> Component.translatable(
                                "ccnr_rp.event.phase.item",
                                p.id(),
                                p.durationMinutes(),
                                CCNRRPMod.eventManager.clock().current() != null
                                        && CCNRRPMod.eventManager
                                                .clock()
                                                .current()
                                                .id()
                                                .equals(p.id())),
                        false));
        return 1;
    }

    private static int phaseSet(CommandSourceStack source, String id) {
        if (CCNRRPMod.eventManager == null) {
            return 0;
        }
        boolean ok = CCNRRPMod.eventManager.setPhase(id);
        source.sendSuccess(
                () -> Component.translatable(ok ? "ccnr_rp.event.phase.set" : "ccnr_rp.event.phase.not_found", id),
                false);
        return ok ? 1 : 0;
    }

    private static int phaseAdvance(CommandSourceStack source) {
        if (CCNRRPMod.eventManager == null) {
            return 0;
        }
        CCNRRPMod.eventManager.clock().advance();
        source.sendSuccess(
                () -> Component.translatable(
                        "ccnr_rp.event.phase.set",
                        CCNRRPMod.eventManager.clock().phaseId()),
                false);
        return 1;
    }
}
