/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.user;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.config.CCNRRPConfig;
import com.ccnrcom.rp.experience.XpChangeList.XpChange;
import com.ccnrcom.rp.status.CharacterStatus;
import com.ccnrcom.rp.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户体系（P9 v2，v2.18.0 经验系统 v3）：一个玩家 UUID = 一个用户；在场/阴间/观察状态、
 * 当前职位、复活冷却、执勤时长（本回合存活秒数）、待结算经验变化列表全部挂在用户上。
 * 等级/经验随用户走。持久化：world/ccnr_rp/user_profiles.json（version 3，原子写）。
 */
public final class UserService {

    /** 用户档案（纯数据）。 */
    public record UserProfile(
            long xp,
            long lastCreateAt,
            boolean anySupportRevive,
            CharacterStatus status,
            String professionId,
            String factionId,
            long cooldownUntil,
            long dutySeconds,
            List<XpChange> pendingXp) {

        public UserProfile {
            pendingXp = pendingXp == null ? List.of() : List.copyOf(pendingXp);
        }

        public UserProfile withXp(long newXp) {
            return new UserProfile(
                    newXp,
                    lastCreateAt,
                    anySupportRevive,
                    status,
                    professionId,
                    factionId,
                    cooldownUntil,
                    dutySeconds,
                    pendingXp);
        }

        public UserProfile withLastCreate(long at) {
            return new UserProfile(
                    xp, at, anySupportRevive, status, professionId, factionId, cooldownUntil, dutySeconds, pendingXp);
        }

        public UserProfile withAnySupport(boolean on) {
            return new UserProfile(
                    xp, lastCreateAt, on, status, professionId, factionId, cooldownUntil, dutySeconds, pendingXp);
        }

        public UserProfile withStatus(CharacterStatus newStatus) {
            return new UserProfile(
                    xp,
                    lastCreateAt,
                    anySupportRevive,
                    newStatus,
                    professionId,
                    factionId,
                    cooldownUntil,
                    dutySeconds,
                    pendingXp);
        }

        public UserProfile withRole(String newProfessionId, String newFactionId) {
            return new UserProfile(
                    xp,
                    lastCreateAt,
                    anySupportRevive,
                    status,
                    newProfessionId,
                    newFactionId,
                    cooldownUntil,
                    dutySeconds,
                    pendingXp);
        }

        public UserProfile withCooldown(long cooldown) {
            return new UserProfile(
                    xp,
                    lastCreateAt,
                    anySupportRevive,
                    status,
                    professionId,
                    factionId,
                    cooldown,
                    dutySeconds,
                    pendingXp);
        }

        public UserProfile withXpDuty(long newXp, long newDutySeconds) {
            return new UserProfile(
                    xp,
                    lastCreateAt,
                    anySupportRevive,
                    status,
                    professionId,
                    factionId,
                    cooldownUntil,
                    newDutySeconds,
                    pendingXp);
        }

        public UserProfile withPendingXp(List<XpChange> newPending) {
            return new UserProfile(
                    xp,
                    lastCreateAt,
                    anySupportRevive,
                    status,
                    professionId,
                    factionId,
                    cooldownUntil,
                    dutySeconds,
                    newPending);
        }
    }

    private final Path file;
    private final Map<String, UserProfile> profiles = new HashMap<>();

    public UserService(Path worldDir) {
        this.file = worldDir.resolve("user_profiles.json");
        load();
    }

    // ---------- 持久化 ----------

    private void load() {
        profiles.clear();
        JsonUtil.readObject(file).ifPresent(root -> {
            if (root.has("users")) {
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
                                String ruleId =
                                        c.has("ruleId") ? c.get("ruleId").getAsString() : "";
                                String title = c.has("title") ? c.get("title").getAsString() : "";
                                long value = c.has("value") ? c.get("value").getAsLong() : 0;
                                if (!ruleId.isBlank()) {
                                    pending.add(new XpChange(ruleId, title, value));
                                }
                            } catch (Exception ignored) {
                                // 坏条目跳过（容错）
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
            }
        });
    }

