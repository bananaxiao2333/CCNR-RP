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
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;

/**
 * /rp attribute 玩家属性命令（玩家属性编辑器，权限 {@code ccnnrp.admin.attribute}）。
 *
 * <p>属性 id 用注册名：原版与 mod 属性同构；mod 没装时该条执行期跳过（提示后不阻断保存）。
 * 写入即对在线成员生效（与面板保存同一条路径：FactionManager 单字段接管 + 立即重套）。
 *
 * <p><b>两层目标</b>（docs/16 §2.4）：
 * <ul>
 *   <li><b>阵营层</b>：{@code /rp attribute set <阵营> …}（原有语法，保持不变）；</li>
 *   <li><b>角色/职业层</b>：{@code /rp attribute prof set <职业> …} —— **同 id 整体覆盖**阵营层
 *       （含该 id 的全部运算条目）。</li>
 * </ul>
 */
final class AttributeCommand {

    private AttributeCommand() {}

    /** 属性配置的承载对象：阵营层 / 职业（角色）层。两层结构一致，只是读写入口与"在线重套"范围不同。 */
    private enum Target {
        FACTION(
                "faction",
                "ccnr_rp.command.attribute.unknown_faction",
                "ccnr_rp.command.attribute.empty",
                "ccnr_rp.command.attribute.saved"),
        PROFESSION(
                "profession",
                "ccnr_rp.command.attribute.unknown_profession",
                "ccnr_rp.command.attribute.empty_profession",
                "ccnr_rp.command.attribute.saved_profession");

        private final String argName;
        private final String unknownKey;
        private final String emptyKey;
        private final String savedKey;

        Target(String argName, String unknownKey, String emptyKey, String savedKey) {
            this.argName = argName;
            this.unknownKey = unknownKey;
            this.emptyKey = emptyKey;
            this.savedKey = savedKey;
        }

        String savedKey() {
            return savedKey;
        }
    }

    static void register(LiteralCommandNode<CommandSourceStack> rp) {
        LiteralArgumentBuilder<CommandSourceStack> base = Commands.literal("attribute")
                .requires(RpCommand.admin(Permissions.ADMIN_ATTRIBUTE))
                .executes(ctx -> RpCommand.usageHint(ctx.getSource(), "ccnr_rp.command.usage.attribute"));
        base.then(Commands.literal("list").executes(ctx -> list(ctx.getSource(), "")));
        mountTarget(base, Target.FACTION, RpSuggest.factions());
        // 职业（角色）层：/rp attribute prof get|set|remove|clear <职业>
        LiteralArgumentBuilder<CommandSourceStack> prof = Commands.literal("prof")
                .executes(ctx -> RpCommand.usageHint(ctx.getSource(), "ccnr_rp.command.usage.attribute"));
        mountTarget(prof, Target.PROFESSION, RpSuggest.professions());
        base.then(prof);
        rp.addChild(base.build());
    }

