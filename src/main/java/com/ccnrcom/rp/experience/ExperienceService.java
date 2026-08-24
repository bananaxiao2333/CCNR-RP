/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.experience;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.character.CharacterData;
import com.ccnrcom.rp.character.CharacterService;
import com.ccnrcom.rp.config.CCNRRPConfig;
import com.ccnrcom.rp.experience.SettlementCalcs.EvacuationMethod;
import com.ccnrcom.rp.experience.SettlementCalcs.Result;
import com.ccnrcom.rp.experience.SettlementCalcs.Weights;
import com.ccnrcom.rp.network.RpChannels;
import com.ccnrcom.rp.network.RpPackets;
import com.ccnrcom.rp.status.CharacterStatus;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 经验服务（P5）：值班时间累加 / 任务登记 / 疏散裁定 / 命令结算（增量幂等）。
 * 结算 = (dutyNow - ledger.duty) * rate + (taskNow - ledger.taskXp) + evacXp（每人每局一次）。
 */
public final class ExperienceService {
    private static final Logger LOGGER = LogManager.getLogger();

    private final MinecraftServer server;
    private final LedgerStore ledger;
    private final PendingNoticeStore pending;
    private long tickCounter = 0;

    public ExperienceService(MinecraftServer server) {
        this.server = server;
        var worldDir = server.getWorldPath(new net.minecraft.world.level.storage.LevelResource("ccnr_rp"));
        this.ledger = new LedgerStore(worldDir);
        this.pending = new PendingNoticeStore(worldDir);
    }

    public LedgerStore ledger() {
        return ledger;
    }

