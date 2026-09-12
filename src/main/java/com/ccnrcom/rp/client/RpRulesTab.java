/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.ccnrcom.rp.experience.ExperienceEventRegistry;
import com.ccnrcom.rp.experience.ExperienceEventRegistry.EventDef;
import com.ccnrcom.rp.experience.ExperienceEventRegistry.Param;
import com.ccnrcom.rp.experience.ExperienceRule;
import com.ccnrcom.rp.experience.ExprEvaluator;
import com.ccnrcom.rp.experience.ExprException;
import com.ccnrcom.rp.experience.ExprParser;
import com.ccnrcom.rp.experience.ExprParser.Expr;
import com.ccnrcom.rp.experience.ExprParser.Kind;
import com.ccnrcom.rp.network.RpChannels;
import com.ccnrcom.rp.network.RpPackets;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * 管理面板「经验规则」页签（经验系统 v3）：规则列表 + 编辑器。
 * 编辑器：事件下拉补全、限定高度可滚动参数面板（参数名+类型，点击插入到聚焦表达式末尾）、
 * 判断/数值/标题三个表达式输入框（各自实时语法校验徽标）、「试算」验证器（按事件默认样例
 * 本地求值展示成不成立）。保存走 RuleEditC2S → 服务端权威校验 + 落盘 + 热重载。
 */
public final class RpRulesTab {

    // 按钮 kind
    private static final int BTN_ADD = 0;
    private static final int BTN_REMOVE = 1;
    private static final int BTN_TOGGLE = 2;
    private static final int BTN_SAVE = 4;
    private static final int BTN_TEST = 5;

    // 滚动条 id（全局唯一；RpAdminScreen.mouseDragged 按 dragId 把拖拽路由回本页签）
    private static final int SB_RULES = 31;
    private static final int SB_PARAMS = 32;

    private final RpAdminScreen screen;
    private int px1, py1, px2, py2;
    private int listX1, listX2, listY1, listY2;
    private int ex1, ex2, ey1, ey2;

    private final List<JsonObject> rules = new ArrayList<>();
    private int sel = -1;
    private int rulesScroll = 0;

    private String draftId = "";
    private boolean draftEnabled = true;
    private String eventId = "character_alive";
    private String draftCond = "";
    private String draftValue = "";
    private String draftTitle = "";
    private EditBox idBox;
    private EditBox eventBox;
    private EditBox condBox;
    private EditBox valueBox;
    private EditBox titleBox;
    // 事件补全（参考限制页目标补全：文字输入 + 下拉候选）
    private List<String> evSugItems = new ArrayList<>();
    private int evSugIdx = -1;
    private String lastEvQuery = null;
    private final List<int[]> evSugBounds = new ArrayList<>();

    private int paramScroll = 0;
    private final List<int[]> paramBounds = new ArrayList<>();
    private final List<int[]> btnBounds = new ArrayList<>();

    private final List<String> testLines = new ArrayList<>();
    private long testAt = 0;
    private String notice = "";
    private long noticeUntil = 0;

    private static final int ROW_H = 20;
    /** 表头带高度：表头独立一行，行列表从表头下方开始，避免表头与首行文字重叠。 */
    private static final int HDR = 13;

    public RpRulesTab(RpAdminScreen screen) {
        this.screen = screen;
    }

