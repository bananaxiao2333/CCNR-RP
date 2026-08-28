/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.data;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.util.JsonUtil;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 配置档存储（P1）：把 config/ccnr_rp/*.json 的「读文件 / 原子写」抽象为「读配置档文档 / upsert 配置文档」。
 * 数据库启用时源在 config_documents(profile, config_key, json)；未启用（或文档缺失）回退读取磁盘文件（迁移期兼容）。
 * 配置档：config_profiles + meta.active_profile；切换经 {#link Database} 读改写。
 */
public final class ConfigStore {

    private static final Logger LOGGER = LogManager.getLogger();
    /** 当前激活配置档（缓存：refreshActive 时更新）。 */
    private static volatile String activeProfile;

    private ConfigStore() {}

    private static Database db() {
        return CCNRRPMod.database;
    }

    private static boolean enabled() {
        Database d = db();
        return d != null && d.enabled();
    }

    private static Path file(String key) {
        try {
            return FMLPaths.CONFIGDIR.get().resolve("ccnr_rp").resolve(key);
        } catch (Throwable t) {
            return null; // 测试/无配置目录环境：FMLPaths 不可用
        }
    }

    // ---------- 配置文档读写 ----------

    /** 读取配置文档（数据库启用且存在→库；否则回退磁盘文件；缺省返回空）。 */
    public static Optional<JsonObject> load(String key) {
        if (enabled()) {
            Optional<JsonObject> fromDb = loadFromDb(key);
            if (fromDb.isPresent()) {
                return fromDb;
            }
        }
        Path f = file(key);
        return f == null ? Optional.empty() : JsonUtil.readObject(f);
    }

    private static Optional<JsonObject> loadFromDb(String key) {
        Database d = db();
        try {
            String json = d.read(c -> {
                try (PreparedStatement ps =
                        c.prepareStatement("SELECT json FROM " + d.dialect().quote("config_documents")
                                + " WHERE " + d.dialect().quote("profile") + "=? AND "
                                + d.dialect().quote("config_key")
                                + "=?")) {
                    ps.setString(1, activeProfile());
                    ps.setString(2, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getString(1) : null;
                    }
                }
            });
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(JsonUtil.GSON.fromJson(json, JsonObject.class));
        } catch (Exception e) {
            LOGGER.error("[CCNR-RP] 配置文档读取失败: {} —— {}", key, e.toString());
            return Optional.empty();
        }
    }

    /** 保存配置文档：数据库启用→upsert（profile,config_key,json）；否则原子写磁盘。 */
    public static boolean save(String key, JsonObject root) {
        if (enabled()) {
            Database d = db();
            try {
                return d.write(c -> {
                    String sql = d.dialect()
                            .upsert(
                                    "config_documents",
                                    List.of("profile", "config_key", "json", "updated_at"),
                                    List.of("profile", "config_key"));
                    try (PreparedStatement ps = c.prepareStatement(sql)) {
                        ps.setString(1, activeProfile());
                        ps.setString(2, key);
                        ps.setString(3, root == null ? "{}" : JsonUtil.GSON.toJson(root));
                        ps.setLong(4, System.currentTimeMillis());
                        ps.executeUpdate();
                    }
                });
            } catch (Exception e) {
                LOGGER.error("[CCNR-RP] 配置文档保存失败: {} —— {}", key, e.toString());
                return false;
            }
        }
        Path f = file(key);
        if (f == null) {
            return true; // FMLPaths 不可用（测试环境）：保留内存语义，跳过写盘
        }
        return JsonUtil.atomicWrite(f, root);
    }

    // ---------- 配置档 ----------

    /** 当前激活配置档：meta.active_profile 优先，否则 DbConfig.profile()，并在首次连接时播种 default。 */
    public static String activeProfile() {
        if (activeProfile == null) {
            refreshActive();
        }
        return activeProfile == null ? "default" : activeProfile;
    }

