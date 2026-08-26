/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.experience;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.config.CCNRRPConfig;
import com.ccnrcom.rp.experience.ExprParser.Expr;
import com.ccnrcom.rp.experience.XpChangeList.XpChange;
import com.ccnrcom.rp.network.RpChannels;
import com.ccnrcom.rp.network.RpPackets;
import com.ccnrcom.rp.status.CharacterStatus;
import com.ccnrcom.rp.user.UserService;
import com.ccnrcom.rp.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 经验服务 v3（事件广播 + 规则引擎，v2.18.0）：<br>
 * 1) 事件广播：character_alive（每 60 秒对每个 ALIVE 用户）/ character_kill / character_death
 *    （结算开始前赋予）；<br>
 * 2) 规则引擎：订阅事件 → 判断表达式（可选，false 跳过）→ 数值表达式 + 标题表达式 →
 *    并入该用户的待结算经验变化列表（同规则合并、标题取后来者）；<br>
 * 3) 结算：列表求和（可为负）计入用户累计 XP 后清空列表（幂等）；触发点 = /rp settle、
 *    死亡/断联退场（事件结束与游戏结束不再自动结算）。<br>
 * 规则存 config/ccnr_rp/experience_rules.json（管理员可编辑，管理面板热重载）。
 */
public final class ExperienceService {
    private static final Logger LOGGER = LogManager.getLogger();

    /** character_alive 事件发布间隔（秒）。 */
    public static final int ALIVE_INTERVAL_SECONDS = 60;

    /** 一次用户结算的汇总结果。 */
    public record SettleSummary(long gain, long newXp, int newLevel, List<XpChange> changes) {}

    private final MinecraftServer server;
    private final PendingNoticeStore pending;
    private final Path rulesFile;
    private List<CompiledRule> rules = List.of();
    private long tickCounter = 0;
    private long aliveEmitCounter = 0;
    /** 限频 WARN：ruleId → 上次告警时间。 */
    private final Map<String, Long> warnAt = new HashMap<>();

    /** 预编译规则（表达式只解析一次）。 */
    private record CompiledRule(ExperienceRule rule, Expr condition, Expr value, Expr title) {}

    public ExperienceService(MinecraftServer server) {
        this.server = server;
        var worldDir = server.getWorldPath(new net.minecraft.world.level.storage.LevelResource("ccnr_rp"));
        this.pending = new PendingNoticeStore(worldDir);
        this.rulesFile = FMLPaths.CONFIGDIR.get().resolve("ccnr_rp").resolve("experience_rules.json");
        loadRules();
    }

    // ------------------------------------------------------------------ 规则存取

    private void loadRules() {
        JsonObject root = JsonUtil.readObject(rulesFile).orElse(null);
        if (root == null || !root.has("rules") || !root.get("rules").isJsonArray()) {
            // 缺失/损坏：写默认规则（贴近 v2 默认体验：值班/击杀/阵亡）
            List<ExperienceRule> defaults = ExperienceRule.DEFAULT_RULES;
            JsonUtil.atomicWrite(rulesFile, ExperienceRule.toJson(defaults));
            LOGGER.warn("[CCNR-RP] 经验规则缺失/损坏，已写入默认规则（{} 条）", defaults.size());
            this.rules = defaults.stream().map(this::compile).toList();
            return;
        }
        List<String> errors = new ArrayList<>();
        List<ExperienceRule> loaded = ExperienceRule.readAll(root, errors);
        List<ExperienceRule> ok = new ArrayList<>();
        for (ExperienceRule r : loaded) {
            List<String> errs = ExperienceRule.validate(r);
            if (!errs.isEmpty()) {
                errors.add("规则 " + r.id() + ": " + String.join("; ", errs));
            } else {
                ok.add(r);
            }
        }
        for (String e : errors) {
            LOGGER.error("[CCNR-RP] {}: {}", rulesFile, e);
        }
        this.rules = ok.stream().map(this::compile).toList();
        LOGGER.info("[CCNR-RP] 经验规则加载完成：{} 条生效，{} 条跳过", rules.size(), errors.size());
    }

