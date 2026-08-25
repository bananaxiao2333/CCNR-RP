/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.experience;

/** 等级曲线（纯逻辑）：xpForLevel(n) = base * n^pow；level(xp) = floor((xp/base)^(1/pow))。 */
public record LevelCurve(double base, double pow) {

    public LevelCurve {
        // 配置误配 0/负数时兜底，避免曲线静默失效（全员恒 0 级或 MAX）
        if (base <= 0) {
            base = 100.0;
        }
        if (pow <= 0) {
            pow = 2.0;
        }
    }

    public static final LevelCurve DEFAULT = new LevelCurve(100.0, 2.0);

    public long xpForLevel(long level) {
        return Math.round(base * Math.pow(Math.max(1, level), pow));
    }

    public int level(long xp) {
        if (xp <= 0 || base <= 0) {
            return 0;
        }
        double raw = Math.pow(xp / base, 1.0 / pow);
        if (raw >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.floor(raw);
    }

    /** 下一级所需总 XP（xp 足够升到 level+1 时返回 true）。 */
    public boolean canLevelUp(long xp, int currentLevel) {
        return level(xp) > currentLevel;
    }
}
