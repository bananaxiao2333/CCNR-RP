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
 * CCNR-RP 管理器（管理员，需权限节点）—— 三页签：
 * 设置（入服规则）/ 职业 CRUD / 阵营 CRUD。全部写入 config/ccnr_rp/factions.json + settings.json。
 * 从 K 面板页眉「管理」进入；仅管理员可修改（服务端二次校验）。
 */
public class RpAdminScreen extends Screen {

    private static RpAdminScreen open;

    private static final int TAB_SETTINGS = 0;
    private static final int TAB_PROFESSION = 1;
    private static final int TAB_FACTION = 2;

    private int tab = TAB_SETTINGS;
    private int px1, py1, px2, py2;
    private int listX1, listX2, listY1, listY2;
    private final List<int[]> rowBounds = new ArrayList<>();
    private int closeX1, closeY1, closeX2, closeY2;
    private String notice = "";
    private long noticeUntil = 0;
    private int scroll = 0;

    private String selProfId = "";
    private String selFactionId = "";
    private int factionIdx = 0;
    private int iconIdx = 0;
    private int tierIdx = 1;
    private boolean selfDeploy = false;

    private EditBox idBox;
    private EditBox nameBox;
    private EditBox colorBox;
    private EditBox descBox;
    private EditBox musicBox;
    private EditBox profileBox;

    private static final String[] ICONS = {"hex", "shield", "claw", "storm", "eye", "target", "cross", "gear"};
    private static final String[] TABS = {
        "ccnr_rp.gui.admin.tab.settings", "ccnr_rp.gui.admin.tab.profession", "ccnr_rp.gui.admin.tab.faction"
    };

    public RpAdminScreen() {
        super(Component.translatable("ccnr_rp.gui.admin.title"));
    }

    public static void refreshIfOpen() {
        if (open != null) {
            open.rebuild();
        }
    }

    @Override
    protected void init() {
        open = this;
        int pw = Math.max(520, Math.min(width - 40, 760));
        int ph = Math.max(340, Math.min(height - 60, 470));
        px1 = (width - pw) / 2;
        py1 = (height - ph) / 2;
        px2 = px1 + pw;
        py2 = py1 + ph;
        closeX1 = px2 - 26;
        closeY1 = py1 + 4;
        closeX2 = px2 - 8;
        closeY2 = py1 + 22;
        listX1 = px1 + 12;
        listX2 = listX1 + Math.min(240, (px2 - px1) * 34 / 100);
        listY1 = py1 + 76;
        listY2 = py2 - 60;
        RpChannels.sendToServer(new RpPackets.ManagerRequestC2S());
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        rowBounds.clear();
        int tabW = 88;
        int tx = px1 + 12;
        for (int i = 0; i < 3; i++) {
            int x = tx + i * (tabW + 6);
            rowBounds.add(new int[] {x, py1 + 42, x + tabW, py1 + 62});
        }
        if (tab == TAB_SETTINGS) {
            int y = py1 + 80;
            for (int i = 0; i < 3; i++) {
                rowBounds.add(new int[] {px1 + 12, y, px2 - 12, y + 50});
                y += 56;
            }
        } else if (tab == TAB_PROFESSION) {
            List<JsonObject> profs = ClientCharacterState.professions();
            int rowH = 20;
            int maxVisible = Math.max(1, (listY2 - listY1) / rowH);
            int off = Math.min(scroll, Math.max(0, profs.size() - maxVisible));
            for (int i = 0; i < profs.size() && i < maxVisible; i++) {
                rowBounds.add(new int[] {listX1, listY1 + i * rowH, listX2, listY1 + (i + 1) * rowH - 1});
            }
        } else {
            List<JsonObject> facs = ClientCharacterState.factions();
            int rowH = 22;
            for (int i = 0; i < facs.size(); i++) {
                rowBounds.add(new int[] {listX1, listY1 + i * rowH, listX2, listY1 + (i + 1) * rowH - 1});
            }
        }
        buildForm();
    }

