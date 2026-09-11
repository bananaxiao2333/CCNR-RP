/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.variable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccnrcom.rp.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 自定义设定变量注册表回归测试（纯逻辑，无需 MC 运行时）：
 * 解析/校验（类型、id、上限、预设、方案引用）、归一化、序列化往返、预设与整套方案的应约语义。
 */
class VariableRegistryTest {

    /** 构造 {"variables":[...],"schemes":[...]} 载荷。 */
    private static VariableRegistry.ParseResult parse(JsonArray vars, JsonArray schemes) {
        JsonObject root = new JsonObject();
        if (vars != null) {
            root.add("variables", vars);
        }
        if (schemes != null) {
            root.add("schemes", schemes);
        }
        return VariableRegistry.parse(root.get("variables"), root.get("schemes"));
    }

    private static JsonObject var(String id, String type, String value) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("type", type);
        o.addProperty("value", value);
        return o;
    }

    private static JsonObject preset(String id, String value) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("value", value);
        return o;
    }

    // ---------------- 解析与校验 ----------------

    @Test
    void parsesThreeTypesAndKeepsOrder() {
        JsonArray vars = new JsonArray();
        vars.add(var("sample_permit", "bool", "true"));
        vars.add(var("mission_count", "number", "3"));
        vars.add(var("sample_zone", "text", "reactor"));
        VariableRegistry.ParseResult r = parse(vars, null);
        assertTrue(r.success(), () -> r.errors().toString());
        assertEquals(3, r.variables().size());
        assertEquals("sample_permit", r.variables().get(0).id());
        assertEquals(VariableType.BOOL, r.variables().get(0).type());
        assertEquals(VariableType.NUMBER, r.variables().get(1).type());
        assertEquals(VariableType.TEXT, r.variables().get(2).type());
        assertEquals("reactor", r.variables().get(2).value());
    }

    @Test
    void rejectsUnknownTypeAndIllegalId() {
        JsonArray vars = new JsonArray();
        vars.add(var("ok", "flag", "true"));
        vars.add(var("Bad-Id", "bool", "true"));
        VariableRegistry.ParseResult r = parse(vars, null);
        assertFalse(r.success());
        assertEquals(2, r.errors().size(), () -> r.errors().toString());
    }

    @Test
    void rejectsValueMismatchingType() {
        JsonArray vars = new JsonArray();
        vars.add(var("a", "number", "not-a-number"));
        vars.add(var("b", "bool", "maybe"));
        VariableRegistry.ParseResult r = parse(vars, null);
        assertFalse(r.success());
        assertEquals(2, r.errors().size(), () -> r.errors().toString());
    }

    @Test
    void rejectsTooManyVariables() {
        JsonArray vars = new JsonArray();
        for (int i = 0; i <= VariableRegistry.MAX_VARIABLES; i++) {
            vars.add(var("v" + i, "bool", "false"));
        }
        VariableRegistry.ParseResult r = parse(vars, null);
        assertFalse(r.success());
        assertTrue(r.errors().get(0).contains("超上限"), () -> r.errors().toString());
    }

    @Test
    void duplicateVariableIdLastWinsWithWarning() {
        JsonArray vars = new JsonArray();
        vars.add(var("dup", "bool", "true"));
        vars.add(var("dup", "bool", "false"));
        VariableRegistry.ParseResult r = parse(vars, null);
        assertTrue(r.success(), () -> r.errors().toString());
        assertEquals(1, r.variables().size());
        assertEquals("false", r.variables().get(0).value());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("重复声明")), () -> r.warnings()
                .toString());
    }

    @Test
    void missingVariablesSectionIsEmptyNotError() {
        VariableRegistry.ParseResult r = parse(null, null);
        assertTrue(r.success());
        assertTrue(r.variables().isEmpty());
        assertTrue(r.schemes().isEmpty());
    }

    @Test
    void nonArrayVariablesIsError() {
        JsonObject root = new JsonObject();
        root.addProperty("variables", "nope");
        VariableRegistry.ParseResult r = VariableRegistry.parse(root.get("variables"), null);
        assertFalse(r.success());
        assertTrue(r.errors().get(0).contains("必须是数组"));
    }

    // ---------------- 类型归一化 ----------------

    @Test
    void boolAcceptsCommonSpellings() {
        assertEquals("true", VariableType.BOOL.normalize("ON"));
        assertEquals("true", VariableType.BOOL.normalize(" yes "));
        assertEquals("true", VariableType.BOOL.normalize("1"));
        assertEquals("false", VariableType.BOOL.normalize("off"));
        assertEquals("false", VariableType.BOOL.normalize("NO"));
        assertEquals("false", VariableType.BOOL.normalize("0"));
        assertNull(VariableType.BOOL.normalize("maybe"));
        assertNull(VariableType.BOOL.normalize(null));
    }

    @Test
    void numberNormalizesIntegralAndKeepsDecimals() {
        assertEquals("42", VariableType.NUMBER.normalize("42.0"));
        assertEquals("42", VariableType.NUMBER.normalize(" 42 "));
        assertEquals("2.5", VariableType.NUMBER.normalize("2.5"));
        assertEquals("-7", VariableType.NUMBER.normalize("-7"));
        assertNull(VariableType.NUMBER.normalize("abc"));
        assertNull(VariableType.NUMBER.normalize("NaN"));
        assertNull(VariableType.NUMBER.normalize("Infinity"));
    }

    @Test
    void textTrimsAndRejectsOverlong() {
        assertEquals("hello", VariableType.TEXT.normalize("  hello  "));
        assertEquals("", VariableType.TEXT.normalize("   "));
        assertNull(VariableType.TEXT.normalize("x".repeat(VariableRegistry.MAX_VALUE_LEN + 1)));
    }

    @Test
    void typeParseIsCaseInsensitiveAndNullSafe() {
        assertEquals(VariableType.BOOL, VariableType.parse(" Bool "));
        assertEquals(VariableType.NUMBER, VariableType.parse("NUMBER"));
        assertEquals(VariableType.TEXT, VariableType.parse("text"));
        assertNull(VariableType.parse("int"));
        assertNull(VariableType.parse(null));
    }

    // ---------------- 预设值 ----------------

    @Test
    void parsesPresetsAndNormalizesThem() {
        JsonObject v = var("sample_permit", "bool", "false");
        JsonArray ps = new JsonArray();
        ps.add(preset("on", "true"));
        ps.add(preset("off", "no"));
        v.add("presets", ps);
        JsonArray vars = new JsonArray();
        vars.add(v);
        VariableRegistry.ParseResult r = parse(vars, null);
        assertTrue(r.success(), () -> r.errors().toString());
        List<Variable.Preset> list = r.variables().get(0).presets();
        assertEquals(2, list.size());
        assertEquals("true", list.get(0).value());
        assertEquals("false", list.get(1).value(), "预设值同样要按类型归一化");
    }

    @Test
    void rejectsPresetWithIllegalValue() {
        JsonObject v = var("n", "number", "1");
        JsonArray ps = new JsonArray();
        ps.add(preset("bad", "xyz"));
        v.add("presets", ps);
        JsonArray vars = new JsonArray();
        vars.add(v);
        VariableRegistry.ParseResult r = parse(vars, null);
        assertFalse(r.success());
        assertTrue(r.errors().get(0).contains("预设取值非法"), () -> r.errors().toString());
    }

    @Test
    void rejectsTooManyPresets() {
        JsonObject v = var("n", "bool", "true");
        JsonArray ps = new JsonArray();
        for (int i = 0; i <= VariableRegistry.MAX_PRESETS; i++) {
            ps.add(preset("p" + i, "true"));
        }
        v.add("presets", ps);
        JsonArray vars = new JsonArray();
        vars.add(v);
        VariableRegistry.ParseResult r = parse(vars, null);
        assertFalse(r.success());
        assertTrue(r.errors().get(0).contains("预设值超上限"));
    }

    // ---------------- 整套预设方案 ----------------

    @Test
    void parsesSchemeAndValidatesReferencedVariable() {
        JsonArray vars = new JsonArray();
        vars.add(var("a", "bool", "false"));
        JsonArray schemes = new JsonArray();
        JsonObject s = new JsonObject();
        s.addProperty("id", "drill");
        s.addProperty("name", "演习");
        JsonObject values = new JsonObject();
        values.addProperty("a", "true");
        s.add("values", values);
        schemes.add(s);
        VariableRegistry.ParseResult r = parse(vars, schemes);
        assertTrue(r.success(), () -> r.errors().toString());
        assertEquals(1, r.schemes().size());
        assertEquals("true", r.schemes().get(0).values().get("a"));
    }

    @Test
    void rejectsSchemeReferencingMissingVariable() {
        JsonArray vars = new JsonArray();
        vars.add(var("a", "bool", "false"));
        JsonArray schemes = new JsonArray();
        JsonObject s = new JsonObject();
        s.addProperty("id", "drill");
        JsonObject values = new JsonObject();
        values.addProperty("ghost", "true");
        s.add("values", values);
        schemes.add(s);
        VariableRegistry.ParseResult r = parse(vars, schemes);
        assertFalse(r.success());
        assertTrue(r.errors().get(0).contains("不存在的变量"), () -> r.errors().toString());
    }

    @Test
    void rejectsSchemeWithTypeMismatchedValue() {
        JsonArray vars = new JsonArray();
        vars.add(var("count", "number", "1"));
        JsonArray schemes = new JsonArray();
        JsonObject s = new JsonObject();
        s.addProperty("id", "bad");
        JsonObject values = new JsonObject();
        values.addProperty("count", "many");
        s.add("values", values);
        schemes.add(s);
        VariableRegistry.ParseResult r = parse(vars, schemes);
        assertFalse(r.success());
        assertTrue(r.errors().get(0).contains("取值非法"), () -> r.errors().toString());
    }

    @Test
    void applySchemeChangesListedVariablesOnly() {
        Variable a = new Variable("a", VariableType.BOOL, "", "", "false", List.of());
        Variable b = new Variable("b", VariableType.NUMBER, "", "", "5", List.of());
        VariableScheme scheme = new VariableScheme("s", "", java.util.Map.of("a", "true"));
        VariableRegistry.ApplyResult res = VariableRegistry.applyScheme(List.of(a, b), scheme);
        assertEquals("true", res.variables().get(0).value());
        assertEquals("5", res.variables().get(1).value(), "方案未列出的变量必须保持原值");
        assertTrue(res.skipped().isEmpty());
        assertEquals("false", a.value(), "applyScheme 不得改动入参");
    }

    @Test
    void applySchemeSkipsMissingVariableAndReportsIt() {
        Variable a = new Variable("a", VariableType.BOOL, "", "", "false", List.of());
        VariableScheme scheme = new VariableScheme("s", "", java.util.Map.of("ghost", "true"));
        VariableRegistry.ApplyResult res = VariableRegistry.applyScheme(List.of(a), scheme);
        assertEquals("false", res.variables().get(0).value());
        assertEquals(1, res.skipped().size());
        assertTrue(res.skipped().get(0).contains("ghost"));
    }

    // ---------------- 序列化往返 ----------------

    @Test
    void jsonRoundTripPreservesEverything() {
        JsonArray vars = new JsonArray();
        JsonObject v = var("sample_permit", "bool", "true");
        v.addProperty("name", "弹头许可");
        v.addProperty("desc", "外部功能读取");
        JsonArray ps = new JsonArray();
        JsonObject p = preset("off", "false");
        p.addProperty("name", "关闭");
        ps.add(p);
        v.add("presets", ps);
        vars.add(v);

        VariableRegistry.ParseResult first = parse(vars, null);
        assertTrue(first.success(), () -> first.errors().toString());
        JsonArray again = VariableRegistry.toJsonArray(first.variables());
        VariableRegistry.ParseResult second = parse(again, null);
        assertTrue(second.success(), () -> second.errors().toString());
        Variable a = first.variables().get(0);
        Variable b = second.variables().get(0);
        assertEquals(a.id(), b.id());
        assertEquals(a.type(), b.type());
        assertEquals(a.value(), b.value());
        assertEquals(a.name(), b.name());
        assertEquals(a.desc(), b.desc());
        assertEquals(a.presets().size(), b.presets().size());
        assertEquals(a.presets().get(0).value(), b.presets().get(0).value());
    }

    @Test
    void schemeRoundTripKeepsValues() {
        JsonArray vars = new JsonArray();
        vars.add(var("a", "bool", "false"));
        VariableRegistry.ParseResult first = parse(vars, null);
        JsonObject s = new JsonObject();
        s.addProperty("id", "drill");
        s.addProperty("name", "演习");
        JsonObject values = new JsonObject();
        values.addProperty("a", "true");
        s.add("values", values);
        JsonArray schemes = new JsonArray();
        schemes.add(s);
        VariableRegistry.ParseResult parsed = parse(vars, schemes);
        assertTrue(parsed.success(), () -> parsed.errors().toString());
        JsonArray out = VariableRegistry.schemesToJsonArray(parsed.schemes());
        VariableRegistry.ParseResult back = parse(vars, out);
        assertTrue(back.success(), () -> back.errors().toString());
        assertEquals("true", back.schemes().get(0).values().get("a"));
    }

    @Test
    void findAndFindSchemeByld() {
        Variable a = new Variable("a", VariableType.BOOL, "", "", "true", List.of());
        VariableScheme s = new VariableScheme("s", "", java.util.Map.of("a", "false"));
        assertTrue(VariableRegistry.find(List.of(a), "a").isPresent());
        assertTrue(VariableRegistry.find(List.of(a), "missing").isEmpty());
        assertTrue(VariableRegistry.find(List.of(a), null).isEmpty());
        assertTrue(VariableRegistry.findScheme(List.of(s), "s").isPresent());
        assertTrue(VariableRegistry.findScheme(List.of(s), "nope").isEmpty());
    }

    @Test
    void validIdRules() {
        assertTrue(VariableRegistry.validId("sample_permit"));
        assertTrue(VariableRegistry.validId("a.b-c_1"));
        assertFalse(VariableRegistry.validId("Bad"));
        assertFalse(VariableRegistry.validId("has space"));
        assertFalse(VariableRegistry.validId(""));
        assertFalse(VariableRegistry.validId(null));
        assertFalse(VariableRegistry.validId("x".repeat(65)));
    }

    @Test
    void parseAcceptsJsonTextViaUtil() {
        // 与配置文件同路径：Gson 文本 → 解析（守护"落盘格式可回读"）
        String json = "{\"variables\":[{\"id\":\"v1\",\"type\":\"text\",\"value\":\"hello\"}],\"schemes\":[]}";
        JsonObject root = JsonUtil.GSON.fromJson(json, JsonObject.class);
        VariableRegistry.ParseResult r = VariableRegistry.parse(root.get("variables"), root.get("schemes"));
        assertTrue(r.success(), () -> r.errors().toString());
        assertEquals("hello", r.variables().get(0).value());
    }
}
