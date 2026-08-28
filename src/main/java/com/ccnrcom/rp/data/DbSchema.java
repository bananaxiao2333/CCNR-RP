/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.data;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 数据库模式（DDL）：CREATE TABLE IF NOT EXISTS + schema_version 追踪。
 * 所有表格统一用跨库类型：TEXT / INTEGER / BLOB（布尔存 INTEGER 0/1），SQLite 与 MySQL 一致。
 * 幂等：重复 bootstrap 不产生副作用；schema_version 记录当前版本（v1 初始 DDL）。
 */
public final class DbSchema {
    private static final Logger LOGGER = LogManager.getLogger();

    /** 表定义：表名 + 列定义（"name TYPE"）+ 主键列。 */
    private record TableDef(String name, String[] cols, String[] pk) {}

    private static final TableDef[] TABLES = {
        new TableDef("schema_version", new String[] {"version INTEGER"}, new String[] {"version"}),
        new TableDef("meta", new String[] {"key TEXT", "value TEXT"}, new String[] {"key"}),
        new TableDef(
                "config_profiles",
                new String[] {"id TEXT", "name TEXT", "active INTEGER", "created_at INTEGER"},
                new String[] {"id"}),
        new TableDef(
                "config_documents",
                new String[] {"profile TEXT", "config_key TEXT", "json TEXT", "updated_at INTEGER"},
                new String[] {"profile", "config_key"}),
        new TableDef(
                "users",
                new String[] {
                    "uuid TEXT",
                    "xp INTEGER",
                    "last_create_at INTEGER",
                    "any_support_revive INTEGER",
                    "status TEXT",
                    "profession_id TEXT",
                    "faction_id TEXT",
                    "cooldown_until INTEGER",
                    "duty_seconds INTEGER"
                },
                new String[] {"uuid"}),
        new TableDef(
                "user_pending_xp",
                new String[] {"uuid TEXT", "rule_id TEXT", "title TEXT", "value INTEGER", "seq INTEGER"},
                new String[] {"uuid", "rule_id"}),
        new TableDef(
                "pending_notices",
                new String[] {"uuid TEXT", "msg_key TEXT", "args TEXT", "seq INTEGER"},
                new String[] {"uuid", "seq"}),
        new TableDef("team_waves_done", new String[] {"team_id TEXT"}, new String[] {"team_id"}),
        new TableDef(
                "server_settings",
                new String[] {"profile TEXT", "key TEXT", "value TEXT", "type TEXT"},
                new String[] {"profile", "key"}),
        new TableDef(
                "assets",
                new String[] {"name TEXT", "kind TEXT", "data BLOB", "size INTEGER", "sha256 TEXT", "updated_at INTEGER"
                },
                new String[] {"name"}),
    };

    private DbSchema() {}

    /** 建表（幂等）并记录 schema_version。返回 false 表示失败（连接不可用/DDL 执行失败）。 */
    public static boolean bootstrap(Connection conn, SqlDialect dialect) {
        for (TableDef td : TABLES) {
            String ddl = createTable(dialect, td);
            try (Statement st = conn.createStatement()) {
                st.execute(ddl);
            } catch (SQLException e) {
                LOGGER.error("[CCNR-RP] 建表失败: {} —— {}", td.name(), e.toString());
                return false;
            }
        }
        // 记录 schema 版本
        try (Statement st = conn.createStatement()) {
            st.execute("INSERT INTO " + dialect.quote("schema_version") + " (version) VALUES (1)");
        } catch (SQLException e) {
            // 主键冲突=已存在版本行，忽略（幂等）
            LOGGER.debug("[CCNR-RP] schema_version 已记录（幂等）");
        }
        return true;
    }

    private static String createTable(SqlDialect d, TableDef td) {
        StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ");
        sb.append(d.quote(td.name())).append(" (");
        for (int i = 0; i < td.cols().length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            String[] parts = td.cols()[i].split(" ", 2);
            sb.append(d.quote(parts[0])).append(' ').append(parts[1]);
        }
        if (td.pk().length > 0) {
            sb.append(", PRIMARY KEY (");
            for (int i = 0; i < td.pk().length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(d.quote(td.pk()[i]));
            }
            sb.append(")");
        }
        sb.append(")");
        return sb.toString();
    }
}
