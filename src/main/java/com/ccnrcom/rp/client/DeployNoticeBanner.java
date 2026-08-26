/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 部署完成常驻横幅（HUD 覆盖层）：部署成功后显示「已部署：职位」并保持一段时间（30s）。
 * 邀请部署 / 波次完毕 / 人满提前部署 / 存活正式转职部署全部经服务端 DeployNoticeS2C 汇入此入口。
 * 入场电影（黑屏）期间不显示；下线清理。
 */
public final class DeployNoticeBanner {

    private static String professionName = "";
    private static String factionId = "";
    private static long shownAt = 0;

    private static final long DURATION_MS = 30_000L;

    private DeployNoticeBanner() {}

    /** 显示部署完成横幅（覆盖上一条）。 */
    public static void show(String profName, String facId) {
        professionName = profName == null ? "" : profName;
        factionId = facId == null ? "" : facId;
        shownAt = System.currentTimeMillis();
    }

    /** 下线清理。 */
    public static void clear() {
        shownAt = 0;
        professionName = "";
        factionId = "";
    }

    public static void render(GuiGraphics g, int w, int h) {
        if (shownAt == 0 || System.currentTimeMillis() - shownAt > DURATION_MS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || CinematicController.active()) {
            return; // 电影黑屏期间不显示
        }
        var font = mc.font;
        String text = Component.translatable("ccnr_rp.hud.deployed_banner", professionName)
                .getString();
        int bw = font.width(text) + 40;
        int x = (w - bw) / 2;
        int y = 22;
        int hh = 26;
        // 顶部居中横幅：绿色主题 + 左侧状态条 + 阵营徽章
        RpRoundRect.fill(g, x, y, x + bw, y + hh, 8f, 0xEE1F4D33);
        RpRoundRect.fill(g, x, y, x + 3, y + hh, 8f, RpTheme.STATUS_ALIVE);
        JsonObject fac = factionById(factionId);
        if (fac != null) {
            RpIcons.factionBadge(g, x + 15, y + hh / 2, 6, fac, false);
        }
        g.drawString(font, text, x + 26, y + 9, RpTheme.STATUS_ALIVE, true);
    }

    private static JsonObject factionById(String fid) {
        if (fid == null || fid.isBlank()) {
            return null;
        }
        for (JsonObject f : ClientCharacterState.factions()) {
            if (fid.equals(
                    f.has("id") && !f.get("id").isJsonNull() ? f.get("id").getAsString() : "")) {
                return f;
            }
        }
        return null;
    }
}
