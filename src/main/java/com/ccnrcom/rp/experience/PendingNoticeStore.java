/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.experience;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.data.Database;
import com.ccnrcom.rp.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** 挂起通知（离线结算）：玩家离线期间的结算结果先落盘，上线后补发（发送即删除）。P2 起 DB 启用时存 pending_notices 表。 */
public final class PendingNoticeStore {
    private static final Logger LOGGER = LogManager.getLogger();

    private final Path file;
    private final Database db;
    private JsonObject root = new JsonObject();

    public PendingNoticeStore(Path worldDir) {
        this.file = worldDir.resolve("pending_notices.json");
        Database d = CCNRRPMod.database;
        this.db = (d != null && d.enabled()) ? d : null;
        load();
    }

    public void load() {
        root = new JsonObject();
        if (db != null) {
            try {
                db.read(c -> {
                    try (ResultSet rs = c.createStatement()
                            .executeQuery("SELECT " + db.dialect().quote("uuid") + ", "
                                    + db.dialect().quote("msg_key") + ", "
                                    + db.dialect().quote("args") + " FROM "
                                    + db.dialect().quote("pending_notices") + " ORDER BY "
                                    + db.dialect().quote("seq"))) {
                        while (rs.next()) {
                            JsonObject n = new JsonObject();
                            n.addProperty("key", rs.getString("msg_key"));
                            JsonArray a = new JsonArray();
                            String argsJson = rs.getString("args");
                            if (argsJson != null && !argsJson.isBlank()) {
                                try {
                                    JsonObject o =
                                            com.ccnrcom.rp.util.JsonUtil.GSON.fromJson(argsJson, JsonObject.class);
                                    if (o.has("args") && o.get("args").isJsonArray()) {
                                        for (com.google.gson.JsonElement e : o.getAsJsonArray("args")) {
                                            a.add(e.getAsString());
                                        }
                                    }
                                } catch (Exception ignored) {
                                    // 坏 args 跳过
                                }
                            }
                            n.add("args", a);
                            add(root, rs.getString("uuid"), n);
                        }
                    }
                    return null;
                });
            } catch (Exception e) {
                LOGGER.warn("[CCNR-RP] pending_notices 读取失败: {}", e.getMessage());
            }
            return;
        }
        JsonUtil.readObject(file).ifPresent(o -> this.root = o);
    }

    private static void add(JsonObject root, String uuid, JsonObject n) {
        JsonArray arr = root.has(uuid) && root.get(uuid).isJsonArray() ? root.getAsJsonArray(uuid) : new JsonArray();
        arr.add(n);
        root.add(uuid, arr);
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

    /** 取出并移除该玩家的全部挂起通知（Object[]={key, String[] args}）；键存在但为坏值（非数组）也一并清除。 */
    public List<Object[]> drain(String playerUuid) {
        List<Object[]> out = new ArrayList<>();
        if (root.has(playerUuid)) {
            if (root.get(playerUuid).isJsonArray()) {
                for (var e : root.getAsJsonArray(playerUuid)) {
                    if (!e.isJsonObject()) {
                        continue;
                    }
                    JsonObject n = e.getAsJsonObject();
                    String key = "";
                    try {
                        key = n.has("key") ? n.get("key").getAsString() : "";
                    } catch (Exception ignored) {
                        key = "";
                    }
                    String[] args = new String[0];
                    if (n.has("args") && n.get("args").isJsonArray()) {
                        JsonArray a = n.getAsJsonArray("args");
                        args = new String[a.size()];
                        for (int i = 0; i < args.length; i++) {
                            try {
                                args[i] = a.get(i).getAsString();
                            } catch (Exception ignored) {
                                args[i] = "";
                            }
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

    /** 保存：数据库启用→重建 pending_notices 行；否则原子写磁盘。 */
    public void save() {
        if (db != null) {
            try {
                db.write(c -> {
                    try (PreparedStatement del =
                            c.prepareStatement("DELETE FROM " + db.dialect().quote("pending_notices"))) {
                        del.executeUpdate();
                    }
                    try (PreparedStatement ins = c.prepareStatement("INSERT INTO "
                            + db.dialect().quote("pending_notices") + " ("
                            + db.dialect().quote("uuid") + ", " + db.dialect().quote("msg_key") + ", "
                            + db.dialect().quote("args") + ", " + db.dialect().quote("seq") + ") VALUES ("
                            + db.dialect().placeholders(4) + ")")) {
                        for (var entry : root.entrySet()) {
                            JsonArray arr = entry.getValue().getAsJsonArray();
                            for (int i = 0; i < arr.size(); i++) {
                                JsonObject n = arr.get(i).getAsJsonObject();
                                ins.setString(1, entry.getKey());
                                ins.setString(2, n.has("key") ? n.get("key").getAsString() : "");
                                JsonObject wrapper = new JsonObject();
                                wrapper.add("args", n.has("args") ? n.get("args") : new JsonArray());
                                ins.setString(3, com.ccnrcom.rp.util.JsonUtil.GSON.toJson(wrapper));
                                ins.setInt(4, i);
                                ins.addBatch();
                            }
                        }
                        ins.executeBatch();
                    }
                });
            } catch (Exception e) {
                LOGGER.error("[CCNR-RP] pending_notices 保存失败: {}", e.toString());
            }
            return;
        }
        JsonUtil.atomicWrite(file, root);
    }
}
