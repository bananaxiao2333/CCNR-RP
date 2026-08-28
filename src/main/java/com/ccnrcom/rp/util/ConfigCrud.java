/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.util;

import com.ccnrcom.rp.data.ConfigStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraftforge.fml.loading.FMLPaths;

/** config/ccnr_rp/*.json 通用 CRUD（事件/阶段/刷新波等数组段读写）。P1 起经 ConfigStore：DB 启用时读写库配置文档，否则读写磁盘。 */
public final class ConfigCrud {

    private ConfigCrud() {}

    public static Path file(String name) {
        return FMLPaths.CONFIGDIR.get().resolve("ccnr_rp").resolve(name);
    }

    /** 读取数组段（不存在时返回空列表，不写盘）。 */
    public static List<JsonObject> items(String fileName, String arrayKey) {
        List<JsonObject> out = new ArrayList<>();
        JsonObject root = ConfigStore.load(fileName).orElse(new JsonObject());
        if (!root.has(arrayKey) || !root.get(arrayKey).isJsonArray()) {
            return out;
        }
        for (JsonElement e : root.getAsJsonArray(arrayKey)) {
            if (e.isJsonObject()) {
                out.add(e.getAsJsonObject());
            }
        }
        return out;
    }

    /** upsert：存在相同 id 则替换，否则追加。返回错误列表（空=成功）。 */
    public static List<String> upsert(String fileName, String arrayKey, JsonObject item) {
        if (item == null || !item.has("id") || item.get("id").getAsString().isBlank()) {
            return List.of("缺少 id");
        }
        String id = item.get("id").getAsString();
        JsonObject root = ConfigStore.load(fileName).orElseGet(JsonObject::new);
        if (!root.has("version")) {
            root.addProperty("version", 1);
        }
        JsonArray arr = root.has(arrayKey) && root.get(arrayKey).isJsonArray()
                ? root.getAsJsonArray(arrayKey)
                : new JsonArray();
        root.add(arrayKey, arr);
        boolean replaced = false;
        for (int i = 0; i < arr.size(); i++) {
            if (arr.get(i).isJsonObject()
                    && arr.get(i).getAsJsonObject().has("id")
                    && arr.get(i).getAsJsonObject().get("id").getAsString().equals(id)) {
                arr.set(i, item);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            arr.add(item);
        }
        if (!ConfigStore.save(fileName, root)) {
            return List.of("配置文件写入失败");
        }
        return List.of();
    }

    /** 删除：返回错误列表（空=成功）。 */
    public static List<String> delete(String fileName, String arrayKey, String id) {
        JsonObject root = ConfigStore.load(fileName).orElse(new JsonObject());
        if (!root.has(arrayKey) || !root.get(arrayKey).isJsonArray()) {
            return List.of("未找到: " + id);
        }
        JsonArray arr = root.getAsJsonArray(arrayKey);
        for (int i = 0; i < arr.size(); i++) {
            if (arr.get(i).isJsonObject()
                    && arr.get(i).getAsJsonObject().has("id")
                    && arr.get(i).getAsJsonObject().get("id").getAsString().equals(id)) {
                arr.remove(i);
                if (!ConfigStore.save(fileName, root)) {
                    return List.of("配置文件写入失败");
                }
                return List.of();
            }
        }
        return List.of("未找到: " + id);
    }

    /** 整份配置（客户端管理器快照用）。 */
    public static JsonObject root(String fileName) {
        return ConfigStore.load(fileName).orElse(new JsonObject());
    }
}
