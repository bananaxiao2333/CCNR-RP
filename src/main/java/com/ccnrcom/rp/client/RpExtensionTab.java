/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * 管理面板「拓展设定」页签（与「阵营组」页签并列）：左侧区域管理 + 右侧阵营弹头设定。<br>
 * 区域（{@code area}）是弹头投放范围 {id,name,dim,x1,y1,z1,x2,y2,z2}，增删改走 ManagerCrudC2S(kind="area") →
 * 服务端权威校验（id 规则 / 每轴须有厚度 / 上限 64）+ 落盘，数据由 CharacterListS2C 的 {@code areas} 数组回显。<br>
 * 弹头发射开关是阵营的单字段写：ManagerCrudC2S(kind="warhead", action="set", {factionId,enabled,areaId})，
 * 与阵营表单的全量保存（kind="faction"）分开，避免全量 upsert 吞掉该字段（docs/01 §11.1）。<br>
 * 本页签无弹窗（下拉弹层不是 modal：不遮挡面板、不接管 Esc），故不涉及 docs/01 §10 的 modal 约束。
 */
public final class RpExtensionTab {

    private final RpAdminScreen screen;
    private int px1, py1, px2, py2;

    // ---------- 左：区域列表 + 编辑器 ----------
    private int listX1, listX2, listY1, listY2;
    private int scroll = 0;
    private static final int ROW_H = 20;
    /** 表头带高度：表头独立一行，行列表从表头下方开始，避免表头与首行文字重叠。 */
    private static final int HDR = 13;
    /** 字段标签左槽宽度（标签画在输入框左侧，省纵向空间）。 */
    private static final int LBL_W = 62;

    private int selIndex = -1;
    private EditBox idBox;
    private EditBox nameBox;
    private EditBox dimBox;
    private EditBox x1Box;
    private EditBox y1Box;
    private EditBox z1Box;
    private EditBox x2Box;
    private EditBox y2Box;
    private EditBox z2Box;
    // 编辑器行 y（rebuild 计算、render/mouseClicked 共用，避免几何重复推导）
    private int edIdY, edNameY, edDimY, edXyz1Y, edXyz2Y, edBtnY;

    // ---------- 右：阵营弹头 ----------
    private int facX1, facX2, facY1, facY2;
    private int facScroll = 0;
    private int whSel = -1;
    /** 弹头开关编辑态（选中阵营时同步一次；保存时随 areaId 一起发出）。 */
    private boolean whEnabled = false;

    private EditBox areaBox;
    private int whToggleY, whAreaY, whSaveY;
    // 目标区域下拉（列出 areas 的 id，点选填入输入框，避免管理员手抄 id）
    private boolean ddOpen = false;
    private int ddIdx = -1;
    private int ddScroll = 0;
    private int ddW = 0;
    private static final int DD_H = 16;
    private static final int DD_ITEM_H = 14;
    private static final int DD_MAX_VISIBLE = 8;
    private static final int DD_BTN_W = 56;

    private String notice = "";
    private long noticeUntil = 0;

    public RpExtensionTab(RpAdminScreen screen) {
        this.screen = screen;
    }

