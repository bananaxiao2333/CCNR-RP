/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import net.minecraft.client.gui.GuiGraphics;

/** 屏幕遮罩渐变（FADE 步骤）：起始/结束透明度线性插值，GuiGraphics 覆盖渲染。 */
public final class FadeOverlay {
    private static String color = "#000000";
    private static float from = 0;
    private static float to = 0;
    private static int duration = 1;
    private static int elapsed = 0;
    private static boolean active = false;

    private FadeOverlay() {}

    public static void start(String hexColor, float fromAlpha, float toAlpha, int durationTicks) {
        color = hexColor;
        from = clamp(fromAlpha);
        to = clamp(toAlpha);
        duration = Math.max(1, durationTicks);
        elapsed = 0;
        active = true;
    }

    public static float alpha() {
        if (!active) {
            return 0;
        }
        elapsed++;
        if (elapsed >= duration) {
            active = false;
            return clamp(to);
        }
        float t = (float) elapsed / duration;
        return from + (to - from) * t;
    }

    public static void render(GuiGraphics gfx, int width, int height) {
        float a = alpha();
        if (a <= 0.001f) {
            return;
        }
        int argb;
        try {
            argb = (int) Math.round(a * 255) << 24 | Integer.parseInt(color.substring(1), 16);
        } catch (Exception e) {
            argb = (int) Math.round(a * 255) << 24 | 0x000000;
        }
        gfx.fill(0, 0, width, height, argb);
    }

    private static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }
}
