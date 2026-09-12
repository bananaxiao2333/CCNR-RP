/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.sequence;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.rule.RuleService;
import com.ccnrcom.rp.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 序列引擎（序列编辑器）：顺序执行步骤 WAIT/WAVE/COMMAND/FORCE_PICK，支持 {{变量}} 注入。
 * 变量来自序列上下文（event/phase/wave/seq/faction/count…）。
 * FORCE_PICK：强制征召（邀请制）——为被选用户创建临时征召兵（UID 名 + 编制职业），
 * 不进角色库（不占角色上限、不显示在 K 面板），接受后部署、拒绝/超时/阵亡即消失。
 */
public final class SequenceEngine {
    private static final Logger LOGGER = LogManager.getLogger();

    /** 临时征召兵登记（不落角色库，完全临时；key=征召 ID）。pending=邀请挂起中；deployedAt=部署时刻（值班结算用）。 */
    public record Conscript(
            String id,
            String playerUuid,
            String name,
            String professionId,
            String factionId,
            boolean pending,
            long deployedAt) {
        public Conscript withPending(boolean v) {
            return new Conscript(id, playerUuid, name, professionId, factionId, v, deployedAt);
        }

        public Conscript withDeployedAt(long at) {
            return new Conscript(id, playerUuid, name, professionId, factionId, pending, at);
        }
    }

    private static final java.util.Map<String, Conscript> CONSCRIPTS = new java.util.concurrent.ConcurrentHashMap<>();

    public static Conscript findConscript(String id) {
        return CONSCRIPTS.get(id);
    }

    /** 该玩家是否正在以征召兵身份在场（仅已接受部署的征召；邀请挂起中不算，避免把未入队玩家切生存）。 */
    public static boolean isConscripted(String playerUuid) {
        return CONSCRIPTS.values().stream().anyMatch(c -> c.playerUuid().equals(playerUuid) && !c.pending());
    }

    /** 该玩家是否已有征召登记（含邀请挂起中）——用于候选池过滤，防止同一玩家被多个波重复邀请（防双身份/重复部署）。 */
    public static boolean hasConscript(String playerUuid) {
        return CONSCRIPTS.values().stream().anyMatch(c -> c.playerUuid().equals(playerUuid));
    }

    /** 接受部署：把征召从"待定"转为"在场"，并记录部署时刻（值班结算用）。 */
    public static void markDeployed(String id) {
        Conscript c = CONSCRIPTS.get(id);
        if (c != null && c.pending()) {
            CONSCRIPTS.put(id, c.withPending(false).withDeployedAt(System.currentTimeMillis()));
        }
    }

    /** 征召兵已执勤秒数（部署到现在的时长，死亡结算玩家加分用）。 */
    public static long conscriptDutySeconds(String playerUuid) {
        long now = System.currentTimeMillis();
        return CONSCRIPTS.values().stream()
                .filter(c -> c.playerUuid().equals(playerUuid) && !c.pending() && c.deployedAt() > 0)
                .mapToLong(c -> Math.max(0, (now - c.deployedAt()) / 1000L))
                .findFirst()
                .orElse(0L);
    }

    public static void removeConscript(String id) {
        CONSCRIPTS.remove(id);
    }

    /** 清除该玩家的征召登记；返回是否确有征召被清理。 */
    public static boolean removeConscriptFor(String playerUuid) {
        return CONSCRIPTS.values().removeIf(c -> c.playerUuid().equals(playerUuid));
    }

    public static void registerConscript(Conscript conscript) {
        CONSCRIPTS.put(conscript.id(), conscript);
    }

    /** 服务停止/世界切换时清空临时征召登记（静态态不应跨世界残留）。 */
    public static void clearConscripts() {
        CONSCRIPTS.clear();
    }

    private final MinecraftServer server;
    private final Random random = new Random();
    private JsonObject root = new JsonObject();
    private final List<Run> runs = new ArrayList<>();

    private record Run(String seqId, Map<String, String> vars, List<Step> steps, long startAt) {}

    private record Step(String type, JsonObject params, long dueAtMs) {}

    public SequenceEngine(MinecraftServer server) {
        this.server = server;
        load();
    }

    // ---------- 配置 ----------

