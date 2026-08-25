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
            String offerId,
            String charId,
            String charName,
            String professionId,
            int initialTicks,
            String waveId,
            String kind) {
        OFFERS.removeIf(o -> o.offerId().equals(offerId));
        OFFERS.add(new OfferEntry(
                offerId,
                charId,
                charName,
                professionId,
                initialTicks,
                waveId,
                kind,
                System.currentTimeMillis(),
                false));
    }

    /** 接受后标记为已同意：保留在右侧悬浮 HUD 中，显示「已同意 · 等待部署」+ 强制部署倒计时。 */
    public static void markAccepted(String offerId) {
        for (int i = 0; i < OFFERS.size(); i++) {
            OfferEntry o = OFFERS.get(i);
            if (o.offerId().equals(offerId)) {
                OFFERS.set(
                        i,
                        new OfferEntry(
                                o.offerId(),
                                o.charId(),
                                o.charName(),
                                o.professionId(),
                                o.initialTicks(),
                                o.waveId(),
                                o.kind(),
                                o.receivedAt(),
                                true));
                return;
            }
        }
    }

    public static void remove(String offerId) {
        OFFERS.removeIf(o -> o.offerId().equals(offerId));
    }

    public static void clear() {
        OFFERS.clear();
    }

    public static boolean isEmpty() {
        prune();
        return OFFERS.isEmpty();
    }

    /** 清理已过期的邀请（按 receivedAt + initialTicks*50ms），防止残留导致弹窗死锁。 */
    public static synchronized void prune() {
        long now = System.currentTimeMillis();
        OFFERS.removeIf(o -> now - o.receivedAt() > o.initialTicks() * 50L);
    }

    public record OfferEntry(
            String offerId,
            String charId,
            String charName,
            String professionId,
            int initialTicks,
            String waveId,
            String kind,
            long receivedAt,
            boolean accepted) {}

    public static void render(GuiGraphics gfx, int width, int height) {
        if (OFFERS.isEmpty()) {
            return;
        }
        // 已同意后：右侧悬浮 HUD 只显示「已同意」的邀请（带强制部署倒计时）；未同意时显示全部待处理邀请。
        List<OfferEntry> toRender = OFFERS.stream().filter(OfferEntry::accepted).toList();
        if (toRender.isEmpty()) {
            toRender = List.copyOf(OFFERS);
        }
        int x = width - 128;
        int y = height / 2 - 90;
        for (OfferEntry o : toRender) {
            int w = 120;
            int h = 44;
            RpRoundRect.fill(gfx, x, y, x + w, y + h, 8f, o.accepted() ? 0xEE1F4D33 : 0xEE3A3A3A);
            // 左侧类型色条（与弹窗一致：征召红/指定编制金/通用选岗青；已同意=绿）
            int kc = o.accepted()
                    ? RpTheme.STATUS_ALIVE
                    : switch (o.kind() == null ? "" : o.kind()) {
                        case "conscript" -> RpTheme.RED_LINE;
                        case "typed" -> RpTheme.GOLD;
                        case "pick" -> RpTheme.CYAN;
                        default -> RpTheme.ACCENT;
                    };
            RpRoundRect.fill(gfx, x, y, x + 3, y + h, 8f, kc);
            // 人物立绘（战术装备预览同款：水平跟随鼠标、俯仰锁定，带职位装备）
            CharacterPreview.renderPortrait(
                    gfx,
                    x + 23,
                    y + 22,
                    16,
                    (float) net.minecraft.client.Minecraft.getInstance()
                            .mouseHandler
                            .xpos(),
                    o.charId());
            String profName = ClientCharacterState.professionName(o.professionId());
            String facName = ClientCharacterState.factionNameOf(o.professionId());
            gfx.drawString(
                    net.minecraft.client.Minecraft.getInstance().font, profName, x + 44, y + 8, RpTheme.TEXT_PRIMARY);
            RpIcons.factionBadge(gfx, x + 50, y + 27, 6, factionOfProfession(o.professionId()), false);
            if (o.accepted()) {
                gfx.drawString(
                        net.minecraft.client.Minecraft.getInstance().font,
                        net.minecraft.network.chat.Component.translatable("ccnr_rp.spawn.recruit.accepted_wait")
                                .getString(),
                        x + 58,
                        y + 21,
                        RpTheme.STATUS_ALIVE,
                        true);
            } else {
                // 副行显示征召阵营（无则留空，不露内部 ID）
                if (!facName.isEmpty()) {
                    gfx.drawString(
                            net.minecraft.client.Minecraft.getInstance().font,
                            facName,
                            x + 58,
                            y + 21,
                            RpTheme.TEXT_SECONDARY);
                }
            }
            long remain = Math.max(0, o.initialTicks() - (System.currentTimeMillis() - o.receivedAt()) / 50);
            int fill = (int) (w * (float) Math.min(o.initialTicks(), remain) / Math.max(1, o.initialTicks()));
            gfx.fill(x, y + h - 3, x + fill, y + h, o.accepted() ? RpTheme.STATUS_ALIVE : RpTheme.ACCENT);
            y += h + 6;
        }
    }

    /** 背包打开时绘制：右上角置顶「已同意玩家」列表（接受后不可取消）。 */
    public static void renderAcceptedList(GuiGraphics gfx, int width, int height) {
        List<OfferEntry> accepted = OFFERS.stream().filter(OfferEntry::accepted).toList();
        if (accepted.isEmpty()) {
            return;
        }
        var font = net.minecraft.client.Minecraft.getInstance().font;
        int x = width - 150;
        int y = 18;
        gfx.drawString(
                font,
                net.minecraft.network.chat.Component.translatable("ccnr_rp.spawn.recruit.accepted_header")
                        .getString(),
                x,
                y,
                RpTheme.CYAN,
                true);
        y += 16;
        for (OfferEntry o : accepted) {
            int h = 24;
            RpRoundRect.outlined(gfx, x, y, x + 140, y + h, 5f, RpTheme.PANEL_BORDER, 0xEE1F4D33);
            CharacterPreview.renderPortrait(gfx, x + 13, y + h / 2, 9, 0f, o.charId());
            gfx.drawString(
                    font,
                    ClientCharacterState.professionName(o.professionId()),
                    x + 26,
                    y + 6,
                    RpTheme.STATUS_ALIVE,
                    true);
            String facName = ClientCharacterState.factionNameOf(o.professionId());
            if (!facName.isEmpty()) {
                gfx.drawString(font, facName, x + 26, y + 13, RpTheme.TEXT_DIM);
            }
            y += h + 4;
        }
    }

    /** 职业 → 所属阵营 JSON（无则 null，徽章回退默认）。 */
    private static com.google.gson.JsonObject factionOfProfession(String professionId) {
        for (com.google.gson.JsonObject p : ClientCharacterState.professions()) {
            String pid = p.has("id") && !p.get("id").isJsonNull() ? p.get("id").getAsString() : "";
            if (pid.equals(professionId)) {
                String fid = p.has("factionId") && !p.get("factionId").isJsonNull()
                        ? p.get("factionId").getAsString()
                        : "";
                for (com.google.gson.JsonObject f : ClientCharacterState.factions()) {
                    if (fid.equals(
                            f.has("id") && !f.get("id").isJsonNull()
                                    ? f.get("id").getAsString()
                                    : "")) {
                        return f;
                    }
                }
                return null;
            }
        }
        return null;
    }
}
