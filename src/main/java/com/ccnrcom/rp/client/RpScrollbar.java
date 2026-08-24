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
            g.fill(x, y1, x + 5, y2, 0x40141C22);
            return new int[] {};
        }
        g.fill(x, y1, x + 5, y2, 0x80141C22); // 槽
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
            g.fill(x1, y, x2, y + 5, 0x40141C22);
            return new int[] {};
        }
        g.fill(x1, y, x2, y + 5, 0x80141C22);
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
}