    private void buildForm() {
        if (tab == TAB_PROFESSION) {
            buildProfessionForm();
        } else if (tab == TAB_FACTION) {
            buildFactionForm();
        }
    }

    // ---------- 职业表单 ----------

    private void buildProfessionForm() {
        int x = listX2 + 10;
        int w = px2 - 12 - x;
        int y = py1 + 76;
        JsonObject prof = selProf();
        String id = prof == null ? "" : str(prof, "id");
        boolean edit = !id.isBlank();
        idBox = mkBox(x, y, w, "ccnr_rp.gui.admin.field.id", id, !edit);
        y += 22;
        nameBox = mkBox(x, y, w, "ccnr_rp.gui.admin.field.name", prof == null ? "" : str(prof, "name"), false);
        y += 22;
        int bw2 = (w - 4) / 2;
        addRenderableWidget(RpButton.secondary(x, y, bw2, 18, Component.literal(factionCycleLabel()), b -> {
            factionIdx = (factionIdx + 1)
                    % Math.max(1, ClientCharacterState.factions().size());
            rebuild();
        }));
        addRenderableWidget(RpButton.secondary(
                x + bw2 + 4,
                y,
                bw2,
                18,
                Component.literal(Component.translatable(
                                selfDeploy ? "ccnr_rp.gui.admin.value.on" : "ccnr_rp.gui.admin.value.off")
                        .getString()),
                b -> {
                    selfDeploy = !selfDeploy;
                    rebuild();
                }));
        y += 22;
        musicBox = mkBox(x, y, w, "ccnr_rp.gui.admin.field.music", prof == null ? "" : str(prof, "music"), false);
        y += 22;
        profileBox = mkBox(x, y, w, "ccnr_rp.gui.admin.field.profile", prof == null ? "" : str(prof, "profile"), false);
        y += 30;
        int bw3 = Math.max(60, w / 4);
        addRenderableWidget(RpButton.primary(
                x, y, bw3, 20, Component.translatable("ccnr_rp.gui.admin.crud.save"), b -> saveProfession(edit)));
        addRenderableWidget(
                RpButton.danger(x + bw3 + 4, y, bw3, 20, Component.translatable("ccnr_rp.gui.admin.crud.delete"), b -> {
                    String sel = idBox.getValue();
                    if (sel.isBlank()) {
                        notice = "职业不存在";
                        return;
                    }
                    JsonObject del = payload();
                    del.addProperty("id", sel);
                    sendCrud("profession", "delete", del);
                }));
        addRenderableWidget(RpButton.secondary(
                x + (bw3 + 4) * 2, y, bw3, 20, Component.translatable("ccnr_rp.gui.admin.crud.new"), b -> {
                    selProfId = "";
                    factionIdx = 0;
                    iconIdx = 0;
                    tierIdx = 1;
                    selfDeploy = false;
                    rebuild();
                }));
    }

    private JsonObject selProf() {
        for (JsonObject p : ClientCharacterState.professions()) {
            if (str(p, "id").equals(selProfId)) {
                return p;
            }
        }
        return null;
    }

    private String factionCycleLabel() {
        List<JsonObject> facs = ClientCharacterState.factions();
        if (facs.isEmpty()) {
            return "阵营: ?";
        }
        JsonObject f = facs.get(factionIdx % facs.size());
        return Component.translatable("ccnr_rp.gui.character.faction", str(f, "name"))
                .getString();
    }

    private void saveProfession(boolean edit) {
        JsonObject p = payload();
        p.addProperty("id", idBox.getValue());
        p.addProperty("name", nameBox.getValue());
        p.addProperty("factionId", currentFactionId());
        p.addProperty("selfDeploy", selfDeploy);
        p.addProperty("music", musicBox.getValue());
        p.addProperty("profile", profileBox.getValue());
        sendCrud("profession", edit ? "update" : "create", p);
    }

    private String currentFactionId() {
        List<JsonObject> facs = ClientCharacterState.factions();
        return facs.isEmpty() ? "" : str(facs.get(factionIdx % facs.size()), "id");
    }

