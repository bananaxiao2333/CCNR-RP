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
 * 管理面板「自定义设定」页签：全局变量的 CRUD + 取值预览 + 两种粒度的预设一键切换。
 *
 * <p>一段式布局：左侧列表随子页签切换（变量 / 预设方案），右侧为编辑器；子页签「变量 / 预设值 / 方案」
 * 分三段承载全部字段——变量（id/名称/类型/说明/当前值）、该变量的预设值（点击即切换取值 + 增删改）、
 * 整套预设方案（点击即套用 + 增删改）。之所以分三段而不是纵向堆叠：面板最小高度只有 340px，
 * 堆叠会溢出可视区，而分段后每段都在一屏内可读（docs/01 §10.1）。
 *
 * <p>写入一律走 {@code ManagerCrudC2S(kind="var"|"scheme")} → 服务端全量校验 + 落盘 + 权威回显
 * （{@code CharacterListS2C} 的 {@code variables}/{@code schemes}），客户端不维护第二份真相；
 * 命令 {@code /rp var} 与面板共用服务端同一条写入路径。
 */
public final class RpVariablesTab {

    private final RpAdminScreen screen;
    private int px1, py1, px2, py2;
    private int listX1, listX2, listY1, listY2;
    private int scroll = 0;
    private static final int ROW_H = 20;
    private static final int HDR = 13;

    /** 子页签：0=变量 1=预设值 2=方案。 */
    private int sub = 0;

    private static final int SUB_COUNT = 3;

    private EditBox idBox;
    private EditBox nameBox;
    private EditBox valueBox;
    private EditBox descBox;
    private EditBox pIdBox;
    private EditBox pNameBox;
    private EditBox pValueBox;
    private EditBox sIdBox;
    private EditBox sNameBox;

    private int selIndex = -1;
    /** 编辑态类型（bool/number/text）：点「类型」按钮循环切换，保存时随载荷发出。 */
    private String editType = "text";

    private String notice = "";
    private long noticeUntil = 0;
    /** 预设值/方案胶囊的滚动起点（胶囊换行铺满两行，超出用滚轮翻页）。 */
    private int chipScroll = 0;
    /** 勾选的预设值 id（可批量删除；右键单个条目也可直接删）。 */
    private final java.util.Set<String> checkedPresets = new java.util.LinkedHashSet<>();
    /** 勾选的预设方案 id。 */
    private final java.util.Set<String> checkedSchemes = new java.util.LinkedHashSet<>();

    // 删除一律走 RpAdminScreen 的共享二次确认框（docs/01 §10.2：实现只有一份）

    public RpVariablesTab(RpAdminScreen screen) {
        this.screen = screen;
    }

    /** 由 RpAdminScreen.rebuild 调用（tab==TAB_VARIABLES）。 */
    public void rebuild(int px1, int py1, int px2, int py2) {
        this.px1 = px1;
        this.py1 = py1;
        this.px2 = px2;
        this.py2 = py2;
        listX1 = px1 + 12;
        listX2 = listX1 + Math.min(240, (px2 - px1) * 34 / 100);
        listY1 = py1 + 76;
        listY2 = py2 - 60;
        int ex = editorX();
        int ew = editorW();
        int cy = contentY();
        int half = (ew - 6) / 2;
        if (idBox == null) {
            idBox = box(ex, cy + 12, ew, 64);
            nameBox = box(ex, cy + 48, ew, 64);
            valueBox = box(ex + half + 6, cy + 84, ew - half - 6, 256);
            descBox = box(ex, cy + 120, ew, 256);
            pIdBox = box(ex, cy + 84, half, 64);
            pNameBox = box(ex + half + 6, cy + 84, ew - half - 6, 64);
            pValueBox = box(ex, cy + 120, ew, 256);
            sIdBox = box(ex, cy + 84, half, 64);
            sNameBox = box(ex + half + 6, cy + 84, ew - half - 6, 64);
        } else {
            place(idBox, ex, cy + 12, ew);
            place(nameBox, ex, cy + 48, ew);
            place(valueBox, ex + half + 6, cy + 84, ew - half - 6);
            place(descBox, ex, cy + 120, ew);
            place(pIdBox, ex, cy + 84, half);
            place(pNameBox, ex + half + 6, cy + 84, ew - half - 6);
            place(pValueBox, ex, cy + 120, ew);
            place(sIdBox, ex, cy + 84, half);
            place(sNameBox, ex + half + 6, cy + 84, ew - half - 6);
        }
        // rebuild 会先 clearWidgets 清空全部控件，当前子页签用到的输入框必须重新注册
        if (sub == 0) {
            screen.addXpWidget(idBox);
            screen.addXpWidget(nameBox);
            screen.addXpWidget(valueBox);
            screen.addXpWidget(descBox);
        } else if (sub == 1) {
            screen.addXpWidget(pIdBox);
            screen.addXpWidget(pNameBox);
            screen.addXpWidget(pValueBox);
        } else {
            screen.addXpWidget(sIdBox);
            screen.addXpWidget(sNameBox);
        }
        chipScroll = 0;
        if (selIndex >= 0 && selIndex < items().size()) {
            loadEditor(selIndex);
        } else {
            clearEditor();
        }
    }

