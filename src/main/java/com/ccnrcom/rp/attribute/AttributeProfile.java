/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.attribute;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 阵营属性配置（纯逻辑，无 MC import，可脱机 JUnit 测）。
 *
 * <p>数据来源：factions.json 中每个阵营的可选字段 {@code attributes}：
 * <pre>{"id": "qdf", "attributes": [{"id": "minecraft:generic.max_health", "amount": 40, "operation": "add"}]}</pre>
 *
 * <p>属性 id 一律写**注册名**（namespace:path）——原版属性与 mod 属性同构：mod 注册的属性只要写对注册名即可生效，
 * 未注册的 id 在执行期跳过并 WARN（见 {@code PlayerAttributeBridge}）。这是"原版优先、mod 自然扩展"的解耦边界：
 * 本类不认识任何具体 mod，只认注册名与数值/运算。
 *
 * <p>为什么单独成纯类：属性数值/运算/校验是规则逻辑（可脱机测），MC 侧只剩"把解析结果写进玩家属性"的薄适配。
 */
public final class AttributeProfile {
    /** 血量属性注册名（部署满状态判定与 FirstAid 桥的翻译入口）。 */
    public static final String MAX_HEALTH = "minecraft:generic.max_health";

    /** 单阵营属性条目上限（防配置滥用导致每次部署无界循环）。 */
    public static final int MAX_ENTRIES = 16;

    /** 注册名格式（namespace:path，与原版 ResourceLocation 宽松规则一致）。 */
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private AttributeProfile() {}

    /** 修饰运算（与 MC {@code AttributeModifier.Operation} 一一对应；配置里写小写名）。 */
    public enum Operation {
        ADD("add"),
        MULTIPLY_BASE("multiply_base"),
        MULTIPLY_TOTAL("multiply_total");

        private final String id;

        Operation(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        /** 解析运算名（大小写无关）；未知返回 null（由调用方转成错误消息）。 */
        public static Operation parse(String s) {
            if (s == null) {
                return null;
            }
            String v = s.trim().toLowerCase(java.util.Locale.ROOT);
            for (Operation op : values()) {
                if (op.id.equals(v)) {
                    return op;
                }
            }
            return null;
        }
    }

    /** 单条属性修饰：目标注册名 + 数值 + 运算。 */
    public record Entry(String id, double amount, Operation operation) {}

    /** 解析结果：成功时 entries 可用；失败时 errors 非空（调用方拒绝落盘，不做部分提交）。 */
    public record ParseResult(List<Entry> entries, List<String> errors, List<String> warnings) {
        public ParseResult {
            entries = entries == null ? List.of() : List.copyOf(entries);
            errors = errors == null ? List.of() : List.copyOf(errors);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public boolean success() {
            return errors.isEmpty();
        }

        public static ParseResult failure(List<String> errors) {
            return new ParseResult(List.of(), errors, List.of());
        }
    }

