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
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 创建角色弹窗：左侧阵营列表（行背景=阵营主题色）+ 右侧职业列表（随阵营联动），
 * 两栏各自滚动（滚轮），底部名字输入 + 创建/取消。
 */
public final class CreateCharacterModal extends Screen {

    private int x1, y1, x2, y2;
    private int closeX1, closeY1, closeX2, closeY2;
    private EditBox nameBox;
    private int selFactionIdx = 0;
    private int selProfIdx = 0;
    private int facScroll = 0;
    private int profScroll = 0;
    private String notice = "";

    private int fX1, fY1, fX2, fY2;
    private int pX1, pY1, pX2, pY2;
    private int rowH = 26;
    private int sbW = 6;

    public CreateCharacterModal() {
        super(Component.translatable("ccnr_rp.gui.character.create.title"));
    }

    @Override
    protected void init() {
        int pw = Math.max(480, Math.min(width * 62 / 100, 600));
        int ph = Math.max(300, Math.min(height - 60, 380));
        x1 = (width - pw) / 2;
        y1 = (height - ph) / 2;
        x2 = x1 + pw;
        y2 = y1 + ph;
        closeX1 = x2 - 26;
        closeY1 = y1 + 4;
        closeX2 = x2 - 8;
        closeY2 = y1 + 22;
        int pad = 14;
        fX1 = x1 + pad;
        fY1 = y1 + 40;
        pX1 = fX1 + (int) ((x2 - x1 - pad * 2) * 0.47);
        pY1 = fY1;
        fX2 = pX1 - 8;
        pX2 = x2 - pad;
        fY2 = y2 - 58;
        pY2 = fY2;
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        int x = x1 + 14;
        int w = x2 - x1 - 28;
        int y = pY2 + 8;
        int hintW = Math.max(60, w / 5);
        addRenderableWidget(RpButton.secondary(
                x, y, hintW, 18, Component.translatable("ccnr_rp.gui.character.create.name_hint"), b -> {}));
        nameBox = new EditBox(
                font, x + hintW + 6, y, w - hintW - 6, 18, Component.translatable("ccnr_rp.gui.character.name"));
        nameBox.setMaxLength(32);
        nameBox.setFilter(s -> s.matches("[\\p{L} ]*"));
        nameBox.setTextColor(RpTheme.CYAN);
        addRenderableWidget(nameBox);
        int by = y + 26;
        addRenderableWidget(RpButton.primary(
                x, by, (w - 4) / 2, 20, Component.translatable("ccnr_rp.gui.character.create"), b -> create()));
        addRenderableWidget(RpButton.secondary(
                x + (w - 4) / 2 + 4,
                by,
                (w - 4) / 2,
                20,
                Component.translatable("ccnr_rp.gui.admin.seq.cancel"),
                b -> closeModal()));
    }

    private void create() {
        List<JsonObject> facs = factions();
        if (facs.isEmpty()) {
            notice = "阵营配置为空";
            return;
        }
        if (nameBox == null || nameBox.getValue() == null || !nameBox.getValue().matches("[\\p{L} ]+")) {
            notice = "请先输入名字（仅文字与空格）";
            return;
        }
        JsonObject f = facs.get(Math.min(selFactionIdx, facs.size() - 1));
        List<JsonObject> profs = professionsOf(str(f, "id"));
        JsonObject p = profs.isEmpty() ? null : profs.get(Math.min(selProfIdx, profs.size() - 1));
        RpChannels.sendToServer(
                new RpPackets.CharacterCreateC2S(nameBox.getValue(), str(f, "id"), p == null ? "" : str(p, "id"), ""));
        notice = "";
        closeModal();
    }

    private void closeModal() {
        net.minecraft.client.Minecraft.getInstance().setScreen(new CharacterManagementScreen());
    }

    // ---------- 数据 ----------

    private List<JsonObject> factions() {
        return new ArrayList<>(ClientCharacterState.factions());
    }

