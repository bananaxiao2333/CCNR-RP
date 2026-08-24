/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.profession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccnrcom.rp.profession.ProfessionJson.SlotItem;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** P2 验收：Loadout JSON 编解码 + 校验。 */
class ProfessionJsonTest {

    @Test
    void slotRoundTrip() {
        SlotItem s = new SlotItem(0, "minecraft:iron_sword", 1, "H4sIAA==");
        JsonObject o = ProfessionJson.slotToJson(s);
        SlotItem back = ProfessionJson.slotFromJson(o, "inventory[0]", new ArrayList<>());
        assertEquals(s, back);
    }

    @Test
    void slotWithoutNbtKeepsNull() {
        SlotItem s = new SlotItem(5, "minecraft:torch", 64, null);
        JsonObject o = ProfessionJson.slotToJson(s);
        assertFalse(o.has("nbt"));
        SlotItem back = ProfessionJson.slotFromJson(o, "inventory[0]", new ArrayList<>());
        assertNull(back.nbtBase64());
        assertEquals(64, back.count());
    }

    @Test
    void validateRejectsBadSlotAndCount() {
        JsonObject loadout = new JsonObject();
        JsonArray inv = new JsonArray();
        inv.add(slotJson(99, "minecraft:stone", 1));
        inv.add(slotJson(0, "minecraft:stone", 0));
        loadout.add("inventory", inv);
        loadout.add("armor", new JsonArray());
        loadout.add("offhand", new JsonObject());
        List<String> errors = ProfessionJson.validate(loadout);
        assertTrue(errors.stream().anyMatch(e -> e.contains("槽位越界")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("数量非法")), errors.toString());
    }

    @Test
    void validateAcceptsValidLoadout() {
        JsonObject loadout = new JsonObject();
        JsonArray inv = new JsonArray();
        inv.add(slotJson(0, "minecraft:iron_sword", 1));
        loadout.add("inventory", inv);
        JsonArray armor = new JsonArray();
        armor.add(slotJson(36, "minecraft:leather_boots", 1));
        loadout.add("armor", armor);
        loadout.add("offhand", slotJson(40, "minecraft:shield", 1));
        assertTrue(ProfessionJson.validate(loadout).isEmpty());
    }

    @Test
    void missingFieldsReported() {
        JsonObject bad = new JsonObject();
        bad.addProperty("slot", 1);
        List<String> errors = new ArrayList<>();
        assertNull(ProfessionJson.slotFromJson(bad, "inventory[0]", errors));
        assertFalse(errors.isEmpty());
    }

    @Test
    void validateRejectsNullLoadout() {
        assertFalse(ProfessionJson.validate(null).isEmpty());
    }

    private static JsonObject slotJson(int slot, String item, int count) {
        JsonObject o = new JsonObject();
        o.addProperty("slot", slot);
        o.addProperty("item", item);
        o.addProperty("count", count);
        return o;
    }
}
