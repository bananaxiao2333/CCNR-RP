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
        LiteralCommandNode<CommandSourceStack> rp =
                dispatcher.register(Commands.literal("rp").executes(ctx -> help(ctx.getSource())));
        FactionCommand.register(rp);
        ProfessionCommand.register(rp);
        CharacterCommand.register(rp);
        StateCommand.register(rp);
        rpNode = rp;
    }

    private static LiteralCommandNode<CommandSourceStack> rpNode;

    public static LiteralCommandNode<CommandSourceStack> rootNode() {
        return rpNode;
    }

    private static int help(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("ccnr_rp.command.help"), false);
        source.sendSuccess(
                () -> Component.literal("  /rp faction list | relation <a> <b> | relation set <a> <b> <type>"
                        + " | group list | group create <id> <ids...> | group relation <g1> <g2> <type>"),
                false);
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
