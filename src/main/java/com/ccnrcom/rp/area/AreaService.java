/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.area;

import com.ccnrcom.rp.data.ConfigStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 区域服务（配置 IO + 只读缓存）：config/ccnr_rp/areas.json 的唯一读写入口。
 *
 * <p>权威源是配置文件（经 {@link ConfigStore}：DB 启用时进 config_documents，否则原子写磁盘）；本类的
 * {@code areas} 只是**只读镜像缓存**，写入一律"读整份 → deepCopy → 改 → 全量校验 → 落盘 → 换缓存"，
 * 不做第二权威源（docs/01 §9.3）。缓存失效点：每次写成功、{@code /rp area reload}、服务端启动重建。
 *
 * <p>生命周期：由 {@code CCNRRPMod} 在 ServerAboutToStart 构造、ServerStopping 置空（对称清理）。
 */
public final class AreaService {
    private static final Logger LOGGER = LogManager.getLogger();

    /** 配置文件（config/ccnr_rp/areas.json）。 */
    public static final String FILE = "areas.json";
    /** 数组段名。 */
    public static final String KEY = "areas";

    private List<Area> areas = List.of();

    public AreaService() {
        load();
    }

    /** 加载（文件缺失→写入内嵌默认样板；解析失败的条目跳过并记错误，服务继续）。 */
    private void load() {
        JsonObject root = ConfigStore.load(FILE).orElse(null);
        if (root == null) {
            // 首次启动：从内嵌默认样板生成（与 factions.json 同一约定：开箱即有一片示例区域）
            root = com.ccnrcom.rp.util.JsonUtil.readResource("/assets/ccnr_rp/defaults/" + FILE)
                    .orElseGet(JsonObject::new);
            ConfigStore.save(FILE, root);
            LOGGER.info("[CCNR-RP] 已生成默认区域配置: {}", FILE);
        }
        apply(root);
    }

    /** 从存储重读（命令/写成功后调用）。 */
    public void reload() {
        apply(ConfigStore.load(FILE).orElseGet(JsonObject::new));
    }

    /** 解析并换缓存。解析失败的条目跳过并记错误，服务继续（docs/01 §5 容错）。 */
    private void apply(JsonObject root) {
        AreaRegistry.ParseResult result = AreaRegistry.parse(root.get(KEY));
        for (String e : result.errors()) {
            LOGGER.error("[CCNR-RP] 区域配置错误（已跳过）：{}", e);
        }
        for (String w : result.warnings()) {
            LOGGER.warn("[CCNR-RP] 区域配置：{}", w);
        }
        this.areas = result.areas();
        LOGGER.info("[CCNR-RP] 区域注册表载入：{} 个", areas.size());
    }

    /** 当前区域列表（只读；每次调用返回同一不可变快照）。 */
    public List<Area> areas() {
        return areas;
    }

    public Optional<Area> find(String id) {
        return AreaRegistry.find(areas, id);
    }

    /** 点命中的第一个区域（命令/外部功能查询用）。 */
    public Optional<Area> at(String dim, double x, double y, double z) {
        return AreaRegistry.at(areas, dim, x, y, z);
    }

    /** 新建/覆盖区域（按 id upsert）。返回错误列表（空=成功）。 */
    public List<String> upsert(Area area) {
        if (area == null || area.id() == null || !AreaRegistry.validId(area.id())) {
            return List.of("区域 id 非法（小写字母/数字/下划线，1-64）");
        }
        if (area.dim() == null || area.dim().isBlank()) {
            return List.of("区域维度不能为空（需 namespace:path）");
        }
        JsonObject current = ConfigStore.load(FILE).orElseGet(JsonObject::new);
        JsonObject candidate = current.deepCopy();
        JsonArray arr = candidate.has(KEY) && candidate.get(KEY).isJsonArray()
                ? candidate.getAsJsonArray(KEY)
                : new JsonArray();
        candidate.add(KEY, arr);
        if (!candidate.has("version")) {
            candidate.addProperty("version", 1);
        }
        boolean replaced = false;
        for (int i = 0; i < arr.size(); i++) {
            if (arr.get(i).isJsonObject()
                    && area.id().equals(arr.get(i).getAsJsonObject().get("id").getAsString())) {
                arr.set(i, AreaRegistry.toJson(area));
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            if (areas.size() >= AreaRegistry.MAX_AREAS) {
                return List.of("区域数量已达上限（" + AreaRegistry.MAX_AREAS + "）");
            }
            arr.add(AreaRegistry.toJson(area));
        }
        // 全量校验候选（含刚写入项与既有项）：任何一条非法都拒绝落盘，不做部分提交
        AreaRegistry.ParseResult check = AreaRegistry.parse(candidate.get(KEY));
        if (!check.success()) {
            return new ArrayList<>(check.errors());
        }
        if (!ConfigStore.save(FILE, candidate)) {
            return List.of("区域配置写入失败");
        }
        reload();
        return List.of();
    }

    /** 删除区域。返回错误列表（空=成功）。 */
    public List<String> delete(String id) {
        if (id == null || id.isBlank()) {
            return List.of("缺少区域 id");
        }
        JsonObject current = ConfigStore.load(FILE).orElseGet(JsonObject::new);
        if (!current.has(KEY) || !current.get(KEY).isJsonArray()) {
            return List.of("未找到区域: " + id);
        }
        JsonObject candidate = current.deepCopy();
        JsonArray arr = candidate.getAsJsonArray(KEY);
        for (int i = 0; i < arr.size(); i++) {
            if (arr.get(i).isJsonObject()
                    && id.equals(arr.get(i).getAsJsonObject().get("id").getAsString())) {
                arr.remove(i);
                if (!ConfigStore.save(FILE, candidate)) {
                    return List.of("区域配置写入失败");
                }
                reload();
                return List.of();
            }
        }
        return List.of("未找到区域: " + id);
    }

    /** 服务端停止时清空缓存（对称清理：静态态不跨世界残留）。 */
    public void clear() {
        this.areas = List.of();
    }
}
