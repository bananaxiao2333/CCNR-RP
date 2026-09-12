/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.command;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.util.Permissions;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * /rp evac &lt;分值&gt; &lt;标题&gt;：手动触发一次**疏散结算**（序列步骤 {@code EVACUATE} 的命令入口）。
 *
 * <p>语义与序列步骤完全一致（同一 {@code ExperienceService.evacuateAlive}）：对当前在场（ALIVE）用户
 * 追加一条疏散分 → 立即结算（其他分数照算）→ 转观察者；走非死亡路径，不产生阵亡扣分（docs/06 §6）。
 * 标题必填且是数据（会显示在结算动画的数值后面），故不设内置默认文案（docs/01 §9.5 数据驱动）。
 *
 * <p>权限用 {@code ccnrrp.admin.settle}（与 /rp settle、/rp xp add 同一节点）——它是一次结算动作。
 */
final class EvacCommand {

    private EvacCommand() {}

    static void register(LiteralCommandNode<CommandSourceStack> rp) {
        rp.addChild(Commands.literal("evac")
                .executes(ctx -> RpCommand.usageHint(ctx.getSource(), "ccnr_rp.command.usage.evac"))
                .requires(RpCommand.admin(Permissions.ADMIN_SETTLE))
                .then(Commands.argument("xp", IntegerArgumentType.integer())
                        .then(Commands.argument("title", StringArgumentType.greedyString())
                                .executes(ctx -> evac(
                                        ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "xp"),
                                        StringArgumentType.getString(ctx, "title")))))
                .build());
    }

    private static int evac(CommandSourceStack source, int xp, String title) {
        if (CCNRRPMod.experience == null) {
            return 0;
        }
        String safeTitle = title == null ? "" : title.trim();
        if (safeTitle.isEmpty()) {
            return RpCommand.usageHint(source, "ccnr_rp.command.usage.evac");
        }
        int count = CCNRRPMod.experience.evacuateAlive(safeTitle, xp);
        source.sendSuccess(() -> Component.translatable("ccnr_rp.evac.done", count, xp, safeTitle), true);
        return count > 0 ? 1 : 0;
    }
}