    /** 挂载一套 get/set/remove/clear 到给定字面量下（阵营层与职业层共用同一实现）。 */
    private static void mountTarget(
            LiteralArgumentBuilder<CommandSourceStack> owner,
            Target target,
            com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> targetSuggest) {
        String arg = target.argName;
        owner.then(Commands.literal("get")
                .then(Commands.argument(arg, StringArgumentType.word())
                        .suggests(targetSuggest)
                        .executes(ctx -> get(ctx.getSource(), target, StringArgumentType.getString(ctx, arg)))));
        owner.then(Commands.literal("set")
                .then(Commands.argument(arg, StringArgumentType.word())
                        .suggests(targetSuggest)
                        // 属性 id 是注册名（含冒号），必须用 ResourceLocation 参数：word() 只吃 [a-zA-Z0-9_]
                        .then(Commands.argument("attribute", ResourceLocationArgument.id())
                                .suggests(AttributeCommand::suggestAttributes)
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg())
                                        .executes(ctx -> set(
                                                ctx.getSource(),
                                                target,
                                                StringArgumentType.getString(ctx, arg),
                                                attributeId(ctx),
                                                DoubleArgumentType.getDouble(ctx, "amount"),
                                                "add"))
                                        .then(Commands.argument("operation", StringArgumentType.word())
                                                .suggests(AttributeCommand::suggestOperations)
                                                .executes(ctx -> set(
                                                        ctx.getSource(),
                                                        target,
                                                        StringArgumentType.getString(ctx, arg),
                                                        attributeId(ctx),
                                                        DoubleArgumentType.getDouble(ctx, "amount"),
                                                        StringArgumentType.getString(ctx, "operation"))))))));
        owner.then(Commands.literal("remove")
                .then(Commands.argument(arg, StringArgumentType.word())
                        .suggests(targetSuggest)
                        .then(Commands.argument("attribute", ResourceLocationArgument.id())
                                .suggests(AttributeCommand::suggestAttributes)
                                .executes(ctx -> remove(
                                        ctx.getSource(),
                                        target,
                                        StringArgumentType.getString(ctx, arg),
                                        attributeId(ctx))))));
        owner.then(Commands.literal("clear")
                .then(Commands.argument(arg, StringArgumentType.word())
                        .suggests(targetSuggest)
                        .executes(ctx ->
                                write(ctx.getSource(), target, StringArgumentType.getString(ctx, arg), List.of()))));
    }

    /** 读取属性参数（ResourceLocation 参数：允许 namespace:path，省略命名空间默认 minecraft）。 */
    private static String attributeId(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        return ResourceLocationArgument.getId(ctx, "attribute").toString();
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

    /** 目标是否存在（阵营在关系图里 / 职业能 find 到）。 */
    private static boolean exists(Target target, String id) {
        if (CCNRRPMod.factions == null) {
            return false;
        }
        return target == Target.FACTION
                ? CCNRRPMod.factions.graph().factions().containsKey(id)
                : CCNRRPMod.factions.findProfession(id).isPresent();
    }

    private static int get(CommandSourceStack source, Target target, String id) {
        if (CCNRRPMod.factions == null) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.error.invalid_argument", "配置管理器未就绪"), false);
            return 0;
        }
        if (!exists(target, id)) {
            source.sendSuccess(() -> Component.translatable(target.unknownKey, id), false);
            return 0;
        }
        List<AttributeProfile.Entry> entries = AttributeProfile.parse(
                        target == Target.FACTION
                                ? CCNRRPMod.factions.factionAttributes(id)
                                : CCNRRPMod.factions.professionAttributes(id))
                .entries();
        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(target.emptyKey, id), false);
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
        // 职业层额外提示：哪些 id 正在覆盖阵营层（与面板上的「覆盖」标记同源，都是 layer() 的交集）
        if (target == Target.PROFESSION) {
            reportOverrides(source, id, entries);
        }
        return 1;
    }

    /** 打印该职业覆盖掉的阵营属性 id（判定键 = 属性 id，与面板「覆盖」标记同源，都是 layer() 的交集）。 */
    private static void reportOverrides(
            CommandSourceStack source, String professionId, List<AttributeProfile.Entry> profEntries) {
        if (CCNRRPMod.factions == null) {
            return;
        }
        String factionId = CCNRRPMod.factions
                .findProfession(professionId)
                .map(def -> com.ccnrcom.rp.faction.FactionProfessions.factionId(def))
                .orElse("");
        AttributeProfile.Layered layered = AttributeProfile.layer(AttributeService.entriesFor(factionId), profEntries);
        if (layered.overriddenIds().isEmpty()) {
            return;
        }
        source.sendSuccess(
                () -> Component.translatable(
                        "ccnr_rp.command.attribute.prof_overrides", String.join(", ", layered.overriddenIds())),
                false);
    }

    /** 单条属性 upsert（同 id 且同运算的旧条目被替换）。 */
    private static int set(
            CommandSourceStack source, Target target, String id, String attributeId, double amount, String opName) {
        AttributeProfile.Operation op = AttributeProfile.Operation.parse(opName);
        if (op == null) {
            source.sendSuccess(
                    () -> Component.translatable(
                            "ccnr_rp.error.invalid_argument", "operation 需为 add/multiply_base/multiply_total"),
                    false);
            return 0;
        }
        List<AttributeProfile.Entry> entries = new ArrayList<>(AttributeProfile.parse(
                        target == Target.FACTION
                                ? CCNRRPMod.factions.factionAttributes(id)
                                : CCNRRPMod.factions.professionAttributes(id))
                .entries());
        entries.removeIf(e -> e.id().equals(attributeId) && e.operation() == op);
        entries.add(new AttributeProfile.Entry(attributeId, amount, op));
        return write(source, target, id, entries);
    }

    private static int remove(CommandSourceStack source, Target target, String id, String attributeId) {
        List<AttributeProfile.Entry> entries = new ArrayList<>(AttributeProfile.parse(
                        target == Target.FACTION
                                ? CCNRRPMod.factions.factionAttributes(id)
                                : CCNRRPMod.factions.professionAttributes(id))
                .entries());
        boolean removed = entries.removeIf(e -> e.id().equals(attributeId));
        if (!removed) {
            source.sendSuccess(
                    () -> Component.translatable("ccnr_rp.command.attribute.entry_missing", attributeId, id), false);
            return 0;
        }
        return write(source, target, id, entries);
    }

    /** 统一写入：单字段接管 + 在线成员立即生效 + 未注册属性提示（不阻断保存）。 */
    private static int write(
            CommandSourceStack source, Target target, String id, List<AttributeProfile.Entry> entries) {
        if (CCNRRPMod.factions == null) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.error.invalid_argument", "配置管理器未就绪"), false);
            return 0;
        }
        com.google.gson.JsonArray json = AttributeProfile.toJson(entries);
        List<String> errors = target == Target.FACTION
                ? CCNRRPMod.factions.setFactionAttributes(id, json)
                : CCNRRPMod.factions.setProfessionAttributes(id, json);
        if (!errors.isEmpty()) {
            source.sendSuccess(
                    () -> Component.translatable("ccnr_rp.error.invalid_argument", String.join("; ", errors)), false);
            return 0;
        }
        int online = target == Target.FACTION
                ? AttributeService.applyToOnlineMembers(id)
                : AttributeService.applyToOnlineProfessionMembers(id);
        List<String> unknown = AttributeService.unknownIds(entries);
        source.sendSuccess(() -> Component.translatable(target.savedKey(), id, entries.size(), online), false);
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