    private EditBox box(int x, int y, int w, int maxLen) {
        EditBox b = new EditBox(Minecraft.getInstance().font, x, y, w, 18, Component.literal("v"));
        b.setMaxLength(maxLen);
        return b;
    }

    private static void place(EditBox b, int x, int y, int w) {
        b.setX(x);
        b.setY(y);
        b.setWidth(w);
    }

    private int editorX() {
        return listX2 + 16;
    }

    private int editorW() {
        return px2 - editorX() - 12;
    }

    /** 子页签栏下方即内容区。 */
    private int contentY() {
        return listY1 + 26;
    }

    private int actionY() {
        return contentY() + 146;
    }

    // ---------- 数据 ----------

    private List<JsonObject> items() {
        return sub == 2 ? ClientCharacterState.schemes() : ClientCharacterState.variables();
    }

    private JsonObject selected() {
        List<JsonObject> list = items();
        return selIndex >= 0 && selIndex < list.size() ? list.get(selIndex) : null;
    }

    /** 当前选中变量的预设值（方案子页签下为空）。 */
    private List<JsonObject> presets() {
        List<JsonObject> out = new ArrayList<>();
        JsonObject v = selected();
        if (v == null || sub == 2 || !v.has("presets") || !v.get("presets").isJsonArray()) {
            return out;
        }
        for (var e : v.getAsJsonArray("presets")) {
            if (e.isJsonObject()) {
                out.add(e.getAsJsonObject());
            }
        }
        return out;
    }

    private static String str(JsonObject o, String key, String def) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private String varId() {
        JsonObject v = selected();
        return v == null ? "" : str(v, "id", "");
    }

    private void clearEditor() {
        selIndex = -1;
        editType = "text";
        if (idBox != null) {
            idBox.setValue("");
            nameBox.setValue("");
            valueBox.setValue("");
            descBox.setValue("");
            pIdBox.setValue("");
            pNameBox.setValue("");
            pValueBox.setValue("");
            sIdBox.setValue("");
            sNameBox.setValue("");
        }
    }

    private void loadEditor(int index) {
        List<JsonObject> list = items();
        if (index < 0 || index >= list.size()) {
            return;
        }
        JsonObject o = list.get(index);
        selIndex = index;
        if (sub == 2) {
            sIdBox.setValue(str(o, "id", ""));
            sNameBox.setValue(str(o, "name", ""));
            return;
        }
        idBox.setValue(str(o, "id", ""));
        nameBox.setValue(str(o, "name", ""));
        valueBox.setValue(str(o, "value", ""));
        descBox.setValue(str(o, "desc", ""));
        String t = str(o, "type", "text").toLowerCase(java.util.Locale.ROOT);
        editType = t.equals("bool") || t.equals("number") ? t : "text";
        pIdBox.setValue("");
        pNameBox.setValue("");
        pValueBox.setValue("");
    }

