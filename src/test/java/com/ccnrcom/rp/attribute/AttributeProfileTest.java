/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.attribute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccnrcom.rp.attribute.AttributeProfile.Entry;
import com.ccnrcom.rp.attribute.AttributeProfile.Operation;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 阵营属性配置解析/校验/结算（issue #2）：正常路径 + 非法输入 + 边界。 */
class AttributeProfileTest {

    private static JsonArray arr(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }

    @Test
    void parsesEntriesAndDefaultsOperationToAdd() {
        AttributeProfile.ParseResult r =
                AttributeProfile.parse(arr("[{\"id\":\"minecraft:generic.max_health\",\"amount\":40},"
                        + "{\"id\":\"minecraft:generic.armor\",\"amount\":0.25,\"operation\":\"multiply_total\"}]"));
        assertTrue(r.success(), r.errors().toString());
        assertEquals(2, r.entries().size());
        assertEquals(Operation.ADD, r.entries().get(0).operation());
        assertEquals(Operation.MULTIPLY_TOTAL, r.entries().get(1).operation());
        assertEquals(40.0, r.entries().get(0).amount(), 1e-9);
    }

    @Test
    void missingOrNonArrayIsEmptyConfigNotError() {
        assertTrue(AttributeProfile.parse(null).success());
        assertTrue(AttributeProfile.parse(null).entries().isEmpty());
        JsonObject obj = new JsonObject();
        assertFalse(AttributeProfile.parse(obj).success()); // 非数组：明确报错（配置写错了要看得见）
        assertTrue(AttributeProfile.parse(new JsonArray()).success());
    }

    @Test
    void resolveFollowsVanillaOrderAddThenBaseThenTotal() {
        List<Entry> entries = List.of(
                new Entry("minecraft:generic.max_health", 10, Operation.ADD),
                new Entry("minecraft:generic.max_health", 0.5, Operation.MULTIPLY_BASE),
                new Entry("minecraft:generic.max_health", 0.2, Operation.MULTIPLY_TOTAL));
        // (20+10) * (1+0.5) * (1+0.2) = 54
        assertEquals(54.0, AttributeProfile.resolve(entries, "minecraft:generic.max_health", 20.0), 1e-9);
        // 未配置的属性返回基值原样
        assertEquals(20.0, AttributeProfile.resolve(entries, "minecraft:generic.armor", 20.0), 1e-9);
    }

    @Test
    void rejectsMalformedIdsOperationsAndAmounts() {
        assertFalse(AttributeProfile.parse(arr("[{\"id\":\"\",\"amount\":1}]")).success());
        assertFalse(AttributeProfile.parse(arr("[{\"id\":\"no_namespace\",\"amount\":1}]"))
                .success());
        assertFalse(AttributeProfile.parse(arr("[{\"id\":\"minecraft:generic.max_health\"}]"))
                .success());
        assertFalse(AttributeProfile.parse(
                        arr("[{\"id\":\"minecraft:generic.armor\",\"amount\":1,\"operation\":\"plus\"}]"))
                .success());
        assertFalse(AttributeProfile.parse(arr("[{\"id\":\"minecraft:generic.armor\",\"amount\":\"abc\"}]"))
                .success());
        assertFalse(AttributeProfile.parse(arr("[123]")).success());
    }

    @Test
    void rejectsNonPositiveAdditiveHealth() {
        // 边界：血量用加法时必须为正（否则会把上限改成 0 或负数）
        assertFalse(AttributeProfile.parse(
                        arr("[{\"id\":\"minecraft:generic.max_health\",\"amount\":0,\"operation\":\"add\"}]"))
                .success());
        assertTrue(AttributeProfile.parse(arr(
                        "[{\"id\":\"minecraft:generic.max_health\",\"amount\":-0.5,\"operation\":\"multiply_total\"}]"))
                .success());
    }

    @Test
    void duplicateDeclarationLastWinsWithWarning() {
        AttributeProfile.ParseResult r =
                AttributeProfile.parse(arr("[{\"id\":\"minecraft:generic.armor\",\"amount\":1},"
                        + "{\"id\":\"minecraft:generic.armor\",\"amount\":3}]"));
        assertTrue(r.success());
        assertEquals(1, r.entries().size());
        assertEquals(3.0, r.entries().get(0).amount(), 1e-9);
        assertEquals(1, r.warnings().size());
    }

    @Test
    void rejectsTooManyEntriesAtBoundary() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i <= AttributeProfile.MAX_ENTRIES; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":\"minecraft:generic.armor\",\"amount\":")
                    .append(i)
                    .append('}');
        }
        sb.append(']');
        assertFalse(AttributeProfile.parse(arr(sb.toString())).success());
    }

    @Test
    void jsonRoundTripKeepsEntries() {
        List<Entry> entries = List.of(new Entry("minecraft:generic.max_health", 30, Operation.ADD));
        AttributeProfile.ParseResult back = AttributeProfile.parse(AttributeProfile.toJson(entries));
        assertTrue(back.success());
        assertEquals(entries, back.entries());
        assertTrue(AttributeProfile.has(entries, "minecraft:generic.max_health"));
        assertFalse(AttributeProfile.has(entries, "minecraft:generic.armor"));
    }
}
