/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * CCNR-RP 界面主题 v4.1——"CCNR:NET 机密终端"（黑白灰军用终端，对齐 Web 管理面板）：
 * 近黑冷暗底 / 白·灰主色(文字+边框，电子屏质感) / 高饱和正红(仅警戒+危险) / 灰阶徽章(机构等级)。
 * 布局：严格三栏网格（左：机构徽章导航 / 中：角色档案列表 / 右：详细资料+3D预览+战术装备）。
 * 与前端 ccnr-rp-gui (App.css) 设计语言一致：黑底、白/灰等宽文字、细灰边、白=强调、红=危险。
 *
 * <p>v4.1（2.25.0）为<b>令牌收口</b>：设计语言与调色板不变，把此前散落在各界面的裸色值
 * （浮层卡片底/控件底/遮罩/滚动条轨道/徽章底/列表行/立体预览全息层）统一登记为语义令牌，
 * 并提供 {@link #listRow}/{@link #listPanel}/{@link #hudCard}/{@link #popupPanel} 等共享绘制入口，
 * 使同类构件在整个 mod 内只有一种画法。新增界面一律走令牌，禁止再写裸色值（docs/14 §2.1）。
 */
public final class RpTheme {
    // ================= 几何 =================
    /** 内嵌列表/浮层圆角。 */
    public static final float RADIUS_MEDIUM = 8;
    /** 顶层面板圆角。 */
    public static final float RADIUS_LARGE = 14;

    // ================= 底板明度阶梯（近黑 → 亮灰，全程中性） =================
    /** 全屏底（关系图等全屏界面）/最深底。 */
    public static final int BG_DEEP = 0xCC0A0A0A;
    /** 全屏界面实心底（关系图三栏/全屏终端）。 */
    public static final int SURFACE_SCREEN = 0xEE161616;
    /** 面板半透明底（近黑）。 */
    public static final int PANEL_BG = 0x99141414;
    /** 顶层面板底（terminalPanel/terminalFrame，透明度略高于 PANEL_BG）。 */
    public static final int OVERLAY = 0xB3141414;
    /** 列表偶数行 / 列表容器底。 */
    public static final int PANEL_BG_EVEN = 0x9B161616;
    /** 卡片 / 悬停行底。 */
    public static final int PANEL_BG_ALT = 0xB3222222;
    /** 浮层 / HUD 实心卡片底（浮在世界之上时用，不透明度更高）。 */
    public static final int SURFACE_CARD = 0xEE1A1A1A;
    /** 次级 / 未激活浮层卡片底。 */
    public static final int SURFACE_CARD_DIM = 0xEE3A3A3A;
    /** 警示浮层卡片底（配 {@link #RED_LINE}/{@link #RED} 描边与状态条）。 */
    public static final int SURFACE_ALERT = 0xEE2E2E2E;
    /** 下拉 / 悬浮浮层底。 */
    public static final int SURFACE_POPUP = 0xE0323232;
    /** 控件常态底（次按钮 / 未选中块 / 只读域）。 */
    public static final int SURFACE_CONTROL = 0xA8323232;
    /** 控件悬停底。 */
    public static final int SURFACE_CONTROL_HOVER = 0xD03A3A3A;
    /** 开关/分段控件「开」态底。 */
    public static final int SURFACE_CONTROL_ON = 0xCC3F3F3F;
    /** 开关/分段控件「关」态底。 */
    public static final int SURFACE_CONTROL_OFF = 0xCC323232;
    /** 内陷槽底（进度条 / 只读域 / 输入域）。 */
    public static final int SURFACE_INSET = 0xB0383838;
    /** 实心沉底（立体预览底盘）。 */
    public static final int SURFACE_SUNKEN = 0xFF202020;
    /** 关系图文本节点底盘。 */
    public static final int SURFACE_DISC = 0xA8141414;

    // ================= 边框 =================
    /** 细灰边（常态）。 */
    public static final int PANEL_BORDER = 0xFF333333;
    /** 亮灰边（悬停 / 激活 / 浮层）。 */
    public static final int PANEL_BORDER_BRIGHT = 0xFF666666;
    /** 弱化边（空槽 / 锁定项）。 */
    public static final int BORDER_DIM = 0xFF4A4A4A;

    // ================= 遮罩 / 蒙层 =================
    /** 模态遮罩（管理面板 / K 面板弹窗通用）。 */
    public static final int SCRIM = 0xA6000000;
    /** 轻遮罩（嵌入式确认弹窗）。 */
    public static final int SCRIM_LIGHT = 0x99000000;
    /** 变暗蒙层（关系图节点降亮）。 */
    public static final int VEIL = 0x32000000;
    /** 条底阴影（血条等衬底）。 */
    public static final int SHADOW = 0x66000000;
    /** 全透明（占位/隐藏占位符，用 g.fill 时无需判空）。 */
    public static final int TRANSPARENT = 0x00000000;
    /** 纯黑（图标挖空/黑色文字）。 */
    public static final int BLACK = 0xFF000000;

    // ================= 列表行 =================
    /** 斑马纹亮行（浮层内列表的细亮条纹）。 */
    public static final int ROW_STRIPE = 0x1FFFFFFF;
    /** 分段控件选中块底。 */
    public static final int ROW_SEL = 0xAA313131;
    /** 分段控件选中块悬停底。 */
    public static final int ROW_SEL_HOVER = 0xAA3A3A3A;
    /** 分段控件未选中块底。 */
    public static final int ROW_IDLE = 0x99333333;

    // ================= 滚动条 =================
    /** 滚动条轨道（无溢出时）。 */
    public static final int SCROLL_TRACK = 0x40383838;
    /** 滚动条轨道（可滚动时）。 */
    public static final int SCROLL_TRACK_ACTIVE = 0x80383838;
    /** 页签横向滚动轨道。 */
    public static final int TAB_TRACK = 0x24FFFFFF;

    // ================= 徽章 / 槽位 =================
    /** 徽章底盘（挖空环内圈）。 */
    public static final int BADGE_DISC = 0xFF2E2E2E;
    /** 徽章图形挖空色（图标内部镂空）。 */
    public static final int BADGE_PUNCH = 0xFF161616;
    /** 战术装备槽底。 */
    public static final int SLOT_BG = 0xFF2F2F2F;
    /** 战术装备槽挖空色（带透明度，槽内图标镂空）。 */
    public static final int SLOT_PUNCH = 0xE62F2F2F;

    // ================= 立体预览全息层 =================
    /** 全息光柱顶（透明）。 */
    public static final int PREVIEW_BEAM_TOP = 0x00FFFFFF;
    /** 全息光柱底（半透白）。 */
    public static final int PREVIEW_BEAM = 0x4DFFFFFF;
    /** 全息底座发光环。 */
    public static final int PREVIEW_RING = 0x8CFFFFFF;
    /** 全息地格线。 */
    public static final int PREVIEW_GRID = 0x5AFFFFFF;

    // ================= 文字（层级：标题>亮灰>次>弱） =================
    /** 主文字（白）。 */
    public static final int TEXT_PRIMARY = 0xFFFFFFFF;
    /** 亮灰强调（正数 / 提示 / 次级标题）；与 {@link #GREEN} 同值——单色终端里「正面」即亮灰。 */
    public static final int TEXT_BRIGHT = 0xFFB4B4B4;
    /** 次文字。 */
    public static final int TEXT_SECONDARY = 0xFFA0A0A0;
    /** 弱文字。 */
    public static final int TEXT_DIM = 0xFF666666;
    /** 禁用文字。 */
    public static final int TEXT_DISABLED = 0xFF6A6A6A;

    // ================= 主色：白色（电子屏发光 / 全息投影） =================
    public static final int CYAN = 0xFFFFFFFF;
    public static final int CYAN_DIM = 0xFF888888;
    public static final int BLUE_BADGE = 0xFF888888;

    // 兼容旧引用（按钮边界色）
    public static final int ACCENT = CYAN_DIM;
    public static final int ACCENT_HOVER = CYAN;
    /** 反白（白底/亮底）之上的文字色——白底必须用此色，否则白底白字不可读。 */
    public static final int ACCENT_TEXT = 0xFF111111;
    /** 主操作实心填充（白底反白）。 */
    public static final int ACCENT_FILL = CYAN;
    /** 主操作悬停填充。 */
    public static final int ACCENT_FILL_HOVER = 0xFFDADADA;

    // ================= 正面 / 正常：中性亮灰（单调终端无非主色） =================
    public static final int GREEN = 0xFFB4B4B4;

    // ================= 危险 / 警戒（唯一保留的彩色） =================
    public static final int RED = 0xFFFF3B30;
    public static final int RED_DIM = 0xFF8C2320;
    public static final int RED_BG = 0xFF6F1613;
    public static final int RED_LINE = 0xFFFF4A40;
    public static final int DANGER = RED_DIM;
    public static final int DANGER_HOVER = RED;
    /** 危险按钮常态底。 */
    public static final int RED_BG_SOFT = 0xAE150C0C;
    /** 危险按钮悬停底。 */
    public static final int RED_BG_HOVER = 0xD0641613;
    /** 危险实心块（关闭键 / 危险操作块）。 */
    public static final int RED_BG_FILL = 0xE66F1613;

    // ================= 关系类型：友好（蓝，区别于中立白 / 敌对红） =================
    /** 数据可视化语义色——关系图/关系页签的第三色，黑白灰之外的刻意保留（docs/14 §6）。 */
    public static final int FRIENDLY = 0xFF4FA6FF;
    /** 关系：中立 = 白。 */
    public static final int NEUTRAL = TEXT_PRIMARY;
    /** 关系：敌对 = 红。 */
    public static final int HOSTILE = RED;

    // ================= 徽章灰阶（机构最高等级最亮） =================
    public static final int GOLD = 0xFFB4B4B4;

    // ================= 状态（AM/M/OB） =================
    public static final int STATUS_ALIVE = 0xFFFFFFFF;
    public static final int STATUS_DEAD = 0xFFFF3B30;
    public static final int STATUS_OBSERVING = 0xFF7E8A8F;
    public static final int COOLDOWN = 0xFFB4B4B4;

    // ================= 屏幕质感 =================
    public static final int SCANLINE = 0x05FFFFFF;
    public static final int SHEEN = 0x05FFFFFF;

    private RpTheme() {}

    /** rgb + alpha 混合。 */
    public static int alphaBlend(int rgb, int alpha) {
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    /** 状态字符串 → 状态色：alive 白 / dead 红 / 其余（含 null 与未知值）一律观察态弱灰。 */
    public static int statusColor(String status) {
        return switch (status == null ? "" : status) {
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

    /** 关系类型 → 语义色：中立白 / 敌对红 / 友好蓝（未知按中立）。 */
    public static int relationColor(String type) {
        return switch (type == null ? "" : type) {
            case "hostile" -> HOSTILE;
            case "friendly" -> FRIENDLY;
            default -> NEUTRAL;
        };
    }

    // ================= 共享绘制入口（同类构件只有一种画法） =================

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

    /**
     * 终端主容器框（完整视觉层，对齐前端 .terminal-panel / .rp-admin-panel 装饰）：
     * 素版面板（近黑底+灰描边）+ 顶部高光 rail（透明→亮灰→透明）+ 四角 L 型角标 + 低透明网格叠层。
     * 供「顶层面板」使用；内嵌弹窗/小卡片仍走 terminalPanel（无角标与网格）。
     */
    public static void terminalFrame(GuiGraphics g, int x1, int y1, int x2, int y2, float radius) {
        terminalPanel(g, x1, y1, x2, y2, radius);
        int railC = PANEL_BORDER_BRIGHT;
        int len = Math.max(6, Math.min(14, (x2 - x1) / 64));
        cornerBrackets(g, x1, y1, x2, y2, len, railC);
        // 顶部高光 rail（低透明度，避免压过内容）
        g.fill(x1 + len, y1 + 1, x2 - len, y1 + 2, alphaBlend(railC, 0x2E));
    }

    /** 卡片：深色圆角 + 灰描边。 */
    public static void card(GuiGraphics g, int x1, int y1, int x2, int y2, float radius, int bg) {
        RpRoundRect.outlined(g, x1, y1, x2, y2, radius, PANEL_BORDER, bg);
    }

    /** 内嵌分组卡片（面板/只读域）：近黑底 + 细灰边。 */
    public static void sectionCard(GuiGraphics g, int x1, int y1, int x2, int y2) {
        RpRoundRect.outlined(g, x1, y1, x2, y2, 4f, PANEL_BORDER, PANEL_BG);
    }

    /** 内嵌列表容器（滚动区）：列表底 + 细灰边。 */
    public static void listPanel(GuiGraphics g, int x1, int y1, int x2, int y2) {
        RpRoundRect.outlined(g, x1, y1, x2, y2, 4f, PANEL_BORDER, PANEL_BG_EVEN);
    }

    /**
     * 列表行底色（纯函数，供守护测试）：悬停 → 卡片灰；偶数行 → 面板底；奇数行 → 透明（透出列表容器底）。
     */
    public static int rowColor(int index, boolean hovered) {
        return hovered ? PANEL_BG_ALT : (index % 2 == 0 ? PANEL_BG : TRANSPARENT);
    }

    /**
     * 列表行底（斑马纹 + 悬停高亮）。选中行不在这里画：选中态一律用 {@link #selectedBar}
     * + {@link #ACCENT_TEXT} 反白（见 docs/14 §6 可读性约束）。index 为数据行下标（非屏幕行）。
     */
    public static void listRow(GuiGraphics g, int x1, int y1, int x2, int y2, int index, boolean hovered) {
        int bg = rowColor(index, hovered);
        if ((bg >>> 24) != 0) {
            g.fill(x1, y1, x2, y2, bg);
        }
    }

    /** 列表行底（悬停由鼠标位置内部判定，省去调用方重复的命中判断）。 */
    public static void listRow(GuiGraphics g, int x1, int y1, int x2, int y2, int index, int mx, int my) {
        listRow(g, x1, y1, x2, y2, index, mx >= x1 && mx <= x2 && my >= y1 && my <= y2);
    }

    /** 表头带 / 分区线（列表容器内）。 */
    public static void listHeaderRule(GuiGraphics g, int x1, int x2, int y) {
        g.fill(x1, y, x2, y + 1, PANEL_BORDER);
    }

    /**
     * 输入 / 下拉控件框（常态细灰边 + 控件底；激活或悬停时描边提到亮灰）。
     * 三个页签的下拉、只读输入域共用同一画法。
     */
    public static void controlBox(GuiGraphics g, int x1, int y1, int x2, int y2, boolean active) {
        RpRoundRect.outlined(g, x1, y1, x2, y2, 3f, active ? PANEL_BORDER_BRIGHT : PANEL_BORDER, SURFACE_CONTROL);
    }

    /** 浮层 / HUD 实心卡片：近黑实心底 + 可指定描边（常态 PANEL_BORDER，警示 RED_LINE/RED_DIM）。 */
    public static void hudCard(GuiGraphics g, int x1, int y1, int x2, int y2, int border) {
        hudCard(g, x1, y1, x2, y2, border, SURFACE_CARD);
    }

    /** 浮层 / HUD 卡片（可指定底：SURFACE_CARD / SURFACE_CARD_DIM / SURFACE_ALERT）。 */
    public static void hudCard(GuiGraphics g, int x1, int y1, int x2, int y2, int border, int bg) {
        RpRoundRect.outlined(g, x1, y1, x2, y2, RADIUS_MEDIUM, border, bg);
    }

    /** HUD 卡片左侧状态条（3px 强调色，贴在卡片左缘内）。 */
    public static void accentBar(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        RpRoundRect.fill(g, x1, y1, x1 + 3, y2, RADIUS_MEDIUM, color);
    }

    /** 下拉 / 悬浮浮层：实心浮层底 + 亮灰边 + 顶部 rail（与各页签下拉保持一致）。 */
    public static void popupPanel(GuiGraphics g, int x1, int y1, int x2, int y2) {
        RpRoundRect.outlined(g, x1, y1, x2, y2, 4f, PANEL_BORDER_BRIGHT, SURFACE_POPUP);
        g.fill(x1, y1, x2, y1 + 1, PANEL_BORDER_BRIGHT);
    }

    /** 下拉候选行底（浮层内）：当前项白底反白，悬停项低透明高亮。 */
    public static void popupRow(GuiGraphics g, int x1, int y1, int x2, int y2, boolean current, boolean hovered) {
        if (current) {
            RpRoundRect.fill(g, x1, y1, x2, y2, 3f, CYAN);
        } else if (hovered) {
            RpRoundRect.fill(g, x1, y1, x2, y2, 3f, alphaBlend(CYAN, 0x1F));
        }
    }

    /** 下拉候选行文字色（配合 {@link #popupRow}：白底上用反白色）。 */
    public static int popupRowText(boolean current, boolean hovered) {
        return current ? ACCENT_TEXT : TEXT_PRIMARY;
    }

    /** 输入框补全浮层（统一外形）：浮层底 + 顶部 rail，rows = 候选行数（每行 12px）。 */
    public static void suggestionPopup(GuiGraphics g, int sx, int sy, int sw, int rows) {
        popupPanel(g, sx - 1, sy - 1, sx + sw + 1, sy + rows * 12 + 1);
    }

    /**
     * 补全候选行（无阴影文字，与输入框内文字风格一致）：
     * 键盘当前项反白（白底 + {@link #ACCENT_TEXT}），鼠标悬停项低透明高亮。
     */
    public static void suggestionRow(
            GuiGraphics g, Font font, int sx, int sy, int sw, String label, boolean current, boolean hovered) {
        popupRow(g, sx, sy, sx + sw, sy + 12, current, hovered);
        g.drawString(font, label, sx + 4, sy + 2, popupRowText(current, hovered), false);
    }

    /** 模态遮罩（全屏）：管理面板 / K 面板弹窗统一走此入口。 */
    public static void modalScrim(GuiGraphics g, int w, int h) {
        g.fill(0, 0, w, h, SCRIM);
    }

    /** 轻遮罩（嵌入式确认弹窗）。 */
    public static void lightScrim(GuiGraphics g, int w, int h) {
        g.fill(0, 0, w, h, SCRIM_LIGHT);
    }

    /** 终端面板网格叠层（细灰网格，模拟军用终端底格；对齐前端 .terminal-panel::after）。 */
    public static void gridOverlay(GuiGraphics g, int x1, int y1, int x2, int y2) {
        int step = 32;
        int line = alphaBlend(PANEL_BORDER, 0x0F);
        for (int x = x1 + step; x < x2; x += step) {
            g.fill(x, y1, x + 1, y2, line);
        }
        for (int y = y1 + step; y < y2; y += step) {
            g.fill(x1, y, x2, y + 1, line);
        }
    }

    /** 选中高亮（素版）：白底反白字 + 左侧白色高亮线（对齐前端 selected 反白风格）。 */
    public static void selectedBar(GuiGraphics g, int x1, int y1, int x2, int y2, float radius) {
        RpRoundRect.fill(g, x1, y1, x2, y2, radius, CYAN);
        RpRoundRect.fill(g, x1, y1, x1 + 3, y2, radius, CYAN);
        g.fill(x1, y2 - 1, x2, y2, TEXT_BRIGHT);
    }

    /** 终端标签风格："[ 机构分类 ]"。 */
    public static String tag(String s) {
        return "[ " + s + " ]";
    }

    /** 终端分区标签风格："// 分区名"。 */
    public static String section(String s) {
        return "// " + s;
    }
}
