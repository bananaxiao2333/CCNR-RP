/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.command;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.util.Permissions;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * /rp wave spawn &lt;id&gt;（P15 §5）：从**波次库**显式召一波，供剧本/管理端手动调兵。
 *
 * <p>与 /rp spawn trigger 同源（都进 SpawnFramework.triggerWave），单独成命令是因为
 * 语义不同：spawn 管的是"刷新波定义"，wave 管的是"按剧本召波"（docs/15 §4.4 波次库纯脚本/命令召），
 * 且权限节点独立（{@code ccnrrp.admin.wave}），可只授权给带队管理。
 */
final class WaveCommand {

    private WaveCommand() {}

    static void register(LiteralCommandNode<CommandSourceStack> rp) {
        rp.addChild(Commands.literal("wave")
                .executes(ctx -> RpCommand.usageHint(ctx.getSource(), "ccnr_rp.command.usage.wave"))
                .requires(RpCommand.admin(Permissions.ADMIN_WAVE))
                .then(Commands.literal("spawn")
                        .executes(ctx -> RpCommand.usageHint(ctx.getSource(), "ccnr_rp.command.usage.wave"))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests(RpSuggest.waves())
                                .executes(ctx -> spawn(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
                .build());
    }

    private static int spawn(CommandSourceStack source, String id) {
        if (CCNRRPMod.spawnFramework == null) {
            return 0;
        }
        boolean known = CCNRRPMod.spawnFramework.waves().stream().anyMatch(w -> w.id().equals(id));
        if (!known) {
            source.sendFailure(Component.translatable("ccnr_rp.spawn.not_found", id));
            return 0;
        }
        CCNRRPMod.spawnFramework.triggerWave(id);
        source.sendSuccess(() -> Component.translatable("ccnr_rp.wave.spawned", id), true);
        return 1;
    }
}
