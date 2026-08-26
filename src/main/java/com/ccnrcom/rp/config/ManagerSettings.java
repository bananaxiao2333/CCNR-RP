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
 * - firstJoinAutoDeploy : 首次入服自动部署（默认开启；职业 id 见 firstJoinProfession）
 * - firstJoinProfession : 首次入服自动部署的职业 id（默认 m5_intern，管理面板可编辑）
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
        o.addProperty("hudEnabled", true);
        o.addProperty("hudProfessionText", true);
        o.addProperty("hudFactionText", false);
        o.addProperty("hudHealthText", false);
        o.addProperty("firstJoinAutoDeploy", true);
        o.addProperty("firstJoinProfession", "m5_intern");
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

    /** 首次入服自动部署开关（true=首次入服玩家自动部署为 firstJoinProfession 职业）。 */
    public boolean firstJoinAutoDeploy() {
        return bool("firstJoinAutoDeploy", true);
    }

    /** 首次入服自动部署的职业 id（管理面板「设置」页可编辑；空串=关闭自动部署）。 */
    public String firstJoinProfession() {
        try {
            return state.has("firstJoinProfession")
                    ? state.get("firstJoinProfession").getAsString()
                    : "m5_intern";
        } catch (Exception e) {
            return "m5_intern";
        }
    }

    /** 设置项键列表（管理面板展示顺序，程序化生成开关/输入行 UI 用）。 */
    public static List<String> keys() {
        return List.of(
                "firstJoinAutoDeploy",
                "firstJoinProfession",
                "forceObserving",
                "openPanelOnJoin",
                "forceRetain",
                "hudEnabled",
                "hudProfessionText",
                "hudFactionText",
                "hudHealthText");
    }

    /** 设置项类型（管理面板渲染用）：bool=开关行，string=文本输入行。 */
    public static String type(String key) {
        return "firstJoinProfession".equals(key) ? "string" : "bool";
    }

    /** 设置单个键（bool=开关 / string=文本），返回错误列表（空=成功）。 */
    public List<String> set(String key, String value) {
        if (!keys().contains(key)) {
            return List.of("未知设置项: " + key);
        }
        if ("string".equals(type(key))) {
            state.addProperty(key, value == null ? "" : value);
        } else {
            if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                return List.of("设置值需为 true/false: " + value);
            }
            state.addProperty(key, "true".equalsIgnoreCase(value));
        }
        if (!JsonUtil.atomicWrite(file, state)) {
            return List.of("设置写入失败");
        }
        return List.of();
    }

    public JsonObject toJson() {
        return state.deepCopy();
    }
}
