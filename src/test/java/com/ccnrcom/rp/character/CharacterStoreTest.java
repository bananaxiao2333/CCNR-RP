/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.character;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccnrcom.rp.status.CharacterStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** P3 验收：角色存储 CRUD + 持久化往返 + 损坏容错。 */
class CharacterStoreTest {

    @TempDir
    Path temp;

    @Test
    void crudAndPersistenceRoundTrip() {
        CharacterStore store = new CharacterStore(temp);
        store.load();
        CharacterData c = store.create("p1", "陆佐", "qdf", "qdf_guard", "背景A", 8, UUID::randomUUID);
        store.save();

        CharacterStore reloaded = new CharacterStore(temp);
        reloaded.load();
        assertEquals(1, reloaded.all().size());
        CharacterData back = reloaded.find(c.id()).orElseThrow();
        assertEquals("陆佐", back.name());
        assertEquals("qdf", back.factionId());
        assertEquals(CharacterStatus.OBSERVING, back.status());
        assertTrue(reloaded.delete(c.id()));
        reloaded.save();
        assertEquals(0, new CharacterStore(temp).all().size());
    }

    @Test
    void findAliveReportsCurrentFirstAlive() {
        CharacterStore store = new CharacterStore(temp);
        store.load();
        CharacterData a = store.create("p1", "A", "qdf", "qdf_guard", "", 8, UUID::randomUUID);
        CharacterData b = store.create("p1", "B", "qdf", "qdf_guard", "", 8, UUID::randomUUID);
        store.update(a.withStatus(CharacterStatus.ALIVE));
        assertTrue(store.findAlive("p1").isPresent());
        assertEquals("A", store.findAlive("p1").orElseThrow().name());
        assertTrue(store.findAlive("p2").isEmpty());
        // 双 alive 的禁止由服务层负责；Store 只报告当前
        store.update(b.withStatus(CharacterStatus.ALIVE));
        assertEquals(
                2,
                store.ofPlayer("p1").stream()
                        .filter(c -> c.status() == CharacterStatus.ALIVE)
                        .count());
    }

    @Test
    void corruptFileDoesNotCrash() throws Exception {
        Path file = temp.resolve("characters.json");
        Files.writeString(file, "{ not json !!!", StandardCharsets.UTF_8);
        CharacterStore store = new CharacterStore(temp);
        store.load();
        assertTrue(store.all().isEmpty());
        assertTrue(Files.exists(temp.resolve("characters.json.bak")));
        store.save();
        CharacterStore reloaded = new CharacterStore(temp);
        reloaded.load();
        assertTrue(reloaded.all().isEmpty());
    }

    @Test
    void updateReplacesAndPersists() {
        CharacterStore store = new CharacterStore(temp);
        store.load();
        CharacterData c = store.create("p1", "X", "qdf", "qdf_guard", "", 8, UUID::randomUUID);
        store.update(c.withStatus(CharacterStatus.DEAD).withCooldown(12345L));
        store.save();
        CharacterStore reloaded = new CharacterStore(temp);
        reloaded.load();
        CharacterData back = reloaded.find(c.id()).orElseThrow();
        assertEquals(CharacterStatus.DEAD, back.status());
        assertEquals(12345L, back.cooldownUntil());
    }
}
