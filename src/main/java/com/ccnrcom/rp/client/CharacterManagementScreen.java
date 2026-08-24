/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.ccnrcom.rp.network.RpChannels;
import com.ccnrcom.rp.network.RpPackets;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 角色管理界面 v3——"SCP:NET 机密终端"三栏网格：
 * 左：机构分类（圆形徽章导航，金/蓝/青按等级区分）｜中：角色档案列表｜右：详细资料 + 真 3D 模型预览 + 战术装备槽(头/胸/腿/背)。
 * 色彩：冷暗金属底 / 青色主色 / 正红选中警戒 / 金色徽章。数据与服务端同步（List→Create→Select→Observe→Activate→Deploy→Delete→Skin）。
 */
public class CharacterManagementScreen extends Screen {

    private static CharacterManagementScreen open;

    private final List<JsonObject> chars = new ArrayList<>();
    private final List<JsonObject> factionMeta = new ArrayList<>();
    private final List<JsonObject> professionMeta = new ArrayList<>();
    private final List<int[]> navBounds = new ArrayList<>();
    private final List<int[]> rowBounds = new ArrayList<>();

    private String selectedId = "";
    private String filterFaction = "";
    private int scroll = 0;
    private int factionIndex = 0;
    private int professionIndex = 0;
    private EditBox nameBox;
    private EditBox backgroundBox;
    private EditBox skinPathBox;
    private String notice = "";
    private long noticeUntil = 0;

    // 布局几何
    private int px1, py1, px2, py2;
    private int hdrY1, hdrY2;
    private int bodyY1, bodyY2;
    private int nlX1, nlX2;
    private int mlX1, mlX2;
    private int rlX1, rlX2;
    private int pvY1, pvY2, pvX1, pvX2;
    private int closeX1, closeY1, closeX2, closeY2;

    public CharacterManagementScreen() {
        super(Component.translatable("ccnr_rp.gui.character.title"));
    }

    public static void refreshIfOpen() {
        if (open != null && net.minecraft.client.Minecraft.getInstance() != null) {
            open.reloadData();
        }
    }

    // ---------- 容器 ----------

    @Override
    protected void init() {
        open = this;
        reloadData();
        px1 = 16;
        py1 = 16;
        px2 = width - 16;
        py2 = height - 16;
        hdrY1 = py1 + 4;
        hdrY2 = hdrY1 + 20;
        bodyY1 = hdrY2 + 26;
        bodyY2 = py2 - 8;
        int innerW = px2 - px1 - 16;
        int nlw = Math.max(84, Math.min(220, innerW * 24 / 100));
        int mlw = Math.max(132, Math.min(320, innerW * 30 / 100));
        nlX1 = px1 + 8;
        nlX2 = nlX1 + nlw;
        mlX1 = nlX2 + 8;
        mlX2 = mlX1 + mlw;
        rlX1 = mlX2 + 8;
        rlX2 = px2 - 8;
        int profileH = 54;
        int createY1 = Math.max(bodyY2 - 96, bodyY1 + profileH + 8 + 84 + 62);
        pvX1 = rlX1;
        pvX2 = rlX2;
        pvY1 = bodyY1 + profileH + 8;
        pvY2 = createY1 - 62;
        if (pvY2 - pvY1 > 170) {
            pvY2 = pvY1 + 170;
        }
        if (pvY2 - pvY1 < 84) {
            pvY2 = pvY1 + 84;
        }
        closeX1 = px2 - 28;
        closeY1 = hdrY1 - 1;
        closeX2 = px2 - 10;
        closeY2 = hdrY1 + 17;
        rebuild();
    }

    private void reloadData() {
        chars.clear();
        chars.addAll(ClientCharacterState.list());
        factionMeta.clear();
        factionMeta.addAll(ClientCharacterState.factions());
        professionMeta.clear();
        professionMeta.addAll(ClientCharacterState.professions());
        if (filterFaction.isEmpty()
                || factionMeta.stream().noneMatch(f -> f.get("id").getAsString().equals(filterFaction))) {
            filterFaction = "";
        }
        if (selectedId.isEmpty() || ClientCharacterState.find(selectedId) == null) {
            selectedId = ClientCharacterState.selected();
        }
        nameBox = null;
        backgroundBox = null;
        skinPathBox = null;
    }

