/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.event;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.animation.AnimationHooks;
import com.ccnrcom.rp.config.CCNRRPConfig;
import com.ccnrcom.rp.data.ConfigStore;
import com.ccnrcom.rp.event.EventModels.EventDefinition;
import com.ccnrcom.rp.event.EventModels.EventState;
import com.ccnrcom.rp.event.EventModels.GamePhase;
import com.ccnrcom.rp.event.EventModels.TriggerContext;
import com.ccnrcom.rp.network.RpChannels;
import com.ccnrcom.rp.network.RpPackets;
import com.ccnrcom.rp.status.CharacterStatus;
import com.ccnrcom.rp.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 事件管理（P6）：阶段时钟推进 → 触发器求值 → 事件生命周期 SCHEDULED→RUNNING→SETTLED；
 * 结束钩子：动画（P7）/刷新波（P8 预留）/自动结算（P5）。
 */
public final class EventManager {
    private static final Logger LOGGER = LogManager.getLogger();

    private final MinecraftServer server;
    private PhaseClock clock;
    private final List<EventDefinition> events = new ArrayList<>();
    /** 开局事件（start=true）：空窗期（未运行）仅可由它触发以重新开局。 */
    private final Set<String> startEventIds = new HashSet<>();
    /** 剧本是否在运行中：/rp end 后置 false（空窗期不自动触发，仅开局事件可重启）。 */
    private boolean running = true;
    /** 第 0 幕是否已发过「开始」信号（ON_PHASE_START 只触发一次/轮）。 */
    private boolean phaseZeroStarted = false;

    private long evalCounter = 0;
    private long startedAtMillis = System.currentTimeMillis();

    public EventManager(MinecraftServer server) {
        this.server = server;
        this.clock = new PhaseClock(loadPhases());
        loadEvents();
    }

    /** 热重载（管理器 CRUD 后调用）：重读 phases.json/events.json。 */
    public void reload() {
        events.clear();
        startEventIds.clear();
        clock = new PhaseClock(loadPhases());
        loadEvents();
        // 开局：定义了 start 事件 → 进入「待启」（running=false，等开局事件触发）；
        // 未定义 start 事件 → 立即运行（兼容旧行为：激活模式即开演）。
        running = startEventIds.isEmpty();
        phaseZeroStarted = false;
        syncRulesPhase();
        LOGGER.info("[CCNR-RP] 事件/阶段已热重载");
        broadcastState();
    }

    /** 当前激活事件 id 列表（RUNNING）。 */
    public List<String> activeEventIds() {
        return events.stream()
                .filter(e -> e.state() == EventState.RUNNING)
                .map(EventDefinition::id)
                .toList();
    }

    /** 推送激活事件横幅（全服在线）。 */
    public void broadcastState() {
        JsonObject pay = new JsonObject();
        JsonArray a = new JsonArray();
        activeEventIds().forEach(id -> a.add(id));
        pay.add("events", a);
        for (ServerPlayer p : onlinePlayers()) {
            RpChannels.sendTo(p, new RpPackets.EventStateS2C(pay.toString()));
        }
    }

