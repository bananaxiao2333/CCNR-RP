/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssetRepositoryTest {

    @Test
    void roundtripViaTempSqlite(@TempDir Path dir) throws Exception {
        Path props = dir.resolve("db.properties");
        Files.writeString(props, "db.enabled=true\ndb.mode=sqlite\ndb.file=" + dir.resolve("test.db") + "\n");
        Database db = new Database(DbConfig.load(props));
        com.ccnrcom.rp.CCNRRPMod.database = db; // 静态仓储（AssetRepository）路由到 CCNRRPMod.database
        assertTrue(db.connect());
        try {
            byte[] data = "OggS-fake-music".getBytes();
            String sha = "aabbcc";
            assertTrue(AssetRepository.save("qa.ogg", "music", data, sha));
            List<AssetRepository.AssetMeta> ms = AssetRepository.list();
            assertEquals(1, ms.size());
            assertEquals("qa.ogg", ms.get(0).name());
            assertEquals("music", ms.get(0).kind());
            assertEquals(sha, ms.get(0).sha256());
            assertEquals(new String(data), new String(AssetRepository.read("qa.ogg")));
        } finally {
            com.ccnrcom.rp.CCNRRPMod.database = null;
            db.disconnect();
        }
    }
}
