/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.status;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.character.CharacterData;
import com.ccnrcom.rp.character.CharacterService;
import com.ccnrcom.rp.config.CCNRRPConfig;
import com.ccnrcom.rp.corpse.CorpseBridge;
import com.ccnrcom.rp.network.RpChannels;
import com.ccnrcom.rp.network.RpPackets;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 角色状态管理（P4）：状态迁移落地 + 掉线判死（事件主路径 + 轮询兜底，均幂等）+ 冷却。
 * 判死链路：ALIVE → DEAD；设置 cooldownUntil；在线时生成 Corpse 遗体并同步客户端；离线时仅状态+日志。
 */
public final class StatusManager {
    private static final Logger LOGGER = LogManager.getLogger();

    private final MinecraftServer server;
    private long pollCounter = 0;

    public StatusManager(MinecraftServer server) {
        this.server = server;
    }

    // ---------- 事件 ----------

    /** 掉线/踢出主路径：立即判死并生成遗体（实体与背包此时仍可用）。 */
    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (CCNRRPMod.managerSettings != null && !CCNRRPMod.managerSettings.forceRetain()) {
            return; // 未开启“强制保留角色”：离服不自动判死
        }
        Optional<CharacterData> alive =
                CCNRRPMod.characters.store().findAlive(player.getUUID().toString());
        if (alive.isPresent()) {
            kill(player, alive.get(), "offline");
        }
    }

    /** 在线死亡：状态与冷却（遗体由 Corpse 原生处理）。 */
    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Optional<CharacterData> alive =
                CCNRRPMod.characters.store().findAlive(player.getUUID().toString());
        if (alive.isPresent()) {
            markDead(alive.get(), player, false, "death");
        }
    }

    /** 轮询兜底：alive 但玩家离线超过宽限 → 补判死（幂等）。 */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || CCNRRPMod.characters == null) {
            return;
        }
        int interval = Math.max(1, CCNRRPConfig.OFFLINE_POLL_SECONDS.get());
        long every = interval * 20L;
        if (++pollCounter < every) {
            return;
        }
        pollCounter = 0;
        long graceMs = CCNRRPConfig.OFFLINE_GRACE_SECONDS.get() * 1000L;
        if (CCNRRPMod.managerSettings != null && !CCNRRPMod.managerSettings.forceRetain()) {
            return; // 未开启“强制保留角色”：轮询兜底判死关闭
        }
        for (CharacterData c : CCNRRPMod.characters.store().all()) {
            if (c.status() != CharacterStatus.ALIVE) {
                continue;
            }
            var player = server.getPlayerList().getPlayer(java.util.UUID.fromString(c.playerUuid()));
            if (player != null) {
                continue;
            }
            // 离线时长未知（无实体），宽限判断退化为：离线即判死（事件主路径已覆盖，此处防漏）
            if (c.cooldownUntil() == 0) {
                markDead(c, null, false, "offline-late");
            }
        }
    }

    /** 阴间循环检测（每 5 秒）：兼容旧存档 DEAD → 观察者池（保留冷却标记：死神阶段标记是 cooldownUntil>0）。 */
    private long deadCheckCounter = 0;

    @SubscribeEvent
    public void onServerTickCheckDead(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || CCNRRPMod.characters == null) {
            return;
        }
        if (++deadCheckCounter < 100) { // 100 ticks = 5s
            return;
        }
        deadCheckCounter = 0;
        for (CharacterData c : CCNRRPMod.characters.store().all()) {
            if (c.status() != CharacterStatus.DEAD) {
                continue;
            }
            // 死亡/判死现在直接写 OBSERVING + 复活冷却；仅旧存档残留 DEAD 在此归一化
            CCNRRPMod.characters.store().update(c.withStatus(CharacterStatus.OBSERVING));
            CCNRRPMod.characters.store().save();
            var owner = server.getPlayerList().getPlayer(java.util.UUID.fromString(c.playerUuid()));
            if (owner != null) {
                CCNRRPMod.characters.sendList(owner);
            }
            LOGGER.info("[CCNR-RP] 阴间循环：{} 旧 DEAD 已归一化为观察者池（冷却标记保留）", c.name());
        }
    }

    // ---------- 判死 ----------

    /** 管理命令：处决玩家（在线则生成遗体）。 */
    public static void killCommand(ServerPlayer player) {
        Optional<CharacterData> alive =
                CCNRRPMod.characters.store().findAlive(player.getUUID().toString());
        if (alive.isEmpty()) {
            RpChannels.sendTo(player, new RpPackets.ErrorS2C("ccnr_rp.status.error.no_alive"));
            return;
        }
        kill(player, alive.get(), "command");
        RpChannels.sendTo(
                player,
                new RpPackets.ErrorS2C(
                        "ccnr_rp.status.killed.command", alive.get().name()));
    }

    /** 强制保留（转生/弃演）：角色直接判定死亡；在线存活时在最后位置落下遗体。档案保留不删除。 */
    public static void retire(CharacterData data, ServerPlayer playerOrNull) {
        if (data.status() == CharacterStatus.ALIVE) {
            markDead(data, playerOrNull, playerOrNull != null, "retire");
            return;
        }
        if (data.status() == CharacterStatus.DEAD) {
            return;
        }
        long cooldownMs = CCNRRPConfig.DEATH_COOLDOWN_MINUTES.get() * 60000L;
        CharacterData dead =
                data.withStatus(CharacterStatus.OBSERVING).withCooldown(System.currentTimeMillis() + cooldownMs);
        CharacterService.updateAndBroadcast(dead, playerOrNull);
        LOGGER.info("[CCNR-RP] 退役 [retire] {} 角色 {}（观察者池 + 复活冷却）", data.playerUuid(), data.name());
    }

    /** 掉线判死：状态 + 冷却 + 遗体 + 同步（幂等：仅 ALIVE 生效）。 */
    private static void kill(ServerPlayer player, CharacterData data, String reason) {
        markDead(data, player, true, reason);
    }

    private static void markDead(CharacterData data, ServerPlayer player, boolean spawnCorpse, String reason) {
        if (data.status() != CharacterStatus.ALIVE && data.status() != CharacterStatus.DEAD) {
            return; // 幂等；DEAD 兼容旧存档（再判死 = 回观察者）
        }
        long cooldownMs = CCNRRPConfig.DEATH_COOLDOWN_MINUTES.get() * 60000L;
        // 死亡/判死 → 立刻回观察者池（状态显示“观察中”）；cooldownUntil = 复活冷却锁
        CharacterData dead =
                data.withStatus(CharacterStatus.OBSERVING).withCooldown(System.currentTimeMillis() + cooldownMs);
        CharacterService.updateAndBroadcast(dead, player);
        if (player != null) {
            CCNRRPMod.characters.sendList(player); // 立即刷新面板锁（死亡即解锁）
        }
        if (spawnCorpse && player != null && CorpseBridge.available()) {
            CorpseBridge.spawnCorpse(player);
        }
        // 服务器侧结算 + 玩家侧显示经验明细（离线挂起，上线补发）
        if (CCNRRPMod.experience != null && !"retire".equals(reason)) {
            boolean offline = reason.startsWith("offline");
            String resultKey = offline ? "ccnr_rp.xp.settle.offline" : "ccnr_rp.xp.settle.death";
            CCNRRPMod.experience.settleForDown(dead, offline ? null : player, resultKey);
        }
        LOGGER.info(
                "[CCNR-RP] 判死 [{}] {} 角色 {}（原因={} → 观察者池，复活冷却 {} 分钟）",
                reason,
                data.playerUuid(),
                data.name(),
                CCNRRPConfig.DEATH_COOLDOWN_MINUTES.get());
    }
}