    public void save() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 3);
        JsonObject users = new JsonObject();
        profiles.forEach((uuid, p) -> {
            JsonObject o = new JsonObject();
            o.addProperty("xp", p.xp());
            o.addProperty("lastCreateAt", p.lastCreateAt());
            o.addProperty("anySupportRevive", p.anySupportRevive());
            o.addProperty("status", p.status().name().toLowerCase(java.util.Locale.ROOT));
            if (p.professionId() != null && !p.professionId().isBlank()) {
                o.addProperty("professionId", p.professionId());
            }
            if (p.factionId() != null && !p.factionId().isBlank()) {
                o.addProperty("factionId", p.factionId());
            }
            o.addProperty("cooldownUntil", p.cooldownUntil());
            o.addProperty("dutySeconds", p.dutySeconds());
            JsonArray pend = new JsonArray();
            for (XpChange c : p.pendingXp()) {
                JsonObject co = new JsonObject();
                co.addProperty("ruleId", c.ruleId());
                co.addProperty("title", c.title() == null ? "" : c.title());
                co.addProperty("value", c.value());
                pend.add(co);
            }
            o.add("pendingXp", pend);
            users.add(uuid, o);
        });
        root.add("users", users);
        JsonUtil.atomicWrite(file, root);
    }

    private UserProfile profile(String playerUuid) {
        return profiles.computeIfAbsent(
                playerUuid, k -> new UserProfile(0, 0, false, CharacterStatus.OBSERVING, "", "", 0, 0, List.of()));
    }

    /** 是否已有用户档案（首次入服判定用；不惰性创建档案）。 */
    public boolean hasProfile(String playerUuid) {
        return playerUuid != null && profiles.containsKey(playerUuid);
    }

    // ---------- 经验（随用户走） ----------

    public long userXp(String playerUuid) {
        return profile(playerUuid).xp();
    }

    /** 结算增益归入用户；支持负数（扣分）。返回新的用户总经验（下限 0）。 */
    public long addXp(String playerUuid, long gain) {
        if (gain == 0) {
            return userXp(playerUuid);
        }
        UserProfile p = profile(playerUuid);
        long next = Math.max(0, p.xp() + gain);
        profiles.put(playerUuid, p.withXp(next));
        return next;
    }

    public int level(String playerUuid) {
        return new com.ccnrcom.rp.experience.LevelCurve(CCNRRPConfig.LEVEL_BASE.get(), CCNRRPConfig.LEVEL_POW.get())
                .level(userXp(playerUuid));
    }

    // ---------- 待结算经验变化列表（经验系统 v3） ----------

    public List<XpChange> pendingXp(String playerUuid) {
        return profile(playerUuid).pendingXp();
    }

    public void setPendingXp(String playerUuid, List<XpChange> pending) {
        UserProfile p = profile(playerUuid);
        profiles.put(playerUuid, p.withPendingXp(pending));
    }

    // ---------- 状态（在场/阴间/观察） ----------

    public CharacterStatus status(String playerUuid) {
        return profile(playerUuid).status();
    }

    public void setStatus(String playerUuid, CharacterStatus status) {
        UserProfile p = profile(playerUuid);
        if (p.status() == status) {
            return; // 幂等：无变化不触发刷新
        }
        profiles.put(playerUuid, p.withStatus(status));
        notifyNametagChanged();
    }

    /** 状态/职位变化 → 异步刷新全服头顶悬浮标签（死亡/复活/部署/换岗后其他玩家头顶立即更新）。 */
    private static void notifyNametagChanged() {
        if (CCNRRPMod.characters != null) {
            CCNRRPMod.characters.broadcastPlayerTags();
        }
    }

    public boolean isAlive(String playerUuid) {
        return status(playerUuid) == CharacterStatus.ALIVE;
    }

    public boolean isObserving(String playerUuid) {
        return status(playerUuid) == CharacterStatus.OBSERVING;
    }

    // ---------- 当前职位 ----------

    public String professionId(String playerUuid) {
        return profile(playerUuid).professionId();
    }

    public String factionId(String playerUuid) {
        return profile(playerUuid).factionId();
    }

    public void setRole(String playerUuid, String professionId, String factionId) {
        UserProfile p = profile(playerUuid);
        if (java.util.Objects.equals(p.professionId(), professionId)
                && java.util.Objects.equals(p.factionId(), factionId)) {
            return; // 幂等：无变化不触发刷新
        }
        profiles.put(playerUuid, p.withRole(professionId, factionId));
        notifyNametagChanged();
    }

    // ---------- 复活冷却 ----------

    public long cooldownUntil(String playerUuid) {
        return profile(playerUuid).cooldownUntil();
    }

    public void setCooldown(String playerUuid, long cooldownUntil) {
        UserProfile p = profile(playerUuid);
        profiles.put(playerUuid, p.withCooldown(cooldownUntil));
    }

    public boolean onCooldown(String playerUuid) {
        return cooldownUntil(playerUuid) > System.currentTimeMillis();
    }

    // ---------- 执勤（本回合存活秒数） ----------

    public long dutySeconds(String playerUuid) {
        return profile(playerUuid).dutySeconds();
    }

    public void setXpDuty(String playerUuid, long xp, long dutySeconds) {
        UserProfile p = profile(playerUuid);
        profiles.put(playerUuid, p.withXpDuty(xp, dutySeconds));
    }

    // ---------- 枚举（settleAll / 影响预检使用） ----------

    /** 当前已登记的所有用户 UUID（含惰性创建的空档案）。 */
    public java.util.Set<String> uuids() {
        return java.util.Set.copyOf(profiles.keySet());
    }

    // ---------- 在职统计（部署人数限制用） ----------

    /** 指定职业的当前在职人数（status==ALIVE 且职业匹配；不含 TEMP 征召，征召不进用户库）。 */
    public int aliveCountByProfession(String professionId) {
        if (professionId == null) {
            return 0;
        }
        int n = 0;
        for (UserProfile p : profiles.values()) {
            if (p.status() == CharacterStatus.ALIVE && professionId.equals(p.professionId())) {
                n++;
            }
        }
        return n;
    }

    /** 指定阵营的当前在职人数（status==ALIVE 且阵营匹配）。 */
    public int aliveCountByFaction(String factionId) {
        if (factionId == null) {
            return 0;
        }
        int n = 0;
        for (UserProfile p : profiles.values()) {
            if (p.status() == CharacterStatus.ALIVE && factionId.equals(p.factionId())) {
                n++;
            }
        }
        return n;
    }

    // ---------- 支援身份开关 ----------

    public boolean anySupportRevive(String playerUuid) {
        return profile(playerUuid).anySupportRevive();
    }

    public void setAnySupportRevive(String playerUuid, boolean on) {
        UserProfile p = profile(playerUuid);
        profiles.put(playerUuid, p.withAnySupport(on));
        save();
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

    /** 静态入口（服务端生命周期由 CCNRRPMod 装配）。 */
    public static UserService service() {
        UserService s = CCNRRPMod.users;
        if (s == null) {
            throw new IllegalStateException("用户服务未初始化");
        }
        return s;
    }
}
