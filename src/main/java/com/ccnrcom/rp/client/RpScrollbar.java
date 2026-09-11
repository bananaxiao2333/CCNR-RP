/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 滚动条工具：终端风格 —— 右侧窄槽 + 圆角方块游标（游标比例=可见/总数，位置按偏移）。
 */
public final class RpScrollbar {

    private RpScrollbar() {}

    /** 绘制竖直滚动条。返回游标 [x1,y1,x2,y2]（无溢出时返回空数组）。 */
    public static int[] draw(GuiGraphics g, int x, int y1, int y2, int total, int visible, int offset) {
        int h = y2 - y1;
        if (total <= visible || total <= 0 || h <= 0) {
            g.fill(x, y1, x + 5, y2, RpTheme.SCROLL_TRACK);
            return new int[] {};
        }
        g.fill(x, y1, x + 5, y2, RpTheme.SCROLL_TRACK_ACTIVE); // 槽
        double thumbH = Math.max(18.0, h * (double) visible / total);
        double maxOff = total - visible;
        double frac = maxOff <= 0 ? 0 : Math.min(1.0, (double) offset / maxOff);
        int ty = (int) (y1 + frac * (h - thumbH));
        RpRoundRect.fill(g, x, ty, x + 5, ty + (int) thumbH, 2.5f, RpTheme.CYAN);
        g.fill(x, ty + (int) thumbH - 2, x + 5, ty + (int) thumbH, RpTheme.CYAN_DIM);
        return new int[] {x, ty, x + 5, ty + (int) thumbH};
    }

    /** 绘制水平滚动条（事件横幅等）。 */
    public static int[] drawH(GuiGraphics g, int x1, int x2, int y, int total, int visible, int offset) {
        int w = x2 - x1;
        if (total <= visible || total <= 0 || w <= 0) {
            g.fill(x1, y, x2, y + 5, RpTheme.SCROLL_TRACK);
            return new int[] {};
        }
        g.fill(x1, y, x2, y + 5, RpTheme.SCROLL_TRACK_ACTIVE);
        double thumbW = Math.max(20.0, w * (double) visible / total);
        double maxOff = total - visible;
        double frac = maxOff <= 0 ? 0 : Math.min(1.0, (double) offset / maxOff);
        int tx = (int) (x1 + frac * (w - thumbW));
        RpRoundRect.fill(g, tx, y, tx + (int) thumbW, y + 5, 2.5f, RpTheme.CYAN);
        return new int[] {tx, y, tx + (int) thumbW, y + 5};
    }

    /** 由拖拽位置换算出列表 offset（调用方再 clamp）。 */
    public static int offsetFromDrag(double mouseY, int trackY1, int trackY2, int total, int visible, int current) {
        int h = trackY2 - trackY1;
        if (h <= 0 || total <= visible) {
            return current;
        }
        double thumbH = Math.max(18.0, h * (double) visible / total);
        double maxOff = total - visible;
        double frac = (mouseY - trackY1 - thumbH / 2.0) / (h - thumbH);
        frac = Math.min(1.0, Math.max(0.0, frac));
        return (int) Math.round(frac * maxOff);
    }

    // ---------- 拖拽状态（同一时刻只拖一个滚动条） ----------

    private static boolean dragging = false;
    private static int dragId = 0;
    private static int dragTrackY1 = 0;
    private static int dragTrackY2 = 0;
    private static int dragTotal = 0;
    private static int dragVisible = 0;
    private static int dragStartScroll = 0;
    private static int dragStartMouse = 0;

    /**
     * 处理竖直滚动条点击：按住游标 → 开始拖拽；点击轨道空白 → 跳到该位置。
     * id 用于区分同一界面多条滚动条（拖拽时按 id 分发）。
     * 返回新滚动值；未命中滚动条返回 -1。
     */
    public static int clickV(
            int mx, int my, int x1, int x2, int y1, int y2, int total, int visible, int offset, int id) {
        if (mx < x1 || mx > x2 || my < y1 || my > y2 || total <= visible || total <= 0) {
            return -1;
        }
        int h = y2 - y1;
        double thumbH = Math.max(18.0, h * (double) visible / total);
        double maxOff = total - visible;
        double frac = maxOff <= 0 ? 0 : Math.min(1.0, (double) offset / maxOff);
        int ty = (int) (y1 + frac * (h - thumbH));
        if (my >= ty && my <= ty + thumbH) {
            // 命中游标：开始拖拽
            dragging = true;
            dragId = id;
            dragTrackY1 = y1;
            dragTrackY2 = y2;
            dragTotal = total;
            dragVisible = visible;
            dragStartScroll = offset;
            dragStartMouse = my;
            return offset;
        }
        // 轨道空白点击：跳转到该位置
        return offsetFromDrag(my, y1, y2, total, visible, offset);
    }

    /** 当前拖拽的滚动条 id（拖拽分发用）。 */
    public static int dragId() {
        return dragId;
    }

    /** 拖拽中：返回新滚动值；未在拖拽返回 -1。 */
    public static int dragV(int my) {
        if (!dragging) {
            return -1;
        }
        int h = dragTrackY2 - dragTrackY1;
        double thumbH = Math.max(18.0, h * (double) dragVisible / dragTotal);
        double maxOff = dragTotal - dragVisible;
        double perPx = maxOff / Math.max(1.0, h - thumbH);
        return (int) Math.round(Math.max(0.0, Math.min(maxOff, dragStartScroll + (my - dragStartMouse) * perPx)));
    }

    public static boolean isDragging() {
        return dragging;
    }

    public static void endDrag() {
        dragging = false;
    }
}
