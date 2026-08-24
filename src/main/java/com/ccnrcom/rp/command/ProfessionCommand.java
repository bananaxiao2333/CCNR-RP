/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.command;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.faction.FactionManager;
import com.ccnrcom.rp.faction.FactionProfessions;
import com.ccnrcom.rp.profession.LoadoutManager;
import com.ccnrcom.rp.util.Permissions;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** /rp profession 人物刷新配置命令（P2）。 */
final class ProfessionCommand {

    private ProfessionCommand() {}

    static void register(LiteralCommandNode<CommandSourceStack> rp) {
        LiteralArgumentBuilder<CommandSourceStack> base = Commands.literal("profession")
                .executes(ctx -> RpCommand.usageHint(ctx.getSource(), "ccnr_rp.command.usage.profession"));

        base.then(Commands.literal("list").executes(ctx -> list(ctx.getSource())));

        base.then(Commands.literal("create")
                .executes(ctx -> RpCommand.usageHint(ctx.getSource(), "ccnr_rp.command.usage.profession"))
                .requires(RpCommand.admin(Permissions.ADMIN_PROFESSION))
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("factionId", StringArgumentType.word())
                                .then(Commands.argument("selfDeploy", BoolArgumentType.bool())
                                        .executes(ctx -> create(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "id"),
                                                StringArgumentType.getString(ctx, "factionId"),
                                                BoolArgumentType.getBool(ctx, "selfDeploy"),
                                                StringArgumentType.getString(ctx, "id")))
                                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                                .executes(ctx -> create(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "id"),
                                                        StringArgumentType.getString(ctx, "factionId"),
                                                        BoolArgumentType.getBool(ctx, "selfDeploy"),
                                                        StringArgumentType.getString(ctx, "name"))))))));

        base.then(Commands.literal("save")
                .executes(ctx -> RpCommand.usageHint(ctx.getSource(), "ccnr_rp.command.usage.profession"))
                .requires(RpCommand.admin(Permissions.ADMIN_PROFESSION))
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> save(ctx.getSource(), StringArgumentType.getString(ctx, "id"), false))
                        .then(Commands.literal("--hotbar")
                                .executes(ctx -> save(ctx.getSource(), StringArgumentType.getString(ctx, "id"), false)))
                        .then(Commands.literal("--full")
                                .executes(
                                        ctx -> save(ctx.getSource(), StringArgumentType.getString(ctx, "id"), true)))));

        base.then(Commands.literal("load")
                .executes(ctx -> RpCommand.usageHint(ctx.getSource(), "ccnr_rp.command.usage.profession"))
                .requires(RpCommand.admin(Permissions.ADMIN_PROFESSION))
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> load(ctx.getSource(), StringArgumentType.getString(ctx, "id")))));

        rp.addChild(base.build());
    }

    private static int list(CommandSourceStack source) {
        FactionManager mgr = CCNRRPMod.factions;
        if (mgr == null) {
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("ccnr_rp.profession.list.header"), false);
        List<String> ids = mgr.professionIds();
        for (String id : ids) {
            JsonObject def = mgr.findProfession(id).orElse(null);
            if (def == null) {
                continue;
            }
            source.sendSuccess(
                    () -> Component.translatable(
                            "ccnr_rp.profession.list.item",
                            id,
                            FactionProfessions.factionId(def),
                            FactionProfessions.selfDeploy(def)),
                    false);
        }
        return 1;
    }

    private static int create(CommandSourceStack source, String id, String factionId, boolean selfDeploy, String name) {
        FactionManager mgr = CCNRRPMod.factions;
        if (mgr == null) {
            return 0;
        }
        String displayName = (name == null || name.isBlank()) ? id : name;
        List<String> errors = mgr.upsertProfession(id, displayName, factionId, selfDeploy, null);
        if (!errors.isEmpty()) {
            source.sendSuccess(
                    () -> Component.translatable("ccnr_rp.profession.error.config", String.join("; ", errors)), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("ccnr_rp.profession.created", id, factionId), false);
        return 1;
    }

    private static int save(CommandSourceStack source, String id, boolean full) {
        FactionManager mgr = CCNRRPMod.factions;
        if (mgr == null || !(source.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }
        JsonObject def = mgr.findProfession(id).orElse(null);
        if (def == null) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.profession.error.not_found", id), false);
            return 0;
        }
        JsonObject loadout = LoadoutManager.capture(player, full);
        List<String> errors = mgr.upsertProfession(
                id,
                FactionProfessions.idsSafeName(def),
                FactionProfessions.factionId(def),
                FactionProfessions.selfDeploy(def),
                loadout);
        if (!errors.isEmpty()) {
            source.sendSuccess(
                    () -> Component.translatable("ccnr_rp.profession.error.config", String.join("; ", errors)), false);
            return 0;
        }
        int slots = loadout.getAsJsonArray("inventory").size()
                + loadout.getAsJsonArray("armor").size()
                + (loadout.has("offhand")
                                && loadout.get("offhand").isJsonObject()
                                && loadout.getAsJsonObject("offhand").size() > 0
                        ? 1
                        : 0);
        source.sendSuccess(
                () -> Component.translatable("ccnr_rp.profession.saved", id, slots, full ? "full" : "hotbar"), false);
        return 1;
    }

    private static int load(CommandSourceStack source, String id) {
        FactionManager mgr = CCNRRPMod.factions;
        if (mgr == null || !(source.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }
        JsonObject def = mgr.findProfession(id).orElse(null);
        if (def == null) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.profession.error.not_found", id), false);
            return 0;
        }
        LoadoutManager.apply(player, FactionProfessions.loadout(def));
        source.sendSuccess(() -> Component.translatable("ccnr_rp.profession.loaded", id), false);
        return 1;
    }
}
