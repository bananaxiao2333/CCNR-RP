/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** 屏幕右侧招募列表（HUD 覆盖层 v2）：圆角卡片 + 进度条。点击操作由 RecruitPopupScreen 承担。 */
public final class RecruitOverlayHud {
    public static final List<OfferEntry> OFFERS = new ArrayList<>();

    /** 整波已加入名单（服务端权威推送）：groupId -> 该波已加入玩家 + 展示 id/目标数。 */
    private static final Map<String, RosterState> ROSTERS = new LinkedHashMap<>();

    private RecruitOverlayHud() {}

    public static void add(
            String offerId,
            String groupId,
            String charId,
            String charName,
            String professionId,
            int initialTicks,
            String waveId,
            String kind) {
        OFFERS.removeIf(o -> o.offerId().equals(offerId));
        OFFERS.add(new OfferEntry(
                offerId,
                groupId,
                charId,
                charName,
                professionId,
                initialTicks,
                waveId,
                kind,
                System.currentTimeMillis(),
                false));
    }

    /** 收到服务端已加入名单推送；空名单 = 该分组结算/取消，客户端移除。 */
    public static void setRoster(
            String groupId,
            String waveId,
            int target,
            List<com.ccnrcom.rp.network.RpPackets.RecruitRosterS2C.RosterEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            ROSTERS.remove(groupId);
        } else {
            ROSTERS.put(groupId, new RosterState(waveId, target, List.copyOf(entries)));
        }
    }

    /** 本玩家收到的邀请所涉及的分组（保持出现顺序），用于按当前波渲染已加入名单。 */
    private static List<String> activeGroupIds() {
        List<String> out = new ArrayList<>();
        for (OfferEntry o : OFFERS) {
            if (o.groupId() != null && !o.groupId().isBlank() && !out.contains(o.groupId())) {
                out.add(o.groupId());
            }
        }
        return out;
    }

    /** 一波的已加入名单展示态：展示 id、目标人数、已加入条目。 */
    private record RosterState(
            String waveId, int target, List<com.ccnrcom.rp.network.RpPackets.RecruitRosterS2C.RosterEntry> entries) {}

    /** 接受后标记为已同意：保留在右侧悬浮 HUD 中，显示「已同意 · 等待部署」+ 强制部署倒计时。 */
    public static void markAccepted(String offerId) {
        for (int i = 0; i < OFFERS.size(); i++) {
            OfferEntry o = OFFERS.get(i);
            if (o.offerId().equals(offerId)) {
                OFFERS.set(
                        i,
                        new OfferEntry(
                                o.offerId(),
                                o.groupId(),
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
        ROSTERS.clear();
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
            String groupId,
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
            RpRoundRect.fill(gfx, x, y, x + w, y + h, 8f, o.accepted() ? 0xEE1A1A1A : 0xEE3A3A3A);
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
            // 人物立绘（战术装备预览同款：XYZ 锁定正面视角，带职位装备）。
            // 临时征召的 charId 不在角色列表，直接按邀请的 professionId 取职业装备渲染
            CharacterPreview.renderPortrait(
                    gfx,
                    x + 23,
                    y + 22,
                    16,
                    o.charId(),
                    o.charName(),
                    ClientCharacterState.professionLoadout(o.professionId()));
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

    /** 背包打开时绘制：右上角置顶「已加入玩家」名单（整波已加入，随服务端推送实时刷新；接受后不可取消）。 */
    public static void renderAcceptedList(GuiGraphics gfx, int width, int height) {
        if (OFFERS.isEmpty()) {
            return;
        }
        var font = net.minecraft.client.Minecraft.getInstance().font;
        int x = width - 150;
        int y = 18;
        boolean drewAny = false;
        for (String groupId : activeGroupIds()) {
            RosterState rs = ROSTERS.get(groupId);
            if (rs == null || rs.entries().isEmpty()) {
                continue;
            }
            gfx.drawString(
                    font,
                    Component.translatable(
                                    "ccnr_rp.spawn.recruit.roster_header",
                                    rs.waveId(),
                                    String.valueOf(rs.entries().size()),
                                    String.valueOf(rs.target()))
                            .getString(),
                    x,
                    y,
                    RpTheme.CYAN,
                    true);
            y += 16;
            for (com.ccnrcom.rp.network.RpPackets.RecruitRosterS2C.RosterEntry e : rs.entries()) {
                int h = 24;
                RpRoundRect.outlined(gfx, x, y, x + 140, y + h, 5f, RpTheme.PANEL_BORDER, 0xEE1A1A1A);
                // 显示该玩家本人皮肤（不再清一色本地玩家皮肤）
                CharacterPreview.renderPlayerSkin(
                        gfx,
                        x + 13,
                        y + h / 2,
                        9,
                        e.charId(),
                        e.charName(),
                        ClientCharacterState.professionLoadout(e.professionId()));
                gfx.drawString(
                        font,
                        ClientCharacterState.professionName(e.professionId()),
                        x + 26,
                        y + 6,
                        RpTheme.STATUS_ALIVE,
                        true);
                String facName = ClientCharacterState.factionNameOf(e.professionId());
                if (!facName.isEmpty()) {
                    gfx.drawString(font, facName, x + 26, y + 13, RpTheme.TEXT_DIM);
                }
                y += h + 4;
            }
            drewAny = true;
        }
        // 名单尚未到达时的兜底：本玩家自己已同意的邀请（同样显示其本人皮肤）
        if (!drewAny) {
            List<OfferEntry> accepted =
                    OFFERS.stream().filter(OfferEntry::accepted).toList();
            if (accepted.isEmpty()) {
                return;
            }
            gfx.drawString(
                    font,
                    Component.translatable("ccnr_rp.spawn.recruit.accepted_header")
                            .getString(),
                    x,
                    y,
                    RpTheme.CYAN,
                    true);
            y += 16;
            for (OfferEntry o : accepted) {
                int h = 24;
                RpRoundRect.outlined(gfx, x, y, x + 140, y + h, 5f, RpTheme.PANEL_BORDER, 0xEE1A1A1A);
                CharacterPreview.renderPlayerSkin(
                        gfx,
                        x + 13,
                        y + h / 2,
                        9,
                        o.charId(),
                        o.charName(),
                        ClientCharacterState.professionLoadout(o.professionId()));
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
