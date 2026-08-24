/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.experience;

import com.ccnrcom.rp.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 挂起通知（离线结算）：玩家离线期间的结算结果先落盘，上线后补发（发送即删除）。 */
public final class PendingNoticeStore {
    private final Path file;
    private JsonObject root = new JsonObject();

    public PendingNoticeStore(Path worldDir) {
        this.file = worldDir.resolve("pending_notices.json");
        load();
    }

    public void load() {
        JsonUtil.readObject(file).ifPresent(o -> this.root = o);
    }

    /** 记录一条待补发通知（翻译 key + 参数数组）。 */
    public void store(String playerUuid, String key, String[] args) {
        JsonArray arr = root.has(playerUuid) && root.get(playerUuid).isJsonArray()
                ? root.getAsJsonArray(playerUuid)
                : new JsonArray();
        JsonObject n = new JsonObject();
        n.addProperty("key", key);
        JsonArray a = new JsonArray();
        if (args != null) {
            for (String s : args) {
                a.add(s == null ? "" : s);
            }
        }
        n.add("args", a);
        arr.add(n);
        root.add(playerUuid, arr);
        save();
    }

    /** 取出并移除该玩家的全部挂起通知（Object[]={key, String[] args}）。 */
    public List<Object[]> drain(String playerUuid) {
        List<Object[]> out = new ArrayList<>();
        if (root.has(playerUuid) && root.get(playerUuid).isJsonArray()) {
            for (var e : root.getAsJsonArray(playerUuid)) {
                if (e.isJsonObject()) {
                    JsonObject n = e.getAsJsonObject();
                    String key = n.has("key") ? n.get("key").getAsString() : "";
                    String[] args = new String[0];
                    if (n.has("args") && n.get("args").isJsonArray()) {
                        JsonArray a = n.getAsJsonArray("args");
                        args = new String[a.size()];
                        for (int i = 0; i < args.length; i++) {
                            args[i] = a.get(i).getAsString();
                        }
                    }
                    out.add(new Object[] {key, args});
                }
            }
            root.remove(playerUuid);
            save();
        }
        return out;
    }

    public void save() {
        JsonUtil.atomicWrite(file, root);
    }
}
