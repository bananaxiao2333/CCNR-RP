/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.config;

import com.google.gson.JsonObject;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.ConfigValue;

/** CCNR-RP 服务端调参配置（serverconfig/ccnr_rp-server.toml）。 */
public final class CCNRRPConfig {
    public static final ForgeConfigSpec SPEC;

    /** 角色死亡后冷却时长（分钟）。 */
    public static final ConfigValue<Integer> DEATH_COOLDOWN_MINUTES;
    /** 掉线判死兜底：角色 alive 但玩家离线超过该秒数补判死。 */
    public static final ConfigValue<Integer> OFFLINE_GRACE_SECONDS;
    /** 掉线判死兜底轮询间隔（秒）。 */
    public static final ConfigValue<Integer> OFFLINE_POLL_SECONDS;
    /** 事件触发器求值间隔（tick）。 */
    public static final ConfigValue<Integer> EVENT_EVAL_INTERVAL_TICKS;
    /** 复活波/在场状态轮询间隔（tick）。 */
    public static final ConfigValue<Integer> SPAWN_POLL_TICKS;
    /** 出生复活延迟（tick，防传送闪断）。 */
    public static final ConfigValue<Integer> SPAWN_DELAY_TICKS;
    /** 经验：值班时间每秒 XP。 */
    public static final ConfigValue<Double> XP_DUTY_PER_SECOND;
    /** 经验：任务默认 XP。 */
    public static final ConfigValue<Integer> XP_TASK_DEFAULT;
    /** 经验：疏散方式——安全撤离。 */
    public static final ConfigValue<Integer> XP_EVAC_SAFE;
    /** 经验：疏散方式——阵亡。 */
    public static final ConfigValue<Integer> XP_EVAC_DIED;
    /** 经验：疏散方式——观察结束。 */
    public static final ConfigValue<Integer> XP_EVAC_OBSERVING;
    /** 经验：疏散方式——滞留。 */
    public static final ConfigValue<Integer> XP_EVAC_STAY_BEHIND;
    /** 等级曲线：level(n) = base * n^pow。 */
    public static final ConfigValue<Double> LEVEL_BASE;

    public static final ConfigValue<Double> LEVEL_POW;

