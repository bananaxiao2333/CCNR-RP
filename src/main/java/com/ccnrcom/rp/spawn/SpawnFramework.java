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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 刷新框架（P8）：自刷新（GUI 部署）/召唤波（队伍创建轮询触发）/招募兜底（右侧列表+超时）。
 * 部署链路：校验 → LoadoutManager.apply → 传送 deployAt → 正式用户状态置 ALIVE / 临时征召不进角色库 → player_spawn 动画。
 */
public final class SpawnFramework implements com.ccnrcom.rp.spawn.RecruitManager.Listener {
    private static final Logger LOGGER = LogManager.getLogger();

    private final MinecraftServer server;
    private final List<Wave> waves = new ArrayList<>();
    private final RecruitManager recruit;
    private long pollCounter = 0;

    /**
     * 落位超时：客户端未在期限内通知（掉线/动画中断/场景时长超预期）时兜底传送，防卡暂存点。
     * 120s 覆盖「HUD 电影 + CMDCam 场景」完整动画时长；正常路径客户端播完即落位，超时仅兜底。
     */
    private static final long LANDING_TIMEOUT_MS = 120_000L;

    /** 待落位部署（入场动画播放期间暂存：落位出生点来源 wave+factionId+professionId，动画完再传）。 */
    private record PendingLanding(Wave wave, String factionId, String professionId, long deadline) {}

    private final Map<UUID, PendingLanding> pendingLandings = new HashMap<>();

    /** 首次入服自动部署（入队时刻 → 等素材同步完成且入服稳定后执行 deploy）。 */
    private final Map<UUID, Long> pendingFirstJoin = new HashMap<>();

