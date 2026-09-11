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
        LiteralArgumentBuilder<CommandSourceStack> base = Commands.literal("faction")
                .executes(ctx -> RpCommand.usageHint(ctx.getSource(), "ccnr_rp.command.usage.faction"));
        base.then(Commands.literal("list").executes(ctx -> list(ctx.getSource())));
        // 关系测定图：仅管理员；服务端下发打开指令，客户端全屏展示阵营徽章+连线（可拖动/缩放）
        base.then(Commands.literal("graph")
                .requires(RpCommand.admin(Permissions.ADMIN_FACTION))
                .executes(ctx -> openGraph(ctx.getSource())));

        base.then(Commands.literal("relation")
                .executes(ctx -> RpCommand.usageHint(ctx.getSource(), "ccnr_rp.command.usage.faction"))
                .then(Commands.argument("a", StringArgumentType.word())
                        .suggests(RpSuggest.factions())
                        .then(Commands.argument("b", StringArgumentType.word())
                                .suggests(RpSuggest.factions())
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
                        .executes(ctx -> RpCommand.usageHint(ctx.getSource(), "ccnr_rp.command.usage.faction"))
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
                                        .suggests(RpSuggest.factions())
                                        .then(Commands.argument("g2", StringArgumentType.word())
                                                .suggests(RpSuggest.factions())
                                                .then(Commands.argument("type", StringArgumentType.word())
                                                        .executes(ctx -> setRelation(
                                                                ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "g1"),
                                                                StringArgumentType.getString(ctx, "g2"),
                                                                StringArgumentType.getString(ctx, "type"))))))))
                .then(Commands.literal("warhead")
                        .requires(RpCommand.admin(Permissions.ADMIN_FACTION))
                        .then(Commands.argument("faction", StringArgumentType.word())
                                .suggests(RpSuggest.factions())
                                .then(Commands.argument(
                                                "enabled", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                                        .executes(ctx -> setWarhead(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "faction"),
                                                com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "enabled"),
                                                ""))
                                        .then(Commands.argument("area", StringArgumentType.string())
                                                .executes(ctx -> setWarhead(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "faction"),
                                                        com.mojang.brigadier.arguments.BoolArgumentType.getBool(
                                                                ctx, "enabled"),
                                                        StringArgumentType.getString(ctx, "area")))))));

        rp.addChild(base.build());
    }

    /**
     * /rp faction warhead &lt;阵营&gt; &lt;true|false&gt; [区域id]：设置"可否启动弹头"与目标区域。
     * 核弹本体不属本 mod（见 docs/16）：这里只写许可与目标，消费方是外部功能。
     */
    private static int setWarhead(CommandSourceStack source, String factionId, boolean enabled, String areaId) {
        FactionManager mgr = manager();
        if (mgr == null) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.error.not_implemented", "P1"), false);
            return 0;
        }
        List<String> errors = mgr.setFactionWarhead(factionId, enabled, areaId);
        if (!errors.isEmpty()) {
            source.sendSuccess(
                    () -> Component.translatable("ccnr_rp.error.invalid_argument", String.join("; ", errors)), false);
            return 0;
        }
        source.sendSuccess(
                () -> Component.translatable(
                        "ccnr_rp.command.faction.warhead",
                        factionId,
                        enabled ? "true" : "false",
                        areaId.isBlank() ? "-" : areaId),
                false);
        return 1;
    }

    private static FactionManager manager() {
        return CCNRRPMod.factions;
    }

    /** /rp faction graph：管理员打开关系测定图（全屏）。 */
    private static int openGraph(CommandSourceStack source) {
        if (source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            com.ccnrcom.rp.network.RpChannels.sendTo(
                    player, new com.ccnrcom.rp.network.RpPackets.FactionGraphOpenS2C());
            return 1;
        }
        source.sendSuccess(() -> Component.translatable("ccnr_rp.faction.error.console"), false);
        return 0;
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
        List<String> members = java.util.Arrays.stream(membersArg.split("\\s+"))
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