    /** 由 RpAdminScreen.rebuild 调用（tab==TAB_XP）。 */
    public void rebuild(int px1, int py1, int px2, int py2) {
        // 先把用户正在编辑的草稿同步进暂存，再重建输入框
        if (condBox != null) {
            draftId = idBox.getValue();
            draftCond = condBox.getValue();
            draftValue = valueBox.getValue();
            draftTitle = titleBox.getValue();
        }
        this.px1 = px1;
        this.py1 = py1;
        this.px2 = px2;
        this.py2 = py2;
        this.listX1 = px1 + 12;
        this.listX2 = listX1 + Math.min(240, (px2 - px1) * 34 / 100);
        this.listY1 = py1 + 76;
        this.listY2 = py2 - 62;
        this.ex1 = listX2 + 14;
        this.ex2 = px2 - 12;
        this.ey1 = py1 + 76;
        this.ey2 = py2 - 40;

        rules.clear();
        rules.addAll(ClientCharacterState.xpRules());
        if (sel >= rules.size()) {
            sel = -1;
        }
        if (sel < 0 && !rules.isEmpty()) {
            sel = 0;
            loadDraft(rules.get(sel));
        } else if (sel >= 0) {
            loadDraft(rules.get(sel));
        }

        var font = Minecraft.getInstance().font;
        int fy = ey1 + 100;
        condBox = addBox(font, ex1 + 100, fy, ex2 - ex1 - 100);
        valueBox = addBox(font, ex1 + 100, fy + 24, ex2 - ex1 - 100);
        titleBox = addBox(font, ex1 + 100, fy + 48, ex2 - ex1 - 100);
        idBox = addBox(font, ex1 + 100, ey1 + 2, Math.min(180, ex2 - ex1 - 100));
        eventBox = addBox(font, ex1 + 100, ey1 + 24, Math.min(180, ex2 - ex1 - 100));
        eventBox.setMaxLength(64);
        syncBoxes();
    }

    private EditBox addBox(net.minecraft.client.gui.Font font, int x, int y, int w) {
        EditBox b = new EditBox(font, x, y, Math.max(60, w), 18, Component.literal(""));
        b.setMaxLength(512);
        screen.addXpWidget(b);
        return b;
    }

    private void loadDraft(JsonObject rule) {
        draftId = str(rule, "id");
        draftEnabled = !rule.has("enabled") || rule.get("enabled").getAsBoolean();
        eventId = str(rule, "eventId");
        if (ExperienceEventRegistry.byId(eventId).isEmpty()) {
            eventId = "character_alive";
        }
        draftCond = str(rule, "conditionExpr");
        draftValue = str(rule, "valueExpr");
        draftTitle = str(rule, "titleExpr");
    }

    private void syncBoxes() {
        condBox.setValue(draftCond);
        valueBox.setValue(draftValue);
        titleBox.setValue(draftTitle);
        idBox.setValue(draftId);
        if (eventBox != null) {
            eventBox.setValue(eventId);
        }
    }

