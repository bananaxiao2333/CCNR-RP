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
    private EditBox condBox;
    private EditBox valueBox;
    private EditBox titleBox;
    private boolean eventMenuOpen = false;

    private int paramScroll = 0;
    private final List<int[]> paramBounds = new ArrayList<>();
    private final List<int[]> btnBounds = new ArrayList<>();

    private final List<String> testLines = new ArrayList<>();
    private long testAt = 0;
    private String notice = "";
    private long noticeUntil = 0;

    private static final int ROW_H = 20;

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
        idBox = addBox(font, ex1 + 100, ey1 + 22, Math.min(180, ex2 - ex1 - 100));
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
    }

    public void render(GuiGraphics g, int mx, int my) {
        var font = Minecraft.getInstance().font;
        int rightX = ex2;

        // 左侧规则列表
        RpRoundRect.outlined(
                g, listX1 - 2, listY1 - 4, listX2 + 2, listY2 + 2, 4f, RpTheme.PANEL_BORDER, RpTheme.PANEL_BG_EVEN);
        g.drawString(
                font,
                Component.translatable("ccnr_rp.gui.admin.tab.xp").getString() + " (" + rules.size() + ")",
                listX1 + 4,
                listY1 - 4,
                RpTheme.TEXT_DIM);
        int maxVisible = Math.max(1, (listY2 - listY1) / ROW_H);
        int off = Math.min(rulesScroll, Math.max(0, rules.size() - maxVisible));
        for (int i = 0; i < rules.size() && i < maxVisible; i++) {
            JsonObject r = rules.get(off + i);
            int y1 = listY1 + i * ROW_H;
            int y2 = y1 + ROW_H - 1;
            boolean s = off + i == sel;
            boolean hov = mx >= listX1 && mx <= listX2 && my >= y1 && my <= y2;
            if (s) {
                RpTheme.selectedBar(g, listX1, y1, listX2, y2, 3f);
            } else {
                g.fill(
                        listX1,
                        y1,
                        listX2,
                        y2 + 1,
                        hov ? RpTheme.PANEL_BG_ALT : (i % 2 == 0 ? RpTheme.PANEL_BG : 0x00000000));
            }
            boolean en = !r.has("enabled") || r.get("enabled").getAsBoolean();
            g.drawString(font, en ? "●" : "○", listX1 + 6, y1 + 6, en ? RpTheme.GREEN : RpTheme.TEXT_DIM, true);
            g.drawString(font, str(r, "id"), listX1 + 18, y1 + 6, s ? 0xFFFFFFFF : RpTheme.TEXT_PRIMARY);
            String ev = str(r, "eventId");
            g.drawString(font, ev, listX2 - font.width(ev) - 6, y1 + 6, RpTheme.TEXT_DIM);
        }
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
        int evX = ex1 + 100;
        int evY = ey1 + 22;
        int evW = Math.min(180, ex2 - ex1 - 100);
        RpRoundRect.outlined(g, evX, evY, evX + evW, evY + 18, 3f, RpTheme.PANEL_BORDER_BRIGHT, RpTheme.PANEL_BG);
        g.drawString(font, eventId, evX + 4, evY + 5, RpTheme.TEXT_PRIMARY);
        g.drawString(font, "▼", evX + evW - 12, evY + 5, RpTheme.TEXT_DIM);
        if (eventMenuOpen) {
            List<EventDef> evs = ExperienceEventRegistry.EVENTS;
            for (int i = 0; i < evs.size(); i++) {
                int oy = evY + 20 + i * 16;
                boolean ov = mx >= evX && mx <= evX + evW && my >= oy && my <= oy + 15;
                g.fill(evX, oy, evX + evW, oy + 15, ov ? RpTheme.PANEL_BG_ALT : RpTheme.PANEL_BG);
                g.drawString(
                        font,
                        evs.get(i).id() + (evs.get(i).id().equals(eventId) ? " ✓" : ""),
                        evX + 4,
                        oy + 3,
                        evs.get(i).id().equals(eventId) ? RpTheme.CYAN : RpTheme.TEXT_PRIMARY);
            }
            btnBounds.add(new int[] {evX, evY + 20, evX + evW, evY + 20 + evs.size() * 16, -1});
        }
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
        RpRoundRect.outlined(g, ex1, paY, rightX, paY + paH, 4f, RpTheme.PANEL_BORDER, RpTheme.PANEL_BG_EVEN);
        g.drawString(
                font,
                Component.translatable("ccnr_rp.xp.rules.params").getString() + " (" + eventId + ")",
                ex1 + 6,
                paY + 4,
                RpTheme.TEXT_DIM);
        paramBounds.clear();
        EventDef def = ExperienceEventRegistry.byId(eventId).orElse(null);
        if (def != null) {
            int maxP = Math.max(1, (paH - 18) / 16);
            int off2 = Math.min(paramScroll, Math.max(0, def.params().size() - maxP));
            for (int i = 0; i < def.params().size() && i < maxP; i++) {
                Param p = def.params().get(off2 + i);
                int py = paY + 20 + i * 16;
                boolean hov = mx >= ex1 && mx <= rightX && my >= py && my <= py + 15;
                if (hov) {
                    g.fill(ex1 + 2, py, rightX - 2, py + 15, RpTheme.PANEL_BG_ALT);
                }
                String type = p.type() == ExperienceEventRegistry.ParamType.LONG ? "LONG" : "STRING";
                g.drawString(font, p.name(), ex1 + 8, py + 3, hov ? 0xFFFFFFFF : RpTheme.TEXT_PRIMARY);
                g.drawString(font, "(" + type + ")", ex1 + 8 + font.width(p.name()) + 8, py + 3, RpTheme.TEXT_DIM);
                g.drawString(font, "⇧ 插入", rightX - 44, py + 3, RpTheme.CYAN);
                paramBounds.add(new int[] {ex1, py, rightX, py + 15});
            }
        }

        // 验证器 + 试算 + 保存
        int vy = paY + paH + 6;
        renderValidation(g, mx, my, vy, rightX);

        if (!notice.isBlank() && System.currentTimeMillis() < noticeUntil) {
            g.drawCenteredString(font, notice, (px1 + px2) / 2, py2 - 18, RpTheme.RED_LINE);
        }
    }

    private void renderValidation(GuiGraphics g, int mx, int my, int vy, int rightX) {
        var font = Minecraft.getInstance().font;
        String[] exprs = {
            condBox == null ? "" : condBox.getValue(),
            valueBox == null ? "" : valueBox.getValue(),
            titleBox == null ? "" : titleBox.getValue()
        };
        String[] labels = {"判断", "数值", "标题"};
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
        RpRoundRect.outlined(
                g,
                bx,
                y + 2,
                bx + bw,
                y + 18,
                3f,
                hov1 ? RpTheme.PANEL_BORDER_BRIGHT : RpTheme.PANEL_BORDER,
                RpTheme.PANEL_BG_ALT);
        g.drawCenteredString(
                font, Component.translatable("ccnr_rp.xp.rules.test").getString(), bx + bw / 2, y + 6, 0xFFFFFFFF);
        btnBounds.add(new int[] {bx, y + 2, bx + bw, y + 18, BTN_TEST});
        int sx = rightX - bw;
        boolean hov2 = mx >= sx && mx <= rightX && my >= y + 2 && my <= y + 18;
        RpRoundRect.outlined(
                g,
                sx,
                y + 2,
                rightX,
                y + 18,
                3f,
                hov2 ? RpTheme.PANEL_BORDER_BRIGHT : RpTheme.PANEL_BORDER,
                RpTheme.PANEL_BG_ALT);
        g.drawCenteredString(
                font, Component.translatable("ccnr_rp.xp.rules.save").getString(), sx + bw / 2, y + 6, 0xFFFFFFFF);
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
            return index == 1 ? "空表达式" : "";
        }
        try {
            Expr e = ExprParser.parse(src);
            Map<String, Kind> kinds = ExperienceEventRegistry.paramKinds(eventId);
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

    public boolean mouseClicked(int mx, int my, int button) {
        if (button != 0) {
            return false;
        }
        int evX = ex1 + 100;
        int evY = ey1 + 22;
        int evW = Math.min(180, ex2 - ex1 - 100);
        if (eventMenuOpen) {
            List<EventDef> evs = ExperienceEventRegistry.EVENTS;
            for (int i = 0; i < evs.size(); i++) {
                int oy = evY + 20 + i * 16;
                if (mx >= evX && mx <= evX + evW && my >= oy && my <= oy + 15) {
                    eventId = evs.get(i).id();
                    eventMenuOpen = false;
                    return true;
                }
            }
            eventMenuOpen = false;
            return true;
        }
        if (mx >= evX && mx <= evX + evW && my >= evY && my <= evY + 18) {
            eventMenuOpen = true;
            return true;
        }
        // 规则列表行
        int maxVisible = Math.max(1, (listY2 - listY1) / ROW_H);
        int off = Math.min(rulesScroll, Math.max(0, rules.size() - maxVisible));
        for (int i = 0; i < rules.size() && i < maxVisible; i++) {
            int y1 = listY1 + i * ROW_H;
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
                    sendEdit("remove", str(rules.get(sel), "id"));
                    sel = -1;
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
        Map<String, Object> sample = ExperienceEventRegistry.defaultSample(eventId);
        testLines.clear();
        if (cond.isBlank()) {
            testLines.add("判断 → 空（恒激活）");
        } else {
            try {
                boolean b = ExprEvaluator.evalBool(ExprParser.parse(cond), sample);
                testLines.add("判断 → " + b + (b ? "（激活）" : "（跳过）"));
            } catch (ExprException e) {
                testLines.add("判断 → ✗ " + e.getMessage());
            }
        }
        try {
            double v = ExprEvaluator.evalNum(ExprParser.parse(value), sample);
            testLines.add("数值 → " + ExprEvaluator.stringify(v));
        } catch (ExprException e) {
            testLines.add("数值 → ✗ " + e.getMessage());
        }
        try {
            String t = ExprEvaluator.evalString(ExprParser.parse(title), sample);
            testLines.add("标题 → " + (t.isEmpty() ? "（空）" : t));
        } catch (ExprException e) {
            testLines.add("标题 → ✗ " + e.getMessage());
        }
        testAt = System.currentTimeMillis();
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
        rule.addProperty("eventId", eventId);
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
            flashRaw(e.getMessage() == null ? "保存失败" : e.getMessage());
        }
    }

    public void mouseScrolled(int mouseX, int mouseY, double delta) {
        if (mouseX >= listX1 && mouseX <= listX2 && mouseY >= listY1 && mouseY <= listY2) {
            int max = Math.max(0, rules.size() - Math.max(1, (listY2 - listY1) / ROW_H));
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
            RpRoundRect.outlined(
                    g,
                    b[0],
                    b[1],
                    b[2],
                    b[3],
                    3f,
                    hov ? RpTheme.PANEL_BORDER_BRIGHT : RpTheme.PANEL_BORDER,
                    RpTheme.PANEL_BG_ALT);
            String key =
                    switch (b[4]) {
                        case BTN_ADD -> "ccnr_rp.xp.rules.add";
                        case BTN_REMOVE -> "ccnr_rp.xp.rules.remove";
                        case BTN_TOGGLE -> "ccnr_rp.xp.rules.toggle";
                        default -> "";
                    };
            if (!key.isEmpty()) {
                g.drawCenteredString(
                        font, Component.translatable(key).getString(), (b[0] + b[2]) / 2, b[1] + 4, 0xFFFFFFFF);
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
}
