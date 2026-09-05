/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import net.minecraft.client.gui.GuiGraphics;

/**
 * CCNR-RP 界面主题 v4——"CCNR:NET 机密终端"（黑白灰军用终端，对齐 Web 管理面板）：
 * 近黑冷暗底 / 白·灰主色(文字+边框，电子屏质感) / 高饱和正红(仅警戒+危险) / 灰阶徽章(机构等级)。
 * 布局：严格三栏网格（左：机构徽章导航 / 中：角色档案列表 / 右：详细资料+3D预览+战术装备）。
 * 与前端 ccnr-rp-gui (App.css) 设计语言一致：黑底、白/灰等宽文字、细灰边、白=强调、红=危险。
 */
public final class RpTheme {
    // ---- 几何 ----
    public static final float RADIUS_MEDIUM = 8;
    public static final float RADIUS_LARGE = 14;
    public static final int PAD = 8;

    // ---- 灰色半透明底（近黑中性灰；仅保留警戒/危险/等级徽章用色）----
    public static final int OVERLAY = 0xB3141414;
    public static final int BG_DEEP = 0xCC0A0A0A;
    public static final int PANEL_BG = 0x99141414;
    public static final int PANEL_BG_ALT = 0xB3222222;
    public static final int PANEL_BG_EVEN = 0x9B161616;
    public static final int PANEL_BORDER = 0xFF333333;
    public static final int PANEL_BORDER_BRIGHT = 0xFF666666;
    public static final int SCANLINE = 0x12FFFFFF;
    public static final int SHEEN = 0x12FFFFFF;

    // ---- 文字（层级：标题>副标题>正文>弱化；中性灰阶）----
    public static final int TEXT_PRIMARY = 0xFFFFFFFF;
    public static final int TEXT_SECONDARY = 0xFFA0A0A0;
    public static final int TEXT_DIM = 0xFF666666;

    // ---- 主色调：白色（电子屏发光 / 全息投影）----
    public static final int CYAN = 0xFFFFFFFF;
    public static final int CYAN_DIM = 0xFF888888;
    public static final int BLUE_BADGE = 0xFF888888;

    // 兼容旧引用（按钮边界色）
    public static final int ACCENT = CYAN_DIM;
    public static final int ACCENT_HOVER = CYAN;
    public static final int ACCENT_TEXT = 0xFF111111;

    // ---- 正面 / 正常：中性亮灰（单调终端无非主色）----
    public static final int GREEN = 0xFFB4B4B4;

    public static final int RED = 0xFFFF3B30;
    public static final int RED_DIM = 0xFF8C2320;
    public static final int RED_BG = 0xFF6F1613;
    public static final int RED_LINE = 0xFFFF4A40;
    public static final int DANGER = RED_DIM;
    public static final int DANGER_HOVER = RED;

    // ---- 徽章灰阶（机构最高等级最亮）----
    public static final int GOLD = 0xFFB4B4B4;

    // ---- 状态（AM/M/OB）----
    public static final int STATUS_ALIVE = 0xFFFFFFFF;
    public static final int STATUS_DEAD = 0xFFFF3B30;
    public static final int STATUS_OBSERVING = 0xFF7E8A8F;
    public static final int COOLDOWN = 0xFFB4B4B4;

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

    /** 机构等级 → 徽章环色：1 白(最高机密) / 2 亮灰(标准机构) / 3 暗灰(普通编制)。 */
    public static int tierColor(int tier) {
        return switch (Math.max(1, Math.min(3, tier))) {
            case 1 -> TEXT_PRIMARY;
            case 2 -> TEXT_SECONDARY;
            default -> TEXT_DIM;
        };
    }

    /** CRT 扫描线（每 3px 一微亮线，强化屏幕质感）。 */
    public static void scanlines(GuiGraphics g, int x1, int y1, int x2, int y2) {
        for (int y = y1; y < y2; y += 3) {
            g.fill(x1, y, x2, y + 1, SCANLINE);
        }
    }

    /** 终端边框四角 L 型角标。 */
    public static void cornerBrackets(GuiGraphics g, int x1, int y1, int x2, int y2, int len, int color) {
        g.fill(x1, y1, x1 + len, y1 + 1, color);
        g.fill(x1, y1, x1 + 1, y1 + len, color);
        g.fill(x2 - len, y1, x2, y1 + 1, color);
        g.fill(x2 - 1, y1, x2, y1 + len, color);
        g.fill(x1, y2 - 1, x1 + len, y2, color);
        g.fill(x1, y2 - len, x1 + 1, y2, color);
        g.fill(x2 - len, y2 - 1, x2, y2, color);
        g.fill(x2 - 1, y2 - len, x2, y2, color);
    }

    /** 终端主面板（素版）：近黑底 + 灰描边，无扫描线/角标/光泽等装饰。 */
    public static void terminalPanel(GuiGraphics g, int x1, int y1, int x2, int y2, float radius) {
        RpRoundRect.fill(g, x1, y1, x2, y2, radius, OVERLAY);
        RpRoundRect.outlined(g, x1, y1, x2, y2, radius, PANEL_BORDER, OVERLAY);
    }

    /** 卡片：深色圆角 + 灰描边。 */
    public static void card(GuiGraphics g, int x1, int y1, int x2, int y2, float radius, int bg) {
        RpRoundRect.outlined(g, x1, y1, x2, y2, radius, PANEL_BORDER, bg);
    }

    /** 选中高亮（素版）：白底反白字 + 左侧白色高亮线（对齐前端 selected 反白风格）。 */
    public static void selectedBar(GuiGraphics g, int x1, int y1, int x2, int y2, float radius) {
        RpRoundRect.fill(g, x1, y1, x2, y2, radius, 0xFFFFFFFF);
        RpRoundRect.fill(g, x1, y1, x1 + 3, y2, radius, 0xFFFFFFFF);
        g.fill(x1, y2 - 1, x2, y2, 0xFFB4B4B4);
    }

    /** 终端标签风格："[ 机构分类 ]"。 */
    public static String tag(String s) {
        return "[ " + s + " ]";
    }
}
