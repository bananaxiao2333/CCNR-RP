/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 行为序列编辑弹窗：编辑所选事件/阶段/刷新波的内嵌步骤（WAIT/WAVE/COMMAND/FORCE_PICK）。
 * 保存时写回拥有者持有的列表实例（原地替换），关闭后返回拥有者界面。
 */
public final class StepsEditorModal extends Screen {

    private static final String[] TYPES = {"WAIT", "WAVE", "COMMAND", "FORCE_PICK"};

    private final RpAdminScreen owner;
    private final List<JsonObject> steps; // owner 实例字段（保存时原地替换）
    private final List<JsonObject> work = new ArrayList<>();
    private final List<int[]> stepBounds = new ArrayList<>();
    private int stepSel = -1;
    private int typeIdx = 0;
    private boolean randomName = true;
    private boolean spawn = true;
    private EditBox fld2;
    private EditBox fld3;
    private EditBox fld4;
    private int x1, y1, x2, y2;
    private int closeX1, closeY1, closeX2, closeY2;

    public StepsEditorModal(RpAdminScreen owner, List<JsonObject> steps) {
        super(Component.translatable("ccnr_rp.gui.admin.seq.title"));
        this.owner = owner;
        this.steps = steps;
        for (JsonObject s : steps) {
            work.add(s.deepCopy());
        }
        if (!work.isEmpty()) {
            stepSel = 0;
            applyToEditor();
        }
    }

    @Override
    protected void init() {
        int pw = Math.max(430, Math.min(width * 2 / 3, 560));
        int ph = Math.max(360, Math.min(height - 60, 460));
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
        stepBounds.clear();
        int x = x1 + 14;
        int w = x2 - x1 - 28;
        int y = y1 + 34;
        // 步骤操作行
        int bw4 = Math.max(56, (w - 12) / 4);
        addRenderableWidget(RpButton.secondary(x, y, bw4, 18, Component.literal("+ 步骤"), b -> {
            work.add(defaultStep());
            stepSel = work.size() - 1;
            applyToEditor();
            rebuild();
        }));
        addRenderableWidget(RpButton.danger(x + bw4 + 4, y, bw4, 18, Component.literal("删步骤"), b -> {
            if (stepSel >= 0 && stepSel < work.size()) {
                work.remove(stepSel);
                stepSel = work.isEmpty() ? -1 : Math.max(0, stepSel - 1);
                applyToEditor();
                rebuild();
            }
        }));
        addRenderableWidget(RpButton.secondary(x + (bw4 + 4) * 2, y, bw4, 18, Component.literal("上移"), b -> {
            if (stepSel > 0) {
                java.util.Collections.swap(work, stepSel, stepSel - 1);
                stepSel--;
                rebuild();
            }
        }));
        addRenderableWidget(RpButton.secondary(x + (bw4 + 4) * 3, y, bw4, 18, Component.literal("下移"), b -> {
            if (stepSel >= 0 && stepSel + 1 < work.size()) {
                java.util.Collections.swap(work, stepSel, stepSel + 1);
                stepSel++;
                rebuild();
            }
        }));
        y += 22;
        for (int i = 0; i < work.size() && i < 6; i++) {
            stepBounds.add(new int[] {x, y, x + w, y + 18});
            y += 19;
        }
        y += 6;
        String type = TYPES[typeIdx];
        int bw2 = (w - 4) / 2;
        addRenderableWidget(RpButton.secondary(x, y, bw2, 18, Component.literal("类型: " + type), b -> {
            typeIdx = (typeIdx + 1) % TYPES.length;
            rebuild();
        }));
        if ("FORCE_PICK".equals(type)) {
            addRenderableWidget(RpButton.secondary(
                    x + bw2 + 4, y, bw2, 18, Component.literal("随机名: " + (randomName ? "是" : "否")), b -> {
                        randomName = !randomName;
                        rebuild();
                    }));
            y += 22;
            fld2 = mkBox(x, y, bw2, "数量", "5");
            fld3 = mkBox(x + bw2 + 4, y, bw2, "职业ID(逗号)", "");
            y += 22;
            fld4 = mkBox(x, y, w, "阵营ID", "");
            y += 22;
            addRenderableWidget(RpButton.primary(x, y, w, 18, Component.literal("刷新: " + (spawn ? "是" : "否")), b -> {
                spawn = !spawn;
                rebuild();
            }));
        } else {
            String hint =
                    switch (type) {
                        case "WAIT" -> "等待秒数";
                        case "WAVE" -> "刷新波ID";
                        default -> "命令文本（可用 {{event}} {{phase}} {{seq}} 变量）";
                    };
            fld2 = mkBox(x, y + 22, w, hint, type.equals("WAIT") ? "10" : "");
        }
        y += 52;
        int bw3 = Math.max(70, w / 3);
        addRenderableWidget(RpButton.primary(
                x, y, bw3, 20, Component.translatable("ccnr_rp.gui.admin.seq.save"), b -> saveAndClose()));
        addRenderableWidget(RpButton.secondary(
                x + (bw3 + 4), y, bw3, 20, Component.translatable("ccnr_rp.gui.admin.seq.cancel"), b -> close()));
    }