    private void notice(String key) {
        notice = Component.translatable(key).getString();
        noticeUntil = System.currentTimeMillis() + 2600;
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    /** 请求重新同步角色列表（服务端已落盘，回显新变量/方案）。 */
    private void refresh() {
        com.ccnrcom.rp.network.RpChannels.sendToServer(new com.ccnrcom.rp.network.RpPackets.RequestCharacterListC2S());
    }

    // ---------- 交互 ----------

    public boolean mouseClicked(int mx, int my, int button) {
        // 子页签
        int ex = editorX();
        int sw = editorW() / SUB_COUNT;
        for (int i = 0; i < SUB_COUNT; i++) {
            int x1 = ex + i * sw;
            if (mx >= x1 && mx <= x1 + sw - 4 && my >= listY1 && my <= listY1 + 18) {
                if (sub != i) {
                    // 0↔1 共用同一份列表（变量），保留选中行免去重新选；2 换列表，必须清选中
                    boolean sameList = sub != 2 && i != 2;
                    sub = i;
                    if (!sameList) {
                        selIndex = -1;
                        clearEditor();
                    }
                    chipScroll = 0;
                    checkedPresets.clear();
                    checkedSchemes.clear();
                    screen.rebuildForTab();
                }
                return true;
            }
        }
        // 类型循环按钮（变量子页签）
        if (sub == 0) {
            int half = (editorW() - 6) / 2;
            int ty = contentY() + 84;
            if (mx >= ex && mx <= ex + half && my >= ty && my <= ty + 18) {
                editType = switch (editType) {
                    case "bool" -> "number";
                    case "number" -> "text";
                    default -> "bool";};
                return true;
            }
        }
        // 预设值胶囊：勾选框=标记待删；单击主体=切换取值；右键=直接删除该条
        if (sub == 1 && !varId().isBlank()) {
            ChipHit hit = chipAt(mx, my, presets(), contentY() + 16);
            if (hit != null) {
                if (hit.onCheckbox()) {
                    toggle(checkedPresets, hit.id());
                } else if (button == 1) {
                    askDeletePresets(List.of(hit.id()));
                } else {
                    JsonObject p = new JsonObject();
                    p.addProperty("id", varId());
                    p.addProperty("presetId", hit.id());
                    screen.requestCrud("var", "presetApply", p);
                    refresh();
                }
                return true;
            }
        }
        // 方案胶囊：同上（勾选框待删 / 单击整套套用 / 右键直接删）
        if (sub == 2) {
            ChipHit hit = chipAt(mx, my, ClientCharacterState.schemes(), contentY() + 16);
            if (hit != null) {
                if (hit.onCheckbox()) {
                    toggle(checkedSchemes, hit.id());
                } else if (button == 1) {
                    askDeleteSchemes(List.of(hit.id()));
                } else {
                    JsonObject p = new JsonObject();
                    p.addProperty("id", hit.id());
                    screen.requestCrud("scheme", "apply", p);
                    refresh();
                }
                return true;
            }
        }
        // 动作按钮：新建 / 保存 / 删除
        int ay = actionY();
        int aw = 64;
        if (mx >= ex && mx <= ex + aw && my >= ay && my <= ay + 20) {
            onCreate();
            return true;
        }
        if (mx >= ex + aw + 6 && mx <= ex + aw + 6 + aw && my >= ay && my <= ay + 20) {
            onSave();
            return true;
        }
        if (mx >= ex + 2 * (aw + 6) && mx <= ex + 2 * (aw + 6) + aw && my >= ay && my <= ay + 20) {
            onDelete();
            return true;
        }
        // 左列表选行
        List<JsonObject> list = items();
        for (int i = 0; i < list.size(); i++) {
            int ry = listY1 + HDR + (i - scroll) * ROW_H;
            if (ry >= listY1 - ROW_H && ry <= listY2 && mx >= listX1 && mx <= listX2 && my >= ry && my <= ry + ROW_H) {
                if (button == 1) {
                    // 右键列表项 = 删除该条目（变量 / 方案），一律先确认
                    if (sub == 2) {
                        askDeleteSchemes(List.of(str(list.get(i), "id", "")));
                    } else {
                        askDeleteVar(str(list.get(i), "id", ""));
                    }
                    return true;
                }
                if (i != selIndex) {
                    checkedPresets.clear(); // 预设挂在具体变量下，换变量即清空勾选（防误删别的变量的预设）
                }
                loadEditor(i);
                chipScroll = 0;
                return true;
            }
        }
        return false;
    }

    /** 胶囊命中结果：命中的 id + 是否落在勾选框上（勾选=标记待删，主体=套用）。 */
    private record ChipHit(String id, boolean onCheckbox) {}

    /** 胶囊命中检测（两行换行铺开，与 render 用同一套几何）。 */
    private ChipHit chipAt(int mx, int my, List<JsonObject> chips, int top) {
        var font = Minecraft.getInstance().font;
        int width = editorW();
        int x = editorX();
        int row = 0;
        for (int i = chipScroll; i < chips.size() && row < 2; i++) {
            String label = chipLabel(chips.get(i));
            int w = chipWidth(font, label);
            if (x + w > editorX() + width && x > editorX()) {
                row++;
                x = editorX();
                if (row >= 2) {
                    break;
                }
            }
            int y1 = top + row * 22;
            if (mx >= x && mx <= x + w && my >= y1 && my <= y1 + 18) {
                String id = str(chips.get(i), "id", null);
                return id == null ? null : new ChipHit(id, mx <= x + CHECK_W);
            }
            x += w + 4;
        }
        return null;
    }

    /** 勾选框占位宽度（左侧勾选区，点击它 = 标记待删）。 */
    private static final int CHECK_W = 17;

    private static int chipWidth(net.minecraft.client.gui.Font font, String label) {
        return font.width(label) + CHECK_W + 10;
    }

    /** 勾选集合开关（不改服务端数据，只标记待删项）。 */
    private static void toggle(java.util.Set<String> set, String id) {
        if (!set.remove(id)) {
            set.add(id);
        }
    }

    private static String chipLabel(JsonObject o) {
        String id = str(o, "id", "");
        String name = str(o, "name", id);
        String label = name.equals(id) ? id : name + " (" + id + ")";
        return o.has("value") ? label + " = " + str(o, "value", "") : label + " [" + size(o, "values") + "]";
    }

    private static int size(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key).size() : 0;
    }

