/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.area;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 区域注册表（issue #3 拓展设定）：解析/校验/命中判定 + 边界与非法输入。 */
class AreaRegistryTest {

    private static JsonArray arr(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }

    private static final String OK = "[{\"id\":\"reactor\",\"name\":\"反应堆大厅\",\"dim\":\"minecraft:overworld\","
            + "\"x1\":10,\"y1\":-60,\"z1\":10,\"x2\":40,\"y2\":80,\"z2\":40}]";

    @Test
    void parsesAndNormalizesCorners() {
        AreaRegistry.ParseResult r = AreaRegistry.parse(arr(OK));
        assertTrue(r.success(), r.errors().toString());
        Area a = r.areas().get(0);
        assertEquals("reactor", a.id());
        assertEquals("反应堆大厅", a.name());
        assertEquals(10.0, a.minX(), 1e-9);
        assertEquals(40.0, a.maxX(), 1e-9);
        // 角点填反也要能用（归一化 min/max）
        AreaRegistry.ParseResult flipped = AreaRegistry.parse(
                arr(
                        "[{\"id\":\"z\",\"dim\":\"minecraft:overworld\",\"x1\":40,\"y1\":80,\"z1\":40,\"x2\":10,\"y2\":-60,\"z2\":10}]"));
        assertTrue(flipped.success());
        assertTrue(flipped.areas().get(0).contains("minecraft:overworld", 20, 0, 20));
    }

    @Test
    void containsRespectsDimensionAndBounds() {
        Area a = AreaRegistry.parse(arr(OK)).areas().get(0);
        assertTrue(a.contains("minecraft:overworld", 10, -60, 10)); // 角点含
        assertTrue(a.contains("minecraft:overworld", 40, 80, 40));
        assertFalse(a.contains("minecraft:overworld", 9.99, 0, 20)); // 界外
        assertFalse(a.contains("minecraft:the_nether", 20, 0, 20)); // 维度不符
        assertFalse(a.contains(null, 20, 0, 20));
    }

    @Test
    void rejectsDegenerateAreasAndBadIds() {
        // 零厚度区域 = 永不命中的"看不见的墙"：直接拒绝
        assertFalse(AreaRegistry.parse(
                        arr(
                                "[{\"id\":\"flat\",\"dim\":\"minecraft:overworld\",\"x1\":0,\"y1\":0,\"z1\":0,\"x2\":0,\"y2\":10,\"z2\":10}]"))
                .success());
        assertFalse(AreaRegistry.parse(
                        arr(
                                "[{\"id\":\"Bad-ID\",\"dim\":\"minecraft:overworld\",\"x1\":0,\"y1\":0,\"z1\":0,\"x2\":1,\"y2\":1,\"z2\":1}]"))
                .success());
        // 维度必须带命名空间
        assertFalse(AreaRegistry.parse(arr(
                        "[{\"id\":\"a\",\"dim\":\"overworld\",\"x1\":0,\"y1\":0,\"z1\":0,\"x2\":1,\"y2\":1,\"z2\":1}]"))
                .success());
        // 坐标非数字
        assertFalse(AreaRegistry.parse(
                        arr(
                                "[{\"id\":\"a\",\"dim\":\"minecraft:overworld\",\"x1\":\"nan\",\"y1\":0,\"z1\":0,\"x2\":1,\"y2\":1,\"z2\":1}]"))
                .success());
    }

    @Test
    void missingConfigIsEmptyNotError() {
        assertTrue(AreaRegistry.parse(null).success());
        assertTrue(AreaRegistry.parse(null).areas().isEmpty());
        assertFalse(AreaRegistry.parse(new com.google.gson.JsonObject()).success());
    }

    @Test
    void duplicateIdLastWinsAndLookupHelpersWork() {
        AreaRegistry.ParseResult r = AreaRegistry.parse(
                arr(
                        "[{\"id\":\"a\",\"dim\":\"minecraft:overworld\",\"x1\":0,\"y1\":0,\"z1\":0,\"x2\":1,\"y2\":1,\"z2\":1},"
                                + "{\"id\":\"a\",\"dim\":\"minecraft:overworld\",\"x1\":0,\"y1\":0,\"z1\":0,\"x2\":9,\"y2\":9,\"z2\":9}]"));
        assertTrue(r.success());
        assertEquals(1, r.areas().size());
        assertEquals(1, r.warnings().size());
        List<Area> areas = r.areas();
        assertTrue(AreaRegistry.find(areas, "a").isPresent());
        assertTrue(AreaRegistry.find(areas, "missing").isEmpty());
        Optional<Area> hit = AreaRegistry.at(areas, "minecraft:overworld", 8, 8, 8);
        assertTrue(hit.isPresent());
        assertTrue(AreaRegistry.at(areas, "minecraft:overworld", 100, 100, 100).isEmpty());
    }

    @Test
    void rejectsTooManyAreasAtBoundary() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i <= AreaRegistry.MAX_AREAS; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":\"a")
                    .append(i)
                    .append(
                            "\",\"dim\":\"minecraft:overworld\",\"x1\":0,\"y1\":0,\"z1\":0,\"x2\":1,\"y2\":1,\"z2\":1}");
        }
        sb.append(']');
        assertFalse(AreaRegistry.parse(arr(sb.toString())).success());
    }

    @Test
    void jsonRoundTripKeepsGeometry() {
        Area a = AreaRegistry.parse(arr(OK)).areas().get(0);
        AreaRegistry.ParseResult back = AreaRegistry.parse(
                JsonParser.parseString("[" + AreaRegistry.toJson(a).toString() + "]")
                        .getAsJsonArray());
        assertEquals(a, back.areas().get(0));
    }
}
