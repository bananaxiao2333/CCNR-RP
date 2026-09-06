/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.rule;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则变更服务（P15 §4.5）：事件动作 {@code ruleChange} 的「幕作用域」状态层。
 *
 * <p>规则以 {@code phase} 为作用域登记；幕切换时由 {@link com.ccnrcom.rp.event.EventManager}
 * 回调 {@link #onPhaseEnd(String)} 自动解除该幕未持久化的变更。同一幕的冲突变更以后者为准（直接覆盖）。
 *
 * <p>子类型：
 * <ul>
 *   <li>{@code limitProfessions} / {@code limitFactions}：限定本幕可部署/可被征召的职业、阵营。</li>
 *   <li>{@code zoneToggle}：开启/关闭目标区（作用于区域判定）。</li>
 *   <li>{@code recruitMode}：本幕谁能收到邀请（SELF_DEPLOY/RESURRECTION/BOTH）。</li>
 * </ul>
 * {@code buff / nerf} 为即时效果（药水效果），不在本服务留存作用域状态，由序列引擎直接应用。
 *
 * <p>纯逻辑（仅 Gson），无 MC 依赖，可直接 JUnit 测试。
 */
public final class RuleService {

    public static final String RULE_LIMIT_PROFESSIONS = "limitProfessions";
    public static final String RULE_LIMIT_FACTIONS = "limitFactions";
    public static final String RULE_ZONE_TOGGLE = "zoneToggle";
    public static final String RULE_BUFF = "buff";
    public static final String RULE_NERF = "nerf";
    public static final String RULE_RECRUIT_MODE = "recruitMode";

    /** 招募方式覆盖（NONE = 未覆盖，沿用波配置自身 mode）。 */
    public enum RecruitMode {
        SELF_DEPLOY,
        RESURRECTION,
        BOTH,
        NONE;
    }

    /** 单幕规则快照（可变；conflict 以覆盖为准）。 */
    public static final class PhaseRules {
        public List<String> professions = List.of(); // 空 = 未限定
        public List<String> factions = List.of(); // 空 = 未限定
        public final Map<String, Boolean> zones = new LinkedHashMap<>();
        public RecruitMode recruitMode = RecruitMode.NONE;
    }

    private static final PhaseRules EMPTY = new PhaseRules();

    /** 按幕登记的规则快照。 */
    private final Map<String, PhaseRules> byPhase = new LinkedHashMap<>();

    private String currentPhase = "";

    /** 当前幕 id（由 EventManager 每 tick 同步；作为 ruleChange 默认作用域）。 */
    public void setCurrentPhase(String phase) {
        this.currentPhase = phase == null ? "" : phase;
    }

    public String currentPhase() {
        return currentPhase;
    }

    /** 清空全部规则（模式切换/重置剧本运行时调用）。 */
    public void reset() {
        byPhase.clear();
    }

    /** 幕结束：解除该幕登记的规则（作用域自动清理）。 */
    public void onPhaseEnd(String phase) {
        if (phase != null) {
            byPhase.remove(phase);
        }
    }

    // ---------- 应用（数据驱动） ----------

    /** 从 ruleChange 动作 JsonObject 应用到指定的幕（phase 缺省用当前幕）。 */
    public void apply(JsonObject action, String phase) {
        String rule = str(action, "rule", "");
        String scope = phase == null || phase.isBlank() ? str(action, "phase", currentPhase) : phase;
        apply(
                scope,
                rule,
                strList(action, "professions"),
                strList(action, "factions"),
                str(action, "zone", ""),
                bool(action, "on", false),
                str(action, "mode", ""));
    }

    /** 应用到指定的幕；phase 为空时用当前幕。 */
    public void apply(
            String phase,
            String rule,
            List<String> professions,
            List<String> factions,
            String zone,
            boolean on,
            String mode) {
        PhaseRules pr = byPhase.computeIfAbsent(phase == null ? "" : phase, k -> new PhaseRules());
        switch (rule == null ? "" : rule) {
            case RULE_LIMIT_PROFESSIONS -> pr.professions = professions == null ? List.of() : List.copyOf(professions);
            case RULE_LIMIT_FACTIONS -> pr.factions = factions == null ? List.of() : List.copyOf(factions);
            case RULE_ZONE_TOGGLE -> pr.zones.put(zone == null ? "" : zone, on);
            case RULE_RECRUIT_MODE -> pr.recruitMode = parseMode(mode);
            default -> {
                // buff/nerf 为即时效果，不在此留存；未知 rule 记空操作（不抛）
            }
        }
    }

    // ---------- 查询 ----------

    /** 该幕是否限定了职业。 */
    public boolean limitProfessionsActive(String phase) {
        return !byPhase(phase).professions.isEmpty();
    }

    /** 该幕是否限定了阵营。 */
    public boolean limitFactionsActive(String phase) {
        return !byPhase(phase).factions.isEmpty();
    }

    /** 该幕下职业是否可部署/可被征召（未限定恒允许）。 */
    public boolean isProfessionAllowed(String phase, String professionId) {
        List<String> limit = byPhase(phase).professions;
        return limit.isEmpty() || limit.contains(professionId);
    }

    /** 该幕下阵营是否可部署/可被征召（未限定恒允许）。 */
    public boolean isFactionAllowed(String phase, String factionId) {
        List<String> limit = byPhase(phase).factions;
        return limit.isEmpty() || limit.contains(factionId);
    }

    /** 该幕的招募方式覆盖（无覆盖返回 NONE）。 */
    public RecruitMode recruitMode(String phase) {
        return byPhase(phase).recruitMode;
    }

    /** 该幕下目标区是否开启（未登记视为关）。 */
    public boolean zoneEnabled(String phase, String zone) {
        return byPhase(phase).zones.getOrDefault(zone == null ? "" : zone, false);
    }

    /** 该幕登记的规则快照（无则空快照；只读约定）。 */
    public PhaseRules phaseRules(String phase) {
        return byPhase(phase);
    }

    // ---------- 解析辅助 ----------

    private PhaseRules byPhase(String phase) {
        return byPhase.getOrDefault(phase == null ? "" : phase, EMPTY);
    }

    public static RecruitMode parseMode(String mode) {
        if (mode == null) {
            return RecruitMode.NONE;
        }
        try {
            return RecruitMode.valueOf(mode.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return RecruitMode.NONE;
        }
    }

    private static String str(JsonObject o, String key, String def) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsBoolean() : def;
    }

    private static List<String> strList(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o != null && o.has(key) && o.get(key).isJsonArray()) {
            o.getAsJsonArray(key).forEach(e -> out.add(e.getAsString()));
        }
        return out;
    }
}
