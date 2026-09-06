/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.command;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.config.ModeManager;
import com.ccnrcom.rp.config.ModeManager.ModeDef;
import com.ccnrcom.rp.util.Permissions;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** /rp mode（P15）：多模式登记/热切换。 */
final class ModeCommand {

    private ModeCommand() {}

    static void register(LiteralCommandNode<CommandSourceStack> rp) {
        rp.addChild(Commands.literal("mode")
                .executes(ctx -> RpCommand.usageHint(ctx.getSource(), "ccnr_rp.command.usage.mode"))
                .requires(RpCommand.admin(Permissions.ADMIN_MODE))
                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                .then(Commands.literal("set")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests(RpSuggest.modes())
                                .executes(ctx -> set(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
                .then(Commands.literal("clear").executes(ctx -> clear(ctx.getSource())))
                .build());
    }

    private static int list(CommandSourceStack source) {
        if (CCNRRPMod.modes == null) {
            return 0;
        }
        source.sendSuccess(
                () -> Component.translatable(
                        "ccnr_rp.command.mode.active", CCNRRPMod.modes.activeId(), CCNRRPMod.modes.activeName()),
                false);
        for (ModeDef m : CCNRRPMod.modes.modes()) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.command.mode.item", m.id(), m.name()), false);
        }
        return 1;
    }

    private static int set(CommandSourceStack source, String id) {
        if (CCNRRPMod.modes == null) {
            return 0;
        }
        if (!CCNRRPMod.modes.set(id)) {
            source.sendFailure(Component.translatable("ccnr_rp.command.mode.not_found", id));
            return 0;
        }
        // 热切：重读新激活模式的剧本配置 + 重置剧本运行时（阶段回第 0 幕、事件/波次重载、停动画）
        ModeManager.resetScenarioRuntime();
        source.sendSuccess(() -> Component.translatable("ccnr_rp.command.mode.set", id), true);
        return 1;
    }

    private static int clear(CommandSourceStack source) {
        if (CCNRRPMod.modes == null) {
            return 0;
        }
        if (!CCNRRPMod.modes.clear()) {
            source.sendFailure(Component.translatable("ccnr_rp.command.mode.clear_fail"));
            return 0;
        }
        ModeManager.resetScenarioRuntime();
        source.sendSuccess(() -> Component.translatable("ccnr_rp.command.mode.clear"), true);
        return 1;
    }
}
