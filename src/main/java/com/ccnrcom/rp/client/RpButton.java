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
 * 终端按钮（扁平化 + 发光边框）：
 * primary=青色边框发光（主操作）/ secondary=暗青灰边（次级）/ danger=红边框（危险操作）。
 * 选中状态（红底白字+红色高亮线）由列表绘制承担，按钮本身不承载选中态。
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

    /** 主操作：青边青字（发光边框）。 */
    public static RpButton primary(int x, int y, int w, int h, Component label, OnPress onPress) {
        return new RpButton(
                x,
                y,
                w,
                h,
                label,
                onPress,
                0xB0111A1E,
                0xD41A2730,
                RpTheme.CYAN_DIM,
                RpTheme.CYAN,
                RpTheme.CYAN,
                0xFFFFFFFF);
    }

    /** 次级：暗青灰边白字。 */
    public static RpButton secondary(int x, int y, int w, int h, Component label, OnPress onPress) {
        return new RpButton(
                x,
                y,
                w,
                h,
                label,
                onPress,
                0xA8101418,
                0xD01A242B,
                RpTheme.PANEL_BORDER_BRIGHT,
                RpTheme.TEXT_SECONDARY,
                RpTheme.TEXT_PRIMARY,
                0xFFFFFFFF);
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
                0xAE150C0C,
                0xD0641613,
                RpTheme.RED_DIM,
                RpTheme.RED,
                RpTheme.RED_LINE,
                0xFFFFFFFF);
    }

    /** 弹窗内静态绘制按钮（非 widget，交给父屏手动命中）。 */
    public static void draw(GuiGraphics g, int x1, int y1, int x2, int y2, String label, int border, boolean primary) {
        RpRoundRect.outlined(g, x1, y1, x2, y2, 6f, border, primary ? 0xB0111A1E : 0xA8101418);
        Font f2 = Minecraft.getInstance().font;
        int tw = f2.width(label);
        g.drawString(
                f2,
                label,
                x1 + (x2 - x1 - tw) / 2,
                y1 + (y2 - y1 - 8) / 2,
                primary ? RpTheme.CYAN : RpTheme.TEXT_PRIMARY,
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
        int color = active ? (isHovered() ? fgHover : fg) : 0xFF3A4A52;
        Font f2 = Minecraft.getInstance().font;
        int tw = f2.width(getMessage());
        g.drawString(f2, getMessage(), getX() + (getWidth() - tw) / 2, getY() + (getHeight() - 8) / 2, color, true);
    }
}
