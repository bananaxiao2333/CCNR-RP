/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.variable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 变量注册表（纯逻辑，无 MC import，可脱机 JUnit 测）：解析/校验/查询/序列化 config/ccnr_rp/variables.json。
 *
 * <p>存在意义（替代 v2.24.0 的「弹头许可 + 区域」固定字段）：过去每加一种"许可/开关/参数"都要写一对
 * 阵营字段 + 一段 GUI + 一条命令 + 一个权限节点，而核弹这类**功能本体不在本 mod**的东西，
 * 真正需要本 mod 提供的只是一个「按 id 取值」的对外数据接口。现在统一成变量：
 * 管理员自建变量（类型 + 当前值 + 预设值），命令与 GUI 双通道 CRUD，外部功能按 id 只读取值。
 *
 * <p>解析策略与 {@code AttributeProfile} 一致：**全量校验，任何一条非法即整体拒绝**（不做部分提交），
 * 同 id 重复声明后者覆盖并记 WARN。
 */
public final class VariableRegistry {
    /** 变量数量上限（每次取值都线性扫描，故需有界）。 */
    public static final int MAX_VARIABLES = 128;
    /** 预设方案数量上限。 */
    public static final int MAX_SCHEMES = 32;
    /** 单个变量的预设值数量上限。 */
    public static final int MAX_PRESETS = 32;
    /** 文本值长度上限。 */
    public static final int MAX_VALUE_LEN = 256;
    /** 显示名/说明长度上限。 */
    public static final int MAX_NAME_LEN = 64;

    public static final int MAX_DESC_LEN = 256;

