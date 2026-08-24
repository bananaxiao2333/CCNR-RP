/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.ccnrcom.rp.network.RpChannels;
import com.ccnrcom.rp.network.RpPackets;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
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
    private static final int TAB_EVENT = 3;
    private static final int TAB_PHASE = 4;
    private static final int TAB_WAVE = 5;
    private static final int TAB_SEQUENCE = 6;

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
    private String selSelId = "";
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
    private EditBox fld2Box;
    private EditBox fld3Box;
    private EditBox fld4Box;
    /** 输入框上方用途标签（x, y, key/raw text），rebuild 时清空重填。 */
    private final List<Object[]> fieldLabels = new ArrayList<>();

    private boolean evState = true;
    private boolean endSettle = true;
    private int modeIdx = 0;
    private int deployIdx = 0;
    private final List<JsonObject> seqSteps = new ArrayList<>();
    private final List<int[]> stepBounds = new ArrayList<>();
    private int stepSel = -1;
    private int stepTypeIdx = 0;
    private boolean stepRandom = true;
    private boolean stepSpawn = true;
    private static final String[] STEP_TYPES = {"WAIT", "WAVE", "COMMAND", "FORCE_PICK"};

    private static final String[] ICONS = {"hex", "shield", "claw", "storm", "eye", "target", "cross", "gear"};
    private static final String[] TABS = {
        "ccnr_rp.gui.admin.tab.settings",
        "ccnr_rp.gui.admin.tab.profession",
        "ccnr_rp.gui.admin.tab.faction",
        "ccnr_rp.gui.admin.tab.event",
        "ccnr_rp.gui.admin.tab.phase",
        "ccnr_rp.gui.admin.tab.wave",
        "ccnr_rp.gui.admin.tab.sequence"
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
        fieldLabels.clear();
        int tabW = Math.min(88, (px2 - px1 - 30) / 7);
        int tx = px1 + 12;
        for (int i = 0; i < 7; i++) {
            int x = tx + i * (tabW + 6);
            rowBounds.add(new int[] {x, py1 + 42, x + tabW, py1 + 62});
        }
        if (tab == TAB_SETTINGS) {
            int y = py1 + 80;
            for (int i = 0; i < 6; i++) {
                rowBounds.add(new int[] {px1 + 12, y, px2 - 12, y + 40});
                y += 44;
            }
        } else {
            List<JsonObject> items = listItems();
            int rowH = rowHeight();
            if (rowH > 0) {
                int maxVisible = Math.max(1, (listY2 - listY1) / rowH);
                int off = Math.min(scroll, Math.max(0, items.size() - maxVisible));
                for (int i = 0; i < items.size() && i < maxVisible; i++) {
                    rowBounds.add(new int[] {listX1, listY1 + i * rowH, listX2, listY1 + (i + 1) * rowH - 1});
                }
            }
        }
        buildForm();
    }

    private List<JsonObject> listItems() {
        return switch (tab) {
            case TAB_PROFESSION -> ClientCharacterState.professions();
            case TAB_FACTION -> ClientCharacterState.factions();
            case TAB_EVENT -> ClientCharacterState.managerEvents();
            case TAB_PHASE -> ClientCharacterState.managerPhases();
            case TAB_WAVE -> ClientCharacterState.managerWaves();
            case TAB_SEQUENCE -> ClientCharacterState.managerSequences();
            default -> List.of();
        };
    }

    private int rowHeight() {
        return switch (tab) {
            case TAB_PROFESSION -> 20;
            case TAB_FACTION -> 22;
            case TAB_EVENT, TAB_PHASE, TAB_WAVE, TAB_SEQUENCE -> 20;
            default -> 0;
        };
    }

    private void buildForm() {
        switch (tab) {
            case TAB_PROFESSION -> buildProfessionForm();
            case TAB_FACTION -> buildFactionForm();
            case TAB_EVENT -> buildEventForm();
            case TAB_PHASE -> buildPhaseForm();
            case TAB_WAVE -> buildWaveForm();
            case TAB_SEQUENCE -> buildSequenceForm();
            default -> {}
        }
    }

    private void buildEventForm() {
        int x = listX2 + 10;
        int w = px2 - 12 - x;
        int y = py1 + 76;
        JsonObject ev = selItem();
        String id = ev == null ? "" : str(ev, "id");
        boolean edit = !id.isBlank();
        idBox = mkBox(x, y, w, "ccnr_rp.gui.admin.field.id", id, !edit);
        y += 30;
        boolean enabled = ev == null || !ev.has("enabled") || ev.get("enabled").getAsBoolean();
        addRenderableWidget(
                RpButton.secondary(x, y, (w - 4) / 2, 18, Component.literal("启用: " + (enabled ? "是" : "否")), b -> {
                    evState = !evState;
                    rebuild();
                }));
        evState = enabled;
        endSettle =
                ev == null || !ev.has("settleOnEnd") || ev.get("settleOnEnd").getAsBoolean();
        addRenderableWidget(RpButton.secondary(
                x + (w - 4) / 2 + 4, y, (w - 4) / 2, 18, Component.literal("结束后结算: " + (endSettle ? "是" : "否")), b -> {
                    endSettle = !endSettle;
                    rebuild();
                }));
        y += 30;
        fld3Box = mkBox(x, y, w, "时长(秒,0=事件持续时间)", ev == null ? "0" : num(ev, "durationSeconds", 0), false);
        y += 30;
        actionRow(x, y, w, edit);
    }

    private void buildPhaseForm() {
        int x = listX2 + 10;
        int w = px2 - 12 - x;
        int y = py1 + 76;
        JsonObject ph = selItem();
        String id = ph == null ? "" : str(ph, "id");
        boolean edit = !id.isBlank();
        idBox = mkBox(x, y, w, "ccnr_rp.gui.admin.field.id", id, !edit);
        y += 30;
        fld2Box = mkBox(x, y, w, "顺序 order", ph == null ? "0" : num(ph, "order", 0), false);
        y += 30;
        fld3Box = mkBox(x, y, w, "时长(分钟)", ph == null ? "30" : num(ph, "durationMinutes", 30), false);
        y += 30;
        actionRow(x, y, w, edit);
    }

    private void buildSequenceForm() {
        int x = listX2 + 10;
        int w = px2 - 12 - x;
        int y = py1 + 76;
        JsonObject seq = selItem();
        String id = seq == null ? "" : str(seq, "id");
        boolean edit = !id.isBlank();
        idBox = mkBox(x, y, w, "ccnr_rp.gui.admin.field.id", id, !edit);
        y += 30;
        // 步骤操作行
        int bw4 = Math.max(56, (w - 12) / 4);
        addRenderableWidget(RpButton.secondary(x, y, bw4, 18, Component.literal("+ 步骤"), b -> {
            seqSteps.add(defaultStep());
            stepSel = seqSteps.size() - 1;
            applyStepEditor();
            rebuild();
        }));
        addRenderableWidget(RpButton.danger(x + bw4 + 4, y, bw4, 18, Component.literal("删步骤"), b -> {
            if (stepSel >= 0 && stepSel < seqSteps.size()) {
                seqSteps.remove(stepSel);
                stepSel = seqSteps.isEmpty() ? -1 : Math.max(0, stepSel - 1);
                applyStepEditor();
                rebuild();
            }
        }));
        addRenderableWidget(RpButton.secondary(x + (bw4 + 4) * 2, y, bw4, 18, Component.literal("上移"), b -> {
            if (stepSel > 0) {
                java.util.Collections.swap(seqSteps, stepSel, stepSel - 1);
                stepSel--;
                rebuild();
            }
        }));
        addRenderableWidget(RpButton.secondary(x + (bw4 + 4) * 3, y, bw4, 18, Component.literal("下移"), b -> {
            if (stepSel >= 0 && stepSel + 1 < seqSteps.size()) {
                java.util.Collections.swap(seqSteps, stepSel, stepSel + 1);
                stepSel++;
                rebuild();
            }
        }));
        y += 22;
        // 步骤列表区（手动渲染 + 命中）
        stepBounds.clear();
        int sy = y;
        for (int i = 0; i < seqSteps.size() && i < 8; i++) {
            stepBounds.add(new int[] {x, sy, x + w, sy + 18});
            sy += 19;
        }
        y = sy + 6;
        // 当前步骤编辑
        String type = STEP_TYPES[stepTypeIdx];
        int bw2 = (w - 4) / 2;
        addRenderableWidget(RpButton.secondary(x, y, bw2, 18, Component.literal("类型: " + type), b -> {
            stepTypeIdx = (stepTypeIdx + 1) % STEP_TYPES.length;
            rebuild();
        }));
        if ("FORCE_PICK".equals(type)) {
            addRenderableWidget(RpButton.secondary(
                    x + bw2 + 4, y, bw2, 18, Component.literal("随机名: " + (stepRandom ? "是" : "否")), b -> {
                        stepRandom = !stepRandom;
                        rebuild();
                    }));
            y += 30;
            fld2Box = mkBox(x, y, bw2, "数量", valueOr(seqStep(), "count", "5"), false);
            fld3Box = mkBox(x + bw2 + 4, y, bw2, "职业ID(逗号)", valueOr(seqStep(), "professions", ""), false);
            y += 30;
            fld4Box = mkBox(x, y, w, "阵营ID", valueOr(seqStep(), "faction", ""), false);
            y += 30;
            addRenderableWidget(
                    RpButton.primary(x, y, w, 18, Component.literal("刷新: " + (stepSpawn ? "是" : "否")), b -> {
                        stepSpawn = !stepSpawn;
                        rebuild();
                    }));
        } else if ("WAIT".equals(type)) {
            fld2Box = mkBox(x, y + 30, w, "等待秒数", valueOr(seqStep(), "seconds", "10"), false);
        } else if ("WAVE".equals(type)) {
            fld2Box = mkBox(x, y + 30, w, "刷新波ID", valueOr(seqStep(), "wave", ""), false);
        } else {
            fld2Box = mkBox(
                    x, y + 30, w, "命令文本（可用 {{event}} {{phase}} {{seq}} 变量）", valueOr(seqStep(), "command", ""), false);
        }
        y += 50;
        actionRow(x, y, w, edit);
    }

    private JsonObject seqStep() {
        return stepSel >= 0 && stepSel < seqSteps.size() ? seqSteps.get(stepSel) : null;
    }

    private String valueOr(JsonObject step, String key, String def) {
        if (step == null || !step.has(key)) {
            return def;
        }
        return step.get(key).getAsString();
    }

    private JsonObject defaultStep() {
        JsonObject s = new JsonObject();
        s.addProperty("type", "WAIT");
        s.addProperty("seconds", 10);
        return s;
    }

    private void applyStepEditor() {
        JsonObject step = seqStep();
        if (step == null) {
            stepTypeIdx = 0;
            stepRandom = true;
            stepSpawn = true;
            return;
        }
        String type = str(step, "type", "WAIT").toUpperCase(java.util.Locale.ROOT);
        for (int i = 0; i < STEP_TYPES.length; i++) {
            if (STEP_TYPES[i].equals(type)) {
                stepTypeIdx = i;
            }
        }
        stepRandom = !step.has("randomName") || step.get("randomName").getAsBoolean();
        stepSpawn = !step.has("spawn") || step.get("spawn").getAsBoolean();
    }

    /** 把当前编辑器状态写回选中步骤。 */
    private void applyEditorToStep() {
        JsonObject step = seqStep();
        if (step == null) {
            return;
        }
        String type = STEP_TYPES[stepTypeIdx];
        step.addProperty("type", type);
        switch (type) {
            case "WAIT" -> step.addProperty("seconds", Math.max(0, parseInt(fld2Box)));
            case "WAVE" -> step.addProperty("wave", fld2Box.getValue());
            case "COMMAND" -> step.addProperty("command", fld2Box.getValue());
            case "FORCE_PICK" -> {
                step.addProperty("count", Math.max(1, parseInt(fld2Box)));
                step.addProperty("professions", fld3Box.getValue());
                step.addProperty("faction", fld4Box.getValue());
                step.addProperty("randomName", stepRandom);
                step.addProperty("spawn", stepSpawn);
            }
            default -> {}
        }
    }

    private void buildWaveForm() {
        int x = listX2 + 10;
        int w = px2 - 12 - x;
        int y = py1 + 76;
        JsonObject wv = selItem();
        String id = wv == null ? "" : str(wv, "id");
        boolean edit = !id.isBlank();
        idBox = mkBox(x, y, w, "ccnr_rp.gui.admin.field.id", id, !edit);
        y += 30;
        int bw2 = (w - 4) / 2;
        addRenderableWidget(RpButton.secondary(x, y, bw2, 18, Component.literal("模式: " + Modes[modeIdx]), b -> {
            modeIdx = (modeIdx + 1) % Modes.length;
            rebuild();
        }));
        addRenderableWidget(
                RpButton.secondary(x + bw2 + 4, y, bw2, 18, Component.literal("部署点: " + DeployTypes[deployIdx]), b -> {
                    deployIdx = (deployIdx + 1) % DeployTypes.length;
                    rebuild();
                }));
        y += 30;
        fld2Box = mkBox(x, y, bw2, "数量", wv == null ? "1" : num(wv, "count", 1), false);
        fld3Box = mkBox(x + bw2 + 4, y, bw2, "最低等级", wv == null ? "0" : num(wv, "minLevel", 0), false);
        y += 30;
        fld4Box = mkBox(x, y, w, "招募时限(秒)", wv == null ? "60" : num(wv, "recruitTimeoutSeconds", 60), false);
        y += 30;
        profileBox = mkBox(x, y, w, "坐标 x y z（POS 时用）", wv == null ? "" : posStr(wv), false);
        y += 30;
        nameBox =
                mkBox(x, y, bw2, "维度(minecraft:overworld)", wv == null ? "minecraft:overworld" : str(wv, "dim"), false);
        colorBox = mkBox(x + bw2 + 4, y, bw2, "队伍ID(逗号)", csv(wv, "teamIds"), false);
        y += 30;
        descBox = mkBox(x, y, bw2, "职业ID(逗号)", csv(wv, "professionIds"), false);
        musicBox = mkBox(x + bw2 + 4, y, bw2, "阵营ID(逗号)", csv(wv, "factionIds"), false);
        y += 30;
        actionRow(x, y, w, edit);
    }

    /** 通用操作行：保存/删除/新建。 */
    private void actionRow(int x, int y, int w, boolean edit) {
        int bw3 = Math.max(60, w / 4);
        String kind = crudKind();
        java.util.function.Supplier<JsonObject> builder = this::buildPayload;
        addRenderableWidget(
                RpButton.primary(x, y, bw3, 20, Component.translatable("ccnr_rp.gui.admin.crud.save"), b -> {
                    sendCrud(kind, edit ? "update" : "create", builder.get());
                }));
        addRenderableWidget(
                RpButton.danger(x + bw3 + 4, y, bw3, 20, Component.translatable("ccnr_rp.gui.admin.crud.delete"), b -> {
                    String sel = idBox.getValue();
                    if (sel.isBlank()) {
                        notice = "缺少 id";
                        return;
                    }
                    JsonObject del = payload();
                    del.addProperty("id", sel);
                    sendCrud(kind, "delete", del);
                }));
        addRenderableWidget(RpButton.secondary(
                x + (bw3 + 4) * 2, y, bw3, 20, Component.translatable("ccnr_rp.gui.admin.crud.new"), b -> {
                    selProfId = "";
                    selFactionId = "";
                    selSelId = "";
                    modeIdx = 0;
                    deployIdx = 0;
                    evState = true;
                    endSettle = true;
                    seqSteps.clear();
                    stepSel = -1;
                    stepTypeIdx = 0;
                    rebuild();
                }));
    }

    private String crudKind() {
        return switch (tab) {
            case TAB_PROFESSION -> "profession";
            case TAB_FACTION -> "faction";
            case TAB_EVENT -> "event";
            case TAB_PHASE -> "phase";
            case TAB_WAVE -> "wave";
            case TAB_SEQUENCE -> "sequence";
            default -> "";
        };
    }

    private JsonObject buildPayload() {
        return switch (tab) {
            case TAB_PROFESSION -> {
                JsonObject p = payload();
                p.addProperty("id", idBox.getValue());
                p.addProperty("name", nameBox.getValue());
                p.addProperty("factionId", currentFactionId());
                p.addProperty("selfDeploy", selfDeploy);
                p.addProperty("music", musicBox.getValue());
                p.addProperty("profile", profileBox.getValue());
                yield p;
            }
            case TAB_FACTION -> {
                JsonObject p = payload();
                p.addProperty("id", idBox.getValue());
                p.addProperty("name", nameBox.getValue());
                p.addProperty("color", colorBox.getValue());
                p.addProperty("description", descBox.getValue());
                p.addProperty("icon", ICONS[iconIdx]);
                p.addProperty("tier", tierIdx + 1);
                yield p;
            }
            case TAB_EVENT -> {
                JsonObject p = payload();
                p.addProperty("id", idBox.getValue());
                JsonObject src = selItem();
                if (src != null) {
                    p.addProperty(
                            "enabled", src.has("enabled") ? src.get("enabled").getAsBoolean() : evState);
                    p.addProperty(
                            "durationSeconds",
                            src.has("durationSeconds")
                                    ? src.get("durationSeconds").getAsInt()
                                    : parseInt(fld3Box));
                    p.addProperty(
                            "settleOnEnd",
                            src.has("settleOnEnd") ? src.get("settleOnEnd").getAsBoolean() : endSettle);
                    if (src.has("triggers")) {
                        p.add("triggers", src.getAsJsonArray("triggers"));
                    }
                    if (src.has("tasks")) {
                        p.add("tasks", src.getAsJsonArray("tasks"));
                    }
                    if (src.has("hooks")) {
                        p.add("hooks", src.getAsJsonObject("hooks"));
                    }
                } else {
                    p.addProperty("enabled", evState);
                    p.addProperty("durationSeconds", parseInt(fld3Box));
                    p.addProperty("settleOnEnd", endSettle);
                }
                yield p;
            }
            case TAB_PHASE -> {
                JsonObject p = payload();
                p.addProperty("id", idBox.getValue());
                p.addProperty("order", parseInt(fld2Box));
                p.addProperty("durationMinutes", parseInt(fld3Box));
                yield p;
            }
            case TAB_SEQUENCE -> {
                applyEditorToStep();
                JsonObject p = payload();
                p.addProperty("id", idBox.getValue());
                JsonArray steps = new JsonArray();
                for (JsonObject s : seqSteps) {
                    steps.add(s.deepCopy());
                }
                p.add("steps", steps);
                yield p;
            }
            case TAB_WAVE -> {
                JsonObject p = payload();
                p.addProperty("id", idBox.getValue());
                p.addProperty("mode", Modes[modeIdx]);
                JsonObject src = selItem();
                p.addProperty(
                        "enabled",
                        src == null || !src.has("enabled") || src.get("enabled").getAsBoolean());
                p.addProperty("count", parseInt(fld2Box));
                p.addProperty("minLevel", parseInt(fld3Box));
                p.addProperty("recruitTimeoutSeconds", parseInt(fld4Box));
                JsonObject deploy = new JsonObject();
                deploy.addProperty("type", DeployTypes[deployIdx]);
                String pos = profileBox.getValue();
                if (pos != null && !pos.isBlank()) {
                    String[] parts = pos.trim().split("\\s+");
                    for (int i = 0; i < parts.length && i < 3; i++) {
                        try {
                            deploy.addProperty(i == 0 ? "x" : i == 1 ? "y" : "z", Integer.parseInt(parts[i]));
                        } catch (Exception ignored) {
                            // 忽略非法坐标
                        }
                    }
                }
                p.add("deployAt", deploy);
                if (!nameBox.getValue().isBlank()) {
                    p.addProperty("dim", nameBox.getValue());
                }
                p.add("teamIds", csvArray(colorBox.getValue()));
                p.add("professionIds", csvArray(descBox.getValue()));
                p.add("factionIds", csvArray(musicBox.getValue()));
                yield p;
            }
            default -> payload();
        };
    }

    private static int parseInt(EditBox box) {
        if (box == null || box.getValue() == null) {
            return 0;
        }
        try {
            return Integer.parseInt(box.getValue().trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static JsonArray csvArray(String s) {
        JsonArray a = new JsonArray();
        if (s == null || s.isBlank()) {
            return a;
        }
        for (String part : s.split(",")) {
            String t = part.trim();
            if (!t.isBlank()) {
                a.add(t);
            }
        }
        return a;
    }

    private JsonObject selItem() {
        List<JsonObject> items = listItems();
        for (JsonObject o : items) {
            if (str(o, "id").equals(selSelId)) {
                return o;
            }
        }
        return null;
    }

    private static String csv(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonArray()) {
            return "";
        }
        java.util.List<String> parts = new ArrayList<>();
        for (com.google.gson.JsonElement e : o.getAsJsonArray(key)) {
            parts.add(e.getAsString());
        }
        return String.join(",", parts);
    }

    private static String posStr(JsonObject o) {
        if (o == null || !o.has("deployAt") || !o.get("deployAt").isJsonObject()) {
            return "";
        }
        JsonObject d = o.getAsJsonObject("deployAt");
        StringBuilder sb = new StringBuilder();
        for (String k : java.util.List.of("x", "y", "z")) {
            if (d.has(k)) {
                if (sb.length() > 0) {
                    sb.append(" ");
                }
                sb.append(d.get(k).getAsString());
            }
        }
        return sb.toString();
    }

    private static String num(JsonObject o, String key, long def) {
        try {
            return o != null && o.has(key) ? o.get(key).getAsString() : String.valueOf(def);
        } catch (Exception e) {
            return String.valueOf(def);
        }
    }

    private static final String[] Modes = {"SELF_DEPLOY", "RECRUIT", "BOTH"};
    private static final String[] DeployTypes = {"WORLD_SPAWN", "POS"};

    // ---------- 职业表单 ----------

    private void buildProfessionForm() {
        int x = listX2 + 10;
        int w = px2 - 12 - x;
        int y = py1 + 76;
        JsonObject prof = selProf();
        String id = prof == null ? "" : str(prof, "id");
        boolean edit = !id.isBlank();
        idBox = mkBox(x, y, w, "ccnr_rp.gui.admin.field.id", id, !edit);
        y += 30;
        nameBox = mkBox(x, y, w, "ccnr_rp.gui.admin.field.name", prof == null ? "" : str(prof, "name"), false);
        y += 30;
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
        y += 30;
        musicBox = mkBox(x, y, w, "ccnr_rp.gui.admin.field.music", prof == null ? "" : str(prof, "music"), false);
        y += 30;
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
        y += 30;
        nameBox = mkBox(x, y, w, "ccnr_rp.gui.admin.field.name", fac == null ? "" : str(fac, "name"), false);
        y += 30;
        colorBox = mkBox(x, y, w, "ccnr_rp.gui.admin.field.color", fac == null ? "#FFFFFF" : str(fac, "color"), false);
        y += 30;
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
        y += 30;
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
        fieldLabels.add(new Object[] {x, y - 10, key});
        return box;
    }

    /** 绘制所有输入框上方的用途标签（lang key 直接翻译；其余为原始文本）。 */
    private void renderFieldLabels(GuiGraphics g) {
        for (Object[] l : fieldLabels) {
            String key = (String) l[2];
            String text =
                    key.startsWith("ccnr_rp.") ? Component.translatable(key).getString() : key;
            g.drawString(font, text, (Integer) l[0], (Integer) l[1], RpTheme.TEXT_DIM, false);
        }
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
        for (int i = 0; i < 7; i++) {
            int[] b = rowBounds.get(i);
            if (mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3]) {
                tab = i;
                scroll = 0;
                selProfId = "";
                selFactionId = "";
                selSelId = "";
                seqSteps.clear();
                stepSel = -1;
                rebuild();
                return true;
            }
        }
        // 序列步骤行
        if (tab == TAB_SEQUENCE) {
            for (int i = 0; i < stepBounds.size(); i++) {
                int[] b = stepBounds.get(i);
                if (mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3]) {
                    stepSel = i;
                    applyStepEditor();
                    rebuild();
                    return true;
                }
            }
        }
        if (!ClientCharacterState.isAdmin() && tab != TAB_SETTINGS) {
            notice = Component.translatable("ccnr_rp.gui.admin.no_perm").getString();
            return true;
        }
        for (int i = 7; i < rowBounds.size(); i++) {
            int[] b = rowBounds.get(i);
            if (mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3]) {
                if (tab == TAB_SETTINGS) {
                    if (!ClientCharacterState.isAdmin()) {
                        notice = Component.translatable("ccnr_rp.gui.admin.no_perm")
                                .getString();
                        return true;
                    }
                    String key = SETTING_KEYS[i - 7];
                    RpChannels.sendToServer(new RpPackets.ManagerSetC2S(key, String.valueOf(!value(key))));
                } else {
                    JsonObject item = visibleItem(i - 7);
                    if (item != null) {
                        selectItem(item);
                    }
                }
                return true;
            }
        }
        return false;
    }

    private JsonObject visibleItem(int i) {
        List<JsonObject> items = listItems();
        int rowH = rowHeight();
        if (rowH == 0) {
            return null;
        }
        int maxVisible = Math.max(1, (listY2 - listY1) / rowH);
        int off = Math.min(scroll, Math.max(0, items.size() - maxVisible));
        int idx = off + i;
        return idx < items.size() ? items.get(idx) : null;
    }

    private void selectItem(JsonObject item) {
        if (tab == TAB_PROFESSION) {
            selectProfession(item);
            return;
        }
        if (tab == TAB_FACTION) {
            selectFaction(item);
            return;
        }
        selSelId = str(item, "id");
        if (tab == TAB_EVENT) {
            evState = !item.has("enabled") || item.get("enabled").getAsBoolean();
            endSettle = !item.has("settleOnEnd") || item.get("settleOnEnd").getAsBoolean();
        } else if (tab == TAB_SEQUENCE) {
            selSelId = str(item, "id");
            seqSteps.clear();
            if (item.has("steps") && item.get("steps").isJsonArray()) {
                for (com.google.gson.JsonElement e : item.getAsJsonArray("steps")) {
                    if (e.isJsonObject()) {
                        seqSteps.add(e.getAsJsonObject());
                    }
                }
            }
            stepSel = seqSteps.isEmpty() ? -1 : 0;
            applyStepEditor();
        } else if (tab == TAB_WAVE) {
            String mode = str(item, "mode");
            for (int i = 0; i < Modes.length; i++) {
                if (Modes[i].equalsIgnoreCase(mode)) {
                    modeIdx = i;
                }
            }
            if (item.has("deployAt") && item.get("deployAt").isJsonObject()) {
                String ty = str(item.getAsJsonObject("deployAt"), "type");
                for (int i = 0; i < DeployTypes.length; i++) {
                    if (DeployTypes[i].equalsIgnoreCase(ty)) {
                        deployIdx = i;
                    }
                }
            }
        }
        rebuild();
    }

    private static final String[] SETTING_KEYS = {
        "forceObserving", "openPanelOnJoin", "forceRetain", "hudProfessionText", "hudFactionText", "hudHealthText"
    };
    private static final String[] SETTING_TITLES = {
        "ccnr_rp.gui.admin.setting.force_observing",
        "ccnr_rp.gui.admin.setting.open_panel",
        "ccnr_rp.gui.admin.setting.force_retain",
        "ccnr_rp.gui.admin.setting.hud_profession",
        "ccnr_rp.gui.admin.setting.hud_faction",
        "ccnr_rp.gui.admin.setting.hud_health"
    };
    private static final String[] SETTING_DESCS = {
        "ccnr_rp.gui.admin.setting.force_observing.desc",
        "ccnr_rp.gui.admin.setting.open_panel.desc",
        "ccnr_rp.gui.admin.setting.force_retain.desc",
        "ccnr_rp.gui.admin.setting.hud_profession.desc",
        "ccnr_rp.gui.admin.setting.hud_faction.desc",
        "ccnr_rp.gui.admin.setting.hud_health.desc"
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

        for (int i = 0; i < 7; i++) {
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
        } else {
            renderListTab(g, mouseX, mouseY);
        }
        if (tab == TAB_SEQUENCE) {
            renderSequenceSteps(g, mouseX, mouseY);
        }
        renderFieldLabels(g);
        if (!notice.isBlank()) {
            g.drawCenteredString(font, "[ 系统 ] " + notice, (px1 + px2) / 2, py2 - 46, RpTheme.RED_LINE);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void renderListTab(GuiGraphics g, int mouseX, int mouseY) {
        List<JsonObject> items = listItems();
        int rowH = rowHeight();
        if (rowH == 0) {
            return;
        }
        int maxVisible = Math.max(1, (listY2 - listY1) / rowH);
        int off = Math.min(scroll, Math.max(0, items.size() - maxVisible));
        RpRoundRect.outlined(
                g, listX1 - 2, listY1 - 4, listX2 + 2, listY2 + 2, 4f, RpTheme.PANEL_BORDER, RpTheme.PANEL_BG_EVEN);
        g.drawString(
                font,
                Component.translatable(TABS[tab]).getString() + " (" + items.size() + ")",
                listX1 + 4,
                listY1 - 4,
                RpTheme.TEXT_DIM);
        for (int i = 0; i < items.size() && i < maxVisible; i++) {
            JsonObject item = items.get(off + i);
            int[] b = rowBounds.get(7 + i);
            boolean sel = str(item, "id").equals(currentSelId());
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
            g.drawString(
                    font,
                    str(item, "name").isBlank() ? str(item, "id") : str(item, "name"),
                    fx,
                    b[1] + 1,
                    sel ? 0xFFFFFFFF : RpTheme.TEXT_PRIMARY,
                    true);
            g.drawString(font, str(item, "id"), fx, b[1] + 11, sel ? 0xFFFFFFFF : RpTheme.TEXT_DIM, true);
        }
    }

    private void renderSequenceSteps(GuiGraphics g, int mouseX, int mouseY) {
        var font = Minecraft.getInstance().font;
        int x = listX2 + 10;
        int w = px2 - 12 - x;
        int y = py1 + 120;
        for (int i = 0; i < stepBounds.size(); i++) {
            int[] b = stepBounds.get(i);
            boolean sel = i == stepSel;
            if (sel) {
                RpTheme.selectedBar(g, b[0], b[1], b[2], b[3], 3f);
            } else {
                g.fill(b[0], b[1], b[2], b[3] + 1, i % 2 == 0 ? RpTheme.PANEL_BG : 0x00000000);
            }
            JsonObject s = stepBounds.size() > i && i < seqSteps.size() ? seqSteps.get(i) : null;
            g.drawString(
                    font,
                    (i + 1) + ". " + stepSummary(s),
                    b[0] + 4,
                    b[1] + 2,
                    sel ? 0xFFFFFFFF : RpTheme.TEXT_PRIMARY,
                    true);
        }
        g.drawString(font, "（共 " + seqSteps.size() + " 步，点击步骤编辑）", x, y - 10, RpTheme.TEXT_DIM);
    }

    private String stepSummary(JsonObject s) {
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

    private String currentSelId() {
        return switch (tab) {
            case TAB_PROFESSION -> selProfId;
            case TAB_FACTION -> selFactionId;
            default -> selSelId;
        };
    }

    private void renderSettings(GuiGraphics g, int mouseX, int mouseY) {
        for (int i = 0; i < 6; i++) {
            int[] b = rowBounds.get(7 + i);
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
                    b[1] + 4,
                    on ? RpTheme.CYAN : RpTheme.TEXT_PRIMARY,
                    true);
            g.drawString(
                    font, Component.translatable(SETTING_DESCS[i]).getString(), b[0] + 10, b[1] + 16, RpTheme.TEXT_DIM);
            drawSwitch(g, b[2] - 60, b[1] + 13, on);
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
            int[] b = rowBounds.get(6 + i);
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
            int[] b = rowBounds.get(6 + i);
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

    private static String str(JsonObject o, String key, String def) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
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
