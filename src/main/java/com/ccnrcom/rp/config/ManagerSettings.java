/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.config;

import com.ccnrcom.rp.util.JsonUtil;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * CCNR-RP 管理器设置（config/ccnr_rp/settings.json，管理员在游戏内用「管理面板」修改）：
 * - forceObserving  : 入服强制观察者状态（默认开启）
 * - openPanelOnJoin : 入服默认打开角色面板（选择部署）
 * - forceRetain     : 强制保留角色（转生/弃演/离服 → 直接判定死亡并留遗体）
 */
public final class ManagerSettings {
    private static final Logger LOGGER = LogManager.getLogger();

    private final Path file;
    private JsonObject state = defaults();

    public ManagerSettings() {
        this.file = FMLPaths.CONFIGDIR.get().resolve("ccnr_rp").resolve("settings.json");
        load();
    }

    private static JsonObject defaults() {
        JsonObject o = new JsonObject();
        o.addProperty("version", 1);
        o.addProperty("forceObserving", true);
        o.addProperty("openPanelOnJoin", true);
        o.addProperty("forceRetain", true);
        o.addProperty("recruitInviteAlive", true);
        o.addProperty("hudEnabled", true);
        o.addProperty("hudProfessionText", true);
        o.addProperty("hudFactionText", false);
        o.addProperty("hudHealthText", false);
        return o;
    }

    public void load() {
        try {
            if (Files.exists(file)) {
                JsonObject read = JsonUtil.readObject(file).orElse(null);
                if (read != null) {
                    state = read;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[CCNR-RP] settings.json 读取失败，使用默认值: {}", e.getMessage());
        }
        // 补齐缺失字段（默认值），保证新版本字段自动出现
        JsonObject merged = defaults();
        state.entrySet().forEach(e -> merged.add(e.getKey(), e.getValue()));
        state = merged;
        JsonUtil.atomicWrite(file, state);
    }

    public boolean forceObserving() {
        return bool("forceObserving", true);
    }

    public boolean openPanelOnJoin() {
        return bool("openPanelOnJoin", true);
    }

    public boolean forceRetain() {
        return bool("forceRetain", true);
    }

    /** 支援波是否允许向「存活（在场）」人员发送招募邀请。 */
    public boolean recruitInviteAlive() {
        return bool("recruitInviteAlive", true);
    }

    private boolean bool(String key, boolean def) {
        try {
            return state.has(key) ? state.get(key).getAsBoolean() : def;
        } catch (Exception e) {
            return def;
        }
    }

    public boolean hudEnabled() {
        return bool("hudEnabled", true);
    }

    public boolean hudProfessionText() {
        return bool("hudProfessionText", true);
    }

    public boolean hudFactionText() {
        return bool("hudFactionText", false);
    }

    public boolean hudHealthText() {
        return bool("hudHealthText", false);
    }

    /** 设置项键列表（管理面板展示顺序，程序化生成开关 UI 用）。 */
    public static List<String> keys() {
        return List.of(
                "forceObserving",
                "openPanelOnJoin",
                "forceRetain",
                "recruitInviteAlive",
                "hudEnabled",
                "hudProfessionText",
                "hudFactionText",
                "hudHealthText");
    }

    /** 设置单个键（仅 bool 支持），返回错误列表（空=成功）。 */
    public List<String> set(String key, String value) {
        if (!keys().contains(key)) {
            return List.of("未知设置项: " + key);
        }
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            return List.of("设置值需为 true/false: " + value);
        }
        state.addProperty(key, "true".equalsIgnoreCase(value));
        if (!JsonUtil.atomicWrite(file, state)) {
            return List.of("设置写入失败");
        }
        return List.of();
    }

    public JsonObject toJson() {
        return state.deepCopy();
    }
}