    private EditBox mkBox(int x, int y, int w, String hint, String value) {
        EditBox box = new EditBox(font, x, y, w, 18, Component.literal(hint));
        box.setMaxLength(512);
        box.setValue(value);
        box.setTextColor(RpTheme.CYAN);
        addRenderableWidget(box);
        return box;
    }

    private JsonObject current() {
        return stepSel >= 0 && stepSel < work.size() ? work.get(stepSel) : null;
    }

    private void applyToEditor() {
        JsonObject s = current();
        if (s == null) {
            typeIdx = 0;
            randomName = true;
            spawn = true;
            return;
        }
        String type = str(s, "type", "WAIT").toUpperCase(java.util.Locale.ROOT);
        for (int i = 0; i < TYPES.length; i++) {
            if (TYPES[i].equals(type)) {
                typeIdx = i;
            }
        }
        randomName = !s.has("randomName") || s.get("randomName").getAsBoolean();
        spawn = !s.has("spawn") || s.get("spawn").getAsBoolean();
    }

    /** 编辑器状态写回选中步骤（保存前调用）。 */
    private void applyToStep() {
        JsonObject s = current();
        if (s == null || fld2 == null) {
            return;
        }
        String type = TYPES[typeIdx];
        s.addProperty("type", type);
        switch (type) {
            case "WAIT" -> s.addProperty("seconds", Math.max(0, parseInt(fld2)));
            case "WAVE" -> s.addProperty("wave", fld2.getValue());
            case "COMMAND" -> s.addProperty("command", fld2.getValue());
            case "FORCE_PICK" -> {
                s.addProperty("count", Math.max(1, parseInt(fld2)));
                s.addProperty("professions", fld3.getValue());
                s.addProperty("faction", fld4.getValue());
                s.addProperty("randomName", randomName);
                s.addProperty("spawn", spawn);
            }
            default -> {}
        }
    }

    private void saveAndClose() {
        applyToStep();
        steps.clear();
        for (JsonObject s : work) {
            steps.add(s.deepCopy());
        }
        close();
    }

    private void close() {
        Minecraft.getInstance().setScreen(owner);
    }

    private static JsonObject defaultStep() {
        JsonObject s = new JsonObject();
        s.addProperty("type", "WAIT");
        s.addProperty("seconds", 10);
        return s;
    }

    private static int parseInt(EditBox box) {
        try {
            return Integer.parseInt(box.getValue().trim());
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xAA000000);
        RpTheme.terminalPanel(g, x1, y1, x2, y2, RpTheme.RADIUS_LARGE);
        g.drawString(
                font,
                Component.translatable("ccnr_rp.gui.admin.seq.title")
                        .getString()
                        .toUpperCase(java.util.Locale.ROOT),
                x1 + 14,
                y1 + 10,
                RpTheme.CYAN,
                true);
        boolean hover = mouseX >= closeX1 && mouseX <= closeX2 && mouseY >= closeY1 && mouseY <= closeY2;
        if (hover) {
            g.fill(closeX1 - 2, closeY1 - 1, closeX2 + 2, closeY2 + 1, 0xE66F1613);
        }
        g.drawString(
                font, "X", (closeX1 + closeX2) / 2 - 2, closeY1 + 4, hover ? 0xFFFFFFFF : RpTheme.TEXT_SECONDARY, true);
        g.fill(x1 + 8, y1 + 28, x2 - 8, y1 + 29, RpTheme.CYAN_DIM);
        // 步骤列表
        for (int i = 0; i < stepBounds.size(); i++) {
            int[] b = stepBounds.get(i);
            boolean sel = i == stepSel;
            if (sel) {
                RpTheme.selectedBar(g, b[0], b[1], b[2], b[3], 3f);
            } else {
                g.fill(b[0], b[1], b[2], b[3] + 1, i % 2 == 0 ? RpTheme.PANEL_BG : 0x00000000);
            }
            JsonObject s = current() != null && i == stepSel ? current() : i < work.size() ? work.get(i) : null;
            g.drawString(
                    font,
                    (i + 1) + ". " + summary(s),
                    b[0] + 4,
                    b[1] + 2,
                    sel ? 0xFFFFFFFF : RpTheme.TEXT_PRIMARY,
                    true);
        }
        if (work.isEmpty()) {
            g.drawString(font, "（暂无步骤 —— 点击「+ 步骤」添加行为）", x1 + 14, y1 + 70, RpTheme.TEXT_DIM);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    private String summary(JsonObject s) {
        if (s == null) {
            return "?";
        }
        String type = str(s, "type", "WAIT");
        return switch (type) {
            case "WAIT" -> "WAIT " + num(s, "seconds", 0) + "s";
            case "WAVE" -> "WAVE " + str(s, "wave");
            case "COMMAND" -> "CMD " + str(s, "command");
            case "FORCE_PICK" -> "PICK " + num(s, "count", 1) + "人[" + str(s, "faction") + "]";
            default -> type;
        };
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        if (mx >= closeX1 && mx <= closeX2 && my >= closeY1 && my <= closeY2) {
            close();
            return true;
        }
        for (int i = 0; i < stepBounds.size(); i++) {
            int[] b = stepBounds.get(i);
            if (mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3]) {
                applyToStep();
                stepSel = i;
                applyToEditor();
                rebuild();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static long num(JsonObject o, String key, long def) {
        try {
            return o.has(key) ? o.get(key).getAsLong() : def;
        } catch (Exception e) {
            return def;
        }
    }
}