    private void rebuild() {
        clearWidgets();
        navBounds.clear();
        rowBounds.clear();
        // 左列导航：全部 + 各机构
        int navH = 22;
        int navGap = 4;
        int y = bodyY1 + 2;
        navBounds.add(new int[] {nlX1, y, nlX2, y + navH, -1});
        y += navH + navGap;
        for (int i = 0; i < factionMeta.size(); i++) {
            navBounds.add(new int[] {nlX1, y, nlX2, y + navH, i});
            y += navH + navGap;
        }
        // 中列角色行
        List<JsonObject> visible = filteredChars();
        int rowH = 40;
        int rowGap = 4;
        int listH = bodyY2 - bodyY1 - 2;
        int maxVisible = Math.max(1, (listH + rowGap) / (rowH + rowGap));
        int offset = Math.min(scroll, Math.max(0, visible.size() - maxVisible));
        for (int i = 0; i < visible.size() && i < maxVisible; i++) {
            int ry = bodyY1 + 2 + i * (rowH + rowGap);
            rowBounds.add(new int[] {mlX1, ry, mlX2, ry + rowH});
        }
        buildRight();
    }

    private void buildRight() {
        clearWidgets2();
        JsonObject c = ClientCharacterState.find(selectedId);
        int x = rlX1;
        int w = rlX2 - rlX1;
        // 操作行
        int ay = pvY2 + 6;
        if (c != null) {
            int bw = Math.max(44, (w - 4 * 4) / 5);
            addW(RpButton.primary(x, ay, bw, 20, Component.translatable("ccnr_rp.gui.character.activate"), b -> {
                RpChannels.sendToServer(new RpPackets.CharacterActivateC2S(selectedId));
                notice("");
            }));
            addW(RpButton.primary(
                    x + (bw + 4), ay, bw, 20, Component.translatable("ccnr_rp.gui.character.deploy"), b -> {
                        RpChannels.sendToServer(new RpPackets.CharacterDeployC2S(selectedId));
                        notice("");
                    }));
            addW(RpButton.secondary(
                    x + (bw + 4) * 2, ay, bw, 20, Component.translatable("ccnr_rp.gui.character.select"), b -> {
                        RpChannels.sendToServer(new RpPackets.CharacterSelectC2S(selectedId));
                        notice("");
                    }));
            addW(RpButton.secondary(
                    x + (bw + 4) * 3, ay, bw, 20, Component.translatable("ccnr_rp.gui.character.observe"), b -> {
                        RpChannels.sendToServer(new RpPackets.CharacterObserveC2S(selectedId));
                        notice("");
                    }));
            addW(RpButton.danger(
                    x + (bw + 4) * 4, ay, bw, 20, Component.translatable("ccnr_rp.gui.character.delete"), b -> {
                        RpChannels.sendToServer(new RpPackets.CharacterDeleteC2S(selectedId));
                        notice("");
                    }));
            // 皮肤上传行
            int sy = ay + 26;
            skinPathBox = new EditBox(
                    font, x, sy, Math.max(90, w - 126), 18, Component.translatable("ccnr_rp.gui.character.skin.path"));
            skinPathBox.setMaxLength(512);
            skinPathBox.setTextColor(RpTheme.CYAN);
            addW(skinPathBox);
            addW(RpButton.secondary(
                    x + Math.max(90, w - 126) + 6,
                    sy,
                    120,
                    18,
                    Component.translatable("ccnr_rp.gui.character.skin.upload"),
                    b -> uploadSkin()));
        }
        // 创建表单卡（底部）
        int createY1 = Math.max(bodyY2 - 96, pvY2 + 62);
        int fy = createY1 + 8;
        addW(RpButton.primary(
                x, fy + 72, w, 20, Component.translatable("ccnr_rp.gui.character.create"), b -> createSubmit()));
        backgroundBox =
                new EditBox(font, x, fy + 50, w, 18, Component.translatable("ccnr_rp.gui.character.background"));
        backgroundBox.setMaxLength(256);
        backgroundBox.setTextColor(RpTheme.CYAN);
        addW(backgroundBox);
        int bw2 = (w - 4) / 2;
        addW(RpButton.secondary(x, fy + 28, bw2, 18, Component.literal(professionLabel()), b -> {
            List<String> list = matchingProfessions();
            if (list.isEmpty()) {
                notice("ccnr_rp.gui.character.profession.empty");
                rebuild();
                return;
            }
            professionIndex = (professionIndex + 1) % list.size();
            rebuild();
        }));
        addW(RpButton.secondary(x + bw2 + 4, fy + 28, bw2, 18, Component.literal(factionLabel()), b -> {
            if (!factionIds().isEmpty()) {
                factionIndex = (factionIndex + 1) % factionIds().size();
                professionIndex = 0;
                rebuild();
            }
        }));
        nameBox = new EditBox(font, x, fy + 4, w, 18, Component.translatable("ccnr_rp.gui.character.name"));
        nameBox.setMaxLength(32);
        nameBox.setTextColor(RpTheme.CYAN);
        addW(nameBox);
    }