    private CompiledRule compile(ExperienceRule r) {
        Expr cond =
                (r.conditionExpr() == null || r.conditionExpr().isBlank()) ? null : ExprParser.parse(r.conditionExpr());
        Expr value = ExprParser.parse(r.valueExpr());
        Expr title = ExprParser.parse(r.titleExpr());
        return new CompiledRule(r, cond, value, title);
    }

    /** 当前规则 JSON（管理面板展示）。 */
    public JsonObject rulesPayload() {
        return ExperienceRule.toJson(rules.stream().map(CompiledRule::rule).toList());
    }

    /** 规则 CRUD（管理面板 C2S 入口）：校验 → 落盘 → 热重载 → 广播；返回错误列表（空=成功）。 */
    public List<String> applyRulesEdit(JsonObject payload) {
        List<String> errors = new ArrayList<>();
        String action = payload.has("action") ? payload.get("action").getAsString() : "";
        try {
            List<ExperienceRule> next =
                    new ArrayList<>(rules.stream().map(CompiledRule::rule).toList());
            switch (action) {
                case "add", "update" -> {
                    JsonObject ro = payload.has("rule") && payload.get("rule").isJsonObject()
                            ? payload.getAsJsonObject("rule")
                            : new JsonObject();
                    ExperienceRule r = ExperienceRule.from(ro);
                    List<String> errs = ExperienceRule.validate(r);
                    if (!errs.isEmpty()) {
                        return errs; // 校验失败：拒存，回显原因
                    }
                    boolean replaced = false;
                    for (int i = 0; i < next.size(); i++) {
                        if (next.get(i).id().equals(r.id())) {
                            next.set(i, r);
                            replaced = true;
                            break;
                        }
                    }
                    if (!replaced) {
                        next.add(r);
                    }
                }
                case "remove" -> {
                    String id = payload.has("id") ? payload.get("id").getAsString() : "";
                    next.removeIf(r -> r.id().equals(id));
                }
                case "toggle" -> {
                    String id = payload.has("id") ? payload.get("id").getAsString() : "";
                    for (int i = 0; i < next.size(); i++) {
                        ExperienceRule r = next.get(i);
                        if (r.id().equals(id)) {
                            next.set(
                                    i,
                                    new ExperienceRule(
                                            r.id(),
                                            !r.enabled(),
                                            r.eventId(),
                                            r.conditionExpr(),
                                            r.valueExpr(),
                                            r.titleExpr()));
                        }
                    }
                }
                default -> errors.add("未知操作: " + action);
            }
            if (errors.isEmpty()) {
                JsonUtil.atomicWrite(rulesFile, ExperienceRule.toJson(next));
                this.rules = next.stream().map(this::compile).toList();
                broadcastRules();
                LOGGER.info("[CCNR-RP] 经验规则已更新：{} 条生效", rules.size());
            }
        } catch (Exception e) {
            errors.add(e.getMessage() == null ? "操作失败" : e.getMessage());
        }
        return errors;
    }

    private void broadcastRules() {
        RpChannels.sendToAll(new RpPackets.RulesStateS2C(rulesPayload().toString()));
    }

    /** 管理面板规则编辑 C2S 处理（服务端权限二次校验）。 */
    public static void onRuleEdit(ServerPlayer player, String payload) {
        if (player == null || CCNRRPMod.experience == null) {
            return;
        }
        if (!com.ccnrcom.rp.util.Permissions.canAdmin(player, com.ccnrcom.rp.util.Permissions.ADMIN_XP)) {
            RpChannels.sendTo(player, new RpPackets.ErrorS2C("ccnr_rp.command.no_permission"));
            return;
        }
        JsonObject req = JsonUtil.GSON.fromJson(payload, JsonObject.class);
        List<String> errors = CCNRRPMod.experience.applyRulesEdit(req == null ? new JsonObject() : req);
        if (errors.isEmpty()) {
            RpChannels.sendTo(player, new RpPackets.ErrorS2C("ccnr_rp.xp.rules.saved"));
        } else {
            for (String e : errors) {
                RpChannels.sendTo(player, new RpPackets.ErrorS2C("ccnr_rp.xp.rules.error", e));
            }
        }
    }

