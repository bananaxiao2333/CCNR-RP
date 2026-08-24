/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.spawn;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.character.CharacterData;
import com.ccnrcom.rp.character.CharacterService;
import com.ccnrcom.rp.config.CCNRRPConfig;
import com.ccnrcom.rp.experience.LevelCurve;
import com.ccnrcom.rp.faction.FactionProfessions;
import com.ccnrcom.rp.network.RpChannels;
import com.ccnrcom.rp.network.RpPackets;
import com.ccnrcom.rp.profession.LoadoutManager;
import com.ccnrcom.rp.spawn.SpawnModels.Candidate;
import com.ccnrcom.rp.spawn.SpawnModels.Mode;
import com.ccnrcom.rp.spawn.SpawnModels.Selection;
import com.ccnrcom.rp.spawn.SpawnModels.Wave;
import com.ccnrcom.rp.status.CharacterStatus;
import com.ccnrcom.rp.util.JsonUtil;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 刷新框架（P8）：自刷新（GUI 部署）/复活波（队伍创建轮询触发）/招募兜底（右侧列表+超时）。
 * 部署链路：校验 → LoadoutManager.apply → 传送 deployAt → 状态 DEAD|OBSERVING → ALIVE → player_spawn 动画。
 */
public final class SpawnFramework implements com.ccnrcom.rp.spawn.RecruitManager.Listener {
    private static final Logger LOGGER = LogManager.getLogger();

    private final MinecraftServer server;
    private final List<Wave> waves = new ArrayList<>();
    private final Map<String, Boolean> teamTriggered = new HashMap<>();
    private final RecruitManager recruit;
    private long pollCounter = 0;

    public SpawnFramework(MinecraftServer server) {
        this.server = server;
        this.recruit = new RecruitManager(this);
        loadWaves();
    }

    public RecruitManager recruit() {
        return recruit;
    }

    /** 招募应答入口（C2S）。 */
    public static void onRecruitAnswer(ServerPlayer player, String offerId, boolean accept) {
        if (player == null || CCNRRPMod.spawnFramework == null) {
            return;
        }
        if (accept) {
            CCNRRPMod.spawnFramework.recruit().accept(offerId, player);
        } else {
            CCNRRPMod.spawnFramework.recruit().decline(offerId, player);
        }
    }

    private void loadWaves() {
        Path file = FMLPaths.CONFIGDIR.get().resolve("ccnr_rp").resolve("spawn_waves.json");
        JsonObject root = JsonUtil.readObject(file).orElseGet(() -> {
            JsonObject d = JsonUtil.readResource("/assets/ccnr_rp/defaults/spawn_waves.json")
                    .orElseGet(JsonObject::new);
            JsonUtil.atomicWrite(file, d);
            return d;
        });
        List<String> errors = SpawnModels.parseWaves(root, waves);
        errors.forEach(e -> LOGGER.error("[CCNR-RP] spawn_waves.json: {}", e));
    }

    /** 热重载（管理器 CRUD 后调用）：重读 spawn_waves.json 并清空队伍触发记录。 */
    public void reload() {
        waves.clear();
        teamTriggered.clear();
        loadWaves();
        LOGGER.info("[CCNR-RP] 刷新波已热重载");
    }

    public List<Wave> waves() {
        return List.copyOf(waves);
    }

    public Optional<Wave> wave(String id) {
        return waves.stream().filter(w -> w.id().equals(id)).findFirst();
    }

    /** 复活波触发（命令/事件钩子/队伍创建）。 */
    public void triggerWave(String waveId) {
        Optional<Wave> w = wave(waveId);
        if (w.isEmpty() || !w.get().enabled() || !w.get().mode().resurrectionAllowed()) {
            LOGGER.info("[CCNR-RP] 复活波不可触发（未启用/模式不含复活）: {}", waveId);
            return;
        }
        Wave wave = w.get();
        List<Candidate> pool = candidatePool();
        Selection sel = WaveSelector.select(pool, wave, System.currentTimeMillis(), new Random());
        int deployed = deployCandidates(sel.deploy(), wave, false);
        if (!sel.recruit().isEmpty()) {
            List<ServerPlayer> online = new ArrayList<>();
            for (Candidate c : sel.recruit()) {
                ServerPlayer p = server.getPlayerList().getPlayer(java.util.UUID.fromString(c.playerUuid()));
                if (p != null) {
                    online.add(p);
                }
            }
            recruit.offer(wave.id(), sel.recruit(), online);
        }
        // 内嵌行为序列（波触发时执行：WAIT/COMMAND/WAVE/FORCE_PICK 等）
        if (CCNRRPMod.sequenceEngine != null
                && wave.steps() != null
                && !wave.steps().isEmpty()) {
            CCNRRPMod.sequenceEngine.runSteps("wave/" + waveId, wave.steps(), java.util.Map.of("wave", waveId));
        }
        LOGGER.info(
                "[CCNR-RP] 复活波 {} 完成：部署 {} 人，招募 {} 人",
                waveId,
                deployed,
                sel.recruit().size());
    }

