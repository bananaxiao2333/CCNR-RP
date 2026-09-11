/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.command;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.attribute.AttributeProfile;
import com.ccnrcom.rp.attribute.AttributeService;
import com.ccnrcom.rp.util.Permissions;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

/**
 * /rp attribute 阵营属性命令（玩家属性编辑器，权限 {@code ccnnrp.admin.attribute}）。
 *
 * <p>属性 id 用注册名：原版与 mod 属性同构；mod 没装时该条执行期跳过（提示后不阻断保存）。
 * 写入即对在线成员生效（与面板保存同一条路径：FactionManager 单字段接管 + 立即重套）。
 */
final class AttributeCommand {

    private AttributeCommand() {}

    static void register(LiteralCommandNode<CommandSourceStack> rp) {
        LiteralArgumentBuilder<CommandSourceStack> base = Commands.literal("attribute")
                .requires(RpCommand.admin(Permissions.ADMIN_ATTRIBUTE))
                .executes(ctx -> RpCommand.usageHint(ctx.getSource(), "ccnr_rp.command.usage.attribute"));
        base.then(Commands.literal("list").executes(ctx -> list(ctx.getSource(), "")));
        base.then(Commands.literal("get")
                .then(Commands.argument("faction", StringArgumentType.word())
                        .suggests(RpSuggest.factions())
                        .executes(ctx -> get(ctx.getSource(), StringArgumentType.getString(ctx, "faction")))));
        base.then(Commands.literal("set")
                .then(Commands.argument("faction", StringArgumentType.word())
                        .suggests(RpSuggest.factions())
                        .then(Commands.argument("attribute", StringArgumentType.word())
                                .suggests(AttributeCommand::suggestAttributes)
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg())
                                        .executes(ctx -> set(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "faction"),
                                                StringArgumentType.getString(ctx, "attribute"),
                                                DoubleArgumentType.getDouble(ctx, "amount"),
                                                "add"))
                                        .then(Commands.argument("operation", StringArgumentType.word())
                                                .suggests(AttributeCommand::suggestOperations)
                                                .executes(ctx -> set(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "faction"),
                                                        StringArgumentType.getString(ctx, "attribute"),
                                                        DoubleArgumentType.getDouble(ctx, "amount"),
                                                        StringArgumentType.getString(ctx, "operation"))))))));
        base.then(Commands.literal("remove")
                .then(Commands.argument("faction", StringArgumentType.word())
                        .suggests(RpSuggest.factions())
                        .then(Commands.argument("attribute", StringArgumentType.word())
                                .suggests(AttributeCommand::suggestAttributes)
                                .executes(ctx -> remove(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "faction"),
                                        StringArgumentType.getString(ctx, "attribute"))))));
        base.then(Commands.literal("clear")
                .then(Commands.argument("faction", StringArgumentType.word())
                        .suggests(RpSuggest.factions())
                        .executes(ctx ->
                                write(ctx.getSource(), StringArgumentType.getString(ctx, "faction"), List.of()))));
        rp.addChild(base.build());
    }

    /** 列出已注册属性 id（原版 + 各 mod）；带可选过滤串。 */
    private static int list(CommandSourceStack source, String filter) {
        List<String> ids = AttributeService.registeredIds();
        String f = filter == null ? "" : filter.trim().toLowerCase(java.util.Locale.ROOT);
        List<String> shown = new ArrayList<>();
        for (String id : ids) {
            if (f.isEmpty() || id.toLowerCase(java.util.Locale.ROOT).contains(f)) {
                shown.add(id);
            }
        }
        source.sendSuccess(() -> Component.translatable("ccnr_rp.command.attribute.list_header", shown.size()), false);
        for (String id : shown) {
            source.sendSuccess(() -> Component.literal(" - " + id), false);
        }
        return shown.size();
    }

    private static int get(CommandSourceStack source, String factionId) {
        if (CCNRRPMod.factions == null) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.error.invalid_argument", "配置管理器未就绪"), false);
            return 0;
        }
        List<AttributeProfile.Entry> entries = AttributeService.entriesFor(factionId);
        if (!CCNRRPMod.factions.graph().factions().containsKey(factionId)) {
            source.sendSuccess(
                    () -> Component.translatable("ccnr_rp.command.attribute.unknown_faction", factionId), false);
            return 0;
        }
        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.command.attribute.empty", factionId), false);
            return 1;
        }
        source.sendSuccess(
                () -> Component.translatable("ccnr_rp.command.attribute.list_header", entries.size()), false);
        for (AttributeProfile.Entry e : entries) {
            source.sendSuccess(
                    () -> Component.translatable(
                            "ccnr_rp.command.attribute.entry",
                            e.id(),
                            e.amount(),
                            e.operation().id()),
                    false);
        }
        return 1;
    }

    /** 单条属性 upsert（同 id 且同运算的旧条目被替换）。 */
    private static int set(
            CommandSourceStack source, String factionId, String attributeId, double amount, String opName) {
        AttributeProfile.Operation op = AttributeProfile.Operation.parse(opName);
        if (op == null) {
            source.sendSuccess(
                    () -> Component.translatable(
                            "ccnr_rp.error.invalid_argument", "operation 需为 add/multiply_base/multiply_total"),
                    false);
            return 0;
        }
        List<AttributeProfile.Entry> entries = new ArrayList<>(AttributeService.entriesFor(factionId));
        entries.removeIf(e -> e.id().equals(attributeId) && e.operation() == op);
        entries.add(new AttributeProfile.Entry(attributeId, amount, op));
        return write(source, factionId, entries);
    }

    private static int remove(CommandSourceStack source, String factionId, String attributeId) {
        List<AttributeProfile.Entry> entries = new ArrayList<>(AttributeService.entriesFor(factionId));
        boolean removed = entries.removeIf(e -> e.id().equals(attributeId));
        if (!removed) {
            source.sendSuccess(
                    () -> Component.translatable("ccnr_rp.command.attribute.entry_missing", attributeId, factionId),
                    false);
            return 0;
        }
        return write(source, factionId, entries);
    }

    /** 统一写入：单字段接管 + 在线成员立即生效 + 未注册属性提示（不阻断保存）。 */
    private static int write(CommandSourceStack source, String factionId, List<AttributeProfile.Entry> entries) {
        if (CCNRRPMod.factions == null) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.error.invalid_argument", "配置管理器未就绪"), false);
            return 0;
        }
        List<String> errors = CCNRRPMod.factions.setFactionAttributes(factionId, AttributeProfile.toJson(entries));
        if (!errors.isEmpty()) {
            source.sendSuccess(
                    () -> Component.translatable("ccnr_rp.error.invalid_argument", String.join("; ", errors)), false);
            return 0;
        }
        int online = AttributeService.applyToOnlineMembers(factionId);
        List<String> unknown = AttributeService.unknownIds(entries);
        source.sendSuccess(
                () -> Component.translatable("ccnr_rp.command.attribute.saved", factionId, entries.size(), online),
                false);
        if (!unknown.isEmpty()) {
            source.sendSuccess(
                    () -> Component.translatable("ccnr_rp.command.attribute.unregistered", String.join(", ", unknown)),
                    false);
        }
        return 1;
    }

    private static CompletableFuture<Suggestions> suggestAttributes(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(AttributeService.registeredIds(), builder);
    }

    private static CompletableFuture<Suggestions> suggestOperations(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(List.of("add", "multiply_base", "multiply_total"), builder);
    }
}
