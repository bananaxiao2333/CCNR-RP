/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.ccnrcom.rp.network.RpChannels;
import com.ccnrcom.rp.network.RpPackets;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** 招募请求窗 v2（CCNR-Com 风格：右侧圆角面板 + 卡片 + 主题按钮；自动弹出/关闭）。 */
public class RecruitPopupScreen extends Screen {
    private static RecruitPopupScreen open;

    private int px1, py1, px2, py2;

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
        int x = px1 + 12;
        int y = py1 + 34;
        for (RecruitOverlayHud.OfferEntry o : entries()) {
            addRenderableWidget(RpButton.primary(
                    x + 190, y + 18, 56, 20, Component.translatable("ccnr_rp.spawn.recruit.accept"), b -> {
                        RpChannels.sendToServer(new RpPackets.RecruitAnswerC2S(o.offerId(), true));
                        RecruitOverlayHud.remove(o.offerId());
                        rebuild();
                    }));
            addRenderableWidget(RpButton.secondary(
                    x + 252, y + 18, 56, 20, Component.translatable("ccnr_rp.spawn.recruit.decline"), b -> {
                        RpChannels.sendToServer(new RpPackets.RecruitAnswerC2S(o.offerId(), false));
                        RecruitOverlayHud.remove(o.offerId());
                        rebuild();
                    }));
            y += 72;
        }
    }

    @Override
    protected void init() {
        open = this;
        int w = Math.min(360, this.width - 40);
        px1 = this.width - w - 12;
        py1 = this.height / 2 - 120;
        px2 = this.width - 12;
        py2 = this.height / 2 + 120;
        rebuild();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        RpRoundRect.outlined(g, px1, py1, px2, py2, 14f, RpTheme.PANEL_BORDER, RpTheme.OVERLAY);
        g.drawString(font, title, px1 + 12, py1 + 10, RpTheme.TEXT_PRIMARY);
        int y = py1 + 34;
        for (RecruitOverlayHud.OfferEntry o : entries()) {
            RpRoundRect.fill(g, px1 + 8, y, px2 - 8, y + 58, 8f, RpTheme.PANEL_BG_ALT);
            ResourceLocation tex = SkinCache.textureOrNull(o.charId());
            if (tex != null) {
                g.blit(tex, px1 + 16, y + 10, 38, 38, 0, 0, 32, 32, 32, 32);
            }
            g.drawString(font, o.charName(), px1 + 62, y + 12, RpTheme.TEXT_PRIMARY);
            g.drawString(font, "复活波 " + o.waveId(), px1 + 62, y + 26, RpTheme.TEXT_SECONDARY);
            g.drawString(font, o.professionId(), px1 + 62, y + 40, RpTheme.ACCENT_HOVER);
            y += 72;
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void tick() {
        if (RecruitOverlayHud.isEmpty()) {
            onClose();
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