    /** 队伍创建轮询（20t；检测 scoreboard 团队新增并命中 teamIds → 触发一次）。 */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        int interval = Math.max(1, CCNRRPConfig.SPAWN_POLL_TICKS.get());
        if (++pollCounter < interval) {
            return;
        }
        pollCounter = 0;
        recruit.onTick();
        Set<String> now = new HashSet<>(server.getScoreboard().getTeamNames());
        boolean changed = false;
        for (String name : now) {
            if (!teamTriggered.containsKey(name)) {
                teamTriggered.put(name, true);
                changed = true;
                for (Wave w : waves) {
                    if (w.enabled()
                            && w.mode().resurrectionAllowed()
                            && w.teamIds().contains(name)
                            && w.teamIds().size() > 0) {
                        LOGGER.info("[CCNR-RP] 队伍 {} 创建 → 复活波 {}", name, w.id());
                        triggerWave(w.id());
                    }
                }
            }
        }
        if (changed) {
            persistTeamState();
        }
    }

    private void persistTeamState() {
        JsonObject root = new JsonObject();
        JsonObject map = new JsonObject();
        teamTriggered.forEach((k, v) -> map.addProperty(k, v));
        root.add("teams", map);
        JsonUtil.atomicWrite(
                server.getWorldPath(new net.minecraft.world.level.storage.LevelResource("ccnr_rp"))
                        .resolve("team_wave_done.json"),
                root);
    }

    private List<Candidate> candidatePool() {
        List<Candidate> pool = new ArrayList<>();
        LevelCurve curve = new LevelCurve(
                com.ccnrcom.rp.config.CCNRRPConfig.LEVEL_BASE.get(),
                com.ccnrcom.rp.config.CCNRRPConfig.LEVEL_POW.get());
        for (CharacterData c : CCNRRPMod.characters.store().all()) {
            boolean selfDeploy = CCNRRPMod.factions != null
                    && CCNRRPMod.factions
                            .findProfession(c.professionId())
                            .map(FactionProfessions::selfDeploy)
                            .orElse(false);
            boolean online = server.getPlayerList().getPlayer(java.util.UUID.fromString(c.playerUuid())) != null;
            pool.add(new Candidate(
                    c.id(),
                    c.playerUuid(),
                    c.name(),
                    c.status().name().toLowerCase(java.util.Locale.ROOT),
                    c.cooldownUntil(),
                    curve.level(c.xp()),
                    c.professionId(),
                    c.factionId(),
                    selfDeploy,
                    online));
        }
        return pool;
    }

    // ---------- 部署 ----------

    private int deployCandidates(List<Candidate> toDeploy, Wave wave, boolean recruited) {
        int deployed = 0;
        for (Candidate cand : toDeploy) {
            if (deployCharacter(cand.charId(), wave, recruited, false)) {
                deployed++;
            }
        }
        return deployed;
    }

    /** 核心部署：校验 → 装备 → 传送 → 状态 ALIVE → 动画/广播。cinematic=true（自部署）时跳过旧 spawn 动画，由入场电影接管。 */
    public boolean deployCharacter(String charId, Wave wave, boolean recruited, boolean cinematic) {
        CharacterService svc = CCNRRPMod.characters;
        if (svc == null) {
            return false;
        }
        CharacterData c = svc.store().find(charId).orElse(null);
        if (c == null) {
            return false;
        }
        if (c.status() == CharacterStatus.ALIVE) {
            return false;
        }
        // 复活冷却只锁「自己职业自部署」（deploySelf 校验）；复活波/强制抽取/招募无视冷却
        ServerPlayer p = server.getPlayerList().getPlayer(java.util.UUID.fromString(c.playerUuid()));
        if (p == null) {
            return false;
        }
        // 装备
        if (CCNRRPMod.factions != null) {
            CCNRRPMod.factions.findProfession(c.professionId()).ifPresent(def -> {
                LoadoutManager.apply(p, FactionProfessions.loadout(def));
            });
        }
        // 传送
        teleport(p, wave);
        // 状态
        CharacterData alive = c.withStatus(CharacterStatus.ALIVE).withCooldown(0);
        svc.store().update(alive);
        svc.store().save();
        CharacterService.updateAndBroadcast(alive, p);
        // 统一部署动画：所有部署路径都走入场电影（黑屏→图标→打字档案→淡出）
        CharacterService.sendCinematic(p, c.id());
        return true;
    }

    private void teleport(ServerPlayer p, Wave wave) {
        String dim = wave.dim() == null ? "minecraft:overworld" : wave.dim();
        net.minecraft.resources.ResourceLocation dimLoc = ResourceLocation.tryParse(dim);
        ServerLevel level = dimLoc == null
                ? null
                : server.getLevel(net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION, dimLoc));
        if (level == null) {
            level = server.overworld();
        }
        net.minecraft.core.BlockPos pos = "POS".equals(wave.deployAtType())
                ? new net.minecraft.core.BlockPos((int) wave.x(), (int) wave.y(), (int) wave.z())
                : level.getSharedSpawnPos();
        p.teleportTo(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        p.setGameMode(GameType.SURVIVAL);
    }

    /** 自刷新（GUI 部署按钮/命令）：自己职业需复活冷却结束（复活波/强制抽取无视冷却）。 */
    public boolean deploySelf(ServerPlayer player, String charId) {
        CharacterService svc = CCNRRPMod.characters;
        if (svc == null) {
            return false;
        }
        CharacterData c = svc.store().find(charId).orElse(null);
        if (c == null || !c.playerUuid().equals(player.getUUID().toString())) {
            return false;
        }
        if (c.status() != CharacterStatus.OBSERVING || !isSelfDeployable(c)) {
            return false;
        }
        if (c.cooldownUntil() > System.currentTimeMillis()) {
            return false; // 自己职业复活冷却中；等待冷却结束或复活波强制复活
        }
        // 找用于部署的刷新波（SELF_DEPLOY/BOTH 且职业匹配）；无匹配默认世界原点
        Wave wave = waves.stream()
                .filter(Wave::enabled)
                .filter(w -> w.mode().selfDeployAllowed())
                .filter(w -> w.matchesProfession(c.professionId(), c.factionId()))
                .findFirst()
                .orElse(new Wave(
                        "_default",
                        Mode.SELF_DEPLOY,
                        true,
                        List.of(),
                        List.of(),
                        List.of(),
                        1,
                        0,
                        "WORLD_SPAWN",
                        0,
                        64,
                        0,
                        "minecraft:overworld",
                        60));
        return deployCharacter(charId, wave, false, true);
    }

    private boolean isSelfDeployable(CharacterData c) {
        if (CCNRRPMod.factions == null) {
            return false;
        }
        return CCNRRPMod.factions
                .findProfession(c.professionId())
                .map(FactionProfessions::selfDeploy)
                .orElse(false);
    }

    public boolean setEnabled(String waveId, boolean on) {
        // 运行时切换：重载配置由管理员改文件触发；此处提供内存开关（下次 reload 覆盖）
        for (int i = 0; i < waves.size(); i++) {
            if (waves.get(i).id().equals(waveId)) {
                Wave w = waves.get(i);
                waves.set(
                        i,
                        new Wave(
                                w.id(),
                                w.mode(),
                                on,
                                w.teamIds(),
                                w.professionIds(),
                                w.factionIds(),
                                w.count(),
                                w.minLevel(),
                                w.deployAtType(),
                                w.x(),
                                w.y(),
                                w.z(),
                                w.dim(),
                                w.recruitTimeoutSeconds(),
                                w.steps()));
                return true;
            }
        }
        return false;
    }

    // ---------- 招募回调 ----------

    @Override
    public void onRecruitAccepted(String charId, String waveId) {
        wave(waveId).ifPresent(w -> deployCharacter(charId, w, true, false));
    }

    @Override
    public void onRecruitFailed(String waveId) {
        eventBroadcast("ccnr_rp.spawn.recruit.failed", waveId);
    }

    private void eventBroadcast(String key, String arg) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            RpChannels.sendTo(p, new RpPackets.ErrorS2C(key, arg));
        }
    }

    /** 让进度上传等使用到的工具字段（保留占位，勿删）。 */
    static {
        LOGGER.debug("P8 SpawnFramework 就绪");
    }
}
