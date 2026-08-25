/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.ccnrcom.rp.network.RpChannels;
import com.ccnrcom.rp.network.RpPackets;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** 通用复活波选岗菜单：显示自己可复活的观察角色（人物渲染 + 名字/职业/部门），点击选岗上岗。 */
public final class RecruitPickScreen extends Screen {
    private final String offerId;
    private final List<JsonObject> chars = new ArrayList<>();
    private final List<int[]> rowBounds = new ArrayList<>();
    private int x1, y1, x2, y2;

    public RecruitPickScreen(String offerId) {
        super(Component.translatable("ccnr_rp.spawn.pick.title"));
        this.offerId = offerId;
        for (JsonObject c : ClientCharacterState.list()) {
            if ("observing".equals(str(c, "status"))) {
                chars.add(c);
            }
        }
    }

    @Override
    protected void init() {
        int pw = Math.max(320, Math.min(width - 60, 420));
        int ph = Math.max(240, Math.min(height - 80, 380));
        x1 = (width - pw) / 2;
        y1 = (height - ph) / 2;
        x2 = x1 + pw;
        y2 = y1 + ph;
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        rowBounds.clear();
        int x = x1 + 12;
        int w = x2 - x1 - 24;
        int y = y1 + 40;
        int rowH = 52;
        for (int i = 0; i < chars.size(); i++) {
            rowBounds.add(new int[] {x, y, x + w, y + rowH - 4});
            y += rowH;
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xAA000000);
        RpTheme.terminalPanel(g, x1, y1, x2, y2, RpTheme.RADIUS_LARGE);
        g.drawString(
                font,
                Component.translatable("ccnr_rp.spawn.pick.title").getString().toUpperCase(java.util.Locale.ROOT),
                x1 + 14,
                y1 + 10,
                RpTheme.CYAN,
                true);
        g.fill(x1 + 8, y1 + 28, x2 - 8, y1 + 29, RpTheme.CYAN_DIM);
        if (chars.isEmpty()) {
            g.drawString(
                    font,
                    Component.translatable("ccnr_rp.spawn.pick.empty").getString(),
                    x1 + 14,
                    y1 + 60,
                    RpTheme.TEXT_DIM);
        }
        for (int i = 0; i < rowBounds.size(); i++) {
            int[] b = rowBounds.get(i);
            boolean hover = mouseX >= b[0] && mouseX <= b[2] && mouseY >= b[1] && mouseY <= b[3];
            RpRoundRect.outlined(
                    g,
                    b[0],
                    b[1],
                    b[2],
                    b[3],
                    6f,
                    hover ? RpTheme.PANEL_BORDER_BRIGHT : RpTheme.PANEL_BORDER,
                    hover ? RpTheme.PANEL_BG_ALT : (i % 2 == 0 ? RpTheme.PANEL_BG : RpTheme.PANEL_BG_EVEN));
            JsonObject c = chars.get(i);
            // 人物立绘（战术装备预览同款：水平跟随鼠标、俯仰锁定，带职位装备）
            int ax = b[0] + 8;
            int ay = b[1] + 8;
            CharacterPreview.renderPortrait(g, ax + 18, ay + 18, 18, mouseX, str(c, "id"));
            // 名字 / 职业 · 部门（部门前画阵营徽章）
            g.drawString(font, str(c, "name"), ax + 44, b[1] + 8, RpTheme.TEXT_PRIMARY, true);
            RpIcons.factionBadge(g, ax + 50, b[1] + 31, 7, factionOf(str(c, "factionId")), false);
            g.drawString(
                    font,
                    professionName(str(c, "professionId")) + " · " + factionName(str(c, "factionId")),
                    ax + 60,
                    b[1] + 24,
                    RpTheme.TEXT_SECONDARY);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        for (int i = 0; i < rowBounds.size(); i++) {
            int[] b = rowBounds.get(i);
            if (mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3]) {
                JsonObject c = chars.get(i);
                RpChannels.sendToServer(new RpPackets.RecruitPickCharacterC2S(offerId, str(c, "id")));
                onClose();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private String professionName(String pid) {
        for (JsonObject p : ClientCharacterState.professions()) {
            if (pid.equals(str(p, "id"))) {
                String n = str(p, "name");
                return n.isBlank() ? pid : n;
            }
        }
        return pid;
    }

    private String factionName(String fid) {
        for (JsonObject f : ClientCharacterState.factions()) {
            if (fid.equals(str(f, "id"))) {
                String n = str(f, "name");
                return n.isBlank() ? fid : n;
            }
        }
        return fid;
    }

    private JsonObject factionOf(String fid) {
        for (JsonObject f : ClientCharacterState.factions()) {
            if (fid.equals(str(f, "id"))) {
                return f;
            }
        }
        return null;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
