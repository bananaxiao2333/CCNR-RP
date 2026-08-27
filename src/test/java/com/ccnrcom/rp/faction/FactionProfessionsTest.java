/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.faction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

/** P3 扩展：职业 cmdcamScene 字段 upsert 写盘/回读（空串=移除字段）；职业部署点（复活点）spawn 解析。 */
class FactionProfessionsTest {

    @Test
    void upsertWritesAndReadsCmdcamScene() {
        JsonObject root = new JsonObject();
        List<String> errors = FactionProfessions.upsert(
                root, "medic", "军医", "a", false, 0, null, "", "", "intro_cam", null, false, id -> id.equals("a"));
        assertTrue(errors.isEmpty(), () -> errors.toString());
        JsonObject def = FactionProfessions.find(root, "medic").orElseThrow();
        assertEquals("intro_cam", FactionProfessions.cmdcamScene(def));

        // 空串 → 移除字段，回读为空
        FactionProfessions.upsert(
                root, "medic", "军医", "a", false, 0, null, "", "", "", null, false, id -> id.equals("a"));
        def = FactionProfessions.find(root, "medic").orElseThrow();
        assertEquals("", FactionProfessions.cmdcamScene(def));
    }

    @Test
    void spawnParsesPointsAndRule() {
        JsonObject def = new JsonObject();
        def.addProperty("id", "medic");
        JsonObject spawn = new JsonObject();
        spawn.addProperty("rule", "SINGLE");
        JsonArray pts = new JsonArray();
        JsonObject p1 = new JsonObject();
        p1.addProperty("x", 10);
        p1.addProperty("y", 64);
        p1.addProperty("z", -5);
        p1.addProperty("dim", "minecraft:overworld");
        pts.add(p1);
        JsonObject p2 = new JsonObject();
        p2.addProperty("x", 20);
        p2.addProperty("y", 70);
        p2.addProperty("z", 0);
        pts.add(p2);
        spawn.add("points", pts);
        def.add("spawn", spawn);

        com.ccnrcom.rp.faction.FactionManager.FactionSpawn s = FactionProfessions.spawn(def);
        assertTrue(s != null);
        assertEquals(com.ccnrcom.rp.faction.FactionManager.SPAWN_RULE_SINGLE, s.rule());
        assertEquals(2, s.points().size());
        assertEquals(10.0, s.points().get(0).x());
        assertEquals(64.0, s.points().get(0).y());
        assertEquals(-5.0, s.points().get(0).z());
        assertEquals("minecraft:overworld", s.points().get(0).dim());
        // 未配置 spawn → null（回退阵营/世界复活点）
        JsonObject noSpawn = new JsonObject();
        noSpawn.addProperty("id", "medic");
        assertNull(FactionProfessions.spawn(noSpawn));
        assertNull(FactionProfessions.spawn(null));
    }

    @Test
    void radioUpsertWritesAndReads() {
        JsonObject root = new JsonObject();
        JsonObject radio = new JsonObject();
        radio.addProperty("speaker", "指挥官");
        JsonArray lines = new JsonArray();
        JsonObject l1 = new JsonObject();
        l1.addProperty("text", "欢迎");
        l1.addProperty("wait", 2.0);
        lines.add(l1);
        radio.add("lines", lines);
        List<String> errors = FactionProfessions.upsert(
                root, "medic", "军医", "a", false, 0, null, "", "", "", radio, true, id -> id.equals("a"));
        assertTrue(errors.isEmpty(), () -> errors.toString());
        JsonObject def = FactionProfessions.find(root, "medic").orElseThrow();
        assertTrue(FactionProfessions.hasRadioLines(FactionProfessions.radio(def)));
        assertTrue(FactionProfessions.radioDisabled(def));
        FactionProfessions.upsert(
                root, "medic", "军医", "a", false, 0, null, "", "", "", new JsonObject(), true, id -> id.equals("a"));
        def = FactionProfessions.find(root, "medic").orElseThrow();
        assertNull(FactionProfessions.radio(def));
        assertFalse(FactionProfessions.hasRadioLines(null));
        assertFalse(FactionProfessions.radioDisabled(new JsonObject()));
    }
}
