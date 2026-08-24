/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.function.Predicate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.permission.nodes.PermissionNode;

/** /rp 命令树根：各系统子命令在此挂载。 */
public final class RpCommand {

    private RpCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> rp = dispatcher.register(Commands.literal("rp")
                .executes(ctx -> help(ctx.getSource()))
                .then(Commands.literal("help").executes(ctx -> help(ctx.getSource()))));
        FactionCommand.register(rp);
        ProfessionCommand.register(rp);
        CharacterCommand.register(rp);
        StateCommand.register(rp);
        XpCommand.register(rp);
        EventCommand.register(rp);
        AnimationCommand.register(rp);
        SpawnCommand.register(rp);
        rpNode = rp;
    }

    private static LiteralCommandNode<CommandSourceStack> rpNode;

    public static LiteralCommandNode<CommandSourceStack> rootNode() {
        return rpNode;
    }

    /** /rp help：全量命令提示（分级 + 参数签名）。 */
    private static int help(CommandSourceStack source) {
        String[] keys = {
            "ccnr_rp.command.help",
            "ccnr_rp.command.usage.gui",
            "ccnr_rp.command.usage.faction",
            "ccnr_rp.command.usage.profession",
            "ccnr_rp.command.usage.character",
            "ccnr_rp.command.usage.state",
            "ccnr_rp.command.usage.xp",
            "ccnr_rp.command.usage.event",
            "ccnr_rp.command.usage.animation",
            "ccnr_rp.command.usage.spawn",
            "ccnr_rp.command.usage.admin"
        };
        for (String k : keys) {
            source.sendSuccess(() -> Component.translatable(k), false);
        }
        return 1;
    }

    /** 打印命令提示（缺参/裸命令时输出该命令完整用法）。 */
    public static int usageHint(CommandSourceStack source, String usageKey) {
        source.sendSuccess(() -> Component.translatable(usageKey), false);
        return 1;
    }

    /** 管理命令要求：OP≥2，或拥有权限节点的在线玩家。 */
    public static Predicate<CommandSourceStack> admin(PermissionNode<Boolean> node) {
        return src -> {
            if (src.getEntity() instanceof ServerPlayer p) {
                return com.ccnrcom.rp.util.Permissions.canAdmin(p, node);
            }
            return src.hasPermission(2);
        };
    }

    /** 挂载子命令到 /rp 根。 */
    public static void mount(LiteralArgumentBuilder<CommandSourceStack> builder) {
        if (rpNode != null) {
            rpNode.addChild(builder.build());
        }
    }
}
