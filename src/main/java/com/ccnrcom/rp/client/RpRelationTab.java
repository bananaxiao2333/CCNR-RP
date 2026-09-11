/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.ccnrcom.rp.faction.RelationType;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * 管理面板「关系管理」页签（与「经验规则」页签并列）：关系规则列表 + 编辑器。
 * 左侧规则列表（从上到下优先级），右侧编辑 from/to（多阵营/组、逗号分隔）、类型三选，
 * 留空 to = 内部关系（列表内两两互设）；新增/保存/删除走 RelationEditC2S →
 * 服务端权威校验+落盘。页签内提供「打开关系测定图」入口（全屏图，关闭返回管理面板）。
 */
public final class RpRelationTab {

    private final RpAdminScreen screen;
    private int px1, py1, px2, py2;
    private int listX1, listX2, listY1, listY2;
    private int scroll = 0;
    private static final int ROW_H = 20;
    /** 表头带高度：表头独立一行，行列表从表头下方开始，避免表头与首行文字重叠。 */
    private static final int HDR = 13;

    private EditBox fromBox;
    private EditBox toBox;
    private RelationType selType = RelationType.NEUTRAL;
    private int selIndex = -1;
    /** 选中规则的原始 from/to（保存/删除定位用；编辑 from/to 后仍按选中规则原位替换/删除）。 */
    private List<String> origFrom = List.of();

    private List<String> origTo = List.of();
    private String notice = "";
    private long noticeUntil = 0;

    // 阵营下拉注入（from/to 各一）：下拉选阵营，注入按钮把 id 追加到对应输入框（逗号分隔、去重）
    private boolean fromDdOpen = false;
    private boolean toDdOpen = false;
    private int fromDdIdx = 0;
    private int toDdIdx = 0;
    private int fromDdScroll = 0;
    private int toDdScroll = 0;
    private int ddW = 0;
    private static final int DD_H = 16;
    private static final int DD_ITEM_H = 14;
    private static final int DD_MAX_VISIBLE = 8;
    private static final int INJECT_W = 52;

    public RpRelationTab(RpAdminScreen screen) {
        this.screen = screen;
    }

    /** 由 RpAdminScreen.rebuild 调用（tab==TAB_RELATION）。 */
    public void rebuild(int px1, int py1, int px2, int py2) {
        this.px1 = px1;
        this.py1 = py1;
        this.px2 = px2;
        this.py2 = py2;
        listX1 = px1 + 12;
        listX2 = listX1 + Math.min(300, (px2 - px1) * 42 / 100);
        // 内容区与其它页签对齐：页签栏占 py1+42..py1+62，内容从 py1+76 起，避免与页签/分隔线重叠
        listY1 = py1 + 76;
        listY2 = py2 - 60;
        int ex = listX2 + 16;
        int ew = px2 - ex - 12;
        // 阵营下拉宽度（留出注入按钮空间）
        ddW = Math.max(60, Math.min(ew - INJECT_W - 6, ew * 55 / 100));
        if (fromBox == null) {
            fromBox = new EditBox(Minecraft.getInstance().font, ex, listY1 + 38, ew, 18, Component.literal("from"));
            fromBox.setMaxLength(256);
            toBox = new EditBox(Minecraft.getInstance().font, ex, listY1 + 94, ew, 18, Component.literal("to"));
            toBox.setMaxLength(256);
        } else {
            fromBox.setX(ex);
            fromBox.setWidth(ew);
            fromBox.setY(listY1 + 38);
            toBox.setX(ex);
            toBox.setWidth(ew);
            toBox.setY(listY1 + 94);
        }
        // rebuild 会先 clearWidgets 清空全部控件，输入框必须每次重新注册，否则不渲染也不接收输入
        screen.addXpWidget(fromBox);
        screen.addXpWidget(toBox);
        // 重建收起下拉弹层并钳制选中下标
        fromDdOpen = false;
        toDdOpen = false;
        fromDdScroll = 0;
        toDdScroll = 0;
        int facSize = ClientCharacterState.factions().size();
        fromDdIdx = facSize == 0 ? -1 : Math.min(fromDdIdx, facSize - 1);
        toDdIdx = facSize == 0 ? -1 : Math.min(toDdIdx, facSize - 1);
        // 数据刷新等触发的重建保留已选规则的编辑内容；无选中则复位
        if (selIndex >= 0 && selIndex < rules().size()) {
            loadEditor(selIndex);
        } else {
            clearEditor();
        }
    }