    private List<JsonObject> professionsOf(String factionId) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonObject p : ClientCharacterState.professions()) {
            if (str(p, "factionId").equals(factionId)) {
                out.add(p);
            }
        }
        return out;
    }

    private static int parseColor(String hex, int defaultRgb) {
        if (hex == null) {
            return 0xFF000000 | defaultRgb;
        }
        try {
            String h = hex.trim().replace("#", "");
            if (h.length() == 6) {
                return 0xFF000000 | Integer.parseInt(h, 16);
            }
        } catch (Exception ignored) {
            // fallthrough
        }
        return 0xFF000000 | defaultRgb;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    // ---------- 渲染 ----------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xAA000000);
        RpTheme.terminalPanel(g, x1, y1, x2, y2, RpTheme.RADIUS_LARGE);
        RpBg.draw(g, x1 + 4, y1 + 4, x2 - 4, y2 - 4);
        g.drawString(
                font,
                Component.translatable("ccnr_rp.gui.character.create.title")
                        .getString()
                        .toUpperCase(java.util.Locale.ROOT),
                x1 + 14,
                y1 + 12,
                RpTheme.CYAN,
                true);
        boolean hover = mouseX >= closeX1 && mouseX <= closeX2 && mouseY >= closeY1 && mouseY <= closeY2;
        if (hover) {
            g.fill(closeX1 - 2, closeY1 - 1, closeX2 + 2, closeY2 + 1, 0xE66F1613);
        }
        g.drawString(
                font, "X", (closeX1 + closeX2) / 2 - 2, closeY1 + 4, hover ? 0xFFFFFFFF : RpTheme.TEXT_SECONDARY, true);
        g.fill(x1 + 8, y1 + 28, x2 - 8, y1 + 29, RpTheme.CYAN_DIM);

        List<JsonObject> facs = factions();
        List<JsonObject> profs = facs.isEmpty()
                ? List.of()
                : professionsOf(str(facs.get(Math.min(selFactionIdx, facs.size() - 1)), "id"));

        renderColumn(g, "阵营 · 点击选择", fX1, fY1, fX2, fY2, facs, facScroll, true, mouseX, mouseY);
        renderColumn(g, "职业 · 随阵营联动", pX1, pY1, pX2, pY2, profs, profScroll, false, mouseX, mouseY);

        if (!notice.isBlank()) {
            g.drawCenteredString(font, notice, (x1 + x2) / 2, y2 - 24, RpTheme.RED_LINE);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    private int listX2(int cx1, int listW) {
        return cx1 + 2 + listW;
    }

    private void renderColumn(
            GuiGraphics g,
            String title,
            int cx1,
            int cy1,
            int cx2,
            int cy2,
            List<JsonObject> items,
            int scroll,
            boolean factionColumn,
            int mouseX,
            int mouseY) {
        RpRoundRect.outlined(g, cx1, cy1, cx2, cy2, 6f, RpTheme.PANEL_BORDER, RpTheme.PANEL_BG);
        g.drawString(font, title, cx1 + 6, cy1 - 11, RpTheme.TEXT_DIM);
        int listW = cx2 - cx1 - sbW - 4;
        int listH = cy2 - cy1;
        int maxVisible = Math.max(1, listH / rowH);
        int off = Math.min(scroll, Math.max(0, items.size() - maxVisible));
        for (int i = 0; i < items.size() && i < maxVisible; i++) {
            int ry = cy1 + i * rowH;
            JsonObject item = items.get(off + i);
            boolean sel = factionColumn ? i + off == selFactionIdx : i + off == selProfIdx;
            boolean hov = mouseX >= cx1 && mouseX <= cx2 && mouseY >= ry && mouseY <= ry + rowH;
            int bg = RpTheme.PANEL_BG_ALT;
            if (factionColumn) {
                int col = parseColor(str(item, "color"), RpTheme.CYAN_DIM);
                int base = 0x66000000 | (col & 0xFFFFFF);
                int hoverBg = 0x99000000 | (col & 0xFFFFFF);
                int selBg = 0xE6000000 | (col & 0xFFFFFF);
                bg = sel ? selBg : hov ? hoverBg : base;
            }
            int rx2 = listX2(cx1, listW);
            RpRoundRect.fill(g, cx1 + 2, ry + 1, rx2, ry + rowH - 1, 5f, bg);
            if (sel) {
                g.fill(cx1 + 2, ry + 1, cx1 + 4, ry + rowH - 1, RpTheme.RED);
                RpTheme.cornerBrackets(g, cx1 + 2, ry + 1, rx2, ry + rowH - 1, 3, RpTheme.RED_LINE);
            } else if (hov) {
                RpRoundRect.outlined(g, cx1 + 2, ry + 1, rx2, ry + rowH - 1, 5f, RpTheme.PANEL_BORDER_BRIGHT, bg);
            }
            String name = str(item, "name");
            int maxW = listW - 14;
            if (font.width(name) > maxW) {
                name = font.plainSubstrByWidth(name, maxW - 1) + "…";
            }
            g.drawString(font, name, cx1 + 7, ry + 5, sel ? 0xFFFFFFFF : RpTheme.TEXT_PRIMARY, true);
            String sub = factionColumn ? str(item, "id") : "ID " + str(item, "id");
            g.drawString(font, sub, cx1 + 7, ry + 16, sel ? 0xFFFFFFFF : RpTheme.TEXT_DIM, true);
        }
        if (items.isEmpty()) {
            g.drawString(font, "（暂无配置）", cx1 + 6, cy1 + 8, RpTheme.TEXT_DIM);
        }
        RpScrollbar.draw(g, cx2 - sbW, cy1, cy2, items.size(), maxVisible, off);
    }

    // ---------- 交互 ----------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        if (mx >= closeX1 && mx <= closeX2 && my >= closeY1 && my <= closeY2) {
            closeModal();
            return true;
        }
        if (mx >= fX1 && mx <= fX2 && my >= fY1 && my <= fY2) {
            int maxVisible = Math.max(1, (fY2 - fY1) / rowH);
            int off = Math.min(facScroll, Math.max(0, factions().size() - maxVisible));
            int idx = (int) ((my - fY1) / rowH) + off;
            if (idx >= 0 && idx < factions().size()) {
                selFactionIdx = idx;
                selProfIdx = 0;
                profScroll = 0;
            }
            return true;
        }
        if (mx >= pX1 && mx <= pX2 && my >= pY1 && my <= pY2) {
            List<JsonObject> facs = factions();
            if (!facs.isEmpty()) {
                List<JsonObject> profs = professionsOf(str(facs.get(Math.min(selFactionIdx, facs.size() - 1)), "id"));
                int maxVisible = Math.max(1, (pY2 - pY1) / rowH);
                int off = Math.min(profScroll, Math.max(0, profs.size() - maxVisible));
                int idx = (int) ((my - pY1) / rowH) + off;
                if (idx >= 0 && idx < profs.size()) {
                    selProfIdx = idx;
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= fX1 && mouseX <= fX2 && mouseY >= fY1 && mouseY <= fY2) {
            int maxVisible = Math.max(1, (fY2 - fY1) / rowH);
            facScroll = Math.min(
                    Math.max(0, (int) (facScroll - delta / 12)),
                    Math.max(0, factions().size() - maxVisible));
            return true;
        }
        if (mouseX >= pX1 && mouseX <= pX2 && mouseY >= pY1 && mouseY <= pY2) {
            List<JsonObject> facs = factions();
            if (!facs.isEmpty()) {
                List<JsonObject> profs = professionsOf(str(facs.get(Math.min(selFactionIdx, facs.size() - 1)), "id"));
                int maxVisible = Math.max(1, (pY2 - pY1) / rowH);
                profScroll =
                        Math.min(Math.max(0, (int) (profScroll - delta / 12)), Math.max(0, profs.size() - maxVisible));
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
