/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * 管理面板「阵营组」页签（与「关系管理」页签并列）：阵营组列表 + 编辑器。<br>
 * 阵营组是关系声明的批量容器 {id, memberIds[]}（成员=阵营 id）。左侧组列表（按 id），
 * 右侧编辑 id + 成员（逗号分隔阵营 id，可下拉注入），新增/保存/删除走 ManagerCrudC2S(kind="group") →
 * 服务端权威校验+落盘。数据由 CharacterListS2C 的 {@code groups} 数组回显。
 */
public final class RpGroupsTab {

    private final RpAdminScreen screen;
    private int px1, py1, px2, py2;
    private int listX1, listX2, listY1, listY2;
    private int scroll = 0;
    private static final int ROW_H = 20;
    /** 表头带高度：表头独立一行，行列表从表头下方开始，避免表头与首行文字重叠。 */
    private static final int HDR = 13;

    private EditBox idBox;
    private EditBox membersBox;
    private EditBox nameBox;
    private int selIndex = -1;
    private String notice = "";
    private long noticeUntil = 0;

    // 阵营下拉注入（成员输入框）：下拉选阵营→注入按钮把 id 追加到 members 输入框（逗号分隔、去重）
    private boolean ddOpen = false;
    private int ddIdx = 0;
    private int ddScroll = 0;
    private int ddW = 0;
    private static final int DD_H = 16;
    private static final int DD_ITEM_H = 14;
    private static final int DD_MAX_VISIBLE = 8;
    private static final int INJECT_W = 52;

    public RpGroupsTab(RpAdminScreen screen) {
        this.screen = screen;
    }

    /** 由 RpAdminScreen.rebuild 调用（tab==TAB_GROUPS）。 */
    public void rebuild(int px1, int py1, int px2, int py2) {
        this.px1 = px1;
        this.py1 = py1;
        this.px2 = px2;
        this.py2 = py2;
        listX1 = px1 + 12;
        listX2 = listX1 + Math.min(300, (px2 - px1) * 42 / 100);
        listY1 = py1 + 76;
        listY2 = py2 - 60;
        int ex = listX2 + 16;
        int ew = px2 - ex - 12;
        ddW = Math.max(60, Math.min(ew - INJECT_W - 6, ew * 55 / 100));
        if (idBox == null) {
            nameBox = new EditBox(Minecraft.getInstance().font, ex, listY1 + 26, ew, 18, Component.literal("name"));
            nameBox.setMaxLength(64);
            idBox = new EditBox(Minecraft.getInstance().font, ex, listY1 + 72, ew, 18, Component.literal("id"));
            idBox.setMaxLength(64);
            membersBox =
                    new EditBox(Minecraft.getInstance().font, ex, listY1 + 120, ew, 18, Component.literal("members"));
            membersBox.setMaxLength(2048);
        } else {
            nameBox.setX(ex);
            nameBox.setY(listY1 + 26);
            nameBox.setWidth(ew);
            idBox.setX(ex);
            idBox.setY(listY1 + 72);
            idBox.setWidth(ew);
            membersBox.setX(ex);
            membersBox.setY(listY1 + 120);
            membersBox.setWidth(ew);
        }
        // rebuild 会先 clearWidgets 清空全部控件，输入框必须每次重新注册
        screen.addXpWidget(nameBox);
        screen.addXpWidget(idBox);
        screen.addXpWidget(membersBox);
        ddOpen = false;
        ddScroll = 0;
        int facSize = ClientCharacterState.factions().size();
        ddIdx = facSize == 0 ? -1 : Math.min(ddIdx, facSize - 1);
        if (selIndex >= 0 && selIndex < groups().size()) {
            loadEditor(selIndex);
        } else {
            clearEditor();
        }
    }

    private int editorX() {
        return listX2 + 16;
    }

    private int actionY() {
        return listY1 + 150;
    }