    public void mouseScrolled(int mouseX, int mouseY, double delta) {
        int ex = editorX();
        if (mouseX >= ex && mouseX <= ex + editorW() && mouseY >= listY1 && mouseY <= actionY()) {
            if (sub == 1 || sub == 2) {
                List<JsonObject> chips = sub == 2 ? ClientCharacterState.schemes() : presets();
                if (chips.size() > 2 && delta != 0) {
                    chipScroll = Math.max(0, Math.min(chipScroll + (delta > 0 ? -1 : 1), chips.size() - 2));
                    return;
                }
            }
        }
        if (mouseX >= listX1 && mouseX <= listX2 && mouseY >= listY1 && mouseY <= listY2) {
            int max = Math.max(0, items().size() - Math.max(1, (listY2 - listY1 - HDR) / ROW_H));
            scroll = delta > 0 ? Math.max(0, scroll - 2) : Math.min(max, scroll + 2);
        }
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return false; // 二次确认框的按键（Esc=取消）由 RpAdminScreen 统一处理
    }

    public boolean mouseClickedOverlay(int mx, int my, int button) {
        return false; // 本页签无浮层：二次确认框由 RpAdminScreen 统一接管（docs/01 §10.2）
    }

    // ---------- 编辑动作 ----------

    private void onCreate() {
        if (sub == 2) {
            if (sIdBox.getValue().trim().isBlank()) {
                notice("ccnr_rp.gui.admin.var.id_required");
                return;
            }
            screen.requestCrud("scheme", "create", schemePayload());
            clearEditor();
            refresh();
            return;
        }
        if (sub == 1) {
            onSavePreset();
            return;
        }
        if (idBox.getValue().trim().isBlank()) {
            notice("ccnr_rp.gui.admin.var.id_required");
            return;
        }
        screen.requestCrud("var", "create", baseVarPayload());
        clearEditor();
        refresh();
    }

    private void onSave() {
        if (sub == 2) {
            if (sIdBox.getValue().trim().isBlank()) {
                notice("ccnr_rp.gui.admin.var.id_required");
                return;
            }
            if (selIndex < 0) {
                notice("ccnr_rp.gui.admin.var.select_first");
                return;
            }
            screen.requestCrud("scheme", "update", schemePayload());
            refresh();
            return;
        }
        if (sub == 1) {
            onSavePreset();
            return;
        }
        if (idBox.getValue().trim().isBlank()) {
            notice("ccnr_rp.gui.admin.var.id_required");
            return;
        }
        if (selIndex < 0) {
            notice("ccnr_rp.gui.admin.var.select_first");
            return;
        }
        screen.requestCrud("var", "update", baseVarPayload());
        refresh();
    }

    /** 「删除」按钮：按当前子页签选中/勾选的目标走二次确认（不直接发包）。 */
    private void onDelete() {
        if (sub == 2) {
            if (!checkedSchemes.isEmpty()) {
                askDeleteSchemes(new ArrayList<>(checkedSchemes));
                return;
            }
            String id = sIdBox.getValue().trim();
            if (id.isBlank()) {
                notice("ccnr_rp.gui.admin.var.scheme_id_required");
                return;
            }
            askDeleteSchemes(List.of(id));
            return;
        }
        if (sub == 1) {
            if (varId().isBlank()) {
                notice("ccnr_rp.gui.admin.var.select_first");
                return;
            }
            if (!checkedPresets.isEmpty()) {
                askDeletePresets(new ArrayList<>(checkedPresets));
                return;
            }
            String pid = pIdBox.getValue().trim();
            if (pid.isBlank()) {
                notice("ccnr_rp.gui.admin.var.preset_id_required");
                return;
            }
            askDeletePresets(List.of(pid));
            return;
        }
        if (selIndex < 0) {
            notice("ccnr_rp.gui.admin.var.select_first");
            return;
        }
        askDeleteVar(str(selected(), "id", ""));
    }