    // ------------------------------------------------------------------ 事件广播

    /** 每 60 秒对每个 ALIVE 用户发布 character_alive（参数含本回合累计存活秒数）。 */
    private void emitAliveEvents() {
        UserService users = CCNRRPMod.users;
        if (users == null) {
            return;
        }
        boolean any = false;
        for (String uuid : users.uuids()) {
            if (users.status(uuid) == CharacterStatus.ALIVE) {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("uuid", uuid);
                params.put("playerName", playerName(uuid));
                params.put("professionId", str(users.professionId(uuid)));
                params.put("factionId", str(users.factionId(uuid)));
                params.put("aliveSeconds", users.dutySeconds(uuid));
                params.put("intervalSeconds", ALIVE_INTERVAL_SECONDS);
                if (applyEvent("character_alive", params, uuid)) {
                    any = true;
                }
            }
        }
        if (any) {
            users.save();
        }
    }

    /** character_kill：玩家击杀任意生物 → 击杀者（StatusManager.onLivingDeath 调用）。 */
    public void emitKill(String killerUuid, ServerPlayer killer, LivingEntity victim) {
        UserService users = CCNRRPMod.users;
        if (users == null || killerUuid == null || victim == null) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("uuid", killerUuid);
        params.put("playerName", killer.getName().getString());
        params.put("professionId", str(users.professionId(killerUuid)));
        params.put("factionId", str(users.factionId(killerUuid)));
        params.put("victimType", victimType(victim));
        params.put("victimName", victim.getName().getString());
        String vu = victim instanceof ServerPlayer sp ? sp.getUUID().toString() : "";
        params.put("victimUuid", vu);
        params.put("victimProfessionId", vu.isEmpty() || !users.hasProfile(vu) ? "" : str(users.professionId(vu)));
        params.put("victimFactionId", vu.isEmpty() || !users.hasProfile(vu) ? "" : str(users.factionId(vu)));
        if (applyEvent("character_kill", params, killerUuid)) {
            users.save();
        }
    }

    /** character_death：角色死亡/掉线判死，结算开始前赋予死者（StatusManager 调用）。 */
    public void emitDeath(String uuid, String reason) {
        UserService users = CCNRRPMod.users;
        if (users == null || uuid == null) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("uuid", uuid);
        params.put("playerName", playerName(uuid));
        params.put("professionId", str(users.professionId(uuid)));
        params.put("factionId", str(users.factionId(uuid)));
        params.put("reason", reason == null ? "" : reason);
        if (applyEvent("character_death", params, uuid)) {
            users.save();
        }
    }

    /**
     * 管理员手动记分（/rp xp add）：向用户待结算列表追加一条自定义标题/数值的变化（可为负）。
     * 同标题条目合并（数值相加、标题取后来者）；返回错误消息（空 = 成功）。
     */
    public String addManualScore(String playerUuid, String title, long value) {
        UserService users = CCNRRPMod.users;
        if (users == null || playerUuid == null || title == null || title.isBlank()) {
            return "ccnr_rp.xp.add.invalid";
        }
        if (!users.hasProfile(playerUuid)) {
            return "ccnr_rp.error.player_not_found";
        }
        String ruleId = "manual:" + title; // 同标题合并；与规则条目（规则 id 前缀不同）不冲突
        List<XpChange> list = XpChangeList.add(users.pendingXp(playerUuid), ruleId, title, value);
        users.setPendingXp(playerUuid, list);
        users.save();
        pushXpList(playerUuid);
        return "";
    }

