/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.faction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 拓展设定字段的落盘语义（issue #2 阵营属性 / issue #3 弹头许可）：
 * 单字段接管必须"只替换自己的字段"，不得吞掉阵营的其它配置（docs/01 §11.1）。
 */
class FactionExtrasSaveTest {

    private static JsonObject factionRoot() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonArray fa = new JsonArray();
        JsonObject f = new JsonObject();
        f.addProperty("id", "qdf");
        f.addProperty("name", "QDF司令部");
        f.addProperty("color", "#4CAF50");
        f.addProperty("description", "设施保全");
        f.addProperty("icon", "shield");
        f.addProperty("tier", 1);
        f.addProperty("music", "audio/qdf.wav");
        f.addProperty("cmdcamScene", "intro_qdf");
        f.addProperty("cinematicBlackScreen", false);
        f.addProperty("cinematicCompact", true);
        f.addProperty("warheadEnabled", true);
        f.addProperty("warheadArea", "reactor");
        fa.add(f);
        JsonObject other = new JsonObject();
        other.addProperty("id", "qsa");
        other.addProperty("name", "QSA");
        fa.add(other);
        root.add("factions", fa);
        root.add("groups", new JsonArray());
        root.add("relations", new JsonArray());
        root.add("professions", new JsonArray());
        return root;
    }

    /** 反射构造 FactionManager（绕过 FMLPaths 构造器），与 FactionManagerSaveTest 同一手法。 */
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

    private static JsonObject faction(Object mgr, String id) throws Exception {
        Field rootField = mgr.getClass().getDeclaredField("root");
        rootField.setAccessible(true);
        JsonObject after = (JsonObject) rootField.get(mgr);
        for (var el : after.getAsJsonArray("factions")) {
            JsonObject o = el.getAsJsonObject();
            if (id.equals(o.get("id").getAsString())) {
                return o;
            }
        }
        throw new IllegalStateException("未找到阵营: " + id);
    }

    @Test
    @SuppressWarnings("unchecked")
    void setFactionAttributesOnlyTouchesAttributes() throws Exception {
        Object mgr = allocManager(factionRoot());
        JsonArray attrs = JsonParser.parseString("[{\"id\":\"minecraft:generic.max_health\",\"amount\":40}]")
                .getAsJsonArray();
        var set = mgr.getClass().getMethod("setFactionAttributes", String.class, com.google.gson.JsonElement.class);
        List<String> errors = (List<String>) set.invoke(mgr, "qdf", attrs);
        assertTrue(errors.isEmpty(), () -> errors.toString());

        JsonObject f = faction(mgr, "qdf");
        assertEquals(
                40.0,
                f.getAsJsonArray("attributes")
                        .get(0)
                        .getAsJsonObject()
                        .get("amount")
                        .getAsDouble());
        // 其它字段一个都不能丢
        assertEquals("QDF司令部", f.get("name").getAsString());
        assertEquals("shield", f.get("icon").getAsString());
        assertEquals("intro_qdf", f.get("cmdcamScene").getAsString());
        assertTrue(f.get("warheadEnabled").getAsBoolean(), "弹头许可字段应保留");
        assertEquals("reactor", f.get("warheadArea").getAsString());
        // 别的阵营不受影响
        assertFalse(faction(mgr, "qsa").has("attributes"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void setFactionAttributesRejectsUnknownOperation() throws Exception {
        Object mgr = allocManager(factionRoot());
        JsonArray bad = JsonParser.parseString(
                        "[{\"id\":\"minecraft:generic.armor\",\"amount\":1,\"operation\":\"plus\"}]")
                .getAsJsonArray();
        var set = mgr.getClass().getMethod("setFactionAttributes", String.class, com.google.gson.JsonElement.class);
        List<String> errors = (List<String>) set.invoke(mgr, "qdf", bad);
        assertFalse(errors.isEmpty(), "非法运算必须拒绝");
        // 拒绝时不得留下半份改动
        assertFalse(faction(mgr, "qdf").has("attributes"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void setFactionWarheadWritesAndClearsBothFields() throws Exception {
        Object mgr = allocManager(factionRoot());
        var set = mgr.getClass().getMethod("setFactionWarhead", String.class, boolean.class, String.class);
        List<String> errors = (List<String>) set.invoke(mgr, "qdf", true, "vault");
        assertTrue(errors.isEmpty(), () -> errors.toString());
        JsonObject f = faction(mgr, "qdf");
        assertTrue(f.get("warheadEnabled").getAsBoolean());
        assertEquals("vault", f.get("warheadArea").getAsString());
        assertEquals("QDF司令部", f.get("name").getAsString(), "其它字段应保留");

        // 关闭：字段移除（盘上只保留"开"的事实），目标区域清空
        List<String> errors2 = (List<String>) set.invoke(mgr, "qdf", false, "");
        assertTrue(errors2.isEmpty(), () -> errors2.toString());
        JsonObject f2 = faction(mgr, "qdf");
        assertFalse(f2.has("warheadEnabled"));
        assertFalse(f2.has("warheadArea"));

        // 读取端语义：缺字段 = false / 空串
        var enabled = mgr.getClass().getMethod("warheadEnabled", String.class);
        var area = mgr.getClass().getMethod("warheadArea", String.class);
        assertFalse((Boolean) enabled.invoke(mgr, "qdf"));
        assertEquals("", area.invoke(mgr, "qdf"));
        assertEquals("", area.invoke(mgr, "missing"));
    }
}
