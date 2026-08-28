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
        int assets = migrateAssets();
        // serverconfig 调参：把当前（toml 种子）值写入 server_settings（幂等）
        com.google.gson.JsonObject sc = com.ccnrcom.rp.config.CCNRRPConfig.values();
        for (String k : com.ccnrcom.rp.config.CCNRRPConfig.keys()) {
            if (sc.has(k)) {
                String typ = (k.equals("enabled") || k.equals("friendlyNotice")) ? "bool" : "number";
                com.ccnrcom.rp.data.ServerSettingsStore.save(k, sc.get(k).getAsString(), typ);
            }
        }
        final int configs = cfg;
        final int runtimeCount = runtime;
        ConfigReloader.reloadAll();
        broadcast(source);
        source.sendSuccess(() -> Component.translatable("ccnr_rp.db.migrate.done", configs, runtimeCount), false);
        return 1;
    }

    /** 迁移 config/ccnr_rp/audio/*.ogg 与 textures/*.png 到 assets 表。 */
    private static int migrateAssets() {
        int n = 0;
        java.nio.file.Path audio = com.ccnrcom.rp.assets.AssetLibrary.audioDir();
        java.nio.file.Path textures = com.ccnrcom.rp.assets.AssetLibrary.texturesDir();
        n += importDir(audio, "music");
        n += importDir(textures, "icon");
        return n;
    }

    private static int importDir(java.nio.file.Path dir, String kind) {
        int n = 0;
        if (dir == null || !java.nio.file.Files.isDirectory(dir)) {
            return 0;
        }
        try (var s = java.nio.file.Files.list(dir)) {
            for (java.nio.file.Path p : s.toList()) {
                if (!java.nio.file.Files.isRegularFile(p)) {
                    continue;
                }
                String name = p.getFileName().toString();
                if ("music".equals(kind) && !name.endsWith(".ogg")) {
                    continue;
                }
                if ("icon".equals(kind) && !name.endsWith(".png")) {
                    continue;
                }
                byte[] data = java.nio.file.Files.readAllBytes(p);
                String sha = sha256(data);
                com.ccnrcom.rp.data.AssetRepository.save(name, kind, data, sha);
                n++;
            }
        } catch (Exception e) {
            // 跳过
        }
        return n;
    }

    private static String sha256(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(data)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
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
