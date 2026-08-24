/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

/**
 * CCNR-RP 界面主题（对齐 CCNR-Com 风格：深色面板/蓝主色/圆角）。
 * v2 UI 重做：与 CCNR-Com 的 CommsTheme 同款配色体系。
 */
public final class RpTheme {
    public static final int RADIUS_MEDIUM = 8;
    public static final int RADIUS_LARGE = 16;
    public static final int PAD = 8;

    public static final int OVERLAY = 0xEE101014;
    public static final int PANEL_BG = 0xFF1E1E22;
    public static final int PANEL_BORDER = 0xFF2E2E36;
    public static final int PANEL_BG_ALT = 0xFF2A2A32;
    public static final int TEXT_PRIMARY = 0xFFFFFFFF;
    public static final int TEXT_SECONDARY = 0xFF9A9AA5;
    public static final int ACCENT = 0xFF2F6BFF;
    public static final int ACCENT_HOVER = 0xFF4A82FF;
    public static final int ACCENT_TEXT = 0xFFFFFFFF;
    public static final int DANGER = 0xFF8B3A3A;
    public static final int DANGER_HOVER = 0xFFA04848;
    public static final int STATUS_ALIVE = 0xFF4CAF50;
    public static final int STATUS_DEAD = 0xFFE53935;
    public static final int STATUS_OBSERVING = 0xFFB0BEC5;
    public static final int COOLDOWN = 0xFFFF9E9E;

    private RpTheme() {}

    /** rgb + alpha 混合。 */
    public static int alphaBlend(int rgb, int alpha) {
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    public static int statusColor(String status) {
        return switch (status) {
            case "alive" -> STATUS_ALIVE;
            case "dead" -> STATUS_DEAD;
            default -> STATUS_OBSERVING;
        };
    }
}
