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
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
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
    /** 待生成遗体（延迟 2 tick）：玩家 + 死亡显示名（尸体显示玩家名）。 */
    private record PendingCorpse(ServerPlayer player, String charName) {}

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
            retire(uuid, player, "offline", RetireFlag.of(RetireFlag.SPAWN_CORPSE, RetireFlag.OFFLINE));
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
                CorpseBridge.spawnCorpse(e.getValue().player(), e.getValue().charName());
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
        // 征召执勤时长并入用户档案（统一结算：征召结束与普通死亡同一函数、同一逐行绿/红）
        if (wasConscript && conscriptDuty > 0 && CCNRRPMod.users != null) {
            long duty = CCNRRPMod.users.dutySeconds(uuid) + conscriptDuty;
            CCNRRPMod.users.setXpDuty(uuid, CCNRRPMod.users.userXp(uuid), duty);
            CCNRRPMod.users.save();
        }
        // 统一：清除客户端征召身份（幂等）。
        // 注意：不在死亡瞬间切旁观者——否则打断原版掉落与 Corpse 尸体生成；重生时由 onPlayerRespawn 切旁观并传回尸体旁。
        RpChannels.sendTo(player, new RpPackets.ConscriptStateS2C(""));
        if (CCNRRPMod.users != null && CCNRRPMod.users.isAlive(uuid)) {
            // 正式用户死亡：统一退场（状态迁移 + 冷却 + 结算 + 逐行；遗体由 Corpse 模组自动生成，不 SPAWN_CORPSE）
            retire(uuid, player, "death", RetireFlag.of());
        } else if (wasConscript && CCNRRPMod.experience != null) {
            // 征召兵死亡（用户本身非在场，状态 OBSERVING）：同一结算函数 + 同一逐行绿/红（执勤时长已并入档案）
            CCNRRPMod.experience.settleUserDown(uuid, player, "ccnr_rp.xp.settle.death");
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
                retire(uuid, null, "offline-late", RetireFlag.of(RetireFlag.OFFLINE));
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
     * 观察者拾取拦截：观察者（无在场身份的用户）禁止拾取任何物品实体。
     * 原版旁观者模式本身不能拾取，但部分模组（如 better_looting）会绕过游戏模式判断直接给物品，
     * 这里在 Forge 拾取事件层统一取消，覆盖所有拾取来源。
     */
    @SubscribeEvent
    public void onEntityItemPickup(EntityItemPickupEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && CCNRRPMod.users != null) {
            String uuid = player.getUUID().toString();
            boolean deployed =
                    CCNRRPMod.users.isAlive(uuid) || com.ccnrcom.rp.sequence.SequenceEngine.isConscripted(uuid);
            if (!deployed) {
                event.setCanceled(true); // 观察者不可拾取（含 better_looting 等模组的拾取路径）
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

    // ---------- 退场（统一 retire 核心） ----------

    /** 管理命令：处决玩家（在线则生成遗体）。 */
    public static void killCommand(ServerPlayer player) {
        String uuid = player.getUUID().toString();
        if (CCNRRPMod.users == null || !CCNRRPMod.users.isAlive(uuid)) {
            RpChannels.sendTo(player, new RpPackets.ErrorS2C("ccnr_rp.status.error.no_alive"));
            return;
        }
        retire(uuid, player, "command", RetireFlag.of(RetireFlag.SPAWN_CORPSE));
        RpChannels.sendTo(
                player,
                new RpPackets.ErrorS2C(
                        "ccnr_rp.status.killed.command", player.getName().getString()));
    }

    /** 强制保留（转生/弃演）：统一退场入口（reason=retire，不结算、在线落遗体）。档案保留不删除。 */
    public static void retire(String playerUuid, ServerPlayer playerOrNull) {
        retire(
                playerUuid,
                playerOrNull,
                "retire",
                playerOrNull != null
                        ? RetireFlag.of(RetireFlag.SPAWN_CORPSE, RetireFlag.SKIP_SETTLE)
                        : RetireFlag.of(RetireFlag.SKIP_SETTLE));
    }

    /**
     * 唯一退场核心：普通死亡 / 判死 / 下班(退役) / 征召结束全部汇入此入口。
     * 行为差异一律由 {@link RetireFlag} 控制（SPAWN_CORPSE / OFFLINE / SKIP_SETTLE），
     * 共用同一状态迁移 + 同一结算函数（settleUserDown）+ 同一逐行绿/红。
     */
    public static void retire(String playerUuid, ServerPlayer playerOrNull, String reason, Set<RetireFlag> flags) {
        if (CCNRRPMod.users == null) {
            return;
        }
        boolean spawnCorpse = flags != null && flags.contains(RetireFlag.SPAWN_CORPSE);
        boolean offline = flags != null && flags.contains(RetireFlag.OFFLINE);
        boolean skipSettle = flags != null && flags.contains(RetireFlag.SKIP_SETTLE);
        CharacterStatus st = CCNRRPMod.users.status(playerUuid);
        boolean alive = st == CharacterStatus.ALIVE;
        boolean observingRetire = st == CharacterStatus.OBSERVING && "retire".equals(reason);
        // 状态迁移（幂等）：仅 ALIVE 判死生效；观察者退役只加冷却；其余（DEAD 残留由轮询兜底）跳过
        if (!alive && !observingRetire) {
            return;
        }
        String charName = playerOrNull != null ? playerOrNull.getName().getString() : "";
        long cooldownMs = CCNRRPConfig.DEATH_COOLDOWN_MINUTES.get() * 60000L;
        if (alive) {
            // 死亡/判死 → 观察模式（OBSERVING）+ 复活冷却标记：不可自部署，等冷却结束或复活波/FORCE_PICK 强制复活
            CCNRRPMod.users.setCooldown(playerUuid, System.currentTimeMillis() + cooldownMs);
            CCNRRPMod.users.setStatus(playerUuid, CharacterStatus.OBSERVING);
            CCNRRPMod.users.save();
            // 自然死亡（reason=death）：Corpse 模组会自动生成遗体（默认用玩家 UUID/姓名）。
            // 在 LivingDeathEvent 阶段捕获身份，供 CorpseBridge 的 PlayerDeathEvent 钩子改写遗体身份
            // （角色显示名 + 皮肤哈希派生 UUID）——尸体显示玩家名；皮肤已移除，哈希恒为 ""。
            if (playerOrNull != null && "death".equals(reason) && CorpseBridge.available()) {
                CorpseBridge.captureDeathChar(playerOrNull.getUUID(), charName);
            }
        } else {
            // 观察者退役（下班）：只加复活冷却，保持观察模式
            CCNRRPMod.users.setCooldown(playerUuid, System.currentTimeMillis() + cooldownMs);
            CCNRRPMod.users.save();
        }
        if (playerOrNull != null && alive) {
            if (CCNRRPMod.characters != null) {
                CCNRRPMod.characters.sendList(playerOrNull); // 立即刷新用户档案列表（观察模式；K 面板可打开）
            }
            // 不在死亡瞬间切旁观者（防打断掉落与 Corpse 尸体生成）；重生时由 onPlayerRespawn 切旁观并传回尸体旁
            if ("death".equals(reason)) {
                deathSpots.put(
                        playerOrNull.getUUID(),
                        new DeathSpot(
                                (ServerLevel) playerOrNull.level(),
                                playerOrNull.blockPosition(),
                                playerOrNull.getYRot(),
                                playerOrNull.getXRot()));
            }
        }
        if (spawnCorpse && playerOrNull != null && CorpseBridge.available()) {
            pendingCorpsePlayers.put(
                    playerOrNull.getUUID(),
                    new PendingCorpse(playerOrNull, charName)); // 延迟 2 tick 生成（实体移除时序安全），尸体保留在原地
        }
        // 服务器侧结算 + 玩家侧显示经验明细（离线挂起，上线补发）——同一结算函数 + 同一逐行绿/红
        if (CCNRRPMod.experience != null && !skipSettle) {
            boolean isOffline = offline || reason.startsWith("offline");
            String resultKey = isOffline ? "ccnr_rp.xp.settle.offline" : "ccnr_rp.xp.settle.death";
            CCNRRPMod.experience.settleUserDown(playerUuid, isOffline ? null : playerOrNull, resultKey);
        }
        LOGGER.info(
                "[CCNR-RP] 退场 [{}] {} 用户 {}（原因={} → 观察模式，复活冷却 {} 分钟）",
                reason,
                playerUuid,
                charName,
                CCNRRPConfig.DEATH_COOLDOWN_MINUTES.get());
    }
}