    /** 首次入服自动部署的最小等待（ms）：给客户端登录/素材同步留时间，避免入服瞬间抢占。 */
    private static final long FIRST_JOIN_MIN_WAIT_MS = 2_000L;

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
        String key = cfgKey("spawn_waves.json");
        String resource = cfgResource("spawn_waves.json");
        JsonObject root = com.ccnrcom.rp.data.ConfigStore.load(key).orElseGet(() -> {
            JsonObject d = JsonUtil.readResource(resource).orElseGet(JsonObject::new);
            com.ccnrcom.rp.data.ConfigStore.save(key, d);
            return d;
        });
        List<String> errors = SpawnModels.parseWaves(root, waves);
        errors.forEach(e -> LOGGER.error("[CCNR-RP] spawn_waves.json: {}", e));
    }

    /** 剧本配置键按当前模式路由（null-safe；模式未激活回退基础文件名）。 */
    private static String cfgKey(String base) {
        return com.ccnrcom.rp.CCNRRPMod.modes != null ? com.ccnrcom.rp.CCNRRPMod.modes.key(base) : base;
    }

    /** 剧本配置内嵌默认资源路径按当前模式路由（缺档播种用）。 */
    private static String cfgResource(String base) {
        return com.ccnrcom.rp.CCNRRPMod.modes != null ? com.ccnrcom.rp.CCNRRPMod.modes.resource(base) : base;
    }

    /** 热重载（管理器 CRUD 后调用）：重读 spawn_waves.json 并清空队伍触发记录。 */
    public void reload() {
        waves.clear();
        loadWaves();
        LOGGER.info("[CCNR-RP] 刷新波已热重载");
    }

    public List<Wave> waves() {
        return List.copyOf(waves);
    }

    public Optional<Wave> wave(String id) {
        return waves.stream().filter(w -> w.id().equals(id)).findFirst();
    }

    /** 召唤波触发（命令/事件钩子/队伍创建）：指定类型=按类型征召（分配角色，不部署玩家自己的角色）；通用=选岗。 */
    public void triggerWave(String waveId) {
        Optional<Wave> w = wave(waveId);
        if (w.isEmpty() || !w.get().enabled()) {
            LOGGER.info("[CCNR-RP] 召唤波不可触发（未启用）: {}", waveId);
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

    /** 指定类型召唤波 = 按类型征召：候选按波模式过滤（存活可收到/死亡可收到/皆可收到），接受后分配波次编制角色。 */
    private void triggerTypedWave(Wave wave) {
        List<ServerPlayer> pool = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            String uuid = p.getUUID().toString();
            if (com.ccnrcom.rp.sequence.SequenceEngine.hasConscript(uuid)) {
                continue; // 已有征召登记（含挂起中）不再重复邀请
            }
            boolean alive = CCNRRPMod.users.isAlive(uuid);
            boolean ok =
                    alive ? recruitAliveAllowed(wave) : CCNRRPMod.users.isObserving(uuid) && recruitDeadAllowed(wave);
            if (ok) {
                pool.add(p);
            }
        }
        if (pool.isEmpty()) {
            LOGGER.info("[CCNR-RP] 召唤波 {}（指定类型）无候选", wave.id());
            return;
        }
        offerTypedConscripts(wave, pool, Math.max(0, wave.count()));
    }

    /** 通用召唤波：观察者被邀请时弹职业菜单选岗；存活玩家（模式允许时）走指定编制式征召（自动分配职业）。 */
    private void triggerPickWave(Wave wave) {
        List<Candidate> cands = new ArrayList<>();
        List<ServerPlayer> online = new ArrayList<>();
        List<ServerPlayer> alivePool = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            String uuid = p.getUUID().toString();
            if (com.ccnrcom.rp.sequence.SequenceEngine.hasConscript(uuid)) {
                continue; // 已有征召登记（含挂起中）不再重复邀请
            }
            boolean alive = CCNRRPMod.users.isAlive(uuid);
            if (alive) {
                if (recruitAliveAllowed(wave)) {
                    alivePool.add(p); // 存活玩家：指定编制式邀请（选岗菜单仅适用于观察角色）
                }
                continue;
            }
            if (!CCNRRPMod.users.isObserving(uuid) || !recruitDeadAllowed(wave)) {
                continue; // 死亡可收到：需要玩家处于观察状态（有可上岗身份）
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
        // 名额均分：存活征召与观察者选岗各占一半（WaveQuota.split 保证总数 ≤ count，防超招；
        // count<=0 或两通道均无候选 → 整波跳过，与指定类型波一致）
        com.ccnrcom.rp.spawn.SpawnModels.WaveQuota quota = com.ccnrcom.rp.spawn.SpawnModels.WaveQuota.split(
                Math.max(0, wave.count()), alivePool.size(), !cands.isEmpty());
        if (quota.hasAlive()) {
            offerTypedConscripts(wave, alivePool, quota.aliveShare()); // 存活玩家：按波职业池随机分配，指定编制式邀请
        }
        if (quota.hasPick()) {
            recruit.offerPick(wave.id(), quota.pickTarget(), cands, online, wave.recruitTimeoutSeconds());
            LOGGER.info("[CCNR-RP] 召唤波 {}（通用）选岗邀请 {} 人", wave.id(), cands.size());
        }
        if (!quota.hasAlive() && !quota.hasPick()) {
            LOGGER.info("[CCNR-RP] 召唤波 {}（通用）无候选或名额为 0，跳过", wave.id());
        }
    }

    /**
     * 指定编制征召邀请（typed）：为候选玩家分配波次编制职业并登记临时征召兵（UID 名），发出邀请。
     * 候选按各自状态标记（存活/观察）；接受后由 onConscriptFinish 按结算时刻状态决定临时或正式转职部署。
     */
    private void offerTypedConscripts(Wave wave, List<ServerPlayer> pool, int target) {
        java.util.Collections.shuffle(pool, new Random());
        int picked = Math.min(Math.max(0, target), pool.size());
        if (picked <= 0) {
            LOGGER.info("[CCNR-RP] 召唤波 {}（指定类型）无候选", wave.id());
            return;
        }
        List<String> profPool = professionsFor(wave);
        if (profPool.isEmpty()) {
            LOGGER.warn("[CCNR-RP] 召唤波 {} 无可用职业编制，跳过", wave.id());
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
            boolean alive = CCNRRPMod.users.isAlive(uuid);
            cands.add(new Candidate(
                    csId, uuid, uidName, alive ? "alive" : "observing", 0, 0, profId, factionId, false, true, false));
            online.add(p);
        }
        recruit.offerConscript(wave.id(), "typed", picked, cands, online, wave.recruitTimeoutSeconds());
        LOGGER.info("[CCNR-RP] 召唤波 {}（指定类型）按类型征召邀请 {} 人", wave.id(), picked);
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
        // 幕作用域规则（P15 §4.5）：本幕限定职业/阵营时，仅保留允许项（波次按当前幕规则征召）
        String phase = currentPhase();
        if (CCNRRPMod.rules != null) {
            if (CCNRRPMod.rules.limitProfessionsActive(phase)) {
                out.removeIf(pid -> !CCNRRPMod.rules.isProfessionAllowed(phase, pid));
            }
            if (CCNRRPMod.rules.limitFactionsActive(phase)) {
                out.removeIf(pid -> {
                    var def = CCNRRPMod.factions.findProfession(pid).orElse(null);
                    return def != null && !CCNRRPMod.rules.isFactionAllowed(phase, FactionProfessions.factionId(def));
                });
            }
        }
        return out;
    }

    /** 当前幕 id（剧本运行时；无则空串）。 */
    private String currentPhase() {
        return CCNRRPMod.eventManager != null ? CCNRRPMod.eventManager.clock().phaseId() : "";
    }

    /** 本幕存活玩家可否收到邀请（波配置 mode × ruleChange recruitMode 覆盖）。 */
    private boolean recruitAliveAllowed(Wave wave) {
        var rm = CCNRRPMod.rules == null
                ? com.ccnrcom.rp.rule.RuleService.RecruitMode.NONE
                : CCNRRPMod.rules.recruitMode(currentPhase());
        return rm == com.ccnrcom.rp.rule.RuleService.RecruitMode.NONE
                ? wave.mode().aliveReceiveAllowed()
                : (rm == com.ccnrcom.rp.rule.RuleService.RecruitMode.SELF_DEPLOY
                        || rm == com.ccnrcom.rp.rule.RuleService.RecruitMode.BOTH);
    }

    /** 本幕死亡/观察玩家可否收到邀请（波配置 mode × ruleChange recruitMode 覆盖）。 */
    private boolean recruitDeadAllowed(Wave wave) {
        var rm = CCNRRPMod.rules == null
                ? com.ccnrcom.rp.rule.RuleService.RecruitMode.NONE
                : CCNRRPMod.rules.recruitMode(currentPhase());
        return rm == com.ccnrcom.rp.rule.RuleService.RecruitMode.NONE
                ? wave.mode().deadReceiveAllowed()
                : (rm == com.ccnrcom.rp.rule.RuleService.RecruitMode.RESURRECTION
                        || rm == com.ccnrcom.rp.rule.RuleService.RecruitMode.BOTH);
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
        return hex(4);
    }

    private String hex2() {
        return hex(2);
    }

    private static String hex(int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append("0123456789ABCDEF".charAt(RANDOM.nextInt(16)));
        }
        return sb.toString();
    }

    private static final Random RANDOM = new Random();

    /**
     * 每 tick（节流）：招募结算 + 部署落位/首次入服兜底。
     * 波次由脚本/命令显式召（P15 §4.4 纯脚本化）：不再按队伍创建自动触发（移除 teamIds 轮询 diff）。
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        checkPendingLandings(); // 部署落位超时兜底（每 tick，开销可忽略）
        checkFirstJoinDeploys(); // 首次入服自动部署（等素材同步后执行，每 tick 开销可忽略）
        int interval = Math.max(1, CCNRRPConfig.SPAWN_POLL_TICKS.get());
        if (++pollCounter < interval) {
            return;
        }
        pollCounter = 0;
        recruit.onTick();
    }

    // ---------- 部署 ----------

    private int deployCandidates(List<Candidate> toDeploy, Wave wave, boolean recruited) {
        int deployed = 0;
        for (Candidate cand : toDeploy) {
            ServerPlayer p = server.getPlayerList().getPlayer(java.util.UUID.fromString(cand.playerUuid()));
            if (p != null && deploy(p, cand.professionId(), wave, DeployFlag.of())) {
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
            boolean cinematic,
            boolean musicOn) {
        // 部署前清空背包（含护甲/副手）并重设角色状态（生命/饱食/效果/火/坠落/空气）：
        // 防止死亡/观察期间遗留物品与状态带进新岗位
        clearInventory(p);
        // 阵营属性（docs/16）必须在 resetPlayerState **之前**套用：resetPlayerState 用 getMaxHealth() 回满，
        // 顺序反了就会按旧上限回满（改了血量出门却不是满状态）。首次套用/换阵营都会先清理旧修饰（幂等）。
        com.ccnrcom.rp.attribute.AttributeService.applyTo(p, factionId);
        resetPlayerState(p);
        if (CCNRRPMod.factions != null) {
            CCNRRPMod.factions.findProfession(professionId).ifPresent(def -> {
                LoadoutManager.apply(p, FactionProfessions.loadout(def));
            });
        }
        // 入场 CMDCam 场景（覆盖优先级：阵营 < 刷新波 < 职业，职业最高）；仅作可选叠加层，不决定是否播电影
        String cmdcamScene = resolveCmdcamScene(factionId, wave, professionId);
        boolean sceneReady = !cmdcamScene.isBlank() && com.ccnrcom.rp.cmdcam.CamSceneBridge.available();
        // 时序（2.14.0 起「先播后落位」；2.14.5 起电影 HUD/音乐与 CMDCam 解耦；本版起无 CMDCam 改「先落位 + 动画同播」）：
        // - 电影 HUD + 出场音乐始终播放（未 SKIP_CINEMATIC/NO_MUSIC）；CMDCam 场景为可选叠加层：
        //   场景已配置且 CMDCam 已装 → 强制旁观者 + 电影 HUD 与场景同一时刻播放 → 全部播完
        //   （客户端检测 HUD 结束 + 场景结束）发 DeployLandC2S → 移动玩家到部署点 → 设置生存；
        // - 未配置场景 / 未装 CMDCam → 开局直接传送部署点 + 切生存，电影 HUD/音乐与落位同一时刻开始
        //   （不再等动画播完；客户端播完后的 DeployLandC2S 因无待落位记录而为空操作）；
        // - SKIP_CINEMATIC → 开局直接落位切生存（不播动画、不等待）。
        boolean deferred = cinematic && sceneReady; // 仅 CMDCam 场景可用时延迟落位（旁观者播场景）
        if (deferred) {
            p.setGameMode(GameType.SPECTATOR); // 动画全程强制旁观者（不可见/不可交互/不可被打）
            pendingLandings.put(
                    p.getUUID(),
                    new PendingLanding(wave, factionId, professionId, System.currentTimeMillis() + LANDING_TIMEOUT_MS));
            LOGGER.info(
                    "[CCNR-RP] 部署入场动画（旁观者 + 电影 HUD + CMDCam 场景）: {} → 场景 {}",
                    p.getName().getString(),
                    cmdcamScene);
        } else {
            teleport(p, wave, factionId, professionId); // 开局直接落位：移动玩家到部署点 + 切生存（teleport 内含 SURVIVAL）
            LOGGER.info(
                    "[CCNR-RP] 部署直接落位（{}）: {}",
                    cinematic ? "无 CMDCam 场景" : "SKIP_CINEMATIC/跳过动画",
                    p.getName().getString());
        }
        // 入场电影（统一组装：名字/职业/阵营/关系推导/背景）
        try {
            com.google.gson.JsonObject en = new com.google.gson.JsonObject();
            en.addProperty("name", name);
            en.addProperty("professionName", professionId);
            String music = "";
            String profProfile = "";
            com.google.gson.JsonArray relations = new com.google.gson.JsonArray();
            if (CCNRRPMod.factions != null) {
                var profDef = CCNRRPMod.factions.findProfession(professionId).orElse(null);
                if (profDef != null) {
                    en.addProperty("professionName", FactionProfessions.idsSafeName(profDef));
                    music = FactionProfessions.music(profDef);
                    profProfile = FactionProfessions.profile(profDef);
                }
                var graph = CCNRRPMod.factions.graph();
                var f = graph.factions().get(factionId);
                if (f != null) {
                    en.addProperty("factionName", f.name());
                    en.addProperty("icon", f.icon());
                    en.addProperty("tier", f.tier());
                    en.addProperty("factionMusic", f.music());
                    en.addProperty("cinematicBlackScreen", f.cinematicBlackScreen());
                    en.addProperty("cinematicCompact", f.cinematicCompact());
                    // 阵营关系行：按「阵营组 + 关系」合并可合并项（纯逻辑见 CinematicRelations）
                    for (com.google.gson.JsonObject o :
                            com.ccnrcom.rp.faction.CinematicRelations.collapse(graph, factionId)) {
                        relations.add(o);
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
            // 场景不可播放（未配置/未装 CMDCam）时载荷置空：客户端不等待场景，HUD 播完即结束
            en.addProperty("cmdcamScene", sceneReady ? cmdcamScene : "");
            en.addProperty("music", musicOn ? music : "");
            en.add("relations", relations);
            // 「项目简历」行：职业 profile（项目简历）优先，未配置则回退角色背景（当前部署路径背景恒为空）
            en.addProperty("background", !profProfile.isBlank() ? profProfile : (background == null ? "" : background));
            // 入场无线电（播放优先级：职业 > 阵营；职业 radioDisabled 时该职业不播任何无线电）：
            // 播完入场动画后客户端 action bar 打字机逐句展示；注入阵营色（说话人按阵营颜色渲染）
            com.google.gson.JsonObject radio = resolveRadio(factionId, professionId);
            if (com.ccnrcom.rp.faction.FactionProfessions.hasRadioLines(radio)) {
                com.google.gson.JsonObject r = radio.deepCopy();
                var fac = CCNRRPMod.factions == null
                        ? null
                        : CCNRRPMod.factions.graph().factions().get(factionId);
                if (fac != null) {
                    r.addProperty("color", fac.color() == null ? "#FFFFFF" : fac.color());
                }
                en.add("radio", r);
            }
            if (cinematic) {
                // 电影 HUD 与（可选）CMDCam 场景同一时刻开始播放（场景在目标维度查取）；
                // 无 CMDCam 时玩家已先落位切生存，动画仅作视觉叠加
                RpChannels.sendTo(p, new RpPackets.CinematicS2C(en.toString()));
                if (sceneReady) {
                    com.ccnrcom.rp.cmdcam.CamSceneBridge.playScene(
                            resolveDeployLevel(wave, factionId, professionId), cmdcamScene, p);
                }
            }
        } catch (Exception ex) {
            LOGGER.warn("[CCNR-RP] 入场电影数据异常，跳过动画", ex);
            if (deferred) {
                // 兜底：电影数据异常 → 直接落位（防卡在暂存点）
                pendingLandings.remove(p.getUUID());
                teleport(p, wave, factionId, professionId);
            }
        }
    }

    /**
     * 入场无线电（播放优先级：职业 > 阵营；职业 radioDisabled=true 时不播任何无线电，含阵营默认）。
     * 返回 {speaker, lines:[{text, wait}]}；未配置/禁用返回 null。
     */
    private static com.google.gson.JsonObject resolveRadio(String factionId, String professionId) {
        if (CCNRRPMod.factions == null) {
            return null;
        }
        var profDef = CCNRRPMod.factions.findProfession(professionId).orElse(null);
        if (profDef != null) {
            if (FactionProfessions.radioDisabled(profDef)) {
                return null; // 职业禁用无线电：不播（含阵营默认）
            }
            com.google.gson.JsonObject profRadio = FactionProfessions.radio(profDef);
            if (FactionProfessions.hasRadioLines(profRadio)) {
                return profRadio; // 职业无线电优先
            }
        }
        return CCNRRPMod.factions.factionRadio(factionId);
    }

    /** 入场 CMDCam 场景（覆盖优先级：阵营 < 刷新波 < 职业，职业最高）；未配置返回空串。 */
    private static String resolveCmdcamScene(String factionId, Wave wave, String professionId) {
        String profScene = "";
        String facScene = "";
        if (CCNRRPMod.factions != null) {
            var profDef = CCNRRPMod.factions.findProfession(professionId).orElse(null);
            if (profDef != null) {
                profScene = FactionProfessions.cmdcamScene(profDef);
            }
            var f = CCNRRPMod.factions.graph().factions().get(factionId);
            if (f != null) {
                facScene = f.cmdcamScene() == null ? "" : f.cmdcamScene();
            }
        }
        String scene = facScene;
        if (wave != null && wave.cmdcamScene() != null && !wave.cmdcamScene().isBlank()) {
            scene = wave.cmdcamScene();
        }
        if (!profScene.isBlank()) {
            scene = profScene;
        }
        return scene;
    }

    /**
     * 客户端全部动画播完（DeployLandC2S，HUD 电影 + CMDCam 场景均已结束）：
     * 移动玩家到部署点 → 设置生存（teleport 内部先传送后设 SURVIVAL）。
     * CMDCam 场景已在部署时与电影同步播放，此处不再触发。
     */
    public void onDeployLand(ServerPlayer player) {
        PendingLanding landing = pendingLandings.remove(player.getUUID());
        if (landing == null) {
            return; // 非待落位（重复/过期通知）忽略
        }
        teleport(player, landing.wave(), landing.factionId(), landing.professionId());
        LOGGER.info("[CCNR-RP] 部署落位完成: {}", player.getName().getString());
    }

    /** 落位超时兜底（每 tick 调用）：动画期间掉线/中断不卡状态，超时直接落位。 */
    private void checkPendingLandings() {
        if (pendingLandings.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        var it = pendingLandings.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getValue().deadline() > now) {
                continue;
            }
            UUID uuid = e.getKey();
            PendingLanding landing = e.getValue();
            it.remove();
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null) {
                teleport(p, landing.wave(), landing.factionId(), landing.professionId());
                LOGGER.warn("[CCNR-RP] 部署落位超时兜底（动画未完成通知）: {}", p.getName().getString());
            }
        }
    }

    /** 掉线清理：入场动画期间掉线移除待落位（离线判死流程接管，防残留）。 */
    @SubscribeEvent
    public void onPlayerLoggedOut(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            pendingLandings.remove(player.getUUID());
            pendingFirstJoin.remove(player.getUUID());
        }
    }

    /** 清空玩家背包/护甲/副手（部署前防遗留物品）。 */
    private static void clearInventory(ServerPlayer p) {
        net.minecraft.world.entity.player.Inventory inv = p.getInventory();
        for (int i = 0; i < 41; i++) { // 0-35 背包 + 36-39 护甲 + 40 副手
            inv.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
        }
    }

    /**
     * 重设角色状态（部署前，配合 clearInventory）：生命回满、饱食度/饱和度/消耗回满、
     * 清空全部药水效果、灭火、清坠落距离、补满空气、清除吸收值——新岗位不带旧状态上场。
     */
    private static void resetPlayerState(ServerPlayer p) {
        p.setHealth(p.getMaxHealth());
        p.getFoodData().setFoodLevel(20);
        p.getFoodData().setSaturation(5.0f);
        p.getFoodData().setExhaustion(0.0f);
        p.removeAllEffects();
        p.clearFire();
        p.setAbsorptionAmount(0.0f);
        p.fallDistance = 0.0f;
        p.setAirSupply(p.getMaxAirSupply());
    }

    /** 部署目标维度：职业部署点维度优先，否则阵营出生点维度，再否则 wave.dim，最后主世界（与 teleport 落位一致）。 */
    private ServerLevel resolveDeployLevel(Wave wave, String factionId, String professionId) {
        if (CCNRRPMod.factions != null && professionId != null && !professionId.isBlank()) {
            com.ccnrcom.rp.faction.FactionManager.FactionSpawn ps = CCNRRPMod.factions.professionSpawn(professionId);
            if (ps != null && !ps.points().isEmpty()) {
                ServerLevel level = spawnLevel(ps.points().get(0).dim());
                if (level != null) {
                    return level;
                }
            }
        }
        if (CCNRRPMod.factions != null && factionId != null && !factionId.isBlank()) {
            com.ccnrcom.rp.faction.FactionManager.FactionSpawn spawn = CCNRRPMod.factions.factionSpawn(factionId);
            if (spawn != null && !spawn.points().isEmpty()) {
                ServerLevel level = spawnLevel(spawn.points().get(0).dim());
                if (level != null) {
                    return level;
                }
            }
        }
        return spawnLevel(wave.dim() == null ? "minecraft:overworld" : wave.dim());
    }

    /**
     * 落位传送（部署点优先级：职业部署点 > 阵营部署点 > wave deployAt > 世界复活点）。
     * 职业/阵营部署点均为管理面板可配置的「多个点 + 分布规则」。
     */
    private void teleport(ServerPlayer p, Wave wave, String factionId, String professionId) {
        // 职业部署点优先（管理面板可为每个职业配置专属部署点）
        if (CCNRRPMod.factions != null && professionId != null && !professionId.isBlank()) {
            com.ccnrcom.rp.faction.FactionManager.FactionSpawn ps = CCNRRPMod.factions.professionSpawn(professionId);
            if (ps != null && !ps.points().isEmpty()) {
                List<com.ccnrcom.rp.faction.FactionManager.SpawnPoint> pts = ps.points();
                com.ccnrcom.rp.faction.FactionManager.SpawnPoint sp;
                if (com.ccnrcom.rp.faction.FactionManager.SPAWN_RULE_SINGLE.equals(ps.rule())) {
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
        // 阵营出生点次之（管理员可在管理面板配置多个出生点与分布规则）
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
        // 回退：wave deployAt / 世界复活点（目标维度与 resolveDeployLevel 一致）
        ServerLevel level = resolveDeployLevel(wave, factionId, professionId);
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
        // 幕作用域规则（P15 §4.5）：本幕限定了职业/阵营时，自部署不可选被禁项
        String phase = currentPhase();
        if (CCNRRPMod.rules != null
                && ((CCNRRPMod.rules.limitProfessionsActive(phase)
                                && !CCNRRPMod.rules.isProfessionAllowed(phase, professionId))
                        || (CCNRRPMod.rules.limitFactionsActive(phase)
                                && !CCNRRPMod.rules.isFactionAllowed(phase, factionId)))) {
            return false;
        }
        // 找用于部署的刷新波（SELF_DEPLOY/BOTH 且职位匹配）；无匹配默认世界原点
        return deploy(player, professionId, selfDeployWave(professionId, factionId), DeployFlag.of());
    }

    /** 默认自部署波（世界出生点；管理刷人/无匹配波时用）。 */
    public Wave defaultSelfWave() {
        return createDefaultSelfWave();
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

    /**
     * 唯一部署核心：自部署 / 管理员刷人 / 复活波 / 强制征召 / 手动部署全部汇入此入口。
     * 行为差异一律由 {@link DeployFlag} 控制（SKIP_CINEMATIC / FORCE_DEPLOY / NO_MUSIC / QUIET / TEMP），
     * 不定义预设与来源：装备 → 传送 → 生存 → 入场电影（按 SKIP/NO_MUSIC）→ 状态/角色（按 TEMP）→ 广播（按 QUIET）→ 经验回合重置。
     */
    public boolean deploy(ServerPlayer player, String professionId, Wave wave, Set<DeployFlag> flags) {
        if (player == null
                || professionId == null
                || wave == null
                || CCNRRPMod.users == null
                || CCNRRPMod.factions == null) {
            return false;
        }
        String uuid = player.getUUID().toString();
        boolean force = flags != null && flags.contains(DeployFlag.FORCE_DEPLOY);
        boolean temp = flags != null && flags.contains(DeployFlag.TEMP);
        boolean quiet = flags != null && flags.contains(DeployFlag.QUIET);
        // 素材同步完成前禁用部署/复活（客户端异步下载中）
        if (!com.ccnrcom.rp.assets.AssetLibrary.isSynced(player)) {
            RpChannels.sendTo(player, new RpPackets.ErrorS2C("ccnr_rp.gui.asset.syncing"));
            return false;
        }
        // 单在场守卫（FORCE_DEPLOY 跳过）：已有存活身份或以征召兵在场时拒绝（防双身份）
        if (!force && (CCNRRPMod.users.isAlive(uuid) || com.ccnrcom.rp.sequence.SequenceEngine.isConscripted(uuid))) {
            return false;
        }
        var def = CCNRRPMod.factions.findProfession(professionId).orElse(null);
        // 职位定义缺失时按空阵营部署（保持原 deployAsPosition 行为：无装备、传送默认点；pick 波观察者可能无职位）
        String factionId = def == null ? "" : FactionProfessions.factionId(def);
        String displayName = def == null ? professionId : FactionProfessions.idsSafeName(def);
        // 部署人数限制（全局性，统一入口检测；管理员刷人/强制征召用 LIMIT_SKIP 跳过）：
        // 重新部署（玩家在场换岗）也检测——目标职业/阵营在职数按「不含本人」计算，避免换岗误判自占位。
        boolean skipLimit = flags != null && flags.contains(DeployFlag.LIMIT_SKIP);
        if (!skipLimit && !temp) {
            boolean selfAlive = CCNRRPMod.users.isAlive(uuid);
            int profCount = CCNRRPMod.users.aliveCountByProfession(professionId)
                    - (selfAlive && professionId.equals(CCNRRPMod.users.professionId(uuid)) ? 1 : 0);
            int facCount = CCNRRPMod.users.aliveCountByFaction(factionId)
                    - (selfAlive && factionId.equals(CCNRRPMod.users.factionId(uuid)) ? 1 : 0);
            var denial = com.ccnrcom.rp.spawn.DeployLimits.check(
                    com.ccnrcom.rp.spawn.DeployLimits.parse(com.ccnrcom.rp.util.ConfigCrud.root("limits.json")),
                    professionId,
                    factionId,
                    profCount,
                    facCount);
            if (denial.isPresent()) {
                com.ccnrcom.rp.spawn.DeployLimits.Denial d = denial.get();
                String targetName = "ccnr_rp.spawn.limit.profession_full".equals(d.key())
                        ? displayName
                        : factionDisplayName(d.target());
                RpChannels.sendTo(player, new RpPackets.ErrorS2C(d.key(), targetName, String.valueOf(d.limit())));
                return false;
            }
        }
        boolean cinematic = flags == null || !flags.contains(DeployFlag.SKIP_CINEMATIC);
        boolean musicOn = flags == null || !flags.contains(DeployFlag.NO_MUSIC);
        applyDeployCore(player, player.getName().getString(), professionId, factionId, "", wave, cinematic, musicOn);
        if (temp) {
            // 临时身份（征召兵）：不写用户库，仅推送征召身份给客户端（HUD 显示征召编制）
            try {
                com.google.gson.JsonObject st = new com.google.gson.JsonObject();
                st.addProperty("professionId", professionId);
                st.addProperty("factionId", factionId);
                RpChannels.sendTo(player, new RpPackets.ConscriptStateS2C(st.toString()));
            } catch (Exception ignored) {
                // 身份推送失败不阻断部署
            }
            if (!quiet) {
                eventBroadcast(
                        "ccnr_rp.spawn.conscript.deployed", player.getName().getString(), displayName);
            }
        } else {
            // 正式用户：状态 ALIVE + 冷却清零 + 经验回合重置（部署 = 新一局开始：
            // 待结算经验变化列表清空、存活秒数归零；已累计用户 XP 保留）
            CCNRRPMod.users.setRole(uuid, professionId, factionId);
            CCNRRPMod.users.setStatus(uuid, CharacterStatus.ALIVE);
            CCNRRPMod.users.setCooldown(uuid, 0);
            CCNRRPMod.users.setXpDuty(uuid, CCNRRPMod.users.userXp(uuid), 0);
            CCNRRPMod.users.setPendingXp(uuid, java.util.List.of());
            CCNRRPMod.users.save();
            if (CCNRRPMod.characters != null) {
                CCNRRPMod.characters.sendList(player);
            }
            if (!quiet) {
                RpChannels.sendTo(player, new RpPackets.ErrorS2C("ccnr_rp.spawn.deployed", displayName));
            }
        }
        // 部署完成客户端常驻横幅（独立提示，QUIET 跳过）：邀请/完毕/提前部署/正式转职统一在这里发
        if (!quiet) {
            RpChannels.sendTo(player, new RpPackets.DeployNoticeS2C(displayName, factionId));
        }
        LOGGER.info(
                "[CCNR-RP] 部署 [{}] {} → {}（{}），wave={}",
                temp ? "TEMP" : "USER",
                player.getName().getString(),
                professionId,
                factionId,
                wave.id());
        // 部署后刷新全服头顶标签（职位/阵营/等级可能变化）
        if (CCNRRPMod.characters != null) {
            CCNRRPMod.characters.broadcastPlayerTags();
        }
        return true;
    }

    // ---------- 首次入服自动部署 ----------

    /**
     * 首次入服自动部署入队（登录时调用；必须在用户档案被惰性创建前判定「首次」）：
     * 玩家无用户档案（首次进入设施）且设置了自动部署职业 → 入队，等素材同步完成且入服稳定后走统一 deploy()。
     * 后续入服已有档案不再触发；已被其他入口部署（管理刷人/复活波）时入队扫描自动跳过。
     */
    public void maybeQueueFirstJoin(ServerPlayer player) {
        if (player == null
                || CCNRRPMod.users == null
                || CCNRRPMod.factions == null
                || CCNRRPMod.managerSettings == null) {
            return;
        }
        if (!CCNRRPMod.managerSettings.firstJoinAutoDeploy()) {
            return;
        }
        String uuid = player.getUUID().toString();
        if (CCNRRPMod.users.hasProfile(uuid)) {
            return; // 非首次入服（已有档案）
        }
        String profId = CCNRRPMod.managerSettings.firstJoinProfession();
        if (profId == null || profId.isBlank()) {
            return; // 未配置自动部署职业（空串 = 关闭）
        }
        if (CCNRRPMod.factions.findProfession(profId).isEmpty()) {
            LOGGER.warn("[CCNR-RP] 首次入服自动部署职业不存在（跳过）: {}", profId);
            return;
        }
        pendingFirstJoin.put(player.getUUID(), System.currentTimeMillis());
        LOGGER.info("[CCNR-RP] 首次入服玩家已入队自动部署: {} → {}", player.getName().getString(), profId);
    }

    /** 每 tick：等素材同步完成（客户端异步下载中，60s 超时兜底）且过最小等待后执行自动部署。 */
    private void checkFirstJoinDeploys() {
        if (pendingFirstJoin.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        var it = pendingFirstJoin.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            UUID uuid = e.getKey();
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p == null) {
                it.remove(); // 已离线（onPlayerLoggedOut 兜底清理）
                continue;
            }
            String suuid = uuid.toString();
            // 已被其他入口部署（管理刷人/复活波/手动部署）→ 不再自动部署
            if (CCNRRPMod.users == null
                    || CCNRRPMod.users.isAlive(suuid)
                    || com.ccnrcom.rp.sequence.SequenceEngine.isConscripted(suuid)) {
                it.remove();
                continue;
            }
            if (now - e.getValue() < FIRST_JOIN_MIN_WAIT_MS || !com.ccnrcom.rp.assets.AssetLibrary.isSynced(p)) {
                continue; // 未到最小等待 / 素材未同步完成
            }
            String profId = CCNRRPMod.managerSettings == null ? "" : CCNRRPMod.managerSettings.firstJoinProfession();
            var def = CCNRRPMod.factions == null || profId.isBlank()
                    ? null
                    : CCNRRPMod.factions.findProfession(profId).orElse(null);
            if (def == null) {
                LOGGER.warn("[CCNR-RP] 首次入服自动部署职业无效，取消: {}（{}）", p.getName().getString(), profId);
                it.remove();
                continue;
            }
            String factionId = com.ccnrcom.rp.faction.FactionProfessions.factionId(def);
            boolean ok = deploy(p, profId, selfDeployWave(profId, factionId), DeployFlag.of());
            it.remove();
            LOGGER.info(
                    "[CCNR-RP] 首次入服自动部署{}: {} → {}（{}）",
                    ok ? "完成" : "失败",
                    p.getName().getString(),
                    profId,
                    factionId);
        }
    }

    /** 自部署落点：首个启用且允许自部署并匹配该职业的刷新波，否则默认自部署波（世界出生点）。 */
    private Wave selfDeployWave(String professionId, String factionId) {
        return waves.stream()
                .filter(Wave::enabled)
                .filter(w -> w.mode().selfDeployAllowed())
                .filter(w -> w.matchesProfession(professionId, factionId))
                .findFirst()
                .orElse(createDefaultSelfWave());
    }

    // ---------- 重新部署 ----------

    /**
     * 重新部署（GUI 确认后，onKillDeploy 调用）：在场（ALIVE）玩家直接重新部署为选定职位——
     * 不处死、不留遗体、不结算死亡经验。走统一 deploy()（FORCE_DEPLOY 绕过单在场守卫）：
     * 清背包 → 发放新职位装备 → 传送到部署点（阵营出生点 → 匹配波 deployAt → 世界出生点）→ 入场电影 → ALIVE + 冷却清零。
     */
    public boolean redeploy(ServerPlayer player, String professionId) {
        if (player == null || professionId == null || CCNRRPMod.users == null || CCNRRPMod.factions == null) {
            return false;
        }
        String uuid = player.getUUID().toString();
        if (!CCNRRPMod.users.isAlive(uuid)) {
            return false; // 仅在场（ALIVE）可重新部署（onKillDeploy 已校验，防御性复查）
        }
        var def = CCNRRPMod.factions.findProfession(professionId).orElse(null);
        if (def == null) {
            return false;
        }
        String factionId = com.ccnrcom.rp.faction.FactionProfessions.factionId(def);
        return deploy(
                player, professionId, selfDeployWave(professionId, factionId), DeployFlag.of(DeployFlag.FORCE_DEPLOY));
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
                                w.cmdcamScene(),
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

    /**
     * 征召邀请结算：接受者按「当前角色状态」决定部署方式（SpawnModels.ConscriptDeployMode，docs/09 §4.3）：
     * - OBSERVING/DEAD → 临时征召兵部署（TEMP，不进角色库，阵亡/结束回到原身份）；
     * - ALIVE → 正式转职部署（不处死：直接改用户角色为征召职业 + 状态 ALIVE + 冷却清零）。
     * 状态在邀请与结算之间可能漂移（如接受后自行部署/死亡），一律以结算时刻的当前状态为准；
     * 部署成功后才变更征召登记（markDeployed/removeConscript），失败即清理，防「以征召在场」状态卡死。
     */
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
            String suuid = cs.playerUuid();
            ServerPlayer owner = server.getPlayerList().getPlayer(java.util.UUID.fromString(suuid));
            if (owner == null) {
                com.ccnrcom.rp.sequence.SequenceEngine.removeConscript(csId); // 已离线：征召清理
                continue;
            }
            if (com.ccnrcom.rp.sequence.SequenceEngine.isConscripted(suuid)) {
                // 已部署过征召（防双身份，仅观察者路径会走到；存活转职不走 TEMP 不产生征召在场）
                com.ccnrcom.rp.sequence.SequenceEngine.removeConscript(csId);
                RpChannels.sendTo(owner, new RpPackets.ErrorS2C("ccnr_rp.spawn.conscript.conflict"));
                LOGGER.info("[CCNR-RP] 征召部署拒绝（已有征召在场）: {}", cs.name());
                continue;
            }
            com.ccnrcom.rp.spawn.SpawnModels.ConscriptDeployMode mode =
                    com.ccnrcom.rp.spawn.SpawnModels.ConscriptDeployMode.of(CCNRRPMod.users.status(suuid));
            if (mode == com.ccnrcom.rp.spawn.SpawnModels.ConscriptDeployMode.SKIP) {
                com.ccnrcom.rp.sequence.SequenceEngine.removeConscript(csId);
                LOGGER.warn("[CCNR-RP] 征召 {} 状态异常（{}），取消部署: {}", id, CCNRRPMod.users.status(suuid), cs.name());
                continue;
            }
            boolean temp = mode == com.ccnrcom.rp.spawn.SpawnModels.ConscriptDeployMode.TEMP;
            boolean ok = deploy(
                    owner,
                    cs.professionId(),
                    defaultConscriptWave(cs.id()),
                    temp
                            ? DeployFlag.of(DeployFlag.FORCE_DEPLOY, DeployFlag.TEMP, DeployFlag.LIMIT_SKIP)
                            : DeployFlag.of(DeployFlag.FORCE_DEPLOY, DeployFlag.LIMIT_SKIP));
            if (!ok) {
                // 部署失败（素材未同步/服务未就绪等）：清理征召登记，避免 pending/在场 状态卡死
                com.ccnrcom.rp.sequence.SequenceEngine.removeConscript(csId);
                LOGGER.warn("[CCNR-RP] 征召 {} 部署失败（{}）: {}", id, temp ? "TEMP" : "转职", cs.name());
                continue;
            }
            if (temp) {
                com.ccnrcom.rp.sequence.SequenceEngine.markDeployed(csId); // 部署成功后才标记在场
            } else {
                com.ccnrcom.rp.sequence.SequenceEngine.removeConscript(csId); // 正式转职：征召登记注销
            }
            deployed++;
        }
        LOGGER.info("[CCNR-RP] 征召 {} 部署已加入 {} 人（人满={}）", id, deployed, full);
    }

    /** 部署临时征召兵（兼容入口）：路由到唯一部署核心 deploy()（FORCE_DEPLOY + TEMP）。 */
    public boolean deployConscript(String conscriptId) {
        var cs = com.ccnrcom.rp.sequence.SequenceEngine.findConscript(conscriptId);
        if (cs == null) {
            return false;
        }
        ServerPlayer p = server.getPlayerList().getPlayer(java.util.UUID.fromString(cs.playerUuid()));
        if (p == null) {
            return false;
        }
        return deploy(
                p,
                cs.professionId(),
                defaultConscriptWave(cs.id()),
                DeployFlag.of(DeployFlag.FORCE_DEPLOY, DeployFlag.TEMP, DeployFlag.LIMIT_SKIP));
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
            // 结算时刻重新校验：观察者身份仍有效才部署（接受后已自行部署/状态漂移则跳过，防二次覆盖）
            if (!CCNRRPMod.users.isObserving(uuid)) {
                LOGGER.info(
                        "[CCNR-RP] 召唤波 {} 结算跳过 {}（已非观察者状态）",
                        waveId,
                        target.getName().getString());
                continue;
            }
            // FORCE_DEPLOY：观察者按自己当前职业部署（正式转职语义，不处死）
            deploy(target, CCNRRPMod.users.professionId(uuid), wave, DeployFlag.of(DeployFlag.FORCE_DEPLOY));
        }
        LOGGER.info("[CCNR-RP] 召唤波 {} 部署已加入 {} 人（人满={}）", waveId, acceptedCharIds.size(), full);
    }

    private void eventBroadcast(String key, String... args) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            RpChannels.sendTo(p, new RpPackets.ErrorS2C(key, args));
        }
    }

    /** 阵营显示名（无则回退 id）。 */
    private String factionDisplayName(String factionId) {
        if (CCNRRPMod.factions == null || factionId == null || factionId.isBlank()) {
            return factionId == null ? "" : factionId;
        }
        var f = CCNRRPMod.factions.graph().factions().get(factionId);
        return f != null && f.name() != null && !f.name().isBlank() ? f.name() : factionId;
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