    /** 清空当前事件（/rp event clear）：全部 RUNNING → SETTLED → 重置为 SCHEDULED 并广播横幅。 */
    public List<String> clearAll() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            EventDefinition def = events.get(i);
            if (def.state() == EventState.RUNNING) {
                events.set(i, def.withState(EventState.SETTLED));
                out.add(def.id());
            }
        }
        resetEvents();
        broadcastState();
        return out;
    }

    public PhaseClock clock() {
        return clock;
    }

    public List<EventDefinition> events() {
        return List.copyOf(events);
    }

    private JsonObject loadOrDefaults(String configKey, String resource) {
        JsonObject root = ConfigStore.load(configKey).orElseGet(JsonObject::new);
        if (root.size() == 0) {
            JsonObject d = JsonUtil.readResource(resource).orElseGet(JsonObject::new);
            ConfigStore.save(configKey, d);
            return d;
        }
        return root;
    }

    /** 剧本配置键按当前模式路由（null-safe；模式未激活回退基础文件名）。 */
    private static String cfgKey(String base) {
        return CCNRRPMod.modes != null ? CCNRRPMod.modes.key(base) : base;
    }

    /** 剧本配置内嵌默认资源路径按当前模式路由（缺档播种用）。 */
    private static String cfgResource(String base) {
        return CCNRRPMod.modes != null ? CCNRRPMod.modes.resource(base) : base;
    }

    private List<GamePhase> loadPhases() {
        return EventModels.parsePhases(loadOrDefaults(cfgKey("phases.json"), cfgResource("phases.json")));
    }

    private void loadEvents() {
        JsonObject root = loadOrDefaults(cfgKey("events.json"), cfgResource("events.json"));
        for (String e : EventModels.parseEvents(root)) {
            LOGGER.error("[CCNR-RP] events.json: {}", e);
        }
        if (root.has("events")) {
            for (com.google.gson.JsonElement el : root.getAsJsonArray("events")) {
                JsonObject eo = el.getAsJsonObject();
                EventModels.parseEvent(eo).ifPresent(ev -> {
                    events.add(ev);
                    if (eo.has("start") && eo.get("start").getAsBoolean()) {
                        startEventIds.add(ev.id());
                    }
                });
            }
        }
    }

    /** 重启事件状态（阶段迁移后事件重新进入 SCHEDULED）。 */
    public void resetEvents() {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).state() != EventState.RUNNING) {
                events.set(i, events.get(i).withState(EventState.SCHEDULED));
            }
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        int interval = Math.max(1, CCNRRPConfig.EVENT_EVAL_INTERVAL_TICKS.get());
        if (++evalCounter < interval) {
            return;
        }
        evalCounter = 0;
        syncRulesPhase();
        PhaseClock.Transition tr = new PhaseClock.Transition(false, null, null);
        if (running) {
            if (!phaseZeroStarted) {
                // 第 0 幕开始信号（一次/轮）：先于推进发射，确保首幕 ON_PHASE_START 与首幕序列被执行
                phaseZeroStarted = true;
                tr = new PhaseClock.Transition(true, clock.phaseId(), null);
            } else {
                // 阶段时钟按节流周期推进（修复重复 tick 导致阶段时长偏短、迁移被丢弃、phase 触发器不触发）
                tr = clock.tick(interval);
                // 条件驱动阶段：当前幕 advanceOn 触发器命中 → 推进到下一幕（不依赖时长）
                if (!tr.changed()) {
                    com.ccnrcom.rp.event.EventModels.GamePhase cur = clock.current();
                    if (cur != null && cur.conditionDriven()) {
                        TriggerContext baseCtx = buildContext(tr);
                        if (TriggerEvaluator.evaluate(cur.advanceOn(), baseCtx)) {
                            tr = clock.advance();
                        }
                    }
                }
            }
            if (tr.changed() && tr.ended() != null) {
                // 幕作用域规则自动解除：离开的幕其限职业/阵营/目标区/招募方式随之失效
                if (CCNRRPMod.rules != null) {
                    CCNRRPMod.rules.onPhaseEnd(tr.ended());
                }
            }
        }
        // 空窗期（未运行）阶段不自动推进；仅开局事件可触发以重新开局
        evaluateAll(tr);
        autoEndRunnings();
    }

    /** 同步当前幕到规则服务（ruleChange 默认作用域 / 波次/部署查询）。 */
    private void syncRulesPhase() {
        if (CCNRRPMod.rules != null) {
            CCNRRPMod.rules.setCurrentPhase(clock.phaseId());
        }
    }

    /** 阶段开始 → 执行内嵌行为序列。 */
    private void runPhaseSteps(PhaseClock.Transition tr) {
        if (CCNRRPMod.sequenceEngine == null || tr == null || !tr.changed() || tr.started() == null) {
            return;
        }
        for (GamePhase p : clock.phases()) {
            if (p.id().equals(tr.started()) && p.steps() != null && !p.steps().isEmpty()) {
                CCNRRPMod.sequenceEngine.runSteps("phase/" + p.id(), p.steps(), java.util.Map.of("phase", p.id()));
                return;
            }
        }
    }

    private void evaluateAll(PhaseClock.Transition tr) {
        runPhaseSteps(tr);
        TriggerContext ctx = buildContext(tr);
        for (int i = 0; i < events.size(); i++) {
            EventDefinition def = events.get(i);
            if (!def.enabled() || def.state() != EventState.SCHEDULED) {
                continue;
            }
            // 空窗期（未运行）仅开局事件可触发；其余事件等待重新开局
            if (!running && !startEventIds.contains(def.id())) {
                continue;
            }
            if (def.triggers().stream().anyMatch(t -> TriggerEvaluator.evaluate(t, ctx))) {
                startEvent(i);
            }
        }
    }

    /** 构建触发器求值上下文（含阶段迁移标记）。 */
    private TriggerContext buildContext(PhaseClock.Transition tr) {
        long gameDay = server.getLevel(net.minecraft.world.level.Level.OVERWORLD) == null
                ? 0L
                : server.getLevel(net.minecraft.world.level.Level.OVERWORLD).getDayTime() / 24000L;
        long tickOfDay = server.getLevel(net.minecraft.world.level.Level.OVERWORLD) == null
                ? 0L
                : server.getLevel(net.minecraft.world.level.Level.OVERWORLD).getDayTime() % 24000L;
        return new TriggerContext(
                clock.phaseId(),
                tr.ended(),
                clock.ticksInPhase(),
                gameDay,
                tickOfDay,
                (System.currentTimeMillis() - startedAtMillis) / 1000L,
                deadCount(),
                aliveCount(),
                scoreboard(),
                tr.changed() && tr.started() != null,
                tr.changed() && tr.ended() != null);
    }

    private void startEvent(int index) {
        EventDefinition def = events.get(index);
        events.set(index, def.withState(EventState.RUNNING));
        noteStart(def);
        LOGGER.info("[CCNR-RP] 事件开始: {} ", def.id());
        // 开局事件：空窗期触发 → 重新开局（清幕作用域规则、回第 0 幕、置运行中、重发首幕信号）
        if (startEventIds.contains(def.id())) {
            running = true;
            phaseZeroStarted = false;
            clock.set(0);
            if (CCNRRPMod.rules != null) {
                CCNRRPMod.rules.reset();
            }
            syncRulesPhase();
            LOGGER.info("[CCNR-RP] 开局事件 {} 触发 → 剧本重新运行", def.id());
        }
        List<ServerPlayer> targets = onlinePlayers();
        targets.forEach(p -> RpChannels.sendTo(p, new RpPackets.ErrorS2C("ccnr_rp.event.started", def.id())));
        // 入场动画：事件自带 startAnimation 优先；否则播 event_start 钩子（通用警报）
        if (!def.startAnimation().isBlank() && CCNRRPMod.animationEngine != null) {
            CCNRRPMod.animationEngine.play(def.startAnimation(), targets, java.util.Map.of("event", def.id()));
        } else {
            AnimationHooks.eventStart(def.id(), targets);
        }
        broadcastState();
        // P8：刷新波钩子（若已实现）
        if (!def.spawnWave().isBlank() && CCNRRPMod.spawnFramework != null) {
            CCNRRPMod.spawnFramework.triggerWave(def.spawnWave());
        }
        // 行为序列（内嵌步骤序列：等待/命令/刷新波/强制抽取等；兼容旧 startSequence 引用）
        if (CCNRRPMod.sequenceEngine != null) {
            if (def.steps() != null && !def.steps().isEmpty()) {
                CCNRRPMod.sequenceEngine.runSteps(
                        "event/" + def.id(),
                        def.steps(),
                        java.util.Map.of("event", def.id(), "phase", clock.phaseId()));
            } else if (!def.startSequence().isBlank()) {
                CCNRRPMod.sequenceEngine.run(
                        def.startSequence(), java.util.Map.of("event", def.id(), "phase", clock.phaseId()));
            }
        }
    }

    private void autoEndRunnings() {
        for (int i = 0; i < events.size(); i++) {
            EventDefinition def = events.get(i);
            if (def.state() == EventState.RUNNING && def.durationSeconds() > 0 && defStartedAt(def) > 0) {
                // 简化：按事件 id 记录启动时间的内存表在 P6 用 startTime 字段存储——维护于本类
                long elapsed = System.currentTimeMillis() - runStart.getOrDefault(def.id(), System.currentTimeMillis());
                if (elapsed >= def.durationSeconds() * 1000L) {
                    endEvent(i);
                }
            }
        }
    }

    private final Map<String, Long> runStart = new HashMap<>();

    private void noteStart(EventDefinition def) {
        runStart.put(def.id(), System.currentTimeMillis());
    }

    private long defStartedAt(EventDefinition def) {
        return runStart.getOrDefault(def.id(), 0L);
    }

    /** 强制启动（命令；仅 SCHEDULED 且 enabled）。 */
    public boolean triggerEvent(String eventId) {
        for (int i = 0; i < events.size(); i++) {
            EventDefinition def = events.get(i);
            if (def.id().equals(eventId) && def.enabled() && def.state() == EventState.SCHEDULED) {
                startEvent(i);
                return true;
            }
        }
        return false;
    }

    public boolean setEnabled(String eventId, boolean on) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).id().equals(eventId)) {
                EventDefinition d = events.get(i);
                EventDefinition nd = new EventDefinition(
                        d.id(),
                        on,
                        d.triggers(),
                        d.tasks(),
                        d.startAnimation(),
                        d.spawnWave(),
                        d.startSequence(),
                        d.notifyTitleKey(),
                        d.durationSeconds(),
                        on ? EventState.SCHEDULED : EventState.SETTLED,
                        d.steps());
                events.set(i, nd);
                return true;
            }
        }
        return false;
    }

    /** 切到指定幕（管理端 /rp phase set；空参 = 切下一幕）。触发 ON_PHASE_END/START 事件。 */
    public boolean setPhase(String phaseId) {
        return switchPhase(phaseId);
    }

    /** 切幕（switchPhase 序列步骤 / 管理端）：phaseId 为空 → 下一幕；否则按 id 切。命中返回 true。 */
    public boolean switchPhase(String phaseId) {
        PhaseClock.Transition tr;
        if (phaseId == null || phaseId.isBlank()) {
            tr = clock.advance();
        } else {
            int idx = indexOfPhase(phaseId);
            if (idx < 0) {
                LOGGER.warn("[CCNR-RP] switchPhase 目标幕不存在: {}", phaseId);
                return false;
            }
            tr = clock.set(idx);
        }
        if (tr.changed()) {
            if (CCNRRPMod.rules != null) {
                CCNRRPMod.rules.onPhaseEnd(tr.ended());
            }
            syncRulesPhase();
            evaluateAll(tr);
        }
        return tr.changed();
    }

    private int indexOfPhase(String phaseId) {
        for (int i = 0; i < clock.phases().size(); i++) {
            if (clock.phases().get(i).id().equals(phaseId)) {
                return i;
            }
        }
        return -1;
    }

    /** 剧本是否运行中（/rp end 后为 false，等待开局事件重启）。 */
    public boolean running() {
        return running;
    }

    /**
     * 触发结局（/rp end）：幂等——已结束/未运行时只处理一次。
     * 播结局动画 + 通报 + 结算 XP + 复位阶段回第 0 幕（或 resetToPhase）+ 清事件运行时，标记空窗期。
     */
    public boolean end(String reason) {
        if (!running) {
            return false;
        }
        running = false;
        List<ServerPlayer> targets = onlinePlayers();
        EndingScript.Script script = loadEnding();
        boolean played = false;
        if (script != null && !script.animation().isBlank() && CCNRRPMod.animationEngine != null) {
            played = CCNRRPMod.animationEngine.play(
                    script.animation(), targets, java.util.Map.of("reason", reason == null ? "" : reason));
        }
        if (script != null) {
            // 动画序列已播（含标题）时不再重复通报原始键；未播动画才用 notify 兜底
            if (script.hasNotify() && !played) {
                broadcastEndingNotify(script, targets);
            }
            if (script.hasReward()) {
                rewardEnding(script);
            }
        }
        resetToPhase(script == null ? "" : script.resetToPhase());
        LOGGER.info("[CCNR-RP] 结局触发（{}）", reason == null || reason.isBlank() ? "无理由" : reason);
        return true;
    }

    /** 复位剧本运行时：阶段回第 0 幕（或 resetToPhase）、事件清零、幕作用域规则清空。 */
    private void resetToPhase(String phaseId) {
        int idx = 0;
        if (phaseId != null && !phaseId.isBlank()) {
            int found = indexOfPhase(phaseId);
            if (found >= 0) {
                idx = found;
            }
        }
        clock.set(idx);
        resetEvents();
        phaseZeroStarted = false;
        if (CCNRRPMod.rules != null) {
            CCNRRPMod.rules.reset();
        }
        syncRulesPhase();
    }

    /** 读取当前模式下的结局剧本（ending.json）；无/空返回 null。 */
    private EndingScript.Script loadEnding() {
        return EndingScript.parse(ConfigStore.load(cfgKey("ending.json")).orElseGet(JsonObject::new))
                .orElse(null);
    }

    /** 结局通报：无动画序列时用原始通报键（title/subtitle/actionbar）向全服播报。 */
    private void broadcastEndingNotify(EndingScript.Script s, List<ServerPlayer> targets) {
        if (targets.isEmpty()) {
            return;
        }
        if (!s.titleKey().isBlank()) {
            Component title = Component.translatable(s.titleKey());
            for (ServerPlayer p : targets) {
                p.sendSystemMessage(title);
            }
        }
        if (!s.subtitleKey().isBlank()) {
            Component subtitle = Component.translatable(s.subtitleKey());
            for (ServerPlayer p : targets) {
                p.sendSystemMessage(subtitle);
            }
        }
        if (!s.actionbarKey().isBlank()) {
            net.minecraft.network.chat.Component text = Component.translatable(s.actionbarKey());
            for (ServerPlayer p : targets) {
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket(text));
            }
        }
    }

    /** 结局结算：向 applyTo 目标追加奖励 XP（进待结算列表，由 /rp settle 结算）。@a = 全员。 */
    private void rewardEnding(EndingScript.Script s) {
        if (CCNRRPMod.experience == null || !s.hasReward()) {
            return;
        }
        for (ServerPlayer p : onlinePlayers()) {
            String uuid = p.getUUID().toString();
            if (CCNRRPMod.users != null && CCNRRPMod.users.hasProfile(uuid)) {
                CCNRRPMod.experience.addManualScore(uuid, "round_reward", s.rewardXp());
            }
        }
    }

    /** 管理端手动触发事件（C2S，管理员权限校验）。 */
    public static void onAdminTrigger(ServerPlayer player, String eventId) {
        if (player == null || CCNRRPMod.eventManager == null) {
            return;
        }
        if (!com.ccnrcom.rp.util.Permissions.canAdmin(player, com.ccnrcom.rp.util.Permissions.ADMIN_EVENT)) {
            RpChannels.sendTo(player, new RpPackets.ErrorS2C("ccnr_rp.command.no_permission"));
            return;
        }
        boolean ok = CCNRRPMod.eventManager.triggerEvent(eventId);
        RpChannels.sendTo(
                player, new RpPackets.ErrorS2C(ok ? "ccnr_rp.event.triggered" : "ccnr_rp.event.not_runnable", eventId));
    }

    /** 手动结束事件（命令）。 */
    public boolean endEvent(String eventId) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).id().equals(eventId) && events.get(i).state() == EventState.RUNNING) {
                endEvent(i);
                return true;
            }
        }
        return false;
    }

    private void endEvent(int index) {
        EventDefinition def = events.get(index);
        events.set(index, def.withState(EventState.SETTLED));
        LOGGER.info("[CCNR-RP] 事件结束: {} ", def.id());
        List<ServerPlayer> targets = onlinePlayers();
        targets.forEach(p -> RpChannels.sendTo(p, new RpPackets.ErrorS2C("ccnr_rp.event.ended", def.id())));
        broadcastState();
    }

    /** 游戏结束：game_end 动画钩子（经验不自动结算；结算仅死亡退场与 /rp settle 触发）。 */
    public void gameOver() {
        AnimationHooks.gameEnd(onlinePlayers());
    }

    private int deadCount() {
        if (CCNRRPMod.users == null) {
            return 0;
        }
        return (int) CCNRRPMod.users.uuids().stream()
                .filter(uuid -> CCNRRPMod.users.status(uuid) == CharacterStatus.DEAD)
                .count();
    }

    private int aliveCount() {
        if (CCNRRPMod.users == null) {
            return 0;
        }
        return (int) CCNRRPMod.users.uuids().stream()
                .filter(uuid -> CCNRRPMod.users.status(uuid) == CharacterStatus.ALIVE)
                .count();
    }

    private Map<String, Integer> scoreboard() {
        return Map.of();
    }

    private List<ServerPlayer> onlinePlayers() {
        return new ArrayList<>(server.getPlayerList().getPlayers());
    }
}
