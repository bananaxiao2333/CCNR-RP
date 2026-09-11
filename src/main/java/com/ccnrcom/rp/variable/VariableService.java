/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.variable;

import com.ccnrcom.rp.data.ConfigStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 变量服务（配置 IO + 只读缓存）：config/ccnr_rp/variables.json 的唯一读写入口，
 * 同时是本 mod 对外的**自定义设定读取接口**（外部功能按 id 取值，见 {@link #raw}/{@link #bool} 等）。
 *
 * <p>权威源是配置文件（经 {@link ConfigStore}：DB 启用时进 config_documents，否则原子写磁盘）；本类的
 * {@code variables}/{@code schemes} 只是**只读镜像缓存**，写入一律"读整份 → deepCopy → 改 → 全量校验 →
 * 落盘 → 换缓存"，不做第二权威源（docs/01 §9.3）。缓存失效点：每次写成功、{@code /rp var reload}、
 * 服务端启动重建。生命周期与其他服务一致：ServerAboutToStart 构造、ServerStopping 置空。
 *
 * <p>对外只读接口的稳定性承诺：变量 id 与类型由管理员定义，本类只提供"按 id 取当前值"的读取语义；
 * 外部功能不得写（写入只经命令/管理面板，走同一份校验）。
 */
public final class VariableService {
    private static final Logger LOGGER = LogManager.getLogger();

    /** 配置文件（config/ccnr_rp/variables.json）。 */
    public static final String FILE = "variables.json";
    /** 变量数组段名。 */
    public static final String KEY = "variables";
    /** 预设方案数组段名。 */
    public static final String KEY_SCHEMES = "schemes";

    private List<Variable> variables = List.of();
    private List<VariableScheme> schemes = List.of();

    public VariableService() {
        load();
    }

    /** 加载（文件缺失→写入内嵌默认样板；解析失败的条目跳过并记错误，服务继续）。 */
    private void load() {
        JsonObject root = ConfigStore.load(FILE).orElse(null);
        if (root == null) {
            root = com.ccnrcom.rp.util.JsonUtil.readResource("/assets/ccnr_rp/defaults/" + FILE)
                    .orElseGet(JsonObject::new);
            ConfigStore.save(FILE, root);
            LOGGER.info("[CCNR-RP] 已生成默认自定义设定配置: {}", FILE);
        }
        apply(root);
    }

    /** 从存储重读（命令/写成功后调用）。 */
    public void reload() {
        apply(ConfigStore.load(FILE).orElseGet(JsonObject::new));
    }

    /** 解析并换缓存。解析失败的条目跳过并记错误，服务继续（docs/01 §5 容错）。 */
    private void apply(JsonObject root) {
        VariableRegistry.ParseResult result = VariableRegistry.parse(root.get(KEY), root.get(KEY_SCHEMES));
        for (String e : result.errors()) {
            LOGGER.error("[CCNR-RP] 自定义设定配置错误（已跳过）：{}", e);
        }
        for (String w : result.warnings()) {
            LOGGER.warn("[CCNR-RP] 自定义设定配置：{}", w);
        }
        this.variables = result.variables();
        this.schemes = result.schemes();
        LOGGER.info("[CCNR-RP] 自定义设定载入：{} 个变量 / {} 套预设方案", variables.size(), schemes.size());
    }

    // ---------------- 查询（含对外只读接口） ----------------

    /** 当前变量列表（只读快照）。 */
    public List<Variable> variables() {
        return variables;
    }

    /** 当前预设方案列表（只读快照）。 */
    public List<VariableScheme> schemes() {
        return schemes;
    }

    public Optional<Variable> find(String id) {
        return VariableRegistry.find(variables, id);
    }

    public Optional<VariableScheme> findScheme(String id) {
        return VariableRegistry.findScheme(schemes, id);
    }

    /** 原始值（不存在返回 def）。命令 {@code /rp var get} 与管理面板预览共用。 */
    public String raw(String id, String def) {
        Variable v = find(id).orElse(null);
        return v == null ? def : v.value();
    }

    /** 按布尔语义取值（值非 true/false 时返回 def；类型不符也算未配置）。 */
    public boolean bool(String id, boolean def) {
        Variable v = find(id).orElse(null);
        if (v == null) {
            return def;
        }
        String n = VariableType.BOOL.normalize(v.value());
        return n == null ? def : Boolean.parseBoolean(n);
    }

    /** 按数值语义取值（非数值返回 def）。 */
    public double number(String id, double def) {
        Variable v = find(id).orElse(null);
        if (v == null) {
            return def;
        }
        try {
            double d = Double.parseDouble(v.value());
            return Double.isFinite(d) ? d : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 按文本语义取值（不存在返回 def）。 */
    public String text(String id, String def) {
        return raw(id, def);
    }

    // ---------------- 写入（全部"校验→落盘→换缓存"） ----------------

    /** 新建/覆盖变量（按 id upsert）。返回错误列表（空=成功）。 */
    public List<String> upsert(Variable variable) {
        if (variable == null || !VariableRegistry.validId(variable.id())) {
            return List.of("变量 id 非法（小写字母/数字/下划线点横线，1-64）");
        }
        String normalized = variable.type() == null ? null : variable.type().normalize(variable.value());
        if (normalized == null) {
            return List.of("变量取值非法（"
                    + (variable.type() == null ? "未知类型" : variable.type().label()) + "）：" + variable.value());
        }
        JsonObject candidate = candidateRoot();
        JsonArray arr = array(candidate, KEY);
        boolean replaced = false;
        Variable fixed = variable.withValue(normalized);
        for (int i = 0; i < arr.size(); i++) {
            if (arr.get(i).isJsonObject() && variable.id().equals(str(arr.get(i).getAsJsonObject(), "id"))) {
                arr.set(i, VariableRegistry.toJson(fixed));
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            if (variables.size() >= VariableRegistry.MAX_VARIABLES) {
                return List.of("变量数量已达上限（" + VariableRegistry.MAX_VARIABLES + "）");
            }
            arr.add(VariableRegistry.toJson(fixed));
        }
        return commit(candidate);
    }

    /** 删除变量（同时从所有预设方案里摘掉对该变量的引用，避免悬挂引用）。 */
    public List<String> delete(String id) {
        if (id == null || id.isBlank()) {
            return List.of("缺少变量 id");
        }
        if (find(id).isEmpty()) {
            return List.of("未找到变量: " + id);
        }
        JsonObject candidate = candidateRoot();
        JsonArray arr = array(candidate, KEY);
        for (int i = 0; i < arr.size(); i++) {
            if (arr.get(i).isJsonObject() && id.equals(str(arr.get(i).getAsJsonObject(), "id"))) {
                arr.remove(i);
                break;
            }
        }
        // 方案里指向该变量的条目一并摘除（否则整份配置会因"引用了不存在的变量"被拒绝落盘）
        JsonArray sa = array(candidate, KEY_SCHEMES);
        for (int i = 0; i < sa.size(); i++) {
            if (!sa.get(i).isJsonObject()) {
                continue;
            }
            JsonObject s = sa.get(i).getAsJsonObject();
            if (s.has("values") && s.get("values").isJsonObject()) {
                s.getAsJsonObject("values").remove(id);
            }
        }
        return commit(candidate);
    }

    /** 设置当前值（按变量自身类型归一化）。返回错误列表（空=成功）。 */
    public List<String> setValue(String id, String rawValue) {
        Variable v = find(id).orElse(null);
        if (v == null) {
            return List.of("未找到变量: " + id);
        }
        String normalized = v.type().normalize(rawValue);
        if (normalized == null) {
            return List.of("取值非法（" + v.type().label() + "）：" + rawValue);
        }
        return replaceVariable(v.withValue(normalized));
    }

    /** 新增/覆盖预设值（按预设 id upsert）。返回错误列表（空=成功）。 */
    public List<String> upsertPreset(String varId, Variable.Preset preset) {
        Variable v = find(varId).orElse(null);
        if (v == null) {
            return List.of("未找到变量: " + varId);
        }
        if (preset == null || !VariableRegistry.validId(preset.id())) {
            return List.of("预设 id 非法（小写字母/数字/下划线点横线，1-64）");
        }
        String normalized = v.type().normalize(preset.value());
        if (normalized == null) {
            return List.of("预设取值非法（" + v.type().label() + "）：" + preset.value());
        }
        Map<String, Variable.Preset> merged = new LinkedHashMap<>();
        for (Variable.Preset p : v.presets()) {
            merged.put(p.id(), p);
        }
        if (!merged.containsKey(preset.id()) && merged.size() >= VariableRegistry.MAX_PRESETS) {
            return List.of("预设值数量已达上限（" + VariableRegistry.MAX_PRESETS + "）");
        }
        merged.put(preset.id(), new Variable.Preset(preset.id(), preset.name(), normalized));
        return replaceVariable(v.withPresets(new ArrayList<>(merged.values())));
    }

    /** 删除单个预设值。返回错误列表（空=成功）。 */
    public List<String> deletePreset(String varId, String presetId) {
        return deletePresets(varId, List.of(presetId == null ? "" : presetId));
    }

    /**
     * 批量删除预设值（面板勾选后一次提交：只落盘一次，而不是每个 id 各写一遍配置）。
     * 返回错误列表（空=成功）——**只要有一个 id 不存在就整批拒绝**，不做部分删除（避免"以为删了其实没删"）。
     */
    public List<String> deletePresets(String varId, java.util.Collection<String> presetIds) {
        Variable v = find(varId).orElse(null);
        if (v == null) {
            return List.of("未找到变量: " + varId);
        }
        if (presetIds == null || presetIds.isEmpty()) {
            return List.of("未指定预设值");
        }
        List<String> missing = new ArrayList<>();
        for (String pid : presetIds) {
            if (v.preset(pid) == null) {
                missing.add(pid == null || pid.isBlank() ? "(空 id)" : pid);
            }
        }
        if (!missing.isEmpty()) {
            return List.of("未找到预设值: " + String.join(", ", missing));
        }
        List<Variable.Preset> kept = new ArrayList<>();
        for (Variable.Preset p : v.presets()) {
            if (!presetIds.contains(p.id())) {
                kept.add(p);
            }
        }
        return replaceVariable(v.withPresets(kept));
    }

    /** 应用单变量预设值（点击即切换）。返回错误列表（空=成功）。 */
    public List<String> applyPreset(String varId, String presetId) {
        Variable v = find(varId).orElse(null);
        if (v == null) {
            return List.of("未找到变量: " + varId);
        }
        Variable.Preset p = v.preset(presetId);
        if (p == null) {
            return List.of("未找到预设值: " + presetId);
        }
        return replaceVariable(v.withValue(p.value()));
    }

    /** 新建/覆盖整套预设方案（按 id upsert）。values 会按各变量类型归一化。 */
    public List<String> upsertScheme(VariableScheme scheme) {
        if (scheme == null || !VariableRegistry.validId(scheme.id())) {
            return List.of("方案 id 非法（小写字母/数字/下划线点横线，1-64）");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : scheme.values().entrySet()) {
            Variable v = find(e.getKey()).orElse(null);
            if (v == null) {
                return List.of("方案引用了不存在的变量: " + e.getKey());
            }
            String normalized = v.type().normalize(e.getValue());
            if (normalized == null) {
                return List.of("方案对 " + e.getKey() + " 的取值非法（" + v.type().label() + "）：" + e.getValue());
            }
            values.put(e.getKey(), normalized);
        }
        JsonObject candidate = candidateRoot();
        JsonArray arr = array(candidate, KEY_SCHEMES);
        VariableScheme fixed = new VariableScheme(scheme.id(), scheme.name(), values);
        boolean replaced = false;
        for (int i = 0; i < arr.size(); i++) {
            if (arr.get(i).isJsonObject() && scheme.id().equals(str(arr.get(i).getAsJsonObject(), "id"))) {
                arr.set(i, VariableRegistry.schemeToJson(fixed));
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            if (schemes.size() >= VariableRegistry.MAX_SCHEMES) {
                return List.of("预设方案数量已达上限（" + VariableRegistry.MAX_SCHEMES + "）");
            }
            arr.add(VariableRegistry.schemeToJson(fixed));
        }
        return commit(candidate);
    }

    /** 删除预设方案。返回错误列表（空=成功）。 */
    public List<String> deleteScheme(String id) {
        return deleteSchemes(List.of(id == null ? "" : id));
    }

    /** 批量删除预设方案（面板勾选后一次提交：只落盘一次）。任一项不存在即整批拒绝。 */
    public List<String> deleteSchemes(java.util.Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of("缺少方案 id");
        }
        List<String> missing = new ArrayList<>();
        for (String id : ids) {
            if (findScheme(id).isEmpty()) {
                missing.add(id == null || id.isBlank() ? "(空 id)" : id);
            }
        }
        if (!missing.isEmpty()) {
            return List.of("未找到方案: " + String.join(", ", missing));
        }
        JsonObject candidate = candidateRoot();
        JsonArray arr = array(candidate, KEY_SCHEMES);
        for (int i = arr.size() - 1; i >= 0; i--) {
            if (arr.get(i).isJsonObject() && ids.contains(str(arr.get(i).getAsJsonObject(), "id"))) {
                arr.remove(i);
            }
        }
        return commit(candidate);
    }

    /** 应用整套预设方案：未列出的变量保持原值。返回错误列表（空=成功；跳过项记 WARN）。 */
    public List<String> applyScheme(String id) {
        VariableScheme scheme = findScheme(id).orElse(null);
        if (scheme == null) {
            return List.of("未找到方案: " + id);
        }
        VariableRegistry.ApplyResult result = VariableRegistry.applyScheme(variables, scheme);
        for (String s : result.skipped()) {
            LOGGER.warn("[CCNR-RP] 预设方案 {} 跳过：{}", id, s);
        }
        JsonObject candidate = candidateRoot();
        candidate.add(KEY, VariableRegistry.toJsonArray(result.variables()));
        return commit(candidate);
    }

    /** 服务端停止时清空缓存（对称清理：静态态不跨世界残留）。 */
    public void clear() {
        this.variables = List.of();
        this.schemes = List.of();
    }

    // ---------------- 内部 ----------------

    /** 单变量替换（值/预设改动共用）：整体重写该变量条目后走同一提交路径。 */
    private List<String> replaceVariable(Variable updated) {
        JsonObject candidate = candidateRoot();
        JsonArray arr = array(candidate, KEY);
        for (int i = 0; i < arr.size(); i++) {
            if (arr.get(i).isJsonObject() && updated.id().equals(str(arr.get(i).getAsJsonObject(), "id"))) {
                arr.set(i, VariableRegistry.toJson(updated));
                return commit(candidate);
            }
        }
        return List.of("未找到变量: " + updated.id());
    }

    /** 读整份配置并深拷贝为候选（写入前状态，绝不直接改权威源）。 */
    private JsonObject candidateRoot() {
        JsonObject current = ConfigStore.load(FILE).orElseGet(JsonObject::new);
        JsonObject candidate = current.deepCopy();
        if (!candidate.has("version")) {
            candidate.addProperty("version", 1);
        }
        return candidate;
    }

    private static JsonArray array(JsonObject root, String key) {
        if (root.has(key) && root.get(key).isJsonArray()) {
            return root.getAsJsonArray(key);
        }
        JsonArray arr = new JsonArray();
        root.add(key, arr);
        return arr;
    }

    /** 全量校验候选（含既有项）→ 落盘 → 换缓存；任何一条非法都拒绝落盘，不做部分提交。 */
    private List<String> commit(JsonObject candidate) {
        VariableRegistry.ParseResult check = VariableRegistry.parse(candidate.get(KEY), candidate.get(KEY_SCHEMES));
        if (!check.success()) {
            return new ArrayList<>(check.errors());
        }
        if (!ConfigStore.save(FILE, candidate)) {
            return List.of("自定义设定配置写入失败");
        }
        reload();
        return List.of();
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
