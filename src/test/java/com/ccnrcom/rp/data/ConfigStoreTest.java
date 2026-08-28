/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 配置文档（DB 路径）+ 配置档切换隔离测试。 */
class ConfigStoreTest {

    @Test
    void saveLoadAndProfileIsolation(@TempDir Path dir) throws Exception {
        Path props = dir.resolve("db.properties");
        Files.writeString(
                props, "db.enabled=true\ndb.mode=sqlite\ndb.file=" + dir.resolve("test.db") + "\ndb.profile=default\n");
        Database db = new Database(DbConfig.load(props));
        com.ccnrcom.rp.CCNRRPMod.database = db;
        try {
            assertTrue(db.connect());
            assertEquals("default", ConfigStore.activeProfile());

            JsonObject a = new JsonObject();
            a.addProperty("version", 1);
            a.addProperty("name", "default-factions");
            assertTrue(ConfigStore.save("factions.json", a));

            Optional<JsonObject> loaded = ConfigStore.load("factions.json");
            assertTrue(loaded.isPresent());
            assertEquals("default-factions", loaded.get().get("name").getAsString());

            // 新建并切换到 p2，写入不同内容，验证隔离
            assertTrue(ConfigStore.createProfile("p2", "p2"));
            assertTrue(ConfigStore.selectProfile("p2"));
            JsonObject b = new JsonObject();
            b.addProperty("version", 1);
            b.addProperty("name", "p2-factions");
            ConfigStore.save("factions.json", b);

            // 切回 default：仍是 default-factions
            assertTrue(ConfigStore.selectProfile("default"));
            assertEquals(
                    "default-factions",
                    ConfigStore.load("factions.json").get().get("name").getAsString());

            // 复制 p2 → p3
            assertTrue(ConfigStore.createProfile("p3", "p3"));
            assertTrue(ConfigStore.copyProfile("p2", "p3"));
            assertTrue(ConfigStore.selectProfile("p3"));
            assertEquals(
                    "p2-factions",
                    ConfigStore.load("factions.json").get().get("name").getAsString());

            assertTrue(ConfigStore.listProfiles().containsAll(List.of("default", "p2", "p3")));
        } finally {
            com.ccnrcom.rp.CCNRRPMod.database = null;
            db.disconnect();
        }
    }
}
