/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.ccnrcom.rp.network.RpChannels;
import com.ccnrcom.rp.network.RpPackets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

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
        // 已同意（等待部署）的邀请不再展示接受/拒绝操作，只保留在右侧悬浮 HUD 显示倒计时。
        return RecruitOverlayHud.OFFERS.stream().filter(e -> !e.accepted()).toList();
    }

    private void rebuild() {
        clearWidgets();
        int x = px1 + 12;
        int y = py1 + 34;
        for (RecruitOverlayHud.OfferEntry o : entries()) {
            addRenderableWidget(RpButton.primary(
                    x + 190, y + 18, 56, 20, Component.translatable("ccnr_rp.spawn.recruit.accept"), b -> {
                        // v2（唯一身份）起：pick 邀请不再弹选岗菜单，接受即按自己当前职业部署（服务端结算时校验观察者状态）
                        RpChannels.sendToServer(new RpPackets.RecruitAnswerC2S(o.offerId(), true));
                        // 同意后不可取消：仅在右侧悬浮 HUD 保留该邀请（已同意 + 强制部署倒计时），弹窗移除操作项。
                        RecruitOverlayHud.markAccepted(o.offerId());
                        rebuild();
                    }));
            addRenderableWidget(RpButton.secondary(
                    x + 252, y + 18, 56, 20, Component.translatable("ccnr_rp.spawn.recruit.decline"), b -> {
                        RpChannels.sendToServer(new RpPackets.RecruitAnswerC2S(o.offerId(), false));
                        RecruitOverlayHud.remove(o.offerId());
                        rebuild();
                    }));
            y += 82;
        }
    }

    @Override
    protected void init() {
        open = this;
        int w = Math.min(360, this.width - 40);
        px1 = this.width - w - 12;
        py1 = this.height / 2 - 140;
        px2 = this.width - 12;
        py2 = this.height / 2 + 140;
        rebuild();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        RpRoundRect.outlined(g, px1, py1, px2, py2, 14f, RpTheme.PANEL_BORDER, RpTheme.OVERLAY);
        g.drawString(font, title, px1 + 12, py1 + 10, RpTheme.TEXT_PRIMARY);
        int y = py1 + 34;
        for (RecruitOverlayHud.OfferEntry o : entries()) {
            int cardH = 68;
            int kc = kindColor(o.kind());
            RpRoundRect.fill(g, px1 + 8, y, px2 - 8, y + cardH, 8f, RpTheme.PANEL_BG_ALT);
            RpRoundRect.fill(g, px1 + 8, y, px1 + 11, y + cardH, 8f, kc); // 左侧类型色条
            // 人物立绘（战术装备预览同款：水平跟随鼠标、俯仰锁定，带职位装备）。
            // 临时征召的 charId 不在角色列表，直接按邀请的 professionId 取职业装备渲染
            CharacterPreview.renderPortrait(
                    g,
                    px1 + 35,
                    y + 29,
                    19,
                    mouseX,
                    o.charId(),
                    o.charName(),
                    ClientCharacterState.professionLoadout(o.professionId()));
            // 主要显示可征召职位显示名（不露内部 ID）
            String profName = ClientCharacterState.professionName(o.professionId());
            String facName = ClientCharacterState.factionNameOf(o.professionId());
            g.drawString(font, profName, px1 + 62, y + 8, RpTheme.TEXT_PRIMARY);
            // 第二行：征召阵营 · 类型标签（颜色区分）：强制征召=红 / 指定编制复活=金 / 通用选岗=青
            g.drawString(
                    font,
                    (facName.isEmpty() ? "" : facName + " · ")
                            + Component.translatable(kindKey(o.kind())).getString(),
                    px1 + 62,
                    y + 22,
                    kc,
                    true);
            g.drawString(
                    font,
                    Component.translatable(kindDescKey(o.kind())).getString(),
                    px1 + 62,
                    y + 34,
                    RpTheme.TEXT_SECONDARY);
            // 项目简历（职业 profile，可选；最多 2 行，超长裁剪；未配置不占行）
            String profile = ClientCharacterState.professionProfile(o.professionId());
            if (!profile.isBlank()) {
                int rw = (px2 - 12) - (px1 + 62);
                List<String> lines = wrapText(profile, Math.max(40, rw));
                int ry = y + 46;
                g.enableScissor(px1 + 62, ry, px2 - 12, y + cardH - 2);
                for (int i = 0; i < Math.min(lines.size(), 2); i++) {
                    g.drawString(font, lines.get(i), px1 + 62, ry, RpTheme.TEXT_SECONDARY);
                    ry += 10;
                }
                g.disableScissor();
            }
            y += 82;
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    /** 邀请类型颜色：强制征召=红 / 指定编制复活=金 / 通用选岗=青。 */
    private static int kindColor(String kind) {
        return switch (kind == null ? "" : kind) {
            case "conscript" -> RpTheme.RED_LINE;
            case "typed" -> RpTheme.GOLD;
            case "pick" -> RpTheme.CYAN;
            default -> RpTheme.TEXT_SECONDARY;
        };
    }

    private static String kindKey(String kind) {
        return switch (kind == null ? "" : kind) {
            case "conscript" -> "ccnr_rp.spawn.recruit.kind_conscript";
            case "typed" -> "ccnr_rp.spawn.recruit.kind_typed";
            case "pick" -> "ccnr_rp.spawn.recruit.kind_pick";
            default -> "ccnr_rp.spawn.recruit.kind_wave";
        };
    }

    private static String kindDescKey(String kind) {
        return switch (kind == null ? "" : kind) {
            case "conscript" -> "ccnr_rp.spawn.recruit.desc_conscript";
            case "typed" -> "ccnr_rp.spawn.recruit.desc_typed";
            case "pick" -> "ccnr_rp.spawn.recruit.desc_pick";
            default -> "ccnr_rp.spawn.recruit.desc_wave";
        };
    }

    @Override
    public void tick() {
        if (entries().isEmpty()) {
            onClose();
        }
    }

    /** 按像素宽度折行（中文/长职位名）。 */
    private List<String> wrapText(String text, int maxW) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            out.add("");
            return out;
        }
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || font.width(cur.toString() + c) > maxW) {
                out.add(cur.toString());
                cur.setLength(0);
                if (c == '\n') {
                    continue;
                }
            }
            cur.append(c);
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
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
