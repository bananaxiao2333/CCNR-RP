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

    /** 阴间循环检测（每 5 秒）：DEAD 且冷却结束 → 自动回观察者，等待被重新部署，绝不自动复活。 */
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
        long now = System.currentTimeMillis();
        for (CharacterData c : CCNRRPMod.characters.store().all()) {
            if (c.status() != CharacterStatus.DEAD) {
                continue;
            }
            if (c.cooldownUntil() > now) {
                continue; // 冷却中继续“阴间”
            }
            CharacterData obs = c.withStatus(CharacterStatus.OBSERVING).withCooldown(0);
            CCNRRPMod.characters.store().update(obs);
            CCNRRPMod.characters.store().save();
            CharacterService.updateAndBroadcast(obs, null);
            var owner = server.getPlayerList().getPlayer(java.util.UUID.fromString(c.playerUuid()));
            if (owner != null) {
                CCNRRPMod.characters.sendList(owner); // 在线拥有者：同步回观察者 + 面板锁
            }
            LOGGER.info("[CCNR-RP] 阴间循环：{} 已回观察者池（冷却结束）", c.name());
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
                data.withStatus(CharacterStatus.DEAD).withCooldown(System.currentTimeMillis() + cooldownMs);
        CharacterService.updateAndBroadcast(dead, playerOrNull);
        LOGGER.info("[CCNR-RP] 退役 [retire] {} 角色 {}（非存活，仅状态）", data.playerUuid(), data.name());
    }

    /** 掉线判死：状态 + 冷却 + 遗体 + 同步（幂等：仅 ALIVE 生效）。 */
    private static void kill(ServerPlayer player, CharacterData data, String reason) {
        markDead(data, player, true, reason);
    }

    private static void markDead(CharacterData data, ServerPlayer player, boolean spawnCorpse, String reason) {
        if (data.status() != CharacterStatus.ALIVE) {
            return; // 幂等
        }
        long cooldownMs = CCNRRPConfig.DEATH_COOLDOWN_MINUTES.get() * 60000L;
        CharacterData dead =
                data.withStatus(CharacterStatus.DEAD).withCooldown(System.currentTimeMillis() + cooldownMs);
        CharacterService.updateAndBroadcast(dead, player);
        if (player != null) {
            CCNRRPMod.characters.sendList(player); // 立即刷新面板锁（死亡即解锁）
        }
        if (spawnCorpse && player != null && CorpseBridge.available()) {
            CorpseBridge.spawnCorpse(player);
        }
        LOGGER.info(
                "[CCNR-RP] 判死 [{}] {} 角色 {}（原因={}，冷却 {} 分钟）",
                reason,
                data.playerUuid(),
                data.name(),
                CCNRRPConfig.DEATH_COOLDOWN_MINUTES.get());
    }
}
