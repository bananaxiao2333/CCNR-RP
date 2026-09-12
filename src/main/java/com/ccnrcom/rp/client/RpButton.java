/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * 终端按钮（扁平化 + 发光边框，对齐前端按钮风格）：
 * primary=白底黑字（主操作，反白）/ secondary=暗底灰边白字（次级）/ danger=红边框红字（危险操作）。
 * 选中状态（白底黑字）由列表绘制承担，按钮本身不承载选中态。
 */
public final class RpButton extends Button {
    private final int fill;
    private final int fillHover;
    private final int border;
    private final int borderHover;
    private final int fg;
    private final int fgHover;

    private RpButton(
            int x,
            int y,
            int w,
            int h,
            Component label,
            OnPress onPress,
            int fill,
            int fillHover,
            int border,
            int borderHover,
            int fg,
            int fgHover) {
        super(x, y, w, h, label, onPress, DEFAULT_NARRATION);
        this.fill = fill;
        this.fillHover = fillHover;
        this.border = border;
        this.borderHover = borderHover;
        this.fg = fg;
        this.fgHover = fgHover;
    }

    /** 主操作：白底黑字（反白，对齐前端主按钮）。 */
    public static RpButton primary(int x, int y, int w, int h, Component label, OnPress onPress) {
        return new RpButton(
                x,
                y,
                w,
                h,
                label,
                onPress,
                RpTheme.ACCENT_FILL,
                RpTheme.ACCENT_FILL_HOVER,
                RpTheme.CYAN,
                RpTheme.ACCENT_HOVER,
                RpTheme.ACCENT_TEXT,
                RpTheme.BLACK);
    }

    /** 次级：暗底灰边白字。 */
    public static RpButton secondary(int x, int y, int w, int h, Component label, OnPress onPress) {
        return new RpButton(
                x,
                y,
                w,
                h,
                label,
                onPress,
                RpTheme.SURFACE_CONTROL,
                RpTheme.SURFACE_CONTROL_HOVER,
                RpTheme.PANEL_BORDER_BRIGHT,
                RpTheme.TEXT_SECONDARY,
                RpTheme.TEXT_PRIMARY,
                RpTheme.TEXT_PRIMARY);
    }

    /** 危险：红边红字，悬停红底白字。 */
    public static RpButton danger(int x, int y, int w, int h, Component label, OnPress onPress) {
        return new RpButton(
                x,
                y,
                w,
                h,
                label,
                onPress,
                RpTheme.RED_BG_SOFT,
                RpTheme.RED_BG_HOVER,
                RpTheme.RED_DIM,
                RpTheme.RED,
                RpTheme.RED_LINE,
                RpTheme.TEXT_PRIMARY);
    }

    /** 弹窗内静态绘制按钮（非 widget，交给父屏手动命中）。 */
    public static void draw(GuiGraphics g, int x1, int y1, int x2, int y2, String label, int border, boolean primary) {
        draw(g, x1, y1, x2, y2, label, border, primary, false);
    }

    /**
     * 弹窗内静态绘制按钮（带悬停）：悬停时描边提到亮灰，与 widget 版 {@link #secondary} 表现一致。
     * primary=白底反白（文字必须用 ACCENT_TEXT，否则白底白字不可读）。
     */
    public static void draw(
            GuiGraphics g, int x1, int y1, int x2, int y2, String label, int border, boolean primary, boolean hovered) {
        int edge = hovered && !primary ? RpTheme.PANEL_BORDER_BRIGHT : border;
        RpRoundRect.outlined(g, x1, y1, x2, y2, 6f, edge, primary ? RpTheme.ACCENT_FILL : RpTheme.SURFACE_CONTROL);
        Font f2 = Minecraft.getInstance().font;
        int tw = f2.width(label);
        g.drawString(
                f2,
                label,
                x1 + (x2 - x1 - tw) / 2,
                y1 + (y2 - y1 - 8) / 2,
                primary ? RpTheme.ACCENT_TEXT : RpTheme.TEXT_PRIMARY,
                true);
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int f = isHovered() ? fillHover : fill;
        int b = isHovered() ? borderHover : border;
        RpRoundRect.outlined(g, getX(), getY(), getX() + getWidth(), getY() + getHeight(), 6f, b, f);
        // 发光内晕
        if (isHovered()) {
            RpRoundRect.fill(
                    g,
                    getX() + 2,
                    getY() + 2,
                    getX() + getWidth() - 2,
                    getY() + getHeight() - 2,
                    5f,
                    RpTheme.alphaBlend(b, 0x26));
        }
        int color = active ? (isHovered() ? fgHover : fg) : RpTheme.TEXT_DISABLED;
        Font f2 = Minecraft.getInstance().font;
        // 标签按像素裁剪到按钮内：按钮行会随按钮数变窄（例如职业页签从 4 个变 5 个），
        // 不裁剪就会把文字画到按钮外面去（与文档/提示行同一类"出框"问题）。
        // 共享裁剪入口：RpTheme.clip（docs/14 §2.1）。
        String label = RpTheme.clip(f2, getMessage().getString(), Math.max(8, getWidth() - 6));
        int tw = f2.width(label);
        g.drawString(f2, label, getX() + (getWidth() - tw) / 2, getY() + (getHeight() - 8) / 2, color, true);
    }
}