    /** 值班时间累加（ALIVE 角色每秒 +1）。 */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || CCNRRPMod.characters == null) {
            return;
        }
        if (++tickCounter < 20) {
            return;
        }
        tickCounter = 0;
        for (CharacterData c : CCNRRPMod.characters.store().all()) {
            if (c.status() == CharacterStatus.ALIVE) {
                CharacterData updated = c.withXpDuty(c.xp() + 0, c.dutySeconds() + 1);
                CCNRRPMod.characters.store().update(updated);
            }
        }
    }

    /** 事件系统调用：任务行为登记（xp 可为默认值，事件定义可覆盖）。 */
    public static void markTask(String charId, String taskId, Integer xpOverride) {
        CharacterService svc = CCNRRPMod.characters;
        if (svc == null || charId == null) {
            return;
        }
        svc.store().find(charId).ifPresent(c -> {
            int xp = xpOverride != null ? xpOverride : CCNRRPConfig.XP_TASK_DEFAULT.get();
            java.util.Map<String, Integer> tasks = new java.util.HashMap<>(c.tasks());
            tasks.merge(taskId, xp, Integer::sum);
            CharacterData updated = new CharacterData(
                    c.id(),
                    c.playerUuid(),
                    c.name(),
                    c.factionId(),
                    c.professionId(),
                    c.background(),
                    c.skin(),
                    c.skinHash(),
                    c.status(),
                    c.xp(),
                    c.dutySeconds(),
                    c.cooldownUntil(),
                    tasks,
                    c.evacuation(),
                    c.createdAt());
            svc.store().update(updated);
            svc.store().save();
        });
    }

    /** 疏散裁定（管理覆盖或系统自动）。 */
    public static void setEvacuation(String charId, EvacuationMethod method) {
        CharacterService svc = CCNRRPMod.characters;
        if (svc == null) {
            return;
        }
        svc.store().find(charId).ifPresent(c -> {
            CharacterData updated = new CharacterData(
                    c.id(),
                    c.playerUuid(),
                    c.name(),
                    c.factionId(),
                    c.professionId(),
                    c.background(),
                    c.skin(),
                    c.skinHash(),
                    c.status(),
                    c.xp(),
                    c.dutySeconds(),
                    c.cooldownUntil(),
                    c.tasks(),
                    method.name(),
                    c.createdAt());
            svc.store().update(updated);
            svc.store().save();
        });
    }

    /** 结算某角色（增量幂等）；返回结算结果文本（空 = 无角色）。 */
    public List<String> settleCharacter(String charId, boolean notifyOwner) {
        CharacterService svc = CCNRRPMod.characters;
        if (svc == null) {
            return List.of();
        }
        CharacterData c = svc.store().find(charId).orElse(null);
        if (c == null) {
            return List.of();
        }
        Weights w = new Weights(
                CCNRRPConfig.XP_DUTY_PER_SECOND.get(),
                CCNRRPConfig.XP_TASK_DEFAULT.get(),
                CCNRRPConfig.XP_EVAC_SAFE.get(),
                CCNRRPConfig.XP_EVAC_DIED.get(),
                CCNRRPConfig.XP_EVAC_OBSERVING.get(),
                CCNRRPConfig.XP_EVAC_STAY_BEHIND.get());
        long dutyDelta = c.dutySeconds() - ledger.dutySeconds(charId);
        int taskSum = c.tasks().values().stream().mapToInt(Integer::intValue).sum();
        int taskDelta = taskSum - ledger.taskXp(charId);
        EvacuationMethod evac = EvacuationMethod.NONE;
        try {
            evac = EvacuationMethod.valueOf(c.evacuation());
        } catch (Exception ignored) {
            evac = EvacuationMethod.NONE;
        }
        boolean evacFirstTime = evac != EvacuationMethod.NONE && !ledger.evacSettled(charId);
        if (!evacFirstTime) {
            evac = EvacuationMethod.NONE;
        }
        if (dutyDelta < 0) {
            dutyDelta = 0;
        }
        if (taskDelta < 0) {
            taskDelta = 0;
        }
        Result r = SettlementCalcs.calculate(dutyDelta, taskDelta, evac, w);
        LevelCurve curve = new LevelCurve(CCNRRPConfig.LEVEL_BASE.get(), CCNRRPConfig.LEVEL_POW.get());
        int before = curve.level(c.xp());
        long newXp = c.xp() + r.totalXp();
        int after = curve.level(newXp);

        CharacterData updated = new CharacterData(
                c.id(),
                c.playerUuid(),
                c.name(),
                c.factionId(),
                c.professionId(),
                c.background(),
                c.skin(),
                c.skinHash(),
                c.status(),
                newXp,
                c.dutySeconds(),
                c.cooldownUntil(),
                c.tasks(),
                c.evacuation(),
                c.createdAt());
        svc.store().update(updated);
        svc.store().save();
        ledger.setDutySeconds(charId, c.dutySeconds());
        ledger.setTaskXp(charId, taskSum);
        if (evacFirstTime) {
            ledger.setEvacSettled(charId, true);
        }
        ledger.save();

        ServerPlayer owner = server.getPlayerList().getPlayer(java.util.UUID.fromString(c.playerUuid()));
        if (owner != null) {
            RpChannels.sendTo(owner, new RpPackets.XpUpdateS2C(charId, newXp, after));
        }
        if (after > before) {
            com.ccnrcom.rp.animation.AnimationHooks.levelUp(owner, after);
        }
        return List.of(
                charId,
                String.valueOf(r.totalXp()),
                String.valueOf(newXp),
                String.valueOf(after),
                String.valueOf(r.dutyXp()),
                String.valueOf(r.taskXp()),
                String.valueOf(r.evacXp()));
    }

    /** 死亡/断联/退役落定：结算该角色并把明细发给拥有者（离线则挂起，上线补发）。 */
    public void settleForDown(CharacterData c, ServerPlayer ownerOrNull, String resultKey) {
        List<String> v = settleCharacter(c.id(), true);
        if (v.isEmpty()) {
            return;
        }
        String[] args = {c.name(), v.get(1), v.get(4), v.get(5), v.get(6), v.get(2), v.get(3)};
        if (ownerOrNull != null) {
            RpChannels.sendTo(ownerOrNull, new RpPackets.ErrorS2C(resultKey, args));
        } else {
            pending.store(c.playerUuid(), resultKey, args);
        }
    }

    /** 上线补发离线期间的结算通知（发送即删除）。 */
    public void flushPending(ServerPlayer player) {
        for (Object[] n : pending.drain(player.getUUID().toString())) {
            RpChannels.sendTo(player, new RpPackets.ErrorS2C((String) n[0], (String[]) n[1]));
        }
    }

    /** 结算全部（all）或单个玩家。 */
    public List<List<String>> settleAll(String playerUuidOrNull) {
        List<List<String>> results = new ArrayList<>();
        for (CharacterData c : CCNRRPMod.characters.store().all()) {
            if (playerUuidOrNull == null || c.playerUuid().equals(playerUuidOrNull)) {
                results.add(settleCharacter(c.id(), true));
            }
        }
        return results;
    }
}