    /** 由 RpAdminScreen.rebuild 调用（tab==TAB_EXTENSION）。 */
    public void rebuild(int px1, int py1, int px2, int py2) {
        this.px1 = px1;
        this.py1 = py1;
        this.px2 = px2;
        this.py2 = py2;
        int top = py1 + 76;
        int mid = px1 + (px2 - px1) / 2;
        listX1 = px1 + 12;
        listX2 = mid - 10;
        facX1 = mid + 6;
        facX2 = px2 - 12;
        // 左列：字段与按钮自下而上定位（列表吃掉剩余高度），避免最小面板高度下溢出面板
        edBtnY = py2 - 64;
        edXyz2Y = edBtnY - 24;
        edXyz1Y = edXyz2Y - 22;
        edDimY = edXyz1Y - 22;
        edNameY = edDimY - 22;
        edIdY = edNameY - 22;
        listY1 = top + 14;
        listY2 = Math.max(listY1 + 20, edIdY - 24);
        int fw = Math.max(60, listX2 - listX1 - LBL_W);
        int cw3 = Math.max(30, (fw - 8) / 3);
        int ex = listX1 + LBL_W;
        idBox = box(idBox, "id", ex, edIdY, fw, 64);
        nameBox = box(nameBox, "name", ex, edNameY, fw, 64);
        dimBox = box(dimBox, "dim", ex, edDimY, fw, 128);
        x1Box = box(x1Box, "x1", ex, edXyz1Y, cw3, 16);
        y1Box = box(y1Box, "y1", ex + cw3 + 4, edXyz1Y, cw3, 16);
        z1Box = box(z1Box, "z1", ex + 2 * (cw3 + 4), edXyz1Y, cw3, 16);
        x2Box = box(x2Box, "x2", ex, edXyz2Y, cw3, 16);
        y2Box = box(y2Box, "y2", ex + cw3 + 4, edXyz2Y, cw3, 16);
        z2Box = box(z2Box, "z2", ex + 2 * (cw3 + 4), edXyz2Y, cw3, 16);

        // 右列：目标区域输入行 + 开关按钮 + 保存按钮自下而上定位
        whSaveY = py2 - 64;
        whAreaY = whSaveY - 24;
        whToggleY = whAreaY - 24;
        facY1 = top + 14;
        facY2 = Math.max(facY1 + 20, whToggleY - 10);
        ddW = Math.max(80, facX2 - facX1 - LBL_W - DD_BTN_W - 6);
        areaBox = box(areaBox, "area", facX1 + LBL_W, whAreaY, ddW, 64);

        // rebuild 会先 clearWidgets 清空全部控件，输入框必须每次重新注册
        scroll = clampScroll(scroll, areas().size(), listY2 - listY1 - HDR);
        facScroll = clampScroll(facScroll, facs().size(), facY2 - facY1 - HDR);
        ddOpen = false;
        ddScroll = 0;
        if (selIndex >= 0 && selIndex < areas().size()) {
            loadAreaEditor(selIndex);
        } else {
            clearAreaEditor();
        }
        if (whSel >= 0 && whSel < facs().size()) {
            loadWarheadEditor(whSel);
        } else {
            clearWarheadEditor();
        }
    }

    /** 复用式输入框：首次创建，之后只调位（rebuild 已清空控件，故每次都要重新注册）。 */
    private EditBox box(EditBox existing, String nar, int x, int y, int w, int maxLen) {
        EditBox b = existing;
        if (b == null) {
            b = new EditBox(Minecraft.getInstance().font, x, y, w, 18, Component.literal(nar));
            b.setMaxLength(maxLen);
            b.setTextColor(RpTheme.CYAN);
        } else {
            b.setX(x);
            b.setY(y);
            b.setWidth(w);
        }
        screen.addXpWidget(b);
        return b;
    }

    private static int clampScroll(int cur, int total, int viewH) {
        int maxVisible = Math.max(1, viewH / ROW_H);
        return Math.max(0, Math.min(cur, Math.max(0, total - maxVisible)));
    }

    // ---------- 数据 ----------

    private static List<JsonObject> areas() {
        return ClientCharacterState.areas();
    }

    private static List<JsonObject> facs() {
        return ClientCharacterState.factions();
    }

