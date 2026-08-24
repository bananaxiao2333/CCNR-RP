/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.command;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.util.Permissions;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** /rp sequence 序列命令（序列编辑器运行入口，管理员）。 */
final class SequenceCommand {

    private SequenceCommand() {}

    static void register(LiteralCommandNode<CommandSourceStack> rp) {
        LiteralArgumentBuilder<CommandSourceStack> base = Commands.literal("sequence")
                .executes(ctx -> RpCommand.usageHint(ctx.getSource(), "ccnr_rp.command.usage.sequence"));
        base.then(Commands.literal("list")
                .requires(RpCommand.admin(Permissions.ADMIN_FACTION))
                .executes(ctx -> list(ctx.getSource())));
        base.then(Commands.literal("run")
                .requires(RpCommand.admin(Permissions.ADMIN_FACTION))
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> run(ctx.getSource(), StringArgumentType.getString(ctx, "id")))));
        rp.addChild(base.build());
    }

    private static int list(CommandSourceStack source) {
        if (CCNRRPMod.sequenceEngine == null) {
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("ccnr_rp.sequence.list.header"), false);
        for (String id : CCNRRPMod.sequenceEngine.list()) {
            source.sendSuccess(() -> Component.literal("  - " + id), false);
        }
        return 1;
    }

    private static int run(CommandSourceStack source, String id) {
        if (CCNRRPMod.sequenceEngine == null) {
            return 0;
        }
        List<String> errors = CCNRRPMod.sequenceEngine.run(id, java.util.Map.of());
        if (!errors.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.sequence.error", String.join("; ", errors)), true);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("ccnr_rp.sequence.started", id), true);
        return 1;
    }
}
