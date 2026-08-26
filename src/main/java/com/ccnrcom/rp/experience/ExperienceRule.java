/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.experience;

import com.ccnrcom.rp.experience.ExprParser.Kind;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 经验规则（纯逻辑）：订阅事件 + 判断表达式（可选）+ 数值表达式 + 标题表达式。
 * 保存/加载时校验：eventId 存在、三个表达式语法与类型合法；坏规则跳过并给出原因。
 */
public record ExperienceRule(
        String id, boolean enabled, String eventId, String conditionExpr, String valueExpr, String titleExpr) {

    /** 默认规则（首次启动写盘；语义贴近 v2 默认体验：值班/击杀/阵亡）。 */
    public static final List<ExperienceRule> DEFAULT_RULES = List.of(
            new ExperienceRule("alive_duty", true, "character_alive", "", "1", "值班"),
            new ExperienceRule("kill_bonus", true, "character_kill", "", "50", "\"击杀 \" + victimType"),
            new ExperienceRule("death_penalty", true, "character_death", "", "-10", "阵亡"));

    /** 校验单条规则；返回错误列表（空 = 合法）。错误消息用于管理面板回显与日志。 */
    public static List<String> validate(ExperienceRule r) {
        List<String> errs = new ArrayList<>();
        if (r.id() == null || r.id().isBlank()) {
            errs.add("规则 id 不能为空");
        }
        if (r.eventId() == null || !ExperienceEventRegistry.exists(r.eventId())) {
            errs.add("未知事件: " + r.eventId());
            return errs; // 事件不存在时参数类型表不可用，后续表达式检查无意义
        }
        Map<String, Kind> kinds = ExperienceEventRegistry.paramKinds(r.eventId());
        if (r.conditionExpr() != null && !r.conditionExpr().isBlank()) {
            try {
                ExprParser.expect(ExprParser.parse(r.conditionExpr()), Kind.BOOL, kinds);
            } catch (ExprException e) {
                errs.add("判断表达式: " + e.getMessage());
            }
        }
        try {
            ExprParser.expect(ExprParser.parse(r.valueExpr()), Kind.NUM, kinds);
        } catch (ExprException e) {
            errs.add("数值表达式: " + e.getMessage());
        }
        try {
            ExprParser.parse(r.titleExpr());
        } catch (ExprException e) {
            errs.add("标题表达式: " + e.getMessage());
        }
        return errs;
    }

    /** 解析单个规则 JSON；缺字段/类型错抛 IllegalArgumentException（调用方按条跳过）。 */
    public static ExperienceRule from(JsonObject o) {
        String id = str(o, "id");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("规则缺 id");
        }
        boolean enabled = o.has("enabled") && o.get("enabled").getAsBoolean();
        String eventId = str(o, "eventId");
        String condition = str(o, "conditionExpr");
        String value = str(o, "valueExpr");
        String title = str(o, "titleExpr");
        if (eventId == null) {
            throw new IllegalArgumentException("规则 " + id + " 缺 eventId");
        }
        if (value == null) {
            throw new IllegalArgumentException("规则 " + id + " 缺 valueExpr");
        }
        return new ExperienceRule(
                id, enabled, eventId, condition == null ? "" : condition, value, title == null ? "" : title);
    }

    /** 读取规则集（容错：坏条目跳过并收集原因）。 */
    public static List<ExperienceRule> readAll(JsonObject root, List<String> errors) {
        List<ExperienceRule> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (root == null || !root.has("rules") || !root.get("rules").isJsonArray()) {
            errors.add("缺少 rules 数组");
            return out;
        }
        JsonArray arr = root.getAsJsonArray("rules");
        for (int i = 0; i < arr.size(); i++) {
            try {
                if (!arr.get(i).isJsonObject()) {
                    errors.add("rules[" + i + "]: 不是对象");
                    continue;
                }
                ExperienceRule r = ExperienceRule.from(arr.get(i).getAsJsonObject());
                if (!seen.add(r.id())) {
                    errors.add("规则 id 重复: " + r.id());
                    continue;
                }
                out.add(r);
            } catch (Exception e) {
                errors.add("rules[" + i + "]: " + e.getMessage());
            }
        }
        return out;
    }

    /** 序列化规则集（顶层 version=1）。 */
    public static JsonObject toJson(List<ExperienceRule> rules) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonArray arr = new JsonArray();
        for (ExperienceRule r : rules) {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.id());
            o.addProperty("enabled", r.enabled());
            o.addProperty("eventId", r.eventId());
            o.addProperty("conditionExpr", r.conditionExpr());
            o.addProperty("valueExpr", r.valueExpr());
            o.addProperty("titleExpr", r.titleExpr());
            arr.add(o);
        }
        root.add("rules", arr);
        return root;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }
}
