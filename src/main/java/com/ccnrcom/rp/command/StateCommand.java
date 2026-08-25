/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.command;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.status.CharacterStatus;
import com.ccnrcom.rp.util.Permissions;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * /rp state / rp kill（P9，v2：状态与冷却随用户走，直接读写 UserService）。
 */
final class StateCommand {

    private StateCommand() {}

    static void register(LiteralCommandNode<CommandSourceStack> rp) {
        rp.addChild(Commands.literal("state")
                .executes(ctx -> RpCommand.usageHint(ctx.getSource(), "ccnr_rp.command.usage.state"))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(RpSuggest.players())
                        .executes(ctx -> state(ctx.getSource(), StringArgumentType.getString(ctx, "player"))))
                .build());
        rp.addChild(Commands.literal("kill")
                .executes(ctx -> RpCommand.usageHint(ctx.getSource(), "ccnr_rp.command.usage.state"))
                .requires(RpCommand.admin(Permissions.ADMIN_KILL))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(RpSuggest.players())
                        .executes(ctx -> kill(ctx.getSource(), StringArgumentType.getString(ctx, "player"))))
                .build());
    }

    private static int state(CommandSourceStack source, String playerName) {
        ServerPlayer target = source.getServer().getPlayerList().getPlayerByName(playerName);
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.error.player_not_found", playerName), false);
            return 0;
        }
        if (CCNRRPMod.users == null) {
            return 0;
        }
        String uuid = target.getUUID().toString();
        CharacterStatus status = CCNRRPMod.users.status(uuid);
        long remain = (CCNRRPMod.users.cooldownUntil(uuid) - System.currentTimeMillis()) / 60000L;
        source.sendSuccess(
                () -> Component.translatable(
                        "ccnr_rp.character.info",
                        target.getName().getString(),
                        CCNRRPMod.users.factionId(uuid),
                        CCNRRPMod.users.professionId(uuid),
                        status.name().toLowerCase(java.util.Locale.ROOT),
                        Math.max(0, remain)),
                false);
        return 1;
    }

    private static int kill(CommandSourceStack source, String playerName) {
        ServerPlayer target = source.getServer().getPlayerList().getPlayerByName(playerName);
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.error.player_not_found", playerName), false);
            return 0;
        }
        if (CCNRRPMod.users == null) {
            return 0;
        }
        String uuid = target.getUUID().toString();
        if (CCNRRPMod.users.status(uuid) != CharacterStatus.ALIVE) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.status.error.no_alive"), false);
            return 0;
        }
        // 统一退场（与 killCommand 同一入口）：状态迁移 + 冷却 + 遗体 + 结算 + 逐行
        com.ccnrcom.rp.status.StatusManager.retire(
                uuid,
                target,
                "command",
                com.ccnrcom.rp.status.RetireFlag.of(com.ccnrcom.rp.status.RetireFlag.SPAWN_CORPSE));
        source.sendSuccess(
                () -> Component.translatable(
                        "ccnr_rp.status.killed.command", target.getName().getString()),
                false);
        return 1;
    }
}
