/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.command;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.character.CharacterService;
import com.ccnrcom.rp.data.AssetRepository;
import com.ccnrcom.rp.data.ConfigReloader;
import com.ccnrcom.rp.data.ConfigStore;
import com.ccnrcom.rp.data.Database;
import com.ccnrcom.rp.data.ServerSettingsStore;
import com.ccnrcom.rp.data.UserRepository;
import com.ccnrcom.rp.experience.XpChangeList.XpChange;
import com.ccnrcom.rp.user.UserService.UserProfile;
import com.ccnrcom.rp.util.ConfigCrud;
import com.ccnrcom.rp.util.JsonUtil;
import com.ccnrcom.rp.util.Permissions;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;

/** /rp db：数据库后端状态、连通性、配置档切换、备份导出与本地迁移（管理命令）。 */
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
                .then(Commands.literal("flush").executes(ctx -> flushWrite(ctx.getSource())))
                .then(Commands.literal("migrate").executes(ctx -> migrate(ctx.getSource())))
                .then(Commands.literal("export")
                        .then(Commands.argument("dir", StringArgumentType.string())
                                .executes(
                                        ctx -> exportData(ctx.getSource(), StringArgumentType.getString(ctx, "dir")))))
                .then(Commands.literal("connect")
                        .then(Commands.literal("sqlite")
                                .then(Commands.argument("file", StringArgumentType.string())
                                        .executes(ctx -> connectSqlite(
                                                ctx.getSource(), StringArgumentType.getString(ctx, "file")))))
                        .then(Commands.literal("mysql")
                                .then(Commands.argument("spec", StringArgumentType.string())
                                        .executes(ctx -> connectMysql(
                                                ctx.getSource(), StringArgumentType.getString(ctx, "spec"))))))
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

    /** 强制刷新写后置（用户档案/挂起通知等落库）。 */
    private static int flushWrite(CommandSourceStack source) {
        Database db = CCNRRPMod.database;
        if (db == null || !db.enabled()) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.db.test.disabled"), false);
            return 1;
        }
        if (CCNRRPMod.users != null) {
            CCNRRPMod.users.save();
        }
        db.flush();
        source.sendSuccess(() -> Component.translatable("ccnr_rp.db.flush.ok"), false);
        return 1;
    }

    /** 本地 → 库（幂等）：配置 JSON + 运行时数据 + 调参 + 素材。 */
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
        Path worldDir = source.getServer().getWorldPath(new net.minecraft.world.level.storage.LevelResource("ccnr_rp"));
        int runtime = com.ccnrcom.rp.data.RuntimeMigrator.migrate(db, worldDir);
        migrateAssets();
        JsonObject sc = com.ccnrcom.rp.config.CCNRRPConfig.values();
        for (String k : com.ccnrcom.rp.config.CCNRRPConfig.keys()) {
            if (sc.has(k)) {
                String typ = (k.equals("enabled") || k.equals("friendlyNotice")) ? "bool" : "number";
                ServerSettingsStore.save(k, sc.get(k).getAsString(), typ);
            }
        }
        final int configs = cfg;
        final int runtimeCount = runtime;
        ConfigReloader.reloadAll();
        broadcast(source);
        source.sendSuccess(() -> Component.translatable("ccnr_rp.db.migrate.done", configs, runtimeCount), false);
        return 1;
    }

    /** 库 → 本地（备份）：配置文档 + 调参 + 用户档案 + 素材到 <dir>。 */
    private static int exportData(CommandSourceStack source, String dir) {
        Database db = CCNRRPMod.database;
        if (db == null || !db.enabled()) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.db.test.disabled"), false);
            return 1;
        }
        try {
            Path base = Path.of(dir);
            Path cfgDir = base.resolve("config");
            Path worldDir = base.resolve("world");
            Path audioDir = base.resolve("audio");
            Path texDir = base.resolve("textures");
            Files.createDirectories(cfgDir);
            Files.createDirectories(worldDir);
            Files.createDirectories(audioDir);
            Files.createDirectories(texDir);
            for (String key : CONFIG_KEYS) {
                ConfigStore.load(key).ifPresent(root -> JsonUtil.atomicWrite(cfgDir.resolve(key), root));
            }
            JsonObject sc = new JsonObject();
            ServerSettingsStore.loadAll().forEach(sc::addProperty);
            Files.writeString(
                    cfgDir.resolve("server_settings.json"), JsonUtil.GSON.toJson(sc) + System.lineSeparator());
            // 用户档案
            Map<String, UserProfile> users = new UserRepository(db).loadAll();
            JsonObject root = new JsonObject();
            root.addProperty("version", 3);
            JsonObject us = new JsonObject();
            users.forEach((uuid, p) -> {
                JsonObject o = new JsonObject();
                o.addProperty("xp", p.xp());
                o.addProperty("lastCreateAt", p.lastCreateAt());
                o.addProperty("anySupportRevive", p.anySupportRevive());
                o.addProperty("status", p.status().name().toLowerCase(java.util.Locale.ROOT));
                if (p.professionId() != null && !p.professionId().isBlank()) {
                    o.addProperty("professionId", p.professionId());
                }
                if (p.factionId() != null && !p.factionId().isBlank()) {
                    o.addProperty("factionId", p.factionId());
                }
                o.addProperty("cooldownUntil", p.cooldownUntil());
                o.addProperty("dutySeconds", p.dutySeconds());
                JsonArray pend = new JsonArray();
                for (XpChange x : p.pendingXp()) {
                    JsonObject xo = new JsonObject();
                    xo.addProperty("ruleId", x.ruleId());
                    xo.addProperty("title", x.title() == null ? "" : x.title());
                    xo.addProperty("value", x.value());
                    pend.add(xo);
                }
                o.add("pendingXp", pend);
                us.add(uuid, o);
            });
            root.add("users", us);
            JsonUtil.atomicWrite(worldDir.resolve("user_profiles.json"), root);
            // 素材
            for (AssetRepository.AssetMeta m : AssetRepository.list()) {
                byte[] data = AssetRepository.read(m.name());
                if (data == null) {
                    continue;
                }
                if ("music".equals(m.kind())) {
                    Files.write(audioDir.resolve(m.name()), data);
                } else {
                    Files.write(texDir.resolve(m.name()), data);
                }
            }
            source.sendSuccess(() -> Component.translatable("ccnr_rp.db.export.done", dir), false);
        } catch (Exception e) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.db.profile.error"), false);
        }
        return 1;
    }

    private static int connectSqlite(CommandSourceStack source, String file) {
        return writeDbProps(source, "db.enabled=true\ndb.mode=sqlite\ndb.file=" + file + "\n");
    }

    /** connect mysql <host,port,db,user,pass>：逗号分隔 spec。 */
    private static int connectMysql(CommandSourceStack source, String spec) {
        String[] parts = spec.split(",");
        if (parts.length != 5) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.db.profile.error"), false);
            return 1;
        }
        return writeDbProps(
                source,
                "db.enabled=true\ndb.mode=mysql\ndb.host=" + parts[0].trim() + "\ndb.port=" + parts[1].trim()
                        + "\ndb.database=" + parts[2].trim() + "\ndb.user=" + parts[3].trim() + "\ndb.pass="
                        + parts[4].trim()
                        + "\n");
    }

    private static int writeDbProps(CommandSourceStack source, String content) {
        try {
            Files.writeString(FMLPaths.CONFIGDIR.get().resolve("db.properties"), content);
            source.sendSuccess(() -> Component.translatable("ccnr_rp.db.connect.ok"), false);
        } catch (Exception e) {
            source.sendSuccess(() -> Component.translatable("ccnr_rp.db.profile.error"), false);
        }
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

    /** 迁移 config/ccnr_rp/audio/*.ogg 与 textures/*.png 到 assets 表。 */
    private static void migrateAssets() {
        importDir(com.ccnrcom.rp.assets.AssetLibrary.audioDir(), "music");
        importDir(com.ccnrcom.rp.assets.AssetLibrary.texturesDir(), "icon");
    }

    private static void importDir(Path dir, String kind) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (var s = Files.list(dir)) {
            for (Path p : s.toList()) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                String name = p.getFileName().toString();
                if ("music".equals(kind) && !name.endsWith(".ogg")) {
                    continue;
                }
                if ("icon".equals(kind) && !name.endsWith(".png")) {
                    continue;
                }
                byte[] data = Files.readAllBytes(p);
                AssetRepository.save(name, kind, data, sha256(data));
            }
        } catch (Exception ignored) {
            // 跳过
        }
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
}
