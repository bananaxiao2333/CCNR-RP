/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 激活事件横幅：顶部居中，事件为一个 4:3 横向长方形排开（边框红色警戒）。
 * 仅在背包（InventoryScreen）打开时绘制（ScreenEvent.Render.Post）；游戏内 HUD 与其他界面不显示。
 */
public final class EventBanner {

    private static int offsetPx = 0;
    private static int lastX1, lastY1, lastX2, lastY2;
    private static int lastOverflow = 0;
    /** 上一次绘制出的滚动条几何 + 滚动域（命中/拖拽用；未溢出时 lastOverflow=0）。 */
    private static int barX1, barX2, barY;

    private static int lastTotal = 0;
    private static int lastVisible = 0;
    /** 滚动条拖拽 id（与各界面的 id 互不冲突；横幅只在背包界面之上交互）。 */
    private static final int SCROLL_ID = 41;

    private EventBanner() {}

    public static boolean active() {
        return !ClientCharacterState.activeEvents().isEmpty();
    }

    /** 鼠标是否在横幅区域内（滚轮捕获用）。 */
    public static boolean inArea(int mx, int my) {
        return mx >= lastX1 - 4 && mx <= lastX2 + 4 && my >= lastY1 - 2 && my <= lastY2 + 16;
    }

    /** 滚轮横向滚动（delta>0 向左看）。 */
    public static void scroll(double delta) {
        offsetPx = (int) Math.max(0, Math.min(lastOverflow, offsetPx + (int) (delta * -24)));
    }

    /**
     * 左键按下：命中滚动条游标/轨道则进入拖拽并消费事件（返回 true）；
     * 未溢出或未命中返回 false，让背包界面照常处理本次点击。
     */
    public static boolean mousePressed(int mx, int my) {
        if (lastOverflow <= 0 || lastTotal <= lastVisible) {
            return false;
        }
        int ns = RpScrollbar.clickH(mx, my, barX1, barX2, barY, barY + 5, lastTotal, lastVisible, offsetPx, SCROLL_ID);
        if (ns < 0) {
            return false;
        }
        offsetPx = (int) Math.max(0, Math.min(ns, lastOverflow));
        return true;
    }

    /** 拖拽中：按鼠标横向位移换算偏移（未在拖拽返回 false）。 */
    public static boolean mouseDragged(int mx) {
        int ns = RpScrollbar.dragH(mx);
        if (ns < 0) {
            return false;
        }
        offsetPx = (int) Math.max(0, Math.min(ns, lastOverflow));
        return true;
    }

    /** 左键抬起：结束滚动条拖拽；返回抬起前是否处于拖拽中（上层据此决定是否消费事件）。 */
    public static boolean mouseReleased() {
        boolean was = RpScrollbar.isDragging();
        RpScrollbar.endDrag();
        return was;
    }

    public static void render(GuiGraphics g, int w, int h) {
        if (CinematicController.active()) {
            return;
        }
        List<String> ids = ClientCharacterState.activeEvents();
        if (ids.isEmpty()) {
            return;
        }
        int bw = 96;
        int bh = 72;
        int gap = 6;
        int total = ids.size() * bw + (ids.size() - 1) * gap;
        int areaW = w - 24;
        lastOverflow = Math.max(0, total - areaW);
        offsetPx = (int) Math.max(0, Math.min(lastOverflow, offsetPx));
        int x = (w - total) / 2 - offsetPx;
        int y = 8;
        lastX1 = 8;
        lastY1 = y;
        lastX2 = w - 8;
        lastY2 = y + bh;
        var font = Minecraft.getInstance().font;
        g.enableScissor(8, y - 2, w - 8, y + bh + 4);
        for (String id : ids) {
            // 4:3 横向长方形（红色警戒边框 + 警示卡片底）
            RpRoundRect.outlined(g, x, y, x + bw, y + bh, 4f, RpTheme.RED_LINE, RpTheme.SURFACE_ALERT);
            g.fill(x, y + 3, x + 4, y + bh - 3, RpTheme.RED);
            g.drawCenteredString(font, Component.literal(id).getString(), x + bw / 2 + 2, y + 12, RpTheme.RED_LINE);
            g.drawCenteredString(
                    font,
                    Component.translatable("ccnr_rp.event.banner.active").getString(),
                    x + bw / 2 + 2,
                    y + 24,
                    RpTheme.TEXT_SECONDARY);
            // 底部警戒刻度
            g.fill(x + 8, y + bh - 4, x + bw - 8, y + bh - 3, RpTheme.RED_LINE);
            x += bw + gap;
        }
        g.disableScissor();
        if (lastOverflow > 0) {
            g.drawCenteredString(font, "‹", 10, y + bh / 2 - 4, offsetPx > 0 ? RpTheme.CYAN : RpTheme.TRANSPARENT);
            g.drawCenteredString(
                    font, "›", w - 10, y + bh / 2 - 4, offsetPx < lastOverflow ? RpTheme.CYAN : RpTheme.TRANSPARENT);
            g.drawCenteredString(
                    font,
                    Component.translatable("ccnr_rp.event.banner.scroll").getString(),
                    w / 2,
                    y + bh + 2,
                    RpTheme.TEXT_DIM);
            // 横向滚动条：滚轮之外**还可以直接拖游标**（几何记下来供命中/拖拽复用）
            barX1 = 40;
            barX2 = w - 40;
            barY = y + bh + 9;
            lastTotal = total;
            lastVisible = areaW;
            RpScrollbar.drawH(g, barX1, barX2, barY, total, areaW, offsetPx);
        } else {
            lastTotal = 0;
            lastVisible = 0;
        }
    }
}
