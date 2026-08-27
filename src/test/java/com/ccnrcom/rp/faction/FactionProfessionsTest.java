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
    void resolveSaveKeepsLoadoutSelfDeployRadioDisabled() {
        // 现有定义：带装备 / 自部署开 / 无线电禁用开 / 无线电配置
        JsonObject root = new JsonObject();
        JsonObject loadout = new JsonObject();
        JsonArray inv = new JsonArray();
        JsonObject sword = new JsonObject();
        sword.addProperty("slot", 0);
        sword.addProperty("item", "minecraft:iron_sword");
        sword.addProperty("count", 1);
        inv.add(sword);
        loadout.add("inventory", inv);
        JsonObject radio = new JsonObject();
        radio.addProperty("speaker", "指挥官");
        JsonArray lines = new JsonArray();
        JsonObject l1 = new JsonObject();
        l1.addProperty("text", "欢迎");
        l1.addProperty("wait", 2.0);
        lines.add(l1);
        radio.add("lines", lines);
        List<String> errors = FactionProfessions.upsert(
                root, "medic", "军医", "a", true, 0, loadout, "m.wav", "简历", "cam", radio, true, id -> id.equals("a"));
        assertTrue(errors.isEmpty(), () -> errors.toString());

        // 表单输出：仅含表单字段（无 selfDeploy/loadout/radio/radioDisabled）
        JsonObject payload = new JsonObject();
        payload.addProperty("id", "medic");
        payload.addProperty("name", "军医改");
        payload.addProperty("factionId", "a");
        payload.addProperty("unlockLevel", 3);
        payload.addProperty("music", "new.wav");
        payload.addProperty("profile", "新简历");
        payload.addProperty("cmdcamScene", "");

        var save = FactionProfessions.resolveSave(
                payload, FactionProfessions.find(root, "medic").orElseThrow());
        assertTrue(save.selfDeploy(), "自部署开关应继承原值");
        assertTrue(save.radioDisabled(), "无线电禁用开关应继承原值");
        assertTrue(save.loadout().has("inventory"), "装备 loadout 应继承原值");
        assertEquals(3, save.unlockLevel());
        assertEquals("军医改", save.name());
        assertEquals("new.wav", save.music());
        assertEquals("", save.cmdcamScene());

        // 端到端：按 resolveSave 结果 upsert 后原字段不被清空
        List<String> saveErrors = FactionProfessions.upsert(
                root,
                save.id(),
                save.name(),
                save.factionId(),
                save.selfDeploy(),
                save.unlockLevel(),
                save.loadout(),
                save.music(),
                save.profile(),
                save.cmdcamScene(),
                save.radio(),
                save.radioDisabled(),
                id -> id.equals("a"));
        assertTrue(saveErrors.isEmpty(), () -> saveErrors.toString());
        JsonObject after = FactionProfessions.find(root, "medic").orElseThrow();
        assertTrue(after.getAsJsonObject("loadout").has("inventory"), "保存后装备不应丢失");
        assertTrue(after.has("radio"), "保存后无线电不应丢失");
        assertTrue(FactionProfessions.radioDisabled(after), "保存后无线电禁用开关不应被重置");
        assertTrue(FactionProfessions.selfDeploy(after), "保存后自部署开关不应被重置");
    }

    @Test
    void resolveSaveForCreateHasNoInheritance() {
        JsonObject payload = new JsonObject();
        payload.addProperty("id", "new_prof");
        payload.addProperty("name", "新职业");
        payload.addProperty("factionId", "a");
        var save = FactionProfessions.resolveSave(payload, null);
        assertNull(save.loadout(), "新建无现有定义，loadout 为 null（upsert 落空装备）");
        assertFalse(save.selfDeploy());
        assertFalse(save.radioDisabled());
        assertNull(save.radio());
        assertEquals("new_prof", save.id());
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
