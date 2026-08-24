/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.command;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.faction.FactionGraph;
import com.ccnrcom.rp.faction.FactionManager;
import com.ccnrcom.rp.faction.RelationType;
import com.ccnrcom.rp.util.Permissions;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** /rp faction 阵营关系命令（P1）。 */
final class FactionCommand {

    private FactionCommand() {}

    static void register(LiteralCommandNode<CommandSourceStack> rp) {
        LiteralArgumentBuilder<CommandSourceStack> base = Commands.literal("faction");
        base.then(Commands.literal("list").executes(ctx -> list(ctx.getSource())));

        base.then(Commands.literal("relation")
                .then(Commands.argument("a", StringArgumentType.word())
                        .then(Commands.argument("b", StringArgumentType.word())
                                .executes(ctx -> getRelation(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "a"),
                                        StringArgumentType.getString(ctx, "b")))
                                .then(Commands.literal("set")
                                        .requires(RpCommand.admin(Permissions.ADMIN_FACTION))
                                        .then(Commands.argument("type", StringArgumentType.word())
                                                .executes(ctx -> setRelation(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "a"),
                                                        StringArgumentType.getString(ctx, "b"),
                                                        StringArgumentType.getString(ctx, "type"))))))));

        base.then(Commands.literal("group")
                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                .then(Commands.literal("create")
                        .requires(RpCommand.admin(Permissions.ADMIN_FACTION))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("members", StringArgumentType.greedyString())
                                        .executes(ctx -> createGroup(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "id"),
                                                StringArgumentType.getString(ctx, "members"))))))
                .then(Commands.literal("relation")
                        .requires(RpCommand.admin(Permissions.ADMIN_FACTION))
                        .then(Commands.argument("g1", StringArgumentType.word())
                                .then(Commands.argument("g2", StringArgumentType.word())
                                        .then(Commands.argument("type", StringArgumentType.word())
                                                .executes(ctx -> setRelation(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "g1"),
                                                        StringArgumentType.getString(ctx, "g2"),
                                                        StringArgumentType.getString(ctx, "type"))))))));

        rp.addChild(base.build());
    }

    private static FactionManager manager() {
        return CCNRRPMod.factions;
    }

    private static int list(CommandSourceStack source) {
        FactionManager mgr = manager();
        if (mgr == null) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.error.not_implemented", "P1"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("ccnr_rp.faction.list.header"), false);
        FactionGraph g = mgr.graph();
        g.factions()
                .values()
                .forEach(f -> source.sendSuccess(
                        () -> Component.translatable("ccnr_rp.faction.list.faction", f.id(), f.name(), f.color()),
                        false));
        g.groups()
                .values()
                .forEach(gr -> source.sendSuccess(
                        () -> Component.translatable(
                                "ccnr_rp.faction.list.group", gr.id(), String.join(",", gr.memberIds())),
                        false));
        return 1;
    }

    private static int getRelation(CommandSourceStack source, String a, String b) {
        FactionManager mgr = manager();
        if (mgr == null) {
            return 0;
        }
        try {
            RelationType r = mgr.graph().resolve(a, b);
            source.sendSuccess(
                    () -> Component.translatable(
                            "ccnr_rp.faction.relation.result", a, b, r.name().toLowerCase(java.util.Locale.ROOT)),
                    false);
            return 1;
        } catch (IllegalArgumentException e) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.faction.error.not_found", e.getMessage()), false);
            return 0;
        }
    }

    private static int setRelation(CommandSourceStack source, String a, String b, String type) {
        FactionManager mgr = manager();
        if (mgr == null) {
            return 0;
        }
        RelationType t = RelationType.parse(type);
        if (t == null) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.faction.error.invalid_type"), false);
            return 0;
        }
        List<String> errors = mgr.setRelation(a, b, t);
        if (!errors.isEmpty()) {
            source.sendSuccess(
                    () -> Component.translatable("ccnr_rp.faction.error.config", String.join("; ", errors)), false);
            return 0;
        }
        source.sendSuccess(
                () -> Component.translatable(
                        "ccnr_rp.faction.relation.set", a, b, t.name().toLowerCase(java.util.Locale.ROOT)),
                false);
        return 1;
    }

    private static int createGroup(CommandSourceStack source, String id, String membersArg) {
        FactionManager mgr = manager();
        if (mgr == null) {
            return 0;
        }
        List<String> members = java.util.Arrays.stream(membersArg.split("\s+"))
                .filter(s -> !s.isEmpty())
                .toList();
        List<String> errors = mgr.createGroup(id, members);
        if (!errors.isEmpty()) {
            source.sendSuccess(
                    () -> Component.translatable("ccnr_rp.faction.error.config", String.join("; ", errors)), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("ccnr_rp.faction.group.created", id, membersArg), false);
        return 1;
    }
}