    public void render(GuiGraphics g, int mx, int my) {
        var font = Minecraft.getInstance().font;
        int rightX = ex2;

        // 左侧规则列表
        RpTheme.listPanel(g, listX1 - 2, listY1 - 4, listX2 + 2, listY2 + 2);
        g.drawString(
                font,
                Component.translatable("ccnr_rp.gui.admin.tab.xp").getString() + " (" + rules.size() + ")",
                listX1 + 4,
                listY1 + 1,
                RpTheme.TEXT_DIM);
        // 表头带与行列表分隔线（避免表头文字压到首行）
        RpTheme.listHeaderRule(g, listX1, listX2, listY1 + HDR - 1);
        int maxVisible = Math.max(1, (listY2 - listY1 - HDR) / ROW_H);
        int off = Math.min(rulesScroll, Math.max(0, rules.size() - maxVisible));
        // 行右缘让出 8px 滚动条槽（列表溢出时右侧是可拖动的滚动条，不再只靠滚轮）
        int rowX2 = listX2 - 8;
        for (int i = 0; i < rules.size() && i < maxVisible; i++) {
            JsonObject r = rules.get(off + i);
            int y1 = listY1 + HDR + i * ROW_H;
            int y2 = y1 + ROW_H - 1;
            boolean s = off + i == sel;
            boolean hov = mx >= listX1 && mx <= listX2 && my >= y1 && my <= y2;
            if (s) {
                RpTheme.selectedBar(g, listX1, y1, rowX2, y2, 3f);
            } else {
                RpTheme.listRow(g, listX1, y1, rowX2, y2 + 1, i, hov);
            }
            boolean en = !r.has("enabled") || r.get("enabled").getAsBoolean();
            g.drawString(font, en ? "●" : "○", listX1 + 6, y1 + 6, en ? RpTheme.GREEN : RpTheme.TEXT_DIM, true);
            g.drawString(font, str(r, "id"), listX1 + 18, y1 + 6, s ? RpTheme.ACCENT_TEXT : RpTheme.TEXT_PRIMARY);
            String ev = str(r, "eventId");
            g.drawString(font, ev, rowX2 - font.width(ev) - 4, y1 + 6, RpTheme.TEXT_DIM);
        }
        // 规则列表滚动条（可拖动；溢出时才出现）
        RpScrollbar.draw(g, listX2 - 6, listY1 + HDR, listY2, rules.size(), maxVisible, off);
        // 列表下方按钮：新增 / 删除 / 启停
        btnBounds.clear();
        int by = py2 - 32;
        addBtn(BTN_ADD, "ccnr_rp.xp.rules.add", listX1, by);
        addBtn(BTN_REMOVE, "ccnr_rp.xp.rules.remove", listX1 + 66, by);
        addBtn(BTN_TOGGLE, "ccnr_rp.xp.rules.toggle", listX1 + 132, by);
        drawButtons(g, mx, my, by);

        // 右侧编辑器
        g.drawString(
                font, Component.translatable("ccnr_rp.xp.rules.id").getString(), ex1, ey1 + 4, RpTheme.TEXT_SECONDARY);
        g.drawString(
                font,
                Component.translatable("ccnr_rp.xp.rules.event").getString(),
                ex1,
                ey1 + 26,
                RpTheme.TEXT_SECONDARY);
        g.drawString(
                font,
                Component.translatable("ccnr_rp.xp.rules.condition").getString(),
                ex1,
                ey1 + 104,
                RpTheme.TEXT_SECONDARY);
        g.drawString(
                font,
                Component.translatable("ccnr_rp.xp.rules.value").getString(),
                ex1,
                ey1 + 128,
                RpTheme.TEXT_SECONDARY);
        g.drawString(
                font,
                Component.translatable("ccnr_rp.xp.rules.title").getString(),
                ex1,
                ey1 + 152,
                RpTheme.TEXT_SECONDARY);

        // 参数面板（限定高度可滚动）
        int paY = ey1 + 176;
        int paH = Math.min(110, py2 - 70 - paY);
        String actEv = activeEventId();
        RpTheme.listPanel(g, ex1, paY, rightX, paY + paH);
        g.drawString(
                font,
                Component.translatable("ccnr_rp.xp.rules.params").getString() + " (" + actEv + ")",
                ex1 + 6,
                paY + 4,
                RpTheme.TEXT_DIM);
        paramBounds.clear();
        EventDef def = ExperienceEventRegistry.byId(actEv).orElse(null);
        if (def != null) {
            int maxP = Math.max(1, (paH - 18) / 16);
            int off2 = Math.min(paramScroll, Math.max(0, def.params().size() - maxP));
            int hintW = font.width("⇧ " + tr("ccnr_rp.xp.rules.insert"));
            // 溢出时右侧让出 8px 滚动条槽（参数面板同样是"可滚但不可拖"的高发区）
            boolean paramOverflow = def.params().size() > maxP;
            int paramX2 = rightX - (paramOverflow ? 8 : 0);
            int hintX = paramX2 - 4 - hintW;
            for (int i = 0; i < def.params().size() && i < maxP; i++) {
                Param p = def.params().get(off2 + i);
                int py = paY + 20 + i * 16;
                boolean hov = mx >= ex1 && mx <= rightX && my >= py && my <= py + 15;
                if (hov) {
                    g.fill(ex1 + 2, py, paramX2 - 2, py + 15, RpTheme.PANEL_BG_ALT);
                }
                String type = p.type() == ExperienceEventRegistry.ParamType.LONG ? "LONG" : "STRING";
                // 名字按可用宽度裁剪，避免与右侧插入提示重叠
                int nameMax = hintX - 12 - (ex1 + 8) - 8 - font.width("(" + type + ")");
                String name = clip(font, p.name(), Math.max(24, nameMax));
                g.drawString(font, name, ex1 + 8, py + 3, hov ? RpTheme.CYAN : RpTheme.TEXT_PRIMARY);
                g.drawString(font, "(" + type + ")", ex1 + 8 + font.width(name) + 8, py + 3, RpTheme.TEXT_DIM);
                g.drawString(font, "⇧ " + tr("ccnr_rp.xp.rules.insert"), hintX, py + 3, RpTheme.CYAN);
                paramBounds.add(new int[] {ex1, py, paramX2 - 2, py + 15});
            }
            RpScrollbar.draw(g, rightX - 6, paY + 18, paY + paH, def.params().size(), maxP, off2);
        }

        // 验证器 + 试算 + 保存
        int vy = paY + paH + 6;
        renderValidation(g, mx, my, vy, rightX);

        if (!notice.isBlank() && System.currentTimeMillis() < noticeUntil) {
            g.drawCenteredString(font, notice, (px1 + px2) / 2, py2 - 18, RpTheme.RED_LINE);
        }
        renderEventSuggestions(g, mx, my);
    }