    private void clearWidgets2() {
        clearWidgets();
        navBounds.clear();
        rowBounds.clear();
    }

    private void addW(AbstractWidget w) {
        addRenderableWidget(w);
    }

    // ---------- 数据 ----------

    private List<String> factionIds() {
        return factionMeta.stream().map(f -> f.get("id").getAsString()).toList();
    }

    private List<JsonObject> filteredChars() {
        if (filterFaction.isEmpty()) {
            return chars;
        }
        return chars.stream()
                .filter(c -> str(c, "factionId").equals(filterFaction))
                .toList();
    }

    private JsonObject majority(String id) {
        for (JsonObject f : factionMeta) {
            if (f.get("id").getAsString().equals(id)) {
                return f;
            }
        }
        return null;
    }

    private String factionName(String id) {
        JsonObject f = majority(id);
        return f != null ? str(f, "name") : id;
    }

    private String professionName(String id) {
        for (JsonObject p : professionMeta) {
            if (p.get("id").getAsString().equals(id)) {
                return str(p, "name");
            }
        }
        return id;
    }

    private List<String> matchingProfessions() {
        List<String> ids = factionIds();
        if (ids.isEmpty()) {
            return List.of();
        }
        String fid = ids.get(Math.min(factionIndex, ids.size() - 1));
        return professionMeta.stream()
                .filter(p -> str(p, "factionId").equals(fid))
                .map(p -> p.get("id").getAsString())
                .toList();
    }

    private String factionLabel() {
        List<String> ids = factionIds();
        if (ids.isEmpty()) {
            return "阵营: ?";
        }
        return "阵营: " + factionName(ids.get(Math.min(factionIndex, ids.size() - 1)));
    }

    private String professionLabel() {
        List<String> list = matchingProfessions();
        return list.isEmpty()
                ? "职业: (暂无配置)"
                : "职业: " + professionName(list.get(Math.min(professionIndex, list.size() - 1)));
    }

    // ---------- 交互 ----------

    private void uploadSkin() {
        String path = skinPathBox.getValue();
        if (path == null || path.isBlank()) {
            notice("ccnr_rp.gui.character.skin.need_path");
            return;
        }
        byte[] data;
        try {
            data = Files.readAllBytes(Path.of(path));
        } catch (Exception e) {
            notice("ccnr_rp.gui.character.skin.read_fail");
            return;
        }
        if (data.length > 256 * 1024) {
            notice("ccnr_rp.gui.character.skin.too_big");
            return;
        }
        int part = 32 * 1024;
        int total = (data.length + part - 1) / part;
        for (int i = 0; i < total; i++) {
            byte[] chunk = java.util.Arrays.copyOfRange(data, i * part, Math.min((i + 1) * part, data.length));
            RpChannels.sendToServer(new RpPackets.SkinUploadPartC2S(selectedId, i, total, chunk));
        }
        RpChannels.sendToServer(new RpPackets.SkinUploadCommitC2S(selectedId, data.length, total));
        notice("ccnr_rp.gui.character.skin.uploading");
    }

