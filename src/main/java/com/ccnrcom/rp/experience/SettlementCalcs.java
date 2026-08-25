/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.experience;

/** 经验结算计算（纯逻辑）：值班时间 + 任务行为 + 疏散方式 → 增量 XP。 */
public final class SettlementCalcs {

    /** 疏散方式（命令结算时裁定）。 */
    public enum EvacuationMethod {
        SAFE_RESCUE,
        DIED,
        OBSERVING_END,
        STAY_BEHIND,
        NONE
    }

    /** 结算权重。 */
    public record Weights(
            double dutyXpPerSecond,
            int taskDefaultXp,
            int evacSafe,
            int evacDied,
            int evacObserving,
            int evacStayBehind) {

        public static final Weights DEFAULT = new Weights(0.1, 50, 200, 50, 0, 150);

        public int evacXp(EvacuationMethod m) {
            return switch (m) {
                case SAFE_RESCUE -> evacSafe;
                case DIED -> evacDied;
                case OBSERVING_END -> evacObserving;
                case STAY_BEHIND -> evacStayBehind;
                case NONE -> 0;
            };
        }
    }

    /** 一次结算的输入与输出。 */
    public record Result(long dutyXp, int taskXp, int evacXp, long totalXp, String evacNote) {}

    private SettlementCalcs() {}

    /**
     * 结算增量。
     *
     * @param dutySecondsDelta 上次结算以来的值班秒数
     * @param taskXpDelta      上次结算以来的任务 XP 增量（任务定义可覆盖默认）
     * @param evac             本次疏散方式（尘埃落定的结局裁定）
     */
    public static Result calculate(long dutySecondsDelta, int taskXpDelta, EvacuationMethod evac, Weights w) {
        // 防御：duty/task 负增量按 0 处理（避免倒扣）；疏散可负（DIED 为惩罚，允许倒扣），总 XP 随之可为负。
        long dutyXp = Math.round(Math.max(0, dutySecondsDelta) * Math.max(0.0, w.dutyXpPerSecond()));
        int taskXp = Math.max(0, taskXpDelta);
        int evacXp = w.evacXp(evac);
        long total = dutyXp + taskXp + evacXp;
        String note = evac == EvacuationMethod.NONE ? "" : evac.name();
        return new Result(dutyXp, taskXp, evacXp, total, note);
    }
}
