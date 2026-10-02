/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.command;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.sync.DataLink;
import com.ccnrcom.rp.util.Permissions;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * /rp data：数据服务（CCNR-Data）链路状态与**当局事件流**（管理命令）。
 *
 * <p>为什么要有它：等级显示改成「数据服务确认后才显示」之后，管理员必须能回答
 * 「现在到底连上没有 / 我的等级为什么不见了 / 有多少上报还压在队列里」，否则这个设计不可运维。
 * 事件流也放在这里 —— 它同时也是「面板可见」在服务端的取数口，客户端面板与 CCNR-PM 都读同一份
 * （{@link DataLink#recentEvents()}），不另做第二份拷贝。
 *
 * <p>权限复用 {@code ccnrrp.admin.db}：本命令与 /rp db 同类（后端基础设施的诊断口），
 * 为它单独开一个权限节点只会让权限配置多一项却没有新的语义。
 */
final class DataCommand {

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private DataCommand() {}

    static void register(LiteralCommandNode<CommandSourceStack> rp) {
        rp.addChild(Commands.literal("data")
                .requires(RpCommand.admin(Permissions.ADMIN_DB))
                .executes(ctx -> status(ctx.getSource()))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("events")
                        .executes(ctx -> events(ctx.getSource(), 20))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 200))
                                .executes(
                                        ctx -> events(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "count")))))
                .then(Commands.literal("flush").executes(ctx -> flush(ctx.getSource())))
                .build());
    }

    private static int status(CommandSourceStack source) {
        DataLink data = CCNRRPMod.data;
        if (data == null) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.data.status.absent"), false);
            return 0;
        }
        JsonObject s = data.statusPayload();
        if (!s.get("enabled").getAsBoolean()) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.data.status.disabled"), false);
            return 1;
        }
        source.sendSuccess(
                () -> Component.translatable(
                        "ccnr_rp.data.status.line",
                        s.get("state").getAsString(),
                        s.get("target").getAsString(),
                        s.get("serverId").getAsString()),
                false);
        source.sendSuccess(
                () -> Component.translatable(
                        "ccnr_rp.data.status.traffic",
                        s.get("sentOk").getAsLong(),
                        s.get("sentFail").getAsLong(),
                        s.get("pending").getAsLong(),
                        s.get("dropped").getAsLong()),
                false);
        String err = s.get("lastError").getAsString();
        if (!err.isBlank()) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.data.status.last_error", err), false);
        }
        source.sendSuccess(
                () -> Component.translatable(
                        "ccnr_rp.data.status.profiles",
                        s.getAsJsonObject("profiles").get("known").getAsInt(),
                        s.getAsJsonObject("profiles").get("failed").getAsInt()),
                false);
        return 1;
    }

    private static int events(CommandSourceStack source, int count) {
        DataLink data = CCNRRPMod.data;
        if (data == null) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.data.status.absent"), false);
            return 0;
        }
        List<JsonObject> all = data.recentEvents();
        if (all.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.data.events.empty"), false);
            return 1;
        }
        int from = Math.max(0, all.size() - count);
        source.sendSuccess(() -> Component.translatable("ccnr_rp.data.events.header", all.size()), false);
        for (int i = from; i < all.size(); i++) {
            JsonObject e = all.get(i);
            String clock = CLOCK.format(Instant.ofEpochMilli(e.get("at").getAsLong()));
            source.sendSuccess(
                    () -> Component.translatable(
                            "ccnr_rp.data.events.line",
                            clock,
                            shortKind(e.get("kind").getAsString()),
                            e.get("text").getAsString()),
                    false);
        }
        return 1;
    }

    private static int flush(CommandSourceStack source) {
        DataLink data = CCNRRPMod.data;
        if (data == null) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.data.status.absent"), false);
            return 0;
        }
        // 只投递一条（FIFO，见 DataOutbox 的说明）；这里不等待结果，进度用 /rp data status 看
        data.tick();
        source.sendSuccess(() -> Component.translatable("ccnr_rp.data.flush.sent"), false);
        return 1;
    }

    /** 事件种类 → 短标签（聊天栏里 {@code EVENT_KIND_CHARACTER_KILL} 太长，会把正文挤没）。 */
    private static String shortKind(String kind) {
        return kind.startsWith("EVENT_KIND_") ? kind.substring("EVENT_KIND_".length()) : kind;
    }
}