    // ---------- 二次确认（删除） ----------

    /** 转交共享确认框（确认后执行，Esc/取消丢弃）；本页签不再自绘模态，避免两套语义漂移。 */
    private void askConfirm(String msg, Runnable action) {
        screen.confirmDelete(msg, action);
    }

    private void askDeleteVar(String id) {
        if (id.isBlank()) {
            return;
        }
        askConfirm(
                Component.translatable("ccnr_rp.gui.admin.var.confirm_del_var", id)
                        .getString(),
                () -> {
                    JsonObject p = new JsonObject();
                    p.addProperty("id", id);
                    screen.requestCrud("var", "delete", p);
                    clearEditor();
                    refresh();
                });
    }

    private void askDeletePresets(List<String> ids) {
        if (ids.isEmpty() || varId().isBlank()) {
            return;
        }
        String vid = varId();
        String msg = ids.size() == 1
                ? Component.translatable("ccnr_rp.gui.admin.var.confirm_del_preset", ids.get(0), vid)
                        .getString()
                : Component.translatable("ccnr_rp.gui.admin.var.confirm_del_presets", ids.size(), vid)
                        .getString();
        askConfirm(msg, () -> removePresets(ids));
    }

    private void askDeleteSchemes(List<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        String msg = ids.size() == 1
                ? Component.translatable("ccnr_rp.gui.admin.var.confirm_del_scheme", ids.get(0))
                        .getString()
                : Component.translatable("ccnr_rp.gui.admin.var.confirm_del_schemes", ids.size())
                        .getString();
        askConfirm(msg, () -> {
            removeSchemes(ids);
            clearEditor();
        });
    }

    /** 提交预设值删除（单条或勾选批量；服务端一次落盘）。 */
    private void removePresets(List<String> ids) {
        JsonObject p = new JsonObject();
        p.addProperty("id", varId());
        if (ids.size() == 1) {
            p.addProperty("presetId", ids.get(0));
        } else {
            JsonArray arr = new JsonArray();
            ids.forEach(arr::add);
            p.add("presetIds", arr);
        }
        screen.requestCrud("var", "presetRemove", p);
        checkedPresets.removeAll(ids);
        refresh();
    }

    /** 提交方案删除（单条或勾选批量；服务端一次落盘）。 */
    private void removeSchemes(List<String> ids) {
        JsonObject p = new JsonObject();
        if (ids.size() == 1) {
            p.addProperty("id", ids.get(0));
        } else {
            JsonArray arr = new JsonArray();
            ids.forEach(arr::add);
            p.add("ids", arr);
        }
        screen.requestCrud("scheme", "delete", p);
        checkedSchemes.removeAll(ids);
        refresh();
    }

    /** 预设值保存：整条变量覆盖（presets 全量重建），因此先合并既有预设再提交。 */
    private void onSavePreset() {
        if (varId().isBlank()) {
            notice("ccnr_rp.gui.admin.var.select_first");
            return;
        }
        String pid = pIdBox.getValue().trim();
        if (pid.isBlank()) {
            notice("ccnr_rp.gui.admin.var.preset_id_required");
            return;
        }
        JsonArray presets = new JsonArray();
        boolean replaced = false;
        for (JsonObject p : presets()) {
            if (pid.equals(str(p, "id", ""))) {
                presets.add(presetJson(pid));
                replaced = true;
            } else {
                presets.add(p);
            }
        }
        if (!replaced) {
            presets.add(presetJson(pid));
        }
        JsonObject payload = baseVarPayload();
        payload.add("presets", presets);
        screen.requestCrud("var", "update", payload);
        refresh();
    }

    private JsonObject presetJson(String pid) {
        JsonObject o = new JsonObject();
        o.addProperty("id", pid);
        String name = pNameBox.getValue().trim();
        o.addProperty("name", name.isBlank() ? pid : name);
        o.addProperty("value", pValueBox.getValue());
        return o;
    }

    /** 变量全量载荷（id/type/name/desc/value/presets）：保留既有预设，避免整条覆盖时丢失。 */
    private JsonObject baseVarPayload() {
        JsonObject v = selected();
        JsonObject p = new JsonObject();
        p.addProperty("id", idBox.getValue().trim());
        p.addProperty("type", editType);
        p.addProperty("name", nameBox.getValue().trim());
        p.addProperty("desc", descBox.getValue().trim());
        p.addProperty("value", valueBox.getValue());
        JsonArray presets = new JsonArray();
        if (v != null && v.has("presets") && v.get("presets").isJsonArray()) {
            for (var e : v.getAsJsonArray("presets")) {
                if (e.isJsonObject()) {
                    presets.add(e.getAsJsonObject());
                }
            }
        }
        p.add("presets", presets);
        return p;
    }