    private static String str(JsonObject o, String key, String def) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static boolean boolOf(JsonObject o, String key, boolean def) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive()
                ? o.get(key).getAsBoolean()
                : def;
    }

    /** 整数坐标（缺失/非法返回 null：调用方给出提示而不发包）。 */
    private static Integer intOf(String s) {
        try {
            return (int) Math.floor(Double.parseDouble(s == null ? "" : s.trim()));
        } catch (Exception e) {
            return null;
        }
    }

    private static String intText(JsonObject o, String key) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive()
                    ? String.valueOf((int) Math.floor(o.get(key).getAsDouble()))
                    : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String onOff(boolean on) {
        return Component.translatable(on ? "ccnr_rp.gui.admin.value.on" : "ccnr_rp.gui.admin.value.off")
                .getString();
    }

    private static String facLabel(JsonObject f) {
        String name = str(f, "name", "");
        String id = str(f, "id", "");
        return name.isBlank() ? id : name + " (" + id + ")";
    }

    private void notice(String key) {
        notice = Component.translatable(key).getString();
        noticeUntil = System.currentTimeMillis() + 2600;
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    /** 请求重新同步角色列表（服务端编辑已落盘，刷新展示新区域/弹头状态）。 */
    private void refresh() {
        com.ccnrcom.rp.network.RpChannels.sendToServer(new com.ccnrcom.rp.network.RpPackets.RequestCharacterListC2S());
    }

    // ---------- 编辑器装载 ----------

    private void clearAreaEditor() {
        selIndex = -1;
        if (idBox == null) {
            return;
        }
        idBox.setValue("");
        nameBox.setValue("");
        dimBox.setValue("minecraft:overworld");
        setXyz("", "", "", "", "", "");
    }

    private void setXyz(String a, String b, String c, String d, String e, String f) {
        x1Box.setValue(a);
        y1Box.setValue(b);
        z1Box.setValue(c);
        x2Box.setValue(d);
        y2Box.setValue(e);
        z2Box.setValue(f);
    }

    private void loadAreaEditor(int index) {
        List<JsonObject> list = areas();
        if (index < 0 || index >= list.size()) {
            return;
        }
        JsonObject a = list.get(index);
        selIndex = index;
        idBox.setValue(str(a, "id", ""));
        nameBox.setValue(str(a, "name", ""));
        dimBox.setValue(str(a, "dim", "minecraft:overworld"));
        setXyz(
                intText(a, "x1"),
                intText(a, "y1"),
                intText(a, "z1"),
                intText(a, "x2"),
                intText(a, "y2"),
                intText(a, "z2"));
    }

    private void clearWarheadEditor() {
        whSel = -1;
        whEnabled = false;
        ddIdx = -1;
        if (areaBox != null) {
            areaBox.setValue("");
        }
    }

    private void loadWarheadEditor(int index) {
        List<JsonObject> list = facs();
        if (index < 0 || index >= list.size()) {
            return;
        }
        JsonObject f = list.get(index);
        whSel = index;
        whEnabled = boolOf(f, "warheadEnabled", false);
        String areaId = str(f, "warheadArea", "");
        areaBox.setValue(areaId);
        ddIdx = indexOfArea(areaId);
    }

    /** 区域 id 在 areas 中的下标（无匹配返回 -1）。 */
    private static int indexOfArea(String areaId) {
        List<JsonObject> list = areas();
        for (int i = 0; i < list.size(); i++) {
            if (str(list.get(i), "id", "").equals(areaId)) {
                return i;
            }
        }
        return -1;
    }

    // ---------- 区域编辑动作 ----------

    /** 区域编辑器载荷（坐标非法返回 null 并给出提示——服务端仍有权威校验，客户端先挡一次）。 */
    private JsonObject areaPayload() {
        String id = idBox.getValue().trim();
        if (id.isBlank()) {
            notice("ccnr_rp.gui.admin.area.id_required");
            return null;
        }
        Integer x1 = intOf(x1Box.getValue());
        Integer y1 = intOf(y1Box.getValue());
        Integer z1 = intOf(z1Box.getValue());
        Integer x2 = intOf(x2Box.getValue());
        Integer y2 = intOf(y2Box.getValue());
        Integer z2 = intOf(z2Box.getValue());
        if (x1 == null || y1 == null || z1 == null || x2 == null || y2 == null || z2 == null) {
            notice("ccnr_rp.gui.admin.area.bad_number");
            return null;
        }
        JsonObject p = new JsonObject();
        p.addProperty("id", id);
        p.addProperty("name", nameBox.getValue().trim());
        String dim = dimBox.getValue().trim();
        p.addProperty("dim", dim.isBlank() ? "minecraft:overworld" : dim);
        p.addProperty("x1", x1);
        p.addProperty("y1", y1);
        p.addProperty("z1", z1);
        p.addProperty("x2", x2);
        p.addProperty("y2", y2);
        p.addProperty("z2", z2);
        return p;
    }

    private void addArea() {
        JsonObject p = areaPayload();
        if (p == null) {
            return;
        }
        screen.requestCrud("area", "create", p);
        clearAreaEditor();
        refresh();
    }

    private void updateArea() {
        if (selIndex < 0) {
            notice("ccnr_rp.gui.admin.area.select_first");
            return;
        }
        JsonObject p = areaPayload();
        if (p == null) {
            return;
        }
        screen.requestCrud("area", "update", p);
        refresh();
    }

    private void removeArea() {
        if (selIndex < 0) {
            notice("ccnr_rp.gui.admin.area.select_first");
            return;
        }
        JsonObject p = new JsonObject();
        p.addProperty("id", str(areas().get(selIndex), "id", ""));
        screen.requestCrud("area", "delete", p);
        clearAreaEditor();
        refresh();
    }

    // ---------- 阵营弹头动作 ----------

    /** 保存弹头设定（单字段写：enabled + areaId 一起发，areaId 允许留空=不指定区域）。 */
    private void saveWarhead() {
        List<JsonObject> list = facs();
        if (whSel < 0 || whSel >= list.size()) {
            notice("ccnr_rp.gui.admin.warhead.select_first");
            return;
        }
        JsonObject p = new JsonObject();
        p.addProperty("factionId", str(list.get(whSel), "id", ""));
        p.addProperty("enabled", whEnabled);
        p.addProperty("areaId", areaBox.getValue().trim());
        screen.requestCrud("warhead", "set", p);
        refresh();
    }

    // ---------- 交互 ----------

    public boolean mouseClicked(int mx, int my, int button) {
        // 左列按钮：新增 / 保存 / 删除
        int bw = Math.max(48, (listX2 - listX1 - LBL_W - 8) / 3);
        int bx = listX1 + LBL_W;
        if (inRect(mx, my, bx, edBtnY, bx + bw, edBtnY + 20)) {
            addArea();
            return true;
        }
        if (inRect(mx, my, bx + bw + 4, edBtnY, bx + 2 * bw + 4, edBtnY + 20)) {
            updateArea();
            return true;
        }
        if (inRect(mx, my, bx + 2 * (bw + 4), edBtnY, bx + 3 * bw + 8, edBtnY + 20)) {
            removeArea();
            return true;
        }
        // 区域列表行选中
        List<JsonObject> list = areas();
        for (int i = 0; i < list.size(); i++) {
            int ry = listY1 + HDR + (i - scroll) * ROW_H;
            if (ry >= listY1 - ROW_H && ry <= listY2 && inRect(mx, my, listX1, ry, listX2, ry + ROW_H)) {
                loadAreaEditor(i);
                return true;
            }
        }
        // 右列：开关 / 目标区域下拉按钮 / 保存
        if (inRect(mx, my, facX1, whToggleY, facX2, whToggleY + 18)) {
            whEnabled = !whEnabled;
            return true;
        }
        if (inRect(mx, my, facX1 + LBL_W + ddW + 6, whAreaY, facX2, whAreaY + DD_H)) {
            if (!areas().isEmpty()) {
                ddOpen = !ddOpen;
            }
            return true;
        }
        if (inRect(mx, my, facX1, whSaveY, facX2, whSaveY + 20)) {
            saveWarhead();
            return true;
        }
        // 阵营列表行选中
        List<JsonObject> facList = facs();
        for (int i = 0; i < facList.size(); i++) {
            int ry = facY1 + HDR + (i - facScroll) * ROW_H;
            if (ry >= facY1 - ROW_H && ry <= facY2 && inRect(mx, my, facX1, ry, facX2, ry + ROW_H)) {
                loadWarheadEditor(i);
                return true;
            }
        }
        return false;
    }

    /** 下拉弹层命中（须在 super.mouseClicked / widget 分发之前调用，弹层盖住输入框）。 */
    public boolean mouseClickedOverlay(int mx, int my, int button) {
        if (button != 0) {
            return false;
        }
        if (ddOpen) {
            for (int i = ddScroll; i < areas().size() && i < ddScroll + DD_MAX_VISIBLE; i++) {
                int y1 = ddPopupItemY(i);
                if (inRect(mx, my, ddPopupX(), y1, ddPopupX() + ddW, y1 + DD_ITEM_H)) {
                    ddIdx = i;
                    ddOpen = false;
                    areaBox.setValue(str(areas().get(i), "id", ""));
                    return true;
                }
            }
        }
        if (inRect(mx, my, facX1 + LBL_W + ddW + 6, whAreaY, facX2, whAreaY + DD_H)) {
            if (!areas().isEmpty()) {
                ddOpen = !ddOpen;
            }
            return true;
        }
        if (ddOpen) {
            ddOpen = false;
            return false;
        }
        return false;
    }

    public void mouseScrolled(int mouseX, int mouseY, double delta) {
        if (ddOpen
                && mouseX >= ddPopupX()
                && mouseX <= ddPopupX() + ddW
                && mouseY >= ddPopupTop()
                && mouseY <= ddPopupTop() + ddPopupH()) {
            int maxDD = Math.max(0, areas().size() - DD_MAX_VISIBLE);
            ddScroll = (int) Math.max(0, Math.min(ddScroll + (delta > 0 ? -1 : 1), maxDD));
            return;
        }
        if (mouseX >= listX1 && mouseX <= listX2 && mouseY >= listY1 && mouseY <= listY2) {
            scroll = clampScroll(scroll + (delta > 0 ? -2 : 2), areas().size(), listY2 - listY1 - HDR);
            return;
        }
        if (mouseX >= facX1 && mouseX <= facX2 && mouseY >= facY1 && mouseY <= facY2) {
            facScroll = clampScroll(facScroll + (delta > 0 ? -2 : 2), facs().size(), facY2 - facY1 - HDR);
        }
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return false;
    }

    // ---------- 目标区域下拉几何 ----------

    private int ddPopupX() {
        return facX1 + LBL_W;
    }

    private int ddPopupTop() {
        return whAreaY + DD_H + 2;
    }

    private int ddPopupH() {
        return Math.min(areas().size(), DD_MAX_VISIBLE) * DD_ITEM_H + 2;
    }

    private int ddPopupItemY(int i) {
        return ddPopupTop() + 1 + (i - ddScroll) * DD_ITEM_H;
    }

    private static boolean inRect(int mx, int my, int x1, int y1, int x2, int y2) {
        return mx >= x1 && mx <= x2 && my >= y1 && my <= y2;
    }

    // ---------- 渲染 ----------

    public void render(GuiGraphics g, int mx, int my) {
        var font = Minecraft.getInstance().font;
        List<JsonObject> list = areas();
        // 左：区域列表
        RpRoundRect.outlined(
                g, listX1 - 2, listY1 - 4, listX2 + 2, listY2 + 2, 4f, RpTheme.PANEL_BORDER, RpTheme.PANEL_BG_EVEN);
        g.drawString(
                font,
                tr("ccnr_rp.gui.admin.area.list") + " (" + list.size() + ")",
                listX1 + 4,
                listY1 + 1,
                RpTheme.TEXT_DIM);
        g.fill(listX1, listY1 + HDR - 1, listX2, listY1 + HDR, RpTheme.PANEL_BORDER);
        g.enableScissor(listX1, listY1 + HDR, listX2, listY2);
        for (int i = 0; i < list.size(); i++) {
            int ry = listY1 + HDR + (i - scroll) * ROW_H;
            if (ry < listY1 - ROW_H || ry > listY2) {
                continue;
            }
            boolean isSel = i == selIndex;
            if (isSel) {
                RpTheme.selectedBar(g, listX1, ry, listX2, ry + ROW_H, 3f);
            } else if (i % 2 == 0) {
                g.fill(listX1, ry, listX2, ry + ROW_H, RpTheme.PANEL_BG_EVEN);
            }
            g.drawString(
                    font,
                    clip(font, areaRowText(list.get(i)), (listX2 - listX1) - 8),
                    listX1 + 4,
                    ry + 5,
                    isSel ? RpTheme.ACCENT_TEXT : RpTheme.TEXT_PRIMARY);
        }
        g.disableScissor();
        if (list.isEmpty()) {
            g.drawString(font, tr("ccnr_rp.gui.admin.area.empty"), listX1 + 4, listY1 + HDR + 4, RpTheme.TEXT_DIM);
        }

        // 左：编辑器（标签在左槽，输入框由 super.render 绘制在本层之上）
        g.drawString(font, tr("ccnr_rp.gui.admin.area.id"), listX1, edIdY + 5, RpTheme.TEXT_DIM);
        g.drawString(font, tr("ccnr_rp.gui.admin.area.name"), listX1, edNameY + 5, RpTheme.TEXT_DIM);
        g.drawString(font, tr("ccnr_rp.gui.admin.area.dim"), listX1, edDimY + 5, RpTheme.TEXT_DIM);
        g.drawString(font, tr("ccnr_rp.gui.admin.area.bounds"), listX1, edXyz1Y + 5, RpTheme.TEXT_DIM);
        g.drawString(font, tr("ccnr_rp.gui.admin.area.bounds2"), listX1, edXyz2Y + 5, RpTheme.TEXT_DIM);
        g.drawString(font, tr("ccnr_rp.gui.admin.area.hint"), listX1, py2 - 36, RpTheme.TEXT_DIM);
        int bw = Math.max(48, (listX2 - listX1 - LBL_W - 8) / 3);
        int bx = listX1 + LBL_W;
        RpButton.draw(
                g,
                bx,
                edBtnY,
                bx + bw,
                edBtnY + 20,
                tr("ccnr_rp.gui.admin.area.add"),
                inRect(mx, my, bx, edBtnY, bx + bw, edBtnY + 20) ? RpTheme.PANEL_BORDER_BRIGHT : RpTheme.PANEL_BORDER,
                true);
        RpButton.draw(
                g,
                bx + bw + 4,
                edBtnY,
                bx + 2 * bw + 4,
                edBtnY + 20,
                tr("ccnr_rp.gui.admin.area.update"),
                inRect(mx, my, bx + bw + 4, edBtnY, bx + 2 * bw + 4, edBtnY + 20)
                        ? RpTheme.PANEL_BORDER_BRIGHT
                        : RpTheme.PANEL_BORDER,
                false);
        RpButton.draw(
                g,
                bx + 2 * (bw + 4),
                edBtnY,
                bx + 3 * bw + 8,
                edBtnY + 20,
                tr("ccnr_rp.gui.admin.area.remove"),
                inRect(mx, my, bx + 2 * (bw + 4), edBtnY, bx + 3 * bw + 8, edBtnY + 20)
                        ? RpTheme.PANEL_BORDER_BRIGHT
                        : RpTheme.PANEL_BORDER,
                false);

        // 右：阵营弹头
        List<JsonObject> facList = facs();
        RpRoundRect.outlined(
                g, facX1 - 2, facY1 - 4, facX2 + 2, facY2 + 2, 4f, RpTheme.PANEL_BORDER, RpTheme.PANEL_BG_EVEN);
        g.drawString(
                font,
                tr("ccnr_rp.gui.admin.warhead.list") + " (" + facList.size() + ")",
                facX1 + 4,
                facY1 + 1,
                RpTheme.TEXT_DIM);
        g.fill(facX1, facY1 + HDR - 1, facX2, facY1 + HDR, RpTheme.PANEL_BORDER);
        g.enableScissor(facX1, facY1 + HDR, facX2, facY2);
        for (int i = 0; i < facList.size(); i++) {
            int ry = facY1 + HDR + (i - facScroll) * ROW_H;
            if (ry < facY1 - ROW_H || ry > facY2) {
                continue;
            }
            JsonObject f = facList.get(i);
            boolean isSel = i == whSel;
            if (isSel) {
                RpTheme.selectedBar(g, facX1, ry, facX2, ry + ROW_H, 3f);
            } else if (i % 2 == 0) {
                g.fill(facX1, ry, facX2, ry + ROW_H, RpTheme.PANEL_BG_EVEN);
            }
            g.drawString(
                    font,
                    clip(font, warheadRowText(f), (facX2 - facX1) - 8),
                    facX1 + 4,
                    ry + 5,
                    isSel ? RpTheme.ACCENT_TEXT : RpTheme.TEXT_PRIMARY);
        }
        g.disableScissor();
        if (facList.isEmpty()) {
            g.drawString(font, tr("ccnr_rp.gui.admin.warhead.empty"), facX1 + 4, facY1 + HDR + 4, RpTheme.TEXT_DIM);
        }

        RpButton.draw(
                g,
                facX1,
                whToggleY,
                facX2,
                whToggleY + 18,
                tr("ccnr_rp.gui.admin.warhead.enabled") + ": " + onOff(whEnabled),
                inRect(mx, my, facX1, whToggleY, facX2, whToggleY + 18)
                        ? RpTheme.PANEL_BORDER_BRIGHT
                        : RpTheme.PANEL_BORDER,
                whEnabled);
        g.drawString(font, tr("ccnr_rp.gui.admin.warhead.target_area"), facX1, whAreaY + 5, RpTheme.TEXT_DIM);
        drawDropdown(g, mx, my);
        RpButton.draw(
                g,
                facX1,
                whSaveY,
                facX2,
                whSaveY + 20,
                tr("ccnr_rp.gui.admin.warhead.save"),
                inRect(mx, my, facX1, whSaveY, facX2, whSaveY + 20)
                        ? RpTheme.PANEL_BORDER_BRIGHT
                        : RpTheme.PANEL_BORDER,
                true);
        g.drawString(font, tr("ccnr_rp.gui.admin.warhead.hint"), facX1, py2 - 36, RpTheme.TEXT_DIM);

        if (System.currentTimeMillis() < noticeUntil) {
            g.drawCenteredString(font, Component.literal(notice), (px1 + px2) / 2, py2 - 24, RpTheme.TEXT_SECONDARY);
        }
    }

    public void renderOverlay(GuiGraphics g, int mx, int my) {
        if (ddOpen) {
            drawPopup(g, mx, my);
        }
    }

    private static String areaRowText(JsonObject a) {
        String id = str(a, "id", "");
        String name = str(a, "name", "");
        String head = name.isBlank() || name.equals(id) ? id : name + " (" + id + ")";
        return head
                + "  "
                + str(a, "dim", "")
                + "  ["
                + intText(a, "x1") + "," + intText(a, "y1") + "," + intText(a, "z1") + " → "
                + intText(a, "x2") + "," + intText(a, "y2") + "," + intText(a, "z2") + "]";
    }

    private static String warheadRowText(JsonObject f) {
        String area = str(f, "warheadArea", "");
        return facLabel(f) + "  " + onOff(boolOf(f, "warheadEnabled", false)) + (area.isBlank() ? "" : "  → " + area);
    }

    private void drawDropdown(GuiGraphics g, int mx, int my) {
        var font = Minecraft.getInstance().font;
        int x = facX1 + LBL_W + ddW + 6;
        int y = whAreaY;
        boolean hov = inRect(mx, my, x, y, facX2, y + DD_H);
        RpRoundRect.outlined(
                g,
                x,
                y,
                facX2,
                y + DD_H,
                3f,
                ddOpen || hov ? RpTheme.PANEL_BORDER_BRIGHT : RpTheme.PANEL_BORDER,
                ddOpen ? RpTheme.PANEL_BG_ALT : RpTheme.PANEL_BG);
        g.drawString(
                font,
                Component.literal(clip(font, tr("ccnr_rp.gui.admin.warhead.pick_area"), DD_BTN_W - 12)),
                x + 4,
                y + 3,
                areas().isEmpty() ? RpTheme.TEXT_DIM : RpTheme.TEXT_PRIMARY);
        int cx = x + DD_BTN_W - 8;
        int cy = y + DD_H / 2;
        g.fill(cx - 3, cy - 1, cx + 4, cy, RpTheme.TEXT_SECONDARY);
        g.fill(cx - 2, cy, cx + 3, cy + 1, RpTheme.TEXT_SECONDARY);
        g.fill(cx - 1, cy + 1, cx + 2, cy + 2, RpTheme.TEXT_SECONDARY);
    }

    private void drawPopup(GuiGraphics g, int mx, int my) {
        var font = Minecraft.getInstance().font;
        int x = ddPopupX();
        int top = ddPopupTop();
        int h = ddPopupH();
        RpRoundRect.outlined(g, x, top, x + ddW, top + h, 4f, RpTheme.PANEL_BORDER_BRIGHT, RpTheme.PANEL_BG_ALT);
        g.enableScissor(x, top, x + ddW, top + h);
        List<JsonObject> list = areas();
        for (int i = ddScroll; i < list.size() && i < ddScroll + DD_MAX_VISIBLE; i++) {
            int y1 = ddPopupItemY(i);
            int y2 = y1 + DD_ITEM_H;
            boolean hov = inRect(mx, my, x, y1, x + ddW, y2);
            if (hov) {
                g.fill(x + 1, y1, x + ddW - 1, y2, RpTheme.PANEL_BORDER);
            }
            g.drawString(
                    font,
                    Component.literal(clip(font, areaRowText(list.get(i)), ddW - 10)),
                    x + 5,
                    y1 + 3,
                    i == ddIdx ? RpTheme.CYAN : (hov ? RpTheme.TEXT_PRIMARY : RpTheme.TEXT_SECONDARY));
        }
        g.disableScissor();
    }

    /** 按像素宽度裁剪文本（超宽截断加省略号）。 */
    private static String clip(net.minecraft.client.gui.Font font, String s, int maxW) {
        if (font.width(s) <= maxW) {
            return s;
        }
        String out = s;
        while (!out.isEmpty() && font.width(out + "…") > maxW) {
            out = out.substring(0, out.length() - 1);
        }
        return out + "…";
    }
}
