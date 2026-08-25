/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.status;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.config.CCNRRPConfig;
import com.ccnrcom.rp.corpse.CorpseBridge;
import com.ccnrcom.rp.network.RpChannels;
import com.ccnrcom.rp.network.RpPackets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 用户状态管理（P4/P9）：状态迁移落地 + 掉线判死（事件主路径 + 轮询兜底，均幂等）+ 冷却。
 * 判死链路：ALIVE → OBSERVING（观察模式）；在线时生成 Corpse 遗体，死亡强制旁观者模式并记录死亡地点，
 * 复活后传送回尸体旁旁观（不改变出生点）；离线时仅状态 + 日志。
 * 唯一身份为 {@link CCNRRPMod#users} 的用户档案（一个玩家 UUID），不再有角色库/角色实体。
 */
public final class StatusManager {
    private static final Logger LOGGER = LogManager.getLogger();

    /** 死亡地点（用于复活后传回尸体旁旁观）。 */
    private record DeathSpot(ServerLevel level, BlockPos pos, float yaw, float pitch) {}

    private final MinecraftServer server;
    private static final Map<UUID, DeathSpot> deathSpots = new HashMap<>();
    /** 待生成遗体（延迟 2 tick）：玩家 + 死亡显示名 + 皮肤哈希（皮肤已移除，恒为 ""——尸体显示玩家名）。 */
    private record PendingCorpse(ServerPlayer player, String charName, String skinHash) {}

    private static final Map<UUID, PendingCorpse> pendingCorpsePlayers = new HashMap<>();

    private long corpseDelayTick = 0;
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
        String uuid = player.getUUID().toString();
        deathSpots.remove(player.getUUID()); // 死亡地点记录随下线清除（复活处理只针对在线死亡）
        // 征召兵随下线消失（临时内容）
        com.ccnrcom.rp.sequence.SequenceEngine.removeConscriptFor(uuid);
        if (CCNRRPMod.users == null
                || (CCNRRPMod.managerSettings != null && !CCNRRPMod.managerSettings.forceRetain())) {
            return; // 未开启“强制保留角色”：离服不自动判死
        }
        if (CCNRRPMod.users.isAlive(uuid)) {
            kill(uuid, player, "offline");
        }
    }

    /** 服务停止/世界切换时清空死亡地点与待生成遗体记录（静态态不应跨世界残留）。 */
    public static void clearDeathSpots() {
        deathSpots.clear();
        pendingCorpsePlayers.clear();
    }

    /** 掉线/处决遗体延迟生成：每 2 tick 处理一批（实体移除完成后生成，尸体保留在原地）。 */
    @SubscribeEvent
    public void onServerTickCorpse(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || pendingCorpsePlayers.isEmpty()) {
            return;
        }
        if (++corpseDelayTick < 2) {
            return;
        }
        corpseDelayTick = 0;
        var it = pendingCorpsePlayers.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            it.remove();
            try {
                CorpseBridge.spawnCorpse(
                        e.getValue().player(),
                        e.getValue().charName(),
                        e.getValue().skinHash());
            } catch (Throwable t) {
                LOGGER.error("[CCNR-RP] 遗体生成失败（保留原生死亡）", t);
            }
        }
    }

    /** 复活处理：死亡记录的地点 → 复活后传送回尸体旁并保持旁观者模式（不改变出生点/床点）。 */
    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        DeathSpot spot = deathSpots.remove(player.getUUID());
        if (spot == null) {
            return;
        }
        player.teleportTo(
                spot.level(),
                spot.pos().getX() + 0.5,
                spot.pos().getY(),
                spot.pos().getZ() + 0.5,
                spot.yaw(),
                spot.pitch());
        player.setGameMode(GameType.SPECTATOR);
    }

    /**
     * 统一死亡流程（普通用户与征召兵走同一套）：
     * 1) 征召身份清理（如有，征召兵只是临时名字）；
     * 2) 记录死亡地点（重生时由 onPlayerRespawn 切旁观并传回尸体旁）；
     * 3) 用户在场（ALIVE）→ 判死 + 结算（先玩家加分后角色结算）；无在场但以征召兵在场 → 玩家值班加分结算（角色不存在则跳过角色结算）。
     */
    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        String uuid = player.getUUID().toString();
        long conscriptDuty = com.ccnrcom.rp.sequence.SequenceEngine.conscriptDutySeconds(uuid);
        boolean wasConscript = com.ccnrcom.rp.sequence.SequenceEngine.removeConscriptFor(uuid);
        if (wasConscript) {
            RpChannels.sendTo(player, new RpPackets.ErrorS2C("ccnr_rp.spawn.conscript.kia"));
            LOGGER.info("[CCNR-RP] 征召兵阵亡，临时编制结束：{}", player.getName().getString());
        }
        // 统一：清除客户端征召身份（幂等）、记录尸体视角。
        // 注意：不在死亡瞬间切旁观者——否则打断原版掉落与 Corpse 尸体生成；重生时由 onPlayerRespawn 切旁观并传回尸体旁。
        RpChannels.sendTo(player, new RpPackets.ConscriptStateS2C(""));
        deathSpots.put(
                player.getUUID(),
                new DeathSpot(
                        (ServerLevel) player.level(), player.blockPosition(), player.getYRot(), player.getXRot()));
        if (CCNRRPMod.users != null && CCNRRPMod.users.isAlive(uuid)) {
            markDead(uuid, player, false, "death");
        } else if (wasConscript && CCNRRPMod.experience != null) {
            // 无在场用户：只给玩家加分（征召兵值班时长）——用户状态非 ALIVE 则跳过角色结算
            CCNRRPMod.experience.settleConscriptDeath(uuid, player, conscriptDuty);
        }
    }

    /** 轮询兜底：ALIVE 但玩家离线超过宽限 → 补判死（幂等）。 */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || CCNRRPMod.users == null) {
            return;
        }
        int interval = Math.max(1, CCNRRPConfig.OFFLINE_POLL_SECONDS.get());
        long every = interval * 20L;
        if (++pollCounter < every) {
            return;
        }
        pollCounter = 0;
        if (CCNRRPMod.managerSettings != null && !CCNRRPMod.managerSettings.forceRetain()) {
            return; // 未开启“强制保留角色”：轮询兜底判死关闭
        }
        for (String uuid : CCNRRPMod.users.uuids()) {
            if (CCNRRPMod.users.status(uuid) != CharacterStatus.ALIVE) {
                continue;
            }
            // 用户 ALIVE 但玩家不在线 → 补判死（幂等；轮询间隔即隐式宽限）
            // 不再依赖 cooldownUntil()==0（部署复活后冷却不为 0 会漏判）
            net.minecraft.server.level.ServerPlayer player = null;
            try {
                player = server.getPlayerList().getPlayer(UUID.fromString(uuid));
            } catch (IllegalArgumentException ignored) {
                // 损坏 uuid：跳过该用户，不打断轮询
            }
            if (player == null) {
                markDead(uuid, null, false, "offline-late");
            }
        }
    }

    /** 观察类型双向强制（每 2 秒轮询）：观察者（无在场用户）→ 旁观者模式；非观察者（有在场用户）→ 生存模式。 */
    private long spectatorCheckCounter = 0;

    @SubscribeEvent
    public void onServerTickForceGameMode(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || CCNRRPMod.users == null) {
            return;
        }
        if (++spectatorCheckCounter < 40) { // 40 ticks = 2s
            return;
        }
        spectatorCheckCounter = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isDeadOrDying()) {
                continue; // 死亡界面中不切旁观：防打断掉落/尸体生成，重生时由 onPlayerRespawn 处理
            }
            // 仅「观察者轮询兜底」：无在场身份（用户/征召）的玩家保持旁观者模式。
            // 非观察者（有在场身份）不再强制切回生存——不干预玩家/管理员的游戏模式选择。
            String uuid = p.getUUID().toString();
            boolean deployed =
                    CCNRRPMod.users.isAlive(uuid) || com.ccnrcom.rp.sequence.SequenceEngine.isConscripted(uuid);
            if (!deployed && p.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) {
                p.setGameMode(GameType.SPECTATOR);
            }
        }
    }

    /**
     * 皮肤绑定纠正轮询（每 5 秒）：原用于按角色皮肤绑定做一致性校正并广播。
     * 皮肤绑定系统已随角色库移除（CharacterService 不再持有皮肤/绑定），无可纠正项；
     * 保留该空处理器以维持事件表面。
     */
    @SubscribeEvent
    public void onServerTickCheckSkinBindings(TickEvent.ServerTickEvent event) {
        // 皮肤绑定已移除：无绑定可纠正，不执行任何操作。
    }

    /** 轮询兜底（每 5 秒）：死亡主路径已直接写观察模式；此处仅归一化旧存档残留 DEAD → 观察者（保留冷却标记）。 */
    private long deadCheckCounter = 0;

    @SubscribeEvent
    public void onServerTickCheckDead(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || CCNRRPMod.users == null) {
            return;
        }
        if (++deadCheckCounter < 100) { // 100 ticks = 5s
            return;
        }
        deadCheckCounter = 0;
        for (String uuid : CCNRRPMod.users.uuids()) {
            if (CCNRRPMod.users.status(uuid) != CharacterStatus.DEAD) {
                continue;
            }
            // 死亡/判死现在直接写观察模式；仅旧存档残留 DEAD 在此归一化（保留冷却标记）
            CCNRRPMod.users.setStatus(uuid, CharacterStatus.OBSERVING);
            CCNRRPMod.users.save();
            ServerPlayer owner = null;
            try {
                owner = server.getPlayerList().getPlayer(UUID.fromString(uuid));
            } catch (IllegalArgumentException ignored) {
                // 损坏 uuid：跳过
            }
            if (owner != null && CCNRRPMod.characters != null) {
                CCNRRPMod.characters.sendList(owner); // 在线拥有者：同步回观察模式 + 面板
            }
            LOGGER.info("[CCNR-RP] 轮询兜底：{} 旧 DEAD 已归一化为观察者（冷却标记保留）", uuid);
        }
    }

    // ---------- 判死 ----------

    /** 管理命令：处决玩家（在线则生成遗体）。 */
    public static void killCommand(ServerPlayer player) {
        String uuid = player.getUUID().toString();
        if (CCNRRPMod.users == null || !CCNRRPMod.users.isAlive(uuid)) {
            RpChannels.sendTo(player, new RpPackets.ErrorS2C("ccnr_rp.status.error.no_alive"));
            return;
        }
        kill(uuid, player, "command");
        RpChannels.sendTo(
                player,
                new RpPackets.ErrorS2C(
                        "ccnr_rp.status.killed.command", player.getName().getString()));
    }

    /** 强制保留（转生/弃演）：用户直接判定死亡；在线存活时在最后位置落下遗体。档案保留不删除。 */
    public static void retire(String playerUuid, ServerPlayer playerOrNull) {
        if (CCNRRPMod.users == null) {
            return;
        }
        CharacterStatus st = CCNRRPMod.users.status(playerUuid);
        if (st == CharacterStatus.ALIVE) {
            markDead(playerUuid, playerOrNull, playerOrNull != null, "retire");
            return;
        }
        if (st == CharacterStatus.DEAD) {
            return;
        }
        long cooldownMs = CCNRRPConfig.DEATH_COOLDOWN_MINUTES.get() * 60000L;
        CCNRRPMod.users.setCooldown(playerUuid, System.currentTimeMillis() + cooldownMs);
        CCNRRPMod.users.setStatus(playerUuid, CharacterStatus.OBSERVING);
        CCNRRPMod.users.save();
        LOGGER.info("[CCNR-RP] 退役 [retire] {} 用户（观察模式 + 复活冷却）", playerUuid);
    }

    /** 掉线判死：状态 + 冷却 + 遗体 + 同步（幂等：仅 ALIVE 生效）。 */
    private static void kill(String playerUuid, ServerPlayer player, String reason) {
        markDead(playerUuid, player, true, reason);
    }

    private static void markDead(String playerUuid, ServerPlayer player, boolean spawnCorpse, String reason) {
        if (CCNRRPMod.users == null || CCNRRPMod.users.status(playerUuid) != CharacterStatus.ALIVE) {
            return; // 幂等：仅存活用户可判死（观察模式下重复触发不再处理）
        }
        String charName = player != null ? player.getName().getString() : "";
        // 自然死亡（reason=death）：Corpse 模组会自动生成遗体（默认用玩家 UUID/姓名）。
        // 在 LivingDeathEvent 阶段捕获身份，供 CorpseBridge 的 PlayerDeathEvent 钩子改写遗体身份
        // （角色显示名 + 皮肤哈希派生 UUID）——尸体显示玩家名；皮肤已移除，哈希恒为 ""。
        if (player != null && "death".equals(reason) && CorpseBridge.available()) {
            CorpseBridge.captureDeathChar(player.getUUID(), charName);
        }
        // 征召兵为临时内容（不在用户库），阵亡由 onLivingDeath 处理；此处仅处理正式用户
        long cooldownMs = CCNRRPConfig.DEATH_COOLDOWN_MINUTES.get() * 60000L;
        // 死亡/判死 → 观察模式（OBSERVING）+ 复活冷却标记：不可自部署，等冷却结束或复活波/FORCE_PICK 强制复活
        CCNRRPMod.users.setCooldown(playerUuid, System.currentTimeMillis() + cooldownMs);
        CCNRRPMod.users.setStatus(playerUuid, CharacterStatus.OBSERVING);
        CCNRRPMod.users.save();
        if (player != null) {
            if (CCNRRPMod.characters != null) {
                CCNRRPMod.characters.sendList(player); // 立即刷新用户档案列表（观察模式；K 面板可打开）
            }
            // 不在死亡瞬间切旁观者（防打断掉落与 Corpse 尸体生成）；重生时由 onPlayerRespawn 切旁观并传回尸体旁
            if ("death".equals(reason)) {
                deathSpots.put(
                        player.getUUID(),
                        new DeathSpot(
                                (ServerLevel) player.level(),
                                player.blockPosition(),
                                player.getYRot(),
                                player.getXRot()));
            }
        }
        if (spawnCorpse && player != null && CorpseBridge.available()) {
            pendingCorpsePlayers.put(
                    player.getUUID(), new PendingCorpse(player, charName, "")); // 延迟 2 tick 生成（实体移除时序安全），尸体保留在原地
        }
        // 服务器侧结算 + 玩家侧显示经验明细（离线挂起，上线补发）
        if (CCNRRPMod.experience != null && !"retire".equals(reason)) {
            boolean offline = reason.startsWith("offline");
            String resultKey = offline ? "ccnr_rp.xp.settle.offline" : "ccnr_rp.xp.settle.death";
            CCNRRPMod.experience.settleUserDown(playerUuid, offline ? null : player, resultKey);
        }
        LOGGER.info(
                "[CCNR-RP] 判死 [{}] {} 用户 {}（原因={} → 观察模式，复活冷却 {} 分钟）",
                reason,
                playerUuid,
                charName,
                CCNRRPConfig.DEATH_COOLDOWN_MINUTES.get());
    }
}
