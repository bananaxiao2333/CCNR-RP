/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.data;

import com.ccnrcom.rp.experience.XpChangeList.XpChange;
import com.ccnrcom.rp.status.CharacterStatus;
import com.ccnrcom.rp.user.UserService.UserProfile;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 用户档案关系表仓储（P2）：users + user_pending_xp。UserService 保持内存 Map 工作状态，
 * load() 从库读回、save() 经本仓储批量落库（写后置）。
 */
public final class UserRepository {

    private static final Logger LOGGER = LogManager.getLogger();

    private final Database db;

    public UserRepository(Database db) {
        this.db = db;
    }

    /** 读取全部用户档案（含 pendingXp）。 */
    public Map<String, UserProfile> loadAll() {
        return db.read(c -> loadAll(c));
    }

    /** 保存全部用户档案与 pendingXp（事务：users upsert + 重建 pendingXp）。 */
    public void saveAll(Map<String, UserProfile> profiles) {
        try {
            db.write(c -> saveAll(c, profiles));
        } catch (Exception e) {
            LOGGER.error("[CCNR-RP] 用户档案保存失败: {}", e.toString());
        }
    }

    private Map<String, UserProfile> loadAll(Connection c) throws SQLException {
        Map<String, UserProfile> out = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT uuid, xp, last_create_at, any_support_revive, status, profession_id, faction_id, cooldown_until, duty_seconds FROM "
                        + db.dialect().quote("users"))) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String uuid = rs.getString("uuid");
                    List<XpChange> pending = loadPending(c, uuid);
                    out.put(
                            uuid,
                            new UserProfile(
                                    rs.getLong("xp"),
                                    rs.getLong("last_create_at"),
                                    rs.getInt("any_support_revive") != 0,
                                    CharacterStatus.parse(rs.getString("status")),
                                    rs.getString("profession_id"),
                                    rs.getString("faction_id"),
                                    rs.getLong("cooldown_until"),
                                    rs.getLong("duty_seconds"),
                                    pending));
                }
            }
        }
        return out;
    }

    private List<XpChange> loadPending(Connection c, String uuid) throws SQLException {
        List<XpChange> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT rule_id, title, value FROM "
                + db.dialect().quote("user_pending_xp") + " WHERE "
                + db.dialect().quote("uuid") + "=? ORDER BY " + db.dialect().quote("seq"))) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String ruleId = rs.getString("rule_id");
                    if (ruleId == null || ruleId.isBlank()) {
                        continue;
                    }
                    out.add(new XpChange(
                            ruleId, rs.getString("title") == null ? "" : rs.getString("title"), rs.getLong("value")));
                }
            }
        }
        return out;
    }

    private void saveAll(Connection c, Map<String, UserProfile> profiles) throws SQLException {
        boolean oldAuto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            String upsert = db.dialect()
                    .upsert(
                            "users",
                            List.of(
                                    "uuid",
                                    "xp",
                                    "last_create_at",
                                    "any_support_revive",
                                    "status",
                                    "profession_id",
                                    "faction_id",
                                    "cooldown_until",
                                    "duty_seconds"),
                            List.of("uuid"));
            try (PreparedStatement ps = c.prepareStatement(upsert)) {
                for (Map.Entry<String, UserProfile> e : profiles.entrySet()) {
                    UserProfile p = e.getValue();
                    ps.setString(1, e.getKey());
                    ps.setLong(2, p.xp());
                    ps.setLong(3, p.lastCreateAt());
                    ps.setInt(4, p.anySupportRevive() ? 1 : 0);
                    ps.setString(5, p.status().name().toLowerCase(java.util.Locale.ROOT));
                    ps.setString(6, p.professionId() == null ? "" : p.professionId());
                    ps.setString(7, p.factionId() == null ? "" : p.factionId());
                    ps.setLong(8, p.cooldownUntil());
                    ps.setLong(9, p.dutySeconds());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            // 重建 pendingXp：先删后插（该用户）
            try (PreparedStatement del =
                    c.prepareStatement("DELETE FROM " + db.dialect().quote("user_pending_xp"))) {
                del.executeUpdate();
            }
            try (PreparedStatement ins = c.prepareStatement("INSERT INTO "
                    + db.dialect().quote("user_pending_xp") + " ("
                    + db.dialect().quote("uuid") + ", " + db.dialect().quote("rule_id") + ", "
                    + db.dialect().quote("title") + ", " + db.dialect().quote("value") + ", "
                    + db.dialect().quote("seq") + ") VALUES (" + db.dialect().placeholders(5) + ")")) {
                for (Map.Entry<String, UserProfile> e : profiles.entrySet()) {
                    List<XpChange> pend = e.getValue().pendingXp();
                    for (int i = 0; i < pend.size(); i++) {
                        XpChange x = pend.get(i);
                        ins.setString(1, e.getKey());
                        ins.setString(2, x.ruleId());
                        ins.setString(3, x.title() == null ? "" : x.title());
                        ins.setLong(4, x.value());
                        ins.setInt(5, i);
                        ins.addBatch();
                    }
                }
                ins.executeBatch();
            }
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(oldAuto);
        }
    }
}
