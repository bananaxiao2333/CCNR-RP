/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DbConfigTest {

    @Test
    void missingFileIsDisabled(@TempDir Path dir) {
        DbConfig c = DbConfig.load(dir.resolve("nope.properties"));
        assertFalse(c.enabled());
    }

    @Test
    void loadSqliteProps() throws Exception {
        Path f = Files.createTempFile("db", ".properties");
        Files.writeString(f, "db.enabled=true\ndb.mode=sqlite\ndb.profile=main\n");
        DbConfig c = DbConfig.load(f);
        assertTrue(c.enabled());
        assertEquals(DbType.SQLITE, c.mode());
        assertEquals("main", c.profile());
        assertTrue(c.jdbcUrl().startsWith("jdbc:sqlite:"));
    }

    @Test
    void passMasked() {
        DbConfig c = DbConfig.disabled();
        // disabled instance has empty pass
        assertEquals("(empty)", c.maskedPass());
    }
}
