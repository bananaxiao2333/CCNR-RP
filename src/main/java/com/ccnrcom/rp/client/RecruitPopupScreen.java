/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.ccnrcom.rp.network.RpChannels;
import com.ccnrcom.rp.network.RpPackets;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** 招募请求小窗（自动弹出；右侧布局）：条目 + 接受/拒绝按钮；列表清空自动关闭。 */
public class RecruitPopupScreen extends Screen {
    private static RecruitPopupScreen open;

    public RecruitPopupScreen() {
        super(Component.translatable("ccnr_rp.spawn.recruit.title"));
    }

    public static void refreshIfOpen() {
        if (open != null) {
            open.rebuild();
        }
    }

    private List<RecruitOverlayHud.OfferEntry> entries() {
        return List.copyOf(RecruitOverlayHud.OFFERS);
    }

    private void rebuild() {
        clearWidgets();
        int x = width - 230;
        int y = height / 2 - 90;
        for (RecruitOverlayHud.OfferEntry o : entries()) {
            addRenderableWidget(Button.builder(Component.translatable("ccnr_rp.spawn.recruit.accept"), b -> {
                        RpChannels.sendToServer(new RpPackets.RecruitAnswerC2S(o.offerId(), true));
                        RecruitOverlayHud.remove(o.offerId());
                        rebuild();
                    })
                    .bounds(x + 130, y + 6, 44, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.translatable("ccnr_rp.spawn.recruit.decline"), b -> {
                        RpChannels.sendToServer(new RpPackets.RecruitAnswerC2S(o.offerId(), false));
                        RecruitOverlayHud.remove(o.offerId());
                        rebuild();
                    })
                    .bounds(x + 178, y + 6, 44, 20)
                    .build());
            y += 42;
        }
    }

    @Override
    protected void init() {
        open = this;
        rebuild();
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        renderBackground(gfx);
        int x = width - 230;
        int y = height / 2 - 90;
        gfx.drawString(font, title, x, y - 14, 0xFFFFFF);
        for (RecruitOverlayHud.OfferEntry o : entries()) {
            gfx.fill(x, y, x + 226, y + 34, 0xAA202020);
            gfx.drawString(font, o.charName() + " [" + o.professionId() + "]", x + 4, y + 8, 0xFFFFFF);
            y += 42;
        }
        super.render(gfx, mouseX, mouseY, partialTick);
    }

    @Override
    public void tick() {
        if (RecruitOverlayHud.isEmpty()) {
            onClose();
            return;
        }
    }

    @Override
    public void onClose() {
        open = null;
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