    public void load() {
        JsonObject d =
                JsonUtil.readResource("/assets/ccnr_rp/defaults/sequences.json").orElseGet(JsonObject::new);
        root = com.ccnrcom.rp.data.ConfigStore.load("sequences.json").orElse(d);
        if (root.size() == 0) {
            com.ccnrcom.rp.data.ConfigStore.save("sequences.json", d);
            root = d;
        }
    }

    public void reload() {
        load();
        LOGGER.info("[CCNR-RP] 序列已热重载");
    }

    public JsonObject root() {
        return root;
    }

    public List<String> list() {
        List<String> out = new ArrayList<>();
        if (root.has("sequences") && root.get("sequences").isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray("sequences")) {
                if (e.isJsonObject()) {
                    out.add(e.getAsJsonObject().get("id").getAsString());
                }
            }
        }
        return out;
    }

    // ---------- 运行 ----------

    /** 运行序列（带上下文变量）。返回错误列表（空=已启动）。 */
    public List<String> run(String seqId, Map<String, String> vars) {
        JsonArray arr = root.has("sequences") ? root.getAsJsonArray("sequences") : null;
        if (arr == null) {
            return List.of("序列配置为空");
        }
        JsonObject seq = null;
        for (JsonElement e : arr) {
            if (e.isJsonObject() && seqId.equals(e.getAsJsonObject().get("id").getAsString())) {
                seq = e.getAsJsonObject();
                break;
            }
        }
        if (seq == null || !seq.has("steps")) {
            return List.of("未找到序列: " + seqId);
        }
        List<JsonObject> steps = new ArrayList<>();
        for (JsonElement e : seq.getAsJsonArray("steps")) {
            if (e.isJsonObject()) {
                steps.add(e.getAsJsonObject());
            }
        }
        return runSteps("seq/" + seqId, steps, vars);
    }

    /** 直接运行一组步骤（事件/阶段/复活波内嵌行为，不再单独成实体）。 */
    public List<String> runSteps(String label, List<JsonObject> stepsIn, Map<String, String> vars) {
        if (stepsIn == null || stepsIn.isEmpty()) {
            return List.of("无步骤");
        }
        long now = System.currentTimeMillis();
        long acc = 0;
        List<Step> steps = new ArrayList<>();
        String trigger = "";
        for (JsonObject s : stepsIn) {
            String type = str(s, "type", "WAIT").toUpperCase(java.util.Locale.ROOT);
            // TRIGGER 锚点：序列首项的「触发事件」语义（代表触发本序列的真实事件/环境），
            // 只读展示与变量注入，不执行、不占时间线
            if ("TRIGGER".equals(type)) {
                if (trigger.isBlank()) {
                    trigger = str(s, "source", "");
                }
                continue;
            }
            long delay = num(s, "seconds", 0);
            steps.add(new Step(type, s, now + acc));
            // WAIT 消耗时间，其余步骤在同一时间点顺序执行
            if ("WAIT".equals(type)) {
                acc += Math.max(0, delay) * 1000L;
            }
        }
        if (steps.isEmpty()) {
            LOGGER.info("[CCNR-RP] 序列仅含触发锚点（无执行步骤），跳过: {}", label);
            return List.of();
        }
        Map<String, String> merged = new HashMap<>(vars);
        merged.put("seq", label);
        // 触发环境注入：COMMAND 步骤可用 {{trigger}} 引用触发来源（如 event:qdf_support）
        if (!trigger.isBlank()) {
            merged.put("trigger", trigger);
        }
        synchronized (runs) {
            runs.add(new Run(label, merged, steps, now));
        }
        LOGGER.info("[CCNR-RP] 序列启动: {} ({} 步)", label, steps.size());
        pushMatchState(); // 新序列开始 = "下一步"变了：推送对局状态给面板
        return List.of();
    }

    /**
     * 下一个待执行步骤还有多少毫秒；没有运行中的序列返回 -1。
     *
     * <p>供左侧对局状态面板显示"序列下一步倒计时"——剧本常用 `WAIT 180` 这种写法表达时序
     * （例如 evac_round 的"3 分钟广播 / 5 分钟疏散"），阶段表本身并没有倒计时，
     * 那种剧本的计时数字就来自这里（docs/14 §5.8）。
     */
    public long nextStepInMs() {
        long best = -1;
        long now = System.currentTimeMillis();
        synchronized (runs) {
            for (Run r : runs) {
                for (Step s : r.steps()) {
                    long left = s.dueAtMs() - now;
                    if (left < 0) {
                        left = 0;
                    }
                    if (best < 0 || left < best) {
                        best = left;
                    }
                }
            }
        }
        return best;
    }

    /** 正在运行的序列名（取最早到期的那条；无则空串）——面板上标注"下一步属于哪个序列"。 */
    public String nextStepLabel() {
        String label = "";
        long best = Long.MAX_VALUE;
        synchronized (runs) {
            for (Run r : runs) {
                for (Step s : r.steps()) {
                    if (s.dueAtMs() < best) {
                        best = s.dueAtMs();
                        label = r.seqId();
                    }
                }
            }
        }
        return label;
    }

    /** 服务端 tick：到点执行步骤。 */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Step> due = new ArrayList<>();
        Map<String, String> vars = new HashMap<>();
        boolean advanced = false;
        synchronized (runs) {
            for (int i = runs.size() - 1; i >= 0; i--) {
                Run r = runs.get(i);
                due.clear();
                for (Step s : r.steps()) {
                    if (s.dueAtMs() <= now) {
                        due.add(s);
                    }
                }
                if (due.isEmpty()) {
                    continue;
                }
                vars.clear();
                vars.putAll(r.vars());
                for (Step s : due) {
                    execute(s.type(), s.params(), vars);
                }
                advanced = true;
                List<Step> rest =
                        r.steps().stream().filter(s -> s.dueAtMs() > now).toList();
                if (rest.isEmpty()) {
                    runs.remove(i);
                    LOGGER.info("[CCNR-RP] 序列完成: {}", r.seqId());
                } else {
                    runs.set(i, new Run(r.seqId(), r.vars(), rest, r.startAt()));
                }
            }
        }
        if (advanced) {
            // 有步骤落地 = "下一步"变了：推送对局状态，让面板倒计时跟上（每步一次，频率极低）
            pushMatchState();
        }
    }

    /** 序列时序变化 → 推送对局状态（面板的"下一步"倒计时随步骤推进更新）。 */
    private static void pushMatchState() {
        if (CCNRRPMod.eventManager != null) {
            CCNRRPMod.eventManager.broadcastMatchState();
        }
    }

    private void execute(String type, JsonObject p, Map<String, String> vars) {
        try {
            switch (type) {
                case "WAIT" -> {}
                case "TRIGGER" -> {} // 安全兜底：锚点不执行（正常已在 runSteps 过滤）
                case "COMMAND" -> {
                    String cmd = inject(str(p, "command", ""), vars);
                    if (!cmd.isBlank()) {
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
                    }
                }
                case "WAVE" -> {
                    String wave = inject(str(p, "wave", ""), vars);
                    if (!wave.isBlank() && CCNRRPMod.spawnFramework != null) {
                        CCNRRPMod.spawnFramework.triggerWave(wave);
                    }
                }
                case "FORCE_PICK" -> forcePick(p, vars);
                case "RULECHANGE", "RULE_CHANGE" -> ruleChange(p, vars);
                case "SWITCHPHASE", "SWITCH_PHASE" -> switchPhaseStep(p, vars);
                case "EVACUATE" -> evacuate(p, vars);
                default -> LOGGER.warn("[CCNR-RP] 未知序列步骤类型: {}", type);
            }
        } catch (Throwable t) {
            LOGGER.error("[CCNR-RP] 序列步骤执行失败: {}", type, t);
        }
    }

    /**
     * 规则变更动作（P15 §4.5）：{@code buff/nerf} 为即时药水效果；其余（限职业/阵营、目标区、招募方式）
     * 登记到幕作用域规则层（由 EventManager 在幕切换时自动解除）。
     */
    private void ruleChange(JsonObject p, Map<String, String> vars) {
        if (CCNRRPMod.rules == null) {
            return;
        }
        String rule = str(p, "rule", "");
        if (RuleService.RULE_BUFF.equals(rule) || RuleService.RULE_NERF.equals(rule)) {
            applyBuff(p, vars);
            return;
        }
        // 作用域：动作自带 phase 优先，否则当前幕（RuleService.apply 内部回退 currentPhase）
        CCNRRPMod.rules.apply(p, "");
        LOGGER.info("[CCNR-RP] ruleChange {}（{} 幕）", rule, CCNRRPMod.rules.currentPhase());
    }

    /**
     * 疏散结算步骤（EVACUATE）：对当前在场（ALIVE）用户追加疏散分并立即结算，随后转观察者。
     *
     * <p>参数：{@code xp}（疏散分，缺省 0 = 只疏散不加分）、{@code title}（疏散分标题，**必填**——
     * 它同时是合并键与结算动画里显示在数值后的文案，缺了会变成一条无名条目，故缺 title 时跳过并 WARN）。
     * 走非死亡路径：不广播 character_death、不落遗体、不产生阵亡扣分（docs/06 §6）。
     */
    private void evacuate(JsonObject p, Map<String, String> vars) {
        if (CCNRRPMod.experience == null) {
            LOGGER.warn("[CCNR-RP] EVACUATE 跳过：经验服务未就绪");
            return;
        }
        String title = inject(str(p, "title", ""), vars).trim();
        if (title.isEmpty()) {
            LOGGER.warn("[CCNR-RP] EVACUATE 跳过：缺少 title（疏散分标题不能为空）");
            return;
        }
        long xp = num(p, "xp", 0);
        CCNRRPMod.experience.evacuateAlive(title, xp);
    }

    /** 强制切幕步骤（switchPhase）：空 phase → 下一幕；否则按 id 切。 */
    private void switchPhaseStep(JsonObject p, Map<String, String> vars) {
        if (CCNRRPMod.eventManager == null) {
            return;
        }
        String phase = inject(str(p, "phase", ""), vars);
        boolean ok = CCNRRPMod.eventManager.switchPhase(phase.isBlank() ? null : phase);
        if (!ok) {
            LOGGER.warn("[CCNR-RP] switchPhase 未命中（目标幕不存在或同幕）: {}", phase);
        }
    }

    /** 即时增益/限制：对 target（@a 全员/玩家名/UUID）应用药水效果秒数。 */
    private void applyBuff(JsonObject p, Map<String, String> vars) {
        String target = inject(str(p, "target", "@a"), vars);
        String effect = str(p, "effect", "");
        long seconds = Math.max(1, num(p, "seconds", 30));
        int amplifier = (int) Math.max(0, num(p, "amplifier", 0));
        if (effect.isBlank()) {
            return;
        }
        ResourceLocation id = ResourceLocation.tryParse(effect);
        var mobEffect = id == null ? null : BuiltInRegistries.MOB_EFFECT.get(id);
        if (mobEffect == null) {
            LOGGER.warn("[CCNR-RP] ruleChange 效果未知: {}", effect);
            return;
        }
        int ticks = (int) Math.min(Math.max(1, seconds * 20L), 600_000L);
        for (ServerPlayer pl : server.getPlayerList().getPlayers()) {
            if (!"@a".equals(target)
                    && !pl.getName().getString().equalsIgnoreCase(target)
                    && !pl.getUUID().toString().equals(target)) {
                continue;
            }
            pl.addEffect(new MobEffectInstance(mobEffect, ticks, amplifier));
        }
    }

    /**
     * 强制征召（改邀请制）：候选 = 所有在线且未在场的用户（开启「以任何支援身份复活」的无角色用户也能收到）；
     * 每个被选中用户创建一个独立征召兵角色（UID 名 + 征召编制职业），发出邀请；接受后部署，拒绝/超时删除（用完即删）。
     */
    private void forcePick(JsonObject p, Map<String, String> vars) {
        int count = (int) Math.max(0, Math.min(64, num(p, "count", 1)));
        String factionId = inject(str(p, "faction", ""), vars);
        String professionsCsv = inject(str(p, "professions", ""), vars);
        if (CCNRRPMod.users == null || CCNRRPMod.factions == null) {
            return;
        }
        if (factionId.isBlank() && !CCNRRPMod.factions.graph().factions().isEmpty()) {
            factionId =
                    CCNRRPMod.factions.graph().factions().keySet().iterator().next();
        }
        // 候选池：在线玩家（未以征召在场）；存活/观察/支援复活均可收到（存活接受后走正式转职部署，不处死）
        List<ServerPlayer> pool = new ArrayList<>();
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            String uuid = online.getUUID().toString();
            if (hasConscript(uuid)) {
                continue; // 已有征召登记（含挂起中）不再重复邀请
            }
            boolean alive = CCNRRPMod.users.status(uuid) == com.ccnrcom.rp.status.CharacterStatus.ALIVE;
            boolean anySupport = CCNRRPMod.users.anySupportRevive(uuid);
            boolean hasObserving = CCNRRPMod.users.status(uuid) == com.ccnrcom.rp.status.CharacterStatus.OBSERVING;
            if (!anySupport && !hasObserving && !alive) {
                continue;
            }
            pool.add(online);
        }
        java.util.Collections.shuffle(pool, random);
        int picked = Math.min(count, pool.size());
        if (picked <= 0 || CCNRRPMod.spawnFramework == null) {
            LOGGER.info("[CCNR-RP] 强制征召无候选（{}）", vars.getOrDefault("seq", "?"));
            return;
        }
        // 征召编制职业池
        List<String> profPool = new ArrayList<>();
        if (!professionsCsv.isBlank()) {
            for (String pid : professionsCsv.split(",")) {
                String t = pid.trim();
                var def = CCNRRPMod.factions.findProfession(t).orElse(null);
                if (def != null
                        && com.ccnrcom.rp.faction.FactionProfessions.factionId(def)
                                .equals(factionId)) {
                    profPool.add(t);
                }
            }
        }
        if (profPool.isEmpty()) {
            for (String pid : CCNRRPMod.factions.professionIds()) {
                var def = CCNRRPMod.factions.findProfession(pid).orElse(null);
                if (def != null
                        && com.ccnrcom.rp.faction.FactionProfessions.factionId(def)
                                .equals(factionId)) {
                    profPool.add(pid);
                }
            }
        }
        String prefix = factionId.isBlank()
                ? "AGENT"
                : factionId.substring(0, Math.min(3, factionId.length())).toUpperCase(java.util.Locale.ROOT);
        // 为每个被选用户登记临时征召兵（UID 名 + 编制职业；不进角色库），发出邀请
        List<com.ccnrcom.rp.spawn.SpawnModels.Candidate> cands = new ArrayList<>();
        List<ServerPlayer> online = new ArrayList<>();
        for (int i = 0; i < picked; i++) {
            ServerPlayer user = pool.get(i);
            if (profPool.isEmpty()) {
                LOGGER.warn("[CCNR-RP] 征召编制职业池为空，跳过 {}", user.getName().getString());
                continue;
            }
            String uuid = user.getUUID().toString();
            String profId = profPool.get(random.nextInt(profPool.size()));
            String uidName = prefix + "-" + hex(4) + "-" + hex(2);
            String csId = "conscript-" + java.util.UUID.randomUUID();
            registerConscript(new Conscript(csId, uuid, uidName, profId, factionId, true, 0L)); // pending：邀请挂起中
            boolean alive = CCNRRPMod.users.status(uuid) == com.ccnrcom.rp.status.CharacterStatus.ALIVE;
            cands.add(new com.ccnrcom.rp.spawn.SpawnModels.Candidate(
                    csId, uuid, uidName, alive ? "alive" : "observing", 0, 0, profId, factionId, false, true, false));
            online.add(user);
            LOGGER.info(
                    "[CCNR-RP] 征召兵登记: {}（{} → {}，临时编制不进角色库）",
                    uidName,
                    user.getName().getString(),
                    profId);
        }
        if (cands.isEmpty()) {
            return;
        }
        String label = vars.getOrDefault("seq", "force");
        CCNRRPMod.spawnFramework.recruitConscript(label, cands.size(), cands, online, 60);
        LOGGER.info("[CCNR-RP] 强制征召邀请发出: {}（需要 {} 人）", label, cands.size());
    }

    private String hex(int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append("0123456789ABCDEF".charAt(random.nextInt(16)));
        }
        return sb.toString();
    }

    private static String seqEscape(String s) {
        return s.replaceAll("[^a-zA-Z0-9_-]", "");
    }

    /** {{key}} 变量注入（缺失保留原样）。 */
    public static String inject(String text, Map<String, String> vars) {
        String out = text;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static long num(JsonObject o, String key, long def) {
        try {
            return o.has(key) ? o.get(key).getAsLong() : def;
        } catch (Exception e) {
            return def;
        }
    }
}
