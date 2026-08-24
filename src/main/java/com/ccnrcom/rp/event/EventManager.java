/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.event;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.animation.AnimationHooks;
import com.ccnrcom.rp.config.CCNRRPConfig;
import com.ccnrcom.rp.event.EventModels.EventDefinition;
import com.ccnrcom.rp.event.EventModels.EventState;
import com.ccnrcom.rp.event.EventModels.GamePhase;
import com.ccnrcom.rp.event.EventModels.Task;
import com.ccnrcom.rp.event.EventModels.TriggerContext;
import com.ccnrcom.rp.experience.ExperienceService;
import com.ccnrcom.rp.network.RpChannels;
import com.ccnrcom.rp.network.RpPackets;
import com.ccnrcom.rp.status.CharacterStatus;
import com.ccnrcom.rp.util.JsonUtil;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;
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
        clock = new PhaseClock(loadPhases());
        loadEvents();
        LOGGER.info("[CCNR-RP] 事件/阶段已热重载");
    }

    public PhaseClock clock() {
        return clock;
    }

    public List<EventDefinition> events() {
        return List.copyOf(events);
    }

    private JsonObject loadOrDefaults(Path file, String resource) {
        JsonObject root = JsonUtil.readObject(file).orElseGet(JsonObject::new);
        if (root.size() == 0) {
            JsonObject d = JsonUtil.readResource(resource).orElseGet(JsonObject::new);
            JsonUtil.atomicWrite(file, d);
            return d;
        }
        return root;
    }

    private List<GamePhase> loadPhases() {
        Path file = FMLPaths.CONFIGDIR.get().resolve("ccnr_rp").resolve("phases.json");
        return EventModels.parsePhases(loadOrDefaults(file, "/assets/ccnr_rp/defaults/phases.json"));
    }

    private void loadEvents() {
        Path file = FMLPaths.CONFIGDIR.get().resolve("ccnr_rp").resolve("events.json");
        JsonObject root = loadOrDefaults(file, "/assets/ccnr_rp/defaults/events.json");
        for (String e : EventModels.parseEvents(root)) {
            LOGGER.error("[CCNR-RP] events.json: {}", e);
        }
        if (root.has("events")) {
            for (com.google.gson.JsonElement el : root.getAsJsonArray("events")) {
                EventModels.parseEvent(el.getAsJsonObject()).ifPresent(events::add);
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
        clock.tick();
        int interval = Math.max(1, CCNRRPConfig.EVENT_EVAL_INTERVAL_TICKS.get());
        if (++evalCounter < interval) {
            return;
        }
        evalCounter = 0;
        // 阶段迁移（每节流周期判定一次；tick 内迁移即视为本 tick 开始/结束）
        PhaseClock.Transition tr = clock.tick();
        evaluateAll(tr);
        autoEndRunnings();
    }

    private void evaluateAll(PhaseClock.Transition tr) {
        TriggerContext ctx = new TriggerContext(
                clock.phaseId(),
                tr.ended(),
                clock.ticksInPhase(),
                0L,
                server.getLevel(net.minecraft.world.level.Level.OVERWORLD) == null
                        ? 0L
                        : server.getLevel(net.minecraft.world.level.Level.OVERWORLD)
                                        .getDayTime()
                                % 24000L,
                (System.currentTimeMillis() - startedAtMillis) / 1000L,
                deadCount(),
                aliveCount(),
                scoreboard(),
                tr.changed() && tr.started() != null,
                tr.changed() && tr.ended() != null);
        for (int i = 0; i < events.size(); i++) {
            EventDefinition def = events.get(i);
            if (!def.enabled() || def.state() != EventState.SCHEDULED) {
                continue;
            }
            if (def.triggers().stream().anyMatch(t -> TriggerEvaluator.evaluate(t, ctx))) {
                startEvent(i);
            }
        }
    }

    private void startEvent(int index) {
        EventDefinition def = events.get(index);
        events.set(index, def.withState(EventState.RUNNING));
        noteStart(def);
        LOGGER.info("[CCNR-RP] 事件开始: {} ", def.id());
        List<ServerPlayer> targets = onlinePlayers();
        targets.forEach(p -> RpChannels.sendTo(p, new RpPackets.ErrorS2C("ccnr_rp.event.started", def.id())));
        AnimationHooks.eventStart(def.id(), targets);
        // P8：刷新波钩子（若已实现）
        if (!def.spawnWave().isBlank() && CCNRRPMod.spawnFramework != null) {
            CCNRRPMod.spawnFramework.triggerWave(def.spawnWave());
        }
        // 任务登记：事件开始时把所有任务标记给当前参与角色（简化：结算时按任务表）
        if (!def.tasks().isEmpty() && CCNRRPMod.experience != null) {
            for (Task t : def.tasks()) {
                CCNRRPMod.characters.store().all().forEach(c -> ExperienceService.markTask(c.id(), t.id(), t.xp()));
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
                        d.notifyTitleKey(),
                        d.durationSeconds(),
                        d.settleOnEnd(),
                        on ? EventState.SCHEDULED : EventState.SETTLED);
                events.set(i, nd);
                return true;
            }
        }
        return false;
    }

    public boolean setPhase(String phaseId) {
        for (int i = 0; i < clock.phases().size(); i++) {
            if (clock.phases().get(i).id().equals(phaseId)) {
                clock.set(i);
                return true;
            }
        }
        return false;
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
        if (def.settleOnEnd() && CCNRRPMod.experience != null) {
            CCNRRPMod.experience.settleAll(null);
        }
    }

    /** 游戏结束：自动疏散裁定 + 全员结算 + game_end 动画钩子。 */
    public void gameOver() {
        if (CCNRRPMod.characters != null) {
            CCNRRPMod.characters.store().all().forEach(c -> {
                com.ccnrcom.rp.experience.SettlementCalcs.EvacuationMethod m =
                        switch (c.status()) {
                            case ALIVE -> com.ccnrcom.rp.experience.SettlementCalcs.EvacuationMethod.SAFE_RESCUE;
                            case DEAD -> com.ccnrcom.rp.experience.SettlementCalcs.EvacuationMethod.DIED;
                            case OBSERVING -> com.ccnrcom.rp.experience.SettlementCalcs.EvacuationMethod.OBSERVING_END;
                        };
                ExperienceService.setEvacuation(c.id(), m);
            });
        }
        if (CCNRRPMod.experience != null) {
            CCNRRPMod.experience.settleAll(null);
        }
        AnimationHooks.gameEnd(onlinePlayers());
    }

    private int deadCount() {
        return (int) CCNRRPMod.characters.store().all().stream()
                .filter(c -> c.status() == CharacterStatus.DEAD)
                .count();
    }

    private int aliveCount() {
        return (int) CCNRRPMod.characters.store().all().stream()
                .filter(c -> c.status() == CharacterStatus.ALIVE)
                .count();
    }

    private Map<String, Integer> scoreboard() {
        return Map.of();
    }

    private List<ServerPlayer> onlinePlayers() {
        return new ArrayList<>(server.getPlayerList().getPlayers());
    }
}
