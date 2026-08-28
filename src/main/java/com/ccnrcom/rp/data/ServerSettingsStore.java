/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.data;

import com.ccnrcom.rp.CCNRRPMod;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** serverconfig 调参入库（P3）：server_settings(profile,key,value,type)。数据库启用时读写库，否则空。 */
public final class ServerSettingsStore {

    private static final Logger LOGGER = LogManager.getLogger();

    private ServerSettingsStore() {}

    private static Database db() {
        return CCNRRPMod.database;
    }

    public static boolean enabled() {
        Database d = db();
        return d != null && d.enabled();
    }

    /** 读取当前配置档全部调参（key→value）。 */
    public static Map<String, String> loadAll() {
        Database d = db();
        if (d == null || !d.enabled()) {
            return Map.of();
        }
        try {
            return d.read(c -> {
                Map<String, String> out = new LinkedHashMap<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT "
                        + d.dialect().quote("key") + ", " + d.dialect().quote("value") + " FROM "
                        + d.dialect().quote("server_settings") + " WHERE "
                        + d.dialect().quote("profile") + "=?")) {
                    ps.setString(1, ConfigStore.activeProfile());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.put(rs.getString(1), rs.getString(2));
                        }
                    }
                }
                return out;
            });
        } catch (Exception e) {
            LOGGER.error("[CCNR-RP] server_settings 读取失败: {}", e.toString());
            return Map.of();
        }
    }

    /** 保存单个调参（按配置档 upsert）。 */
    public static boolean save(String key, String value, String type) {
        Database d = db();
        if (d == null || !d.enabled()) {
            return false;
        }
        try {
            return d.write(c -> {
                String sql = d.dialect()
                        .upsert(
                                "server_settings",
                                List.of("profile", "key", "value", "type"),
                                List.of("profile", "key"));
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, ConfigStore.activeProfile());
                    ps.setString(2, key);
                    ps.setString(3, value);
                    ps.setString(4, type == null ? "number" : type);
                    ps.executeUpdate();
                }
            });
        } catch (Exception e) {
            LOGGER.error("[CCNR-RP] server_settings 保存失败: {}", e.toString());
            return false;
        }
    }
}
