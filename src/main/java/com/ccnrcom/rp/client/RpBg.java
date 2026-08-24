/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;

/**
 * 终端背景：CCNR 竖版图标（半透明水印）。等比铺满面板高度并居中，alpha 0.28 保持可读性。
 */
public final class RpBg {

    private static final ResourceLocation LOGO = new ResourceLocation("ccnr_rp", "textures/gui/bg_logo.png");
    private static final float ALPHA = 0.38f;

    private RpBg() {}

    /** 在终端面板内绘制半透明背景图标。 */
    public static void draw(GuiGraphics g, int x1, int y1, int x2, int y2) {
        int pw = x2 - x1;
        int ph = y2 - y1;
        if (pw <= 0 || ph <= 0) {
            return;
        }
        // 等比：铺满面板高度，居中
        int size = Math.min(Math.min(pw, ph) - 24, 620);
        if (size < 40) {
            return;
        }
        int ix = x1 + (pw - size) / 2;
        int iy = y1 + (ph - size) / 2;
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, ALPHA);
        g.blit(LOGO, ix, iy, size, size, 0, 0, 1024, 1024, 1024, 1024);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
