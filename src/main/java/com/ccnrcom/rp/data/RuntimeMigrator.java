/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.data;

import com.ccnrcom.rp.experience.XpChangeList.XpChange;
import com.ccnrcom.rp.status.CharacterStatus;
import com.ccnrcom.rp.user.UserService.UserProfile;
import com.ccnrcom.rp.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** 运行时数据迁移（P2）：把 world/ccnr_rp/{user_profiles,pending_notices,team_wave_done}.json 幂等迁入数据库。 */
public final class RuntimeMigrator {

    private static final Logger LOGGER = LogManager.getLogger();

    private RuntimeMigrator() {}

    /** 导入三个运行时文件到库（行级幂等）。返回迁移计数。 */
    public static int migrate(Database db, Path worldDir) {
        int n = 0;
        n += migrateUsers(db, worldDir);
        n += migrateNotices(db, worldDir);
        n += migrateTeams(db, worldDir);
        return n;
    }

    private static int migrateUsers(Database db, Path worldDir) {
        Path file = worldDir.resolve("user_profiles.json");
        if (!Files.exists(file)) {
            return 0;
        }
        JsonObject root = JsonUtil.readObject(file).orElse(null);
        if (root == null || !root.has("users")) {
            return 0;
        }
        Map<String, UserProfile> profiles = new LinkedHashMap<>();
        root.getAsJsonObject("users").entrySet().forEach(e -> {
            JsonObject o = e.getValue().getAsJsonObject();
            List<XpChange> pending = new ArrayList<>();
            if (o.has("pendingXp") && o.get("pendingXp").isJsonArray()) {
                for (var el : o.getAsJsonArray("pendingXp")) {
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    JsonObject c = el.getAsJsonObject();
                    try {
                        String ruleId = c.has("ruleId") ? c.get("ruleId").getAsString() : "";
                        String title = c.has("title") ? c.get("title").getAsString() : "";
                        long value = c.has("value") ? c.get("value").getAsLong() : 0;
                        if (!ruleId.isBlank()) {
                            pending.add(new XpChange(ruleId, title, value));
                        }
                    } catch (Exception ignored) {
                        // 坏条目跳过
                    }
                }
            }
            profiles.put(
                    e.getKey(),
                    new UserProfile(
                            num(o, "xp", 0),
                            num(o, "lastCreateAt", 0),
                            o.has("anySupportRevive")
                                    && o.get("anySupportRevive").getAsBoolean(),
                            CharacterStatus.parse(str(o, "status", "observing")),
                            str(o, "professionId", ""),
                            str(o, "factionId", ""),
                            num(o, "cooldownUntil", 0),
                            num(o, "dutySeconds", 0),
                            pending));
        });
        new UserRepository(db).saveAll(profiles);
        LOGGER.info("[CCNR-RP] 已迁移用户档案 {} 条", profiles.size());
        return profiles.size();
    }

    private static int migrateNotices(Database db, Path worldDir) {
        Path file = worldDir.resolve("pending_notices.json");
        if (!Files.exists(file)) {
            return 0;
        }
        JsonObject root = JsonUtil.readObject(file).orElse(null);
        if (root == null) {
            return 0;
        }
        int[] counter = {0};
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
                        if (!entry.getValue().isJsonArray()) {
                            continue;
                        }
                        int seq = 0;
                        for (var el : entry.getValue().getAsJsonArray()) {
                            JsonObject n2 = el.getAsJsonObject();
                            ins.setString(1, entry.getKey());
                            ins.setString(2, n2.has("key") ? n2.get("key").getAsString() : "");
                            JsonObject wrapper = new JsonObject();
                            wrapper.add("args", n2.has("args") ? n2.get("args") : new JsonArray());
                            ins.setString(3, JsonUtil.GSON.toJson(wrapper));
                            ins.setInt(4, seq++);
                            ins.addBatch();
                            counter[0]++;
                        }
                    }
                    ins.executeBatch();
                }
            });
        } catch (Exception e) {
            LOGGER.error("[CCNR-RP] pending_notices 迁移失败: {}", e.toString());
        }
        LOGGER.info("[CCNR-RP] 已迁移挂起通知 {} 条", counter[0]);
        return counter[0];
    }

    private static int migrateTeams(Database db, Path worldDir) {
        Path file = worldDir.resolve("team_wave_done.json");
        if (!Files.exists(file)) {
            return 0;
        }
        JsonObject root = JsonUtil.readObject(file).orElse(null);
        if (root == null || !root.has("teams")) {
            return 0;
        }
        int[] counter = {0};
        try {
            db.write(c -> {
                try (PreparedStatement del =
                        c.prepareStatement("DELETE FROM " + db.dialect().quote("team_waves_done"))) {
                    del.executeUpdate();
                }
                try (PreparedStatement ins =
                        c.prepareStatement("INSERT INTO " + db.dialect().quote("team_waves_done") + " ("
                                + db.dialect().quote("team_id") + ") VALUES (?)")) {
                    for (var e : root.getAsJsonObject("teams").entrySet()) {
                        ins.setString(1, e.getKey());
                        ins.addBatch();
                        counter[0]++;
                    }
                    ins.executeBatch();
                }
            });
        } catch (Exception e) {
            LOGGER.error("[CCNR-RP] team_waves_done 迁移失败: {}", e.toString());
        }
        LOGGER.info("[CCNR-RP] 已迁移队伍触发记录 {} 条", counter[0]);
        return counter[0];
    }

    private static long num(JsonObject o, String key, long def) {
        try {
            return o.has(key) ? o.get(key).getAsLong() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }
}
