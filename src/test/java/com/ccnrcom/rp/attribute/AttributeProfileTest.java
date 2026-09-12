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
    // ---------- 分层：角色（职业）层同 id 覆盖阵营层（docs/16 §2.4） ----------

    private static final String HP = "minecraft:generic.max_health";
    private static final String ARMOR = "minecraft:generic.armor";

    /** 职业层为空 → 全部继承阵营层，且没有任何"覆盖"。 */
    @Test
    void layerInheritsEverythingWhenProfessionHasNoAttributes() {
        List<Entry> fac = List.of(new Entry(HP, 40, Operation.ADD), new Entry(ARMOR, 4, Operation.ADD));
        AttributeProfile.Layered l = AttributeProfile.layer(fac, List.of());
        assertEquals(fac, l.effective());
        assertTrue(l.overriddenIds().isEmpty());
        assertFalse(l.isOverridden(HP));
    }

    /** 同 id 覆盖：职业层声明了 HP，阵营层的 HP 整条让位，生效值取职业层。 */
    @Test
    void professionOverridesSameIdOfFaction() {
        List<Entry> fac = List.of(new Entry(HP, 40, Operation.ADD), new Entry(ARMOR, 4, Operation.ADD));
        List<Entry> prof = List.of(new Entry(HP, 60, Operation.ADD));
        AttributeProfile.Layered l = AttributeProfile.layer(fac, prof);

        assertEquals(2, l.effective().size());
        assertTrue(l.isOverridden(HP));
        assertFalse(l.isOverridden(ARMOR), "职业层没声明的 id 不算覆盖");
        // 生效：血量 = 基数 20 + 职业层 60 = 80（阵营层的 40 已整条让位，否则会是 120）
        assertEquals(80.0, AttributeProfile.resolve(l.effective(), HP, 20.0));
        // 护甲照旧从阵营层继承：20 + 4
        assertEquals(24.0, AttributeProfile.resolve(l.effective(), ARMOR, 20.0));
    }

    /** 关键断言："含该 id 的全部运算"——阵营层同一 id 的多条不同运算也一并让位。 */
    @Test
    void overrideReplacesAllOperationsOfThatId() {
        List<Entry> fac = List.of(
                new Entry(HP, 40, Operation.ADD),
                new Entry(HP, 0.5, Operation.MULTIPLY_BASE),
                new Entry(HP, 0.25, Operation.MULTIPLY_TOTAL));
        List<Entry> prof = List.of(new Entry(HP, 100, Operation.ADD));
        AttributeProfile.Layered l = AttributeProfile.layer(fac, prof);

        assertEquals(1, l.effective().size(), "阵营层该 id 的三条运算条目必须全部让位");
        assertEquals(prof, l.effective());
        // (20 + 100) —— 阵营层的 0.5 / 0.25 乘算不再参与
        assertEquals(120.0, AttributeProfile.resolve(l.effective(), HP, 20.0));
    }

    /** 职业层声明的、阵营层没有的 id 属于"新增"，不计入覆盖集合（界面上不该标「覆盖」）。 */
    @Test
    void newIdInProfessionIsAdditionNotOverride() {
        List<Entry> fac = List.of(new Entry(HP, 40, Operation.ADD));
        List<Entry> prof = List.of(new Entry(ARMOR, 10, Operation.ADD));
        AttributeProfile.Layered l = AttributeProfile.layer(fac, prof);

        assertEquals(2, l.effective().size());
        assertTrue(l.overriddenIds().isEmpty());
        assertFalse(l.isOverridden(ARMOR));
    }

    /** 边界：两侧都为 null / 空 → 空结果、不抛异常。 */
    @Test
    void layerHandlesNullAndEmpty() {
        assertEquals(List.of(), AttributeProfile.layer(null, null).effective());
        assertEquals(List.of(), AttributeProfile.layer(List.of(), List.of()).effective());
        assertEquals(
                1,
                AttributeProfile.layer(List.of(new Entry(HP, 1, Operation.ADD)), null)
                        .effective()
                        .size());
    }

    /** 覆盖集合是多值（多个 id 同时被覆盖都被记下，供界面逐行标「覆盖」）。 */
    @Test
    void overrideSetCoversMultipleIds() {
        List<Entry> fac = List.of(new Entry(HP, 40, Operation.ADD), new Entry(ARMOR, 4, Operation.ADD));
        List<Entry> prof = List.of(new Entry(HP, 60, Operation.ADD), new Entry(ARMOR, 8, Operation.ADD));
        AttributeProfile.Layered l = AttributeProfile.layer(fac, prof);
        assertEquals(java.util.Set.of(HP, ARMOR), l.overriddenIds());
        assertEquals(2, l.effective().size());
    }
}
