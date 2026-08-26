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

    private EditBox fromBox;
    private EditBox toBox;
    private RelationType selType = RelationType.NEUTRAL;
    private int selIndex = -1;
    private String notice = "";
    private long noticeUntil = 0;

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
        listY1 = py1 + 44;
        listY2 = py2 - 56;
        int ex = listX2 + 16;
        int ew = px2 - ex - 12;
        if (fromBox == null) {
            fromBox = new EditBox(Minecraft.getInstance().font, ex, listY1, ew, 18, Component.literal("from"));
            fromBox.setMaxLength(256);
            toBox = new EditBox(Minecraft.getInstance().font, ex, listY1 + 34, ew, 18, Component.literal("to"));
            toBox.setMaxLength(256);
            screen.addXpWidget(fromBox);
            screen.addXpWidget(toBox);
        } else {
            fromBox.setX(ex);
            fromBox.setWidth(ew);
            fromBox.setY(listY1);
            toBox.setX(ex);
            toBox.setWidth(ew);
            toBox.setY(listY1 + 34);
        }
        clearEditor();
    }

    private int editorX() {
        return listX2 + 16;
    }

    private int editorY() {
        return listY1 + 58;
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
        fromBox.setValue(join(idList(r, "from")));
        List<String> to = idList(r, "to");
        toBox.setValue(join(to));
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
        // 列表行选择
        List<JsonObject> rules = rules();
        for (int i = 0; i < rules.size(); i++) {
            int ry = listY1 + (i - scroll) * ROW_H;
            if (ry >= listY1 - ROW_H && ry <= listY2 && mx >= listX1 && mx <= listX2 && my >= ry && my <= ry + ROW_H) {
                loadEditor(i);
                return true;
            }
        }
        return false;
    }

    public void mouseScrolled(int mouseX, int mouseY, double delta) {
        if (mouseX >= listX1 && mouseX <= listX2 && mouseY >= listY1 && mouseY <= listY2) {
            int max = Math.max(0, rules().size() - Math.max(1, (listY2 - listY1) / ROW_H));
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
        JsonObject req = new JsonObject();
        req.addProperty("action", "update");
        req.add("rule", editorRule());
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
        JsonObject req = new JsonObject();
        req.addProperty("action", "remove");
        req.add("rule", editorRule());
        com.ccnrcom.rp.network.RpChannels.sendToServer(
                new com.ccnrcom.rp.network.RpPackets.RelationEditC2S(req.toString()));
        clearEditor();
        refresh();
    }

    /** 请求重新同步角色列表（服务端编辑已落盘，刷新展示新规则）。 */
    private void refresh() {
        com.ccnrcom.rp.network.RpChannels.sendToServer(new com.ccnrcom.rp.network.RpPackets.RequestCharacterListC2S());
    }

    // ---------- 渲染 ----------

    public void render(GuiGraphics g, int mx, int my) {
        var font = Minecraft.getInstance().font;
        // 左侧：规则列表
        g.drawString(
                font,
                Component.translatable("ccnr_rp.gui.admin.relation.list"),
                listX1,
                py1 + 20,
                RpTheme.TEXT_SECONDARY);
        List<JsonObject> rules = rules();
        g.enableScissor(listX1, listY1, listX2, listY2);
        for (int i = 0; i < rules.size(); i++) {
            int ry = listY1 + (i - scroll) * ROW_H;
            if (ry < listY1 - ROW_H || ry > listY2) {
                continue;
            }
            JsonObject r = rules.get(i);
            boolean sel = i == selIndex;
            if (sel) {
                RpTheme.selectedBar(g, listX1, ry, listX2, ry + ROW_H, 3f);
            } else if (i % 2 == 0) {
                g.fill(listX1, ry, listX2, ry + ROW_H, 0x1FFFFFFF);
            }
            List<String> from = idList(r, "from");
            List<String> to = idList(r, "to");
            boolean internal = sameSet(from, to);
            String type = str(r, "type", "neutral");
            String label = internal
                    ? tr("ccnr_rp.gui.admin.relation.internal") + ": " + join(from)
                    : join(from) + " × " + join(to);
            g.drawString(font, label, listX1 + 4, ry + 5, sel ? 0xFFFFFFFF : RpTheme.TEXT_PRIMARY);
            g.drawString(font, typeTag(type), listX1 + 4 + font.width(label) + 6, ry + 5, typeColor(type));
        }
        g.disableScissor();

        // 右侧：编辑器
        int ex = editorX();
        int ey = editorY();
        g.drawString(
                font,
                Component.translatable("ccnr_rp.gui.admin.relation.from"),
                ex,
                listY1 - 14,
                RpTheme.TEXT_SECONDARY);
        g.drawString(
                font, Component.translatable("ccnr_rp.gui.admin.relation.to"), ex, listY1 + 20, RpTheme.TEXT_SECONDARY);
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
                        case 0 -> 0xFFFFFFFF;
                        case 1 -> RpTheme.RED;
                        default -> RpTheme.GREEN;
                    };
            RpRoundRect.outlined(
                    g,
                    bx,
                    ey,
                    bx + bw,
                    ey + 20,
                    3f,
                    sel ? color : RpTheme.PANEL_BORDER,
                    sel ? RpTheme.alphaBlend(color, 0x22) : 0xA8323232);
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
        g.fill(ex - 1, listY1 - 1, ex + (px2 - ex - 12) + 1, listY1 + 19, 0x99383838);
        g.fill(ex - 1, listY1 + 33, ex + (px2 - ex - 12) + 1, listY1 + 53, 0x99383838);
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
            return RpTheme.GREEN;
        }
        return 0xFFFFFFFF;
    }
}
