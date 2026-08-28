/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.data;

import com.ccnrcom.rp.CCNRRPMod;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** 素材（音乐/图标）BLOB 仓储（P4）：assets(name,kind,data,size,sha256,updated_at)。 */
public final class AssetRepository {

    private static final Logger LOGGER = LogManager.getLogger();

    /** 素材元数据（不含 data，避免加载 BLOB）。 */
    public record AssetMeta(String name, String kind, long size, String sha256) {}

    private AssetRepository() {}

    private static Database db() {
        return CCNRRPMod.database;
    }

    private static boolean enabled() {
        Database d = db();
        return d != null && d.enabled();
    }

    /** 全部素材元数据。 */
    public static List<AssetMeta> list() {
        Database d = db();
        if (d == null || !d.enabled()) {
            return List.of();
        }
        try {
            return d.read(c -> {
                List<AssetMeta> out = new ArrayList<>();
                try (ResultSet rs = c.createStatement()
                        .executeQuery("SELECT " + d.dialect().quote("name") + ", "
                                + d.dialect().quote("kind") + ", " + d.dialect().quote("size") + ", "
                                + d.dialect().quote("sha256") + " FROM "
                                + d.dialect().quote("assets"))) {
                    while (rs.next()) {
                        out.add(new AssetMeta(
                                rs.getString("name"),
                                rs.getString("kind"),
                                rs.getLong("size"),
                                rs.getString("sha256")));
                    }
                }
                return out;
            });
        } catch (Exception e) {
            LOGGER.error("[CCNR-RP] assets 列表读取失败: {}", e.toString());
            return List.of();
        }
    }

    /** 读取素材字节。 */
    public static byte[] read(String name) {
        Database d = db();
        if (d == null || !d.enabled()) {
            return null;
        }
        try {
            return d.read(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT "
                        + d.dialect().quote("data") + " FROM " + d.dialect().quote("assets") + " WHERE "
                        + d.dialect().quote("name") + "=?")) {
                    ps.setString(1, name);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getBytes(1) : null;
                    }
                }
            });
        } catch (Exception e) {
            LOGGER.error("[CCNR-RP] assets 读取失败: {}", e.toString());
            return null;
        }
    }

    /** 保存素材（upsert）；返回 false=失败。 */
    public static boolean save(String name, String kind, byte[] data, String sha256) {
        Database d = db();
        if (d == null || !d.enabled()) {
            return false;
        }
        try {
            return d.write(c -> {
                String sql = d.dialect()
                        .upsert(
                                "assets",
                                List.of("name", "kind", "data", "size", "sha256", "updated_at"),
                                List.of("name"));
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, name);
                    ps.setString(2, kind);
                    ps.setBytes(3, data);
                    ps.setInt(4, data == null ? 0 : data.length);
                    ps.setString(5, sha256 == null ? "" : sha256);
                    ps.setLong(6, System.currentTimeMillis());
                    ps.executeUpdate();
                }
            });
        } catch (Exception e) {
            LOGGER.error("[CCNR-RP] assets 保存失败: {}", e.toString());
            return false;
        }
    }

    /** 删除素材；返回 false=失败。 */
    public static boolean delete(String name) {
        Database d = db();
        if (d == null || !d.enabled()) {
            return false;
        }
        try {
            return d.write(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM "
                        + d.dialect().quote("assets") + " WHERE " + d.dialect().quote("name") + "=?")) {
                    ps.setString(1, name);
                    ps.executeUpdate();
                }
            });
        } catch (Exception e) {
            LOGGER.error("[CCNR-RP] assets 删除失败: {}", e.toString());
            return false;
        }
    }

    public static boolean enabled0() {
        return enabled();
    }
}
