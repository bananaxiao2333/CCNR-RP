/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.faction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 阵营属性字段的落盘语义（issue #2，docs/16）：
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
        // 老配置文件里的历史遗留键（原弹头许可）也必须原样保留：非本功能的字段一律不碰
        assertTrue(f.get("warheadEnabled").getAsBoolean(), "历史遗留字段应原样保留");
        assertEquals("reactor", f.get("warheadArea").getAsString());
        // 已删除的入场电影版式开关（cinematicCompact，2.25.3）同样是孤儿键：不读、不清
        assertTrue(f.get("cinematicCompact").getAsBoolean(), "已删除功能的孤儿键应原样保留");
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

    /**
     * 入场电影版式切换删除后的兼容契约（docs/14 §6）：老 factions.json 里的 {@code cinematicCompact}
     * （旧「标准版式」写法，值为 false）必须照常加载——字段不再被读取（入场恒为简洁版式），但也不能被
     * 表单保存/单字段写清除（无害孤儿键，同 warheadEnabled 的处理方式）。
     */
    @Test
    @SuppressWarnings("unchecked")
    void legacyCinematicCompactKeyIsAcceptedButNeverReadNorCleared() throws Exception {
        JsonObject root = factionRoot();
        root.getAsJsonArray("factions").get(0).getAsJsonObject().addProperty("cinematicCompact", false);

        FactionModels.ParseResult parsed = FactionManager.parse(root);
        assertTrue(parsed.success(), () -> parsed.errors().toString());
        assertFalse(parsed.graph().factions().get("qdf").cinematicBlackScreen(), "同阵营的「入场全屏黑」开关仍按配置解析");
        assertThrows(
                NoSuchMethodException.class,
                () -> FactionModels.Faction.class.getMethod("cinematicCompact"),
                "版式开关字段已删除（2.25.3），不得回归");

        // 表单保存（updateFaction）只写表单字段：老键原样保留，不会被清除
        Object mgr = allocManager(root);
        var update = mgr.getClass()
                .getMethod(
                        "updateFaction",
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        int.class,
                        String.class,
                        String.class,
                        boolean.class);
        List<String> errors = (List<String>)
                update.invoke(mgr, "qdf", "QDF司令部", "#4CAF50", "设施保全", "shield", 1, "audio/qdf.wav", "intro_qdf", true);
        assertTrue(errors.isEmpty(), () -> errors.toString());
        JsonObject f = faction(mgr, "qdf");
        assertTrue(f.get("cinematicBlackScreen").getAsBoolean(), "表单里的「入场全屏黑」仍可写");
        assertFalse(f.get("cinematicCompact").getAsBoolean(), "已废弃的版式开关键应原样保留");
    }
}
