/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.animation;

import com.ccnrcom.rp.network.RpChannels;
import com.ccnrcom.rp.network.RpPackets;
import com.ccnrcom.rp.util.JsonUtil;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 动画引擎（服务端）：加载序列 → 钩子绑定 → 目标决策 → AnimationPlayS2C 分发。
 * 默认钩子绑定（可在 animations.json 的 hooks 段覆盖）：
 * player_spawn / player_death / game_end / event_start / level_up。
 */
public final class AnimationEngine {
    private static final Logger LOGGER = LogManager.getLogger();

    private final Map<String, AnimationModels.Sequence> sequences = new LinkedHashMap<>();
    private final Map<String, String> hookBindings = new LinkedHashMap<>();

    public AnimationEngine() {
        Path file = FMLPaths.CONFIGDIR.get().resolve("ccnr_rp").resolve("animations.json");
        JsonObject root = JsonUtil.readObject(file).orElseGet(() -> {
            JsonObject d = JsonUtil.readResource("/assets/ccnr_rp/defaults/animations.json")
                    .orElseGet(JsonObject::new);
            JsonUtil.atomicWrite(file, d);
            return d;
        });
        List<String> errors = AnimationModels.parseAll(root, sequences);
        errors.forEach(e -> LOGGER.error("[CCNR-RP] animations.json: {}", e));
        if (root.has("hooks") && root.getAsJsonObject("hooks").size() > 0) {
            root.getAsJsonObject("hooks")
                    .entrySet()
                    .forEach(e -> hookBindings.put(e.getKey(), e.getValue().getAsString()));
        }
        hookBindings.putIfAbsent("player_spawn", "spawn_intro");
        hookBindings.putIfAbsent("player_death", "player_death");
        hookBindings.putIfAbsent("game_end", "game_end");
        hookBindings.putIfAbsent("event_start", "event_start_alarm");
        hookBindings.putIfAbsent("level_up", "level_up");
    }

    public Optional<AnimationModels.Sequence> sequence(String id) {
        return Optional.ofNullable(sequences.get(id));
    }

    public String boundSequence(String hook) {
        return hookBindings.getOrDefault(hook, "");
    }

    public int count() {
        return sequences.size();
    }

    /** 对目标播放序列（参数化）。服务端步骤（PARTICLE/SOUND）直接执行；其余进入客户端 S2C。 */
    public boolean play(String sequenceId, List<ServerPlayer> targets, Map<String, String> params) {
        Optional<AnimationModels.Sequence> seq = sequence(sequenceId);
        if (seq.isEmpty() || targets.isEmpty()) {
            return false;
        }
        params = params == null ? Map.of() : params;
        List<AnimationModels.Step> clientSteps = new java.util.ArrayList<>();
        for (AnimationModels.Step s : seq.get().steps()) {
            if (s.type().equals("PARTICLE")) {
                spawnParticles(firstTarget(targets), s);
            } else if (s.type().equals("SOUND")) {
                playSound(firstTarget(targets), s);
            } else if (s.type().equals("ACTIONBAR")) {
                sendActionBar(targets, s);
            } else {
                clientSteps.add(s);
            }
        }
        if (!clientSteps.isEmpty()) {
            String payload = sequenceToJson(new AnimationModels.Sequence(sequenceId, clientSteps), params);
            for (ServerPlayer p : targets) {
                RpChannels.sendTo(p, new RpPackets.AnimationPlayS2C(payload));
            }
        }
        LOGGER.info("[CCNR-RP] 动画播放 {} → {} 个目标（客户端步骤 {}）", sequenceId, targets.size(), clientSteps.size());
        return true;
    }

    private static void sendActionBar(List<ServerPlayer> targets, AnimationModels.Step s) {
        net.minecraft.network.chat.Component text = net.minecraft.network.chat.Component.literal(s.param("text", ""));
        for (ServerPlayer p : targets) {
            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket(text));
        }
    }

    private static ServerPlayer firstTarget(List<ServerPlayer> targets) {
        return targets.isEmpty() ? null : targets.get(0);
    }

    private static void spawnParticles(ServerPlayer target, AnimationModels.Step s) {
        if (target == null) {
            return;
        }
        try {
            net.minecraft.core.particles.ParticleType<?> type =
                    net.minecraft.core.registries.BuiltInRegistries.PARTICLE_TYPE.get(
                            net.minecraft.resources.ResourceLocation.tryParse(s.param("particle", "minecraft:poof")));
            if (type == null || !(target.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                return;
            }
            var particle = (net.minecraft.core.particles.ParticleOptions) type;
            level.sendParticles(
                    particle,
                    target.getX(),
                    target.getY() + 1.0,
                    target.getZ(),
                    (int) Math.min(s.paramLong("count", 30), 200),
                    (double) s.paramLong("spread", 1),
                    (double) s.paramLong("spread", 1),
                    (double) s.paramLong("spread", 1),
                    0.05);
        } catch (Exception e) {
            LOGGER.warn("[CCNR-RP] PARTICLE 执行失败: {}", e.toString());
        }
    }

    private static void playSound(ServerPlayer target, AnimationModels.Step s) {
        if (target == null) {
            return;
        }
        try {
            net.minecraft.sounds.SoundEvent event = net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.get(
                    net.minecraft.resources.ResourceLocation.tryParse(s.param("sound", "minecraft:block.anvil.land")));
            if (event == null || !(target.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                return;
            }
            level.playSound(
                    null,
                    target.getX(),
                    target.getY(),
                    target.getZ(),
                    event,
                    net.minecraft.sounds.SoundSource.AMBIENT,
                    (float) (s.paramLong("volume", 1) / 100.0),
                    (float) (s.paramLong("pitch", 100) / 100.0));
        } catch (Exception e) {
            LOGGER.warn("[CCNR-RP] SOUND 执行失败: {}", e.toString());
        }
    }

    /** 按钩子播放（无绑定或无序列则静默跳过）。 */
    public boolean playHook(String hook, List<ServerPlayer> targets, Map<String, String> params) {
        String id = boundSequence(hook);
        return !id.isBlank() && play(id, targets, params);
    }

    /** 序列 JSON 内联传给客户端（参数已在解析时注入文本键）。 */
    static String sequenceToJson(AnimationModels.Sequence seq, Map<String, String> params) {
        JsonObject o = new JsonObject();
        o.addProperty("id", seq.id());
        com.google.gson.JsonArray steps = new com.google.gson.JsonArray();
        for (AnimationModels.Step s : seq.steps()) {
            steps.add(stepToJson(s, params));
        }
        o.add("steps", steps);
        return o.toString();
    }

    private static com.google.gson.JsonObject stepToJson(AnimationModels.Step s, Map<String, String> params) {
        JsonObject o = new JsonObject();
        o.addProperty("type", s.type());
        s.params().forEach((k, v) -> o.addProperty(k, inject(v, params)));
        if (!s.children().isEmpty()) {
            com.google.gson.JsonArray c = new com.google.gson.JsonArray();
            s.children().forEach(ch -> c.add(stepToJson(ch, params)));
            o.add("steps", c);
        }
        return o;
    }

    /** ${name} 参数注入（缺失保留原样并 WARN 由客户端兜底显示）。 */
    static String inject(String text, Map<String, String> params) {
        String out = text;
        for (Map.Entry<String, String> e : params.entrySet()) {
            out = out.replace(" + e.getKey() + ", e.getValue());
        }
        return out;
    }
}
