/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.command;

import com.ccnrcom.rp.character.CharacterService;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * /rp character 角色/职位命令（P9；角色库已删除，改为按「职位」维度操作）。
 * 保留 list（请求用户档案列表）与 deploy（按职位自刷新部署），并保留 cooldown（查询自身状态与冷却）。
 */
final class CharacterCommand {

    private CharacterCommand() {}

    static void register(LiteralCommandNode<CommandSourceStack> rp) {
        LiteralArgumentBuilder<CommandSourceStack> base = Commands.literal("character")
                .executes(ctx -> RpCommand.usageHint(ctx.getSource(), "ccnr_rp.command.usage.character"));

        // list：请求用户档案列表（K 面板/客户端全量初始化）。
        base.then(Commands.literal("list").executes(ctx -> {
            if (ctx.getSource().getEntity() instanceof ServerPlayer p) {
                CharacterService.onRequestList(p);
                return 1;
            }
            return 0;
        }));

        // deploy：按职位自刷新部署到游戏内（校验与落地都在 CharacterService.onDeployPosition）。
        base.then(Commands.literal("deploy")
                .then(Commands.argument("profession", StringArgumentType.word())
                        .suggests(RpSuggest.professions())
                        .executes(ctx -> {
                            if (ctx.getSource().getEntity() instanceof ServerPlayer p) {
                                CharacterService.onDeployPosition(p, StringArgumentType.getString(ctx, "profession"));
                                return 1;
                            }
                            return 0;
                        })));

        // cooldown：查询自己的当前状态/职位/阵营/剩余冷却。
        base.then(Commands.literal("cooldown").executes(ctx -> {
            if (ctx.getSource().getEntity() instanceof ServerPlayer p) {
                return info(ctx.getSource(), p);
            }
            return 0;
        }));

        rp.addChild(base.build());
    }

    private static int info(CommandSourceStack source, ServerPlayer player) {
        if (com.ccnrcom.rp.CCNRRPMod.users == null) {
            return 0;
        }
        com.ccnrcom.rp.user.UserService users = com.ccnrcom.rp.CCNRRPMod.users;
        String uuid = player.getUUID().toString();
        com.ccnrcom.rp.status.CharacterStatus status = users.status(uuid);
        long remain = (users.cooldownUntil(uuid) - System.currentTimeMillis()) / 60000L;
        source.sendSuccess(
                () -> Component.translatable(
                        "ccnr_rp.character.info",
                        player.getName().getString(),
                        users.factionId(uuid),
                        users.professionId(uuid),
                        status.name().toLowerCase(java.util.Locale.ROOT),
                        remain < 0 ? 0 : remain),
                false);
        return 1;
    }
}
