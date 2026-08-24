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
        if (!root.has(charId)) {
            root.add(charId, new JsonObject());
        }
        return root.getAsJsonObject(charId);
    }

    public long dutySeconds(String charId) {
        JsonObject e = entry(charId);
        return e.has("dutySeconds") ? e.get("dutySeconds").getAsLong() : 0;
    }

    public int taskXp(String charId) {
        JsonObject e = entry(charId);
        return e.has("taskXp") ? e.get("taskXp").getAsInt() : 0;
    }

    public boolean evacSettled(String charId) {
        JsonObject e = entry(charId);
        return e.has("evacSettled") && e.get("evacSettled").getAsBoolean();
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
