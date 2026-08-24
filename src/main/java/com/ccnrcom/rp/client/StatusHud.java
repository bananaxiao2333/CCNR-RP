/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 右下角状态栏 HUD：每行 = 方形图标 + 等宽长方形（进度条或文字，由配置决定）。
 * 行：职位（等级进度）/ 阵营（背景=阵营颜色）/ 血量（百分比）。全部可在管理器设置。
 */
public final class StatusHud {

    private StatusHud() {}

    public static void render(GuiGraphics g, int w, int h) {
        if (!ClientCharacterState.settingBool("hudEnabled", true)) {
            return;
        }
        // 入场电影（黑屏）期间隐藏 HUD——层低于黑屏
        if (CinematicController.active()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        JsonObject charData = currentCharacter();
        if (charData == null) {
            return;
        }
        String profName = professionName(charData);
        JsonObject faction = factionMeta(charData);
        String facName = faction == null ? str(charData, "factionId") : str(faction, "name");
        int facColor = faction == null ? 0xFF3D7BFF : parseColor(str(faction, "color"), 0xFF3D7BFF);
        String facIcon = faction == null ? "hex" : str(faction, "icon");
        if (facIcon.isBlank()) {
            facIcon = "hex";
        }
        float health = mc.player.getHealth();
        float maxHealth = mc.player.getMaxHealth();
        int healthPct = maxHealth <= 0 ? 0 : Math.round(health / maxHealth * 100f);

        int rowW = 178;
        int iconS = 18;
        int barW = rowW - iconS - 4;
        int barH = 16;
        int rowGap = 4;
        int margin = 10;
        int px = w - rowW - margin;
        int py = h - margin - (barH * 3 + rowGap * 2) - 12;

        int rowY = py;
        // 职位
        RpIcons.badge(g, px + iconS / 2, rowY + barH / 2, iconS / 2 - 1, "cross", 2, false);
        drawRow(
                g,
                px,
                rowY,
                iconS,
                barW,
                barH,
                profName,
                ClientCharacterState.settingBool("hudProfessionText", true),
                levelProgress(charData),
                RpTheme.CYAN);
        // 阵营（背景=阵营颜色）
        rowY += barH + rowGap;
        RpIcons.badge(g, px + iconS / 2, rowY + barH / 2, iconS / 2 - 1, facIcon, tierOf(faction), false);
        drawRow(
                g,
                px,
                rowY,
                iconS,
                barW,
                barH,
                facName,
                ClientCharacterState.settingBool("hudFactionText", false),
                1.0f,
                facColor);
        // 血量
        rowY += barH + rowGap;
        float hpProgress = healthPct / 100f;
        int hpColor = healthPct > 50 ? 0xFF35E07A : (healthPct > 25 ? 0xFFFFC84C : 0xFFFF3B30);
        RpIcons.slot(g, px + 1, rowY + 1, iconS - 2, "heart", hpColor);
        drawRow(
                g,
                px,
                rowY,
                iconS,
                barW,
                barH,
                healthPct + "%",
                ClientCharacterState.settingBool("hudHealthText", false),
                hpProgress,
                hpColor);
    }

    /** 行渲染：等宽长方形（textMode → 文字；否则进度条 fill）。图标由调用方先画。 */
    private static void drawRow(
            GuiGraphics g,
            int px,
            int y,
            int iconS,
            int barW,
            int barH,
            String label,
            boolean textMode,
            float fill,
            int color) {
        int bx = px + iconS + 4;
        // 槽
        RpRoundRect.outlined(g, bx, y, bx + barW, y + barH, 3f, RpTheme.PANEL_BORDER_BRIGHT, 0xB0141C22);
        int fillPx = Math.max(0, Math.min(barW - 2, Math.round(fill * (barW - 2))));
        var font = Minecraft.getInstance().font;
        if (textMode) {
            // 文字模式：底部用颜色细线点缀
            g.fill(bx + 1, y + barH - 3, bx + barW - 1, y + barH - 2, color);
            g.drawString(font, label, bx + 4, y + (barH - 8) / 2 + 1, RpTheme.TEXT_PRIMARY, true);
        } else {
            g.fill(bx + 1, y + 1, bx + 1 + fillPx, y + barH - 1, color);
            String pct = Math.round(fill * 100f) + "%";
            g.drawString(font, pct, bx + (barW - font.width(pct)) / 2, y + (barH - 8) / 2 + 1, 0xFF0A0E13);
        }
    }

    private static JsonObject currentCharacter() {
        for (JsonObject c : ClientCharacterState.list()) {
            if ("alive".equals(str(c, "status"))) {
                return c;
            }
        }
        return ClientCharacterState.find(ClientCharacterState.selected());
    }

    private static String professionName(JsonObject c) {
        String pid = str(c, "professionId");
        for (JsonObject p : ClientCharacterState.professions()) {
            if (pid.equals(str(p, "id"))) {
                String n = str(p, "name");
                return n.isBlank() ? pid : n;
            }
        }
        return pid;
    }

    private static JsonObject factionMeta(JsonObject c) {
        String fid = str(c, "factionId");
        for (JsonObject f : ClientCharacterState.factions()) {
            if (fid.equals(str(f, "id"))) {
                return f;
            }
        }
        return null;
    }

    private static int tierOf(JsonObject f) {
        try {
            return f != null && f.has("tier")
                    ? Math.max(1, Math.min(3, f.get("tier").getAsInt()))
                    : 2;
        } catch (Exception e) {
            return 2;
        }
    }

    private static float levelProgress(JsonObject c) {
        try {
            long xp = c.has("xp") ? c.get("xp").getAsLong() : 0;
            var curve = new com.ccnrcom.rp.experience.LevelCurve(
                    com.ccnrcom.rp.config.CCNRRPConfig.LEVEL_BASE.get(),
                    com.ccnrcom.rp.config.CCNRRPConfig.LEVEL_POW.get());
            int lv = curve.level(xp);
            long cur = curve.xpForLevel(lv);
            long next = curve.xpForLevel(lv + 1);
            if (next <= cur) {
                return 0f;
            }
            return (float) Math.max(0.0, Math.min(1.0, (double) (xp - cur) / (next - cur)));
        } catch (Exception e) {
            return 0f;
        }
    }

    private static int parseColor(String hex, int def) {
        if (hex == null || !hex.startsWith("#") || hex.length() != 7) {
            return def;
        }
        try {
            return 0xFF000000 | Integer.parseInt(hex.substring(1), 16);
        } catch (Exception e) {
            return def;
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
