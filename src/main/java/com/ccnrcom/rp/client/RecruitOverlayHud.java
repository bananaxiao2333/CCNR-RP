/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;

/** 屏幕右侧招募列表（HUD 覆盖层）：条目 + 倒计时条 + 皮肤头像。点击操作由 RecruitPopupScreen 承担。 */
public final class RecruitOverlayHud {
    public static final List<OfferEntry> OFFERS = new ArrayList<>();

    private RecruitOverlayHud() {}

    public static void add(
            String offerId, String charId, String charName, String professionId, int initialTicks, String waveId) {
        OFFERS.removeIf(o -> o.offerId().equals(offerId));
        OFFERS.add(new OfferEntry(offerId, charId, charName, professionId, initialTicks, waveId));
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
            String offerId, String charId, String charName, String professionId, int initialTicks, String waveId) {}

    /** 渲染（每 tick 由 overlay 调用；剩余比例近似用时间戳——由入口记录）。 */
    public static void render(GuiGraphics gfx, int width, int height) {
        if (OFFERS.isEmpty()) {
            return;
        }
        int x = width - 130;
        int y = height / 2 - 80;
        for (OfferEntry o : OFFERS) {
            int w = 122;
            int h = 36;
            gfx.fill(x, y, x + w, y + h, 0xAA202020);
            if (o.charId != null && net.minecraft.client.Minecraft.getInstance().getConnection() != null) {
                var tex = SkinCache.textureOrNull(o.charId);
                if (tex != null) {
                    gfx.blit(tex, x + 3, y + 3, 30, 30, 0, 0, 32, 32, 32, 32);
                }
            }
            gfx.drawString(net.minecraft.client.Minecraft.getInstance().font, o.charName, x + 38, y + 4, 0xFFFFFF);
            gfx.drawString(net.minecraft.client.Minecraft.getInstance().font, o.professionId, x + 38, y + 16, 0xCCCCCC);
            // 倒计时条（简化：固定显示 offer 剩余轮廓）
            gfx.fill(x, y + h - 4, x + w, y + h, 0xFF2F6BFF);
            y += h + 6;
        }
    }
}
