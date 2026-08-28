/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SqlDialectTest {

    @Test
    void placeholders() {
        assertEquals("?, ?, ?", new SqlDialect(DbType.SQLITE).placeholders(3));
        assertEquals("?", new SqlDialect(DbType.MYSQL).placeholders(1));
    }

    @Test
    void quoteUsesBackticksForBoth() {
        assertEquals("`users`", new SqlDialect(DbType.SQLITE).quote("users"));
        assertEquals("`users`", new SqlDialect(DbType.MYSQL).quote("users"));
    }

    @Test
    void upsertSqliteOnConflict() {
        String sql = new SqlDialect(DbType.SQLITE).upsert("users", List.of("uuid", "xp", "status"), List.of("uuid"));
        assertTrue(sql.startsWith("INSERT INTO `users`"));
        assertTrue(sql.contains("ON CONFLICT(`uuid`) DO UPDATE SET"));
        assertTrue(sql.contains("`xp` = excluded.`xp`"));
    }

    @Test
    void upsertMySqlOnDuplicate() {
        String sql = new SqlDialect(DbType.MYSQL).upsert("users", List.of("uuid", "xp"), List.of("uuid"));
        assertTrue(sql.contains("ON DUPLICATE KEY UPDATE"));
        assertTrue(sql.contains("`xp` = VALUES(`xp`)"));
    }
}