    /** 玩家头顶悬浮标签总开关（服务端权威，客户端遵从）。 */
    public static final ConfigValue<Boolean> NAMETAG_ENABLED;
    /** 玩家头顶悬浮标签阵营徽章大小（世界单位，默认 9；0=不显示徽章）。 */
    public static final ConfigValue<Integer> NAMETAG_BADGE_SIZE;
    /** 玩家头顶悬浮标签离头顶的高度（格，默认 0.9；越大标签越高）。 */
    public static final ConfigValue<Double> NAMETAG_OFFSET;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("character");
        DEATH_COOLDOWN_MINUTES = b.comment("角色死亡后的冷却时长（分钟）").define("deathCooldownMinutes", 30);
        b.pop();
        b.push("status");
        OFFLINE_GRACE_SECONDS = b.comment("角色 alive 但玩家离线超过该秒数（轮询兜底）补判死").define("offlineGraceSeconds", 10);
        OFFLINE_POLL_SECONDS = b.comment("掉线判死兜底轮询间隔（秒）").define("offlinePollSeconds", 5);
        b.pop();
        b.push("event");
        EVENT_EVAL_INTERVAL_TICKS = b.comment("事件触发器求值间隔（tick，20t=1秒）").define("evalIntervalTicks", 20);
        b.pop();
        b.push("spawn");
        SPAWN_POLL_TICKS = b.comment("复活波/团队创建轮询间隔（tick）").define("pollTicks", 20);
        SPAWN_DELAY_TICKS = b.comment("部署延迟（tick）").define("deployDelayTicks", 5);
        b.pop();
        b.push("xp");
        XP_DUTY_PER_SECOND = b.comment("值班时间每秒 XP").define("dutyXpPerSecond", 0.1);
        XP_TASK_DEFAULT = b.comment("任务行为默认 XP（事件可覆盖 xp）").define("taskDefaultXp", 50);
        XP_EVAC_SAFE = b.comment("疏散方式 SAFE_RESCUE 的 XP").define("evacSafeXp", 200);
        XP_EVAC_DIED = b.comment("疏散方式 DIED 的 XP（可为负数：死亡扣分）").define("evacDiedXp", -10);
        XP_EVAC_OBSERVING = b.comment("疏散方式 OBSERVING_END 的 XP").define("evacObservingXp", 0);
        XP_EVAC_STAY_BEHIND = b.comment("疏散方式 STAY_BEHIND 的 XP").define("evacStayBehindXp", 150);
        b.pop();
        b.push("level");
        LEVEL_BASE = b.comment("等级曲线基数：xpForLevel(n) = base * n^pow").define("base", 100.0);
        LEVEL_POW = b.comment("等级曲线指数").define("pow", 2.0);
        b.pop();
        b.push("nametag");
        NAMETAG_ENABLED = b.comment("玩家头顶悬浮标签总开关（false=完全关闭）").define("enabled", true);
        NAMETAG_BADGE_SIZE = b.comment("头顶标签阵营徽章大小（世界单位，0=不显示徽章，只显示文字）").define("badgeSize", 9);
        NAMETAG_OFFSET = b.comment("头顶标签离头顶的高度（格，越大标签越高）").define("offset", 0.9);
        b.pop();
        SPEC = b.build();
    }

    private CCNRRPConfig() {}

    // ---------- 管理面板程序化设定（serverconfig 只读/写） ----------

    /** serverconfig 可编辑项键（管理面板「设定」标签展示顺序）。 */
    public static java.util.List<String> keys() {
        return java.util.List.of(
                "deathCooldownMinutes",
                "offlineGraceSeconds",
                "offlinePollSeconds",
                "evalIntervalTicks",
                "pollTicks",
                "deployDelayTicks",
                "dutyXpPerSecond",
                "taskDefaultXp",
                "evacSafeXp",
                "evacDiedXp",
                "evacObservingXp",
                "evacStayBehindXp",
                "base",
                "pow",
                "enabled",
                "badgeSize",
                "offset");
    }

    /** 当前所有 serverconfig 值（key → 数值），供管理面板展示。 */
    public static JsonObject values() {
        JsonObject o = new JsonObject();
        o.addProperty("deathCooldownMinutes", DEATH_COOLDOWN_MINUTES.get());
        o.addProperty("offlineGraceSeconds", OFFLINE_GRACE_SECONDS.get());
        o.addProperty("offlinePollSeconds", OFFLINE_POLL_SECONDS.get());
        o.addProperty("evalIntervalTicks", EVENT_EVAL_INTERVAL_TICKS.get());
        o.addProperty("pollTicks", SPAWN_POLL_TICKS.get());
        o.addProperty("deployDelayTicks", SPAWN_DELAY_TICKS.get());
        o.addProperty("dutyXpPerSecond", XP_DUTY_PER_SECOND.get());
        o.addProperty("taskDefaultXp", XP_TASK_DEFAULT.get());
        o.addProperty("evacSafeXp", XP_EVAC_SAFE.get());
        o.addProperty("evacDiedXp", XP_EVAC_DIED.get());
        o.addProperty("evacObservingXp", XP_EVAC_OBSERVING.get());
        o.addProperty("evacStayBehindXp", XP_EVAC_STAY_BEHIND.get());
        o.addProperty("base", LEVEL_BASE.get());
        o.addProperty("pow", LEVEL_POW.get());
        o.addProperty("enabled", NAMETAG_ENABLED.get());
        o.addProperty("badgeSize", NAMETAG_BADGE_SIZE.get());
        o.addProperty("offset", NAMETAG_OFFSET.get());
        return o;
    }

    /** 设置单个 serverconfig 项并落盘；返回错误（空=成功）。 */
    public static java.util.List<String> set(String key, String value) {
        try {
            switch (key) {
                case "deathCooldownMinutes" -> DEATH_COOLDOWN_MINUTES.set(Integer.parseInt(value.trim()));
                case "offlineGraceSeconds" -> OFFLINE_GRACE_SECONDS.set(Integer.parseInt(value.trim()));
                case "offlinePollSeconds" -> OFFLINE_POLL_SECONDS.set(Integer.parseInt(value.trim()));
                case "evalIntervalTicks" -> EVENT_EVAL_INTERVAL_TICKS.set(Integer.parseInt(value.trim()));
                case "pollTicks" -> SPAWN_POLL_TICKS.set(Integer.parseInt(value.trim()));
                case "deployDelayTicks" -> SPAWN_DELAY_TICKS.set(Integer.parseInt(value.trim()));
                case "dutyXpPerSecond" -> XP_DUTY_PER_SECOND.set(Double.parseDouble(value.trim()));
                case "taskDefaultXp" -> XP_TASK_DEFAULT.set(Integer.parseInt(value.trim()));
                case "evacSafeXp" -> XP_EVAC_SAFE.set(Integer.parseInt(value.trim()));
                case "evacDiedXp" -> XP_EVAC_DIED.set(Integer.parseInt(value.trim()));
                case "evacObservingXp" -> XP_EVAC_OBSERVING.set(Integer.parseInt(value.trim()));
                case "evacStayBehindXp" -> XP_EVAC_STAY_BEHIND.set(Integer.parseInt(value.trim()));
                case "base" -> LEVEL_BASE.set(Double.parseDouble(value.trim()));
                case "pow" -> LEVEL_POW.set(Double.parseDouble(value.trim()));
                case "enabled" -> NAMETAG_ENABLED.set(Boolean.parseBoolean(value.trim()));
                case "badgeSize" -> NAMETAG_BADGE_SIZE.set(Integer.parseInt(value.trim()));
                case "offset" -> NAMETAG_OFFSET.set(Double.parseDouble(value.trim()));
                default -> {
                    return java.util.List.of("未知配置项: " + key);
                }
            }
            SPEC.save();
            return java.util.List.of();
        } catch (NumberFormatException e) {
            return java.util.List.of("配置值需为数字: " + value);
        }
    }
}
