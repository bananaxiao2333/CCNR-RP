/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccnrcom.rp.experience.XpChangeList.XpChange;
import com.ccnrcom.rp.status.CharacterStatus;
import com.ccnrcom.rp.user.UserService.UserProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UserRepositoryTest {

    @Test
    void roundtripViaTempSqlite(@TempDir Path dir) throws Exception {
        Path props = dir.resolve("db.properties");
        Files.writeString(
                props, "db.enabled=true\ndb.mode=sqlite\ndb.file=" + dir.resolve("test.db") + "\ndb.profile=default\n");
        Database db = new Database(DbConfig.load(props));
        assertTrue(db.connect(), "connect should succeed");
        UserRepository repo = new UserRepository(db);

        UserProfile p = new UserProfile(
                100L,
                1700000000000L,
                true,
                CharacterStatus.ALIVE,
                "qdf_guard",
                "qdf",
                1700001000000L,
                3600L,
                List.of(new XpChange("alive_duty", "值班", 5), new XpChange("kill_zombie", "击杀僵尸", -2)));
        repo.saveAll(Map.of("uuid-1", p));

        Map<String, UserProfile> loaded = repo.loadAll();
        assertEquals(1, loaded.size());
        UserProfile back = loaded.get("uuid-1");
        assertEquals(100L, back.xp());
        assertEquals(CharacterStatus.ALIVE, back.status());
        assertEquals("qdf_guard", back.professionId());
        assertEquals("qdf", back.factionId());
        assertEquals(3600L, back.dutySeconds());
        assertTrue(back.anySupportRevive());
        assertEquals(2, back.pendingXp().size());
        assertEquals("alive_duty", back.pendingXp().get(0).ruleId());
        assertEquals(-2, back.pendingXp().get(1).value());
        db.disconnect();
    }
}
