/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 背包界面左侧「对局状态」面板：当前**游戏模式**与**回合阶段**两张卡片 + 计时读数。
 *
 * <p><b>为什么显示名/描述/图标而不是 id</b>：顶部事件横幅过去直接画内部 id
 * （{@code qdf_support} 这种英文串），玩家读不懂、管理员也无处填中文名。本面板与横幅
 * 都改走 {@code DisplayInfo}（name/desc/icon，见 docs/15 §4.9）——
 * 显示名为空时回退 id（界面永不露空串），描述按像素断行。
 *
 * <p><b>图标画法（用户指定）</b>：图标**半遮挡在卡片左下角**——圆心落在卡片左下角顶点上，
 * 再 {@code enableScissor} 裁到卡片矩形，于是伸出卡片的那一半被裁掉，只剩卡片内的部分。
 * 因此卡片底部预留 {@link #ICON_RESERVE}px 的空白带，文字永远不会压到图标上。
 *
 * <p><b>只在背包界面画</b>（{@code ScreenEvent.Render.Post} 的 InventoryScreen 分支），
 * 与 {@link EventBanner}/{@link StatusHud} 同一挂载点；游戏内 HUD 与其他界面不显示。
 * 本面板当前**纯只读**：不注册任何鼠标/键盘处理（管理员快速操作见 mod/TODO.md §1）。
 */
public final class MatchStatusPanel {

    /** 卡片宽度上限（窄窗口按可用空间收缩）。 */
    private static final int MAX_CARD_W = 150;
    /** 可用横向空间低于此值就不画（窄窗口降级，不压背包本体）。 */
    private static final int MIN_CARD_W = 86;
    /** 卡片左下角图标半径。 */
    private static final int ICON_R = 12;
    /** 卡片底部为"半遮挡图标"预留的空白带（高度）。 */
    private static final int ICON_RESERVE = 14;

    private static final int PAD_X = 6;
    private static final int PAD_Y = 5;
    private static final int GAP = 6;
    /** 背包界面本体宽度（原版固定 176）。 */
    private static final int INVENTORY_W = 176;

    private MatchStatusPanel() {}

    private static int cardWidth(int w) {
        int leftSpace = (w - INVENTORY_W) / 2 - 16;
        return Math.min(MAX_CARD_W, leftSpace);
    }

    public static void render(GuiGraphics g, int w, int h) {
        if (CinematicController.active()) {
            return; // 入场电影期间不画（与事件横幅同一约定）
        }
        if (!ClientCharacterState.matchStateSeen()) {
            return; // 从未收到对局状态（服务器未装本 mod / 通道不可用）：整块不画，绝不显示假状态
        }
        int cardW = cardWidth(w);
        if (cardW < MIN_CARD_W) {
            return; // 窗口太窄：让位给背包本体，不硬挤
        }
        var font = Minecraft.getInstance().font;
        int x1 = 8;
        int x2 = x1 + cardW;

        // 先量高度再决定纵向起点：两张卡整体在背包界面左侧垂直居中
        int modeH = measureCard(font, modeName(), modeDesc(), cardW);
        int phaseH = measureCard(font, phaseName(), phaseDesc(), cardW);
        int timers = timerRows();
        int phaseExtra = timers * 10;
        int total = modeH + GAP + phaseH + phaseExtra;
        int y = Math.max(6, (h - total) / 2);

        y = drawCard(g, font, x1, y, x2, y + modeH, modeLabel(), modeName(), modeDesc(), modeIcon(), false);
        y += GAP;
        int phaseTop = y;
        y = drawCard(
                g, font, x1, y, x2, y + phaseH + phaseExtra, phaseLabel(), phaseName(), phaseDesc(), phaseIcon(), true);
        // 计时读数：贴在阶段卡片内底部信息区（有几种画几行，没有就不占位）
        if (timers > 0) {
            drawTimers(g, font, x1 + PAD_X, phaseTop + phaseH, x2 - PAD_X);
        }
        // 空窗期提示：`/rp end` 之后本局不再运行，明确写出来比让玩家猜"为什么没反应"好
        if (!ClientCharacterState.matchRunning()) {
            int ty = y + 3;
            g.drawString(
                    font, Component.translatable("ccnr_rp.match.idle").getString(), x1, ty, RpTheme.TEXT_DIM, false);
        }
    }

    /** 卡片标签（沿用终端 `[ 标签 ]` 语言）。 */
    private static String modeLabel() {
        return Component.translatable("ccnr_rp.match.label.mode").getString();
    }

    private static String phaseLabel() {
        return Component.translatable("ccnr_rp.match.label.phase").getString();
    }

    private static String modeName() {
        JsonObject m = ClientCharacterState.matchMode();
        String name = str(m, "name");
        if (!name.isBlank()) {
            return name;
        }
        // 未激活模式：回退本地化文案（而不是露出空串或内部 id）
        String id = str(m, "id");
        return id.isBlank() ? Component.translatable("ccnr_rp.match.mode.none").getString() : id;
    }

    private static String modeDesc() {
        JsonObject m = ClientCharacterState.matchMode();
        String desc = str(m, "desc");
        if (!desc.isBlank()) {
            return desc;
        }
        return str(m, "id").isBlank()
                ? Component.translatable("ccnr_rp.match.mode.none.desc").getString()
                : "";
    }

    private static String modeIcon() {
        return str(ClientCharacterState.matchMode(), "icon");
    }

    private static String phaseName() {
        JsonObject p = ClientCharacterState.matchPhase();
        String name = str(p, "name");
        if (!name.isBlank()) {
            return name;
        }
        String id = str(p, "id");
        return id.isBlank() ? Component.translatable("ccnr_rp.match.phase.none").getString() : id;
    }

    private static String phaseDesc() {
        JsonObject p = ClientCharacterState.matchPhase();
        String desc = str(p, "desc");
        if (!desc.isBlank()) {
            return desc;
        }
        // 阶段描述缺省：显示"第 n/m 幕"读数，比空白有用
        int idx = num(p, "index", -1);
        int total = num(p, "total", 0);
        if (idx < 0 || total <= 0) {
            return "";
        }
        return Component.translatable("ccnr_rp.match.phase.pos", idx + 1, total).getString();
    }

    private static String phaseIcon() {
        return str(ClientCharacterState.matchPhase(), "icon");
    }

    /** 有几条计时可用（阶段倒计时 / 序列下一步），0~2。 */
    private static int timerRows() {
        int n = 0;
        if (ClientCharacterState.phaseSecondsLeft() >= 0) {
            n++;
        }
        if (ClientCharacterState.seqSecondsLeft() >= 0) {
            n++;
        }
        return n;
    }

    /** 卡片高度：标签 + 显示名 + 描述行数（按像素断行）+ 内边距 + 图标预留带。 */
    private static int measureCard(net.minecraft.client.gui.Font font, String name, String desc, int cardW) {
        int textW = cardW - PAD_X * 2;
        int lines = desc.isBlank() ? 0 : RpTheme.wrapText(font, desc, textW).size();
        return PAD_Y + 10 + 11 + lines * 9 + ICON_RESERVE;
    }

    /**
     * 画一张卡片，返回卡片下缘 y。
     *
     * @param warn 警示态（阶段卡用警示底 + 红状态条：本局正在推进，比模式卡更"活"）
     */
    private static int drawCard(
            GuiGraphics g,
            net.minecraft.client.gui.Font font,
            int x1,
            int y1,
            int x2,
            int y2,
            String label,
            String name,
            String desc,
            String icon,
            boolean warn) {
        int border = warn ? RpTheme.RED_DIM : RpTheme.PANEL_BORDER_BRIGHT;
        int bg = warn ? RpTheme.SURFACE_ALERT : RpTheme.SURFACE_CARD;
        RpTheme.hudCard(g, x1, y1, x2, y2, border, bg);
        RpTheme.accentBar(g, x1, y1, x2, y2, warn ? RpTheme.RED : RpTheme.CYAN_DIM);

        int tx = x1 + PAD_X;
        int ty = y1 + PAD_Y;
        g.drawString(font, "[ " + label + " ]", tx, ty, RpTheme.TEXT_DIM, false);
        ty += 10;
        g.drawString(font, RpTheme.clip(font, name, x2 - PAD_X - tx), tx, ty, RpTheme.TEXT_PRIMARY, false);
        ty += 11;
        if (!desc.isBlank()) {
            for (String line : RpTheme.wrapText(font, desc, x2 - PAD_X - tx)) {
                if (ty + 9 > y2 - ICON_RESERVE + 2) {
                    break; // 不越过图标预留带
                }
                g.drawString(font, line, tx, ty, RpTheme.TEXT_SECONDARY, false);
                ty += 9;
            }
        }
        drawCornerIcon(g, x1, y1, x2, y2, icon, warn ? RpTheme.RED_LINE : RpTheme.TEXT_SECONDARY);
        return y2;
    }

    /**
     * 卡片左下角的**半遮挡图标**：圆心落在卡片左下角顶点，整体裁到卡片矩形内，
     * 于是伸出卡片的那一半被裁掉（只剩卡片里的四分之一）。
     */
    private static void drawCornerIcon(GuiGraphics g, int x1, int y1, int x2, int y2, String icon, int color) {
        if (icon == null || icon.isBlank()) {
            return;
        }
        g.enableScissor(x1, y1, x2, y2);
        // 略微内缩，避免被圆角卡片的描边切得过碎；圆心即左下角顶点 → 半遮挡
        RpIcons.icon(g, x1 + 1, y2 - 1, ICON_R, icon, color);
        g.disableScissor();
    }

    /** 计时读数：左标签右数字，机器读数观感（与 K 面板 readout 同一语言）。 */
    private static void drawTimers(GuiGraphics g, net.minecraft.client.gui.Font font, int x1, int y, int x2) {
        long phase = ClientCharacterState.phaseSecondsLeft();
        if (phase >= 0) {
            drawReadout(
                    g,
                    font,
                    x1,
                    y,
                    x2,
                    Component.translatable("ccnr_rp.match.timer.phase").getString(),
                    phase);
            y += 10;
        }
        long seq = ClientCharacterState.seqSecondsLeft();
        if (seq >= 0) {
            drawReadout(
                    g,
                    font,
                    x1,
                    y,
                    x2,
                    Component.translatable("ccnr_rp.match.timer.next").getString(),
                    seq);
        }
    }

    private static void drawReadout(
            GuiGraphics g, net.minecraft.client.gui.Font font, int x1, int y, int x2, String label, long seconds) {
        String value = formatClock(seconds);
        g.drawString(font, label, x1, y, RpTheme.TEXT_DIM, false);
        int vw = font.width(value);
        g.drawString(font, value, x2 - vw, y, seconds <= 10 ? RpTheme.RED_LINE : RpTheme.TEXT_BRIGHT, false);
    }

    /** 秒 → {@code m:ss} / {@code h:mm:ss}（负数按 0 处理）。 */
    public static String formatClock(long seconds) {
        long s = Math.max(0, seconds);
        long hh = s / 3600;
        long mm = (s % 3600) / 60;
        long ss = s % 60;
        if (hh > 0) {
            return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hh, mm, ss);
        }
        return String.format(java.util.Locale.ROOT, "%d:%02d", mm, ss);
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return "";
        }
        try {
            return o.get(key).getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    private static int num(JsonObject o, String key, int def) {
        try {
            return o != null && o.has(key) ? o.get(key).getAsInt() : def;
        } catch (Exception e) {
            return def;
        }
    }
}