    /** id 与预设 id 通用格式：小写字母数字下划线点横线，1-64 字符。 */
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_.-]{1,64}");

    private VariableRegistry() {}

    /**
     * 解析结果：成功时 variables/schemes 可用；失败时 errors 非空（调用方拒绝落盘）。
     * warnings 用于"能被容忍但不该发生"的情况（重复声明覆盖、方案引用了不存在的变量等）。
     */
    public record ParseResult(
            List<Variable> variables, List<VariableScheme> schemes, List<String> errors, List<String> warnings) {
        public ParseResult {
            variables = variables == null ? List.of() : List.copyOf(variables);
            schemes = schemes == null ? List.of() : List.copyOf(schemes);
            errors = errors == null ? List.of() : List.copyOf(errors);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public boolean success() {
            return errors.isEmpty();
        }
    }

    /** 应用整套方案的结果：换值后的变量列表 + 被跳过的项说明（引用了不存在的变量等，不视为错误）。 */
    public record ApplyResult(List<Variable> variables, List<String> skipped) {
        public ApplyResult {
            variables = variables == null ? List.of() : List.copyOf(variables);
            skipped = skipped == null ? List.of() : List.copyOf(skipped);
        }
    }

    /** 解析 variables / schemes 两段（缺省或非数组 = 空，不算错误）。 */
    public static ParseResult parse(JsonElement variablesEl, JsonElement schemesEl) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Variable> vars = new LinkedHashMap<>();
        if (variablesEl != null && !variablesEl.isJsonNull()) {
            if (!variablesEl.isJsonArray()) {
                errors.add("variables 必须是数组");
            } else {
                JsonArray arr = variablesEl.getAsJsonArray();
                if (arr.size() > MAX_VARIABLES) {
                    errors.add("变量数量超上限（" + MAX_VARIABLES + "）：" + arr.size());
                } else {
                    for (int i = 0; i < arr.size(); i++) {
                        parseVariable(arr.get(i), i, vars, errors, warnings);
                    }
                }
            }
        }
        Map<String, VariableScheme> schemes = new LinkedHashMap<>();
        if (schemesEl != null && !schemesEl.isJsonNull()) {
            if (!schemesEl.isJsonArray()) {
                errors.add("schemes 必须是数组");
            } else {
                JsonArray arr = schemesEl.getAsJsonArray();
                if (arr.size() > MAX_SCHEMES) {
                    errors.add("预设方案数量超上限（" + MAX_SCHEMES + "）：" + arr.size());
                } else {
                    for (int i = 0; i < arr.size(); i++) {
                        parseScheme(arr.get(i), i, schemes, vars, errors, warnings);
                    }
                }
            }
        }
        return new ParseResult(new ArrayList<>(vars.values()), new ArrayList<>(schemes.values()), errors, warnings);
    }

    private static void parseVariable(
            JsonElement el, int index, Map<String, Variable> out, List<String> errors, List<String> warnings) {
        if (!el.isJsonObject()) {
            errors.add("variables[" + index + "] 不是对象");
            return;
        }
        JsonObject o = el.getAsJsonObject();
        String id = str(o, "id");
        if (id.isBlank()) {
            errors.add("variables[" + index + "] 缺少 id");
            return;
        }
        if (!validId(id)) {
            errors.add("variables[" + index + "] id 非法（小写字母/数字/下划线点横线，1-64）：" + id);
            return;
        }
        VariableType type = VariableType.parse(str(o, "type"));
        if (type == null) {
            errors.add("variables[" + index + "] type 非法（需 bool/number/text）：" + id);
            return;
        }
        String rawValue = str(o, "value");
        String value = type.normalize(rawValue);
        if (value == null) {
            errors.add("variables[" + index + "] 取值非法（" + type.label() + "）：" + id + " → " + rawValue);
            return;
        }
        List<Variable.Preset> presets = new ArrayList<>();
        if (o.has("presets") && !o.get("presets").isJsonNull()) {
            if (!o.get("presets").isJsonArray()) {
                errors.add("variables[" + index + "] presets 必须是数组：" + id);
                return;
            }
            JsonArray pa = o.getAsJsonArray("presets");
            if (pa.size() > MAX_PRESETS) {
                errors.add("variables[" + index + "] 预设值超上限（" + MAX_PRESETS + "）：" + id);
                return;
            }
            Map<String, Variable.Preset> merged = new LinkedHashMap<>();
            for (int j = 0; j < pa.size(); j++) {
                Variable.Preset p = parsePreset(id, pa.get(j), j, type, errors, warnings);
                if (p == null) {
                    return; // 预设非法 = 该变量整体拒绝（不做部分提交）
                }
                Variable.Preset prev = merged.put(p.id(), p);
                if (prev != null) {
                    warnings.add("变量 " + id + " 的预设值重复声明后者覆盖：" + p.id());
                }
            }
            presets = new ArrayList<>(merged.values());
        }
        String name = str(o, "name");
        String desc = str(o, "desc");
        if (name.length() > MAX_NAME_LEN) {
            errors.add("variables[" + index + "] name 超长（" + MAX_NAME_LEN + "）：" + id);
            return;
        }
        if (desc.length() > MAX_DESC_LEN) {
            errors.add("variables[" + index + "] desc 超长（" + MAX_DESC_LEN + "）：" + id);
            return;
        }
        Variable prev = out.put(id, new Variable(id, type, name, desc, value, presets));
        if (prev != null) {
            warnings.add("变量重复声明后者覆盖：" + id);
        }
    }

    private static Variable.Preset parsePreset(
            String varId, JsonElement el, int index, VariableType type, List<String> errors, List<String> warnings) {
        if (!el.isJsonObject()) {
            errors.add("变量 " + varId + " 的 presets[" + index + "] 不是对象");
            return null;
        }
        JsonObject o = el.getAsJsonObject();
        String pid = str(o, "id");
        if (pid.isBlank() || !validId(pid)) {
            errors.add("变量 " + varId + " 的预设 id 非法：" + pid);
            return null;
        }
        String raw = str(o, "value");
        String value = type.normalize(raw);
        if (value == null) {
            errors.add("变量 " + varId + " 的预设取值非法（" + type.label() + "）：" + pid + " → " + raw);
            return null;
        }
        String name = str(o, "name");
        if (name.length() > MAX_NAME_LEN) {
            errors.add("变量 " + varId + " 的预设 name 超长：" + pid);
            return null;
        }
        return new Variable.Preset(pid, name, value);
    }

    private static void parseScheme(
            JsonElement el,
            int index,
            Map<String, VariableScheme> out,
            Map<String, Variable> vars,
            List<String> errors,
            List<String> warnings) {
        if (!el.isJsonObject()) {
            errors.add("schemes[" + index + "] 不是对象");
            return;
        }
        JsonObject o = el.getAsJsonObject();
        String id = str(o, "id");
        if (id.isBlank() || !validId(id)) {
            errors.add("schemes[" + index + "] id 非法：" + id);
            return;
        }
        if (!o.has("values") || !o.get("values").isJsonObject()) {
            errors.add("schemes[" + index + "] 缺少 values 对象：" + id);
            return;
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("values").entrySet()) {
            String varId = e.getKey();
            if (!validId(varId)) {
                errors.add("方案 " + id + " 引用了非法变量 id：" + varId);
                return;
            }
            Variable v = vars.get(varId);
            if (v == null) {
                errors.add("方案 " + id + " 引用了不存在的变量：" + varId);
                return;
            }
            String raw = e.getValue().isJsonNull() ? "" : e.getValue().getAsString();
            String value = v.type().normalize(raw);
            if (value == null) {
                errors.add("方案 " + id + " 对 " + varId + " 的取值非法（" + v.type().label() + "）：" + raw);
                return;
            }
            values.put(varId, value);
        }
        String name = str(o, "name");
        if (name.length() > MAX_NAME_LEN) {
            errors.add("方案 name 超长：" + id);
            return;
        }
        VariableScheme prev = out.put(id, new VariableScheme(id, name, values));
        if (prev != null) {
            warnings.add("预设方案重复声明后者覆盖：" + id);
        }
    }

    /** 序列化单个变量（写盘/下发用）。 */
    public static JsonObject toJson(Variable v) {
        JsonObject o = new JsonObject();
        o.addProperty("id", v.id());
        o.addProperty("type", v.type().name().toLowerCase(java.util.Locale.ROOT));
        if (v.name() != null && !v.name().isBlank()) {
            o.addProperty("name", v.name());
        }
        if (v.desc() != null && !v.desc().isBlank()) {
            o.addProperty("desc", v.desc());
        }
        o.addProperty("value", v.value());
        if (!v.presets().isEmpty()) {
            JsonArray pa = new JsonArray();
            for (Variable.Preset p : v.presets()) {
                JsonObject po = new JsonObject();
                po.addProperty("id", p.id());
                if (p.name() != null && !p.name().isBlank()) {
                    po.addProperty("name", p.name());
                }
                po.addProperty("value", p.value());
                pa.add(po);
            }
            o.add("presets", pa);
        }
        return o;
    }

    public static JsonArray toJsonArray(List<Variable> variables) {
        JsonArray arr = new JsonArray();
        if (variables == null) {
            return arr;
        }
        for (Variable v : variables) {
            arr.add(toJson(v));
        }
        return arr;
    }

    /** 序列化单个预设方案。 */
    public static JsonObject schemeToJson(VariableScheme s) {
        JsonObject o = new JsonObject();
        o.addProperty("id", s.id());
        if (s.name() != null && !s.name().isBlank()) {
            o.addProperty("name", s.name());
        }
        JsonObject values = new JsonObject();
        for (Map.Entry<String, String> e : s.values().entrySet()) {
            values.addProperty(e.getKey(), e.getValue());
        }
        o.add("values", values);
        return o;
    }

    public static JsonArray schemesToJsonArray(List<VariableScheme> schemes) {
        JsonArray arr = new JsonArray();
        if (schemes == null) {
            return arr;
        }
        for (VariableScheme s : schemes) {
            arr.add(schemeToJson(s));
        }
        return arr;
    }

    /** 按 id 查变量（不存在返回空）。 */
    public static Optional<Variable> find(List<Variable> variables, String id) {
        if (variables == null || id == null || id.isBlank()) {
            return Optional.empty();
        }
        for (Variable v : variables) {
            if (v.id().equals(id)) {
                return Optional.of(v);
            }
        }
        return Optional.empty();
    }

    /** 按 id 查预设方案（不存在返回空）。 */
    public static Optional<VariableScheme> findScheme(List<VariableScheme> schemes, String id) {
        if (schemes == null || id == null || id.isBlank()) {
            return Optional.empty();
        }
        for (VariableScheme s : schemes) {
            if (s.id().equals(id)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    /**
     * 应用整套方案：把方案里列出的变量换成目标值，**未列出的变量保持原值**；
     * 引用了不存在的变量则跳过并记入 skipped（方案本身仍合法，便于先写方案后建变量）。
     * 纯函数：不改动入参列表。
     */
    public static ApplyResult applyScheme(List<Variable> variables, VariableScheme scheme) {
        List<Variable> out = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        if (variables == null) {
            return new ApplyResult(out, skipped);
        }
        for (Variable v : variables) {
            String target = scheme == null ? null : scheme.values().get(v.id());
            if (target == null) {
                out.add(v);
                continue;
            }
            String normalized = v.type().normalize(target);
            if (normalized == null) {
                skipped.add(v.id() + "（取值非法：" + target + "）");
                out.add(v);
            } else {
                out.add(v.withValue(normalized));
            }
        }
        if (scheme != null) {
            for (String varId : scheme.values().keySet()) {
                if (find(variables, varId).isEmpty()) {
                    skipped.add(varId + "（变量不存在）");
                }
            }
        }
        return new ApplyResult(out, skipped);
    }

    /** 校验 id 格式（命令/GUI 新建前用；与 parse 同一套规则）。 */
    public static boolean validId(String id) {
        return id != null && ID_PATTERN.matcher(id).matches();
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString().trim() : "";
    }
}
