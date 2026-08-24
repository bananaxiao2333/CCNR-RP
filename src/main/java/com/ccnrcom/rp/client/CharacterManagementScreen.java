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
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 角色管理界面 v2——对齐 CCNR-Com APP 风格：
 * 深色半透明圆角面板 / 左侧角色列表(头像+状态胶囊) / 右侧详情卡+操作 / 创建表单卡 / 皮肤上传卡。
 * 所有数据与服务端同步（List→Create→Select→Observe→Activate→Deploy→Delete→Skin）。
 */
public class CharacterManagementScreen extends Screen {

    private static CharacterManagementScreen open;

    private final List<JsonObject> chars = new ArrayList<>();
    private final List<String> factionIds = new ArrayList<>();
    private final List<String> professionIds = new ArrayList<>();
    private final List<int[]> rowBounds = new ArrayList<>();

    private String selectedId = "";
    private int scroll = 0;
    private int factionIndex = 0;
    private int professionIndex = 0;
    private EditBox nameBox;
    private EditBox backgroundBox;
    private EditBox skinPathBox;
    private String notice = "";
    private long noticeUntil = 0;

    private int panelX1, panelY1, panelX2, panelY2;
    private int listX1, listY1, listX2, listY2;
    private int rightX1, rightY1, rightX2, rightY2;

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
        // 面板区域
        panelX1 = 18;
        panelY1 = 18;
        panelX2 = width - 18;
        panelY2 = height - 18;
        int mid = panelX1 + (panelX2 - panelX1) * 40 / 100;
        listX1 = panelX1 + 10;
        listY1 = panelY1 + 40;
        listX2 = mid - 10;
        listY2 = panelY2 - 10;
        rightX1 = mid + 10;
        rightY1 = panelY1 + 40;
        rightX2 = panelX2 - 10;
        rightY2 = panelY2 - 10;
        rebuild();
    }

    private void reloadData() {
        chars.clear();
        chars.addAll(ClientCharacterState.list());
        factionIds.clear();
        ClientCharacterState.factions().forEach(f -> factionIds.add(f.get("id").getAsString()));
        professionIds.clear();
        ClientCharacterState.professions()
                .forEach(p -> professionIds.add(p.get("id").getAsString()));
        if (selectedId.isEmpty() || ClientCharacterState.find(selectedId) == null) {
            selectedId = ClientCharacterState.selected();
        }
        nameBox = null;
        backgroundBox = null;
        skinPathBox = null;
    }

    private void rebuild() {
        clearWidgets();
        rowBounds.clear();
        int rowH = 44;
        int listH = listY2 - listY1;
        int maxVisible = Math.max(1, listH / rowH);
        int offset = Math.min(scroll, Math.max(0, Math.max(0, chars.size() - maxVisible)));
        List<JsonObject> visible = chars.subList(offset, Math.min(chars.size(), offset + maxVisible));
        for (int i = 0; i < visible.size(); i++) {
            rowBounds.add(new int[] {listX1, listY1 + i * rowH, listX2, listY1 + (i + 1) * rowH - 4});
        }
        buildRight();
    }

    private void buildRight() {
        clearWidgets2();
        JsonObject c = ClientCharacterState.find(selectedId);
        int x = rightX1;
        int y = rightY1;
        int w = rightX2 - rightX1;
        // 操作行（详情卡下方）
        if (c != null) {
            int bw = Math.max(52, (w - 10) / 5);
            addW(RpButton.primary(x, y, bw, 20, Component.translatable("ccnr_rp.gui.character.activate"), b -> {
                RpChannels.sendToServer(new RpPackets.CharacterActivateC2S(selectedId));
                notice("");
            }));
            addW(RpButton.primary(x + bw + 4, y, bw, 20, Component.translatable("ccnr_rp.gui.character.deploy"), b -> {
                RpChannels.sendToServer(new RpPackets.CharacterDeployC2S(selectedId));
                notice("");
            }));
            addW(RpButton.secondary(
                    x + (bw + 4) * 2, y, bw, 20, Component.translatable("ccnr_rp.gui.character.select"), b -> {
                        RpChannels.sendToServer(new RpPackets.CharacterSelectC2S(selectedId));
                        notice("");
                    }));
            addW(RpButton.secondary(
                    x + (bw + 4) * 3, y, bw, 20, Component.translatable("ccnr_rp.gui.character.observe"), b -> {
                        RpChannels.sendToServer(new RpPackets.CharacterObserveC2S(selectedId));
                        notice("");
                    }));
            addW(RpButton.danger(
                    x + (bw + 4) * 4, y, bw, 20, Component.translatable("ccnr_rp.gui.character.delete"), b -> {
                        RpChannels.sendToServer(new RpPackets.CharacterDeleteC2S(selectedId));
                        notice("");
                    }));
            // 皮肤上传
            int sy = y + 28;
            skinPathBox = new EditBox(
                    font, x, sy, Math.max(80, w - 130), 18, Component.translatable("ccnr_rp.gui.character.skin.path"));
            skinPathBox.setMaxLength(512);
            addW(skinPathBox);
            addW(RpButton.secondary(
                    x + Math.max(80, w - 130) + 6,
                    sy,
                    118,
                    18,
                    Component.translatable("ccnr_rp.gui.character.skin.upload"),
                    b -> uploadSkin()));
        }
        // 创建表单卡
        int fy = Math.max(y + 120, panelY2 - 118);
        addW(RpButton.primary(
                x, fy + 96, 90, 20, Component.translatable("ccnr_rp.gui.character.create"), b -> createSubmit()));
        backgroundBox =
                new EditBox(font, x, fy + 58, w, 34, Component.translatable("ccnr_rp.gui.character.background"));
        backgroundBox.setMaxLength(256);
        addW(backgroundBox);
        addW(RpButton.secondary(x + w / 2, fy + 34, w / 2, 18, Component.literal(professionLabel()), b -> {
            List<String> list = matchingProfessions();
            if (!list.isEmpty()) {
                professionIndex = (professionIndex + 1) % list.size();
                rebuild();
            }
        }));
        addW(RpButton.secondary(x, fy + 34, w / 2, 18, Component.literal(factionLabel()), b -> {
            if (!factionIds.isEmpty()) {
                factionIndex = (factionIndex + 1) % factionIds.size();
                professionIndex = 0;
                rebuild();
            }
        }));
        nameBox = new EditBox(font, x, fy + 10, w, 18, Component.translatable("ccnr_rp.gui.character.name"));
        nameBox.setMaxLength(32);
        addW(nameBox);
    }

    private void clearWidgets2() {
        // 保留列表区结构：rebuild 由 init 重排
        clearWidgets();
        rowBounds.clear();
    }

    private void addW(net.minecraft.client.gui.components.AbstractWidget w) {
        addRenderableWidget(w);
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
        String f = factionIds.isEmpty() ? "" : factionIds.get(factionIndex);
        String p = matchingProfessions().isEmpty() ? "" : matchingProfessions().get(professionIndex);
        RpChannels.sendToServer(new RpPackets.CharacterCreateC2S(nameBox.getValue(), f, p, backgroundBox.getValue()));
        notice("");
    }

    private List<String> matchingProfessions() {
        if (factionIds.isEmpty()) {
            return List.of();
        }
        String fid = factionIds.get(factionIndex);
        return ClientCharacterState.professionsOf(fid).stream()
                .map(p -> p.get("id").getAsString())
                .toList();
    }

    private String factionLabel() {
        return factionIds.isEmpty() ? "?" : "阵营: " + factionIds.get(factionIndex);
    }

    private String professionLabel() {
        List<String> list = matchingProfessions();
        return list.isEmpty() ? "?" : "职业: " + list.get(Math.min(professionIndex, list.size() - 1));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        for (int i = 0; i < rowBounds.size(); i++) {
            int[] b = rowBounds.get(i);
            if (mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3]) {
                int offset = Math.min(scroll, Math.max(0, chars.size() - (listY2 - listY1) / 44));
                selectedId = chars.get(offset + i).get("id").getAsString();
                rebuild();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scroll = (int) Math.max(0, scroll - delta / 10);
        rebuild();
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
        // 主面板
        RpRoundRect.fill(g, panelX1, panelY1, panelX2, panelY2, RpTheme.RADIUS_LARGE, RpTheme.OVERLAY);
        RpRoundRect.outlined(
                g, panelX1, panelY1, panelX2, panelY2, RpTheme.RADIUS_LARGE, RpTheme.PANEL_BORDER, RpTheme.OVERLAY);
        g.drawString(font, title, panelX1 + 12, panelY1 + 10, RpTheme.TEXT_PRIMARY);
        g.drawString(
                font,
                Component.translatable("ccnr_rp.gui.character.list.hint", chars.size()),
                panelX1 + 12 + font.width(title) + 14,
                panelY1 + 13,
                RpTheme.TEXT_SECONDARY);

        renderList(g, mouseX, mouseY);
        renderDetail(g);
        renderFormLabels(g);
        if (!notice.isBlank() && System.currentTimeMillis() < noticeUntil) {
            g.drawCenteredString(font, notice, (panelX1 + panelX2) / 2, panelY2 - 24, 0xFFFFD75A);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void renderList(GuiGraphics g, int mouseX, int mouseY) {
        RpRoundRect.fill(g, listX1, listY1, listX2, listY2, RpTheme.RADIUS_MEDIUM, RpTheme.PANEL_BG);
        int rowH = 44;
        int offset = Math.min(scroll, Math.max(0, Math.max(0, chars.size() - (listY2 - listY1) / rowH)));
        List<JsonObject> visible = chars.subList(offset, Math.min(chars.size(), offset + (listY2 - listY1) / rowH));
        for (int i = 0; i < visible.size(); i++) {
            JsonObject c = visible.get(i);
            int x1 = listX1 + 4;
            int y1 = listY1 + 4 + i * rowH;
            int x2 = listX2 - 4;
            int y2 = y1 + rowH - 6;
            boolean sel = c.get("id").getAsString().equals(selectedId);
            boolean hover = mouseX >= x1 && mouseX <= x2 && mouseY >= y1 && mouseY <= y2;
            int bg = sel ? 0x332F6BFF : (hover ? 0xFF3A3A44 : 0xFF26262C);
            RpRoundRect.fill(g, x1, y1, x2, y2, 8f, bg);
            if (sel) {
                RpRoundRect.fill(g, x1, y1, x1 + 3, y2, 8f, RpTheme.ACCENT);
            }
            // 头像
            ResourceLocation tex = SkinCache.textureOrNull(c.get("id").getAsString());
            int ax = x1 + 6;
            int ay = y1 + 5;
            if (tex != null) {
                g.blit(tex, ax, ay, 30, 30, 0, 0, 32, 32, 32, 32);
            } else {
                RpRoundRect.fill(g, ax, ay, ax + 30, ay + 30, 6f, RpTheme.PANEL_BG_ALT);
                g.drawString(font, firstChar(c), ax + 11, ay + 11, RpTheme.TEXT_SECONDARY);
            }
            g.drawString(font, str(c, "name"), ax + 38, y1 + 8, RpTheme.TEXT_PRIMARY);
            g.drawString(
                    font,
                    str(c, "professionId") + " · " + str(c, "factionId"),
                    ax + 38,
                    y1 + 20,
                    RpTheme.TEXT_SECONDARY);
            int pillW = 46;
            String status = str(c, "status");
            int sx2 = x2 - 6;
            RpRoundRect.fill(
                    g, sx2 - pillW, y1 + 10, sx2, y1 + 22, 6f, RpTheme.alphaBlend(RpTheme.statusColor(status), 0xFF));
            g.drawString(font, statusKey(status), sx2 - pillW + 7, y1 + 12, 0xFFFFFFFF);
            String cooldown = cooldownText(c.get("cooldownUntil").getAsLong());
            if (!cooldown.isBlank()) {
                g.drawString(font, cooldown, sx2 - pillW - 52, y1 + 12, RpTheme.COOLDOWN);
            }
        }
    }

    private void renderDetail(GuiGraphics g) {
        JsonObject c = ClientCharacterState.find(selectedId);
        if (c == null) {
            return;
        }
        int x = rightX1;
        int y = rightY1 + 32;
        int w = rightX2 - rightX1;
        int h = 76;
        RpRoundRect.outlined(g, x, y, x + w, y + h, RpTheme.RADIUS_MEDIUM, RpTheme.PANEL_BORDER, RpTheme.PANEL_BG_ALT);
        // 皮肤预览（左侧）
        ResourceLocation tex = SkinCache.textureOrNull(c.get("id").getAsString());
        if (tex != null) {
            g.blit(tex, x + 8, y + 8, 60, 60, 0, 0, 32, 32, 32, 32);
        } else {
            RpRoundRect.fill(g, x + 8, y + 8, x + 68, y + 68, 8f, RpTheme.PANEL_BG);
            g.drawString(font, firstChar(c), x + 34, y + 34, RpTheme.TEXT_SECONDARY);
        }
        g.drawString(font, str(c, "name"), x + 80, y + 12, RpTheme.TEXT_PRIMARY);
        g.drawString(
                font,
                String.format("%s · %s", str(c, "professionId"), str(c, "factionId")),
                x + 80,
                y + 26,
                RpTheme.ACCENT_HOVER);
        g.drawString(
                font,
                String.format("XP %s  ·  等级 %s", c.get("xp").getAsLong(), levelOf(c)),
                x + 80,
                y + 40,
                RpTheme.TEXT_SECONDARY);
        g.drawString(font, str(c, "background"), x + 80, y + 56, RpTheme.TEXT_SECONDARY);
    }

    private void renderFormLabels(GuiGraphics g) {
        int x = rightX1;
        int fy = Math.max(rightY1 + 32 + 88, panelY2 - 118);
        g.drawString(font, Component.translatable("ccnr_rp.gui.character.create"), x, fy - 8, RpTheme.TEXT_PRIMARY);
    }

    // ---------- 工具 ----------

    private static String firstChar(JsonObject c) {
        String n = c.has("name") ? c.get("name").getAsString() : "?";
        return n.length() > 0 ? n.substring(0, 1) : "?";
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
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