    /** 方案载荷：values = 当前表单里该方案的取值表（方案子页签下不做逐项编辑，快照用命令或「保存快照」按钮）。 */
    private JsonObject schemePayload() {
        JsonObject p = new JsonObject();
        p.addProperty("id", sIdBox.getValue().trim());
        p.addProperty("name", sNameBox.getValue().trim());
        JsonObject existing = selected();
        p.add(
                "values",
                existing != null
                                && existing.has("values")
                                && existing.get("values").isJsonObject()
                        ? existing.getAsJsonObject("values").deepCopy()
                        : new JsonObject());
        return p;
    }

    // ---------- 渲染 ----------

    public void render(GuiGraphics g, int mx, int my) {
        var font = Minecraft.getInstance().font;
        List<JsonObject> list = items();
        RpTheme.listPanel(g, listX1 - 2, listY1 - 4, listX2 + 2, listY2 + 2);
        g.drawString(
                font,
                tr(sub == 2 ? "ccnr_rp.gui.admin.var.scheme_list" : "ccnr_rp.gui.admin.var.list") + " (" + list.size()
                        + ")",
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
            boolean sel = i == selIndex;
            if (sel) {
                RpTheme.selectedBar(g, listX1, ry, listX2, ry + ROW_H, 3f);
            } else {
                RpTheme.listRow(g, listX1, ry, listX2, ry + ROW_H, i, mx, my);
            }
            g.drawString(
                    font,
                    clip(font, rowLabel(list.get(i)), (listX2 - listX1) - 10),
                    listX1 + 4,
                    ry + 5,
                    sel ? RpTheme.ACCENT_TEXT : RpTheme.TEXT_PRIMARY);
        }
        g.disableScissor();

        drawSubTabs(g, mx, my);
        int ex = editorX();
        int cy = contentY();
        if (sub == 0) {
            drawVarEditor(g, ex, cy, mx, my);
        } else if (sub == 1) {
            drawPresetEditor(g, ex, cy, mx, my);
        } else {
            drawSchemeEditor(g, ex, cy, mx, my);
        }
        if (System.currentTimeMillis() < noticeUntil) {
            g.drawCenteredString(font, Component.literal(notice), (px1 + px2) / 2, py2 - 24, RpTheme.TEXT_SECONDARY);
        }
    }

    public void renderOverlay(GuiGraphics g, int mx, int my) {}

    private static String rowLabel(JsonObject o) {
        String id = str(o, "id", "");
        String name = str(o, "name", id);
        String label = name.equals(id) ? id : name + " (" + id + ")";
        return o.has("value") ? label + " = " + str(o, "value", "") : label + " [" + size(o, "values") + "]";
    }

    private void drawSubTabs(GuiGraphics g, int mx, int my) {
        var font = Minecraft.getInstance().font;
        int ex = editorX();
        int sw = editorW() / SUB_COUNT;
        String[] keys = {
            "ccnr_rp.gui.admin.var.sub.vars", "ccnr_rp.gui.admin.var.sub.presets", "ccnr_rp.gui.admin.var.sub.schemes"
        };
        for (int i = 0; i < SUB_COUNT; i++) {
            int x1 = ex + i * sw;
            int x2 = x1 + sw - 4;
            boolean on = sub == i;
            boolean hov = !on && mx >= x1 && mx <= x2 && my >= listY1 && my <= listY1 + 18;
            RpButton.draw(
                    g, x1, listY1, x2, listY1 + 18, tr(keys[i]), on ? RpTheme.ACCENT : RpTheme.PANEL_BORDER, on, hov);
        }
    }