    private void createSubmit() {
        List<String> ids = factionIds();
        if (ids.isEmpty()) {
            notice("ccnr_rp.gui.character.faction.empty");
            return;
        }
        String f = ids.get(Math.min(factionIndex, ids.size() - 1));
        List<String> list = matchingProfessions();
        String p = list.isEmpty() ? "" : list.get(Math.min(professionIndex, list.size() - 1));
        RpChannels.sendToServer(new RpPackets.CharacterCreateC2S(nameBox.getValue(), f, p, backgroundBox.getValue()));
        notice("");
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
        for (int i = 0; i < navBounds.size(); i++) {
            int[] b = navBounds.get(i);
            if (mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3]) {
                filterFaction = b[4] < 0 ? "" : factionMeta.get(b[4]).get("id").getAsString();
                scroll = 0;
                rebuild();
                return true;
            }
        }
        for (int i = 0; i < rowBounds.size(); i++) {
            int[] b = rowBounds.get(i);
            if (mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3]) {
                int offset = offsetOfRows();
                List<JsonObject> visible = filteredChars();
                selectedId = visible.get(offset + i).get("id").getAsString();
                rebuild();
                return true;
            }
        }
        return false;
    }

    private int offsetOfRows() {
        int rowH = 40;
        int gap = 4;
        int listH = bodyY2 - bodyY1 - 2;
        int maxVisible = Math.max(1, (listH + gap) / (rowH + gap));
        return Math.min(scroll, Math.max(0, filteredChars().size() - maxVisible));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= mlX1 && mouseX <= mlX2 && mouseY >= bodyY1 - 4 && mouseY <= bodyY2) {
            scroll = (int) Math.max(0, scroll - delta / 10);
            rebuild();
        }
        return true;
    }

    private void notice(String key) {
        if (key == null || key.isBlank()) {
            notice = "";
            return;
        }
        notice = Component.translatable(key).getString();
        noticeUntil = System.currentTimeMillis() + 2600;
    }

    // ---------- 渲染 ----------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        RpTheme.terminalPanel(g, px1, py1, px2, py2, RpTheme.RADIUS_LARGE);
        renderHeader(g, mouseX, mouseY);
        renderColHeaders(g);
        renderNav(g, mouseX, mouseY);
        renderList(g, mouseX, mouseY);
        renderProfile(g);
        renderPreview(g, mouseX, mouseY);
        renderNotice(g);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void renderHeader(GuiGraphics g, int mouseX, int mouseY) {
        int y = hdrY1 + 5;
        // 左侧：徽章 + 身份数据库
        RpIcons.badge(g, px1 + 17, hdrY1 + 10, 8, "hex", 1, false);
        g.drawString(
                font,
                Component.translatable("ccnr_rp.gui.character.db_header")
                        .getString()
                        .toUpperCase(Locale.ROOT),
                px1 + 30,
                y,
                RpTheme.CYAN,
                true);
        int tx = px1
                + 30
                + font.width(Component.translatable("ccnr_rp.gui.character.db_header")
                        .getString()
                        .toUpperCase(Locale.ROOT));
        g.drawString(font, " v" + versionString(), tx + 6, y + 1, RpTheme.TEXT_DIM);
        g.fill(px1 + 8, hdrY2 - 1, px2 - 8, hdrY2, RpTheme.CYAN_DIM);
        // 右侧：CCNR:NET 页眉 + 关闭按钮
        String net = Component.translatable("ccnr_rp.gui.character.net_header")
                .getString()
                .toUpperCase(Locale.ROOT);
        int nx = px2 - 8 - font.width(net) - 20;
        g.drawString(font, net, nx, y, RpTheme.CYAN, true);
        RpTheme.cornerBrackets(g, nx - 6, hdrY1 + 1, nx + font.width(net) + 6, hdrY2 - 1, 4, RpTheme.CYAN_DIM);
        // 关闭
        boolean hover = mouseIn(mouseX, mouseY, closeX1, closeY1, closeX2, closeY2);
        if (hover) {
            g.fill(closeX1 - 2, closeY1 - 1, closeX2 + 2, closeY2 + 1, 0xE66F1613);
            RpRoundRect.outlined(g, closeX1 - 3, closeY1 - 2, closeX2 + 3, closeY2 + 2, 4f, RpTheme.RED, 0x00000000);
        }
        g.drawString(
                font, "X", (closeX1 + closeX2) / 2 - 2, closeY1 + 4, hover ? 0xFFFFFFFF : RpTheme.TEXT_SECONDARY, true);
    }

    private void renderColHeaders(GuiGraphics g) {
        int y = hdrY2 + 8;
        g.drawString(
                font,
                RpTheme.tag(Component.translatable("ccnr_rp.gui.character.nav").getString()),
                nlX1,
                y,
                RpTheme.TEXT_DIM);
        g.drawString(
                font,
                RpTheme.tag(Component.translatable("ccnr_rp.gui.character.list_header")
                                .getString()) + " (" + filteredChars().size() + ")",
                mlX1,
                y,
                RpTheme.TEXT_DIM);
        g.drawString(
                font,
                RpTheme.tag(Component.translatable("ccnr_rp.gui.character.detail_header")
                        .getString()),
                rlX1,
                y,
                RpTheme.TEXT_DIM);
        g.fill(nlX1, y + 12, rlX2, y + 13, RpTheme.PANEL_BORDER);
    }

    private void renderNav(GuiGraphics g, int mouseX, int mouseY) {
        for (int i = 0; i < navBounds.size(); i++) {
            int[] b = navBounds.get(i);
            JsonObject fac = b[4] < 0 ? null : factionMeta.get(b[4]);
            String id = fac == null ? "" : fac.get("id").getAsString();
            boolean sel = !filterFaction.isEmpty() && id.equals(filterFaction);
            boolean hover = mouseX >= b[0] && mouseX <= b[2] && mouseY >= b[1] && mouseY <= b[3];
            if (sel) {
                RpTheme.selectedBar(g, b[0], b[1], b[2], b[3], 6f);
            } else {
                RpRoundRect.outlined(
                        g,
                        b[0],
                        b[1],
                        b[2],
                        b[3],
                        6f,
                        hover ? RpTheme.PANEL_BORDER_BRIGHT : RpTheme.PANEL_BORDER,
                        hover ? RpTheme.PANEL_BG_ALT : RpTheme.PANEL_BG);
            }
            if (fac == null) {
                RpIcons.badge(g, b[0] + 12, b[1] + 11, 8, "target", 2, sel);
                g.drawString(
                        font,
                        Component.translatable("ccnr_rp.gui.character.filter.all")
                                .getString(),
                        b[0] + 25,
                        b[1] + 7,
                        sel ? 0xFFFFFFFF : RpTheme.TEXT_SECONDARY,
                        true);
            } else {
                int tier = tierOf(fac);
                String icon = str(fac, "icon");
                if (icon.isBlank()) {
                    icon = "hex";
                }
                RpIcons.badge(g, b[0] + 12, b[1] + 11, 8, icon, tier, sel);
                String name = str(fac, "name");
                int maxW = b[2] - b[0] - 28;
                if (font.width(name) > maxW) {
                    name = font.plainSubstrByWidth(name, maxW - 1) + "…";
                }
                g.drawString(font, name, b[0] + 25, b[1] + 7, sel ? 0xFFFFFFFF : RpTheme.TEXT_SECONDARY, true);
            }
        }
    }

    private void renderList(GuiGraphics g, int mouseX, int mouseY) {
        RpRoundRect.outlined(g, mlX1 - 3, bodyY1 - 2, mlX2 + 3, bodyY2, 8f, RpTheme.PANEL_BORDER, 0x00000000);
        List<JsonObject> visible = filteredChars();
        int offset = offsetOfRows();
        int rowH = 40;
        for (int i = 0; i < rowBounds.size(); i++) {
            int[] b = rowBounds.get(i);
            int idx = offset + i;
            if (idx >= visible.size()) {
                break;
            }
            JsonObject c = visible.get(idx);
            boolean sel = c.get("id").getAsString().equals(selectedId);
            boolean hover = mouseX >= b[0] && mouseX <= b[2] && mouseY >= b[1] && mouseY <= b[3];
            if (sel) {
                RpTheme.selectedBar(g, b[0], b[1], b[2], b[3], 8f);
            } else {
                RpRoundRect.outlined(
                        g,
                        b[0],
                        b[1],
                        b[2],
                        b[3],
                        8f,
                        hover ? RpTheme.PANEL_BORDER_BRIGHT : RpTheme.PANEL_BORDER,
                        hover ? RpTheme.PANEL_BG_ALT : (i % 2 == 0 ? RpTheme.PANEL_BG : RpTheme.PANEL_BG_EVEN));
            }
            // 头像
            ResourceLocation tex = SkinCache.textureOrNull(c.get("id").getAsString());
            int ax = b[0] + 7;
            int ay = b[1] + 7;
            if (tex != null) {
                g.blit(tex, ax, ay, 26, 26, 0, 0, 32, 32, 32, 32);
                g.fill(ax, ay, ax + 26, ay + 1, RpTheme.PANEL_BORDER);
                g.fill(ax, ay + 25, ax + 26, ay + 26, RpTheme.PANEL_BORDER);
                g.fill(ax, ay, ax + 1, ay + 26, RpTheme.PANEL_BORDER);
                g.fill(ax + 25, ay, ax + 26, ay + 26, RpTheme.PANEL_BORDER);
            } else {
                RpIcons.circle(g, ax + 13, ay + 13, 12, RpTheme.PANEL_BG_ALT);
                g.drawString(font, firstChar(c), ax + 9, ay + 8, RpTheme.TEXT_SECONDARY, true);
            }
            g.drawString(font, str(c, "name"), ax + 34, b[1] + 6, sel ? 0xFFFFFFFF : RpTheme.TEXT_PRIMARY, true);
            g.drawString(
                    font,
                    professionName(str(c, "professionId")) + " · " + factionName(str(c, "factionId")),
                    ax + 34,
                    b[1] + 20,
                    sel ? 0xFFFFFFFF : RpTheme.TEXT_SECONDARY);
            // 状态胶囊
            String status = str(c, "status");
            int pillW = 34;
            int sx2 = b[2] - 7;
            RpRoundRect.fill(
                    g,
                    sx2 - pillW,
                    b[1] + 14,
                    sx2,
                    b[1] + 26,
                    4f,
                    RpTheme.alphaBlend(RpTheme.statusColor(status), 0xFF));
            g.drawString(font, statusKey(status), sx2 - pillW + 11, b[1] + 16, 0xFFFFFFFF);
            // 冷却
            String cooldown = cooldownText(c.get("cooldownUntil").getAsLong());
            if (!cooldown.isBlank()) {
                g.drawString(font, "CD " + cooldown, sx2 - pillW - 44, b[1] + 16, RpTheme.COOLDOWN, true);
            }
        }
    }

    private void renderProfile(GuiGraphics g) {
        JsonObject c = ClientCharacterState.find(selectedId);
        int x = rlX1;
        int w = rlX2 - rlX1;
        int y = bodyY1;
        int h = 54;
        RpTheme.card(g, x, y, x + w, y + h, 8f, RpTheme.PANEL_BG);
        if (c == null) {
            g.drawString(font, "— NO ACTIVE FILE —", x + 10, y + 22, RpTheme.TEXT_DIM, true);
            return;
        }
        String name = str(c, "name");
        g.drawString(font, name, x + 8, y + 5, RpTheme.CYAN, true);
        String status = str(c, "status");
        int pillW = 34;
        int pillX = x + w - pillW - 8;
        RpRoundRect.fill(
                g, pillX, y + 5, pillX + pillW, y + 17, 4f, RpTheme.alphaBlend(RpTheme.statusColor(status), 0xFF));
        g.drawString(font, statusKey(status), pillX + 11, y + 7, 0xFFFFFFFF);
        g.drawString(font, "ID " + str(c, "id"), x + 8 + font.width(name) + 8, y + 7, RpTheme.TEXT_DIM);
        // 阵营徽章 + 职业
        JsonObject fac = majority(str(c, "factionId"));
        String gname = fac == null ? "hex" : str(fac, "icon");
        if (gname.isBlank()) {
            gname = "hex";
        }
        RpIcons.badge(g, x + 12, y + 27, 7, gname, fac == null ? 2 : tierOf(fac), false);
        g.drawString(
                font,
                factionName(str(c, "factionId")) + "  /  " + professionName(str(c, "professionId")),
                x + 24,
                y + 22,
                RpTheme.TEXT_PRIMARY,
                true);
        // 经验/等级/执勤/冷却
        long xp = c.has("xp") ? c.get("xp").getAsLong() : 0;
        long duty = c.has("dutySeconds") ? c.get("dutySeconds").getAsLong() : 0;
        String stat = String.format("XP %d   等级 %d   执勤 %dh", xp, levelOf(c), duty / 3600);
        g.drawString(font, stat, x + 8, y + 36, RpTheme.TEXT_SECONDARY);
        String cd = cooldownText(c.get("cooldownUntil").getAsLong());
        if (!cd.isBlank()) {
            g.drawString(font, "冷却 " + cd + "m", x + 8 + font.width(stat) + 10, y + 36, RpTheme.COOLDOWN, true);
        }
    }

    private void renderPreview(GuiGraphics g, int mouseX, int mouseY) {
        int x = pvX1;
        int w = pvX2 - pvX1;
        RpRoundRect.outlined(g, x, pvY1, x + w, pvY2, 8f, RpTheme.PANEL_BORDER, 0xEE10161B);
        RpTheme.scanlines(g, x + 2, pvY1 + 2, x + w - 2, pvY2 - 2);
        g.drawString(
                font,
                RpTheme.tag(Component.translatable("ccnr_rp.gui.character.preview")
                                .getString())
                        .toUpperCase(Locale.ROOT),
                x + 8,
                pvY1 + 4,
                RpTheme.CYAN_DIM,
                true);
        JsonObject c = ClientCharacterState.find(selectedId);
        // 3D 模型（裁剪在框内，跟随鼠标）
        if (c != null) {
            g.enableScissor(x + 2, pvY1 + 14, x + w - 2, pvY2 - 24);
            CharacterPreview.render(g, x + w / 2, pvY2 - 30, 26, mouseX, mouseY, c);
            g.disableScissor();
        } else {
            g.drawCenteredString(
                    font,
                    Component.translatable("ccnr_rp.gui.character.detail_header"),
                    x + w / 2,
                    pvY1 + 60,
                    RpTheme.TEXT_DIM);
        }
        // 战术装备槽：头/胸/腿/背
        String[] slots = {"helm", "chest", "legs", "back"};
        String[] slotNames = {
            Component.translatable("ccnr_rp.gui.character.equip.head").getString(),
            Component.translatable("ccnr_rp.gui.character.equip.chest").getString(),
            Component.translatable("ccnr_rp.gui.character.equip.legs").getString(),
            Component.translatable("ccnr_rp.gui.character.equip.back").getString()
        };
        int slot = 15;
        int total = slots.length * (slot + 6) - 6;
        int sx = x + w / 2 - total / 2;
        int sy = pvY2 - 21;
        for (int i = 0; i < slots.length; i++) {
            RpIcons.slot(g, sx + i * (slot + 6), sy, slot, slots[i], RpTheme.CYAN);
            g.drawString(font, slotNames[i], sx + i * (slot + 6) + 1, sy + slot + 3, RpTheme.TEXT_DIM);
        }
    }

    private void renderNotice(GuiGraphics g) {
        if (!notice.isBlank() && System.currentTimeMillis() < noticeUntil) {
            g.drawCenteredString(font, "[ 系统 ] " + notice, (px1 + px2) / 2, py2 - 20, RpTheme.RED_LINE);
        }
    }

    // ---------- 工具 ----------

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static int tierOf(JsonObject f) {
        try {
            return f.has("tier") ? Math.max(1, Math.min(3, f.get("tier").getAsInt())) : 2;
        } catch (Exception e) {
            return 2;
        }
    }

    private static String firstChar(JsonObject c) {
        String n = str(c, "name");
        return n.length() > 0 ? n.substring(0, 1) : "?";
    }

    private static String statusKey(String status) {
        return switch (status) {
            case "alive" -> "AM";
            case "dead" -> "M";
            default -> "OB";
        };
    }

    private static String cooldownText(long cooldownUntil) {
        if (cooldownUntil <= 0) {
            return "";
        }
        long min = (cooldownUntil - System.currentTimeMillis()) / 60000L;
        return min > 0 ? min + "m" : "";
    }

    private static int levelOf(JsonObject c) {
        try {
            return new com.ccnrcom.rp.experience.LevelCurve(
                            com.ccnrcom.rp.config.CCNRRPConfig.LEVEL_BASE.get(),
                            com.ccnrcom.rp.config.CCNRRPConfig.LEVEL_POW.get())
                    .level(c.get("xp").getAsLong());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String versionString() {
        try {
            return net.minecraftforge.fml.ModList.get()
                    .getModContainerById("ccnr_rp")
                    .map(c -> c.getModInfo().getVersion().toString())
                    .orElse("?");
        } catch (Exception e) {
            return "?";
        }
    }

    private boolean mouseIn(double mx, double my, int x1, int y1, int x2, int y2) {
        return mx >= x1 && mx <= x2 && my >= y1 && my <= y2;
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