    /** 事件补全下拉（参考限制页目标补全）：事件输入框聚焦时按输入过滤注册表事件。 */
    private void renderEventSuggestions(GuiGraphics g, int mx, int my) {
        evSugBounds.clear();
        if (eventBox == null || !eventBox.isFocused()) {
            evSugItems = new ArrayList<>();
            evSugIdx = -1;
            lastEvQuery = null;
            return;
        }
        String q = eventBox.getValue() == null ? "" : eventBox.getValue().toLowerCase(java.util.Locale.ROOT);
        if (!q.equals(lastEvQuery)) {
            lastEvQuery = q;
            evSugIdx = -1;
        }
        evSugItems = new ArrayList<>();
        for (EventDef d : ExperienceEventRegistry.EVENTS) {
            if (q.isBlank() || d.id().toLowerCase(java.util.Locale.ROOT).contains(q)) {
                evSugItems.add(d.id());
            }
        }
        if (evSugIdx >= evSugItems.size()) {
            evSugIdx = evSugItems.size() - 1;
        }
        if (evSugItems.isEmpty()) {
            return;
        }
        var font = Minecraft.getInstance().font;
        int sx = eventBox.getX();
        int sy = eventBox.getY() + 20;
        int sw = eventBox.getWidth();
        int n = Math.min(6, evSugItems.size());
        RpTheme.suggestionPopup(g, sx, sy, sw, n);
        for (int i = 0; i < n; i++) {
            int yy = sy + i * 12;
            boolean rowHov = mx >= sx && mx <= sx + sw && my >= yy && my <= yy + 12;
            RpTheme.suggestionRow(g, font, sx, yy, sw, evSugItems.get(i), i == evSugIdx, rowHov);
            evSugBounds.add(new int[] {sx, yy, sx + sw, yy + 12});
        }
    }

    private void renderValidation(GuiGraphics g, int mx, int my, int vy, int rightX) {
        var font = Minecraft.getInstance().font;
        String[] exprs = {
            condBox == null ? "" : condBox.getValue(),
            valueBox == null ? "" : valueBox.getValue(),
            titleBox == null ? "" : titleBox.getValue()
        };
        String[] labels = {tr("ccnr_rp.xp.rules.v.cond"), tr("ccnr_rp.xp.rules.v.value"), tr("ccnr_rp.xp.rules.v.title")
        };
        int y = vy;
        for (int i = 0; i < exprs.length; i++) {
            String status = check(exprs[i], i);
            boolean ok = status.isEmpty();
            g.drawString(
                    font,
                    labels[i] + ": " + (ok ? "✓" : "✗ " + status),
                    ex1,
                    y,
                    ok ? RpTheme.GREEN : RpTheme.RED,
                    true);
            y += 13;
        }
        // 试算 + 保存按钮
        int bw = 56;
        int bx = rightX - 2 * bw - 8;
        boolean hov1 = mx >= bx && mx <= bx + bw && my >= y + 2 && my <= y + 18;
        RpButton.draw(
                g,
                bx,
                y + 2,
                bx + bw,
                y + 18,
                Component.translatable("ccnr_rp.xp.rules.test").getString(),
                RpTheme.PANEL_BORDER,
                false,
                hov1);
        btnBounds.add(new int[] {bx, y + 2, bx + bw, y + 18, BTN_TEST});
        int sx = rightX - bw;
        boolean hov2 = mx >= sx && mx <= rightX && my >= y + 2 && my <= y + 18;
        RpButton.draw(
                g,
                sx,
                y + 2,
                rightX,
                y + 18,
                Component.translatable("ccnr_rp.xp.rules.save").getString(),
                RpTheme.PANEL_BORDER,
                false,
                hov2);
        btnBounds.add(new int[] {sx, y + 2, rightX, y + 18, BTN_SAVE});
        // 试算结果（3 秒）
        if (System.currentTimeMillis() - testAt < 3000L) {
            int ty = y + 24;
            for (String line : testLines) {
                g.drawString(font, line, ex1, ty, RpTheme.TEXT_PRIMARY, true);
                ty += 12;
            }
        }
    }