    private List<JsonObject> groups() {
        return ClientCharacterState.groups();
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static List<String> idList(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (!o.has(key) || !o.get(key).isJsonArray()) {
            return out;
        }
        for (var e : o.getAsJsonArray(key)) {
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                out.add(e.getAsString());
            }
        }
        return out;
    }

    private static String join(List<String> ids) {
        return String.join(", ", ids);
    }

    private void clearEditor() {
        selIndex = -1;
        if (nameBox != null) {
            nameBox.setValue("");
        }
        if (idBox != null) {
            idBox.setValue("");
        }
        if (membersBox != null) {
            membersBox.setValue("");
        }
    }

    private void loadEditor(int index) {
        List<JsonObject> groups = groups();
        if (index < 0 || index >= groups.size()) {
            return;
        }
        JsonObject g = groups.get(index);
        selIndex = index;
        nameBox.setValue(str(g, "name", ""));
        idBox.setValue(str(g, "id", ""));
        membersBox.setValue(join(idList(g, "members")));
    }

    private void notice(String key) {
        notice = Component.translatable(key).getString();
        noticeUntil = System.currentTimeMillis() + 2600;
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    // ---------- 交互 ----------

    public boolean mouseClicked(int mx, int my, int button) {
        int ex = editorX();
        int ay = actionY();
        int aw = 64;
        if (mx >= ex && mx <= ex + aw && my >= ay && my <= ay + 20) {
            addGroup();
            return true;
        }
        if (mx >= ex + aw + 6 && mx <= ex + aw + 6 + aw && my >= ay && my <= ay + 20) {
            updateGroup();
            return true;
        }
        if (mx >= ex + 2 * (aw + 6) && mx <= ex + 2 * (aw + 6) + aw && my >= ay && my <= ay + 20) {
            removeGroup();
            return true;
        }
        List<JsonObject> groups = groups();
        for (int i = 0; i < groups.size(); i++) {
            int ry = listY1 + HDR + (i - scroll) * ROW_H;
            if (ry >= listY1 - ROW_H && ry <= listY2 && mx >= listX1 && mx <= listX2 && my >= ry && my <= ry + ROW_H) {
                if (button == 1) {
                    askRemoveGroup(str(groups.get(i), "id", "")); // 右键条目 = 删除该组（先确认）
                } else {
                    loadEditor(i);
                }
                return true;
            }
        }
        return false;
    }

    public void mouseScrolled(int mouseX, int mouseY, double delta) {
        int ex = editorX();
        int maxScroll = Math.max(0, ClientCharacterState.factions().size() - DD_MAX_VISIBLE);
        if (ddOpen
                && mouseX >= ex
                && mouseX <= ex + ddW
                && mouseY >= ddPopupTop()
                && mouseY <= ddPopupTop() + ddPopupH()) {
            ddScroll = (int) Math.max(0, Math.min(ddScroll + (delta > 0 ? -1 : 1), maxScroll));
            return;
        }
        if (mouseX >= listX1 && mouseX <= listX2 && mouseY >= listY1 && mouseY <= listY2) {
            int max = Math.max(0, groups().size() - Math.max(1, (listY2 - listY1 - HDR) / ROW_H));
            if (delta > 0) {
                scroll = Math.max(0, scroll - 2);
            } else {
                scroll = Math.min(max, scroll + 2);
            }
        }
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return false;
    }

    // ---------- 阵营下拉注入 ----------

    private int ddY() {
        return listY1 + 102;
    }

    private int ddPopupTop() {
        return ddY() + DD_H + 2;
    }

    private int ddPopupH() {
        int n = ClientCharacterState.factions().size();
        return Math.min(n, DD_MAX_VISIBLE) * DD_ITEM_H + 2;
    }

    private int ddPopupItemY(int i) {
        return ddPopupTop() + 1 + (i - ddScroll) * DD_ITEM_H;
    }

    /** 下拉与注入按钮命中（须在 super.mouseClicked / widget 分发之前调用，弹层盖住输入框）。 */
    public boolean mouseClickedOverlay(int mx, int my, int button) {
        if (button != 0) {
            return false;
        }
        int ex = editorX();
        List<JsonObject> facs = ClientCharacterState.factions();
        if (ddOpen) {
            for (int i = ddScroll; i < facs.size() && i < ddScroll + DD_MAX_VISIBLE; i++) {
                int y1 = ddPopupItemY(i);
                if (mx >= ex && mx <= ex + ddW && my >= y1 && my <= y1 + DD_ITEM_H) {
                    ddIdx = i;
                    ddOpen = false;
                    return true;
                }
            }
        }
        if (mx >= ex && mx <= ex + ddW && my >= ddY() && my <= ddY() + DD_H) {
            if (!facs.isEmpty()) {
                ddOpen = !ddOpen;
            }
            return true;
        }
        if (mx >= ex + ddW + 6 && mx <= ex + ddW + 6 + INJECT_W && my >= ddY() && my <= ddY() + DD_H) {
            injectFaction();
            return true;
        }
        if (ddOpen) {
            ddOpen = false;
            return false;
        }
        return false;
    }

    /** 把下拉选中的阵营 id 追加到成员输入框（逗号分隔、去重）。 */
    private void injectFaction() {
        List<JsonObject> facs = ClientCharacterState.factions();
        if (facs.isEmpty() || ddIdx < 0 || ddIdx >= facs.size()) {
            return;
        }
        String id = str(facs.get(ddIdx), "id", "");
        if (id.isBlank()) {
            return;
        }
        List<String> cur = new ArrayList<>();
        for (String s : membersBox.getValue().split(",")) {
            if (!s.isBlank()) {
                String t = s.trim();
                if (!cur.contains(t)) {
                    cur.add(t);
                }
            }
        }
        if (!cur.contains(id)) {
            cur.add(id);
        }
        membersBox.setValue(String.join(", ", cur));
        ddOpen = false;
    }

    private static String facLabel(JsonObject f) {
        String name = str(f, "name", "");
        String id = str(f, "id", "");
        return name.isBlank() ? id : name + " (" + id + ")";
    }

    // ---------- 编辑动作 ----------

    private JsonObject editorPayload() {
        JsonObject p = new JsonObject();
        p.addProperty("id", idBox.getValue().trim());
        p.addProperty("name", nameBox.getValue().trim());
        JsonArray members = new JsonArray();
        for (String s : membersBox.getValue().split(",")) {
            if (!s.isBlank()) {
                members.add(s.trim());
            }
        }
        p.add("memberIds", members);
        return p;
    }

    private void addGroup() {
        if (idBox.getValue().trim().isBlank()) {
            notice("ccnr_rp.gui.admin.group.id_required");
            return;
        }
        screen.requestCrud("group", "create", editorPayload());
        clearEditor();
        refresh();
    }

    private void updateGroup() {
        if (selIndex < 0) {
            notice("ccnr_rp.gui.admin.group.select_first");
            return;
        }
        if (idBox.getValue().trim().isBlank()) {
            notice("ccnr_rp.gui.admin.group.id_required");
            return;
        }
        screen.requestCrud("group", "update", editorPayload());
        clearEditor();
        refresh();
    }

    private void removeGroup() {
        if (selIndex < 0) {
            notice("ccnr_rp.gui.admin.group.select_first");
            return;
        }
        askRemoveGroup(str(groups().get(selIndex), "id", ""));
    }

    /** 删除阵营组：一律先二次确认（右键条目与「删除」按钮共用，docs/01 §10.2）。 */
    private void askRemoveGroup(String id) {
        if (id.isBlank()) {
            return;
        }
        screen.confirmDelete(
                Component.translatable("ccnr_rp.gui.admin.confirm.del_msg", tr("ccnr_rp.gui.admin.tab.groups"), id)
                        .getString(),
                () -> {
                    JsonObject p = new JsonObject();
                    p.addProperty("id", id);
                    screen.requestCrud("group", "delete", p);
                    clearEditor();
                    refresh();
                });
    }

    /** 请求重新同步角色列表（服务端编辑已落盘，刷新展示新组）。 */
    private void refresh() {
        com.ccnrcom.rp.network.RpChannels.sendToServer(new com.ccnrcom.rp.network.RpPackets.RequestCharacterListC2S());
    }

    // ---------- 渲染 ----------

    public void render(GuiGraphics g, int mx, int my) {
        var font = Minecraft.getInstance().font;
        List<JsonObject> groups = groups();
        RpTheme.listPanel(g, listX1 - 2, listY1 - 4, listX2 + 2, listY2 + 2);
        g.drawString(
                font,
                tr("ccnr_rp.gui.admin.group.list") + " (" + groups.size() + ")",
                listX1 + 4,
                listY1 + 1,
                RpTheme.TEXT_DIM);
        // 表头带与行列表分隔线（避免表头文字压到首行）
        g.fill(listX1, listY1 + HDR - 1, listX2, listY1 + HDR, RpTheme.PANEL_BORDER);
        g.enableScissor(listX1, listY1 + HDR, listX2, listY2);
        for (int i = 0; i < groups.size(); i++) {
            int ry = listY1 + HDR + (i - scroll) * ROW_H;
            if (ry < listY1 - ROW_H || ry > listY2) {
                continue;
            }
            JsonObject grp = groups.get(i);
            boolean sel = i == selIndex;
            if (sel) {
                RpTheme.selectedBar(g, listX1, ry, listX2, ry + ROW_H, 3f);
            } else {
                RpTheme.listRow(g, listX1, ry, listX2, ry + ROW_H, i, mx, my);
            }
            String id = str(grp, "id", "");
            String name = str(grp, "name", id);
            if (name.isBlank()) {
                name = id;
            }
            List<String> members = idList(grp, "members");
            String raw = name.equals(id) ? name : name + " (" + id + ")";
            raw += " (" + members.size() + ")";
            int labelMax = (listX2 - listX1) - 4 - 6;
            String label = clip(font, raw, Math.max(20, labelMax));
            g.drawString(font, label, listX1 + 4, ry + 5, sel ? RpTheme.ACCENT_TEXT : RpTheme.TEXT_PRIMARY);
        }
        g.disableScissor();

        int ex = editorX();
        g.drawString(font, tr("ccnr_rp.gui.admin.group.name"), ex, listY1 + 4, RpTheme.TEXT_SECONDARY);
        g.drawString(font, tr("ccnr_rp.gui.admin.group.id"), ex, listY1 + 48, RpTheme.TEXT_SECONDARY);
        g.drawString(font, tr("ccnr_rp.gui.admin.group.members"), ex, listY1 + 94, RpTheme.TEXT_SECONDARY);
        drawDropdown(g, mx, my);
        int ay = actionY();
        int aw = 64;
        RpButton.draw(g, ex, ay, ex + aw, ay + 20, tr("ccnr_rp.gui.admin.group.add"), RpTheme.CYAN_DIM, true);
        RpButton.draw(
                g,
                ex + aw + 6,
                ay,
                ex + aw + 6 + aw,
                ay + 20,
                tr("ccnr_rp.gui.admin.group.update"),
                RpTheme.PANEL_BORDER_BRIGHT,
                false);
        RpButton.draw(
                g,
                ex + 2 * (aw + 6),
                ay,
                ex + 2 * (aw + 6) + aw,
                ay + 20,
                tr("ccnr_rp.gui.admin.group.remove"),
                RpTheme.RED_DIM,
                false);
        if (System.currentTimeMillis() < noticeUntil) {
            g.drawCenteredString(font, Component.literal(notice), (px1 + px2) / 2, py2 - 24, RpTheme.TEXT_SECONDARY);
        }
        g.drawString(font, tr("ccnr_rp.gui.admin.group.members_hint"), ex, py2 - 40, RpTheme.TEXT_DIM);
        g.fill(ex - 1, listY1 + 25, ex + (px2 - ex - 12) + 1, listY1 + 45, RpTheme.SURFACE_INSET);
        g.fill(ex - 1, listY1 + 71, ex + (px2 - ex - 12) + 1, listY1 + 91, RpTheme.SURFACE_INSET);
        g.fill(ex - 1, listY1 + 121, ex + (px2 - ex - 12) + 1, listY1 + 141, RpTheme.SURFACE_INSET);
    }

    public void renderOverlay(GuiGraphics g, int mx, int my) {
        if (ddOpen) {
            drawPopup(g, mx, my);
        }
    }

    private void drawDropdown(GuiGraphics g, int mx, int my) {
        var font = Minecraft.getInstance().font;
        int ex = editorX();
        int y = ddY();
        boolean hov = mx >= ex && mx <= ex + ddW && my >= y && my <= y + DD_H;
        RpTheme.controlBox(g, ex, y, ex + ddW, y + DD_H, ddOpen || hov);
        List<JsonObject> facs = ClientCharacterState.factions();
        String label = facs.isEmpty() || ddIdx < 0 || ddIdx >= facs.size()
                ? tr("ccnr_rp.gui.admin.group.pick_faction")
                : facLabel(facs.get(ddIdx));
        g.drawString(
                font,
                Component.literal(clip(font, label, ddW - 18)),
                ex + 4,
                y + 3,
                facs.isEmpty() || ddIdx < 0 || ddIdx >= facs.size() ? RpTheme.TEXT_DIM : RpTheme.TEXT_PRIMARY);
        int cx = ex + ddW - 8;
        int cy = y + DD_H / 2;
        g.fill(cx - 3, cy - 1, cx + 4, cy, RpTheme.TEXT_SECONDARY);
        g.fill(cx - 2, cy, cx + 3, cy + 1, RpTheme.TEXT_SECONDARY);
        g.fill(cx - 1, cy + 1, cx + 2, cy + 2, RpTheme.TEXT_SECONDARY);
        RpButton.draw(
                g,
                ex + ddW + 6,
                y,
                ex + ddW + 6 + INJECT_W,
                y + DD_H,
                tr("ccnr_rp.gui.admin.group.inject"),
                RpTheme.CYAN_DIM,
                true);
    }

    private void drawPopup(GuiGraphics g, int mx, int my) {
        var font = Minecraft.getInstance().font;
        int ex = editorX();
        List<JsonObject> facs = ClientCharacterState.factions();
        int top = ddPopupTop();
        int h = ddPopupH();
        RpTheme.popupPanel(g, ex, top, ex + ddW, top + h);
        g.enableScissor(ex, top, ex + ddW, top + h);
        for (int i = ddScroll; i < facs.size() && i < ddScroll + DD_MAX_VISIBLE; i++) {
            int y1 = ddPopupItemY(i);
            int y2 = y1 + DD_ITEM_H;
            boolean hov = mx >= ex && mx <= ex + ddW && my >= y1 && my <= y2;
            boolean cur = i == ddIdx;
            RpTheme.popupRow(g, ex + 1, y1, ex + ddW - 1, y2, cur, hov);
            g.drawString(
                    font,
                    Component.literal(clip(font, facLabel(facs.get(i)), ddW - 10)),
                    ex + 5,
                    y1 + 3,
                    RpTheme.popupRowText(cur, hov));
        }
        g.disableScissor();
    }

    /** 按像素宽度裁剪文本（共享入口，算法见 RpTheme.clip）。 */
    private static String clip(net.minecraft.client.gui.Font font, String s, int maxW) {
        return RpTheme.clip(font, s, maxW);
    }
}
