/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.user;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.config.CCNRRPConfig;
import com.ccnrcom.rp.status.CharacterStatus;
import com.ccnrcom.rp.util.JsonUtil;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户体系（P9，v2：删除角色实体后为唯一身份）。
 * 一个玩家 UUID = 一个用户；在场/阴间/观察状态、当前职位、复活冷却、执勤时长、任务与疏散裁定全部挂在用户上。
 * 等级/经验随用户走。创建角色（多角色库）已删除——玩家直接选职位部署。
 * 持久化：world/ccnr_rp/user_profiles.json（原子写）。
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
            Map<String, Integer> tasks,
            String evacuation) {

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
                    tasks,
                    evacuation);
        }

        public UserProfile withLastCreate(long at) {
            return new UserProfile(
                    xp,
                    at,
                    anySupportRevive,
                    status,
                    professionId,
                    factionId,
                    cooldownUntil,
                    dutySeconds,
                    tasks,
                    evacuation);
        }

        public UserProfile withAnySupport(boolean on) {
            return new UserProfile(
                    xp,
                    lastCreateAt,
                    on,
                    status,
                    professionId,
                    factionId,
                    cooldownUntil,
                    dutySeconds,
                    tasks,
                    evacuation);
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
                    tasks,
                    evacuation);
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
                    tasks,
                    evacuation);
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
                    tasks,
                    evacuation);
        }

        public UserProfile withXpDuty(long newXp, long newDutySeconds) {
            return new UserProfile(
                    newXp,
                    lastCreateAt,
                    anySupportRevive,
                    status,
                    professionId,
                    factionId,
                    cooldownUntil,
                    newDutySeconds,
                    tasks,
                    evacuation);
        }

        public UserProfile withEvacuation(String newEvacuation) {
            return new UserProfile(
                    xp,
                    lastCreateAt,
                    anySupportRevive,
                    status,
                    professionId,
                    factionId,
                    cooldownUntil,
                    dutySeconds,
                    tasks,
                    newEvacuation);
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
                    Map<String, Integer> tasks = new LinkedHashMap<>();
                    if (o.has("tasks") && o.get("tasks").isJsonObject()) {
                        o.getAsJsonObject("tasks")
                                .entrySet()
                                .forEach(t -> tasks.put(t.getKey(), t.getValue().getAsInt()));
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
                                    tasks,
                                    str(o, "evacuation", "none")));
                });
            }
        });
    }

    public void save() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 2);
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
            JsonObject t = new JsonObject();
            p.tasks().forEach((k, v) -> t.addProperty(k, v));
            o.add("tasks", t);
            o.addProperty("evacuation", p.evacuation() == null ? "none" : p.evacuation());
            users.add(uuid, o);
        });
        root.add("users", users);
        JsonUtil.atomicWrite(file, root);
    }

    private UserProfile profile(String playerUuid) {
        return profiles.computeIfAbsent(
                playerUuid,
                k -> new UserProfile(
                        0, 0, false, CharacterStatus.OBSERVING, "", "", 0, 0, new LinkedHashMap<>(), "none"));
    }

    // ---------- 经验（随用户走） ----------

    public long userXp(String playerUuid) {
        return profile(playerUuid).xp();
    }

    /** 结算增益归入用户；支持负数（扣分）。返回新的用户总经验。 */
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

    // ---------- 状态（在场/阴间/观察） ----------

    public CharacterStatus status(String playerUuid) {
        return profile(playerUuid).status();
    }

    public void setStatus(String playerUuid, CharacterStatus status) {
        UserProfile p = profile(playerUuid);
        profiles.put(playerUuid, p.withStatus(status));
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
        profiles.put(playerUuid, p.withRole(professionId, factionId));
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

    // ---------- 执勤 / 任务 / 疏散 ----------

    public long dutySeconds(String playerUuid) {
        return profile(playerUuid).dutySeconds();
    }

    public void setXpDuty(String playerUuid, long xp, long dutySeconds) {
        UserProfile p = profile(playerUuid);
        profiles.put(playerUuid, p.withXpDuty(xp, dutySeconds));
    }

    public Map<String, Integer> tasks(String playerUuid) {
        return profile(playerUuid).tasks();
    }

    public String evacuation(String playerUuid) {
        return profile(playerUuid).evacuation();
    }

    public void setEvacuation(String playerUuid, String evacuation) {
        UserProfile p = profile(playerUuid);
        profiles.put(playerUuid, p.withEvacuation(evacuation));
    }

    // ---------- 枚举（settleAll / 影响预检使用） ----------

    /** 当前已登记的所有用户 UUID（含惰性创建的空档案）。 */
    public java.util.Set<String> uuids() {
        return java.util.Set.copyOf(profiles.keySet());
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
