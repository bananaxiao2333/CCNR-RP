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
 * 击杀友好提示（HUD 覆盖层 toast）：击杀者击杀友好阵营玩家后短暂显示，
 * 展示被击杀者的阵营、职业、玩家名与玩家 UUID（需求「击杀友好提示」）。
 * 位置锚点 = 聊天区底部 + 聊天区高度 + 可配置偏移（serverconfig kill.noticeOffset，服务端权威下发），
 * 提示贴在聊天区上方，避免被聊天框遮挡；ChatScreen 打开时聊天区更高，提示自动随之上移。
 * 显示时长固定（约 6 秒）；入场电影黑屏期间不显示；下线清理。
 * 服务端开关 serverconfig kill.friendlyNotice 关闭时服务端不发包，本层自然不显示。
 */
public final class KillFriendlyNoticeHud {

    private static String victimName = "";
    private static String victimUuid = "";
    private static String victimFactionId = "";
    private static String victimProfessionId = "";
    private static long shownAt = 0;

    private static final long DURATION_MS = 6_000L;
    private static final int ROW_H = 11;
    private static final int LINE_GAP = 2;

    private KillFriendlyNoticeHud() {}

    /** 显示击杀友好提示（覆盖上一条）。 */
    public static void show(String name, String uuid, String factionId, String professionId) {
        victimName = name == null ? "" : name;
        victimUuid = uuid == null ? "" : uuid;
        victimFactionId = factionId == null ? "" : factionId;
        victimProfessionId = professionId == null ? "" : professionId;
        shownAt = System.currentTimeMillis();
    }

    /** 下线清理。 */
    public static void clear() {
        shownAt = 0;
        victimName = "";
        victimUuid = "";
        victimFactionId = "";
        victimProfessionId = "";
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
        // 三行：标题（红色醒目）+ 阵营·职业 + 玩家名·UUID（第二/三行次要色）
        String title = Component.translatable("ccnr_rp.hud.kill_friendly.title").getString();
        String fac = factionName(victimFactionId);
        String prof = professionName(victimProfessionId);
        String line2 = Component.translatable("ccnr_rp.hud.kill_friendly.detail", fac, prof)
                .getString();
        String line3 = Component.translatable("ccnr_rp.hud.kill_friendly.player", victimName, victimUuid)
                .getString();
        int bw = Math.max(Math.max(font.width(title), font.width(line2)), Math.max(font.width(line3), 120)) + 28;
        int boxH = ROW_H * 3 + LINE_GAP * 2 + 16;
        // 锚点 = 聊天区底部 + 聊天区高度 + 可配置偏移：提示贴在聊天区上方（ChatComponent 底部距屏底 40px，
        // 渲染时整体按 chatScale 缩放，屏幕像素高度 = getHeight() * getScale()）。
        var chat = mc.gui.getChat();
        int chatBottom = h - 40;
        int chatHeightPx = (int) Math.ceil(chat.getHeight() * chat.getScale());
        int offset = Math.max(0, ClientCharacterState.killNoticeOffset());
        int x = 10;
        int y = chatBottom - chatHeightPx - offset - boxH;
        if (y < 10) {
            y = 10; // 兜底：聊天区异常高（如全屏聊天）时至少留在屏幕内
        }
        RpRoundRect.fill(g, x, y, x + bw, y + boxH, 8f, 0xEE2E2E2E);
        RpRoundRect.fill(g, x, y, x + 3, y + boxH, 8f, RpTheme.STATUS_DEAD); // 左侧红色状态条
        g.drawString(font, title, x + 14, y + 6, RpTheme.RED, true);
        g.drawString(font, line2, x + 14, y + 6 + ROW_H + LINE_GAP, RpTheme.TEXT_PRIMARY, true);
        g.drawString(font, line3, x + 14, y + 6 + (ROW_H + LINE_GAP) * 2, RpTheme.TEXT_SECONDARY, true);
    }

    private static String factionName(String fid) {
        if (fid == null || fid.isBlank()) {
            return fid == null ? "" : fid;
        }
        for (JsonObject f : ClientCharacterState.factions()) {
            if (fid.equals(
                    f.has("id") && !f.get("id").isJsonNull() ? f.get("id").getAsString() : "")) {
                String n = f.has("name") && !f.get("name").isJsonNull()
                        ? f.get("name").getAsString()
                        : "";
                return n.isBlank() ? fid : n;
            }
        }
        return fid;
    }

    private static String professionName(String pid) {
        if (pid == null || pid.isBlank()) {
            return pid == null ? "" : pid;
        }
        for (JsonObject p : ClientCharacterState.professions()) {
            if (pid.equals(
                    p.has("id") && !p.get("id").isJsonNull() ? p.get("id").getAsString() : "")) {
                String n = p.has("name") && !p.get("name").isJsonNull()
                        ? p.get("name").getAsString()
                        : "";
                return n.isBlank() ? pid : n;
            }
        }
        return pid;
    }
}
