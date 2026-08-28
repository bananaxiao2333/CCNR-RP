/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.faction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/** 阵营表单保存回归：updateFaction 只覆盖表单字段，radio/spawn/professions 等原数据必须保留（防止「保存清空其他数据」回归）。 */
class FactionManagerSaveTest {

    private static JsonObject sampleRoot() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonArray fa = new JsonArray();
        JsonObject f = new JsonObject();
        f.addProperty("id", "qdf");
        f.addProperty("name", "QDF");
        f.addProperty("color", "#FF0000");
        f.addProperty("icon", "shield");
        f.addProperty("tier", 1);
        f.addProperty("music", "audio/qdf.wav");
        f.addProperty("cmdcamScene", "intro_cam");
        JsonObject radio = new JsonObject();
        radio.addProperty("speaker", "指挥官");
        JsonArray lines = new JsonArray();
        JsonObject l1 = new JsonObject();
        l1.addProperty("text", "欢迎");
        l1.addProperty("wait", 2.0);
        lines.add(l1);
        radio.add("lines", lines);
        f.add("radio", radio);
        JsonObject spawn = new JsonObject();
        spawn.addProperty("rule", "SINGLE");
        JsonArray pts = new JsonArray();
        JsonObject p1 = new JsonObject();
        p1.addProperty("x", 1);
        p1.addProperty("y", 64);
        p1.addProperty("z", 2);
        p1.addProperty("dim", "minecraft:overworld");
        pts.add(p1);
        spawn.add("points", pts);
        f.add("spawn", spawn);
        fa.add(f);
        root.add("factions", fa);
        JsonArray pa = new JsonArray();
        JsonObject prof = new JsonObject();
        prof.addProperty("id", "qdf_guard");
        prof.addProperty("name", "QDF队员");
        prof.addProperty("factionId", "qdf");
        pa.add(prof);
        root.add("professions", pa);
        return root;
    }

    /** 构造带全字段职业（装备/音乐/简历/场景/无线电/开关）的根。 */
    private static JsonObject professionRoot() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonObject loadout = new JsonObject();
        JsonArray inv = new JsonArray();
        JsonObject sword = new JsonObject();
        sword.addProperty("slot", 0);
        sword.addProperty("item", "minecraft:iron_sword");
        sword.addProperty("count", 1);
        inv.add(sword);
        loadout.add("inventory", inv);
        loadout.add("armor", new JsonArray());
        loadout.add("offhand", new JsonObject());
        JsonObject radio = new JsonObject();
        radio.addProperty("speaker", "指挥官");
        JsonArray lines = new JsonArray();
        JsonObject l1 = new JsonObject();
        l1.addProperty("text", "欢迎");
        l1.addProperty("wait", 2.0);
        lines.add(l1);
        radio.add("lines", lines);
        JsonObject prof = new JsonObject();
        prof.addProperty("id", "medic");
        prof.addProperty("name", "军医");
        prof.addProperty("factionId", "a");
        prof.addProperty("selfDeploy", true);
        prof.addProperty("unlockLevel", 2);
        prof.addProperty("music", "audio/medic.wav");
        prof.addProperty("profile", "项目简历内容");
        prof.addProperty("cmdcamScene", "intro_cam");
        prof.addProperty("radioDisabled", true);
        prof.add("radio", radio);
        prof.add("loadout", loadout);
        JsonArray pa = new JsonArray();
        pa.add(prof);
        root.add("professions", pa);
        root.add("factions", new JsonArray());
        return root;
    }

    /** 反射构造 FactionManager（绕过 FMLPaths 构造器）。 */
    private static Object allocManager(JsonObject root) throws Exception {
        Object mgr;
        try {
            mgr = new FactionManager();
        } catch (Throwable t) {
            Class<?> unsafeCls = Class.forName("sun.misc.Unsafe");
            Field uf = unsafeCls.getDeclaredField("theUnsafe");
            uf.setAccessible(true);
            Object unsafe = uf.get(null);
            java.lang.reflect.Method alloc = unsafeCls.getMethod("allocateInstance", Class.class);
            mgr = alloc.invoke(unsafe, FactionManager.class);
        }
        Class<?> c = mgr.getClass();
        Field rootF = c.getDeclaredField("root");
        rootF.setAccessible(true);
        rootF.set(mgr, root);
        java.lang.reflect.Method reload = c.getDeclaredMethod("reloadFromRoot");
        reload.setAccessible(true);
        reload.invoke(mgr);
        return mgr;
    }

    @Test
    @SuppressWarnings("unchecked")
    void setProfessionLoadoutOnlyTouchesLoadout() throws Exception {
        JsonObject root = professionRoot();
        Object mgr = allocManager(root);
        Class<?> c = mgr.getClass();
        // 新 loadout：只改装备
        JsonObject newLoadout = new JsonObject();
        JsonArray inv = new JsonArray();
        JsonObject gun = new JsonObject();
        gun.addProperty("slot", 0);
        gun.addProperty("item", "minecraft:bow");
        gun.addProperty("count", 1);
        inv.add(gun);
        newLoadout.add("inventory", inv);
        newLoadout.add("armor", new JsonArray());
        newLoadout.add("offhand", new JsonObject());

        java.lang.reflect.Method set =
                c.getMethod("setProfessionLoadout", String.class, com.google.gson.JsonObject.class);
        java.util.List<String> errors = (java.util.List<String>) set.invoke(mgr, "medic", newLoadout);
        assertTrue(errors.isEmpty(), () -> errors.toString());

        Field rootField = c.getDeclaredField("root");
        rootField.setAccessible(true);
        JsonObject after = (JsonObject) rootField.get(mgr);
        JsonObject prof = after.getAsJsonArray("professions").get(0).getAsJsonObject();
        // loadout 被替换
        assertEquals(
                "minecraft:bow",
                prof.getAsJsonObject("loadout")
                        .getAsJsonArray("inventory")
                        .get(0)
                        .getAsJsonObject()
                        .get("item")
                        .getAsString());
        // 其它基础配置全部保留（保存装备不得吞掉）
        assertEquals("audio/medic.wav", prof.get("music").getAsString(), "音乐应保留");
        assertEquals("项目简历内容", prof.get("profile").getAsString(), "项目简历应保留");
        assertEquals("intro_cam", prof.get("cmdcamScene").getAsString(), "CMDCam 场景应保留");
        assertTrue(prof.get("radioDisabled").getAsBoolean(), "无线电禁用开关应保留");
        assertTrue(prof.has("radio"), "无线电配置应保留");
        assertTrue(prof.get("selfDeploy").getAsBoolean(), "自部署开关应保留");
        assertEquals(2, prof.get("unlockLevel").getAsInt(), "解锁等级应保留");
    }

    @Test
    @SuppressWarnings("unchecked")
    void setProfessionLoadoutUnknownIdRejected() throws Exception {
        JsonObject root = professionRoot();
        Object mgr = allocManager(root);
        Class<?> c = mgr.getClass();
        java.lang.reflect.Method set =
                c.getMethod("setProfessionLoadout", String.class, com.google.gson.JsonObject.class);
        java.util.List<String> errors = (java.util.List<String>) set.invoke(mgr, "no_such", new JsonObject());
        assertTrue(!errors.isEmpty(), "未知职业应报错");
        Field rootField = c.getDeclaredField("root");
        rootField.setAccessible(true);
        JsonObject after = (JsonObject) rootField.get(mgr);
        assertEquals(1, after.getAsJsonArray("professions").size(), "不应新增职业");
    }

    @Test
    @SuppressWarnings("unchecked")
    void setProfessionSpawnPersists() throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonArray pa = new JsonArray();
        JsonObject prof = new JsonObject();
        prof.addProperty("id", "medic");
        prof.addProperty("name", "军医");
        prof.addProperty("factionId", "a");
        pa.add(prof);
        root.add("professions", pa);
        root.add("factions", new JsonArray());
        Object mgr = allocManager(root);
        Class<?> c = mgr.getClass();

        java.util.List<com.ccnrcom.rp.faction.FactionManager.SpawnPoint> pts = java.util.List.of(
                new com.ccnrcom.rp.faction.FactionManager.SpawnPoint(10, 64, -5, "minecraft:overworld"),
                new com.ccnrcom.rp.faction.FactionManager.SpawnPoint(20, 70, 0, "minecraft:the_nether"));
        java.lang.reflect.Method set =
                c.getMethod("setProfessionSpawn", String.class, String.class, java.util.List.class);
        java.util.List<String> errors = (java.util.List<String>) set.invoke(mgr, "medic", "SINGLE", pts);
        assertTrue(errors.isEmpty(), () -> errors.toString());

        Field rootField = c.getDeclaredField("root");
        rootField.setAccessible(true);
        JsonObject after = (JsonObject) rootField.get(mgr);
        JsonObject prof2 = after.getAsJsonArray("professions").get(0).getAsJsonObject();
        assertTrue(prof2.has("spawn"), "spawn 应已写入");
        JsonObject sp = prof2.getAsJsonObject("spawn");
        assertEquals("SINGLE", sp.get("rule").getAsString());
        assertEquals(2, sp.getAsJsonArray("points").size());
        assertEquals(
                "minecraft:the_nether",
                sp.getAsJsonArray("points").get(1).getAsJsonObject().get("dim").getAsString());
        // 其它字段保留
        assertEquals("军医", prof2.get("name").getAsString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateFactionPreservesOtherFields() throws Exception {
        JsonObject root = sampleRoot();

        // 绕过构造器（FMLPaths）分配实例
        Object mgr;
        try {
            mgr = new FactionManager();
        } catch (Throwable t) {
            Class<?> unsafeCls = Class.forName("sun.misc.Unsafe");
            Field uf = unsafeCls.getDeclaredField("theUnsafe");
            uf.setAccessible(true);
            Object unsafe = uf.get(null);
            java.lang.reflect.Method alloc = unsafeCls.getMethod("allocateInstance", Class.class);
            mgr = alloc.invoke(unsafe, FactionManager.class);
        }
        Class<?> c = mgr.getClass();
        Field rootF = c.getDeclaredField("root");
        rootF.setAccessible(true);
        rootF.set(mgr, root);
        // 重建 graph
        java.lang.reflect.Method reload = c.getDeclaredMethod("reloadFromRoot");
        reload.setAccessible(true);
        reload.invoke(mgr);
        // 更新（模拟表单保存 8 字段）
        java.lang.reflect.Method upd = c.getMethod(
                "updateFaction",
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                int.class,
                String.class,
                String.class);
        java.util.List<String> errors =
                (java.util.List<String>) upd.invoke(mgr, "qdf", "新名字", "#00FF00", "新描述", "hex", 2, "audio/new.wav", "");
        assertTrue(errors.isEmpty(), () -> errors.toString());
        JsonObject after = (JsonObject) rootF.get(mgr);
        JsonObject f2 = after.getAsJsonArray("factions").get(0).getAsJsonObject();
        // 保留字段
        assertTrue(f2.has("radio"), "radio 应保留");
        assertTrue(f2.has("spawn"), "spawn 应保留");
        assertTrue(after.has("professions"), "professions 数组应保留");
        assertEquals(1, after.getAsJsonArray("professions").size());
        // 更新字段
        assertEquals("新名字", f2.get("name").getAsString());
        assertEquals("audio/new.wav", f2.get("music").getAsString());
    }
}
