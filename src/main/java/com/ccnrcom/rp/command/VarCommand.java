/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.command;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.util.Permissions;
import com.ccnrcom.rp.variable.Variable;
import com.ccnrcom.rp.variable.VariableService;
import com.ccnrcom.rp.variable.VariableType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * /rp var 自定义设定命令（权限 {@code ccnrrp.admin.var}）：变量与预设方案的命令行 CRUD。
 *
 * <p>与「自定义设定」管理面板共用 {@link VariableService} 的同一条写入路径（全量校验 + 落盘 + 换缓存），
 * 所以命令与 GUI 不会各自维护一份状态。
 *
 * <p><b>{@code /rp var get <id>} 只打印裸值</b>（不带任何前缀/翻译/装饰），便于命令方块与 RCON 直接取值；
 * 找不到变量时不打印任何内容并返回 0。需要看类型/说明/预设时用 {@code /rp var info <id>}。
 */
final class VarCommand {

    private VarCommand() {}

    static void register(LiteralCommandNode<CommandSourceStack> rp) {
        LiteralArgumentBuilder<CommandSourceStack> base = Commands.literal("var")
                .requires(RpCommand.admin(Permissions.ADMIN_VAR))
                .executes(ctx -> RpCommand.usageHint(ctx.getSource(), "ccnr_rp.command.usage.var"));

        base.then(Commands.literal("list").executes(ctx -> list(ctx.getSource(), "")));
        base.then(Commands.literal("get")
                .then(Commands.argument("id", StringArgumentType.word())
                        .suggests(RpSuggest.variables())
                        .executes(ctx -> get(ctx.getSource(), StringArgumentType.getString(ctx, "id")))));
        base.then(Commands.literal("info")
                .then(Commands.argument("id", StringArgumentType.word())
                        .suggests(RpSuggest.variables())
                        .executes(ctx -> info(ctx.getSource(), StringArgumentType.getString(ctx, "id")))));
        // 值用 greedyString：文本变量可以带空格（命令最后一段参数）
        base.then(Commands.literal("set")
                .then(Commands.argument("id", StringArgumentType.word())
                        .suggests(RpSuggest.variables())
                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                .executes(ctx -> set(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "id"),
                                        StringArgumentType.getString(ctx, "value"))))));
        base.then(Commands.literal("create")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests((ctx, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                        List.of("bool", "number", "text"), b))
                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                        .executes(ctx -> create(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "id"),
                                                StringArgumentType.getString(ctx, "type"),
                                                StringArgumentType.getString(ctx, "value")))))));
        base.then(Commands.literal("remove")
                .then(Commands.argument("id", StringArgumentType.word())
                        .suggests(RpSuggest.variables())
                        .executes(ctx -> remove(ctx.getSource(), StringArgumentType.getString(ctx, "id")))));
        base.then(Commands.literal("reload").executes(ctx -> reload(ctx.getSource())));

        LiteralArgumentBuilder<CommandSourceStack> preset = Commands.literal("preset")
                .executes(ctx -> RpCommand.usageHint(ctx.getSource(), "ccnr_rp.command.usage.var"));
        preset.then(Commands.literal("list")
                .then(Commands.argument("id", StringArgumentType.word())
                        .suggests(RpSuggest.variables())
                        .executes(ctx -> presetList(ctx.getSource(), StringArgumentType.getString(ctx, "id")))));
        // 点击即切换：把变量值改成该预设值
        preset.then(Commands.literal("apply")
                .then(Commands.argument("id", StringArgumentType.word())
                        .suggests(RpSuggest.variables())
                        .then(Commands.argument("preset", StringArgumentType.word())
                                .suggests(RpSuggest.presets("id"))
                                .executes(ctx -> presetApply(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "id"),
                                        StringArgumentType.getString(ctx, "preset"))))));
        preset.then(Commands.literal("set")
                .then(Commands.argument("id", StringArgumentType.word())
                        .suggests(RpSuggest.variables())
                        .then(Commands.argument("preset", StringArgumentType.word())
                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                        .executes(ctx -> presetSet(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "id"),
                                                StringArgumentType.getString(ctx, "preset"),
                                                StringArgumentType.getString(ctx, "value")))))));
        preset.then(Commands.literal("remove")
                .then(Commands.argument("id", StringArgumentType.word())
                        .suggests(RpSuggest.variables())
                        .then(Commands.argument("preset", StringArgumentType.word())
                                .suggests(RpSuggest.presets("id"))
                                .executes(ctx -> presetRemove(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "id"),
                                        StringArgumentType.getString(ctx, "preset"))))));
        base.then(preset);

        LiteralArgumentBuilder<CommandSourceStack> scheme = Commands.literal("scheme")
                .executes(ctx -> RpCommand.usageHint(ctx.getSource(), "ccnr_rp.command.usage.var"));
        scheme.then(Commands.literal("list").executes(ctx -> schemeList(ctx.getSource())));
        // 一键套用整套：未列出的变量保持原值
        scheme.then(Commands.literal("apply")
                .then(Commands.argument("id", StringArgumentType.word())
                        .suggests(RpSuggest.schemes())
                        .executes(ctx -> schemeApply(ctx.getSource(), StringArgumentType.getString(ctx, "id")))));
        // 快照：把当前所有变量的值存成一套命名方案
        scheme.then(Commands.literal("save")
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> schemeSave(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "id"),
                                        StringArgumentType.getString(ctx, "name"))))));
        scheme.then(Commands.literal("remove")
                .then(Commands.argument("id", StringArgumentType.word())
                        .suggests(RpSuggest.schemes())
                        .executes(ctx -> schemeRemove(ctx.getSource(), StringArgumentType.getString(ctx, "id")))));
        base.then(scheme);

        rp.addChild(base.build());
    }

    /** 服务就绪检查（未就绪时提示并返回 null，调用方直接返回 0）。 */
    private static VariableService service(CommandSourceStack source) {
        VariableService svc = CCNRRPMod.variables;
        if (svc == null) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.error.invalid_argument", "自定义设定服务未就绪"), false);
        }
        return svc;
    }

    private static boolean failed(CommandSourceStack source, List<String> errors) {
        if (errors.isEmpty()) {
            return false;
        }
        source.sendSuccess(
                () -> Component.translatable("ccnr_rp.error.invalid_argument", String.join("; ", errors)), false);
        return true;
    }

    private static int list(CommandSourceStack source, String filter) {
        VariableService svc = service(source);
        if (svc == null) {
            return 0;
        }
        String f = filter == null ? "" : filter.trim().toLowerCase(java.util.Locale.ROOT);
        List<Variable> shown = svc.variables().stream()
                .filter(v ->
                        f.isEmpty() || v.id().toLowerCase(java.util.Locale.ROOT).contains(f))
                .toList();
        source.sendSuccess(() -> Component.translatable("ccnr_rp.command.var.list_header", shown.size()), false);
        for (Variable v : shown) {
            source.sendSuccess(
                    () -> Component.translatable(
                            "ccnr_rp.command.var.entry",
                            v.id(),
                            v.type().label(),
                            v.value(),
                            v.presets().size()),
                    false);
        }
        return shown.size();
    }

    /** 裸值输出（命令方块/RCON 友好）：只有值本身，没有前缀。 */
    private static int get(CommandSourceStack source, String id) {
        VariableService svc = service(source);
        if (svc == null) {
            return 0;
        }
        Variable v = svc.find(id).orElse(null);
        if (v == null) {
            return 0;
        }
        source.sendSuccess(() -> Component.literal(v.value()), false);
        return 1;
    }

    private static int info(CommandSourceStack source, String id) {
        VariableService svc = service(source);
        if (svc == null) {
            return 0;
        }
        Variable v = svc.find(id).orElse(null);
        if (v == null) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.command.var.unknown", id), false);
            return 0;
        }
        source.sendSuccess(
                () -> Component.translatable(
                        "ccnr_rp.command.var.info",
                        v.id(),
                        v.displayName(),
                        v.type().label(),
                        v.value(),
                        v.desc().isBlank() ? "-" : v.desc()),
                false);
        for (Variable.Preset p : v.presets()) {
            source.sendSuccess(
                    () -> Component.translatable(
                            "ccnr_rp.command.var.preset_entry", p.id(), p.displayName(), p.value()),
                    false);
        }
        return 1;
    }

    private static int set(CommandSourceStack source, String id, String value) {
        VariableService svc = service(source);
        if (svc == null) {
            return 0;
        }
        if (failed(source, svc.setValue(id, value))) {
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("ccnr_rp.command.var.saved", id, svc.raw(id, "")), false);
        return 1;
    }

    private static int create(CommandSourceStack source, String id, String typeName, String value) {
        VariableService svc = service(source);
        if (svc == null) {
            return 0;
        }
        VariableType type = VariableType.parse(typeName);
        if (type == null) {
            source.sendSuccess(
                    () -> Component.translatable("ccnr_rp.error.invalid_argument", "类型需为 bool/number/text"), false);
            return 0;
        }
        if (svc.find(id).isPresent()) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.command.var.exists", id), false);
            return 0;
        }
        if (failed(source, svc.upsert(new Variable(id, type, id, "", value, List.of())))) {
            return 0;
        }
        source.sendSuccess(
                () -> Component.translatable("ccnr_rp.command.var.created", id, type.label(), svc.raw(id, "")), false);
        return 1;
    }

    private static int remove(CommandSourceStack source, String id) {
        VariableService svc = service(source);
        if (svc == null) {
            return 0;
        }
        if (failed(source, svc.delete(id))) {
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("ccnr_rp.command.var.removed", id), false);
        return 1;
    }

    private static int reload(CommandSourceStack source) {
        VariableService svc = service(source);
        if (svc == null) {
            return 0;
        }
        svc.reload();
        source.sendSuccess(
                () -> Component.translatable(
                        "ccnr_rp.command.var.reloaded",
                        svc.variables().size(),
                        svc.schemes().size()),
                false);
        return 1;
    }

    private static int presetList(CommandSourceStack source, String id) {
        VariableService svc = service(source);
        if (svc == null) {
            return 0;
        }
        Variable v = svc.find(id).orElse(null);
        if (v == null) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.command.var.unknown", id), false);
            return 0;
        }
        source.sendSuccess(
                () -> Component.translatable(
                        "ccnr_rp.command.var.preset_header", id, v.presets().size()),
                false);
        for (Variable.Preset p : v.presets()) {
            source.sendSuccess(
                    () -> Component.translatable(
                            "ccnr_rp.command.var.preset_entry", p.id(), p.displayName(), p.value()),
                    false);
        }
        return v.presets().size();
    }

    private static int presetApply(CommandSourceStack source, String id, String presetId) {
        VariableService svc = service(source);
        if (svc == null) {
            return 0;
        }
        if (failed(source, svc.applyPreset(id, presetId))) {
            return 0;
        }
        source.sendSuccess(
                () -> Component.translatable("ccnr_rp.command.var.preset_applied", id, presetId, svc.raw(id, "")),
                false);
        return 1;
    }

    private static int presetSet(CommandSourceStack source, String id, String presetId, String value) {
        VariableService svc = service(source);
        if (svc == null) {
            return 0;
        }
        if (failed(source, svc.upsertPreset(id, new Variable.Preset(presetId, presetId, value)))) {
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("ccnr_rp.command.var.preset_saved", id, presetId), false);
        return 1;
    }

    private static int presetRemove(CommandSourceStack source, String id, String presetId) {
        VariableService svc = service(source);
        if (svc == null) {
            return 0;
        }
        if (failed(source, svc.deletePreset(id, presetId))) {
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("ccnr_rp.command.var.preset_removed", id, presetId), false);
        return 1;
    }

    private static int schemeList(CommandSourceStack source) {
        VariableService svc = service(source);
        if (svc == null) {
            return 0;
        }
        source.sendSuccess(
                () -> Component.translatable(
                        "ccnr_rp.command.var.scheme_header", svc.schemes().size()),
                false);
        for (var s : svc.schemes()) {
            source.sendSuccess(
                    () -> Component.translatable(
                            "ccnr_rp.command.var.scheme_entry",
                            s.id(),
                            s.displayName(),
                            s.values().size()),
                    false);
        }
        return svc.schemes().size();
    }

    private static int schemeApply(CommandSourceStack source, String id) {
        VariableService svc = service(source);
        if (svc == null) {
            return 0;
        }
        if (failed(source, svc.applyScheme(id))) {
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("ccnr_rp.command.var.scheme_applied", id), false);
        return 1;
    }

    private static int schemeSave(CommandSourceStack source, String id, String name) {
        VariableService svc = service(source);
        if (svc == null) {
            return 0;
        }
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        for (Variable v : svc.variables()) {
            values.put(v.id(), v.value());
        }
        if (failed(source, svc.upsertScheme(new com.ccnrcom.rp.variable.VariableScheme(id, name, values)))) {
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("ccnr_rp.command.var.scheme_saved", id, values.size()), false);
        return 1;
    }

    private static int schemeRemove(CommandSourceStack source, String id) {
        VariableService svc = service(source);
        if (svc == null) {
            return 0;
        }
        if (failed(source, svc.deleteScheme(id))) {
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("ccnr_rp.command.var.scheme_removed", id), false);
        return 1;
    }
}