    private int editorX() {
        return listX2 + 16;
    }

    private int editorY() {
        // 类型三选：位于 to 输入框（listY1+94..112）下方
        return listY1 + 118;
    }

    private int actionY() {
        return editorY() + 34;
    }

    private List<JsonObject> rules() {
        return ClientCharacterState.relationRules();
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
        origFrom = List.of();
        origTo = List.of();
        if (fromBox != null) {
            fromBox.setValue("");
        }
        if (toBox != null) {
            toBox.setValue("");
        }
        selType = RelationType.NEUTRAL;
    }

    private void loadEditor(int index) {
        List<JsonObject> rules = rules();
        if (index < 0 || index >= rules.size()) {
            return;
        }
        JsonObject r = rules.get(index);
        selIndex = index;
        origFrom = idList(r, "from");
        origTo = idList(r, "to");
        fromBox.setValue(join(origFrom));
        toBox.setValue(join(origTo));
        selType = RelationType.parse(str(r, "type", "neutral"));
        if (selType == null) {
            selType = RelationType.NEUTRAL;
        }
    }

    private void notice(String key) {
        notice = Component.translatable(key).getString();
        noticeUntil = System.currentTimeMillis() + 2600;
    }

    // ---------- 交互 ----------

    public boolean mouseClicked(int mx, int my, int button) {
        // 类型三选
        int bw = 54;
        int ex = editorX();
        int ey = editorY();
        for (int i = 0; i < 3; i++) {
            int bx = ex + i * (bw + 6);
            if (mx >= bx && mx <= bx + bw && my >= ey && my <= ey + 20) {
                selType = switch (i) {
                    case 0 -> RelationType.NEUTRAL;
                    case 1 -> RelationType.HOSTILE;
                    default -> RelationType.FRIENDLY;};
                return true;
            }
        }
        // 操作按钮：新增 / 保存 / 删除
        int ay = actionY();
        int aw = 64;
        if (mx >= ex && mx <= ex + aw && my >= ay && my <= ay + 20) {
            addRule();
            return true;
        }
        if (mx >= ex + aw + 6 && mx <= ex + aw + 6 + aw && my >= ay && my <= ay + 20) {
            updateRule();
            return true;
        }
        if (mx >= ex + 2 * (aw + 6) && mx <= ex + 2 * (aw + 6) + aw && my >= ay && my <= ay + 20) {
            removeRule();
            return true;
        }
        // 测定图入口（全屏；关闭返回管理面板）
        if (mx >= ex && mx <= ex + aw + 6 + aw && my >= ay + 26 && my <= ay + 46) {
            Minecraft.getInstance().setScreen(new FactionGraphScreen());
            return true;
        }
        // 列表行选择（右键 = 删除该规则，先二次确认）
        List<JsonObject> rules = rules();
        for (int i = 0; i < rules.size(); i++) {
            int ry = listY1 + HDR + (i - scroll) * ROW_H;
            if (ry >= listY1 - ROW_H && ry <= listY2 && mx >= listX1 && mx <= listX2 && my >= ry && my <= ry + ROW_H) {
                if (button == 1) {
                    loadEditor(i); // 先定位到该行（删除用的是选中规则的原始 from/to）
                    askRemoveRule();
                } else {
                    loadEditor(i);
                }
                return true;
            }
        }
        return false;
    }

