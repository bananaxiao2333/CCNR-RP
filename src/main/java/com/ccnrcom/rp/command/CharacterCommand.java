/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.command;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.character.CharacterData;
import com.ccnrcom.rp.character.CharacterService;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** /rp character 角色命令（P3；GUI 为主，命令为快捷入口，规则一致）。 */
final class CharacterCommand {

    private CharacterCommand() {}

    static void register(LiteralCommandNode<CommandSourceStack> rp) {
        LiteralArgumentBuilder<CommandSourceStack> base = Commands.literal("character")
                .executes(ctx -> RpCommand.usageHint(ctx.getSource(), "ccnr_rp.command.usage.character"));

        base.then(Commands.literal("list").executes(ctx -> {
            if (ctx.getSource().getEntity() instanceof ServerPlayer p) {
                CharacterService.onRequestList(p);
                return 1;
            }
            return 0;
        }));

        base.then(Commands.literal("create")
                .then(Commands.argument("name", StringArgumentType.string())
                        .then(Commands.argument("faction", StringArgumentType.word())
                                .then(Commands.argument("profession", StringArgumentType.word())
                                        .then(Commands.argument("background", StringArgumentType.greedyString())
                                                .executes(ctx -> {
                                                    if (ctx.getSource().getEntity() instanceof ServerPlayer p) {
                                                        CharacterService.onCreate(
                                                                p,
                                                                StringArgumentType.getString(ctx, "name"),
                                                                StringArgumentType.getString(ctx, "faction"),
                                                                StringArgumentType.getString(ctx, "profession"),
                                                                StringArgumentType.getString(ctx, "background"));
                                                        return 1;
                                                    }
                                                    return 0;
                                                })
                                                .executes(ctx -> {
                                                    if (ctx.getSource().getEntity() instanceof ServerPlayer p) {
                                                        CharacterService.onCreate(
                                                                p,
                                                                StringArgumentType.getString(ctx, "name"),
                                                                StringArgumentType.getString(ctx, "faction"),
                                                                StringArgumentType.getString(ctx, "profession"),
                                                                "");
                                                        return 1;
                                                    }
                                                    return 0;
                                                }))))));

        base.then(Commands.literal("select")
                .then(Commands.argument("id", StringArgumentType.word()).executes(ctx -> {
                    if (ctx.getSource().getEntity() instanceof ServerPlayer p) {
                        CharacterService.onSelect(p, StringArgumentType.getString(ctx, "id"));
                        return 1;
                    }
                    return 0;
                })));

        base.then(Commands.literal("delete")
                .then(Commands.argument("id", StringArgumentType.word()).executes(ctx -> {
                    if (ctx.getSource().getEntity() instanceof ServerPlayer p) {
                        CharacterService.onDelete(p, StringArgumentType.getString(ctx, "id"));
                        return 1;
                    }
                    return 0;
                })));

        base.then(Commands.literal("observe")
                .then(Commands.argument("id", StringArgumentType.word()).executes(ctx -> {
                    if (ctx.getSource().getEntity() instanceof ServerPlayer p) {
                        CharacterService.onObserve(p, StringArgumentType.getString(ctx, "id"));
                        return 1;
                    }
                    return 0;
                })));

        base.then(Commands.literal("activate")
                .then(Commands.argument("id", StringArgumentType.word()).executes(ctx -> {
                    if (ctx.getSource().getEntity() instanceof ServerPlayer p) {
                        CharacterService.onActivate(p, StringArgumentType.getString(ctx, "id"));
                        return 1;
                    }
                    return 0;
                })));

        base.then(Commands.literal("cooldown")
                .then(Commands.argument("id", StringArgumentType.word()).executes(ctx -> {
                    if (ctx.getSource().getEntity() instanceof ServerPlayer p) {
                        return info(ctx.getSource(), StringArgumentType.getString(ctx, "id"));
                    }
                    return 0;
                })));

        rp.addChild(base.build());
    }

    private static int info(CommandSourceStack source, String id) {
        if (com.ccnrcom.rp.CCNRRPMod.characters == null) {
            return 0;
        }
        Optional<CharacterData> c = CCNRRPMod.characters.store().find(id);
        if (c.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.character.error.not_found", id), false);
            return 0;
        }
        CharacterData d = c.get();
        String status =
                switch (d.status()) {
                    case ALIVE -> "alive";
                    case DEAD -> "dead";
                    case OBSERVING -> "observing";
                };
        long remain = (d.cooldownUntil() - System.currentTimeMillis()) / 60000L;
        source.sendSuccess(
                () -> Component.translatable(
                        "ccnr_rp.character.info",
                        d.name(),
                        d.factionId(),
                        d.professionId(),
                        status,
                        remain < 0 ? 0 : remain),
                false);
        return 1;
    }
}