    /** 表达式语法/类型校验；空=合法。index: 0=判断 1=数值 2=标题。 */
    private String check(String src, int index) {
        if (src == null || src.isBlank()) {
            // 判断空=恒激活、标题空=合法；数值必填
            return index == 1 ? tr("ccnr_rp.xp.rules.v.required") : "";
        }
        try {
            Expr e = ExprParser.parse(src);
            Map<String, Kind> kinds = ExperienceEventRegistry.paramKinds(activeEventId());
            if (index == 0) {
                ExprParser.expect(e, Kind.BOOL, kinds);
            } else if (index == 1) {
                ExprParser.expect(e, Kind.NUM, kinds);
            }
            return "";
        } catch (ExprException ex) {
            return ex.getMessage();
        }
    }

    /** 键盘：事件补全候选上/下/回车/Esc（参考限制页目标补全）。 */
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!evSugItems.isEmpty() && eventBox != null && eventBox.isFocused()) {
            if (keyCode == 264) { // Down
                evSugIdx = (evSugIdx + 1) % evSugItems.size();
                return true;
            }
            if (keyCode == 265) { // Up
                evSugIdx = (evSugIdx - 1 + evSugItems.size()) % evSugItems.size();
                return true;
            }
            if (keyCode == 257 || keyCode == 335) { // Enter / Numpad Enter
                if (evSugIdx >= 0 && evSugIdx < evSugItems.size()) {
                    selectEvent(evSugItems.get(evSugIdx));
                }
                evSugIdx = -1;
                return true;
            }
            if (keyCode == 256) { // Esc
                evSugIdx = -1;
                return true;
            }
        }
        return false;
    }

    /** 选择事件：更新生效事件、输入框与参数面板滚动。 */
    private void selectEvent(String id) {
        eventId = id;
        if (eventBox != null) {
            eventBox.setValue(id);
        }
        paramScroll = 0;
    }

    /** 当前生效事件：输入框内容为合法事件时跟随输入，否则用最近选中的事件。 */
    private String activeEventId() {
        if (eventBox != null) {
            String v = eventBox.getValue();
            if (v != null && ExperienceEventRegistry.exists(v)) {
                return v;
            }
        }
        return eventId;
    }

    public boolean mouseClicked(int mx, int my, int button) {
        if (button == 1) {
            // 右键规则行 = 删除该规则（与「删除」按钮同一确认入口，docs/01 §10.2）
            int idx = ruleRowAt(mx, my);
            if (idx >= 0) {
                sel = idx;
                String rid = str(rules.get(idx), "id");
                screen.confirmDelete(
                        Component.translatable("ccnr_rp.gui.admin.confirm.del_msg", tr("ccnr_rp.gui.admin.tab.xp"), rid)
                                .getString(),
                        () -> {
                            sendEdit("remove", rid);
                            sel = -1;
                        });
                return true;
            }
            return false;
        }
        if (button != 0) {
            return false;
        }
        // 事件补全候选点击（优先于其他交互）
        if (!evSugBounds.isEmpty()) {
            for (int i = 0; i < evSugBounds.size(); i++) {
                int[] b = evSugBounds.get(i);
                if (mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3]) {
                    if (i < evSugItems.size()) {
                        selectEvent(evSugItems.get(i));
                    }
                    evSugIdx = -1;
                    return true;
                }
            }
        }
        // 规则列表行
        int maxVisible = Math.max(1, (listY2 - listY1 - HDR) / ROW_H);
        int off = Math.min(rulesScroll, Math.max(0, rules.size() - maxVisible));
        // 规则列表滚动条（可拖动；id 在本页签内唯一，拖拽经 RpAdminScreen 按 dragId 路由回这里）
        int rns = RpScrollbar.clickV(
                mx, my, listX2 - 6, listX2 - 1, listY1 + HDR, listY2, rules.size(), maxVisible, off, SB_RULES);
        if (rns >= 0) {
            rulesScroll = (int) Math.max(0, Math.min(rns, Math.max(0, rules.size() - maxVisible)));
            return true;
        }
        // 参数面板滚动条（同样可拖动）
        EventDef activeDef = ExperienceEventRegistry.byId(activeEventId()).orElse(null);
        if (activeDef != null) {
            int paY = ey1 + 176;
            int paH = Math.min(110, py2 - 70 - paY);
            int maxP = Math.max(1, (paH - 18) / 16);
            int off2 = Math.min(paramScroll, Math.max(0, activeDef.params().size() - maxP));
            int pns = RpScrollbar.clickV(
                    mx,
                    my,
                    ex2 - 6,
                    ex2 - 1,
                    paY + 18,
                    paY + paH,
                    activeDef.params().size(),
                    maxP,
                    off2,
                    SB_PARAMS);
            if (pns >= 0) {
                paramScroll = (int)
                        Math.max(0, Math.min(pns, Math.max(0, activeDef.params().size() - maxP)));
                return true;
            }
        }
        for (int i = 0; i < rules.size() && i < maxVisible; i++) {
            int y1 = listY1 + HDR + i * ROW_H;
            if (mx >= listX1 && mx <= listX2 && my >= y1 && my <= y1 + ROW_H - 1) {
                sel = off + i;
                loadDraft(rules.get(sel));
                syncBoxes();
                return true;
            }
        }
        // 参数面板：插入到聚焦表达式末尾
        for (int i = 0; i < paramBounds.size(); i++) {
            int[] b = paramBounds.get(i);
            if (mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3]) {
                EditBox target = focusedExprBox();
                if (target == null) {
                    flash("ccnr_rp.xp.rules.select_first");
                    return true;
                }
                EventDef def = ExperienceEventRegistry.byId(eventId).orElse(null);
                int pi = i + paramScroll;
                if (def != null && pi >= 0 && pi < def.params().size()) {
                    String name = def.params().get(pi).name();
                    String cur = target.getValue();
                    target.setValue(cur + (cur.isEmpty() ? "" : " ") + name);
                }
                return true;
            }
        }
        // 按钮
        for (int[] b : btnBounds) {
            if (mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3]) {
                onButton(b.length > 4 ? b[4] : -1);
                return true;
            }
        }
        return false;
    }

    private EditBox focusedExprBox() {
        if (condBox != null && condBox.isFocused()) {
            return condBox;
        }
        if (valueBox != null && valueBox.isFocused()) {
            return valueBox;
        }
        if (titleBox != null && titleBox.isFocused()) {
            return titleBox;
        }
        return null;
    }

    private void onButton(int kind) {
        switch (kind) {
            case BTN_ADD -> {
                int n = 1;
                while (true) {
                    String cand = "rule" + n;
                    boolean dup = false;
                    for (JsonObject r : rules) {
                        if (str(r, "id").equals(cand)) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) {
                        draftId = cand;
                        break;
                    }
                    n++;
                }
                draftEnabled = true;
                eventId = "character_alive";
                draftCond = "";
                draftValue = "1";
                draftTitle = "";
                syncBoxes();
                sel = -1;
                flash("ccnr_rp.xp.rules.new_hint");
            }
            case BTN_REMOVE -> {
                if (sel >= 0 && sel < rules.size()) {
                    // 删除经验规则：一律先二次确认（右键条目与「删除」按钮共用，docs/01 §10.2）
                    String rid = str(rules.get(sel), "id");
                    screen.confirmDelete(
                            Component.translatable(
                                            "ccnr_rp.gui.admin.confirm.del_msg", tr("ccnr_rp.gui.admin.tab.xp"), rid)
                                    .getString(),
                            () -> {
                                sendEdit("remove", rid);
                                sel = -1;
                            });
                }
            }
            case BTN_TOGGLE -> {
                if (sel >= 0 && sel < rules.size()) {
                    sendEdit("toggle", str(rules.get(sel), "id"));
                }
            }
            case BTN_SAVE -> save();
            case BTN_TEST -> runTest();
            default -> {}
        }
    }

    private void runTest() {
        String cond = condBox == null ? "" : condBox.getValue();
        String value = valueBox == null ? "" : valueBox.getValue();
        String title = titleBox == null ? "" : titleBox.getValue();
        Map<String, Object> sample = ExperienceEventRegistry.defaultSample(activeEventId());
        testLines.clear();
        if (cond.isBlank()) {
            testLines.add(tr("ccnr_rp.xp.rules.v.condAlways"));
        } else {
            try {
                boolean b = ExprEvaluator.evalBool(ExprParser.parse(cond), sample);
                testLines.add(b ? tr("ccnr_rp.xp.rules.v.condTrue") : tr("ccnr_rp.xp.rules.v.condFalse"));
            } catch (ExprException e) {
                testLines.add(tr("ccnr_rp.xp.rules.v.condErr", e.getMessage()));
            }
        }
        try {
            double v = ExprEvaluator.evalNum(ExprParser.parse(value), sample);
            testLines.add(tr("ccnr_rp.xp.rules.v.valueResult", ExprEvaluator.stringify(v)));
        } catch (ExprException e) {
            testLines.add(tr("ccnr_rp.xp.rules.v.valueErr", e.getMessage()));
        }
        try {
            String t = ExprEvaluator.evalString(ExprParser.parse(title), sample);
            testLines.add(t.isEmpty() ? tr("ccnr_rp.xp.rules.v.titleEmpty") : tr("ccnr_rp.xp.rules.v.titleResult", t));
        } catch (ExprException e) {
            testLines.add(tr("ccnr_rp.xp.rules.v.titleErr", e.getMessage()));
        }
        testAt = System.currentTimeMillis();
    }

    /** 命中规则行的绝对下标（考虑滚动偏移；未命中返回 -1）。 */
    private int ruleRowAt(int mx, int my) {
        if (mx < listX1 || mx > listX2 || my < listY1 || my > listY2) {
            return -1;
        }
        int maxVisible = Math.max(1, (listY2 - listY1 - HDR) / ROW_H);
        int off = Math.min(rulesScroll, Math.max(0, rules.size() - maxVisible));
        for (int i = 0; i < rules.size() && i < maxVisible; i++) {
            int y1 = listY1 + HDR + i * ROW_H;
            if (my >= y1 && my <= y1 + ROW_H - 1) {
                return off + i;
            }
        }
        return -1;
    }

    private void sendEdit(String action, String id) {
        JsonObject req = new JsonObject();
        req.addProperty("action", action);
        if ("remove".equals(action) || "toggle".equals(action)) {
            req.addProperty("id", id);
        } else {
            req.add("rule", draftJson());
        }
        RpChannels.sendToServer(new RpPackets.RuleEditC2S(req.toString()));
    }

    private JsonObject draftJson() {
        JsonObject rule = new JsonObject();
        rule.addProperty("id", idBox == null ? draftId : idBox.getValue());
        rule.addProperty("enabled", draftEnabled);
        rule.addProperty("eventId", activeEventId());
        rule.addProperty("conditionExpr", condBox == null ? draftCond : condBox.getValue());
        rule.addProperty("valueExpr", valueBox == null ? draftValue : valueBox.getValue());
        rule.addProperty("titleExpr", titleBox == null ? draftTitle : titleBox.getValue());
        return rule;
    }

    /** 保存当前草稿（本地预校验，服务端权威校验）。 */
    public void save() {
        try {
            ExperienceRule r = ExperienceRule.from(draftJson());
            List<String> errs = ExperienceRule.validate(r);
            if (!errs.isEmpty()) {
                for (String e : errs) {
                    flashRaw(e);
                }
                return;
            }
            sendEdit("update", r.id());
        } catch (Exception e) {
            flashRaw(e.getMessage() == null ? tr("ccnr_rp.xp.rules.save_fail") : e.getMessage());
        }
    }

    /**
     * 滚动条拖拽（由 {@code RpAdminScreen.mouseDragged} 按 dragId 路由）：规则列表 / 参数面板两条都可拖。
     * 返回是否消费本次拖拽。
     */
    public boolean mouseDragged(int mx, int my) {
        int ns = RpScrollbar.dragV(my);
        if (ns < 0) {
            return false;
        }
        if (RpScrollbar.dragId() == SB_RULES) {
            int max = Math.max(0, rules.size() - Math.max(1, (listY2 - listY1 - HDR) / ROW_H));
            rulesScroll = (int) Math.max(0, Math.min(ns, max));
            return true;
        }
        if (RpScrollbar.dragId() == SB_PARAMS) {
            EventDef def = ExperienceEventRegistry.byId(activeEventId()).orElse(null);
            if (def != null) {
                int paY = ey1 + 176;
                int paH = Math.min(110, py2 - 70 - paY);
                int maxP = Math.max(1, (paH - 18) / 16);
                paramScroll =
                        (int) Math.max(0, Math.min(ns, Math.max(0, def.params().size() - maxP)));
                return true;
            }
        }
        return false;
    }

    public void mouseScrolled(int mouseX, int mouseY, double delta) {
        if (mouseX >= listX1 && mouseX <= listX2 && mouseY >= listY1 && mouseY <= listY2) {
            int max = Math.max(0, rules.size() - Math.max(1, (listY2 - listY1 - HDR) / ROW_H));
            rulesScroll = (int) Math.max(0, Math.min(rulesScroll - delta / 8, max));
        } else {
            paramScroll = (int) Math.max(0, paramScroll - delta / 8);
        }
    }

    private void addBtn(int kind, String key, int x, int y) {
        btnBounds.add(new int[] {x, y, x + 60, y + 18, kind});
    }

    private void drawButtons(GuiGraphics g, int mx, int my, int y) {
        var font = Minecraft.getInstance().font;
        for (int[] b : btnBounds) {
            if (b[4] < 0) {
                continue; // 事件下拉选项不是按钮
            }
            boolean hov = mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3];
            String key =
                    switch (b[4]) {
                        case BTN_ADD -> "ccnr_rp.xp.rules.add";
                        case BTN_REMOVE -> "ccnr_rp.xp.rules.remove";
                        case BTN_TOGGLE -> "ccnr_rp.xp.rules.toggle";
                        default -> "";
                    };
            if (!key.isEmpty()) {
                RpButton.draw(
                        g,
                        b[0],
                        b[1],
                        b[2],
                        b[3],
                        Component.translatable(key).getString(),
                        RpTheme.PANEL_BORDER,
                        false,
                        hov);
            }
        }
    }

    private void flash(String key) {
        notice = Component.translatable(key).getString();
        noticeUntil = System.currentTimeMillis() + 2500L;
    }

    private void flashRaw(String text) {
        notice = text;
        noticeUntil = System.currentTimeMillis() + 4000L;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    /** 本地化文本（规则编辑器 UI 文案）。 */
    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    /** 本地化文本（带参数）。 */
    private static String tr(String key, String arg) {
        return Component.translatable(key, arg).getString();
    }

    /** 按像素宽度裁剪文本（共享入口，算法见 RpTheme.clip）。 */
    private static String clip(net.minecraft.client.gui.Font font, String s, int maxW) {
        return RpTheme.clip(font, s, maxW);
    }
}
