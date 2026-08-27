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
import java.nio.file.Files;
import java.nio.file.Path;
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

    @Test
    @SuppressWarnings("unchecked")
    void updateFactionPreservesOtherFields() throws Exception {
        JsonObject root = sampleRoot();
        Path tmp = Files.createTempFile("factions-test", ".json");
        Files.deleteIfExists(tmp);

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
        Field fileF = c.getDeclaredField("file");
        fileF.setAccessible(true);
        fileF.set(mgr, tmp);
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
