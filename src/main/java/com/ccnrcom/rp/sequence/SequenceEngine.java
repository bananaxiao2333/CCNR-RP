/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.sequence;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.character.CharacterData;
import com.ccnrcom.rp.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 序列引擎（序列编辑器）：顺序执行步骤 WAIT/WAVE/COMMAND/FORCE_PICK，支持 {{变量}} 注入。
 * 变量来自序列上下文（event/phase/wave/seq/faction/count…）。
 * FORCE_PICK：从观察者池强制抽取 ≤N 名在线玩家角色，附上指定职业 + 随机 UID 名字，可刷新生效。
 */
public final class SequenceEngine {
    private static final Logger LOGGER = LogManager.getLogger();

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

    private Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("ccnr_rp").resolve("sequences.json");
    }

    public void load() {
        JsonObject d =
                JsonUtil.readResource("/assets/ccnr_rp/defaults/sequences.json").orElseGet(JsonObject::new);
        root = JsonUtil.readObject(file()).orElse(d);
        if (root.size() == 0) {
            JsonUtil.atomicWrite(file(), d);
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
        for (JsonObject s : stepsIn) {
            String type = str(s, "type", "WAIT").toUpperCase(java.util.Locale.ROOT);
            long delay = num(s, "seconds", 0);
            steps.add(new Step(type, s, now + acc));
            // WAIT 消耗时间，其余步骤在同一时间点顺序执行
            if ("WAIT".equals(type)) {
                acc += Math.max(0, delay) * 1000L;
            }
        }
        Map<String, String> merged = new HashMap<>(vars);
        merged.put("seq", label);
        synchronized (runs) {
            runs.add(new Run(label, merged, steps, now));
        }
        LOGGER.info("[CCNR-RP] 序列启动: {} ({} 步)", label, steps.size());
        return List.of();
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
    }

    private void execute(String type, JsonObject p, Map<String, String> vars) {
        try {
            switch (type) {
                case "WAIT" -> {}
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
                default -> LOGGER.warn("[CCNR-RP] 未知序列步骤类型: {}", type);
            }
        } catch (Throwable t) {
            LOGGER.error("[CCNR-RP] 序列步骤执行失败: {}", type, t);
        }
    }

    /** 强制抽取观察者：≤count 名在线观察者角色，随机职业（可指定）+ 随机 UID 名字，可选刷新生效。 */
    private void forcePick(JsonObject p, Map<String, String> vars) {
        int count = (int) Math.max(0, Math.min(64, num(p, "count", 1)));
        String factionId = inject(str(p, "faction", ""), vars);
        String professionsCsv = inject(str(p, "professions", ""), vars);
        boolean randomName = !p.has("randomName") || p.get("randomName").getAsBoolean();
        boolean spawn = !p.has("spawn") || p.get("spawn").getAsBoolean();
        if (CCNRRPMod.characters == null || CCNRRPMod.factions == null) {
            return;
        }
        List<CharacterData> pool = new ArrayList<>();
        for (CharacterData c : CCNRRPMod.characters.store().all()) {
            if (c.status() != com.ccnrcom.rp.status.CharacterStatus.OBSERVING) {
                continue;
            }
            ServerPlayer online = server.getPlayerList().getPlayer(java.util.UUID.fromString(c.playerUuid()));
            if (online != null) {
                pool.add(c);
            }
        }
        java.util.Collections.shuffle(pool, random);
        int picked = Math.min(count, pool.size());
        List<String> profPool = new ArrayList<>();
        if (factionId.isBlank() && !CCNRRPMod.factions.graph().factions().isEmpty()) {
            factionId =
                    CCNRRPMod.factions.graph().factions().keySet().iterator().next();
        }
        if (CCNRRPMod.factions != null) {
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
        }
        String prefix = factionId.isBlank()
                ? "AGENT"
                : factionId.substring(0, Math.min(3, factionId.length())).toUpperCase(java.util.Locale.ROOT);
        for (int i = 0; i < picked; i++) {
            CharacterData c = pool.get(i);
            String profId = profPool.isEmpty() ? c.professionId() : profPool.get(random.nextInt(profPool.size()));
            String name = randomName ? prefix + "-" + hex(4) + "-" + hex(2) : c.name();
            var updated = c.withRole(name, profId);
            CCNRRPMod.characters.store().update(updated);
            CCNRRPMod.characters.store().save();
            com.ccnrcom.rp.character.CharacterService.updateAndBroadcast(updated, null);
            LOGGER.info("[CCNR-RP] 强制抽取: {} → {}（职业 {}）", c.name(), name, profId);
            if (spawn && CCNRRPMod.spawnFramework != null) {
                var wave = new com.ccnrcom.rp.spawn.SpawnModels.Wave(
                        "_force_" + seqEscape(c.id()),
                        com.ccnrcom.rp.spawn.SpawnModels.Mode.SELF_DEPLOY,
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
                        0);
                CCNRRPMod.spawnFramework.deployCharacter(updated.id(), wave, false, false);
            }
        }
        LOGGER.info("[CCNR-RP] 强制抽取完成: {}/{}", picked, count);
    }

    private static String seqEscape(String s) {
        return s.replaceAll("[^a-zA-Z0-9_-]", "");
    }

    private String hex(int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append("0123456789ABCDEF".charAt(random.nextInt(16)));
        }
        return sb.toString();
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
