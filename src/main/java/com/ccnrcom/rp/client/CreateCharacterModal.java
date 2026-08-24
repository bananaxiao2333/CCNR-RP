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
 * 创建角色弹窗：名字输入 + 阵营/职业循环选择 + 创建/取消。
 * 新建入口从 K 面板底部表单改为弹窗（避免挤占主界面空间）。
 */
public final class CreateCharacterModal extends Screen {

    private int x1, y1, x2, y2;
    private int closeX1, closeY1, closeX2, closeY2;
    private EditBox nameBox;
    private int factionIdx = 0;
    private int professionIdx = 0;
    private String notice = "";

    public CreateCharacterModal() {
        super(Component.translatable("ccnr_rp.gui.character.create.title"));
    }

    @Override
    protected void init() {
        int pw = Math.max(360, Math.min(width * 55 / 100, 460));
        int ph = 240;
        x1 = (width - pw) / 2;
        y1 = (height - ph) / 2;
        x2 = x1 + pw;
        y2 = y1 + ph;
        closeX1 = x2 - 26;
        closeY1 = y1 + 4;
        closeX2 = x2 - 8;
        closeY2 = y1 + 22;
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        int x = x1 + 16;
        int w = x2 - x1 - 32;
        int y = y1 + 36;
        // 名字
        addRenderableWidget(RpButton.secondary(
                x,
                y,
                Math.max(60, w / 4),
                18,
                Component.translatable("ccnr_rp.gui.character.create.name_hint"),
                b -> {}));
        nameBox = new EditBox(
                font,
                x + Math.max(60, w / 4) + 6,
                y,
                w - Math.max(60, w / 4) - 6,
                18,
                Component.translatable("ccnr_rp.gui.character.name"));
        nameBox.setMaxLength(32);
        nameBox.setFilter(s -> s.matches("[\\p{L} ]*"));
        nameBox.setTextColor(RpTheme.CYAN);
        addRenderableWidget(nameBox);
        y += 26;
        // 阵营/职业循环
        List<String> facs = factionIds();
        List<String> profs = matchingProfessions();
        String fLabel =
                facs.isEmpty() ? "阵营: ?" : "阵营: " + factionName(facs.get(Math.min(factionIdx, facs.size() - 1)));
        String pLabel = profs.isEmpty()
                ? "职业: (暂无配置)"
                : "职业: " + professionName(profs.get(Math.min(professionIdx, profs.size() - 1)));
        addRenderableWidget(RpButton.secondary(x, y, w / 2, 18, Component.literal(fLabel), b -> {
            if (!facs.isEmpty()) {
                factionIdx = (factionIdx + 1) % facs.size();
                professionIdx = 0;
                rebuild();
            }
        }));
        addRenderableWidget(RpButton.secondary(x + w / 2 + 4, y, w / 2 - 4, 18, Component.literal(pLabel), b -> {
            List<String> list = matchingProfessions();
            if (list.isEmpty()) {
                notice = "职业: (暂无配置)";
                return;
            }
            professionIdx = (professionIdx + 1) % list.size();
            rebuild();
        }));
        y += 26;
        // 创建/取消
        addRenderableWidget(RpButton.primary(
                x, y, (w - 4) / 2, 20, Component.translatable("ccnr_rp.gui.character.create"), b -> create()));
        addRenderableWidget(RpButton.secondary(
                x + (w - 4) / 2 + 4,
                y,
                (w - 4) / 2,
                20,
                Component.translatable("ccnr_rp.gui.admin.seq.cancel"),
                b -> closeModal()));
    }

    private void create() {
        List<String> ids = factionIds();
        if (ids.isEmpty()) {
            notice = "阵营配置为空";
            return;
        }
        if (nameBox == null || nameBox.getValue() == null || !nameBox.getValue().matches("[\\p{L} ]+")) {
            notice = "请先输入名字（仅文字与空格）";
            return;
        }
        String f = ids.get(Math.min(factionIdx, ids.size() - 1));
        List<String> list = matchingProfessions();
        String p = list.isEmpty() ? "" : list.get(Math.min(professionIdx, list.size() - 1));
        RpChannels.sendToServer(new RpPackets.CharacterCreateC2S(nameBox.getValue(), f, p, ""));
        notice = "";
        closeModal();
    }

    private void closeModal() {
        net.minecraft.client.Minecraft.getInstance().setScreen(new CharacterManagementScreen());
    }

    // ---------- 数据 ----------

    private List<String> factionIds() {
        List<String> out = new ArrayList<>();
        for (JsonObject f : ClientCharacterState.factions()) {
            out.add(str(f, "id"));
        }
        return out;
    }

    private List<String> matchingProfessions() {
        List<String> ids = factionIds();
        if (ids.isEmpty()) {
            return List.of();
        }
        String fid = ids.get(Math.min(factionIdx, ids.size() - 1));
        List<String> out = new ArrayList<>();
        for (JsonObject p : ClientCharacterState.professions()) {
            if (str(p, "factionId").equals(fid)) {
                out.add(str(p, "id"));
            }
        }
        return out;
    }

    private String factionName(String id) {
        for (JsonObject f : ClientCharacterState.factions()) {
            if (str(f, "id").equals(id)) {
                return str(f, "name");
            }
        }
        return id;
    }

    private String professionName(String id) {
        for (JsonObject p : ClientCharacterState.professions()) {
            if (str(p, "id").equals(id)) {
                return str(p, "name");
            }
        }
        return id;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    // ---------- 渲染/交互 ----------

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
                x1 + 16,
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
        if (!notice.isBlank()) {
            g.drawCenteredString(font, notice, (x1 + x2) / 2, y2 - 26, RpTheme.RED_LINE);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        if (mx >= closeX1 && mx <= closeX2 && my >= closeY1 && my <= closeY2) {
            closeModal();
            return true;
        }
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
