/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.experience;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.config.CCNRRPConfig;
import com.ccnrcom.rp.experience.SettlementCalcs.EvacuationMethod;
import com.ccnrcom.rp.experience.SettlementCalcs.Result;
import com.ccnrcom.rp.experience.SettlementCalcs.Weights;
import com.ccnrcom.rp.network.RpChannels;
import com.ccnrcom.rp.network.RpPackets;
import com.ccnrcom.rp.status.CharacterStatus;
import com.ccnrcom.rp.user.UserService;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 经验服务（P5，v2 按用户结算）：值班时间累加 / 任务登记 / 疏散裁定 / 命令结算（增量幂等）。
 * 结算 = (dutyNow - ledger.duty) * rate + (taskNow - ledger.taskXp) + evacXp（每人每局一次）。
 * 经验随用户走：结算直接写入 UserService.UserProfile。
 */
public final class ExperienceService {
    private static final Logger LOGGER = LogManager.getLogger();

    /** 一条结算明细（key + 带符号值 + 展示参数）。 */
    public record SettleLine(String key, long value, String[] args) {}

    /** 一次用户结算的汇总结果。 */
    public record SettleSummary(long totalXp, int newLevel, List<SettleLine> lines) {}

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

    /** 值班时间累加（ALIVE 用户每秒 +1，写入 UserProfile）。 */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || CCNRRPMod.users == null) {
            return;
        }
        if (++tickCounter < 20) {
            return;
        }
        tickCounter = 0;
        UserService users = CCNRRPMod.users;
        for (String uuid : users.uuids()) {
            if (users.status(uuid) == CharacterStatus.ALIVE) {
                users.setXpDuty(uuid, users.userXp(uuid), users.dutySeconds(uuid) + 1);
            }
        }
    }

    /** 事件系统调用：任务行为登记（xp 可为默认值，事件定义可覆盖）。keyed by playerUuid。 */
    public static void markTask(String playerUuid, String taskId, Integer xpOverride) {
        if (CCNRRPMod.users == null || playerUuid == null) {
            return;
        }
        UserService users = CCNRRPMod.users;
        int xp = xpOverride != null ? xpOverride : CCNRRPConfig.XP_TASK_DEFAULT.get();
        java.util.Map<String, Integer> tasks = new java.util.HashMap<>(users.tasks(playerUuid));
        tasks.merge(taskId, xp, Integer::sum);
        users.setXpDuty(playerUuid, users.userXp(playerUuid), users.dutySeconds(playerUuid));
        users.tasks(playerUuid).clear();
        users.tasks(playerUuid).putAll(tasks);
        users.save();
    }

    /** 疏散裁定（管理覆盖或系统自动）。keyed by playerUuid。 */
    public static void setEvacuation(String playerUuid, EvacuationMethod method) {
        if (CCNRRPMod.users == null || playerUuid == null) {
            return;
        }
        CCNRRPMod.users.setEvacuation(playerUuid, method.name());
        CCNRRPMod.users.save();
    }

    /** 在线玩家（按 uuid）；不存在返回 null。 */
    private ServerPlayer online(String playerUuid) {
        try {
            return server.getPlayerList().getPlayer(java.util.UUID.fromString(playerUuid));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 结算某用户（增量幂等）；返回汇总（null = 用户服务未就绪）。 */
    public SettleSummary settleUser(String playerUuid, boolean notifyOwner) {
        if (CCNRRPMod.users == null) {
            return null;
        }
        UserService users = CCNRRPMod.users;
        Weights w = new Weights(
                CCNRRPConfig.XP_DUTY_PER_SECOND.get(),
                CCNRRPConfig.XP_TASK_DEFAULT.get(),
                CCNRRPConfig.XP_EVAC_SAFE.get(),
                CCNRRPConfig.XP_EVAC_DIED.get(),
                CCNRRPConfig.XP_EVAC_OBSERVING.get(),
                CCNRRPConfig.XP_EVAC_STAY_BEHIND.get());
        long dutyNow = users.dutySeconds(playerUuid);
        long dutyDelta = dutyNow - ledger.dutySeconds(playerUuid);
        int taskSum = users.tasks(playerUuid).values().stream()
                .mapToInt(Integer::intValue)
                .sum();
        int taskDelta = taskSum - ledger.taskXp(playerUuid);
        EvacuationMethod evac = EvacuationMethod.NONE;
        try {
            evac = EvacuationMethod.valueOf(users.evacuation(playerUuid));
        } catch (Exception ignored) {
            evac = EvacuationMethod.NONE;
        }
        boolean evacFirst = evac != EvacuationMethod.NONE && !ledger.evacSettled(playerUuid);
        if (!evacFirst) {
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
        long before = users.userXp(playerUuid);
        long newXp = users.addXp(playerUuid, r.totalXp());
        int newLevel = curve.level(newXp);

        // 结算顺序（统一流程）：ledger 基线（防重复）→ 用户经验落盘 → 用户档案复位
        ledger.setDutySeconds(playerUuid, dutyNow);
        ledger.setTaskXp(playerUuid, taskSum);
        if (evacFirst) {
            ledger.setEvacSettled(playerUuid, true);
        }
        ledger.save();

        // 用户档案：同步 xp/duty，疏散裁定重置（每局重新发放）
        users.setXpDuty(playerUuid, newXp, dutyNow);
        if (evacFirst) {
            users.setEvacuation(playerUuid, "none");
        }
        users.save();

        ServerPlayer owner = online(playerUuid);
        if (owner != null) {
            RpChannels.sendTo(owner, new RpPackets.UserXpS2C(newXp, newLevel));
        }

        List<SettleLine> lines = new ArrayList<>();
        lines.add(new SettleLine("duty", r.dutyXp(), new String[] {String.valueOf(dutyDelta)}));
        if (r.taskXp() != 0) {
            lines.add(new SettleLine("task", r.taskXp(), new String[] {String.valueOf(taskDelta)}));
        }
        if (r.evacXp() != 0) {
            lines.add(new SettleLine("evac", r.evacXp(), new String[] {evac.name()}));
        }
        if (owner != null) {
            submitLines(owner, new SettleSummary(newXp, newLevel, lines));
        }
        if (curve.level(before) < newLevel) {
            com.ccnrcom.rp.animation.AnimationHooks.levelUp(owner, newLevel);
        }
        return new SettleSummary(newXp, newLevel, lines);
    }

    /** 把结算明细下发为 XpLinesS2C（每行 "sign|value|key|args"）。 */
    private void submitLines(ServerPlayer owner, SettleSummary s) {
        String[] lines = s.lines().stream().map(ExperienceService::lineString).toArray(String[]::new);
        RpChannels.sendTo(owner, new RpPackets.XpLinesS2C(lines));
    }

    private static String lineString(SettleLine l) {
        String sign = l.value() >= 0 ? "+" : "-";
        StringBuilder sb = new StringBuilder();
        sb.append(sign)
                .append('|')
                .append(Math.abs(l.value()))
                .append('|')
                .append("ccnr_rp.xp.line.")
                .append(l.key());
        if (l.args() != null && l.args().length > 0) {
            sb.append('|').append(String.join(",", l.args()));
        } else {
            sb.append('|');
        }
        return sb.toString();
    }

    /** 死亡/断联/退役落定：结算该用户并把明细发给拥有者（离线则挂起，上线补发）。 */
    public void settleUserDown(String playerUuid, ServerPlayer ownerOrNull, String resultKey) {
        SettleSummary s = settleUser(playerUuid, true);
        if (s == null) {
            return;
        }
        // 结算完成后强制刷成观察者身份（防任何路径残留非观察状态）
        if (CCNRRPMod.users != null && CCNRRPMod.users.status(playerUuid) != CharacterStatus.OBSERVING) {
            CCNRRPMod.users.setStatus(playerUuid, CharacterStatus.OBSERVING);
            CCNRRPMod.users.save();
        }
        long dutyXp = 0;
        long taskXp = 0;
        long evacXp = 0;
        for (SettleLine l : s.lines()) {
            switch (l.key()) {
                case "duty" -> dutyXp = l.value();
                case "task" -> taskXp = l.value();
                case "evac" -> evacXp = l.value();
                default -> {}
            }
        }
        String[] args = {
            String.valueOf(s.totalXp()),
            String.valueOf(dutyXp),
            String.valueOf(taskXp),
            String.valueOf(evacXp),
            String.valueOf(s.totalXp()),
            String.valueOf(s.newLevel())
        };
        // 在线：已由 settleUser 下发逐行 XpLinesS2C（右下角逐行红/绿显示），不再发旧汇总聊天气泡。
        // 离线：挂起，登录补发（flushPending）。
        if (ownerOrNull == null) {
            pending.store(playerUuid, resultKey, args);
        }
    }

    /** 上线补发离线期间的结算通知（发送即删除）。 */
    public void flushPending(ServerPlayer player) {
        for (Object[] n : pending.drain(player.getUUID().toString())) {
            RpChannels.sendTo(player, new RpPackets.ErrorS2C((String) n[0], (String[]) n[1]));
        }
    }

    /** 结算全部（all）或单个玩家；单条坏档异常隔离，不阻塞整批。 */
    public List<SettleSummary> settleAll(String playerUuidOrNull) {
        List<SettleSummary> results = new ArrayList<>();
        if (CCNRRPMod.users == null) {
            return results;
        }
        for (String uuid : CCNRRPMod.users.uuids()) {
            if (playerUuidOrNull == null || uuid.equals(playerUuidOrNull)) {
                try {
                    results.add(settleUser(uuid, false));
                } catch (Exception ex) {
                    LOGGER.error("[CCNR-RP] 结算失败（跳过该用户，不影响整批）: {}", uuid, ex);
                }
            }
        }
        return results;
    }
}
