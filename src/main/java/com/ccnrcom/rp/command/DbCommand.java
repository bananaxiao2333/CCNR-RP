/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.command;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.character.CharacterService;
import com.ccnrcom.rp.data.ConfigReloader;
import com.ccnrcom.rp.data.ConfigStore;
import com.ccnrcom.rp.data.Database;
import com.ccnrcom.rp.util.ConfigCrud;
import com.ccnrcom.rp.util.JsonUtil;
import com.ccnrcom.rp.util.Permissions;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** /rp db：数据库后端状态、连通性、配置档切换与本地迁移（管理命令）。 */
final class DbCommand {

    private static final List<String> CONFIG_KEYS = List.of(
            "factions.json",
            "settings.json",
            "phases.json",
            "events.json",
            "spawn_waves.json",
            "sequences.json",
            "experience_rules.json",
            "limits.json",
            "animations.json");

    private DbCommand() {}

    static void register(LiteralCommandNode<CommandSourceStack> rp) {
        rp.addChild(Commands.literal("db")
                .requires(RpCommand.admin(Permissions.ADMIN_DB))
                .executes(ctx -> status(ctx.getSource()))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("test").executes(ctx -> test(ctx.getSource())))
                .then(Commands.literal("migrate").executes(ctx -> migrate(ctx.getSource())))
                .then(Commands.literal("profile")
                        .executes(ctx -> profileList(ctx.getSource()))
                        .then(Commands.literal("list").executes(ctx -> profileList(ctx.getSource())))
                        .then(Commands.literal("create")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(ctx -> profileCreate(
                                                ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
                        .then(Commands.literal("select")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(ctx -> profileSelect(
                                                ctx.getSource(), StringArgumentType.getString(ctx, "id"))))))
                .build());
    }

    private static int status(CommandSourceStack source) {
        Database db = CCNRRPMod.database;
        if (db == null || !db.enabled()) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.db.status.disabled"), false);
            return 1;
        }
        source.sendSuccess(
                () -> Component.translatable(
                        "ccnr_rp.db.status.enabled",
                        db.config().mode().name(),
                        ConfigStore.activeProfile(),
                        db.config().maskedPass()),
                false);
        return 1;
    }

    private static int test(CommandSourceStack source) {
        Database db = CCNRRPMod.database;
        if (db == null || !db.enabled()) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.db.test.disabled"), false);
            return 1;
        }
        boolean ok;
        try {
            ok = db.read(c -> {
                try (Statement st = c.createStatement()) {
                    st.execute("SELECT 1");
                } catch (SQLException e) {
                    return false;
                }
                return true;
            });
        } catch (Exception e) {
            ok = false;
        }
        final boolean testOk = ok;
        source.sendSuccess(() -> Component.translatable(testOk ? "ccnr_rp.db.test.ok" : "ccnr_rp.db.test.fail"), false);
        return 1;
    }

    /** 本地配置 JSON → 配置档文档（幂等）：迁移 config/ccnr_rp/*.json 到当前配置档。 */
    private static int migrate(CommandSourceStack source) {
        Database db = CCNRRPMod.database;
        if (db == null || !db.enabled()) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.db.test.disabled"), false);
            return 1;
        }
        int cfg = 0;
        for (String key : CONFIG_KEYS) {
            JsonObject disk = JsonUtil.readObject(ConfigCrud.file(key)).orElse(null);
            if (disk != null) {
                ConfigStore.save(key, disk);
                cfg++;
            }
        }
        java.nio.file.Path worldDir =
                source.getServer().getWorldPath(new net.minecraft.world.level.storage.LevelResource("ccnr_rp"));
        int runtime = com.ccnrcom.rp.data.RuntimeMigrator.migrate(db, worldDir);
        final int configs = cfg;
        final int runtimeCount = runtime;
        ConfigReloader.reloadAll();
        broadcast(source);
        source.sendSuccess(() -> Component.translatable("ccnr_rp.db.migrate.done", configs, runtimeCount), false);
        return 1;
    }

    private static int profileList(CommandSourceStack source) {
        Database db = CCNRRPMod.database;
        if (db == null || !db.enabled()) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.db.test.disabled"), false);
            return 1;
        }
        String active = ConfigStore.activeProfile();
        List<String> profs = ConfigStore.listProfiles();
        StringBuilder sb = new StringBuilder();
        for (String p : profs) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            if (p.equals(active)) {
                sb.append("*").append(p);
            } else {
                sb.append(p);
            }
        }
        source.sendSuccess(() -> Component.translatable("ccnr_rp.db.profile.list", active, sb.toString()), false);
        return 1;
    }

    private static int profileCreate(CommandSourceStack source, String id) {
        Database db = CCNRRPMod.database;
        if (db == null || !db.enabled()) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.db.test.disabled"), false);
            return 1;
        }
        if (ConfigStore.createProfile(id, id)) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.db.profile.created", id), false);
        } else {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.db.profile.error"), false);
        }
        return 1;
    }

    private static int profileSelect(CommandSourceStack source, String id) {
        Database db = CCNRRPMod.database;
        if (db == null || !db.enabled()) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.db.test.disabled"), false);
            return 1;
        }
        if (!ConfigStore.selectProfile(id)) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.db.profile.error"), false);
            return 1;
        }
        ConfigReloader.reloadAll();
        broadcast(source);
        source.sendSuccess(() -> Component.translatable("ccnr_rp.db.profile.selected", id), false);
        return 1;
    }

    private static void broadcast(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer editor) {
            CharacterService.broadcastConfigAll(editor);
        }
    }
}
