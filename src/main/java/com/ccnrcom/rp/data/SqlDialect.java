/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.data;

import java.util.ArrayList;
import java.util.List;

/** 数据库方言差异（SQLite vs MySQL）：占位符、标识符引用、upsert。纯逻辑，可单测。 */
public final class SqlDialect {

    private final DbType type;

    public SqlDialect(DbType type) {
        this.type = type;
    }

    public boolean isSqlite() {
        return type == DbType.SQLITE;
    }

    public DbType type() {
        return type;
    }

    /** n 个占位符："?, ?, ?"。 */
    public String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('?');
        }
        return sb.toString();
    }

    /** 引用标识符：统一用反引号（SQLite 与 MySQL 均支持）。 */
    public String quote(String ident) {
        return "`" + ident + "`";
    }

    /**
     * 生成 upsert 语句：insert + （冲突时）更新所有非主键列。
     * 调用方负责在第一个参数列表中传入全部列值（与 columns 顺序一致）。
     */
    public String upsert(String table, List<String> columns, List<String> pkCols) {
        List<String> quotedCols = new ArrayList<>();
        for (String c : columns) {
            quotedCols.add(quote(c));
        }
        String cols = String.join(", ", quotedCols);
        SqlDialect d = this;
        String values = d.placeholders(columns.size());
        String sql = "INSERT INTO " + quote(table) + " (" + cols + ") VALUES (" + values + ")";
        List<String> nonPk = new ArrayList<>();
        for (String c : columns) {
            if (!pkCols.contains(c)) {
                nonPk.add(c);
            }
        }
        if (isSqlite()) {
            List<String> pkQ = new ArrayList<>();
            for (String c : pkCols) {
                pkQ.add(quote(c));
            }
            StringBuilder set = new StringBuilder();
            for (int i = 0; i < nonPk.size(); i++) {
                if (i > 0) {
                    set.append(", ");
                }
                String c = nonPk.get(i);
                set.append(quote(c)).append(" = excluded.").append(quote(c));
            }
            sql += " ON CONFLICT(" + String.join(", ", pkQ) + ") DO UPDATE SET " + set;
        } else {
            StringBuilder set = new StringBuilder();
            for (int i = 0; i < nonPk.size(); i++) {
                if (i > 0) {
                    set.append(", ");
                }
                String c = nonPk.get(i);
                set.append(quote(c)).append(" = VALUES(").append(quote(c)).append(")");
            }
            sql += " ON DUPLICATE KEY UPDATE " + set;
        }
        return sql;
    }

    /** 生成 CREATE TABLE IF NOT EXISTS 语句。 */
    public String createTable(String table, List<String> cols, List<String> pkCols, boolean autoIncrementFirstPk) {
        List<String> defs = new ArrayList<>();
        for (int i = 0; i < cols.size(); i++) {
            String c = cols.get(i);
            if (autoIncrementFirstPk && i == 0 && pkCols.contains(c)) {
                defs.add(quote(c)
                        + (isSqlite() ? " INTEGER PRIMARY KEY AUTOINCREMENT" : " INT AUTO_INCREMENT PRIMARY KEY"));
            } else {
                defs.add(quote(c) + " " + colType(c, pkCols));
            }
        }
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS ")
                .append(quote(table))
                .append(" (");
        sql.append(String.join(", ", defs));
        if (!pkCols.isEmpty() && !(autoIncrementFirstPk && pkCols.size() == 1)) {
            List<String> pkQ = new ArrayList<>();
            for (String c : pkCols) {
                pkQ.add(quote(c));
            }
            sql.append(", PRIMARY KEY (").append(String.join(", ", pkQ)).append(")");
        }
        sql.append(")");
        return sql.toString();
    }

    /** 列类型推断（按列名/用途）；调用方可对特定键覆盖。 */
    private String colType(String col, List<String> pkCols) {
        String c = col.toLowerCase();
        if (c.contains("data") && !c.contains("date")) {
            return "BLOB";
        }
        if (c.endsWith("_at")
                || c.contains("_at")
                || c.equals("seq")
                || c.equals("x")
                || c.equals("y")
                || c.equals("z")
                || c.equals("size")
                || c.equals("limit")
                || c.equals("count")
                || c.equals("cooldown_until")
                || c.equals("duty_seconds")
                || c.endsWith("_seconds")
                || c.equals("order")
                || c.equals("last_create_at")
                || c.equals("updated_at")) {
            return "INTEGER";
        }
        if (c.contains("_id")
                || c.endsWith("_id")
                || c.endsWith("_key")
                || c.equals("name")
                || c.equals("type")
                || c.equals("mode")
                || c.equals("status")
                || c.equals("value")
                || c.equals("kind")
                || c.equals("title")
                || c.equals("msg_key")
                || c.equals("rule_id")
                || c.equals("profession_id")
                || c.equals("faction_id")
                || c.equals("team_id")
                || c.equals("profile")
                || c.equals("sha256")) {
            return "TEXT";
        }
        if (c.startsWith("is_")
                || c.contains("_flag")
                || c.equals("active")
                || c.equals("any_support_revive")
                || c.equals("enabled")
                || c.equals("fallback_to_files")) {
            return isSqlite() ? "INTEGER" : "TINYINT(1)";
        }
        return "TEXT";
    }
}
