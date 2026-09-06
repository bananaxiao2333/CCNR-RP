/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.config;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.data.ConfigStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 多模式（P15）：mode = 一整套「阶段 + 事件 + 波次库 + 动画 + 结局剧本」的剧本，可整体激活/切换。
 *
 * <p>数据：{@code config/ccnr_rp/modes.json} 登记所有 mode 并标记当前激活；每个 mode 的剧本配置位于
 * {@code config/ccnr_rp/modes/<modeId>/<基础文件名>}（如 phases.json / events.json / spawn_waves.json / animations.json）。
 * 未激活任何 mode（active 为空）时，剧本配置回退到顶层基础文件（兼容旧行为）。
 *
 * <p>热切换 = 重读新激活 mode 的全套配置 + 重置剧本运行时（阶段回第 0 幕、事件/波次状态清零、停掉播放中动画）。
 * 公共部分（阵营/职业/档案/经验）不属单模式，全局共享。切换不再持久化"未结束的剧本"。
 */
public final class ModeManager {

    private static final Logger LOGGER = LogManager.getLogger();
    /** 登记文件（相对 config/ccnr_rp/）。 */
    private static final String FILE = "modes.json";

    private volatile ModeDef active = new ModeDef("", "");
    private volatile List<ModeDef> modes = List.of();

    public ModeManager() {
        reload();
    }

    /** 重读 modes.json：登记列表 + 激活态。模式配置由管理员在 config/ccnr_rp 目录自建（不从此源码资源播种）。 */
    public void reload() {
        JsonObject root = ConfigStore.load(FILE).orElseGet(JsonObject::new);
        ModeConfig parsed = parseModes(root);
        this.modes = parsed.modes();
        this.active = modes.stream()
                .filter(m -> m.id().equals(parsed.activeId()))
                .findFirst()
                .orElse(new ModeDef("", ""));
        // 激活 id 不存在于登记列表时，视为未激活（避免指向缺失剧本）
        if (!parsed.activeId().isBlank() && !this.active.id().equals(parsed.activeId())) {
            LOGGER.warn("[CCNR-RP] modes.json 激活 '{}' 不在登记列表，回退为未激活", parsed.activeId());
        }
    }

    /** 当前是否处于某模式下（激活 id 非空）。 */
    public boolean active() {
        return !active.id().isBlank();
    }

    public String activeId() {
        return active.id();
    }

    public String activeName() {
        return active.name();
    }

    public List<ModeDef> modes() {
        return modes;
    }

    /** 剧本配置的 ConfigStore 键：模式激活时按 {@code modes/<id>/<base>}，否则基础文件名。 */
    public String key(String base) {
        return active() ? "modes/" + active.id() + "/" + base : base;
    }

    /** 剧本配置的内嵌默认资源路径：模式激活时按默认模式文件夹，否则顶层默认。 */
    public String resource(String base) {
        return active()
                ? "/assets/ccnr_rp/defaults/modes/" + active.id() + "/" + base
                : "/assets/ccnr_rp/defaults/" + base;
    }

    /**
     * 激活某 mode（热切）：写回 modes.json 的 active 字段，并回调各管理器重载（由调用方触发）。
     * 返回 false=id 未登记。
     */
    public boolean set(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        Optional<ModeDef> target = modes.stream().filter(m -> m.id().equals(id)).findFirst();
        if (target.isEmpty()) {
            return false;
        }
        JsonObject root = ConfigStore.load(FILE).orElseGet(JsonObject::new);
        root.addProperty("active", id);
        if (!root.has("version")) {
            root.addProperty("version", 1);
        }
        if (!ConfigStore.save(FILE, root)) {
            LOGGER.error("[CCNR-RP] 写入 {} 失败，切换 mode 未生效", FILE);
            return false;
        }
        reload();
        return true;
    }

    /** 清除激活（回到未激活/旧顶层配置）。 */
    public boolean clear() {
        JsonObject root = ConfigStore.load(FILE).orElseGet(JsonObject::new);
        root.addProperty("active", "");
        if (!ConfigStore.save(FILE, root)) {
            return false;
        }
        reload();
        return true;
    }

    // ---------- 纯解析（可测） ----------

    /** 解析结果：激活 id + 登记列表。 */
    public record ModeConfig(String activeId, List<ModeDef> modes) {}

    public record ModeDef(String id, String name) {}

    /** 从 modes.json 根对象解析：active（缺省 ""）与 modes[]（缺失时回退空）。 */
    public static ModeConfig parseModes(JsonObject root) {
        String active =
                root != null && root.has("active") && !root.get("active").isJsonNull()
                        ? root.get("active").getAsString()
                        : "";
        List<ModeDef> out = new ArrayList<>();
        if (root != null && root.has("modes") && root.get("modes").isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray("modes")) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject o = e.getAsJsonObject();
                String id = str(o, "id", "");
                if (id.isBlank()) {
                    continue;
                }
                out.add(new ModeDef(id, str(o, "name", id)));
            }
        }
        return new ModeConfig(active, List.copyOf(out));
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    /** 供命令/面板列出的计数。 */
    public static JsonObject summary(ModeManager mgr) {
        JsonObject o = new JsonObject();
        o.addProperty("active", mgr == null ? "" : mgr.activeId());
        JsonArray arr = new JsonArray();
        if (mgr != null) {
            for (ModeDef m : mgr.modes()) {
                JsonObject mo = new JsonObject();
                mo.addProperty("id", m.id());
                mo.addProperty("name", m.name());
                arr.add(mo);
            }
        }
        o.add("modes", arr);
        return o;
    }

    /** 供 {@link CCNRRPMod} 在模式切换后重置剧本运行时（阶段回第 0 幕，事件/波次重载，停动画）。 */
    public static void resetScenarioRuntime() {
        if (CCNRRPMod.eventManager != null) {
            CCNRRPMod.eventManager.reload();
        }
        if (CCNRRPMod.spawnFramework != null) {
            CCNRRPMod.spawnFramework.reload();
        }
        if (CCNRRPMod.animationEngine != null) {
            CCNRRPMod.animationEngine.reload();
        }
    }
}