    // ---------- 阵营表单 ----------

    private void buildFactionForm() {
        int x = listX2 + 10;
        int w = px2 - 12 - x;
        int y = py1 + 76;
        JsonObject fac = selFaction();
        String id = fac == null ? "" : str(fac, "id");
        boolean edit = !id.isBlank();
        idBox = mkBox(x, y, w, "ccnr_rp.gui.admin.field.id", id, !edit);
        y += 22;
        nameBox = mkBox(x, y, w, "ccnr_rp.gui.admin.field.name", fac == null ? "" : str(fac, "name"), false);
        y += 22;
        colorBox = mkBox(x, y, w, "ccnr_rp.gui.admin.field.color", fac == null ? "#FFFFFF" : str(fac, "color"), false);
        y += 22;
        int bw2 = (w - 4) / 2;
        addRenderableWidget(RpButton.secondary(x, y, bw2, 18, Component.literal("图标: " + ICONS[iconIdx]), b -> {
            iconIdx = (iconIdx + 1) % ICONS.length;
            rebuild();
        }));
        addRenderableWidget(
                RpButton.secondary(x + bw2 + 4, y, bw2, 18, Component.literal("等级: " + (tierIdx + 1)), b -> {
                    tierIdx = (tierIdx + 1) % 3;
                    rebuild();
                }));
        y += 22;
        descBox = mkBox(x, y, w, "ccnr_rp.gui.admin.field.desc", fac == null ? "" : str(fac, "description"), false);
        y += 30;
        int bw3 = Math.max(60, w / 4);
        addRenderableWidget(RpButton.primary(
                x, y, bw3, 20, Component.translatable("ccnr_rp.gui.admin.crud.save"), b -> saveFaction(edit)));
        addRenderableWidget(
                RpButton.danger(x + bw3 + 4, y, bw3, 20, Component.translatable("ccnr_rp.gui.admin.crud.delete"), b -> {
                    String sel = idBox.getValue();
                    if (sel.isBlank()) {
                        notice = "阵营不存在";
                        return;
                    }
                    JsonObject del = payload();
                    del.addProperty("id", sel);
                    sendCrud("faction", "delete", del);
                }));
        addRenderableWidget(RpButton.secondary(
                x + (bw3 + 4) * 2, y, bw3, 20, Component.translatable("ccnr_rp.gui.admin.crud.new"), b -> {
                    selFactionId = "";
                    iconIdx = 0;
                    tierIdx = 1;
                    rebuild();
                }));
    }

    private JsonObject selFaction() {
        for (JsonObject f : ClientCharacterState.factions()) {
            if (str(f, "id").equals(selFactionId)) {
                return f;
            }
        }
        return null;
    }

    private void saveFaction(boolean edit) {
        JsonObject p = payload();
        p.addProperty("id", idBox.getValue());
        p.addProperty("name", nameBox.getValue());
        p.addProperty("color", colorBox.getValue());
        p.addProperty("description", descBox.getValue());
        p.addProperty("icon", ICONS[iconIdx]);
        p.addProperty("tier", tierIdx + 1);
        sendCrud("faction", edit ? "update" : "create", p);
    }

    // ---------- 通用 ----------

    private EditBox mkBox(int x, int y, int w, String key, String value, boolean locked) {
        EditBox box = new EditBox(font, x, y, w, 18, Component.translatable(key));
        box.setMaxLength(512);
        box.setValue(value == null ? "" : value);
        box.setTextColor(RpTheme.CYAN);
        box.setEditable(!locked);
        addRenderableWidget(box);
        return box;
    }

    private static JsonObject payload() {
        return new JsonObject();
    }

    private void sendCrud(String kind, String action, JsonObject payload) {
        RpChannels.sendToServer(new RpPackets.ManagerCrudC2S(kind, action, payload.toString()));
    }