    /** 规则引擎核心：订阅匹配规则 → 判断 → 求值 → 并入列表；返回是否有变化（不落盘，由调用方决定）。 */
    private boolean applyEvent(String eventId, Map<String, Object> params, String targetUuid) {
        UserService users = CCNRRPMod.users;
        if (users == null || targetUuid == null) {
            return false;
        }
        List<XpChange> list = new ArrayList<>(users.pendingXp(targetUuid));
        boolean changed = false;
        for (CompiledRule c : rules) {
            ExperienceRule r = c.rule();
            if (!r.enabled() || !r.eventId().equals(eventId)) {
                continue;
            }
            try {
                if (c.condition() != null && !ExprEvaluator.evalBool(c.condition(), params)) {
                    continue; // 判断不满足：本条规则本次跳过
                }
                double v = ExprEvaluator.evalNum(c.value(), params);
                String title = ExprEvaluator.evalString(c.title(), params);
                list = XpChangeList.add(list, r.id(), title, Math.round(v));
                changed = true;
            } catch (ExprException e) {
                warnLimited(r.id(), e);
            }
        }
        if (changed) {
            users.setPendingXp(targetUuid, list);
            pushXpList(targetUuid);
        }
        return changed;
    }

    /** 限频告警：同一条规则 60 秒内最多刷一次。 */
    private void warnLimited(String ruleId, ExprException e) {
        long now = System.currentTimeMillis();
        if (now - warnAt.getOrDefault(ruleId, 0L) > 60_000L) {
            warnAt.put(ruleId, now);
            LOGGER.warn("[CCNR-RP] 经验规则 {} 求值失败（跳过本次）: {}", ruleId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 值班与 alive 事件

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || CCNRRPMod.users == null) {
            return;
        }
        if (++tickCounter < 20) {
            return;
        }
        tickCounter = 0;
        UserService users = CCNRRPMod.users;
        // 每秒：ALIVE 用户本回合存活秒数 +1（部署时清零；不逐秒落盘，结算/部署/事件变更时持久化）
        for (String uuid : users.uuids()) {
            if (users.status(uuid) == CharacterStatus.ALIVE) {
                users.setXpDuty(uuid, users.userXp(uuid), users.dutySeconds(uuid) + 1);
            }
        }
        if (++aliveEmitCounter >= ALIVE_INTERVAL_SECONDS) {
            aliveEmitCounter = 0;
            emitAliveEvents();
        }
    }

    // ------------------------------------------------------------------ 结算

    /** 结算某用户（增量幂等：列表求和后清空）；返回汇总（null = 用户服务未就绪）。 */
    public SettleSummary settleUser(String playerUuid, boolean notifyOwner) {
        if (CCNRRPMod.users == null) {
            return null;
        }
        UserService users = CCNRRPMod.users;
        List<XpChange> changes = List.copyOf(users.pendingXp(playerUuid));
        long gain = XpChangeList.sum(changes);
        LevelCurve curve = new LevelCurve(CCNRRPConfig.LEVEL_BASE.get(), CCNRRPConfig.LEVEL_POW.get());
        long before = users.userXp(playerUuid);
        long newXp = users.addXp(playerUuid, gain);
        int newLevel = curve.level(newXp);
        // 列表结算即清空（幂等：重复 settle 无增量）
        users.setPendingXp(playerUuid, List.of());
        users.save();

        ServerPlayer owner = online(playerUuid);
        if (owner != null) {
            RpChannels.sendTo(owner, new RpPackets.UserXpS2C(newXp, newLevel));
            if (!changes.isEmpty()) {
                RpChannels.sendTo(owner, new RpPackets.XpSettleAnimS2C(xpPayload(changes, newXp)));
            } else {
                pushXpList(playerUuid);
            }
        }
        if (curve.level(before) < newLevel) {
            com.ccnrcom.rp.animation.AnimationHooks.levelUp(owner, newLevel);
        }
        return new SettleSummary(gain, newXp, newLevel, changes);
    }

    /** 死亡/断联/退役落定：结算该用户；在线看 HUD 结算动画，离线挂起上线补发。 */
    public void settleUserDown(String playerUuid, ServerPlayer ownerOrNull, String resultKey) {
        SettleSummary s = settleUser(playerUuid, true);
        if (s == null) {
            return;
        }
        // 结算完成后强制刷成观察者身份（防任何路径残留非观察状态）
        if (CCNRRPMod.users != null && CCNRRPMod.users.status(playerUuid) != CharacterStatus.OBSERVING) {
            CCNRRPMod.users.setStatus(playerUuid, CharacterStatus.OBSERVING);
            CCNRRPMod.users.save();
        }
        // 在线：HUD 结算动画已下发（XpSettleAnimS2C）；离线：挂起，登录补发（flushPending）
        if (ownerOrNull == null) {
            pending.store(playerUuid, resultKey, new String[] {
                String.valueOf(s.gain()), String.valueOf(s.newXp()), String.valueOf(s.newLevel())
            });
        }
    }

    /** 上线补发离线期间的结算通知（发送即删除）。 */
    public void flushPending(ServerPlayer player) {
        for (Object[] n : pending.drain(player.getUUID().toString())) {
            RpChannels.sendTo(player, new RpPackets.ErrorS2C((String) n[0], (String[]) n[1]));
        }
    }

    /** 结算全部（all）或单个玩家；单条坏档异常隔离，不阻塞整批。 */
    public List<SettleSummary> settleAll(String playerUuidOrNull) {
        List<SettleSummary> results = new ArrayList<>();
        if (CCNRRPMod.users == null) {
            return results;
        }
        for (String uuid : CCNRRPMod.users.uuids()) {
            if (playerUuidOrNull == null || uuid.equals(playerUuidOrNull)) {
                try {
                    results.add(settleUser(uuid, false));
                } catch (Exception ex) {
                    LOGGER.error("[CCNR-RP] 结算失败（跳过该用户，不影响整批）: {}", uuid, ex);
                }
            }
        }
        return results;
    }

    // ------------------------------------------------------------------ 客户端推送

    /** 推送当前待结算列表 + 当前总经验（HUD 常驻显示）。 */
    private void pushXpList(String playerUuid) {
        ServerPlayer owner = online(playerUuid);
        if (owner == null || CCNRRPMod.users == null) {
            return;
        }
        RpChannels.sendTo(
                owner,
                new RpPackets.XpListS2C(
                        xpPayload(CCNRRPMod.users.pendingXp(playerUuid), CCNRRPMod.users.userXp(playerUuid))));
    }

    /** 列表 + 总经验 JSON：{total, items:[{title,value}]}。 */
    private String xpPayload(List<XpChange> changes, long total) {
        JsonObject o = new JsonObject();
        o.addProperty("total", total);
        JsonArray arr = new JsonArray();
        for (XpChange c : changes) {
            JsonObject item = new JsonObject();
            item.addProperty("title", c.title() == null ? "" : c.title());
            item.addProperty("value", c.value());
            arr.add(item);
        }
        o.add("items", arr);
        return o.toString();
    }

    // ------------------------------------------------------------------ 工具

    /** 在线玩家（按 uuid）；不存在返回 null。 */
    private ServerPlayer online(String playerUuid) {
        try {
            return server.getPlayerList().getPlayer(java.util.UUID.fromString(playerUuid));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String playerName(String playerUuid) {
        ServerPlayer p = online(playerUuid);
        return p == null ? "" : p.getName().getString();
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    /** 实体类型短名（去掉命名空间，如 zombie），供规则比较 victimType。 */
    private static String victimType(LivingEntity victim) {
        try {
            return victim.getType().builtInRegistryHolder().key().location().getPath();
        } catch (Exception e) {
            return victim.getType().toString();
        }
    }
}
