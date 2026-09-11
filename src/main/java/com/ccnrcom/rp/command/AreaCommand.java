/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.command;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.area.Area;
import com.ccnrcom.rp.area.AreaRegistry;
import com.ccnrcom.rp.util.Permissions;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;

/**
 * /rp area 区域（拓展设定：目标区）命令，权限 {@code ccnnrp.admin.area}。
 *
 * <p>区域是本 mod 对外发布的**数据接口**：核弹等外部功能不属于本 mod，它们按 id 读取区域定义
 * （{@code CCNRRPMod.areas} / areas.json）；本命令只是同一份数据的命令行入口（与面板共用 AreaService）。
 */
final class AreaCommand {

    private AreaCommand() {}

    static void register(LiteralCommandNode<CommandSourceStack> rp) {
        LiteralArgumentBuilder<CommandSourceStack> base = Commands.literal("area")
                .requires(RpCommand.admin(Permissions.ADMIN_AREA))
                .executes(ctx -> RpCommand.usageHint(ctx.getSource(), "ccnr_rp.command.usage.area"));
        base.then(Commands.literal("list").executes(ctx -> list(ctx.getSource())));
        base.then(Commands.literal("info")
                .then(Commands.argument("id", StringArgumentType.string())
                        .executes(ctx -> info(ctx.getSource(), StringArgumentType.getString(ctx, "id")))));
        base.then(Commands.literal("at").then(atX()));
        base.then(Commands.literal("add").then(addId()));
        base.then(Commands.literal("remove")
                .then(Commands.argument("id", StringArgumentType.string())
                        .executes(ctx -> remove(ctx.getSource(), StringArgumentType.getString(ctx, "id")))));
        rp.addChild(base.build());
    }

    /** at <x> <y> <z> [dim]：点查询（省略维度时用执行者所在维度）。 */
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Double> atX() {
        var atZ = Commands.argument("z", DoubleArgumentType.doubleArg())
                .executes(ctx -> at(
                        ctx.getSource(),
                        dbl(ctx, "x"),
                        dbl(ctx, "y"),
                        dbl(ctx, "z"),
                        ctx.getSource().getLevel().dimension().location().toString()))
                .then(Commands.argument("dim", ResourceLocationArgument.id())
                        .executes(ctx -> at(
                                ctx.getSource(),
                                dbl(ctx, "x"),
                                dbl(ctx, "y"),
                                dbl(ctx, "z"),
                                ResourceLocationArgument.getId(ctx, "dim").toString())));
        var atY = Commands.argument("y", DoubleArgumentType.doubleArg()).then(atZ);
        return Commands.argument("x", DoubleArgumentType.doubleArg()).then(atY);
    }

    /** add <id> <dim> <x1> <y1> <z1> <x2> <y2> <z2>。 */
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> addId() {
        var z2 = Commands.argument("z2", DoubleArgumentType.doubleArg())
                .executes(ctx -> add(
                        ctx.getSource(),
                        StringArgumentType.getString(ctx, "id"),
                        ResourceLocationArgument.getId(ctx, "dim").toString(),
                        dbl(ctx, "x1"),
                        dbl(ctx, "y1"),
                        dbl(ctx, "z1"),
                        dbl(ctx, "x2"),
                        dbl(ctx, "y2"),
                        dbl(ctx, "z2"),
                        ""));
        var y2 = Commands.argument("y2", DoubleArgumentType.doubleArg()).then(z2);
        var x2 = Commands.argument("x2", DoubleArgumentType.doubleArg()).then(y2);
        var z1 = Commands.argument("z1", DoubleArgumentType.doubleArg()).then(x2);
        var y1 = Commands.argument("y1", DoubleArgumentType.doubleArg()).then(z1);
        var x1 = Commands.argument("x1", DoubleArgumentType.doubleArg()).then(y1);
        var dim = Commands.argument("dim", ResourceLocationArgument.id()).then(x1);
        return Commands.argument("id", StringArgumentType.string()).then(dim);
    }

    private static double dbl(CommandContext<CommandSourceStack> ctx, String name) {
        return DoubleArgumentType.getDouble(ctx, name);
    }

    private static int list(CommandSourceStack source) {
        if (CCNRRPMod.areas == null) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.error.invalid_argument", "区域服务未就绪"), false);
            return 0;
        }
        List<Area> areas = CCNRRPMod.areas.areas();
        source.sendSuccess(() -> Component.translatable("ccnr_rp.command.area.list_header", areas.size()), false);
        for (Area a : areas) {
            source.sendSuccess(
                    () -> Component.translatable(
                            "ccnr_rp.command.area.entry", a.id(), a.name(), a.dim(), a.boundsText()),
                    false);
        }
        return areas.size();
    }

    private static int info(CommandSourceStack source, String id) {
        if (CCNRRPMod.areas == null) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.error.invalid_argument", "区域服务未就绪"), false);
            return 0;
        }
        Area a = CCNRRPMod.areas.find(id).orElse(null);
        if (a == null) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.command.area.not_found", id), false);
            return 0;
        }
        source.sendSuccess(
                () -> Component.translatable("ccnr_rp.command.area.info", a.id(), a.name(), a.dim(), a.boundsText()),
                false);
        return 1;
    }

    private static int at(CommandSourceStack source, double x, double y, double z, String dim) {
        if (CCNRRPMod.areas == null) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.error.invalid_argument", "区域服务未就绪"), false);
            return 0;
        }
        Area hit = CCNRRPMod.areas.at(dim, x, y, z).orElse(null);
        if (hit == null) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.command.area.none", dim), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("ccnr_rp.command.area.hit", hit.id(), hit.name()), false);
        return 1;
    }

    private static int add(
            CommandSourceStack source,
            String id,
            String dim,
            double x1,
            double y1,
            double z1,
            double x2,
            double y2,
            double z2,
            String name) {
        if (CCNRRPMod.areas == null) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.error.invalid_argument", "区域服务未就绪"), false);
            return 0;
        }
        if (!AreaRegistry.validId(id)) {
            source.sendSuccess(
                    () -> Component.translatable("ccnr_rp.error.invalid_argument", "区域 id 非法: " + id), false);
            return 0;
        }
        Area area = new Area(id, name.isBlank() ? id : name, dim, x1, y1, z1, x2, y2, z2);
        List<String> errors = CCNRRPMod.areas.upsert(area);
        if (!errors.isEmpty()) {
            source.sendSuccess(
                    () -> Component.translatable("ccnr_rp.error.invalid_argument", String.join("; ", errors)), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("ccnr_rp.command.area.saved", id, area.boundsText()), false);
        return 1;
    }

    private static int remove(CommandSourceStack source, String id) {
        if (CCNRRPMod.areas == null) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.error.invalid_argument", "区域服务未就绪"), false);
            return 0;
        }
        List<String> errors = CCNRRPMod.areas.delete(id);
        if (!errors.isEmpty()) {
            source.sendSuccess(
                    () -> Component.translatable("ccnr_rp.error.invalid_argument", String.join("; ", errors)), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("ccnr_rp.command.area.removed", id), false);
        return 1;
    }
}
