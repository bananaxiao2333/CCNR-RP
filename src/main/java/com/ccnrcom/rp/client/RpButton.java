/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** 主题化按钮（对齐 CCNR-Com APP 风格：圆角/主色/危险色/悬停高亮）。 */
public final class RpButton extends Button {
    private final int bg;
    private final int bgHover;
    private final int fg;

    private RpButton(int x, int y, int w, int h, Component label, OnPress onPress, int bg, int bgHover, int fg) {
        super(x, y, w, h, label, onPress, DEFAULT_NARRATION);
        this.bg = bg;
        this.bgHover = bgHover;
        this.fg = fg;
    }

    /** 主色（蓝底白字）。 */
    public static RpButton primary(int x, int y, int w, int h, Component label, OnPress onPress) {
        return new RpButton(x, y, w, h, label, onPress, RpTheme.ACCENT, RpTheme.ACCENT_HOVER, RpTheme.ACCENT_TEXT);
    }

    /** 次级（深灰底白字）。 */
    public static RpButton secondary(int x, int y, int w, int h, Component label, OnPress onPress) {
        return new RpButton(
                x, y, w, h, label, onPress, RpTheme.PANEL_BG_ALT, RpTheme.PANEL_BORDER, RpTheme.TEXT_PRIMARY);
    }

    /** 危险（暗红底淡红字）。 */
    public static RpButton danger(int x, int y, int w, int h, Component label, OnPress onPress) {
        return new RpButton(x, y, w, h, label, onPress, RpTheme.DANGER, RpTheme.DANGER_HOVER, 0xFFFF9E9E);
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int b = isHovered() ? bgHover : bg;
        RpRoundRect.outlined(g, getX(), getY(), getX() + getWidth(), getY() + getHeight(), 6f, b, b);
        int textColor = active ? fg : 0xFF55505A;
        net.minecraft.client.gui.Font f = net.minecraft.client.Minecraft.getInstance().font;
        int tw = f.width(getMessage());
        g.drawString(f, getMessage(), getX() + (getWidth() - tw) / 2, getY() + (getHeight() - 8) / 2, textColor);
    }
}