    private boolean value(String key) {
        return ClientCharacterState.settingBool(key, true);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        if (mx >= closeX1 && mx <= closeX2 && my >= closeY1 && my <= closeY2) {
            onClose();
            return true;
        }
        for (int i = 0; i < 3; i++) {
            int[] b = rowBounds.get(i);
            if (mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3]) {
                tab = i;
                scroll = 0;
                selProfId = "";
                selFactionId = "";
                rebuild();
                return true;
            }
        }
        if (!ClientCharacterState.isAdmin() && tab != TAB_SETTINGS) {
            notice = Component.translatable("ccnr_rp.gui.admin.no_perm").getString();
            return true;
        }
        for (int i = 3; i < rowBounds.size(); i++) {
            int[] b = rowBounds.get(i);
            if (mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3]) {
                if (tab == TAB_SETTINGS) {
                    if (!ClientCharacterState.isAdmin()) {
                        notice = Component.translatable("ccnr_rp.gui.admin.no_perm")
                                .getString();
                        return true;
                    }
                    String key = SETTING_KEYS[i - 3];
                    RpChannels.sendToServer(new RpPackets.ManagerSetC2S(key, String.valueOf(!value(key))));
                } else if (tab == TAB_PROFESSION) {
                    JsonObject p = visibleProfession(i - 3);
                    if (p != null) {
                        selectProfession(p);
                    }
                } else {
                    JsonObject f = ClientCharacterState.factions().get(i - 3);
                    if (f != null) {
                        selectFaction(f);
                    }
                }
                return true;
            }
        }
        return false;
    }

    private static final String[] SETTING_KEYS = {"forceObserving", "openPanelOnJoin", "forceRetain"};
    private static final String[] SETTING_TITLES = {
        "ccnr_rp.gui.admin.setting.force_observing",
        "ccnr_rp.gui.admin.setting.open_panel",
        "ccnr_rp.gui.admin.setting.force_retain"
    };
    private static final String[] SETTING_DESCS = {
        "ccnr_rp.gui.admin.setting.force_observing.desc",
        "ccnr_rp.gui.admin.setting.open_panel.desc",
        "ccnr_rp.gui.admin.setting.force_retain.desc"
    };

    private JsonObject visibleProfession(int i) {
        List<JsonObject> profs = ClientCharacterState.professions();
        int rowH = 20;
        int maxVisible = Math.max(1, (listY2 - listY1) / rowH);
        int off = Math.min(scroll, Math.max(0, profs.size() - maxVisible));
        int idx = off + i;
        return idx < profs.size() ? profs.get(idx) : null;
    }

    private void selectProfession(JsonObject p) {
        selProfId = str(p, "id");
        String fid = str(p, "factionId");
        List<JsonObject> facs = ClientCharacterState.factions();
        for (int i = 0; i < facs.size(); i++) {
            if (str(facs.get(i), "id").equals(fid)) {
                factionIdx = i;
                break;
            }
        }
        selfDeploy = p.has("selfDeploy") && p.get("selfDeploy").getAsBoolean();
        rebuild();
    }

    private void selectFaction(JsonObject f) {
        selFactionId = str(f, "id");
        String icon = str(f, "icon");
        for (int i = 0; i < ICONS.length; i++) {
            if (ICONS[i].equals(icon)) {
                iconIdx = i;
                break;
            }
        }
        tierIdx = Math.max(0, Math.min(2, tierOf(f) - 1));
        rebuild();
    }

    private static int tierOf(JsonObject f) {
        try {
            return f.has("tier") ? f.get("tier").getAsInt() : 2;
        } catch (Exception e) {
            return 2;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (tab == TAB_PROFESSION) {
            scroll = (int) Math.max(0, scroll - delta / 8);
            rebuild();
        }
        return true;
    }

    // ---------- 渲染 ----------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        RpTheme.terminalPanel(g, px1, py1, px2, py2, RpTheme.RADIUS_LARGE);
        g.drawString(font, title.getString().toUpperCase(java.util.Locale.ROOT), px1 + 12, py1 + 8, RpTheme.CYAN, true);
        boolean admin = ClientCharacterState.isAdmin();
        g.drawString(
                font,
                admin
                        ? "● ADMIN"
                        : "● "
                                + Component.translatable("ccnr_rp.gui.admin.no_perm")
                                        .getString(),
                px1 + 12 + font.width(title.getString()) + 14,
                py1 + 10,
                admin ? RpTheme.GOLD : RpTheme.RED,
                true);
        boolean hover = mouseX >= closeX1 && mouseX <= closeX2 && mouseY >= closeY1 && mouseY <= closeY2;
        if (hover) {
            g.fill(closeX1 - 2, closeY1 - 1, closeX2 + 2, closeY2 + 1, 0xE66F1613);
        }
        g.drawString(
                font, "X", (closeX1 + closeX2) / 2 - 2, closeY1 + 4, hover ? 0xFFFFFFFF : RpTheme.TEXT_SECONDARY, true);
        g.fill(px1 + 8, py1 + 26, px2 - 8, py1 + 27, RpTheme.CYAN_DIM);

        for (int i = 0; i < 3; i++) {
            int[] b = rowBounds.get(i);
            boolean sel = tab == i;
            boolean hov = mouseX >= b[0] && mouseX <= b[2] && mouseY >= b[1] && mouseY <= b[3];
            if (sel) {
                RpTheme.selectedBar(g, b[0], b[1], b[2], b[3], 4f);
            } else {
                RpRoundRect.outlined(
                        g,
                        b[0],
                        b[1],
                        b[2],
                        b[3],
                        4f,
                        hov ? RpTheme.PANEL_BORDER_BRIGHT : RpTheme.PANEL_BORDER,
                        hov ? RpTheme.PANEL_BG_ALT : RpTheme.PANEL_BG);
            }
            g.drawCenteredString(
                    font,
                    Component.translatable(TABS[i]).getString(),
                    (b[0] + b[2]) / 2,
                    b[1] + 6,
                    sel ? 0xFFFFFFFF : RpTheme.TEXT_SECONDARY);
        }

        if (tab == TAB_SETTINGS) {
            renderSettings(g, mouseX, mouseY);
        } else if (tab == TAB_PROFESSION) {
            renderProfessionList(g, mouseX, mouseY);
        } else {
            renderFactionList(g, mouseX, mouseY);
        }
        if (!notice.isBlank()) {
            g.drawCenteredString(font, "[ 系统 ] " + notice, (px1 + px2) / 2, py2 - 46, RpTheme.RED_LINE);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void renderSettings(GuiGraphics g, int mouseX, int mouseY) {
        for (int i = 0; i < 3; i++) {
            int[] b = rowBounds.get(3 + i);
            boolean on = value(SETTING_KEYS[i]);
            boolean hoverRow = mouseX >= b[0] && mouseX <= b[2] && mouseY >= b[1] && mouseY <= b[3];
            RpRoundRect.outlined(
                    g,
                    b[0],
                    b[1],
                    b[2],
                    b[3],
                    6f,
                    hoverRow && ClientCharacterState.isAdmin() ? RpTheme.PANEL_BORDER_BRIGHT : RpTheme.PANEL_BORDER,
                    hoverRow ? RpTheme.PANEL_BG_ALT : RpTheme.PANEL_BG);
            g.drawString(
                    font,
                    Component.translatable(SETTING_TITLES[i]).getString(),
                    b[0] + 10,
                    b[1] + 8,
                    on ? RpTheme.CYAN : RpTheme.TEXT_PRIMARY,
                    true);
            g.drawString(
                    font, Component.translatable(SETTING_DESCS[i]).getString(), b[0] + 10, b[1] + 24, RpTheme.TEXT_DIM);
            drawSwitch(g, b[2] - 60, b[1] + 18, on);
        }
    }

    private void drawSwitch(GuiGraphics g, int sx, int sy, boolean on) {
        int sw = 46;
        RpRoundRect.outlined(
                g,
                sx,
                sy,
                sx + sw,
                sy + 14,
                3f,
                on ? RpTheme.CYAN : RpTheme.PANEL_BORDER,
                on ? 0xCC0F2A33 : 0xCC10161B);
        if (on) {
            g.fill(sx + sw / 2 + 2, sy + 3, sx + sw - 3, sy + 11, RpTheme.CYAN);
        } else {
            g.fill(sx + 3, sy + 3, sx + sw / 2 - 2, sy + 11, RpTheme.TEXT_DIM);
        }
        String label = Component.translatable(on ? "ccnr_rp.gui.admin.value.on" : "ccnr_rp.gui.admin.value.off")
                .getString();
        g.drawString(font, label, sx - font.width(label) - 8, sy + 3, on ? RpTheme.CYAN : RpTheme.TEXT_DIM, true);
    }

    private void renderProfessionList(GuiGraphics g, int mouseX, int mouseY) {
        List<JsonObject> profs = ClientCharacterState.professions();
        int rowH = 20;
        int maxVisible = Math.max(1, (listY2 - listY1) / rowH);
        int off = Math.min(scroll, Math.max(0, profs.size() - maxVisible));
        RpRoundRect.outlined(
                g, listX1 - 2, listY1 - 4, listX2 + 2, listY2 + 2, 4f, RpTheme.PANEL_BORDER, RpTheme.PANEL_BG_EVEN);
        g.drawString(font, "职业(" + profs.size() + ")", listX1 + 4, listY1 - 4, RpTheme.TEXT_DIM);
        for (int i = 0; i < profs.size() && i < maxVisible; i++) {
            JsonObject p = profs.get(off + i);
            int[] b = rowBounds.get(3 + i);
            boolean sel = str(p, "id").equals(selProfId);
            boolean hov = mouseX >= b[0] && mouseX <= b[2] && mouseY >= b[1] && mouseY <= b[3];
            if (sel) {
                RpTheme.selectedBar(g, b[0], b[1], b[2], b[3], 3f);
            } else {
                g.fill(
                        b[0],
                        b[1],
                        b[2],
                        b[3] + 1,
                        hov ? RpTheme.PANEL_BG_ALT : (i % 2 == 0 ? RpTheme.PANEL_BG : 0x00000000));
            }
            int fx = b[0] + 5;
            g.drawString(font, str(p, "name"), fx, b[1] + 1, sel ? 0xFFFFFFFF : RpTheme.TEXT_PRIMARY, true);
            g.drawString(font, str(p, "id"), fx, b[1] + 11, sel ? 0xFFFFFFFF : RpTheme.TEXT_DIM, true);
        }
    }

    private void renderFactionList(GuiGraphics g, int mouseX, int mouseY) {
        List<JsonObject> facs = ClientCharacterState.factions();
        int rowH = 22;
        RpRoundRect.outlined(
                g, listX1 - 2, listY1 - 4, listX2 + 2, listY2 + 2, 4f, RpTheme.PANEL_BORDER, RpTheme.PANEL_BG_EVEN);
        g.drawString(font, "阵营(" + facs.size() + ")", listX1 + 4, listY1 - 4, RpTheme.TEXT_DIM);
        for (int i = 0; i < facs.size(); i++) {
            JsonObject f = facs.get(i);
            int[] b = rowBounds.get(3 + i);
            boolean sel = str(f, "id").equals(selFactionId);
            boolean hov = mouseX >= b[0] && mouseX <= b[2] && mouseY >= b[1] && mouseY <= b[3];
            if (sel) {
                RpTheme.selectedBar(g, b[0], b[1], b[2], b[3], 3f);
            } else {
                g.fill(
                        b[0],
                        b[1],
                        b[2],
                        b[3] + 1,
                        hov ? RpTheme.PANEL_BG_ALT : (i % 2 == 0 ? RpTheme.PANEL_BG : 0x00000000));
            }
            g.drawString(font, str(f, "name"), b[0] + 5, b[1] + 2, sel ? 0xFFFFFFFF : RpTheme.TEXT_PRIMARY, true);
            g.drawString(font, str(f, "id"), b[0] + 5, b[1] + 12, sel ? 0xFFFFFFFF : RpTheme.TEXT_DIM, true);
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
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