    private void drawVarEditor(GuiGraphics g, int ex, int cy, int mx, int my) {
        var font = Minecraft.getInstance().font;
        int ew = editorW();
        int half = (ew - 6) / 2;
        label(g, "ccnr_rp.gui.admin.var.id", ex, cy);
        label(g, "ccnr_rp.gui.admin.var.name", ex, cy + 36);
        label(g, "ccnr_rp.gui.admin.var.type", ex, cy + 72);
        label(g, "ccnr_rp.gui.admin.var.value", ex + half + 6, cy + 72);
        label(g, "ccnr_rp.gui.admin.var.desc", ex, cy + 108);
        // 类型循环按钮：点一次换一种（bool → number → text）
        int ty = cy + 84;
        boolean thov = mx >= ex && mx <= ex + half && my >= ty && my <= ty + 18;
        RpButton.draw(
                g,
                ex,
                ty,
                ex + half,
                ty + 18,
                editType.toUpperCase(java.util.Locale.ROOT),
                RpTheme.PANEL_BORDER_BRIGHT,
                false,
                thov);
        inset(g, ex, cy + 12, ex + ew, cy + 30);
        inset(g, ex, cy + 48, ex + ew, cy + 66);
        inset(g, ex + half + 6, ty, ex + ew, ty + 18);
        inset(g, ex, cy + 120, ex + ew, cy + 138);
        drawActions(
                g, mx, my, "ccnr_rp.gui.admin.var.add", "ccnr_rp.gui.admin.var.update", "ccnr_rp.gui.admin.var.remove");
        g.drawString(font, tr("ccnr_rp.gui.admin.var.hint"), ex, py2 - 52, RpTheme.TEXT_DIM);
        g.drawString(font, tr("ccnr_rp.gui.admin.var.list_hint"), ex, py2 - 40, RpTheme.TEXT_DIM);
    }

    private void drawPresetEditor(GuiGraphics g, int ex, int cy, int mx, int my) {
        var font = Minecraft.getInstance().font;
        int ew = editorW();
        int half = (ew - 6) / 2;
        String v = varId();
        g.drawString(
                font,
                v.isBlank() ? tr("ccnr_rp.gui.admin.var.select_first") : tr("ccnr_rp.gui.admin.var.preset_target") + v,
                ex,
                cy,
                v.isBlank() ? RpTheme.TEXT_DIM : RpTheme.TEXT_SECONDARY);
        drawChips(g, ex, cy + 16, presets(), mx, my, v.isBlank());
        label(g, "ccnr_rp.gui.admin.var.preset_id", ex, cy + 72);
        label(g, "ccnr_rp.gui.admin.var.preset_name", ex + half + 6, cy + 72);
        label(g, "ccnr_rp.gui.admin.var.preset_value", ex, cy + 108);
        inset(g, ex, cy + 84, ex + half, cy + 102);
        inset(g, ex + half + 6, cy + 84, ex + ew, cy + 102);
        inset(g, ex, cy + 120, ex + ew, cy + 138);
        drawActions(
                g,
                mx,
                my,
                "ccnr_rp.gui.admin.var.preset_add",
                "ccnr_rp.gui.admin.var.preset_save",
                "ccnr_rp.gui.admin.var.preset_remove");
        g.drawString(font, tr("ccnr_rp.gui.admin.var.preset_hint"), ex, py2 - 40, RpTheme.TEXT_DIM);
    }

    private void drawSchemeEditor(GuiGraphics g, int ex, int cy, int mx, int my) {
        var font = Minecraft.getInstance().font;
        int ew = editorW();
        int half = (ew - 6) / 2;
        g.drawString(font, tr("ccnr_rp.gui.admin.var.scheme_hint"), ex, cy, RpTheme.TEXT_SECONDARY);
        drawChips(g, ex, cy + 16, ClientCharacterState.schemes(), mx, my, false);
        label(g, "ccnr_rp.gui.admin.var.scheme_id", ex, cy + 72);
        label(g, "ccnr_rp.gui.admin.var.scheme_name", ex + half + 6, cy + 72);
        inset(g, ex, cy + 84, ex + half, cy + 102);
        inset(g, ex + half + 6, cy + 84, ex + ew, cy + 102);
        drawActions(
                g,
                mx,
                my,
                "ccnr_rp.gui.admin.var.scheme_add",
                "ccnr_rp.gui.admin.var.scheme_update",
                "ccnr_rp.gui.admin.var.scheme_remove");
        g.drawString(font, tr("ccnr_rp.gui.admin.var.scheme_hint2"), ex, py2 - 40, RpTheme.TEXT_DIM);
    }

