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
 * 在背包等任意界面打开时依然可见（ScreenEvent.Render.Post 第二渲染路径）。
 */
public final class EventBanner {

    private EventBanner() {}

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
        int x = (w - total) / 2;
        int y = 8;
        var font = Minecraft.getInstance().font;
        for (String id : ids) {
            // 4:3 横向长方形（红色警戒边框 + 半透明深底）
            RpRoundRect.outlined(g, x, y, x + bw, y + bh, 4f, RpTheme.RED_LINE, 0xC00A0E13);
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
        g.drawCenteredString(
                font,
                Component.translatable("ccnr_rp.event.banner.hint").getString(),
                w / 2,
                y + bh + 2,
                RpTheme.TEXT_DIM);
    }
}
