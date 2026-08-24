/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.command;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.character.CharacterData;
import com.ccnrcom.rp.status.StatusManager;
import com.ccnrcom.rp.util.Permissions;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** /rp state / rp kill（P4）。 */
final class StateCommand {

    private StateCommand() {}

    static void register(LiteralCommandNode<CommandSourceStack> rp) {
        rp.addChild(Commands.literal("state")
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> state(ctx.getSource(), StringArgumentType.getString(ctx, "player"))))
                .build());
        rp.addChild(Commands.literal("kill")
                .requires(RpCommand.admin(Permissions.ADMIN_KILL))
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> kill(ctx.getSource(), StringArgumentType.getString(ctx, "player"))))
                .build());
    }

    private static int state(CommandSourceStack source, String playerName) {
        ServerPlayer target = source.getServer().getPlayerList().getPlayerByName(playerName);
        if (target == null) {
            boolean found = false;
            for (CharacterData c : CCNRRPMod.characters.store().all()) {
                if (c.playerUuid().equals(playerName)) {
                    found = true;
                }
            }
            source.sendSuccess(() -> Component.translatable("ccnr_rp.error.player_not_found", playerName), false);
            return found ? 1 : 0;
        }
        for (CharacterData c :
                CCNRRPMod.characters.store().ofPlayer(target.getUUID().toString())) {
            long remain = (c.cooldownUntil() - System.currentTimeMillis()) / 60000L;
            source.sendSuccess(
                    () -> Component.translatable(
                            "ccnr_rp.character.info",
                            c.name(),
                            c.factionId(),
                            c.professionId(),
                            c.status().name().toLowerCase(java.util.Locale.ROOT),
                            Math.max(0, remain)),
                    false);
        }
        return 1;
    }

    private static int kill(CommandSourceStack source, String playerName) {
        ServerPlayer target = source.getServer().getPlayerList().getPlayerByName(playerName);
        if (target == null) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.error.player_not_found", playerName), false);
            return 0;
        }
        StatusManager.killCommand(target);
        return 1;
    }
}
