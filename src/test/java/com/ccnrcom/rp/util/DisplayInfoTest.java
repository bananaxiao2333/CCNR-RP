/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** 对局状态展示三件套（显示名/描述/图标）：解析、回退、roundtrip 与非法输入边界。 */
class DisplayInfoTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void parsesAllThreeFields() {
        DisplayInfo d = DisplayInfo.parse(json("{\"name\":\"收容失效\",\"desc\":\"设施封锁\",\"icon\":\"shield\"}"));
        assertEquals("收容失效", d.name());
        assertEquals("设施封锁", d.desc());
        assertEquals("shield", d.icon());
        assertTrue(d.hasIcon());
        assertTrue(d.hasDesc());
    }

    /** 空显示名回退 id——界面永远不该露空串。 */
    @Test
    void blankNameFallsBackToFallback() {
        assertEquals("qdf_support", DisplayInfo.EMPTY.nameOr("qdf_support"));
        assertEquals(
                "qdf_support", DisplayInfo.parse(json("{\"name\":\"   \"}")).nameOr("qdf_support"));
        assertEquals("qdf_support", DisplayInfo.parse(json("{}")).nameOr("qdf_support"));
    }

    /** 有显示名就用显示名，不回退。 */
    @Test
    void configuredNameWins() {
        assertEquals("QDF 支援", DisplayInfo.parse(json("{\"name\":\"QDF 支援\"}")).nameOr("qdf_support"));
    }

    /** 非法输入边界：null / 缺字段 / 显式 json null → 不抛异常，按未配置处理。 */
    @Test
    void malformedInputIsTreatedAsUnset() {
        assertEquals(DisplayInfo.EMPTY, DisplayInfo.parse(null));
        DisplayInfo none = DisplayInfo.parse(json("{}"));
        assertEquals("", none.name());
        assertEquals("", none.desc());
        assertFalse(none.hasIcon());
        assertFalse(none.hasDesc());
        DisplayInfo explicitNull = DisplayInfo.parse(json("{\"name\":null,\"desc\":null,\"icon\":null}"));
        assertEquals(DisplayInfo.EMPTY, explicitNull);
        // nameOr 的 fallback 为 null 也不能炸
        assertEquals("", DisplayInfo.EMPTY.nameOr(null));
    }

    /**
     * 非字符串标量按 Gson 语义转成字面文本（与仓库既有 `str()` 助手同一行为，保持一致），
     * 且**未知图标名不会让渲染崩**——{@code RpIcons.iconPolygon} 的 default 分支回退六边形。
     */
    @Test
    void scalarValuesCoerceToLiteralText() {
        DisplayInfo d = DisplayInfo.parse(json("{\"name\":12,\"icon\":true}"));
        assertEquals("12", d.name());
        assertEquals("true", d.icon());
        assertTrue(d.hasIcon());
    }

    @Test
    void toJsonRoundTripsAndKeepsEmptyKeys() {
        DisplayInfo d = new DisplayInfo("定时疏散", "人数达标开局", "img:badge_a");
        DisplayInfo back = DisplayInfo.parse(d.toJson());
        assertEquals(d, back);
        // 三个键都写（便于管理面板回显与手改）
        assertTrue(d.toJson().has("name"));
        assertTrue(d.toJson().has("desc"));
        assertTrue(d.toJson().has("icon"));
        assertTrue(DisplayInfo.EMPTY.toJson().has("icon"));
    }
}
