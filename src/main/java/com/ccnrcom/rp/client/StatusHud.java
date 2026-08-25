/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 右下角状态栏 HUD：每行 = 方形图标 + 等宽长方形（进度条或文字，由配置决定）。
 * 行：职位（等级进度）/ 阵营（背景=阵营颜色）/ 血量（百分比）。全部可在管理器设置。
 * 非存活（观察模式/死亡，含角色库为空）时套用一个状态：职业=观察者、阵营=观察模式、血量=不适用。
 */
public final class StatusHud {

    private StatusHud() {}

    /** 常驻覆盖层：右下角逐行结算（绿加/红减），无需打开背包即可见。 */
    public static void renderXpOverlay(GuiGraphics g, int w, int h) {
        if (CinematicController.active() || Minecraft.getInstance().player == null) {
            return;
        }
        int rowW = 178;
        int iconS = 18;
        int barH = 16;
        int rowGap = 4;
        int margin = 10;
        int px = w - rowW - margin;
        int py = h - margin - (barH * 3 + rowGap * 2) - 12;
        renderXpLines(g, px + iconS, py - margin, w - px - iconS);
    }

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
        // 用户级身份（v2）：职位/阵营均取自用户档案；征召兵在场时按征召编制显示
        JsonObject conscript = ClientCharacterState.conscript();
        boolean alive = ClientCharacterState.isDeployed();
        boolean conscriptAlive = conscript != null;
        String profName = conscriptAlive
                ? professionNameById(str(conscript, "professionId"))
                : (alive
                        ? professionNameById(ClientCharacterState.userProfessionId())
                        : Component.translatable("ccnr_rp.hud.observer").getString());
        int profColor = (alive || conscriptAlive) ? RpTheme.CYAN : RpTheme.STATUS_OBSERVING;
        JsonObject faction =
                factionById(conscriptAlive ? str(conscript, "factionId") : ClientCharacterState.userFactionId());
        String facName = (alive || conscriptAlive)
                ? (faction == null
                        ? (conscriptAlive ? str(conscript, "factionId") : ClientCharacterState.userFactionId())
                        : str(faction, "name"))
                : Component.translatable("ccnr_rp.hud.observe_mode").getString();
        int facColor = (alive || conscriptAlive)
                ? (faction == null ? 0xFF3D7BFF : parseColor(str(faction, "color"), 0xFF3D7BFF))
                : RpTheme.STATUS_OBSERVING;
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
        // 结算明细：右下角逐行（绿=加分 / 红=减分，如「-10 死亡」），置于状态栏上方
        renderXpLines(g, px + iconS, py - margin, w - px - iconS);

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
                alive ? levelProgress() : 0f,
                profColor);
        // 阵营（背景=阵营颜色）
        rowY += barH + rowGap;
        RpIcons.factionBadge(g, px + iconS / 2, rowY + barH / 2, iconS / 2 - 1, faction, false);
        drawRow(
                g,
                px,
                rowY,
                iconS,
                barW,
                barH,
                facName,
                ClientCharacterState.settingBool("hudFactionText", false),
                alive ? 1.0f : 0f,
                facColor);
        // 血量 / 观察模式（非存活第三行显示“不适用”）
        rowY += barH + rowGap;
        if (!alive) {
            int obsColor = RpTheme.STATUS_OBSERVING;
            RpIcons.slot(g, px + 1, rowY + 1, iconS - 2, "heart", obsColor);
            drawRow(
                    g,
                    px,
                    rowY,
                    iconS,
                    barW,
                    barH,
                    Component.translatable("ccnr_rp.hud.health_na").getString(),
                    true,
                    0f,
                    obsColor);
        } else {
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
        RpRoundRect.outlined(g, bx, y, bx + barW, y + barH, 3f, RpTheme.PANEL_BORDER_BRIGHT, 0xB0383838);
        int fillPx = Math.max(0, Math.min(barW - 2, Math.round(fill * (barW - 2))));
        var font = Minecraft.getInstance().font;
        if (textMode) {
            // 文字模式：底部用颜色细线点缀
            g.fill(bx + 1, y + barH - 3, bx + barW - 1, y + barH - 2, color);
            g.drawString(font, label, bx + 4, y + (barH - 8) / 2 + 1, RpTheme.TEXT_PRIMARY, true);
        } else {
            g.fill(bx + 1, y + 1, bx + 1 + fillPx, y + barH - 1, color);
            String pct = Math.round(fill * 100f) + "%";
            g.drawString(font, pct, bx + (barW - font.width(pct)) / 2, y + (barH - 8) / 2 + 1, 0xFF141414);
        }
    }

    private static String professionNameById(String pid) {
        if (pid == null || pid.isBlank()) {
            return Component.translatable("ccnr_rp.hud.observer").getString();
        }
        for (JsonObject p : ClientCharacterState.professions()) {
            if (pid.equals(str(p, "id"))) {
                String n = str(p, "name");
                return n.isBlank() ? pid : n;
            }
        }
        return pid;
    }

    private static JsonObject factionById(String fid) {
        if (fid == null || fid.isBlank()) {
            return null;
        }
        for (JsonObject f : ClientCharacterState.factions()) {
            if (fid.equals(str(f, "id"))) {
                return f;
            }
        }
        return null;
    }

    /** 结算明细逐行渲染（右下角，绿=加分 / 红=减分，如「-10 死亡」）。 */
    private static void renderXpLines(GuiGraphics g, int x1, int y, int x2) {
        List<String[]> lines = ClientCharacterState.xpLines();
        if (lines.isEmpty()) {
            return;
        }
        var font = Minecraft.getInstance().font;
        int lineH = 14;
        int ny = y - lines.size() * lineH;
        for (String[] p : lines) {
            if (p.length < 3) {
                continue;
            }
            boolean plus = "+".equals(p[0]);
            long value = 0;
            try {
                value = Long.parseLong(p[1]);
            } catch (Exception ignored) {
                // 非数字按 0 展示
            }
            String key = p[2];
            String[] args = p.length > 3 && p[3] != null && !p[3].isBlank() ? p[3].split(",") : new String[0];
            String label;
            if (key.endsWith(".evac")) {
                String m = args.length > 0 ? args[0] : "";
                label = switch (m) {
                    case "DIED" -> Component.translatable("ccnr_rp.xp.line.evac.died")
                            .getString();
                    case "SAFE_RESCUE" -> Component.translatable("ccnr_rp.xp.line.evac.safe")
                            .getString();
                    case "STAY_BEHIND" -> Component.translatable("ccnr_rp.xp.line.evac.stay")
                            .getString();
                    case "OBSERVING_END" -> Component.translatable("ccnr_rp.xp.line.evac.observe")
                            .getString();
                    default -> Component.translatable("ccnr_rp.xp.line.evac").getString();};
            } else {
                try {
                    label = Component.translatable(key, (Object[]) args).getString();
                } catch (Exception e) {
                    label = key;
                }
            }
            String text = (plus ? "+" : "-") + value + " " + label;
            int color = plus ? 0xFF35E07A : 0xFFFF4C4C;
            g.drawString(font, text, x1, ny, color, true);
            ny += lineH;
        }
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

    /** 等级进度：经验随用户走（用户总经验）。 */
    private static float levelProgress() {
        try {
            long xp = ClientCharacterState.userXp();
            var curve = new com.ccnrcom.rp.experience.LevelCurve(
                    ClientCharacterState.levelBase(), ClientCharacterState.levelPow());
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