    private static void refreshActive() {
        Database d = db();
        if (d == null || !d.enabled()) {
            activeProfile = "default";
            return;
        }
        try {
            String fromMeta = d.read(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT value FROM "
                        + d.dialect().quote("meta") + " WHERE " + d.dialect().quote("key") + "=?")) {
                    ps.setString(1, "active_profile");
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getString(1) : null;
                    }
                }
            });
            if (fromMeta != null && !fromMeta.isBlank()) {
                activeProfile = fromMeta;
                return;
            }
        } catch (Exception e) {
            LOGGER.warn("[CCNR-RP] 读取 active_profile 失败，回退 DbConfig.profile(): {}", e.getMessage());
        }
        activeProfile = d.profile();
        // 首次连接：若无 meta.active_profile，则设置为默认档并将 DbConfig.profile() 落库为 meta
        try {
            d.write(c -> {
                try (PreparedStatement ps =
                        c.prepareStatement("INSERT INTO " + d.dialect().quote("meta") + " ("
                                + d.dialect().quote("key") + ", " + d.dialect().quote("value") + ") VALUES (?, ?)")) {
                    ps.setString(1, "active_profile");
                    ps.setString(2, d.profile());
                    ps.executeUpdate();
                }
            });
        } catch (Exception ignored) {
            // 主键冲突=已存在，忽略
        }
    }

    /** 设置激活配置档（写 meta.active_profile）并刷新缓存。返回 false=失败。 */
    public static boolean selectProfile(String profile) {
        Database d = db();
        if (d == null || !d.enabled()) {
            LOGGER.warn("[CCNR-RP] 数据库未启用，无法切换配置档");
            return false;
        }
        boolean ok = d.write(c -> {
            try (PreparedStatement ps =
                    c.prepareStatement("INSERT INTO " + d.dialect().quote("meta") + " ("
                            + d.dialect().quote("key") + ", " + d.dialect().quote("value")
                            + ") VALUES (?, ?) ON CONFLICT(" + d.dialect().quote("key") + ") DO UPDATE SET "
                            + d.dialect().quote("value")
                            + "=excluded." + d.dialect().quote("value"))) {
                ps.setString(1, "active_profile");
                ps.setString(2, profile);
                ps.executeUpdate();
            }
        });
        if (ok) {
            activeProfile = profile;
        }
        return ok;
    }

    /** 已注册配置档列表。 */
    public static List<String> listProfiles() {
        Database d = db();
        if (d == null || !d.enabled()) {
            return List.of();
        }
        try {
            return d.read(c -> {
                List<String> out = new ArrayList<>();
                try (var rs = c.createStatement()
                        .executeQuery("SELECT " + d.dialect().quote("id") + " FROM "
                                + d.dialect().quote("config_profiles") + " ORDER BY "
                                + d.dialect().quote("id"))) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
                return out;
            });
        } catch (Exception e) {
            LOGGER.error("[CCNR-RP] 配置档列表失败: {}", e.toString());
            return List.of();
        }
    }

    /** 注册配置档（幂等）。 */
    public static boolean createProfile(String id, String name) {
        Database d = db();
        if (d == null || !d.enabled()) {
            return false;
        }
        return d.write(c -> {
            String sql =
                    d.dialect().upsert("config_profiles", List.of("id", "name", "active", "created_at"), List.of("id"));
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, id);
                ps.setString(2, name == null || name.isBlank() ? id : name);
                ps.setInt(3, 0);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            }
        });
    }

    /** 复制档：把 from 档的所有配置文档复制到 to 档（新建配置档用）。 */
    public static boolean copyProfile(String from, String to) {
        Database d = db();
        if (d == null || !d.enabled()) {
            return false;
        }
        return d.write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO "
                    + d.dialect().quote("config_documents")
                    + " (" + d.dialect().quote("profile") + ", " + d.dialect().quote("config_key") + ", "
                    + d.dialect().quote("json")
                    + ", " + d.dialect().quote("updated_at") + ") SELECT ?, "
                    + d.dialect().quote("config_key") + ", " + d.dialect().quote("json")
                    + ", ? FROM " + d.dialect().quote("config_documents") + " WHERE "
                    + d.dialect().quote("profile") + "=?")) {
                ps.setString(1, to);
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, from);
                ps.executeUpdate();
            }
        });
    }

    /** 首次连接播种：确保激活配置档在 config_profiles 中存在（幂等）。 */
    public static void ensureProfiles() {
        Database d = db();
        if (d == null || !d.enabled()) {
            return;
        }
        String active = activeProfile();
        createProfile(active, active);
    }
}