    /**
     * 解析 {@code attributes} 数组（缺省/非数组 = 空配置，不算错误：阵营可以不配属性）。
     * 同一 (id, 运算) 重复声明时**后者覆盖**并记 WARN（与 ruleChange 同幕冲突覆盖的既有语义一致）。
     */
    public static ParseResult parse(JsonElement element) {
        List<Entry> entries = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (element == null || element.isJsonNull()) {
            return new ParseResult(entries, errors, warnings);
        }
        if (!element.isJsonArray()) {
            return ParseResult.failure(List.of("attributes 必须是数组"));
        }
        JsonArray arr = element.getAsJsonArray();
        if (arr.size() > MAX_ENTRIES) {
            return ParseResult.failure(List.of("attributes 条目过多（上限 " + MAX_ENTRIES + "）：" + arr.size()));
        }
        // 覆盖语义：LinkedHashMap 保序（写盘顺序 = 声明顺序），重复键原位覆盖
        Map<String, Entry> merged = new LinkedHashMap<>();
        for (int i = 0; i < arr.size(); i++) {
            JsonElement el = arr.get(i);
            if (!el.isJsonObject()) {
                errors.add("attributes[" + i + "] 不是对象");
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String id = o.has("id") && !o.get("id").isJsonNull()
                    ? o.get("id").getAsString().trim()
                    : "";
            if (id.isBlank()) {
                errors.add("attributes[" + i + "] 缺少 id");
                continue;
            }
            if (!ID_PATTERN.matcher(id).matches()) {
                errors.add("attributes[" + i + "] id 非法（需 namespace:path，小写）：" + id);
                continue;
            }
            double amount;
            try {
                amount = o.has("amount") ? o.get("amount").getAsDouble() : Double.NaN;
            } catch (Exception e) {
                errors.add("attributes[" + i + "] amount 不是数字：" + id);
                continue;
            }
            if (!Double.isFinite(amount)) {
                errors.add("attributes[" + i + "] amount 非法（需有限数字）：" + id);
                continue;
            }
            Operation op =
                    Operation.parse(o.has("operation") ? o.get("operation").getAsString() : "add");
            if (op == null) {
                errors.add("attributes[" + i + "] operation 非法（add/multiply_base/multiply_total）：" + id);
                continue;
            }
            if (MAX_HEALTH.equals(id) && op == Operation.ADD && amount <= 0) {
                errors.add("attributes[" + i + "] 血量加法必须为正数（下限 1 点）：" + id);
                continue;
            }
            String key = id + "|" + op.id();
            Entry prev = merged.put(key, new Entry(id, amount, op));
            if (prev != null) {
                warnings.add("attributes 重复声明后者覆盖：" + id + " (" + op.id() + ")");
            }
        }
        return new ParseResult(new ArrayList<>(merged.values()), errors, warnings);
    }

    /** 序列化为配置 JSON 数组（写盘用；空列表返回空数组，保持字段存在以便管理界面回显）。 */
    public static JsonArray toJson(List<Entry> entries) {
        JsonArray arr = new JsonArray();
        if (entries == null) {
            return arr;
        }
        for (Entry e : entries) {
            JsonObject o = new JsonObject();
            o.addProperty("id", e.id());
            o.addProperty("amount", e.amount());
            o.addProperty("operation", e.operation().id());
            arr.add(o);
        }
        return arr;
    }

    /** 是否配置了该属性（部署时用于判定"是否需要处理满状态"）。 */
    public static boolean has(List<Entry> entries, String attributeId) {
        if (entries == null || attributeId == null) {
            return false;
        }
        for (Entry e : entries) {
            if (attributeId.equals(e.id())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 分层合并结果：生效条目 + **被职业层覆盖掉的属性 id**（后者供管理界面标「覆盖」/「已覆盖阵营」）。
     *
     * @param effective     实际套用到玩家的条目（阵营未覆盖者在前、职业条目在后，便于界面回显与排查）
     * @param overriddenIds 同时存在于阵营层与职业层、最终由职业层生效的 id（= 两层 id 的交集）
     */
    public record Layered(List<Entry> effective, java.util.Set<String> overriddenIds) {
        public Layered {
            effective = effective == null ? List.of() : List.copyOf(effective);
            overriddenIds = overriddenIds == null ? java.util.Set.of() : java.util.Set.copyOf(overriddenIds);
        }

        /** 该属性 id 是否被职业层覆盖（管理界面的标记判据）。 */
        public boolean isOverridden(String attributeId) {
            return attributeId != null && overriddenIds.contains(attributeId);
        }
    }

    /**
     * 层级合并（纯逻辑）：**职业层覆盖阵营层，判定键 = 属性 id**。
     *
     * <p><b>规则</b>：只要职业层声明了某个属性 id，阵营层该 id 的**全部运算条目**都被取代——
     * 不是按 (id+运算) 逐条覆盖。理由：'血量'这类语义下把两层的不同运算叠在一起既没意义也难解释，
     * 而"同 id 整体覆盖"是管理员最容易预期的行为（用户定调）。
     *
     * <p>职业层没声明的 id 照旧从阵营层继承；职业层新声明的 id 属于"新增"而非覆盖
     * （不计入 {@link Layered#overriddenIds}）。
     */
    public static Layered layer(List<Entry> factionEntries, List<Entry> professionEntries) {
        List<Entry> fac = factionEntries == null ? List.of() : factionEntries;
        List<Entry> prof = professionEntries == null ? List.of() : professionEntries;
        if (prof.isEmpty()) {
            return new Layered(fac, java.util.Set.of());
        }
        java.util.Set<String> profIds = new java.util.LinkedHashSet<>();
        for (Entry e : prof) {
            profIds.add(e.id());
        }
        List<Entry> out = new ArrayList<>();
        java.util.Set<String> overridden = new java.util.LinkedHashSet<>();
        for (Entry e : fac) {
            if (profIds.contains(e.id())) {
                overridden.add(e.id()); // 该 id 由职业层接管：阵营层条目整体让位
            } else {
                out.add(e);
            }
        }
        out.addAll(prof);
        return new Layered(out, overridden);
    }
    /**
     * 按原版结算顺序计算 base 基值经过本配置后的最终值（纯函数，供单测与 FirstAid 血量换算复用）：
     * {@code v = (base + Σadd) * (1 + Σmultiply_base) * Π(1 + multiply_total_i)}。
     * 未配置该属性时返回 base 原值。
     */
    public static double resolve(List<Entry> entries, String attributeId, double base) {
        if (entries == null || attributeId == null) {
            return base;
        }
        double add = 0;
        double multBase = 0;
        double multTotal = 1;
        boolean found = false;
        for (Entry e : entries) {
            if (!attributeId.equals(e.id())) {
                continue;
            }
            found = true;
            switch (e.operation()) {
                case ADD -> add += e.amount();
                case MULTIPLY_BASE -> multBase += e.amount();
                case MULTIPLY_TOTAL -> multTotal *= 1.0 + e.amount();
            }
        }
        if (!found) {
            return base;
        }
        return (base + add) * (1.0 + multBase) * multTotal;
    }
}