    public void mouseScrolled(int mouseX, int mouseY, double delta) {
        // 阵营下拉弹层：弹层内滚轮滚动选项
        int ex = editorX();
        List<JsonObject> facs = ClientCharacterState.factions();
        int maxScroll = Math.max(0, facs.size() - DD_MAX_VISIBLE);
        if (fromDdOpen
                && mouseX >= ex
                && mouseX <= ex + ddW
                && mouseY >= ddPopupTop(true)
                && mouseY <= ddPopupTop(true) + ddPopupH(true)) {
            fromDdScroll = (int) Math.max(0, Math.min(fromDdScroll + (delta > 0 ? -1 : 1), maxScroll));
            return;
        }
        if (toDdOpen
                && mouseX >= ex
                && mouseX <= ex + ddW
                && mouseY >= ddPopupTop(false)
                && mouseY <= ddPopupTop(false) + ddPopupH(false)) {
            toDdScroll = (int) Math.max(0, Math.min(toDdScroll + (delta > 0 ? -1 : 1), maxScroll));
            return;
        }
        if (mouseX >= listX1 && mouseX <= listX2 && mouseY >= listY1 && mouseY <= listY2) {
            int max = Math.max(0, rules().size() - Math.max(1, (listY2 - listY1 - HDR) / ROW_H));
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

    /** 下拉本体 y。 */
    private int ddY(boolean isFrom) {
        return listY1 + (isFrom ? 16 : 72);
    }

    /** 弹层顶部 y。 */
    private int ddPopupTop(boolean isFrom) {
        return ddY(isFrom) + DD_H + 2;
    }

    /** 弹层可见高度（最多 DD_MAX_VISIBLE 项）。 */
    private int ddPopupH(boolean isFrom) {
        int n = ClientCharacterState.factions().size();
        return Math.min(n, DD_MAX_VISIBLE) * DD_ITEM_H + 2;
    }

    /** 弹层第 i 项 y 起点（含滚动偏移）。 */
    private int ddPopupItemY(boolean isFrom, int i) {
        int scroll = isFrom ? fromDdScroll : toDdScroll;
        return ddPopupTop(isFrom) + 1 + (i - scroll) * DD_ITEM_H;
    }

    /**
     * 阵营下拉与注入按钮命中（须在 RpAdminScreen 的 super.mouseClicked / widget 分发**之前**调用：
     * 弹层展开时会盖住 from/to 输入框等控件，必须先命中弹层项/下拉/注入按钮）。
     */
    public boolean mouseClickedOverlay(int mx, int my, int button) {
        if (button != 0) {
            return false;
        }
        int ex = editorX();
        List<JsonObject> facs = ClientCharacterState.factions();
        // 弹层项（展开时优先，弹层盖住下方控件）
        if (fromDdOpen) {
            for (int i = fromDdScroll; i < facs.size() && i < fromDdScroll + DD_MAX_VISIBLE; i++) {
                int y1 = ddPopupItemY(true, i);
                if (mx >= ex && mx <= ex + ddW && my >= y1 && my <= y1 + DD_ITEM_H) {
                    fromDdIdx = i;
                    fromDdOpen = false;
                    return true;
                }
            }
        }
        if (toDdOpen) {
            for (int i = toDdScroll; i < facs.size() && i < toDdScroll + DD_MAX_VISIBLE; i++) {
                int y1 = ddPopupItemY(false, i);
                if (mx >= ex && mx <= ex + ddW && my >= y1 && my <= y1 + DD_ITEM_H) {
                    toDdIdx = i;
                    toDdOpen = false;
                    return true;
                }
            }
        }
        // 下拉本体：点击切换展开
        if (mx >= ex && mx <= ex + ddW && my >= ddY(true) && my <= ddY(true) + DD_H) {
            if (!facs.isEmpty()) {
                fromDdOpen = !fromDdOpen;
                toDdOpen = false;
            }
            return true;
        }
        if (mx >= ex && mx <= ex + ddW && my >= ddY(false) && my <= ddY(false) + DD_H) {
            if (!facs.isEmpty()) {
                toDdOpen = !toDdOpen;
                fromDdOpen = false;
            }
            return true;
        }
        // 注入按钮：把下拉选中的阵营 id 追加到对应输入框
        if (mx >= ex + ddW + 6 && mx <= ex + ddW + 6 + INJECT_W && my >= ddY(true) && my <= ddY(true) + DD_H) {
            injectFaction(true);
            return true;
        }
        if (mx >= ex + ddW + 6 && mx <= ex + ddW + 6 + INJECT_W && my >= ddY(false) && my <= ddY(false) + DD_H) {
            injectFaction(false);
            return true;
        }
        // 点击弹层外：收起弹层并放行到底层控件
        if (fromDdOpen || toDdOpen) {
            fromDdOpen = false;
            toDdOpen = false;
            return false;
        }
        return false;
    }

    /** 把下拉选中的阵营 id 追加到对应输入框（逗号分隔、去重）。 */
    private void injectFaction(boolean isFrom) {
        List<JsonObject> facs = ClientCharacterState.factions();
        int idx = isFrom ? fromDdIdx : toDdIdx;
        if (facs.isEmpty() || idx < 0 || idx >= facs.size()) {
            return;
        }
        String id = str(facs.get(idx), "id", "");
        if (id.isBlank()) {
            return;
        }
        EditBox box = isFrom ? fromBox : toBox;
        List<String> cur = new ArrayList<>();
        for (String s : box.getValue().split(",")) {
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
        box.setValue(String.join(", ", cur));
        if (isFrom) {
            fromDdOpen = false;
        } else {
            toDdOpen = false;
        }
    }

    /** 下拉项显示名：名字(id)，名字缺失时用 id。 */
    private static String facLabel(JsonObject f) {
        String name = str(f, "name", "");
        String id = str(f, "id", "");
        return name.isBlank() ? id : name + " (" + id + ")";
    }

    // ---------- 编辑动作 ----------

    private JsonObject editorRule() {
        JsonObject rule = new JsonObject();
        JsonArray from = new JsonArray();
        for (String s : fromBox.getValue().split(",")) {
            if (!s.isBlank()) {
                from.add(s.trim());
            }
        }
        rule.add("from", from);
        String toText = toBox.getValue().trim();
        if (!toText.isBlank()) {
            JsonArray to = new JsonArray();
            for (String s : toText.split(",")) {
                if (!s.isBlank()) {
                    to.add(s.trim());
                }
            }
            rule.add("to", to);
        }
        rule.addProperty("type", selType.name().toLowerCase(java.util.Locale.ROOT));
        return rule;
    }

    private void addRule() {
        if (editorRule().getAsJsonArray("from").isEmpty()) {
            notice("ccnr_rp.gui.admin.relation.from_required");
            return;
        }
        JsonObject req = new JsonObject();
        req.addProperty("action", "add");
        req.add("rule", editorRule());
        com.ccnrcom.rp.network.RpChannels.sendToServer(
                new com.ccnrcom.rp.network.RpPackets.RelationEditC2S(req.toString()));
        clearEditor();
        refresh();
    }

    private void updateRule() {
        if (selIndex < 0) {
            notice("ccnr_rp.gui.admin.relation.select_first");
            return;
        }
        if (editorRule().getAsJsonArray("from").isEmpty()) {
            notice("ccnr_rp.gui.admin.relation.from_required");
            return;
        }
        JsonObject req = new JsonObject();
        req.addProperty("action", "update");
        req.add("rule", editorRule());
        // 携带选中规则的原始 from/to，服务端按此定位并原位替换（支持修改 from/to，不会误增新规则）
        req.add("original", originalRule());
        com.ccnrcom.rp.network.RpChannels.sendToServer(
                new com.ccnrcom.rp.network.RpPackets.RelationEditC2S(req.toString()));
        clearEditor();
        refresh();
    }

    private void removeRule() {
        if (selIndex < 0) {
            notice("ccnr_rp.gui.admin.relation.select_first");
            return;
        }
        askRemoveRule();
    }

    /** 删除关系规则：一律先二次确认（右键条目与「删除」按钮共用，docs/01 §10.2）。 */
    private void askRemoveRule() {
        screen.confirmDelete(
                Component.translatable(
                                "ccnr_rp.gui.admin.confirm.del_msg",
                                tr("ccnr_rp.gui.admin.tab.relation"),
                                ruleLabel(selIndex))
                        .getString(),
                () -> {
                    JsonObject req = new JsonObject();
                    req.addProperty("action", "remove");
                    // 删除针对选中规则本身（用原始 from/to 定位），而非输入框当前内容
                    req.add("rule", originalRule());
                    com.ccnrcom.rp.network.RpChannels.sendToServer(
                            new com.ccnrcom.rp.network.RpPackets.RelationEditC2S(req.toString()));
                    clearEditor();
                    refresh();
                });
    }

    /**
     * 规则显示名（用于删除确认文案）：**与列表行同一文案**（内部: a, b / a × b + 类型），
     * 不直接倒 from/to 原始 JSON——那会拼出几十个字符的长串，在确认框里既不可读又撑爆布局。
     */
    private String ruleLabel(int index) {
        List<JsonObject> rules = ClientCharacterState.relationRules();
        if (index < 0 || index >= rules.size()) {
            return "";
        }
        JsonObject r = rules.get(index);
        List<String> from = idList(r, "from");
        List<String> to = idList(r, "to");
        String type = str(r, "type", "neutral");
        String raw = sameSet(from, to)
                ? tr("ccnr_rp.gui.admin.relation.internal") + ": " + sideLabel(from)
                : sideLabel(from) + " × " + sideLabel(to);
        return raw + " " + typeTag(type);
    }

    /** 选中规则的原始 from/to（未选中时为空数组；to 为空则省略 = 内部关系）。 */
    private JsonObject originalRule() {
        JsonObject o = new JsonObject();
        JsonArray from = new JsonArray();
        origFrom.forEach(from::add);
        o.add("from", from);
        if (!origTo.isEmpty()) {
            JsonArray to = new JsonArray();
            origTo.forEach(to::add);
            o.add("to", to);
        }
        return o;
    }

    /** 请求重新同步角色列表（服务端编辑已落盘，刷新展示新规则）。 */
    private void refresh() {
        com.ccnrcom.rp.network.RpChannels.sendToServer(new com.ccnrcom.rp.network.RpPackets.RequestCharacterListC2S());
    }

    // ---------- 渲染 ----------

    public void render(GuiGraphics g, int mx, int my) {
        var font = Minecraft.getInstance().font;
        List<JsonObject> rules = rules();
        // 左侧：规则列表（面板 + 标题 + 可滚动行，与其它页签列表风格一致）
        RpTheme.listPanel(g, listX1 - 2, listY1 - 4, listX2 + 2, listY2 + 2);
        g.drawString(
                font,
                Component.translatable("ccnr_rp.gui.admin.relation.list").getString() + " (" + rules.size() + ")",
                listX1 + 4,
                listY1 + 1,
                RpTheme.TEXT_DIM);
        // 表头带与行列表分隔线（避免表头文字压到首行）
        RpTheme.listHeaderRule(g, listX1, listX2, listY1 + HDR - 1);
        g.enableScissor(listX1, listY1 + HDR, listX2, listY2);
        for (int i = 0; i < rules.size(); i++) {
            int ry = listY1 + HDR + (i - scroll) * ROW_H;
            if (ry < listY1 - ROW_H || ry > listY2) {
                continue;
            }
            JsonObject r = rules.get(i);
            boolean sel = i == selIndex;
            if (sel) {
                RpTheme.selectedBar(g, listX1, ry, listX2, ry + ROW_H, 3f);
            } else {
                RpTheme.listRow(g, listX1, ry, listX2, ry + ROW_H, i, mx, my);
            }
            List<String> from = idList(r, "from");
            List<String> to = idList(r, "to");
            boolean internal = sameSet(from, to);
            String type = str(r, "type", "neutral");
            String raw = internal
                    ? tr("ccnr_rp.gui.admin.relation.internal") + ": " + sideLabel(from)
                    : sideLabel(from) + " × " + sideLabel(to);
            // 行文本按列表宽裁剪，避免长规则名溢出与右侧类型标签重叠
            String tag = typeTag(type);
            int labelMax = (listX2 - listX1) - 4 - 6 - font.width(tag) - 6;
            String label = clip(font, raw, Math.max(20, labelMax));
            g.drawString(font, label, listX1 + 4, ry + 5, sel ? RpTheme.ACCENT_TEXT : RpTheme.TEXT_PRIMARY);
            g.drawString(font, tag, listX1 + 4 + font.width(label) + 6, ry + 5, typeColor(type));
        }
        g.disableScissor();

        // 右侧：编辑器
        int ex = editorX();
        int ey = editorY();
        g.drawString(
                font,
                Component.translatable("ccnr_rp.gui.admin.relation.from"),
                ex,
                listY1 + 4,
                RpTheme.TEXT_SECONDARY);
        g.drawString(
                font, Component.translatable("ccnr_rp.gui.admin.relation.to"), ex, listY1 + 60, RpTheme.TEXT_SECONDARY);
        // 阵营下拉 + 注入按钮（from/to 各一）
        drawDropdown(g, mx, my, true);
        drawDropdown(g, mx, my, false);
        // 类型三选按钮
        int bw = 54;
        for (int i = 0; i < 3; i++) {
            int bx = ex + i * (bw + 6);
            boolean sel =
                    switch (i) {
                        case 0 -> selType == RelationType.NEUTRAL;
                        case 1 -> selType == RelationType.HOSTILE;
                        default -> selType == RelationType.FRIENDLY;
                    };
            int color =
                    switch (i) {
                        case 0 -> RpTheme.NEUTRAL;
                        case 1 -> RpTheme.RED;
                        default -> RpTheme.FRIENDLY;
                    };
            RpRoundRect.outlined(
                    g,
                    bx,
                    ey,
                    bx + bw,
                    ey + 20,
                    3f,
                    sel ? color : RpTheme.PANEL_BORDER,
                    sel ? RpTheme.alphaBlend(color, 0x22) : RpTheme.SURFACE_CONTROL);
            String key =
                    switch (i) {
                        case 0 -> "ccnr_rp.gui.admin.relation.type_neutral";
                        case 1 -> "ccnr_rp.gui.admin.relation.type_hostile";
                        default -> "ccnr_rp.gui.admin.relation.type_friendly";
                    };
            g.drawCenteredString(
                    font, Component.translatable(key), bx + bw / 2, ey + 5, sel ? color : RpTheme.TEXT_PRIMARY);
        }
        // 操作按钮：新增 / 保存 / 删除
        int ay = actionY();
        int aw = 64;
        RpButton.draw(g, ex, ay, ex + aw, ay + 20, tr("ccnr_rp.gui.admin.relation.add"), RpTheme.CYAN_DIM, true);
        RpButton.draw(
                g,
                ex + aw + 6,
                ay,
                ex + aw + 6 + aw,
                ay + 20,
                tr("ccnr_rp.gui.admin.relation.update"),
                RpTheme.PANEL_BORDER_BRIGHT,
                false);
        RpButton.draw(
                g,
                ex + 2 * (aw + 6),
                ay,
                ex + 2 * (aw + 6) + aw,
                ay + 20,
                tr("ccnr_rp.gui.admin.relation.remove"),
                RpTheme.RED_DIM,
                false);
        // 测定图入口（全屏；关闭返回管理面板）
        RpButton.draw(
                g,
                ex,
                ay + 26,
                ex + aw + 6 + aw,
                ay + 46,
                tr("ccnr_rp.gui.admin.relation.graph"),
                RpTheme.CYAN_DIM,
                true);
        // 提示
        if (System.currentTimeMillis() < noticeUntil) {
            g.drawCenteredString(font, Component.literal(notice), (px1 + px2) / 2, py2 - 24, RpTheme.TEXT_SECONDARY);
        }
        g.drawString(font, Component.translatable("ccnr_rp.gui.admin.relation.hint"), ex, py2 - 40, RpTheme.TEXT_DIM);
        // 输入框背景（EditBox 自身绘制，此处仅补充面板底色一致性）
        g.fill(ex - 1, listY1 + 37, ex + (px2 - ex - 12) + 1, listY1 + 57, RpTheme.SURFACE_INSET);
        g.fill(ex - 1, listY1 + 93, ex + (px2 - ex - 12) + 1, listY1 + 113, RpTheme.SURFACE_INSET);
    }

    // ---------- 阵营下拉渲染 ----------

    /** 下拉弹层（在 widget 渲染之后调用，弹层盖住输入框等控件）。 */
    public void renderOverlay(GuiGraphics g, int mx, int my) {
        if (fromDdOpen) {
            drawPopup(g, mx, my, true);
        }
        if (toDdOpen) {
            drawPopup(g, mx, my, false);
        }
    }

    private void drawDropdown(GuiGraphics g, int mx, int my, boolean isFrom) {
        var font = Minecraft.getInstance().font;
        int ex = editorX();
        int y = ddY(isFrom);
        boolean open = isFrom ? fromDdOpen : toDdOpen;
        int idx = isFrom ? fromDdIdx : toDdIdx;
        boolean hov = mx >= ex && mx <= ex + ddW && my >= y && my <= y + DD_H;
        RpTheme.controlBox(g, ex, y, ex + ddW, y + DD_H, open || hov);
        List<JsonObject> facs = ClientCharacterState.factions();
        String label = facs.isEmpty() || idx < 0 || idx >= facs.size()
                ? tr("ccnr_rp.gui.admin.relation.pick_faction")
                : facLabel(facs.get(idx));
        g.drawString(
                font,
                Component.literal(clip(font, label, ddW - 18)),
                ex + 4,
                y + 3,
                facs.isEmpty() || idx < 0 || idx >= facs.size() ? RpTheme.TEXT_DIM : RpTheme.TEXT_PRIMARY);
        // 下拉箭头（小三角）
        int cx = ex + ddW - 8;
        int cy = y + DD_H / 2;
        g.fill(cx - 3, cy - 1, cx + 4, cy, RpTheme.TEXT_SECONDARY);
        g.fill(cx - 2, cy, cx + 3, cy + 1, RpTheme.TEXT_SECONDARY);
        g.fill(cx - 1, cy + 1, cx + 2, cy + 2, RpTheme.TEXT_SECONDARY);
        // 注入按钮
        RpButton.draw(
                g,
                ex + ddW + 6,
                y,
                ex + ddW + 6 + INJECT_W,
                y + DD_H,
                tr("ccnr_rp.gui.admin.relation.inject"),
                RpTheme.CYAN_DIM,
                true);
    }

    private void drawPopup(GuiGraphics g, int mx, int my, boolean isFrom) {
        var font = Minecraft.getInstance().font;
        int ex = editorX();
        List<JsonObject> facs = ClientCharacterState.factions();
        int top = ddPopupTop(isFrom);
        int h = ddPopupH(isFrom);
        int scroll = isFrom ? fromDdScroll : toDdScroll;
        int cur = isFrom ? fromDdIdx : toDdIdx;
        RpTheme.popupPanel(g, ex, top, ex + ddW, top + h);
        g.enableScissor(ex, top, ex + ddW, top + h);
        for (int i = scroll; i < facs.size() && i < scroll + DD_MAX_VISIBLE; i++) {
            int y1 = ddPopupItemY(isFrom, i);
            int y2 = y1 + DD_ITEM_H;
            boolean hov = mx >= ex && mx <= ex + ddW && my >= y1 && my <= y2;
            boolean cur2 = i == cur;
            RpTheme.popupRow(g, ex + 1, y1, ex + ddW - 1, y2, cur2, hov);
            g.drawString(
                    font,
                    Component.literal(clip(font, facLabel(facs.get(i)), ddW - 10)),
                    ex + 5,
                    y1 + 3,
                    RpTheme.popupRowText(cur2, hov));
        }
        g.disableScissor();
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    private static boolean sameSet(List<String> a, List<String> b) {
        if (a.size() != b.size()) {
            return false;
        }
        List<String> sa = new ArrayList<>(a);
        List<String> sb = new ArrayList<>(b);
        java.util.Collections.sort(sa);
        java.util.Collections.sort(sb);
        return sa.equals(sb);
    }

    /**
     * 把一组 id 缩短为已定义阵营组的引用：若这组 id 恰好等于某个组的 memberIds 集合，
     * 返回该组的 id（供列表/规则摘要用，避免重复罗列全部成员）；否则原样拼接。
     * 组内无引用或未匹配时按成员 id 逐个返回（保留展示信息）。
     */
    private static String shortenIds(List<String> ids) {
        if (ids.isEmpty()) {
            return "";
        }
        for (JsonObject g : ClientCharacterState.groups()) {
            List<String> members = idList(g, "members");
            if (sameSet(members, ids)) {
                String gid = str(g, "id", "");
                if (!gid.isBlank()) {
                    return Component.translatable("ccnr_rp.gui.admin.relation.group_ref", gid)
                            .getString();
                }
            }
        }
        return join(ids);
    }

    /** 展示用 from/to 摘要（阵营组匹配时缩短，降低规则名长度）。 */
    private static String sideLabel(List<String> ids) {
        return shortenIds(ids);
    }

    /** 按像素宽度裁剪文本（共享入口，算法见 RpTheme.clip）。 */
    private static String clip(net.minecraft.client.gui.Font font, String s, int maxW) {
        return RpTheme.clip(font, s, maxW);
    }

    private static String typeTag(String type) {
        RelationType t = RelationType.parse(type);
        if (t == RelationType.HOSTILE) {
            return tr("ccnr_rp.gui.admin.relation.type_hostile");
        }
        if (t == RelationType.FRIENDLY) {
            return tr("ccnr_rp.gui.admin.relation.type_friendly");
        }
        return tr("ccnr_rp.gui.admin.relation.type_neutral");
    }

    private static int typeColor(String type) {
        RelationType t = RelationType.parse(type);
        if (t == RelationType.HOSTILE) {
            return RpTheme.RED;
        }
        if (t == RelationType.FRIENDLY) {
            return RpTheme.FRIENDLY;
        }
        return RpTheme.NEUTRAL;
    }
}
