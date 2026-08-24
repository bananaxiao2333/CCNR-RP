/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.character;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.ccnrcom.rp.status.CharacterStatus;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** P3 验收：CharacterData JSON 往返。 */
class CharacterDataJsonTest {

    @Test
    void roundTrip() {
        CharacterData c = new CharacterData(
                "id1",
                "p1",
                "陆佐",
                "qdf",
                "qdf_guard",
                "背景",
                "id1.png",
                "abc123",
                CharacterStatus.DEAD,
                500,
                3600,
                99999,
                Map.of("evac", 50),
                "SAFE_RESCUE",
                111);
        CharacterData back = CharacterData.fromJson(c.toJson());
        assertEquals(c.id(), back.id());
        assertEquals(c.name(), back.name());
        assertEquals(c.status(), back.status());
        assertEquals(c.xp(), back.xp());
        assertEquals(c.cooldownUntil(), back.cooldownUntil());
        assertEquals(c.skinHash(), back.skinHash());
        assertEquals(50, (int) back.tasks().get("evac"));
    }

    @Test
    void oldFieldDefaults() {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("id", "x");
        o.addProperty("playerUuid", "p");
        o.addProperty("name", "n");
        CharacterData c = CharacterData.fromJson(o);
        assertEquals(CharacterStatus.OBSERVING, c.status());
        assertNull(c.skin());
    }
}