    /** 预设值/方案胶囊：两行换行铺开，点击即套用。 */
    private void drawChips(GuiGraphics g, int x, int top, List<JsonObject> chips, int mx, int my, boolean disabled) {
        var font = Minecraft.getInstance().font;
        int width = editorW();
        if (chips.isEmpty()) {
            g.drawString(font, tr("ccnr_rp.gui.admin.var.chips_empty"), x, top + 4, RpTheme.TEXT_DIM);
            return;
        }
        java.util.Set<String> checked = sub == 2 ? checkedSchemes : checkedPresets;
        int cx = x;
        int row = 0;
        for (int i = chipScroll; i < chips.size() && row < 2; i++) {
            String label = chipLabel(chips.get(i));
            int w = chipWidth(font, label);
            if (cx + w > x + width && cx > x) {
                row++;
                cx = x;
                if (row >= 2) {
                    break;
                }
            }
            int y1 = top + row * 22;
            String id = str(chips.get(i), "id", "");
            boolean on = checked.contains(id);
            boolean hov = !disabled && mx >= cx && mx <= cx + w && my >= y1 && my <= y1 + 18;
            boolean hovBox = hov && mx <= cx + CHECK_W;
            if (hov && !hovBox) {
                RpButton.draw(g, cx, y1, cx + w, y1 + 18, label, RpTheme.ACCENT, true, true);
            } else {
                RpRoundRect.outlined(
                        g,
                        cx,
                        y1,
                        cx + w,
                        y1 + 18,
                        6f,
                        on ? RpTheme.ACCENT : RpTheme.PANEL_BORDER,
                        on ? RpTheme.SURFACE_CONTROL_ON : RpTheme.SURFACE_CONTROL);
                g.drawString(
                        font, label, cx + CHECK_W + 5, y1 + 5, disabled ? RpTheme.TEXT_DISABLED : RpTheme.TEXT_PRIMARY);
            }
            drawCheckbox(g, cx + 5, y1 + 4, on, hovBox);
            cx += w + 4;
        }
        if (chips.size() > 2) {
            g.drawString(
                    font,
                    "‹ " + (chipScroll + 1) + "/" + chips.size() + " ›",
                    x + width - font.width("‹ " + (chipScroll + 1) + "/" + chips.size() + " ›"),
                    top + 46,
                    RpTheme.TEXT_DIM);
        }
    }

    /** 胶囊左侧的勾选框（11×11）：勾选=标记待删，点它不触发套用。 */
    private void drawCheckbox(GuiGraphics g, int x, int y, boolean on, boolean hovered) {
        int border = on ? RpTheme.ACCENT : hovered ? RpTheme.PANEL_BORDER_BRIGHT : RpTheme.BORDER_DIM;
        RpRoundRect.outlined(g, x, y, x + 11, y + 11, 3f, border, on ? RpTheme.ACCENT_FILL : RpTheme.SURFACE_INSET);
        if (on) {
            int ink = RpTheme.ACCENT_TEXT;
            g.fill(x + 3, y + 5, x + 4, y + 8, ink);
            g.fill(x + 4, y + 6, x + 5, y + 9, ink);
            g.fill(x + 5, y + 5, x + 6, y + 8, ink);
            g.fill(x + 6, y + 4, x + 8, y + 7, ink);
            g.fill(x + 7, y + 3, x + 9, y + 6, ink);
        }
    }

    private void drawActions(GuiGraphics g, int mx, int my, String addKey, String saveKey, String delKey) {
        int ex = editorX();
        int ay = actionY();
        int aw = 64;
        RpButton.draw(g, ex, ay, ex + aw, ay + 20, tr(addKey), RpTheme.CYAN_DIM, true, hit(mx, my, ex, ay, aw));
        RpButton.draw(
                g,
                ex + aw + 6,
                ay,
                ex + 2 * aw + 6,
                ay + 20,
                tr(saveKey),
                RpTheme.PANEL_BORDER_BRIGHT,
                false,
                hit(mx, my, ex + aw + 6, ay, aw));
        RpButton.draw(
                g,
                ex + 2 * (aw + 6),
                ay,
                ex + 3 * aw + 12,
                ay + 20,
                tr(delKey),
                RpTheme.RED_DIM,
                false,
                hit(mx, my, ex + 2 * (aw + 6), ay, aw));
    }

    private static boolean hit(int mx, int my, int x, int y, int w) {
        return mx >= x && mx <= x + w && my >= y && my <= y + 20;
    }

    private void label(GuiGraphics g, String key, int x, int y) {
        g.drawString(Minecraft.getInstance().font, tr(key), x, y, RpTheme.TEXT_SECONDARY);
    }

    private void inset(GuiGraphics g, int x1, int y1, int x2, int y2) {
        g.fill(x1 - 1, y1 - 1, x2 + 1, y2 + 1, RpTheme.SURFACE_INSET);
    }

    /** 按像素宽度裁剪文本（共享入口，算法见 RpTheme.clip）。 */
    private static String clip(net.minecraft.client.gui.Font font, String s, int maxW) {
        return RpTheme.clip(font, s, maxW);
    }
}
