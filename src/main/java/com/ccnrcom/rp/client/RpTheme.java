/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import net.minecraft.client.gui.GuiGraphics;

/**
 * CCNR-RP 界面主题 v3——"CCNR:NET 机密终端"（量子科学设施）：
 * 冷暗科技金属底 / 青色主色(文字+边框，电子屏发光) / 高饱和正红(选中+警戒) / 金·蓝徽章(机构等级)。
 * 布局：严格三栏网格（左：机构分类徽章导航 / 中：角色档案列表 / 右：详细资料+3D预览+战术装备实物）。
 */
public final class RpTheme {
    // ---- 几何 ----
    public static final float RADIUS_MEDIUM = 8;
    public static final float RADIUS_LARGE = 14;
    public static final int PAD = 8;

    // ---- 灰色半透明底（中性灰，保持克制；功能色仅保留于选中/警示/等级徽章）----
    public static final int OVERLAY = 0xB32E2E2E;
    public static final int BG_DEEP = 0xCC262626;
    public static final int PANEL_BG = 0x99383838;
    public static final int PANEL_BG_ALT = 0xB3454545;
    public static final int PANEL_BG_EVEN = 0x9B313131;
    public static final int PANEL_BORDER = 0xFF5F5F5F;
    public static final int PANEL_BORDER_BRIGHT = 0xFF858585;
    public static final int SCANLINE = 0x20000000;
    public static final int SHEEN = 0x12FFFFFF;

    // ---- 文字（层级：标题>副标题>正文>弱化；中性灰阶）----
    public static final int TEXT_PRIMARY = 0xFFF0F0F0;
    public static final int TEXT_SECONDARY = 0xFFB4B4B4;
    public static final int TEXT_DIM = 0xFF808080;

    // ---- 主色调：冷青色（电子屏发光 / 全息投影）----
    public static final int CYAN = 0xFF45D8F2;
    public static final int CYAN_DIM = 0xFF2B8195;
    public static final int BLUE_BADGE = 0xFF3D7BFF;

    // 兼容旧引用（按钮边界色）
    public static final int ACCENT = CYAN_DIM;
    public static final int ACCENT_HOVER = CYAN;
    public static final int ACCENT_TEXT = 0xFF161616;

    // ---- 警示/选中：高饱和正红 + 红底白字 ----
    /** 正面（加分/正常）绿色。 */
    public static final int GREEN = 0xFF35E07A;

    public static final int RED = 0xFFFF3B30;
    public static final int RED_DIM = 0xFF8C2320;
    public static final int RED_BG = 0xFF6F1613;
    public static final int RED_LINE = 0xFFFF4A40;
    public static final int DANGER = RED_DIM;
    public static final int DANGER_HOVER = RED;

    // ---- 徽章金（机构最高等级）----
    public static final int GOLD = 0xFFFFC84C;

    // ---- 状态（AM/M/OB）----
    public static final int STATUS_ALIVE = 0xFF35E07A;
    public static final int STATUS_DEAD = 0xFFFF3B30;
    public static final int STATUS_OBSERVING = 0xFF7E8A8F;
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

    /** 机构等级 → 徽章环色：1 金(最高机密) / 2 蓝(标准机构) / 3 青(普通编制)。 */
    public static int tierColor(int tier) {
        return switch (Math.max(1, Math.min(3, tier))) {
            case 1 -> GOLD;
            case 2 -> BLUE_BADGE;
            default -> CYAN;
        };
    }

    /** CRT 扫描线（每 3px 一暗线，强化屏幕质感）。 */
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

    /** 终端主面板（素版）：金属底 + 青色描边，无扫描线/角标/光泽等装饰。 */
    public static void terminalPanel(GuiGraphics g, int x1, int y1, int x2, int y2, float radius) {
        RpRoundRect.fill(g, x1, y1, x2, y2, radius, OVERLAY);
        RpRoundRect.outlined(g, x1, y1, x2, y2, radius, PANEL_BORDER, OVERLAY);
    }

    /** 卡片：深色圆角 + 暗青描边。 */
    public static void card(GuiGraphics g, int x1, int y1, int x2, int y2, float radius, int bg) {
        RpRoundRect.outlined(g, x1, y1, x2, y2, radius, PANEL_BORDER, bg);
    }

    /** 选中高亮（素版）：红底 + 左侧红色高亮线，无四角红标。 */
    public static void selectedBar(GuiGraphics g, int x1, int y1, int x2, int y2, float radius) {
        RpRoundRect.fill(g, x1, y1, x2, y2, radius, 0xE68C1613);
        RpRoundRect.fill(g, x1, y1, x1 + 3, y2, radius, RED);
        g.fill(x1 + 4, y2 - 2, x2 - 4, y2 - 1, RED_LINE);
    }

    /** 终端标签风格："[ 机构分类 ]"。 */
    public static String tag(String s) {
        return "[ " + s + " ]";
    }
}
