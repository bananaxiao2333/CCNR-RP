/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;

/** 屏幕右侧招募列表（HUD 覆盖层 v2）：圆角卡片 + 进度条。点击操作由 RecruitPopupScreen 承担。 */
public final class RecruitOverlayHud {
    public static final List<OfferEntry> OFFERS = new ArrayList<>();

    private RecruitOverlayHud() {}

    public static void add(
            String offerId, String charId, String charName, String professionId, int initialTicks, String waveId) {
        OFFERS.removeIf(o -> o.offerId().equals(offerId));
        OFFERS.add(new OfferEntry(
                offerId, charId, charName, professionId, initialTicks, waveId, System.currentTimeMillis()));
    }

    public static void remove(String offerId) {
        OFFERS.removeIf(o -> o.offerId().equals(offerId));
    }

    public static void clear() {
        OFFERS.clear();
    }

    public static boolean isEmpty() {
        return OFFERS.isEmpty();
    }

    public record OfferEntry(
            String offerId,
            String charId,
            String charName,
            String professionId,
            int initialTicks,
            String waveId,
            long receivedAt) {}

    public static void render(GuiGraphics gfx, int width, int height) {
        if (OFFERS.isEmpty()) {
            return;
        }
        int x = width - 128;
        int y = height / 2 - 90;
        for (OfferEntry o : OFFERS) {
            int w = 120;
            int h = 44;
            RpRoundRect.fill(gfx, x, y, x + w, y + h, 8f, 0xEE1E1E22);
            RpRoundRect.fill(gfx, x, y, x + 3, y + h, 8f, RpTheme.ACCENT);
            var tex = SkinCache.textureOrNull(o.charId());
            if (tex != null) {
                gfx.blit(tex, x + 7, y + 6, 32, 32, 0, 0, 32, 32, 32, 32);
            }
            gfx.drawString(
                    net.minecraft.client.Minecraft.getInstance().font,
                    o.charName(),
                    x + 44,
                    y + 8,
                    RpTheme.TEXT_PRIMARY);
            gfx.drawString(
                    net.minecraft.client.Minecraft.getInstance().font,
                    o.professionId(),
                    x + 44,
                    y + 21,
                    RpTheme.TEXT_SECONDARY);
            long remain = Math.max(0, o.initialTicks() - (System.currentTimeMillis() - o.receivedAt()) / 50);
            int fill = (int) (w * (float) Math.min(o.initialTicks(), remain) / Math.max(1, o.initialTicks()));
            gfx.fill(x, y + h - 3, x + fill, y + h, RpTheme.ACCENT);
            y += h + 6;
        }
    }
}
