/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.spawn;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.config.CCNRRPConfig;
import com.ccnrcom.rp.faction.FactionProfessions;
import com.ccnrcom.rp.network.RpChannels;
import com.ccnrcom.rp.network.RpPackets;
import com.ccnrcom.rp.profession.LoadoutManager;
import com.ccnrcom.rp.spawn.SpawnModels.Candidate;
import com.ccnrcom.rp.spawn.SpawnModels.Mode;
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

    /** 复活波触发（命令/事件钩子/队伍创建）：指定类型=按类型征召（分配角色，不部署玩家自己的角色）；通用=选岗。 */
    public void triggerWave(String waveId) {
        Optional<Wave> w = wave(waveId);
        if (w.isEmpty() || !w.get().enabled() || !w.get().mode().resurrectionAllowed()) {
            LOGGER.info("[CCNR-RP] 复活波不可触发（未启用/模式不含复活）: {}", waveId);
            return;
        }
        Wave wave = w.get();
        boolean typed = (wave.professionIds() != null && !wave.professionIds().isEmpty())
                || (wave.factionIds() != null && !wave.factionIds().isEmpty());
        if (typed) {
            triggerTypedWave(wave); // 指定类型：按类型征召
        } else {
            triggerPickWave(wave); // 通用：玩家自选可复活职业上岗
        }
        // 内嵌行为序列（波触发时执行：WAIT/COMMAND/WAVE/FORCE_PICK 等）
        if (CCNRRPMod.sequenceEngine != null
                && wave.steps() != null
                && !wave.steps().isEmpty()) {
            CCNRRPMod.sequenceEngine.runSteps("wave/" + waveId, wave.steps(), java.util.Map.of("wave", waveId));
        }
    }

    /** 指定类型复活波 = 按类型征召：所有在线未在场玩家被邀请，接受后分配波次编制角色（临时、不进角色库）。 */
    private void triggerTypedWave(Wave wave) {
        List<ServerPlayer> pool = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            String uuid = p.getUUID().toString();
            boolean recruitInviteAlive =
                    CCNRRPMod.managerSettings != null && CCNRRPMod.managerSettings.recruitInviteAlive();
            if ((!recruitInviteAlive && CCNRRPMod.users.isAlive(uuid))
                    || com.ccnrcom.rp.sequence.SequenceEngine.isConscripted(uuid)) {
                continue;
            }
            pool.add(p);
        }
        java.util.Collections.shuffle(pool, new Random());
        int picked = Math.min(Math.max(0, wave.count()), pool.size());
        if (picked <= 0) {
            LOGGER.info("[CCNR-RP] 复活波 {}（指定类型）无候选", wave.id());
            return;
        }
        List<String> profPool = professionsFor(wave);
        if (profPool.isEmpty()) {
            LOGGER.warn("[CCNR-RP] 复活波 {} 无可用职业编制，跳过", wave.id());
            return;
        }
        String factionId = wave.factionIds() == null || wave.factionIds().isEmpty()
                ? ""
                : wave.factionIds().get(0);
        String prefix = factionId.isBlank()
                ? "SUP"
                : factionId.substring(0, Math.min(3, factionId.length())).toUpperCase(java.util.Locale.ROOT);
        Random rng = new Random();
        List<Candidate> cands = new ArrayList<>();
        List<ServerPlayer> online = new ArrayList<>();
        for (int i = 0; i < picked; i++) {
            ServerPlayer p = pool.get(i);
            String uuid = p.getUUID().toString();
            String profId = profPool.get(rng.nextInt(profPool.size()));
            String uidName = prefix + "-" + hex4() + "-" + hex2();
            String csId = "conscript-" + java.util.UUID.randomUUID();
            com.ccnrcom.rp.sequence.SequenceEngine.registerConscript(
                    new com.ccnrcom.rp.sequence.SequenceEngine.Conscript(
                            csId, uuid, uidName, profId, factionId, true, 0L));
            cands.add(new Candidate(csId, uuid, uidName, "observing", 0, 0, profId, factionId, false, true, false));
            online.add(p);
        }
        recruit.offerConscript(wave.id(), "typed", picked, cands, online, wave.recruitTimeoutSeconds());
        LOGGER.info("[CCNR-RP] 复活波 {}（指定类型）按类型征召邀请 {} 人", wave.id(), picked);
    }

    /** 通用复活波：有可复活（观察）角色的玩家被邀请，接受时弹职业菜单选岗。 */
    private void triggerPickWave(Wave wave) {
        List<Candidate> cands = new ArrayList<>();
        List<ServerPlayer> online = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            String uuid = p.getUUID().toString();
            boolean recruitInviteAlive =
                    CCNRRPMod.managerSettings != null && CCNRRPMod.managerSettings.recruitInviteAlive();
            if ((!recruitInviteAlive && CCNRRPMod.users.isAlive(uuid))
                    || com.ccnrcom.rp.sequence.SequenceEngine.isConscripted(uuid)) {
                continue;
            }
            if (!recruitInviteAlive && !CCNRRPMod.users.isObserving(uuid)) {
                continue; // 通用波需要玩家处于观察状态（有可上岗身份）；开启向存活邀约时可无视
            }
            cands.add(new Candidate(
                    "user-" + uuid,
                    uuid,
                    p.getName().getString(),
                    "observing",
                    0,
                    CCNRRPMod.users.level(uuid),
                    CCNRRPMod.users.professionId(uuid),
                    CCNRRPMod.users.factionId(uuid),
                    false,
                    true,
                    true));
            online.add(p);
        }
        if (cands.isEmpty()) {
            LOGGER.info("[CCNR-RP] 复活波 {}（通用）无可选岗玩家", wave.id());
            return;
        }
        recruit.offerPick(wave.id(), Math.max(0, wave.count()), cands, online, wave.recruitTimeoutSeconds());
        LOGGER.info("[CCNR-RP] 复活波 {}（通用）选岗邀请 {} 人", wave.id(), cands.size());
    }

    /** 波次可用职业编制（职业/阵营过滤）。 */
    private List<String> professionsFor(Wave wave) {
        List<String> out = new ArrayList<>();
        if (CCNRRPMod.factions == null) {
            return out;
        }
        java.util.Set<String> facs = new java.util.HashSet<>(wave.factionIds());
        if (wave.professionIds() != null && !wave.professionIds().isEmpty()) {
            for (String pid : wave.professionIds()) {
                var def = CCNRRPMod.factions.findProfession(pid).orElse(null);
                if (def != null && (facs.isEmpty() || facs.contains(FactionProfessions.factionId(def)))) {
                    out.add(pid);
                }
            }
        } else {
            for (String pid : CCNRRPMod.factions.professionIds()) {
                var def = CCNRRPMod.factions.findProfession(pid).orElse(null);
                if (def != null && (facs.isEmpty() || facs.contains(FactionProfessions.factionId(def)))) {
                    out.add(pid);
                }
            }
        }
        return out;
    }

    /** 通用波选岗（C2S）：所有权/状态校验后计入已加入。 */
    public static void onRecruitPick(ServerPlayer player, String offerId, String charId) {
        if (player == null || CCNRRPMod.spawnFramework == null) {
            return;
        }
        String uuid = player.getUUID().toString();
        if (CCNRRPMod.users == null || CCNRRPMod.users.isAlive(uuid) || !CCNRRPMod.users.isObserving(uuid)) {
            return;
        }
        CCNRRPMod.spawnFramework.recruit().pick(offerId, player, charId);
    }

    private String hex4() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            sb.append("0123456789ABCDEF".charAt(new Random().nextInt(16)));
        }
        return sb.toString();
    }

    private String hex2() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2; i++) {
            sb.append("0123456789ABCDEF".charAt(new Random().nextInt(16)));
        }
        return sb.toString();
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
        if (CCNRRPMod.users == null) {
            return pool;
        }
        for (String uuid : CCNRRPMod.users.uuids()) {
            // 活着（已有在场身份）或正在以征召兵在场的玩家不进候选池 → 不会收到邀请
            boolean recruitInviteAlive =
                    CCNRRPMod.managerSettings != null && CCNRRPMod.managerSettings.recruitInviteAlive();
            if ((!recruitInviteAlive && CCNRRPMod.users.isAlive(uuid))
                    || com.ccnrcom.rp.sequence.SequenceEngine.isConscripted(uuid)) {
                continue;
            }
            boolean online = server.getPlayerList().getPlayer(java.util.UUID.fromString(uuid)) != null;
            boolean anySupport = CCNRRPMod.users.anySupportRevive(uuid);
            pool.add(new Candidate(
                    "user-" + uuid,
                    uuid,
                    playerName(uuid),
                    CCNRRPMod.users.status(uuid).name().toLowerCase(java.util.Locale.ROOT),
                    CCNRRPMod.users.cooldownUntil(uuid),
                    CCNRRPMod.users.level(uuid),
                    CCNRRPMod.users.professionId(uuid),
                    CCNRRPMod.users.factionId(uuid),
                    false,
                    online,
                    anySupport));
        }
        return pool;
    }

    private String playerName(String uuid) {
        ServerPlayer p = server.getPlayerList().getPlayer(java.util.UUID.fromString(uuid));
        return p == null ? uuid : p.getName().getString();
    }

    // ---------- 部署 ----------

    private int deployCandidates(List<Candidate> toDeploy, Wave wave, boolean recruited) {
        int deployed = 0;
        for (Candidate cand : toDeploy) {
            if (deployAsPosition(cand.playerUuid(), cand.name(), cand.professionId(), cand.factionId(), wave)) {
                deployed++;
            }
        }
        return deployed;
    }

    /**
     * 统一部署磨子（普通角色与征召兵共用）：装备→传送→生存→入场电影（cinematic=false 跳过入场动画）。
     * 部署位置由 wave 的 deployAt 决定（未配置则世界出生点）。
     */
    private void applyDeployCore(
            ServerPlayer p,
            String name,
            String professionId,
            String factionId,
            String background,
            Wave wave,
            boolean cinematic) {
        if (CCNRRPMod.factions != null) {
            CCNRRPMod.factions.findProfession(professionId).ifPresent(def -> {
                LoadoutManager.apply(p, FactionProfessions.loadout(def));
            });
        }
        teleport(p, wave, factionId);
        p.setGameMode(GameType.SURVIVAL);
        // 入场电影（统一组装：名字/职业/阵营/关系推导/背景）
        try {
            com.google.gson.JsonObject en = new com.google.gson.JsonObject();
            en.addProperty("name", name);
            en.addProperty("professionName", professionId);
            String music = "";
            com.google.gson.JsonArray relations = new com.google.gson.JsonArray();
            if (CCNRRPMod.factions != null) {
                var profDef = CCNRRPMod.factions.findProfession(professionId).orElse(null);
                if (profDef != null) {
                    en.addProperty("professionName", FactionProfessions.idsSafeName(profDef));
                    music = FactionProfessions.music(profDef);
                }
                var graph = CCNRRPMod.factions.graph();
                var f = graph.factions().get(factionId);
                if (f != null) {
                    en.addProperty("factionName", f.name());
                    en.addProperty("icon", f.icon());
                    en.addProperty("tier", f.tier());
                    en.addProperty("factionMusic", f.music());
                    for (var other : graph.factions().values()) {
                        if (other.id().equals(f.id())) {
                            continue;
                        }
                        var type = graph.resolve(f.id(), other.id());
                        if (type != null && type != com.ccnrcom.rp.faction.RelationType.NEUTRAL) {
                            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
                            o.addProperty("name", other.name());
                            o.addProperty("type", type.name().toLowerCase(java.util.Locale.ROOT));
                            relations.add(o);
                        }
                    }
                }
            }
            if (!en.has("factionName")) {
                en.addProperty("factionName", factionId);
                en.addProperty("icon", "hex");
                en.addProperty("tier", 2);
            }
            if (!en.has("factionMusic")) {
                en.addProperty("factionMusic", "");
            }
            en.addProperty("music", music);
            en.add("relations", relations);
            en.addProperty("background", background == null ? "" : background);
            if (cinematic) {
                RpChannels.sendTo(p, new RpPackets.CinematicS2C(en.toString()));
            }
        } catch (Exception ex) {
            LOGGER.warn("[CCNR-RP] 入场电影数据异常，跳过动画", ex);
        }
    }

    private void teleport(ServerPlayer p, Wave wave, String factionId) {
        // 阵营出生点优先（管理员可在管理面板配置多个出生点与分布规则）
        if (CCNRRPMod.factions != null && factionId != null && !factionId.isBlank()) {
            com.ccnrcom.rp.faction.FactionManager.FactionSpawn spawn = CCNRRPMod.factions.factionSpawn(factionId);
            if (spawn != null && !spawn.points().isEmpty()) {
                List<com.ccnrcom.rp.faction.FactionManager.SpawnPoint> pts = spawn.points();
                com.ccnrcom.rp.faction.FactionManager.SpawnPoint sp;
                if (com.ccnrcom.rp.faction.FactionManager.SPAWN_RULE_SINGLE.equals(spawn.rule())) {
                    // 集中：同一波次部署的人落同一随机点（按 wave id 稳定取点）
                    sp = pts.get(Math.floorMod(wave.id().hashCode(), pts.size()));
                } else {
                    // 分摊：每个部署的人随机分配一个点，分散开
                    sp = pts.get(new Random().nextInt(pts.size()));
                }
                ServerLevel level = spawnLevel(sp.dim());
                if (level != null) {
                    p.teleportTo(level, sp.x() + 0.5, sp.y(), sp.z() + 0.5, 0, 0);
                    p.setGameMode(GameType.SURVIVAL);
                    return;
                }
            }
        }
        // 回退：wave deployAt / 世界出生点
        ServerLevel level = spawnLevel(wave.dim() == null ? "minecraft:overworld" : wave.dim());
        net.minecraft.core.BlockPos pos = "POS".equals(wave.deployAtType())
                ? new net.minecraft.core.BlockPos((int) wave.x(), (int) wave.y(), (int) wave.z())
                : level.getSharedSpawnPos();
        p.teleportTo(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        p.setGameMode(GameType.SURVIVAL);
    }

    /** 按维度 id 解析 ServerLevel；未知维度回退主世界。 */
    private ServerLevel spawnLevel(String dim) {
        ResourceLocation dimLoc = ResourceLocation.tryParse(dim);
        ServerLevel level = dimLoc == null
                ? null
                : server.getLevel(net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION, dimLoc));
        return level == null ? server.overworld() : level;
    }

    /** 自刷新部署（GUI/命令）：以「职位」为维度，状态 OBSERVING → ALIVE。等级门控已在 CharacterService 校验。 */
    public boolean deployPosition(ServerPlayer player, String professionId) {
        if (player == null || professionId == null || CCNRRPMod.users == null || CCNRRPMod.factions == null) {
            return false;
        }
        String uuid = player.getUUID().toString();
        if (!CCNRRPMod.users.isObserving(uuid) || CCNRRPMod.users.onCooldown(uuid)) {
            return false;
        }
        var def = CCNRRPMod.factions.findProfession(professionId).orElse(null);
        if (def == null) {
            return false;
        }
        String factionId = FactionProfessions.factionId(def);
        // 找用于部署的刷新波（SELF_DEPLOY/BOTH 且职位匹配）；无匹配默认世界原点
        Wave wave = waves.stream()
                .filter(Wave::enabled)
                .filter(w -> w.mode().selfDeployAllowed())
                .filter(w -> w.matchesProfession(professionId, factionId))
                .findFirst()
                .orElse(createDefaultSelfWave());
        return deployAsPosition(uuid, player.getName().getString(), professionId, factionId, wave);
    }

    private Wave createDefaultSelfWave() {
        return new Wave(
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
                60);
    }

    /** 用户级部署核心：装备 → 传送 → 状态 ALIVE（部署 = 新一局开始：重置疏散裁定）。 */
    public boolean deployAsPosition(String uuid, String name, String professionId, String factionId, Wave wave) {
        ServerPlayer p = server.getPlayerList().getPlayer(java.util.UUID.fromString(uuid));
        if (p == null || CCNRRPMod.users == null) {
            return false;
        }
        // 单在场守卫：已有存活身份或以征召兵在场时拒绝
        if (CCNRRPMod.users.isAlive(uuid) || com.ccnrcom.rp.sequence.SequenceEngine.isConscripted(uuid)) {
            return false;
        }
        if (!com.ccnrcom.rp.assets.AssetLibrary.isSynced(p)) {
            RpChannels.sendTo(p, new RpPackets.ErrorS2C("ccnr_rp.gui.asset.syncing"));
            return false;
        }
        applyDeployCore(p, name, professionId, factionId, "", wave, true);
        CCNRRPMod.users.setRole(uuid, professionId, factionId);
        CCNRRPMod.users.setEvacuation(uuid, "none");
        CCNRRPMod.users.setStatus(uuid, CharacterStatus.ALIVE);
        CCNRRPMod.users.setCooldown(uuid, 0);
        CCNRRPMod.users.save();
        if (CCNRRPMod.experience != null) {
            CCNRRPMod.experience.ledger().setEvacSettled(uuid, false);
            CCNRRPMod.experience.ledger().save();
        }
        if (CCNRRPMod.characters != null) {
            CCNRRPMod.characters.sendList(p);
        }
        return true;
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

    // ---------- 征召（邀请制） ----------

    /** 征召邀请入口（强制征召步骤调用）：候选为已创建的征召兵角色（UID 名）。 */
    public void recruitConscript(
            String id, int target, List<Candidate> candidates, List<ServerPlayer> online, long timeoutSec) {
        recruit.offerConscript(id, "conscript", target, candidates, online, timeoutSec);
        LOGGER.info("[CCNR-RP] 征召邀请发出: {}（需要 {} 人）", id, target);
    }

    /** 征召邀请结算：接受者（临时征召兵）按编制部署（不进角色库）。 */
    @Override
    public void onConscriptFinish(String id, List<String> acceptedCharIds, boolean full) {
        if (acceptedCharIds.isEmpty()) {
            eventBroadcast("ccnr_rp.spawn.recruit.failed", id);
            return;
        }
        eventBroadcast(
                full ? "ccnr_rp.spawn.recruit.full" : "ccnr_rp.spawn.recruit.timeout_deploy",
                id,
                String.valueOf(acceptedCharIds.size()));
        int deployed = 0;
        for (String csId : acceptedCharIds) {
            var cs = com.ccnrcom.rp.sequence.SequenceEngine.findConscript(csId);
            if (cs == null) {
                continue;
            }
            // 单在场守卫：已有在场角色或已部署征召 → 拒绝部署并清理（防先复活波后征召的双身份）
            boolean hasAlive = CCNRRPMod.users.isAlive(cs.playerUuid());
            if (hasAlive || com.ccnrcom.rp.sequence.SequenceEngine.isConscripted(cs.playerUuid())) {
                com.ccnrcom.rp.sequence.SequenceEngine.removeConscript(csId);
                var owner = server.getPlayerList().getPlayer(java.util.UUID.fromString(cs.playerUuid()));
                if (owner != null) {
                    RpChannels.sendTo(owner, new RpPackets.ErrorS2C("ccnr_rp.spawn.conscript.conflict"));
                }
                LOGGER.info("[CCNR-RP] 征召部署拒绝（已有在场身份）: {}", cs.name());
                continue;
            }
            com.ccnrcom.rp.sequence.SequenceEngine.markDeployed(csId); // 接受部署：待定 → 在场
            if (deployConscript(csId)) {
                deployed++;
            }
        }
        LOGGER.info("[CCNR-RP] 征召 {} 部署已加入 {} 人（人满={}）", id, deployed, full);
    }

    /** 部署临时征召兵：装备 + 传送 + 生存模式（不涉及角色库）。 */
    public boolean deployConscript(String conscriptId) {
        var cs = com.ccnrcom.rp.sequence.SequenceEngine.findConscript(conscriptId);
        if (cs == null) {
            return false;
        }
        ServerPlayer p = server.getPlayerList().getPlayer(java.util.UUID.fromString(cs.playerUuid()));
        if (p == null) {
            return false;
        }
        // 素材同步完成前禁用部署/复活（客户端异步下载中）
        if (!com.ccnrcom.rp.assets.AssetLibrary.isSynced(p)) {
            RpChannels.sendTo(p, new RpPackets.ErrorS2C("ccnr_rp.gui.asset.syncing"));
            LOGGER.info("[CCNR-RP] 征召部署拒绝：{} 素材同步未完成", p.getName().getString());
            return false;
        }
        // 统一部署磨子（与普通角色同一套）：装备→传送→生存→入场电影
        applyDeployCore(
                p,
                cs.name(),
                cs.professionId(),
                cs.factionId(),
                "临时征召兵（编制 " + cs.professionId() + "）",
                defaultConscriptWave(cs.id()),
                true);
        // 征召兵在场身份推给客户端（HUD 显示征召编制；征召兵不在角色库）
        try {
            com.google.gson.JsonObject st = new com.google.gson.JsonObject();
            st.addProperty("professionId", cs.professionId());
            st.addProperty("factionId", cs.factionId());
            RpChannels.sendTo(p, new RpPackets.ConscriptStateS2C(st.toString()));
        } catch (Exception ignored) {
            // 身份推送失败不阻断部署
        }
        eventBroadcast("ccnr_rp.spawn.conscript.deployed", cs.name(), cs.professionId());
        LOGGER.info("[CCNR-RP] 征召兵 {} 部署完成（编制 {}）", cs.name(), cs.professionId());
        return true;
    }

    /** 邀请作废（拒绝/超时）：临时征召兵直接消失（无角色库内容）。 */
    @Override
    public void onOfferDiscarded(String charId, String id) {
        var cs = com.ccnrcom.rp.sequence.SequenceEngine.findConscript(charId);
        if (cs == null) {
            return;
        }
        com.ccnrcom.rp.sequence.SequenceEngine.removeConscript(charId);
        LOGGER.info("[CCNR-RP] 征召邀请作废（{}）：临时征召兵 {} 已注销", id, cs.name());
    }

    private Wave defaultConscriptWave(String charId) {
        return new Wave(
                "_force_" + charId.replaceAll("[^a-zA-Z0-9_-]", ""),
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
                60);
    }

    // ---------- 招募回调 ----------

    /** 有人选择加入：全服广播已加入名单（已 N/需要 M）。 */
    @Override
    public void onRecruitAccepted(String charName, String waveId, int acceptedCount, int target) {
        eventBroadcast(
                "ccnr_rp.spawn.recruit.joined",
                charName,
                waveId,
                String.valueOf(acceptedCount),
                String.valueOf(target));
    }

    /** 波次结算：人满提前部署；超时按已加入部署；无人加入则失败公告。 */
    @Override
    public void onWaveFinish(String waveId, List<String> acceptedCharIds, boolean full) {
        Optional<Wave> w = wave(waveId);
        if (w.isEmpty()) {
            return;
        }
        Wave wave = w.get();
        if (acceptedCharIds.isEmpty()) {
            eventBroadcast("ccnr_rp.spawn.recruit.failed", waveId);
            return;
        }
        if (full) {
            eventBroadcast("ccnr_rp.spawn.recruit.full", waveId, String.valueOf(acceptedCharIds.size()));
        } else {
            eventBroadcast("ccnr_rp.spawn.recruit.timeout_deploy", waveId, String.valueOf(acceptedCharIds.size()));
        }
        for (String charId : acceptedCharIds) {
            String uuid = charId.startsWith("user-") ? charId.substring(5) : charId;
            ServerPlayer target = server.getPlayerList().getPlayer(java.util.UUID.fromString(uuid));
            if (target == null) {
                continue;
            }
            deployAsPosition(
                    uuid,
                    target.getName().getString(),
                    CCNRRPMod.users.professionId(uuid),
                    CCNRRPMod.users.factionId(uuid),
                    wave);
        }
        LOGGER.info("[CCNR-RP] 复活波 {} 部署已加入 {} 人（人满={}）", waveId, acceptedCharIds.size(), full);
    }

    private void eventBroadcast(String key, String... args) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            RpChannels.sendTo(p, new RpPackets.ErrorS2C(key, args));
        }
    }

    /** 管理端手动召唤复活波（C2S，管理员权限校验）。 */
    public static void onAdminTrigger(ServerPlayer player, String waveId) {
        if (player == null || CCNRRPMod.spawnFramework == null) {
            return;
        }
        if (!com.ccnrcom.rp.util.Permissions.canAdmin(player, com.ccnrcom.rp.util.Permissions.ADMIN_SPAWN)) {
            RpChannels.sendTo(player, new RpPackets.ErrorS2C("ccnr_rp.command.no_permission"));
            return;
        }
        CCNRRPMod.spawnFramework.triggerWave(waveId);
        RpChannels.sendTo(player, new RpPackets.ErrorS2C("ccnr_rp.spawn.triggered", waveId));
    }

    /** 让进度上传等使用到的工具字段（保留占位，勿删）。 */
    static {
        LOGGER.debug("P8 SpawnFramework 就绪");
    }
}
