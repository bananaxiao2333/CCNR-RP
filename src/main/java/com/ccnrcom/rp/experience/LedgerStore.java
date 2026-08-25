/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.experience;

import com.ccnrcom.rp.util.JsonUtil;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.Optional;

/** 结算账本：记录每个角色已结算的守卫秒数/任务 XP/疏散标记，保证增量幂等。 */
public final class LedgerStore {
    private final Path file;
    private JsonObject root = new JsonObject();

    public LedgerStore(Path worldDir) {
        this.file = worldDir.resolve("xp_ledger.json");
        load();
    }

    public void load() {
        JsonUtil.readObject(file).ifPresent(o -> this.root = o);
    }

    public JsonObject entry(String charId) {
        if (!root.has(charId) || !root.get(charId).isJsonObject()) {
            root.add(charId, new JsonObject()); // 坏值（非对象）一律重建
        }
        return root.getAsJsonObject(charId);
    }

    public long dutySeconds(String charId) {
        JsonObject e = entry(charId);
        try {
            return e.has("dutySeconds") ? e.get("dutySeconds").getAsLong() : 0;
        } catch (Exception ex) {
            return 0; // 手改/损坏的类型错配：按 0 处理
        }
    }

    public int taskXp(String charId) {
        JsonObject e = entry(charId);
        try {
            return e.has("taskXp") ? e.get("taskXp").getAsInt() : 0;
        } catch (Exception ex) {
            return 0;
        }
    }

    public boolean evacSettled(String charId) {
        JsonObject e = entry(charId);
        try {
            return e.has("evacSettled") && e.get("evacSettled").getAsBoolean();
        } catch (Exception ex) {
            return false;
        }
    }

    /** 删除角色时清理其结算基线（防文件膨胀与角色 id 复用污染）。 */
    public void remove(String charId) {
        if (root.has(charId)) {
            root.remove(charId);
            save();
        }
    }

    public void setDutySeconds(String charId, long v) {
        entry(charId).addProperty("dutySeconds", v);
    }

    public void setTaskXp(String charId, int v) {
        entry(charId).addProperty("taskXp", v);
    }

    public void setEvacSettled(String charId, boolean v) {
        entry(charId).addProperty("evacSettled", v);
    }

    public void save() {
        JsonUtil.atomicWrite(file, root);
    }

    public Optional<String> lastError() {
        return Optional.empty();
    }

    public Path file() {
        return file;
    }
}
