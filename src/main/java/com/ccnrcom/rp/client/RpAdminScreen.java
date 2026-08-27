/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.ccnrcom.rp.network.RpChannels;
import com.ccnrcom.rp.network.RpPackets;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
    private static final int TAB_LIMITS = 6;
    private static final int TAB_XP = 7;
    private static final int TAB_RELATION = 8;

    private int tab = TAB_SETTINGS;
    /** 经验规则页签（经验系统 v3）：自包含编辑器；首次使用才构造（避免构造期 this 逃逸）。 */
    private RpRulesTab rulesTab;
    /** 关系管理页签（关系系统）：规则列表 + 编辑器 + 测定图入口；与经验规则页签并列。 */
    private RpRelationTab relationTab;
    // 页签栏横向滚动（过窄时可滚动，滚动条可拖拽）
    private int tabScroll = 0;
    private int maxTabScroll = 0;
    private boolean tabDrag = false;
    private int tabDragStartX = 0;
    private int tabDragStartScroll = 0;
    private int tabThumbX1 = 0;
    private int tabThumbW = 0;
    private static final int TAB_SB_Y = 64; // 滚动条轨道 y（页签栏下方）
    private static final int TAB_SB_H = 4;

    private RpRulesTab rulesTab() {
        if (rulesTab == null) {
            rulesTab = new RpRulesTab(this);
        }
        return rulesTab;
    }

    private RpRelationTab relationTab() {
        if (relationTab == null) {
            relationTab = new RpRelationTab(this);
        }
        return relationTab;
    }

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
    private String selLimitId = "";
    private int limitTypeIdx = 0;
    private int factionIdx = 0;
    private int iconIdx = 0;
    private int tierIdx = 1;
    private EditBox unlockLevelBox;
    /** serverconfig 程序化设定：key → 数值输入框（设定标签）。 */
    private final java.util.Map<String, net.minecraft.client.gui.components.EditBox> cfgBoxes =
            new java.util.HashMap<>();
    /** 设定标签（serverconfig）滚动偏移。 */
    private int settingsScroll = 0;

    // 部署点编辑（P9 阵营出生点；职业复活点复用同一弹窗）：可编辑坐标列表 + 分布规则（弹窗管理）
    private final List<double[]> spawnPts = new ArrayList<>();
    private final List<String> spawnDims = new ArrayList<>();
    private String spawnRule = "SPREAD";
    private final List<int[]> spawnRowBounds = new ArrayList<>();
    private final List<String> spawnRowTexts = new ArrayList<>();
    private boolean spawnModalOpen = false;
    /** 部署点弹窗目标：kind = faction|profession；targetId = 目标 id。 */
    private String spawnModalKind = "faction";

    private String spawnModalTarget = "";
    private int spX1, spY1, spX2, spY2;
    private int spRuleX1, spRuleY1, spRuleX2, spRuleY2;
    private int spAddX1, spAddY1, spAddX2, spAddY2;
    private int spSaveX1, spSaveY1, spSaveX2, spSaveY2;
    private int spCancelX1, spCancelY1, spCancelX2, spCancelY2;
    private final List<int[]> spRemoveBounds = new ArrayList<>();
    /** 部署点弹窗每行「传送」按钮矩形（渲染与点击共用）。 */
    private final List<int[]> spTeleportBounds = new ArrayList<>();

    // 无线电编辑器（阵营/职业共用弹窗）：speaker + 多句 text/wait 列表（入场动画播完 action bar 打字机播放）
    private boolean radioModalOpen = false;
    private String radioModalKind = "faction"; // faction|profession
    private String radioModalTarget = "";
    private String radioSpeaker = "指挥官";
    private final List<String> radioLines = new ArrayList<>(); // 每句文本
    private final List<String> radioWaits = new ArrayList<>(); // 每句停留秒数
    private boolean radioDisabled = false; // 职业：禁用无线电开关
    private int radioScroll = 0;
    private int rdX1, rdY1, rdX2, rdY2;
    private int rdAddX1, rdAddY1, rdAddX2, rdAddY2;
    private int rdSaveX1, rdSaveY1, rdSaveX2, rdSaveY2;
    private int rdCancelX1, rdCancelY1, rdCancelX2, rdCancelY2;
    private int rdToggleX1, rdToggleY1, rdToggleX2, rdToggleY2;
    private final List<int[]> rdDelBounds = new ArrayList<>();
    private EditBox radioSpeakerBox;
    private final List<Object[]> radioTextEdits = new ArrayList<>();
    /** 行内输入框已建的可见行范围（off, maxVis）；可见范围变化时重建，稳定复用防止焦点/输入丢失。 */
    private int radioEditStart = -1;

    private int radioEditCount = 0;
    /** 句子行拖拽调序：正在拖拽的行索引（-1=未拖拽）。 */
    private int radioDragIdx = -1;
    /** 句子列表布局（渲染时更新，供拖拽调序命中计算）。 */
    private int radioListTop = 0;

    private int radioMaxVis = 0;
    private int radioOff = 0;
    private static final int RADIO_ROW_H = 26;
    /** 句子行拖拽手柄命中区（x1,y1,x2,y2,idx）。 */
    private final List<int[]> rdHandleBounds = new ArrayList<>();

    // 行为序列编辑器（流程编辑器，P1.4）：弹窗管理 WAIT/WAVE/COMMAND/FORCE_PICK 步骤（仿出生点弹窗）
    private boolean seqModalOpen = false;
    private String seqModalTitle = "";
    private final List<JsonObject> seqSteps = new ArrayList<>();
    private int stepSel = -1;
    private int seqScroll = 0;
    private JsonArray editedSequence = null; // 弹窗保存后的序列（主表单保存时优先写入 "sequence"）
    private final java.util.Map<String, EditBox> seqBoxes = new java.util.LinkedHashMap<>();
    private int sqX1, sqY1, sqX2, sqY2;
    private int sqListY1, sqListY2;
    private int sqFieldX1, sqFieldY1, sqFieldX2, sqFieldY2;
    private int sqRuleX1, sqRuleY1, sqRuleX2, sqRuleY2;
    private int sqAddX1, sqAddY1, sqAddX2, sqAddY2;
    private int sqSaveX1, sqSaveY1, sqSaveX2, sqSaveY2;
    private int sqCancelX1, sqCancelY1, sqCancelX2, sqCancelY2;
    private final List<int[]> sqStepBounds = new ArrayList<>();
    private final List<int[]> sqUpBounds = new ArrayList<>();
    private final List<int[]> sqDownBounds = new ArrayList<>();
    private final List<int[]> sqDelBounds = new ArrayList<>();

    private EditBox idBox;
    private EditBox nameBox;
    private EditBox colorBox;
    private EditBox descBox;
    private EditBox musicBox;
    /** 阵营 CMDCam 出场场景名（可选）。 */
    private EditBox camSceneBox;

    private EditBox musicUploadBox;
    /** 音乐补全提示：当前过滤列表 / 选中索引 / 命中矩形（渲染时重建）。 */
    private List<String> musicSugItems = new ArrayList<>();

    private int musicSugIdx = -1;
    private String lastMusicQuery = null;
    private final List<int[]> musicSugBounds = new ArrayList<>();
    /** CMDCam 场景补全提示（阵营/职业/刷新波场景输入框共用）：当前过滤列表 / 选中索引 / 命中矩形 / 所属输入框。 */
    private List<String> camSugItems = new ArrayList<>();

    private int camSugIdx = -1;
    private String lastCamQuery = null;
    private final List<int[]> camSugBounds = new ArrayList<>();
    private EditBox camSugBox;
    /** 限制目标补全（限制页「目标」输入框）：按当前类型补全阵营/职业 id；当前过滤列表 / 选中索引 / 命中矩形。 */
    private List<String> limitTargetSugItems = new ArrayList<>();

    private int limitTargetSugIdx = -1;
    private String lastLimitTargetQuery = null;
    private final List<int[]> limitTargetSugBounds = new ArrayList<>();
    /** 通用 id 补全（刷新波职业/阵营/维度、设置页职业、序列弹窗波/职业/阵营）：数据源枚举 + 激活输入框 + 状态。 */
    private enum SugSource {
        NONE,
        PROFESSION,
        FACTION,
        WAVE,
        DIMENSION
    }

    private SugSource idSugSource = SugSource.NONE;
    private EditBox idSugBox = null;
    private List<String> idSugItems = new ArrayList<>();

    private int idSugIdx = -1;
    private String lastIdSugQuery = null;
    private final List<int[]> idSugBounds = new ArrayList<>();
    private EditBox profileBox;
    private EditBox fld2Box;
    private EditBox fld3Box;
    private EditBox fld4Box;
    /** 输入框上方用途标签（x, y, key/raw text），rebuild 时清空重填。 */
    private final List<Object[]> fieldLabels = new ArrayList<>();

    private boolean evState = true;
    private int modeIdx = 0;
    private int deployIdx = 0;
    // 影响确认弹窗
    private boolean impactOpen = false;
    private String impactTitle = "";
    private final List<String> impactLines = new ArrayList<>();
    private int mX1, mY1, mX2, mY2;
    private int okX1, okY1, okX2, okY2;
    private int noX1, noY1, noX2, noY2;
    private String pendingKind = "";
    private String pendingAction = "";
    private JsonObject pendingPayload;

    private static final String[] ICONS = {"hex", "shield", "claw", "storm", "eye", "target", "cross", "gear"};

    /** 图标可选值 = 内置向量图形 + 服务器素材库图标（img: 中央下发），去重保序。 */
    private static java.util.List<String> iconOptions() {
        java.util.List<String> out = new java.util.ArrayList<>(java.util.List.of(ICONS));
        for (String s : ClientAssetCache.iconNames()) {
            if (!out.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    private static final String[] TABS = {
        "ccnr_rp.gui.admin.tab.settings",
        "ccnr_rp.gui.admin.tab.profession",
        "ccnr_rp.gui.admin.tab.faction",
        "ccnr_rp.gui.admin.tab.event",
        "ccnr_rp.gui.admin.tab.phase",
        "ccnr_rp.gui.admin.tab.wave",
        "ccnr_rp.gui.admin.tab.limits",
        "ccnr_rp.gui.admin.tab.xp",
        "ccnr_rp.gui.admin.tab.relation"
    };

    /** 限制类型（部署人数上限规则）：GLOBAL=通用角色上限（职业无专属时兜底）/ FACTION=阵营上限 / PROFESSION=职业上限。 */
    private static final String[] LIMIT_TYPES = {"GLOBAL", "FACTION", "PROFESSION"};

    /** 打开前的上层面板（K 面板）；关闭时返回。 */
    private final Screen parent;

    public RpAdminScreen() {
        super(Component.translatable("ccnr_rp.gui.admin.title"));
        this.parent = Minecraft.getInstance().screen;
    }

    public static void refreshIfOpen() {
        if (open != null) {
            open.rebuild();
        }
    }

    /** 经验规则页签向本屏幕注册输入框控件（addRenderableWidget 为 protected）。 */
    public void addXpWidget(net.minecraft.client.gui.components.AbstractWidget widget) {
        addRenderableWidget(widget);
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
        // 页签栏几何是所有页签共用的，必须先填充（render/鼠标分发都依赖 rowBounds 前 8 项）。
        // 过窄时横向滚动：保持页签可读宽度，超出的部分通过 tabScroll 偏移 + 底部滚动条查看。
        int avail = px2 - px1 - 24; // 页签可用宽（左右各 12）
        int tabW = Math.min(84, Math.max(64, avail / TABS.length));
        int totalTab = TABS.length * (tabW + 4) - 4;
        maxTabScroll = Math.max(0, totalTab - avail);
        tabScroll = Math.max(0, Math.min(tabScroll, maxTabScroll));
        int tx = px1 + 12;
        for (int i = 0; i < TABS.length; i++) {
            int x = tx + i * (tabW + 4) - tabScroll;
            rowBounds.add(new int[] {x, py1 + 42, x + tabW, py1 + 62});
        }
        if (tab == TAB_XP) {
            rulesTab().rebuild(px1, py1, px2, py2);
            return;
        }
        if (tab == TAB_RELATION) {
            relationTab().rebuild(px1, py1, px2, py2);
            return;
        }
        if (tab == TAB_SETTINGS) {
            // 开关行（settings.json 键，程序化生成）：按滚动偏移生成可见的开关行（数值行由输入框承载，不进 rowBounds）
            int swCount = ClientCharacterState.settingKeys().size();
            int total = settingsRows();
            int maxVisible = Math.max(1, (py2 - 70 - settingsYTop()) / 30);
            settingsScroll = Math.max(0, Math.min(settingsScroll, Math.max(0, total - maxVisible)));
            int y = settingsYTop();
            for (int i = settingsScroll; i < total; i++) {
                if (y + 22 > py2 - 70) {
                    break;
                }
                if (i < swCount) {
                    // 5 元素：x1,y1,x2,y2,absIdx（仅开关行加入点击区；字符串/数值行由输入框承载）
                    String sk = ClientCharacterState.settingKeys().get(i);
                    if ("bool".equals(com.ccnrcom.rp.config.ManagerSettings.type(sk))) {
                        rowBounds.add(new int[] {px1 + 12, y, settingsRight(), y + 22, i});
                    }
                }
                y += 30;
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
            case TAB_LIMITS -> ClientCharacterState.deployLimits();
            default -> List.of();
        };
    }

    private int rowHeight() {
        return switch (tab) {
            case TAB_PROFESSION -> 20;
            case TAB_FACTION -> 22;
            case TAB_EVENT, TAB_PHASE, TAB_WAVE -> 20;
            case TAB_LIMITS -> 20;
            default -> 0;
        };
    }

    private void buildForm() {
        switch (tab) {
            case TAB_SETTINGS -> buildSettingsForm();
            case TAB_PROFESSION -> buildProfessionForm();
            case TAB_FACTION -> buildFactionForm();
            case TAB_EVENT -> buildEventForm();
            case TAB_PHASE -> buildPhaseForm();
            case TAB_WAVE -> buildWaveForm();
            case TAB_LIMITS -> buildLimitsForm();
            default -> {}
        }
    }

    /** 「设定」标签程序化表单：开关（bool）/ 字符串设置项 + serverconfig 数值输入框，统一滚动 + 一个保存按钮。 */
    private void buildSettingsForm() {
        cfgBoxes.clear();
        int x = px1 + 12;
        int w = settingsRight() - x;
        int yMax = py2 - 70;
        JsonObject cfg = ClientCharacterState.serverConfig();
        java.util.List<String> setKeys = ClientCharacterState.settingKeys();
        int swCount = setKeys.size();
        java.util.List<String> keys = com.ccnrcom.rp.config.CCNRRPConfig.keys();
        int total = settingsRows();
        int maxVisible = Math.max(1, (yMax - settingsYTop()) / 30);
        settingsScroll = Math.max(0, Math.min(settingsScroll, Math.max(0, total - maxVisible)));
        int y = settingsYTop();
        for (int i = settingsScroll; i < total; i++) {
            if (y + 22 > yMax) {
                break;
            }
            if (i < swCount) {
                String key = setKeys.get(i);
                if ("string".equals(com.ccnrcom.rp.config.ManagerSettings.type(key))) {
                    // 字符串设置项：文本输入框（标签区右侧至面板边，留滚动条间距）
                    String cur = ClientCharacterState.settingString(key, settingStringDefault(key));
                    int bw = Math.max(80, w - 126);
                    cfgBoxes.put(key, mkBox(x + 120, y + 2, bw, "", cur, false));
                }
            } else {
                String key = keys.get(i - swCount);
                String cur = cfg.has(key) ? cfg.get(key).getAsString() : "";
                int bw = Math.max(80, w - 126);
                cfgBoxes.put(key, mkBox(x + 120, y + 2, bw, "", cur, false));
            }
            y += 30;
        }
        addRenderableWidget(RpButton.primary(
                x,
                py2 - 40,
                Math.min(160, Math.max(96, w / 3)),
                20,
                Component.translatable("ccnr_rp.gui.admin.crud.save"),
                b -> saveSettingsForm()));
    }

    /** 保存「设定」标签全部输入框：settings.json 字符串项 → ManagerSetC2S；serverconfig 数值 → ServerConfigSetC2S。 */
    private void saveSettingsForm() {
        for (java.util.Map.Entry<String, net.minecraft.client.gui.components.EditBox> e : cfgBoxes.entrySet()) {
            String key = e.getKey();
            String value = e.getValue().getValue();
            if (com.ccnrcom.rp.config.ManagerSettings.keys().contains(key)) {
                RpChannels.sendToServer(new RpPackets.ManagerSetC2S(key, value));
            } else {
                RpChannels.sendToServer(new RpPackets.ServerConfigSetC2S(key, value));
            }
        }
        notice = "设置已保存";
        rebuild();
    }

    private void buildEventForm() {
        int x = listX2 + 10;
        int w = px2 - 12 - x;
        int y = py1 + 76;
        JsonObject ev = selItem();
        String id = ev == null ? "" : str(ev, "id");
        boolean edit = !id.isBlank();
        // ID 仅编辑模式锁定（创建模式必须可输入，否则无法新建）
        idBox = mkBox(x, y, w, "ccnr_rp.gui.admin.field.id", id, edit);
        y += 30;
        boolean enabled = ev == null || !ev.has("enabled") || ev.get("enabled").getAsBoolean();
        evState = enabled;
        fld3Box = mkBox(x, y, w, "时长(秒,0=事件持续时间)", ev == null ? "0" : num(ev, "durationSeconds", 0), false);
        y += 30;
        // 管理快捷操作：启用开关 / 编辑行为序列 / 手动触发事件 —— 三个长按钮压成一行短按钮并排
        boolean evEnabled = enabled;
        buttonRow(
                x,
                y,
                w,
                20,
                java.util.List.of(
                        new ActButton("启用: " + (evEnabled ? "是" : "否"), 1, () -> {
                            evState = !evState;
                            rebuild();
                        }),
                        new ActButton("行为序列", 1, () -> openSequenceModal()),
                        new ActButton("触发事件", 0, () -> {
                            String evtId = idBox.getValue();
                            if (evtId.isBlank()) {
                                notice = "缺少 id";
                                return;
                            }
                            RpChannels.sendToServer(new RpPackets.AdminEventTriggerC2S(evtId));
                        })));
        y += 26;
        actionRow(x, y, w, edit);
    }

    private void buildPhaseForm() {
        int x = listX2 + 10;
        int w = px2 - 12 - x;
        int y = py1 + 76;
        JsonObject ph = selItem();
        String id = ph == null ? "" : str(ph, "id");
        boolean edit = !id.isBlank();
        // ID 仅编辑模式锁定（创建模式必须可输入，否则无法新建）
        idBox = mkBox(x, y, w, "ccnr_rp.gui.admin.field.id", id, edit);
        y += 30;
        fld2Box = mkBox(x, y, w, "顺序 order", ph == null ? "0" : num(ph, "order", 0), false);
        y += 30;
        fld3Box = mkBox(x, y, w, "时长(分钟)", ph == null ? "30" : num(ph, "durationMinutes", 30), false);
        y += 30;
        // 编辑行为序列（整行长按钮压成短按钮）
        buttonRow(x, y, w, 20, java.util.List.of(new ActButton("行为序列", 1, () -> openSequenceModal())));
        y += 26;
        actionRow(x, y, w, edit);
    }

    /** 部署人数限制编辑器：规则 = 类型（GLOBAL/FACTION/PROFESSION）+ 目标（阵营/职业 id）+ 人数上限。 */
    private void buildLimitsForm() {
        int x = listX2 + 10;
        int w = px2 - 12 - x;
        int y = py1 + 76;
        JsonObject r = selLimit();
        String id = r == null ? "" : str(r, "id");
        boolean edit = !id.isBlank();
        // 类型循环按钮 + ID（仅编辑模式锁定）
        addRenderableWidget(
                RpButton.secondary(x, y, (w - 4) / 2, 18, Component.literal("类型: " + limitTypeLabel()), b -> {
                    limitTypeIdx = (limitTypeIdx + 1) % LIMIT_TYPES.length;
                    rebuild();
                }));
        idBox = mkBox(x + (w - 4) / 2 + 4, y, (w - 4) / 2, "ccnr_rp.gui.admin.field.id", id, edit);
        y += 30;
        // 目标（阵营/职业 id；GLOBAL 留空 = 通用兜底）
        String target = r == null ? "" : str(r, "target", "");
        nameBox = mkBox(x, y, w, "目标(阵营/职业 id；GLOBAL 留空=通用)", target, false);
        y += 30;
        // 人数上限
        unlockLevelBox = mkBox(x, y, w, "人数上限 limit（0=禁止部署；不限=不配置该规则）", r == null ? "" : num(r, "limit", 0), false);
        y += 30;
        // 说明 + 清空全部限制：两个整行长按钮压成一行短按钮并排
        buttonRow(
                x,
                y,
                w,
                20,
                java.util.List.of(new ActButton("说明：0=禁止部署；在职≥上限拒绝", 1, () -> {}), new ActButton("清空全部限制", 2, () -> {
                    for (JsonObject rule : ClientCharacterState.deployLimits()) {
                        JsonObject del = payload();
                        del.addProperty("id", str(rule, "id"));
                        requestCrud("limit", "delete", del);
                    }
                    notice = "已请求清空限制";
                })));
        y += 26;
        actionRow(x, y, w, edit);
    }

    private String limitTypeLabel() {
        return switch (LIMIT_TYPES[limitTypeIdx]) {
            case "GLOBAL" -> "通用角色上限（职业未配置时的兜底）";
            case "FACTION" -> "阵营上限（该阵营在职总人数）";
            default -> "职业上限（该职业在职人数）";
        };
    }

    private JsonObject selLimit() {
        for (JsonObject r : ClientCharacterState.deployLimits()) {
            if (str(r, "id").equals(selLimitId)) {
                return r;
            }
        }
        return null;
    }

    /** 从补全显示名「名字(id)」或「id」提取原始 id（填入目标框用）。 */
    private static String sugTargetId(String label) {
        int open = label.lastIndexOf('(');
        int close = label.lastIndexOf(')');
        if (open >= 0 && close > open) {
            return label.substring(open + 1, close);
        }
        return label;
    }

    /**
     * 应用补全选中项到输入框：逗号分隔的多值框（刷新波职业/阵营 ID）追加或替换末尾词，
     * 单值框（维度/波 ID/设置职业）整体替换。空值直接填入。
     */
    private static String applyIdSug(String current, String id) {
        if (current == null || current.isBlank()) {
            return id;
        }
        if (!current.contains(",")) {
            return id; // 单值输入：整体替换
        }
        int lastComma = current.lastIndexOf(',');
        String prefix = current.substring(0, lastComma + 1).trim(); // 保留逗号分隔结构
        return prefix + id;
    }

    private void buildWaveForm() {
        int x = listX2 + 10;
        int w = px2 - 12 - x;
        int y = py1 + 76;
        JsonObject wv = selItem();
        String id = wv == null ? "" : str(wv, "id");
        boolean edit = !id.isBlank();
        // ID 仅编辑模式锁定（创建模式必须可输入，否则无法新建）
        idBox = mkBox(x, y, w, "ccnr_rp.gui.admin.field.id", id, edit);
        y += 30;
        int bw2 = (w - 4) / 2;
        addRenderableWidget(
                RpButton.secondary(x, y, bw2, 18, Component.literal("模式: " + waveModeLabel(Modes[modeIdx])), b -> {
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
        // CMDCam 出场场景（可选）：部署入场电影播完黑屏转场播放该摄像机场景（补全提示见 renderCamSceneSuggestions）
        camSceneBox =
                mkBox(x, y, w, "ccnr_rp.gui.admin.field.cam_scene", wv == null ? "" : str(wv, "cmdcamScene"), false);
        y += 30;
        // 管理快捷操作：编辑行为序列 / 手动召唤复活波 —— 两个长按钮压成一行短按钮并排
        buttonRow(
                x,
                y,
                w,
                20,
                java.util.List.of(new ActButton("行为序列", 1, () -> openSequenceModal()), new ActButton("召唤复活波", 0, () -> {
                    String wvId = idBox.getValue();
                    if (wvId.isBlank()) {
                        notice = "缺少 id";
                        return;
                    }
                    RpChannels.sendToServer(new RpPackets.AdminWaveTriggerC2S(wvId));
                })));
        y += 26;
        actionRow(x, y, w, edit);
    }

    /** 通用操作行：保存/删除/新建。 */
    private void actionRow(int x, int y, int w, boolean edit) {
        int bw3 = Math.max(60, w / 4);
        String kind = crudKind();
        java.util.function.Supplier<JsonObject> builder = this::buildPayload;
        addRenderableWidget(
                RpButton.primary(x, y, bw3, 20, Component.translatable("ccnr_rp.gui.admin.crud.save"), b -> {
                    requestCrud(kind, edit ? "update" : "create", builder.get());
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
                    requestCrud(kind, "delete", del);
                }));
        addRenderableWidget(RpButton.secondary(
                x + (bw3 + 4) * 2, y, bw3, 20, Component.translatable("ccnr_rp.gui.admin.crud.new"), b -> {
                    selProfId = "";
                    selFactionId = "";
                    selSelId = "";
                    editedSequence = null; // 新建：丢弃流程编辑缓存
                    modeIdx = 0;
                    deployIdx = 0;
                    evState = true;
                    rebuild();
                }));
    }

    /** 一行操作按钮（等宽短按钮，并排摆列）：variant 0=主操作 / 1=次级 / 2=危险。 */
    private record ActButton(String label, int variant, Runnable onPress) {}

    /** 把多个「整行堆叠」的长按钮压缩成一行等宽短按钮并排（n=1 时压成有界短按钮）。 */
    private void buttonRow(int x, int y, int w, int h, java.util.List<ActButton> buttons) {
        if (buttons == null || buttons.isEmpty()) {
            return;
        }
        int n = buttons.size();
        int bw = n == 1 ? Math.min(180, Math.max(96, w / 3)) : (w - (n - 1) * 4) / n;
        for (int i = 0; i < n; i++) {
            ActButton b = buttons.get(i);
            int bx = x + i * (bw + 4);
            RpButton btn =
                    switch (b.variant()) {
                        case 0 -> RpButton.primary(bx, y, bw, h, Component.literal(b.label()), p -> b.onPress()
                                .run());
                        case 2 -> RpButton.danger(bx, y, bw, h, Component.literal(b.label()), p -> b.onPress()
                                .run());
                        default -> RpButton.secondary(bx, y, bw, h, Component.literal(b.label()), p -> b.onPress()
                                .run());
                    };
            addRenderableWidget(btn);
        }
    }

    private String crudKind() {
        return switch (tab) {
            case TAB_PROFESSION -> "profession";
            case TAB_FACTION -> "faction";
            case TAB_EVENT -> "event";
            case TAB_PHASE -> "phase";
            case TAB_WAVE -> "wave";
            case TAB_LIMITS -> "limit";
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
                p.addProperty("unlockLevel", intOf(unlockLevelBox.getValue(), 0));
                p.addProperty("music", musicBox.getValue());
                p.addProperty("profile", profileBox.getValue());
                p.addProperty("cmdcamScene", camSceneBox == null ? "" : camSceneBox.getValue());
                yield p;
            }
            case TAB_FACTION -> {
                JsonObject p = payload();
                p.addProperty("id", idBox.getValue());
                p.addProperty("name", nameBox.getValue());
                p.addProperty("color", colorBox.getValue());
                p.addProperty("description", descBox.getValue());
                p.addProperty("icon", iconOptions().get(iconIdx));
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
                }
                addSequenceField(p, src);
                yield p;
            }
            case TAB_PHASE -> {
                JsonObject p = payload();
                p.addProperty("id", idBox.getValue());
                p.addProperty("order", parseInt(fld2Box));
                p.addProperty("durationMinutes", parseInt(fld3Box));
                JsonObject phSrc = selItem();
                addSequenceField(p, phSrc);
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
                p.addProperty("cmdcamScene", camSceneBox == null ? "" : camSceneBox.getValue());
                addSequenceField(p, src);
                yield p;
            }
            case TAB_LIMITS -> {
                JsonObject p = payload();
                p.addProperty("id", idBox.getValue());
                p.addProperty("type", LIMIT_TYPES[limitTypeIdx]);
                p.addProperty("target", nameBox == null ? "" : nameBox.getValue());
                p.addProperty("limit", unlockLevelBox == null ? 0 : intOf(unlockLevelBox.getValue(), 0));
                yield p;
            }
            default -> payload();
        };
    }

    /**
     * 行为序列写入 payload：流程编辑器弹窗保存过（editedSequence 非空）→ 用编辑结果；
     * 否则透传原条目的 sequence（保留旧配置）。
     */
    private void addSequenceField(JsonObject p, JsonObject src) {
        if (editedSequence != null) {
            p.add("sequence", editedSequence.deepCopy());
        } else if (src != null && src.has("sequence")) {
            p.add("sequence", src.getAsJsonArray("sequence"));
        }
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

    private static int intOf(String v, int def) {
        try {
            return Math.max(0, Integer.parseInt(v.trim()));
        } catch (Exception e) {
            return def;
        }
    }

    /** 召唤波模式（持久化值；展示文案走 waveModeLabel）。存活可收到 / 死亡可收到 / 皆可收到。 */
    private static final String[] Modes = {"SELF_DEPLOY", "RESURRECTION", "BOTH"};

    private static final String[] DeployTypes = {"WORLD_SPAWN", "POS"};

    /** 召唤波模式展示文案（存活人员可收到 / 死亡人员可收到 / 皆可收到）。 */
    private static String waveModeLabel(String mode) {
        return switch (mode) {
            case "SELF_DEPLOY" -> Component.translatable("ccnr_rp.gui.admin.wave.mode.self_deploy")
                    .getString();
            case "RESURRECTION" -> Component.translatable("ccnr_rp.gui.admin.wave.mode.resurrection")
                    .getString();
            case "BOTH" -> Component.translatable("ccnr_rp.gui.admin.wave.mode.both")
                    .getString();
            default -> mode;
        };
    }

    // ---------- 职业表单 ----------

    private void buildProfessionForm() {
        int x = listX2 + 10;
        int w = px2 - 12 - x;
        int y = py1 + 76;
        JsonObject prof = selProf();
        String id = prof == null ? "" : str(prof, "id");
        boolean edit = !id.isBlank();
        // ID 仅编辑模式锁定（创建模式必须可输入，否则无法新建）
        idBox = mkBox(x, y, w, "ccnr_rp.gui.admin.field.id", id, edit);
        y += 30;
        nameBox = mkBox(x, y, w, "ccnr_rp.gui.admin.field.name", prof == null ? "" : str(prof, "name"), false);
        y += 30;
        int bw2 = (w - 4) / 2;
        addRenderableWidget(RpButton.secondary(x, y, bw2, 18, Component.literal(factionCycleLabel()), b -> {
            factionIdx = (factionIdx + 1)
                    % Math.max(1, ClientCharacterState.factions().size());
            rebuild();
        }));
        unlockLevelBox = mkBox(
                x + bw2 + 4,
                y,
                bw2,
                "ccnr_rp.gui.admin.field.unlock_level",
                prof == null ? "0" : num(prof, "unlockLevel", 0),
                false);
        y += 30;
        musicBox = mkBox(x, y, w, "ccnr_rp.gui.admin.field.music", prof == null ? "" : str(prof, "music"), false);
        y += 30;
        buildMusicUploadRow(x, y, w);
        y += 30;
        profileBox = mkBox(x, y, w, "ccnr_rp.gui.admin.field.profile", prof == null ? "" : str(prof, "profile"), false);
        y += 30;
        // CMDCam 出场场景（可选）：部署入场电影播完黑屏转场播放该摄像机场景（补全提示见 renderCamSceneSuggestions）
        camSceneBox = mkBox(
                x, y, w, "ccnr_rp.gui.admin.field.cam_scene", prof == null ? "" : str(prof, "cmdcamScene"), false);
        y += 30;
        // 管理快捷操作（职业级）：无线电 / 刷给自己 / 全量保存装备 / 职业复活点 —— 4 个长按钮压成一行短按钮并排
        buttonRow(
                x,
                y,
                w,
                20,
                java.util.List.of(
                        new ActButton("无线电管理", 1, () -> openRadioModal("profession")),
                        new ActButton("刷给自己", 0, () -> {
                            if (selProfId.isBlank()) {
                                notice = "请先在左侧选择职业";
                                return;
                            }
                            RpChannels.sendToServer(new RpPackets.AdminSelfProfessionC2S(selProfId));
                        }),
                        new ActButton("保存装备", 1, () -> {
                            if (selProfId.isBlank()) {
                                notice = "请先在左侧选择职业";
                                return;
                            }
                            RpChannels.sendToServer(new RpPackets.AdminProfessionSaveFullC2S(selProfId));
                        }),
                        new ActButton("职业复活点", 1, () -> openSpawnModal("profession"))));
        y += 26;
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
                    requestCrud("profession", "delete", del);
                }));
        addRenderableWidget(RpButton.secondary(
                x + (bw3 + 4) * 2, y, bw3, 20, Component.translatable("ccnr_rp.gui.admin.crud.new"), b -> {
                    selProfId = "";
                    factionIdx = 0;
                    iconIdx = 0;
                    tierIdx = 1;
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
        p.addProperty("unlockLevel", intOf(unlockLevelBox.getValue(), 0));
        p.addProperty("music", musicBox.getValue());
        p.addProperty("profile", profileBox.getValue());
        p.addProperty("cmdcamScene", camSceneBox == null ? "" : camSceneBox.getValue());
        requestCrud("profession", edit ? "update" : "create", p);
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
        loadFactionSpawn(fac);
        if (fac != null) {
            syncFactionIconTier(fac); // 选中后每次重建表单都同步图标/等级（防刷新回退默认值）
        }
        // ID 仅编辑模式锁定（创建模式必须可输入，否则无法新建）
        idBox = mkBox(x, y, w, "ccnr_rp.gui.admin.field.id", id, edit);
        y += 30;
        nameBox = mkBox(x, y, w, "ccnr_rp.gui.admin.field.name", fac == null ? "" : str(fac, "name"), false);
        y += 30;
        colorBox = mkBox(x, y, w, "ccnr_rp.gui.admin.field.color", fac == null ? "#FFFFFF" : str(fac, "color"), false);
        y += 30;
        int bw2 = (w - 4) / 2;
        addRenderableWidget(RpButton.secondary(
                x, y, bw2, 18, Component.literal("图标: " + iconOptions().get(iconIdx)), b -> {
                    iconIdx = (iconIdx + 1) % iconOptions().size();
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
        musicBox = mkBox(x, y, w, "ccnr_rp.gui.admin.field.music", fac == null ? "" : str(fac, "music"), false);
        y += 30;
        buildMusicUploadRow(x, y, w);
        y += 30;
        // CMDCam 出场场景（可选）：部署入场电影播完黑屏转场播放该摄像机场景
        camSceneBox =
                mkBox(x, y, w, "ccnr_rp.gui.admin.field.cam_scene", fac == null ? "" : str(fac, "cmdcamScene"), false);
        y += 30;
        // 管理快捷操作（阵营级）：无线电管理 / 管理部署点 —— 两个长按钮压成一行短按钮并排
        buttonRow(
                x,
                y,
                w,
                20,
                java.util.List.of(
                        new ActButton("无线电管理", 1, () -> openRadioModal("faction")),
                        new ActButton("管理部署点", 1, () -> openSpawnModal("faction"))));
        y += 26;
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
                    requestCrud("faction", "delete", del);
                }));
        addRenderableWidget(RpButton.secondary(
                x + (bw3 + 4) * 2, y, bw3, 20, Component.translatable("ccnr_rp.gui.admin.crud.new"), b -> {
                    selFactionId = "";
                    iconIdx = 0;
                    tierIdx = 1;
                    rebuild();
                }));
        // 关系管理与关系测定图已并入独立页签（TAB_RELATION），不再放在阵营表单内
    }

    /** 从阵营 JSON 载入出生点配置到编辑状态。 */
    private void loadFactionSpawn(JsonObject fac) {
        spawnPts.clear();
        spawnDims.clear();
        spawnRowBounds.clear();
        spawnRowTexts.clear();
        spTeleportBounds.clear();
        spawnRule = "SPREAD";
        if (fac == null || !fac.has("spawn") || !fac.get("spawn").isJsonObject()) {
            return;
        }
        JsonObject sp = fac.getAsJsonObject("spawn");
        spawnRule = "SINGLE".equalsIgnoreCase(str(sp, "rule", "SPREAD")) ? "SINGLE" : "SPREAD";
        if (sp.has("points") && sp.get("points").isJsonArray()) {
            for (com.google.gson.JsonElement e : sp.getAsJsonArray("points")) {
                if (e.isJsonObject()) {
                    JsonObject o = e.getAsJsonObject();
                    spawnPts.add(new double[] {dbl(o, "x", 0), dbl(o, "y", 64), dbl(o, "z", 0)});
                    spawnDims.add(str(o, "dim", "minecraft:overworld"));
                }
            }
        }
    }

    private static double dbl(JsonObject o, String key, double def) {
        try {
            return o != null && o.has(key) ? o.get(key).getAsDouble() : def;
        } catch (Exception e) {
            return def;
        }
    }

    /** 打开部署点管理弹窗：kind=faction（阵营部署点）| profession（职业复活点），从当前选中条目载入配置到工作副本。 */
    private void openSpawnModal(String kind) {
        JsonObject target = "profession".equals(kind) ? selProf() : selFaction();
        if (target == null || str(target, "id").isBlank()) {
            notice = "请先在左侧选择" + ("profession".equals(kind) ? "职业" : "阵营");
            return;
        }
        spawnModalKind = kind;
        spawnModalTarget = str(target, "id");
        loadFactionSpawn(target);
        notice = ""; // 弹窗已打开，清掉残留的“请先选择”提示
        spawnModalOpen = true;
    }

    private String ruleLabel(String rule) {
        return "SINGLE".equals(rule) ? "集中(单点)" : "分摊(随机)";
    }

    /** 把本机玩家当前坐标 + 维度加入出生点列表（弹窗内，不触发窗体重建）。 */
    private void addCurrentPos() {
        net.minecraft.client.player.LocalPlayer p = net.minecraft.client.Minecraft.getInstance().player;
        if (p == null) {
            notice = "需以玩家身份打开";
            return;
        }
        String dim = p.level().dimension().location().toString();
        spawnPts.add(new double[] {p.getX(), p.getY(), p.getZ()});
        spawnDims.add(dim);
    }

    /** 发送部署点配置到服务端（写 faction/profession spawn 字段），成功后关闭弹窗。 */
    private void saveSpawn() {
        if (spawnModalTarget.isBlank()) {
            notice = "请先选择目标";
            return;
        }
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (int i = 0; i < spawnPts.size(); i++) {
            double[] p = spawnPts.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("x", p[0]);
            o.addProperty("y", p[1]);
            o.addProperty("z", p[2]);
            o.addProperty("dim", spawnDims.get(i));
            arr.add(o);
        }
        if ("profession".equals(spawnModalKind)) {
            RpChannels.sendToServer(new RpPackets.AdminProfessionSpawnC2S(spawnModalTarget, spawnRule, arr.toString()));
        } else {
            RpChannels.sendToServer(new RpPackets.AdminFactionSpawnC2S(spawnModalTarget, spawnRule, arr.toString()));
        }
        spawnModalOpen = false;
    }

    /** 渲染出生点管理弹窗（每帧；按钮为手动绘制，命中在 mouseClicked）。 */
    private void renderSpawnModal(GuiGraphics g, int mouseX, int mouseY) {
        // 弹窗遮罩：压暗底层界面，明确“弹窗在最上层、下层不可交互”
        g.fill(0, 0, width, height, 0xA6000000);
        int w = Math.min(560, width - 40);
        int h = Math.min(400, height - 40);
        int x1 = (width - w) / 2;
        int y1 = (height - h) / 2;
        spX1 = x1;
        spY1 = y1;
        spX2 = x1 + w;
        spY2 = y1 + h;
        RpTheme.terminalPanel(g, x1, y1, x1 + w, y1 + h, RpTheme.RADIUS_LARGE);
        g.drawString(
                font,
                ("profession".equals(spawnModalKind) ? "管理职业复活点 — " : "管理部署点 — ") + spawnModalTarget,
                x1 + 14,
                y1 + 10,
                RpTheme.CYAN,
                true);
        g.fill(x1 + 8, y1 + 26, x1 + w - 8, y1 + 27, RpTheme.CYAN_DIM);

        int cx = x1 + 14;
        int cw = x1 + w - 14;
        int cy = y1 + 38;
        int bw = Math.max(72, (cw - cx - 12) / 4);
        int border = RpTheme.PANEL_BORDER;
        int borderHover = RpTheme.PANEL_BORDER_BRIGHT;
        // 规则切换
        spRuleX1 = cx;
        spRuleY1 = cy;
        spRuleX2 = cx + bw;
        spRuleY2 = cy + 18;
        RpButton.draw(
                g,
                spRuleX1,
                spRuleY1,
                spRuleX2,
                spRuleY2,
                "规则: " + ruleLabel(spawnRule),
                inRect(mouseX, mouseY, spRuleX1, spRuleY1, spRuleX2, spRuleY2) ? borderHover : border,
                false);
        // 添加当前坐标
        spAddX1 = spRuleX2 + 4;
        spAddY1 = cy;
        spAddX2 = spAddX1 + bw;
        spAddY2 = cy + 18;
        RpButton.draw(
                g,
                spAddX1,
                spAddY1,
                spAddX2,
                spAddY2,
                "+ 添加当前坐标",
                inRect(mouseX, mouseY, spAddX1, spAddY1, spAddX2, spAddY2) ? borderHover : border,
                false);
        // 保存
        spSaveX1 = spAddX2 + 4;
        spSaveY1 = cy;
        spSaveX2 = spSaveX1 + bw;
        spSaveY2 = cy + 18;
        RpButton.draw(
                g,
                spSaveX1,
                spSaveY1,
                spSaveX2,
                spSaveY2,
                "保存",
                inRect(mouseX, mouseY, spSaveX1, spSaveY1, spSaveX2, spSaveY2) ? borderHover : border,
                true);
        // 关闭
        spCancelX1 = spSaveX2 + 4;
        spCancelY1 = cy;
        spCancelX2 = spCancelX1 + bw;
        spCancelY2 = cy + 18;
        RpButton.draw(
                g,
                spCancelX1,
                spCancelY1,
                spCancelX2,
                spCancelY2,
                "关闭",
                inRect(mouseX, mouseY, spCancelX1, spCancelY1, spCancelX2, spCancelY2) ? borderHover : border,
                false);
        cy += 26;

        int ry = cy + 4;
        int rx = cx;
        int rw2 = cw - rx;
        g.drawString(font, "提示：点“+ 添加当前坐标”把传送到此处的坐标记入；规则=分摊/集中。", rx, ry, RpTheme.TEXT_DIM);
        ry += 16;

        spRemoveBounds.clear();
        spTeleportBounds.clear();
        for (int i = 0; i < spawnPts.size(); i++) {
            double[] p = spawnPts.get(i);
            g.drawString(
                    font,
                    String.format("(%d, %d, %d)  %s", (int) p[0], (int) p[1], (int) p[2], spawnDims.get(i)),
                    rx,
                    ry + 3,
                    RpTheme.TEXT_PRIMARY);
            int rbX = x1 + w - 14 - 48;
            // 每行「传送」按钮：直接传送到该坐标（就地检查出生点/复活点）
            int tpX = rbX - 48 - 4;
            spTeleportBounds.add(new int[] {tpX, ry, tpX + 48, ry + 16});
            RpButton.draw(
                    g,
                    tpX,
                    ry,
                    tpX + 48,
                    ry + 16,
                    "传送",
                    inRect(mouseX, mouseY, tpX, ry, tpX + 48, ry + 16) ? borderHover : border,
                    false);
            spRemoveBounds.add(new int[] {rbX, ry, rbX + 48, ry + 16});
            RpButton.draw(
                    g,
                    rbX,
                    ry,
                    rbX + 48,
                    ry + 16,
                    "移除",
                    inRect(mouseX, mouseY, rbX, ry, rbX + 48, ry + 16) ? borderHover : border,
                    false);
            ry += 22;
        }
        if (spawnPts.isEmpty()) {
            g.drawString(font, "（未配置出生点：部署回退部署点/世界出生点）", rx, ry + 3, RpTheme.TEXT_DIM);
        }
    }

    private boolean inRect(int mx, int my, int x1, int y1, int x2, int y2) {
        return mx >= x1 && mx <= x2 && my >= y1 && my <= y2;
    }

    /** 弹窗内命中处理（在 mouseClicked 顶部调用）。 */
    private boolean spawnModalClick(double mx, double my) {
        if (inRect((int) mx, (int) my, spRuleX1, spRuleY1, spRuleX2, spRuleY2)) {
            spawnRule = "SINGLE".equals(spawnRule) ? "SPREAD" : "SINGLE";
            return true;
        }
        if (inRect((int) mx, (int) my, spAddX1, spAddY1, spAddX2, spAddY2)) {
            addCurrentPos();
            return true;
        }
        if (inRect((int) mx, (int) my, spSaveX1, spSaveY1, spSaveX2, spSaveY2)) {
            saveSpawn();
            return true;
        }
        if (inRect((int) mx, (int) my, spCancelX1, spCancelY1, spCancelX2, spCancelY2)) {
            spawnModalOpen = false;
            return true;
        }
        for (int i = 0; i < spRemoveBounds.size(); i++) {
            int[] rb = spRemoveBounds.get(i);
            if (inRect((int) mx, (int) my, rb[0], rb[1], rb[2], rb[3])) {
                if (i < spawnPts.size()) {
                    spawnPts.remove(i);
                    spawnDims.remove(i);
                }
                return true;
            }
        }
        for (int i = 0; i < spTeleportBounds.size(); i++) {
            int[] tb = spTeleportBounds.get(i);
            if (inRect((int) mx, (int) my, tb[0], tb[1], tb[2], tb[3])) {
                if (i < spawnPts.size()) {
                    double[] p = spawnPts.get(i);
                    RpChannels.sendToServer(new RpPackets.AdminTeleportC2S(p[0], p[1], p[2], spawnDims.get(i)));
                }
                return true;
            }
        }
        return false;
    }

    // ---------- 无线电编辑器（阵营/职业共用弹窗：speaker + 多句 text/wait；职业另有禁用开关） ----------

    /** 打开无线电管理弹窗：kind=faction|profession，从当前选中条目载入配置到工作副本。 */
    private void openRadioModal(String kind) {
        JsonObject target = "profession".equals(kind) ? selProf() : selFaction();
        if (target == null || str(target, "id").isBlank()) {
            notice = "请先在左侧选择" + ("profession".equals(kind) ? "职业" : "阵营");
            return;
        }
        radioModalKind = kind;
        radioModalTarget = str(target, "id");
        radioLines.clear();
        radioWaits.clear();
        radioSpeaker = "指挥官";
        radioDisabled = false;
        if (target.has("radio") && target.get("radio").isJsonObject()) {
            JsonObject r = target.getAsJsonObject("radio");
            radioSpeaker = str(r, "speaker", "指挥官");
            if (r.has("lines") && r.get("lines").isJsonArray()) {
                for (com.google.gson.JsonElement e : r.getAsJsonArray("lines")) {
                    if (!e.isJsonObject()) {
                        continue;
                    }
                    JsonObject o = e.getAsJsonObject();
                    radioLines.add(str(o, "text", ""));
                    radioWaits.add(
                            o.has("wait") && o.get("wait").isJsonPrimitive()
                                    ? String.valueOf(o.get("wait").getAsDouble())
                                    : "1.5");
                }
            }
        }
        if (radioLines.isEmpty()) {
            radioLines.add("");
            radioWaits.add("1.5");
        }
        radioDisabled = "profession".equals(kind)
                && target.has("radioDisabled")
                && target.get("radioDisabled").getAsBoolean();
        radioScroll = 0;
        notice = "";
        // 清理上一轮编辑框状态：重新打开时重建输入框（值取自上面载入的 radioSpeaker/radioLines）
        if (radioSpeakerBox != null) {
            removeWidget(radioSpeakerBox);
            radioSpeakerBox = null;
        }
        for (Object[] e : radioTextEdits) {
            removeWidget((EditBox) e[0]);
            removeWidget((EditBox) e[1]);
        }
        radioTextEdits.clear();
        radioEditStart = -1;
        radioEditCount = 0;
        radioModalOpen = true;
    }

    /** 发送无线电配置到服务端（写 faction/profession radio 字段），成功后关闭弹窗。 */
    private void saveRadioModal() {
        if (radioModalTarget.isBlank()) {
            notice = "请先选择目标";
            return;
        }
        com.google.gson.JsonObject radio = new com.google.gson.JsonObject();
        radio.addProperty("speaker", radioSpeaker.isBlank() ? "指挥官" : radioSpeaker);
        com.google.gson.JsonArray lines = new com.google.gson.JsonArray();
        for (int i = 0; i < radioLines.size(); i++) {
            String text = radioLines.get(i);
            if (text == null || text.isBlank()) {
                continue;
            }
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("text", text);
            double wait = 1.5;
            try {
                wait = Math.max(0, Double.parseDouble(radioWaits.get(i)));
            } catch (Exception ignored) {
                // 非法数值用默认 1.5s
            }
            o.addProperty("wait", wait);
            lines.add(o);
        }
        radio.add("lines", lines);
        com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
        payload.addProperty("id", radioModalTarget);
        payload.add("radio", radio);
        if ("profession".equals(radioModalKind)) {
            payload.addProperty("radioDisabled", radioDisabled);
        }
        RpChannels.sendToServer(new RpPackets.ManagerCrudC2S(
                "profession".equals(radioModalKind) ? "radio-profession" : "radio-faction",
                "update",
                payload.toString()));
        closeRadioModal();
    }

    /** 渲染无线电管理弹窗（每帧；按钮手动绘制，命中在 radioModalClick）。 */
    private void renderRadioModal(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 输入框在打开时创建、跨帧稳定复用（仅编辑行范围变化/关闭时重建），避免每帧重建丢失焦点与输入
        g.fill(0, 0, width, height, 0xA6000000);
        int w = Math.min(560, width - 40);
        int h = Math.min(420, height - 40);
        int x1 = (width - w) / 2;
        int y1 = (height - h) / 2;
        rdX1 = x1;
        rdY1 = y1;
        rdX2 = x1 + w;
        rdY2 = y1 + h;
        RpTheme.terminalPanel(g, x1, y1, x1 + w, y1 + h, RpTheme.RADIUS_LARGE);
        g.drawString(
                font,
                ("profession".equals(radioModalKind) ? "无线电管理（职业）— " : "无线电管理（阵营）— ") + radioModalTarget,
                x1 + 14,
                y1 + 10,
                RpTheme.CYAN,
                true);
        g.fill(x1 + 8, y1 + 26, x1 + w - 8, y1 + 27, RpTheme.CYAN_DIM);

        int cx = x1 + 14;
        int cw = x1 + w - 14;
        int cy = y1 + 38;
        int bw = Math.max(64, (cw - cx - 12) / 4);
        int border = RpTheme.PANEL_BORDER;
        int borderHover = RpTheme.PANEL_BORDER_BRIGHT;
        // 说话人（阵营色渲染前缀）：首次创建、后续复用（不重建，保证可输入且不丢焦点）
        g.drawString(font, "说话人（阵营色）:", cx, cy, RpTheme.TEXT_DIM);
        if (radioSpeakerBox == null) {
            radioSpeakerBox = mkBox(cx + 120, cy, cw - cx - 120, "", radioSpeaker, false);
        } else {
            radioSpeakerBox.setX(cx + 120);
            radioSpeakerBox.setY(cy);
            radioSpeakerBox.setWidth(cw - cx - 120);
        }
        radioSpeakerBox.render(g, mouseX, mouseY, partialTick);
        cy += 30;
        // 添加句子
        rdAddX1 = cx;
        rdAddY1 = cy;
        rdAddX2 = cx + bw;
        rdAddY2 = cy + 18;
        RpButton.draw(
                g,
                rdAddX1,
                rdAddY1,
                rdAddX2,
                rdAddY2,
                "+ 添加句子",
                inRect(mouseX, mouseY, rdAddX1, rdAddY1, rdAddX2, rdAddY2) ? borderHover : border,
                false);
        // 职业：禁用无线电开关
        if ("profession".equals(radioModalKind)) {
            rdToggleX1 = rdAddX2 + 4;
            rdToggleY1 = cy;
            rdToggleX2 = rdToggleX1 + bw * 2;
            rdToggleY2 = cy + 18;
            RpButton.draw(
                    g,
                    rdToggleX1,
                    rdToggleY1,
                    rdToggleX2,
                    rdToggleY2,
                    "禁用无线电: " + (radioDisabled ? "开" : "关"),
                    inRect(mouseX, mouseY, rdToggleX1, rdToggleY1, rdToggleX2, rdToggleY2) ? borderHover : border,
                    radioDisabled);
        }
        // 保存
        rdSaveX1 = ("profession".equals(radioModalKind) ? rdToggleX2 : rdAddX2) + 4;
        rdSaveY1 = cy;
        rdSaveX2 = rdSaveX1 + bw;
        rdSaveY2 = cy + 18;
        RpButton.draw(
                g,
                rdSaveX1,
                rdSaveY1,
                rdSaveX2,
                rdSaveY2,
                "保存",
                inRect(mouseX, mouseY, rdSaveX1, rdSaveY1, rdSaveX2, rdSaveY2) ? borderHover : border,
                true);
        // 关闭
        rdCancelX1 = rdSaveX2 + 4;
        rdCancelY1 = cy;
        rdCancelX2 = rdCancelX1 + bw;
        rdCancelY2 = cy + 18;
        RpButton.draw(
                g,
                rdCancelX1,
                rdCancelY1,
                rdCancelX2,
                rdCancelY2,
                "关闭",
                inRect(mouseX, mouseY, rdCancelX1, rdCancelY1, rdCancelX2, rdCancelY2) ? borderHover : border,
                false);
        cy += 26;

        g.drawString(font, "提示：行内直接编辑句子与停留秒数；按住行首「≡」上下拖动调换顺序；新句子追加在尾部。", cx, cy, RpTheme.TEXT_DIM);
        cy += 16;

        // 句子列表（滚动，行高 RADIO_ROW_H）：拖拽手柄 + 文本输入 + 停留秒数输入 + 删除 —— 行内编辑
        int listTop = cy + 4;
        int listBottom = rdY2 - 20;
        int maxVis = Math.max(1, (listBottom - listTop) / RADIO_ROW_H);
        int off = Math.min(radioScroll, Math.max(0, radioLines.size() - maxVis));
        radioListTop = listTop;
        radioMaxVis = maxVis;
        radioOff = off;
        rdDelBounds.clear();
        rdHandleBounds.clear();
        // 行内输入框范围跟踪：可见范围 (off, maxVis) 变化（滚动/增删/调序）时重建，稳定复用防焦点丢失
        if (radioEditStart != off || radioEditCount != maxVis) {
            for (Object[] e : radioTextEdits) {
                removeWidget((EditBox) e[0]);
                removeWidget((EditBox) e[1]);
            }
            radioTextEdits.clear();
            radioEditStart = off;
            radioEditCount = maxVis;
        }
        int ry = listTop;
        for (int i = 0; i < maxVis; i++) {
            int idx = off + i;
            if (idx >= radioLines.size()) {
                break;
            }
            int rowRight = cw - 14;
            int delW = 40;
            int delX = rowRight - delW;
            int hx = cx + 4;
            int hw = 16;
            int gap = 6;
            int avail = rowRight - cx - hw - delW - 3 * gap;
            int waitW = Math.max(48, Math.min(96, avail / 4));
            int textW = avail - waitW;
            int tbX = hx + hw + gap;
            int wbX = tbX + textW + gap;
            int boxY = ry + 4;
            RpRoundRect.outlined(
                    g,
                    cx,
                    ry,
                    rowRight,
                    ry + RADIO_ROW_H - 2,
                    6f,
                    inRect(mouseX, mouseY, cx, ry, rowRight, ry + RADIO_ROW_H - 2) ? borderHover : border,
                    idx % 2 == 0 ? RpTheme.PANEL_BG : RpTheme.PANEL_BG_EVEN);
            // 拖拽手柄（左：按住上下拖动调序）
            int hx2 = hx + hw;
            int hy = ry + 6;
            int hy2 = ry + RADIO_ROW_H - 6;
            rdHandleBounds.add(new int[] {hx, hy, hx2, hy2, idx});
            g.drawString(
                    font,
                    "≡",
                    hx + 4,
                    hy + 2,
                    inRect(mouseX, mouseY, hx, hy, hx2, hy2) ? RpTheme.CYAN : RpTheme.TEXT_SECONDARY);
            // 行内文本 + 停留秒数输入框（稳定复用）
            Object[] e = i < radioTextEdits.size() ? radioTextEdits.get(i) : null;
            EditBox tb;
            EditBox wb;
            if (e == null) {
                tb = mkBox(tbX, boxY, textW, "", radioLines.get(idx), false);
                wb = mkBox(wbX, boxY, waitW, "", radioWaits.get(idx), false);
                radioTextEdits.add(new Object[] {tb, wb, idx});
            } else {
                tb = (EditBox) e[0];
                wb = (EditBox) e[1];
                tb.setX(tbX);
                tb.setY(boxY);
                tb.setWidth(textW);
                wb.setX(wbX);
                wb.setY(boxY);
                wb.setWidth(waitW);
            }
            tb.render(g, mouseX, mouseY, partialTick);
            wb.render(g, mouseX, mouseY, partialTick);
            // 删除按钮（右）
            rdDelBounds.add(new int[] {delX, ry, delX + delW, ry + 18, idx});
            RpButton.draw(
                    g,
                    delX,
                    ry + 1,
                    delX + delW,
                    ry + 19,
                    "删",
                    inRect(mouseX, mouseY, delX, ry + 1, delX + delW, ry + 19) ? borderHover : border,
                    false);
            ry += RADIO_ROW_H;
        }
        if (radioLines.isEmpty()) {
            g.drawString(font, "（空：点「+ 添加句子」开始）", cx, listTop + 4, RpTheme.TEXT_DIM);
        }
        RpScrollbar.draw(g, cw - 8, listTop, listBottom, radioLines.size(), maxVis, off);
    }

    /** 无线电弹窗命中（在 mouseClicked 顶部调用，弹窗期间吞掉底层点击）。 */
    private boolean radioModalClick(double mx, double my, int button) {
        // 先把输入框当前值刷回 radioLines/radioWaits/radioSpeaker，再做结构操作（增删行/保存），避免丢输入
        collectRadioFields();
        if (inRect((int) mx, (int) my, rdAddX1, rdAddY1, rdAddX2, rdAddY2)) {
            radioLines.add("");
            radioWaits.add("1.5");
            radioScroll = radioLines.size();
            return true;
        }
        if (inRect((int) mx, (int) my, rdSaveX1, rdSaveY1, rdSaveX2, rdSaveY2)) {
            collectRadioFields();
            saveRadioModal();
            return true;
        }
        if (inRect((int) mx, (int) my, rdCancelX1, rdCancelY1, rdCancelX2, rdCancelY2)) {
            closeRadioModal();
            return true;
        }
        if ("profession".equals(radioModalKind)
                && inRect((int) mx, (int) my, rdToggleX1, rdToggleY1, rdToggleX2, rdToggleY2)) {
            radioDisabled = !radioDisabled;
            return true;
        }
        for (int[] h : rdHandleBounds) {
            if (inRect((int) mx, (int) my, h[0], h[1], h[2], h[3])) {
                int idx = h.length > 4 ? h[4] : -1;
                if (idx >= 0 && idx < radioLines.size()) {
                    radioDragIdx = idx; // 开始拖拽调序（mouseDragged 里移动行）
                }
                return true;
            }
        }
        for (int[] b : rdDelBounds) {
            if (inRect((int) mx, (int) my, b[0], b[1], b[2], b[3])) {
                int idx = b.length > 4 ? b[4] : -1;
                if (idx >= 0 && idx < radioLines.size()) {
                    radioLines.remove(idx);
                    radioWaits.remove(idx);
                }
                return true;
            }
        }
        for (Object[] e : radioTextEdits) {
            EditBox tb = (EditBox) e[0];
            EditBox wb = (EditBox) e[1];
            boolean hitTb = tb.mouseClicked(mx, my, button);
            if (hitTb || wb.mouseClicked(mx, my, button)) {
                setFocused(hitTb ? tb : wb);
                return true;
            }
        }
        return true;
    }

    private void collectRadioFields() {
        for (Object[] e : radioTextEdits) {
            int i = (Integer) e[2];
            if (i < radioLines.size()) {
                radioLines.set(i, ((EditBox) e[0]).getValue());
                radioWaits.set(i, ((EditBox) e[1]).getValue());
            }
        }
        if (radioSpeakerBox != null) {
            radioSpeaker = radioSpeakerBox.getValue();
        }
    }

    /** 按鼠标 Y 计算拖拽目标行索引（限制在可见行范围内）。 */
    private int radioDragTarget(double my) {
        if (radioMaxVis <= 0 || radioListTop <= 0) {
            return radioDragIdx;
        }
        int v = (int) ((my - radioListTop) / RADIO_ROW_H);
        v = Math.max(0, Math.min(v, radioMaxVis - 1));
        int idx = radioOff + v;
        return Math.max(0, Math.min(idx, radioLines.size() - 1));
    }

    /** 拖拽调序：把 from 行移动到 to 行，并强制下一帧重建行内输入框。 */
    private void moveRadioLine(int from, int to) {
        if (from == to || from < 0 || from >= radioLines.size() || to < 0 || to >= radioLines.size()) {
            return;
        }
        String t = radioLines.remove(from);
        String w = radioWaits.remove(from);
        radioLines.add(to, t);
        radioWaits.add(to, w);
        radioDragIdx = to;
        radioEditStart = -1;
        radioEditCount = 0;
    }

    /** 关闭无线电编辑弹窗并清理其专属输入框 widget（等价于行为序列弹窗的 closeSequenceModal）。 */
    private void closeRadioModal() {
        radioDragIdx = -1;
        if (radioSpeakerBox != null) {
            removeWidget(radioSpeakerBox);
            radioSpeakerBox = null;
        }
        for (Object[] e : radioTextEdits) {
            removeWidget((EditBox) e[0]);
            removeWidget((EditBox) e[1]);
        }
        radioTextEdits.clear();
        radioEditStart = -1;
        radioEditCount = 0;
        setFocused(null);
        radioModalOpen = false;
    }

    // ---------- 行为序列编辑器（流程编辑器，仿出生点弹窗） ----------

    private static final String[] SEQ_TYPES = {"WAIT", "WAVE", "COMMAND", "FORCE_PICK"};
    /** 「触发事件」锚点步骤类型：序列中的只读锚点，不可删除/不可改类型/不可编辑参数，但可上移下移调整位置。 */
    private static final String TRIGGER_TYPE = "TRIGGER";

    /** 生成「触发事件」锚点步骤（只读）：source=kind:id，label=显示名。 */
    private JsonObject makeTriggerAnchor() {
        JsonObject it = selItem();
        String id = it == null ? idBox.getValue() : str(it, "id");
        JsonObject a = new JsonObject();
        a.addProperty("type", TRIGGER_TYPE);
        a.addProperty("source", crudKind() + ":" + id);
        a.addProperty("label", kindLabel(crudKind()) + " " + id);
        return a;
    }

    private boolean isTriggerStep(int idx) {
        return idx >= 0
                && idx < seqSteps.size()
                && TRIGGER_TYPE.equals(str(seqSteps.get(idx), "type", "").toUpperCase(java.util.Locale.ROOT));
    }

    /** 第一个可编辑（非锚点）步骤下标；无则 -1。 */
    private int firstEditableStep() {
        for (int i = 0; i < seqSteps.size(); i++) {
            if (!isTriggerStep(i)) {
                return i;
            }
        }
        return -1;
    }

    /** 打开流程编辑器弹窗：载入当前选中条目（事件/阶段/刷新波）的 sequence 数组到工作副本。 */
    private void openSequenceModal() {
        JsonObject it = selItem();
        if (it == null || str(it, "id").isBlank()) {
            notice = "请先在左侧选择条目";
            return;
        }
        seqModalTitle = kindLabel(crudKind()) + " " + str(it, "id");
        seqSteps.clear();
        if (it.has("sequence") && it.get("sequence").isJsonArray()) {
            for (JsonElement e : it.getAsJsonArray("sequence")) {
                if (e.isJsonObject()) {
                    seqSteps.add(e.getAsJsonObject().deepCopy());
                }
            }
        }
        // 「触发事件」锚点：序列中必须保留一个（不可删/不可改类型/不可编辑参数，可上移下移）。
        // 旧数据缺失时补插为首项；已有则保留原位置，仅刷新 source/label 与当前上下文对齐
        boolean hasTrigger = false;
        for (int i = 0; i < seqSteps.size(); i++) {
            if (isTriggerStep(i)) {
                seqSteps.set(i, makeTriggerAnchor());
                hasTrigger = true;
                break;
            }
        }
        if (!hasTrigger) {
            seqSteps.add(0, makeTriggerAnchor());
        }
        // 默认选中第一个可编辑步骤（锚点只读）
        stepSel = firstEditableStep();
        seqScroll = 0;
        seqModalOpen = true;
        notice = "";
        rebuildSeqBoxes();
    }

    private static String kindLabel(String kind) {
        return switch (kind) {
            case "event" -> "事件";
            case "phase" -> "阶段";
            case "wave" -> "刷新波";
            default -> kind;
        };
    }

    private static JsonObject defaultStep(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        switch (type) {
            case "WAIT" -> o.addProperty("seconds", 10);
            case "WAVE" -> o.addProperty("wave", "");
            case "COMMAND" -> o.addProperty("command", "");
            case "FORCE_PICK" -> {
                o.addProperty("count", 1);
                o.addProperty("professions", "");
                o.addProperty("faction", "");
            }
            default -> {}
        }
        return o;
    }

    /** 步骤摘要（列表行显示）。 */
    private static String stepSummary(JsonObject s) {
        String type = str(s, "type", "WAIT").toUpperCase(java.util.Locale.ROOT);
        return switch (type) {
            case "TRIGGER" -> "触发事件：" + str(s, "label");
            case "WAIT" -> "等待 " + num(s, "seconds", 10) + "s";
            case "WAVE" -> "召唤波 " + str(s, "wave");
            case "COMMAND" -> "命令 " + str(s, "command");
            case "FORCE_PICK" -> "征召 " + num(s, "count", 1) + " 人 [" + str(s, "professions") + "] (" + str(s, "faction")
                    + ")";
            default -> type;
        };
    }

    private void cycleStepType() {
        if (isTriggerStep(stepSel)) {
            return; // 锚点不可改类型
        }
        if (stepSel < 0 || stepSel >= seqSteps.size()) {
            return;
        }
        String cur = str(seqSteps.get(stepSel), "type", "WAIT").toUpperCase(java.util.Locale.ROOT);
        int idx = 0;
        for (int i = 0; i < SEQ_TYPES.length; i++) {
            if (SEQ_TYPES[i].equals(cur)) {
                idx = i;
            }
        }
        seqSteps.set(stepSel, defaultStep(SEQ_TYPES[(idx + 1) % SEQ_TYPES.length]));
        rebuildSeqBoxes();
    }

    private void addSeqStep() {
        seqSteps.add(defaultStep("WAIT"));
        stepSel = seqSteps.size() - 1;
        seqScroll = Math.max(0, seqSteps.size() - seqMaxVisible());
        rebuildSeqBoxes();
    }

    /** 弹窗几何（渲染与输入框重建共用，随窗口大小变化重算）。 */
    private void layoutSeqModal() {
        int w = Math.min(560, width - 40);
        int h = Math.min(420, height - 40);
        sqX1 = (width - w) / 2;
        sqY1 = (height - h) / 2;
        sqX2 = sqX1 + w;
        sqY2 = sqY1 + h;
        sqListY1 = sqY1 + 84;
        sqListY2 = sqY2 - 128;
        sqFieldX1 = sqX1 + 14;
        sqFieldY1 = sqY2 - 112;
        sqFieldX2 = sqX2 - 14;
        sqFieldY2 = sqY2 - 14;
    }

    /** 步骤列表可见行数。 */
    private int seqMaxVisible() {
        int rowH = 22;
        int h = sqListY2 - sqListY1;
        return Math.max(1, (h + 2) / rowH);
    }

    /** 重建选中步骤的参数字段输入框（打开/点选/切类型/添加时调用；锚点只读无输入框）。 */
    private void rebuildSeqBoxes() {
        layoutSeqModal(); // 先算弹窗几何（输入框按弹窗内坐标定位）
        for (EditBox b : seqBoxes.values()) {
            removeWidget(b);
        }
        seqBoxes.clear();
        // stepSel 可为 -1（序列只有触发锚点、无可编辑步骤时 firstEditableStep 返回 -1）：
        // 此时不生成任何参数输入框，等待用户点「+ 添加步骤」后再重建
        if (!seqModalOpen || stepSel < 0 || stepSel >= seqSteps.size() || isTriggerStep(stepSel)) {
            return;
        }
        JsonObject s = seqSteps.get(stepSel);
        String type = str(s, "type", "WAIT").toUpperCase(java.util.Locale.ROOT);
        int x = sqFieldX1;
        int w = sqFieldX2 - sqFieldX1;
        int bw2 = (w - 4) / 2;
        // 字段描述用输入框灰色占位提示（值空时显示，输入即消失），避免描述文本画在框内造成重叠
        switch (type) {
            case "WAIT" -> seqBoxes.put(
                    "seconds", seqBox(x, sqFieldY1, w, "等待秒数", String.valueOf(num(s, "seconds", 10))));
            case "WAVE" -> seqBoxes.put("wave", seqBox(x, sqFieldY1, w, "刷新波 ID", str(s, "wave")));
            case "COMMAND" -> seqBoxes.put(
                    "command",
                    seqBox(x, sqFieldY1, w, "命令文本：可用 {{event}} {{phase}} {{seq}} {{trigger}} 变量", str(s, "command")));
            case "FORCE_PICK" -> {
                seqBoxes.put("count", seqBox(x, sqFieldY1, bw2, "数量", String.valueOf(num(s, "count", 1))));
                seqBoxes.put("professions", seqBox(x + bw2 + 4, sqFieldY1, bw2, "职业ID(逗号)", str(s, "professions")));
                seqBoxes.put("faction", seqBox(x, sqFieldY1 + 24, w, "阵营ID", str(s, "faction")));
            }
            default -> {}
        }
    }

    /** 流程编辑器参数字段：带灰色占位提示；不注册 fieldLabels（弹窗关闭后由 closeSequenceModal 统一清理）。 */
    private EditBox seqBox(int x, int y, int w, String hint, String value) {
        EditBox box = new EditBox(font, x, y, w, 18, Component.literal(hint));
        box.setMaxLength(512);
        box.setValue(value == null ? "" : value);
        box.setTextColor(RpTheme.CYAN);
        box.setSuggestion(hint); // 值空时显示描述文本（占位提示），输入后自动消失，不重叠
        box.setEditable(true);
        addRenderableWidget(box);
        return box;
    }

    /** 关闭流程编辑器：移除参数字段输入框并释放屏幕焦点（防关闭后残留 GUI / 焦点指向已移除控件）。 */
    private void closeSequenceModal() {
        for (EditBox b : seqBoxes.values()) {
            removeWidget(b);
        }
        seqBoxes.clear();
        setFocused(null);
        seqModalOpen = false;
    }

    /** 把选中步骤的输入框值写回工作副本（保存/切行/切类型前调用）。 */
    private void collectSeqFields() {
        if (stepSel < 0 || stepSel >= seqSteps.size()) {
            return;
        }
        JsonObject s = seqSteps.get(stepSel);
        for (java.util.Map.Entry<String, EditBox> e : seqBoxes.entrySet()) {
            String v = e.getValue().getValue().trim();
            if ("count".equals(e.getKey()) || "seconds".equals(e.getKey())) {
                try {
                    s.addProperty(e.getKey(), Integer.parseInt(v));
                } catch (Exception ignored) {
                    // 非法数字保留原值
                }
            } else {
                s.addProperty(e.getKey(), v);
            }
        }
    }

    /** 保存流程：收集字段 → 写 editedSequence → 复用主表单 CRUD 保存（payload 带上 sequence）→ 关闭。 */
    private void saveSequenceModal() {
        collectSeqFields();
        // 锚点（触发事件）原位对齐当前上下文：source/label 由容器 id 生成，位置保留用户调整结果；缺失补插为首项
        JsonObject anchor = makeTriggerAnchor();
        boolean refreshed = false;
        for (int i = 0; i < seqSteps.size(); i++) {
            if (isTriggerStep(i)) {
                seqSteps.get(i).addProperty("source", anchor.get("source").getAsString());
                seqSteps.get(i).addProperty("label", anchor.get("label").getAsString());
                refreshed = true;
                break;
            }
        }
        if (!refreshed) {
            seqSteps.add(0, anchor);
        }
        JsonArray arr = new JsonArray();
        for (JsonObject s : seqSteps) {
            arr.add(s.deepCopy());
        }
        editedSequence = arr;
        closeSequenceModal();
        String kind = crudKind();
        boolean edit = !idBox.getValue().isBlank();
        requestCrud(kind, edit ? "update" : "create", buildPayload());
        rebuild();
    }

    /** 渲染流程编辑器弹窗（每帧；按钮手动绘制，命中在 sequenceModalClick）。 */
    private void renderSequenceModal(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xA6000000);
        layoutSeqModal();
        RpTheme.terminalPanel(g, sqX1, sqY1, sqX2, sqY2, RpTheme.RADIUS_LARGE);
        g.drawString(font, "行为序列 — " + seqModalTitle, sqX1 + 14, sqY1 + 10, RpTheme.CYAN, true);
        g.fill(sqX1 + 8, sqY1 + 26, sqX2 - 8, sqY1 + 27, RpTheme.CYAN_DIM);

        int cx = sqX1 + 14;
        int cw = sqX2 - 14;
        int cy = sqY1 + 38;
        int bw = Math.max(76, (cw - cx - 12) / 4);
        int border = RpTheme.PANEL_BORDER;
        int borderHover = RpTheme.PANEL_BORDER_BRIGHT;
        String typeCur = stepSel >= 0 && stepSel < seqSteps.size()
                ? str(seqSteps.get(stepSel), "type", "WAIT").toUpperCase(java.util.Locale.ROOT)
                : "—";
        // 类型切换
        sqRuleX1 = cx;
        sqRuleY1 = cy;
        sqRuleX2 = cx + bw;
        sqRuleY2 = cy + 18;
        RpButton.draw(
                g,
                sqRuleX1,
                sqRuleY1,
                sqRuleX2,
                sqRuleY2,
                "类型: " + typeCur,
                inRect(mouseX, mouseY, sqRuleX1, sqRuleY1, sqRuleX2, sqRuleY2) ? borderHover : border,
                stepSel >= 1); // 锚点不可改类型
        // 添加步骤
        sqAddX1 = sqRuleX2 + 4;
        sqAddY1 = cy;
        sqAddX2 = sqAddX1 + bw;
        sqAddY2 = cy + 18;
        RpButton.draw(
                g,
                sqAddX1,
                sqAddY1,
                sqAddX2,
                sqAddY2,
                "+ 添加步骤",
                inRect(mouseX, mouseY, sqAddX1, sqAddY1, sqAddX2, sqAddY2) ? borderHover : border,
                false);
        // 保存
        sqSaveX1 = sqAddX2 + 4;
        sqSaveY1 = cy;
        sqSaveX2 = sqSaveX1 + bw;
        sqSaveY2 = cy + 18;
        RpButton.draw(
                g,
                sqSaveX1,
                sqSaveY1,
                sqSaveX2,
                sqSaveY2,
                "保存",
                inRect(mouseX, mouseY, sqSaveX1, sqSaveY1, sqSaveX2, sqSaveY2) ? borderHover : border,
                true);
        // 关闭
        sqCancelX1 = sqSaveX2 + 4;
        sqCancelY1 = cy;
        sqCancelX2 = sqCancelX1 + bw;
        sqCancelY2 = cy + 18;
        RpButton.draw(
                g,
                sqCancelX1,
                sqCancelY1,
                sqCancelX2,
                sqCancelY2,
                "关闭",
                inRect(mouseX, mouseY, sqCancelX1, sqCancelY1, sqCancelX2, sqCancelY2) ? borderHover : border,
                false);
        g.drawString(
                font,
                "提示：点击步骤行选中编辑；WAIT=等待秒数 / WAVE=召唤刷新波 / COMMAND=执行命令（{{event}} {{phase}} {{seq}} 变量）/ FORCE_PICK=强制征召。",
                cx,
                cy + 24,
                RpTheme.TEXT_DIM);

        // 步骤列表（滚动，行高 22）：点选 / 上移 / 下移 / 删除；
        // 「触发事件」锚点行为只读行（🔒 锁定样式，无删除按钮，但可上移下移调整位置）
        sqStepBounds.clear();
        sqUpBounds.clear();
        sqDownBounds.clear();
        sqDelBounds.clear();
        int ry = sqListY1;
        int maxVis = seqMaxVisible();
        int off = Math.min(seqScroll, Math.max(0, seqSteps.size() - maxVis));
        for (int i = 0; i < maxVis; i++) {
            int idx = off + i;
            if (idx >= seqSteps.size()) {
                break;
            }
            JsonObject s = seqSteps.get(idx);
            boolean sel = idx == stepSel;
            boolean anchor = isTriggerStep(idx);
            int rX1 = cx;
            int rX2 = cw - 158; // 右侧留按钮区（锚点少一个「删」，按钮区仍对齐）
            if (sel) {
                RpTheme.selectedBar(g, rX1, ry, rX2, ry + 20, 6f);
            } else if (anchor) {
                RpRoundRect.outlined(
                        g,
                        rX1,
                        ry,
                        rX2,
                        ry + 20,
                        6f,
                        inRect(mouseX, mouseY, rX1, ry, rX2, ry + 20) ? RpTheme.CYAN_DIM : border,
                        RpTheme.alphaBlend(RpTheme.CYAN, 0x18));
            } else {
                RpRoundRect.outlined(
                        g,
                        rX1,
                        ry,
                        rX2,
                        ry + 20,
                        6f,
                        inRect(mouseX, mouseY, rX1, ry, rX2, ry + 20) ? borderHover : border,
                        i % 2 == 0 ? RpTheme.PANEL_BG : RpTheme.PANEL_BG_EVEN);
            }
            String label = anchor
                    ? "🔒 " + stepSummary(s) + "（只读，触发来源 " + str(s, "source") + "）"
                    : "[" + idx + "] " + stepSummary(s);
            g.drawString(
                    font, label, rX1 + 6, ry + 5, sel ? 0xFFFFFFFF : (anchor ? RpTheme.CYAN : RpTheme.TEXT_PRIMARY));
            // 5 元素：x1,y1,x2,y2,absIdx（绝对下标，供点击映射；锚点含 ↑↓ 不含 删）
            sqStepBounds.add(new int[] {rX1, ry, rX2, ry + 20, idx});
            int bx = rX2 + 4;
            int bw3 = Math.max(30, (cw - bx - 4) / 3);
            sqUpBounds.add(new int[] {bx, ry, bx + bw3, ry + 20, idx});
            RpButton.draw(
                    g,
                    bx,
                    ry,
                    bx + bw3,
                    ry + 20,
                    "↑",
                    inRect(mouseX, mouseY, bx, ry, bx + bw3, ry + 20) ? borderHover : border,
                    false);
            sqDownBounds.add(new int[] {bx + bw3 + 2, ry, bx + bw3 * 2 + 2, ry + 20, idx});
            RpButton.draw(
                    g,
                    bx + bw3 + 2,
                    ry,
                    bx + bw3 * 2 + 2,
                    ry + 20,
                    "↓",
                    inRect(mouseX, mouseY, bx + bw3 + 2, ry, bx + bw3 * 2 + 2, ry + 20) ? borderHover : border,
                    false);
            if (!anchor) {
                sqDelBounds.add(new int[] {bx + bw3 * 2 + 4, ry, bx + bw3 * 3 + 4, ry + 20, idx});
                RpButton.draw(
                        g,
                        bx + bw3 * 2 + 4,
                        ry,
                        bx + bw3 * 3 + 4,
                        ry + 20,
                        "删",
                        inRect(mouseX, mouseY, bx + bw3 * 2 + 4, ry, bx + bw3 * 3 + 4, ry + 20) ? borderHover : border,
                        false);
            }
            ry += 22;
        }
        if (seqSteps.isEmpty()) {
            g.drawString(font, "（空：点「+ 添加步骤」开始编排）", cx, sqListY1 + 4, RpTheme.TEXT_DIM);
        }
        RpScrollbar.draw(g, cw - 8, sqListY1, sqListY2, seqSteps.size(), maxVis, off);

        // 选中项参数区：锚点只读展示；可编辑步骤按类型生成输入框
        if (isTriggerStep(stepSel)) {
            JsonObject anchor = seqSteps.get(stepSel);
            g.drawString(font, "触发事件锚点（只读）：", sqFieldX1, sqFieldY1 - 12, RpTheme.TEXT_DIM);
            g.drawString(
                    font,
                    str(anchor, "label") + "（source=" + str(anchor, "source") + "）",
                    sqFieldX1,
                    sqFieldY1 + 4,
                    RpTheme.TEXT_SECONDARY);
            g.drawString(
                    font,
                    "代表触发本序列的真实事件/环境，不可删除、不可修改、不可编辑参数；可用 ↑↓ 调整位置，实际执行由其余步骤承担。",
                    sqFieldX1,
                    sqFieldY1 + 22,
                    RpTheme.TEXT_DIM);
        } else if (stepSel >= 0 && stepSel < seqSteps.size()) {
            String type = str(seqSteps.get(stepSel), "type", "WAIT").toUpperCase(java.util.Locale.ROOT);
            g.drawString(font, "步骤参数（" + type + "）：", sqFieldX1, sqFieldY1 - 12, RpTheme.TEXT_DIM);
            // 字段描述以输入框灰色占位提示呈现（值空显示，输入即消失），不再画在框内与输入文本重叠
            for (EditBox b : seqBoxes.values()) {
                b.render(g, mouseX, mouseY, partialTick);
            }
        }
    }

    /** 流程编辑器弹窗命中（在 mouseClicked 顶部调用，弹窗期间吞掉底层点击）。 */
    private boolean sequenceModalClick(double mx, double my, int button) {
        if (inRect((int) mx, (int) my, sqRuleX1, sqRuleY1, sqRuleX2, sqRuleY2)) {
            cycleStepType();
            return true;
        }
        if (inRect((int) mx, (int) my, sqAddX1, sqAddY1, sqAddX2, sqAddY2)) {
            addSeqStep();
            return true;
        }
        if (inRect((int) mx, (int) my, sqSaveX1, sqSaveY1, sqSaveX2, sqSaveY2)) {
            saveSequenceModal();
            return true;
        }
        if (inRect((int) mx, (int) my, sqCancelX1, sqCancelY1, sqCancelX2, sqCancelY2)) {
            closeSequenceModal();
            return true;
        }
        for (int i = 0; i < sqUpBounds.size(); i++) {
            int[] b = sqUpBounds.get(i);
            if (inRect((int) mx, (int) my, b[0], b[1], b[2], b[3])) {
                collectSeqFields();
                int idx = b.length > 4 ? b[4] : -1;
                if (idx > 0 && idx < seqSteps.size()) { // 所有步骤（含锚点）可上移，但不能越过首位
                    java.util.Collections.swap(seqSteps, idx, idx - 1);
                    stepSel = idx - 1;
                }
                rebuildSeqBoxes();
                return true;
            }
        }
        for (int i = 0; i < sqDownBounds.size(); i++) {
            int[] b = sqDownBounds.get(i);
            if (inRect((int) mx, (int) my, b[0], b[1], b[2], b[3])) {
                collectSeqFields();
                int idx = b.length > 4 ? b[4] : -1;
                if (idx >= 0 && idx + 1 < seqSteps.size()) {
                    java.util.Collections.swap(seqSteps, idx, idx + 1);
                    stepSel = idx + 1;
                }
                rebuildSeqBoxes();
                return true;
            }
        }
        for (int i = 0; i < sqDelBounds.size(); i++) {
            int[] b = sqDelBounds.get(i);
            if (inRect((int) mx, (int) my, b[0], b[1], b[2], b[3])) {
                int idx = b.length > 4 ? b[4] : -1;
                if (idx >= 0 && idx < seqSteps.size() && !isTriggerStep(idx)) { // 锚点不可删除
                    seqSteps.remove(idx);
                    if (stepSel >= seqSteps.size()) {
                        stepSel = seqSteps.size() - 1;
                    }
                }
                rebuildSeqBoxes();
                return true;
            }
        }
        for (int i = 0; i < sqStepBounds.size(); i++) {
            int[] b = sqStepBounds.get(i);
            if (inRect((int) mx, (int) my, b[0], b[1], b[2], b[3])) {
                collectSeqFields();
                int idx = b.length > 4 ? b[4] : -1;
                if (idx >= 0 && idx < seqSteps.size()) {
                    stepSel = idx;
                }
                rebuildSeqBoxes();
                return true;
            }
        }
        for (EditBox box : seqBoxes.values()) {
            if (box.mouseClicked(mx, my, button)) {
                setFocused(box); // 弹窗点击绕过 super.mouseClicked，需手动把屏幕焦点给到输入框，键盘输入才能路由进来
                return true;
            }
        }
        return true;
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
        // 图标：当前阵营的图标不在可选列表（如自定义 img 素材未同步）时保留原值，防止保存覆盖成别的图标
        JsonObject fac = selFaction();
        String curIcon = fac == null ? "" : str(fac, "icon");
        if (!curIcon.isBlank() && !iconOptions().contains(curIcon)) {
            p.addProperty("icon", curIcon);
        } else {
            p.addProperty("icon", iconOptions().get(iconIdx));
        }
        p.addProperty("tier", tierIdx + 1);
        p.addProperty("music", musicBox.getValue());
        p.addProperty("cmdcamScene", camSceneBox == null ? "" : camSceneBox.getValue());
        requestCrud("faction", edit ? "update" : "create", p);
    }

    // ---------- 音乐管理 ----------

    /** 音乐上传行：路径/URL 输入 + 上传按钮（职业与阵营表单共用）。 */
    private void buildMusicUploadRow(int x, int y, int w) {
        int bw = Math.max(64, w / 5);
        musicUploadBox = mkBox(x, y, w - bw - 4, "ccnr_rp.gui.admin.music.path", "", false);
        addRenderableWidget(RpButton.secondary(
                x + w - bw, y, bw, 18, Component.translatable("ccnr_rp.gui.admin.music.upload"), b -> uploadMusic()));
    }

    private void uploadMusic() {
        String src = musicUploadBox.getValue();
        if (src == null || src.isBlank()) {
            notice = Component.translatable("ccnr_rp.gui.admin.music.need_path").getString();
            return;
        }
        if (src.startsWith("http://") || src.startsWith("https://")) {
            String name = musicNameFromUrl(src);
            if (name == null) {
                notice = Component.translatable("ccnr_rp.gui.admin.music.invalid_name")
                        .getString();
                return;
            }
            fetchMusicUrl(src, name);
            return;
        }
        try {
            String name = java.nio.file.Path.of(src).getFileName().toString();
            if (com.ccnrcom.rp.music.MusicStore.validateName(name) != null) {
                notice = Component.translatable("ccnr_rp.gui.admin.music.invalid_name")
                        .getString();
                return;
            }
            byte[] data = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(src));
            if (data.length > com.ccnrcom.rp.music.MusicStore.MAX_BYTES) {
                notice = Component.translatable("ccnr_rp.gui.admin.music.too_big")
                        .getString();
                return;
            }
            sendMusicChunks(name, data);
        } catch (Exception e) {
            notice = Component.translatable("ccnr_rp.gui.admin.music.read_fail").getString();
        }
    }

    /** URL 末段取文件名（去 query/fragment）；非法返回 null。 */
    private static String musicNameFromUrl(String url) {
        try {
            String path = new java.net.URL(url).getPath();
            int slash = path.lastIndexOf('/');
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            return name.isBlank() ? null : name;
        } catch (Exception e) {
            return null;
        }
    }

    /** URL 拉取音乐（后台线程，成功后回主线程上传）。 */
    private void fetchMusicUrl(String url, String name) {
        Thread t = new Thread(
                () -> {
                    byte[] data = null;
                    try {
                        java.net.HttpURLConnection conn =
                                (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                        conn.setConnectTimeout(8000);
                        conn.setReadTimeout(20000);
                        conn.setRequestProperty("User-Agent", "CCNR-RP/1.0");
                        try (java.io.InputStream in = conn.getInputStream();
                                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                bos.write(buf, 0, n);
                                if (bos.size() > com.ccnrcom.rp.music.MusicStore.MAX_BYTES) {
                                    bos.reset();
                                    bos.write(new byte[] {0});
                                    break;
                                }
                            }
                            data = bos.toByteArray();
                        }
                    } catch (Exception ignored) {
                        data = null;
                    }
                    final byte[] result = data;
                    net.minecraft.client.Minecraft.getInstance().execute(() -> {
                        if (result == null || result.length > com.ccnrcom.rp.music.MusicStore.MAX_BYTES) {
                            notice = Component.translatable("ccnr_rp.gui.admin.music.fetch_fail")
                                    .getString();
                            return;
                        }
                        sendMusicChunks(name, result);
                    });
                },
                "ccnr-rp-music-url");
        t.setDaemon(true);
        t.start();
    }

    /** 音乐分片上传（本地/URL 共用）。 */
    private void sendMusicChunks(String name, byte[] data) {
        int part = 32 * 1024;
        int total = (data.length + part - 1) / part;
        for (int i = 0; i < total; i++) {
            byte[] chunk = java.util.Arrays.copyOfRange(data, i * part, Math.min((i + 1) * part, data.length));
            RpChannels.sendToServer(new RpPackets.MusicUploadPartC2S(name, i, total, chunk));
        }
        RpChannels.sendToServer(new RpPackets.MusicUploadCommitC2S(name, data.length, total));
        notice = Component.translatable("ccnr_rp.gui.admin.music.uploading").getString();
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

    /** 更新/删除先向服务端做影响预检（波及角色/波/事件/阶段），确认后再执行；新建直接执行。 */
    private void requestCrud(String kind, String action, JsonObject payload) {
        if ("create".equals(action)) {
            sendCrud(kind, action, payload);
            return;
        }
        pendingKind = kind;
        pendingAction = action;
        pendingPayload = payload;
        RpChannels.sendToServer(new RpPackets.ManagerImpactC2S(kind, action, payload.toString()));
    }

    /** 服务端影响清单返回（ClientPacketHandlers 转发；空清单=无波及，直接执行）。 */
    public static void onImpact(String payloadJson) {
        if (open == null) {
            return;
        }
        open.applyImpact(payloadJson);
    }

    private void applyImpact(String payloadJson) {
        JsonObject root;
        try {
            root = com.ccnrcom.rp.util.JsonUtil.GSON.fromJson(payloadJson, JsonObject.class);
        } catch (Exception e) {
            root = null;
        }
        if (root == null) {
            sendCrud(pendingKind, pendingAction, pendingPayload);
            return;
        }
        impactLines.clear();
        if (root.has("lines") && root.get("lines").isJsonArray()) {
            for (var e : root.getAsJsonArray("lines")) {
                impactLines.add(e.getAsString());
            }
        }
        if (impactLines.isEmpty()) {
            // 无波及 → 直接执行
            sendCrud(pendingKind, pendingAction, pendingPayload);
            return;
        }
        impactTitle = root.has("id") ? root.get("id").getAsString() : "";
        impactOpen = true;
    }

    /** 开关当前值（缺省按 ManagerSettings 默认值，与服务端一致）。 */
    private boolean value(String key) {
        return ClientCharacterState.settingBool(key, settingDefault(key));
    }

    /** settings.json 键默认值（与服务端 ManagerSettings.defaults() 保持一致）。 */
    private static boolean settingDefault(String key) {
        return switch (key) {
            case "forceObserving",
                    "openPanelOnJoin",
                    "forceRetain",
                    "hudEnabled",
                    "hudProfessionText",
                    "firstJoinAutoDeploy" -> true;
            case "hudFactionText", "hudHealthText" -> false;
            default -> true;
        };
    }

    /** settings.json 字符串项默认值（与服务端 ManagerSettings.defaults() 保持一致）。 */
    private static String settingStringDefault(String key) {
        return switch (key) {
            case "firstJoinProfession" -> "m5_intern";
            default -> "";
        };
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // CMDCam 场景补全提示点击优先（选中项填入场景框）
        if (!camSugBounds.isEmpty()) {
            for (int i = 0; i < camSugBounds.size(); i++) {
                int[] b = camSugBounds.get(i);
                if (mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3]) {
                    if (camSugBox != null && i < camSugItems.size()) {
                        camSugBox.setValue(camSugItems.get(i));
                    }
                    camSugIdx = -1;
                    return true;
                }
            }
        }
        // 音乐补全提示点击优先（选中项填入音乐框）
        if (!musicSugBounds.isEmpty()) {
            for (int i = 0; i < musicSugBounds.size(); i++) {
                int[] b = musicSugBounds.get(i);
                if (mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3]) {
                    if (musicBox != null && i < musicSugItems.size()) {
                        musicBox.setValue(musicSugItems.get(i));
                    }
                    musicSugIdx = -1;
                    return true;
                }
            }
        }
        // 限制目标补全提示点击优先（选中项填入限制目标框；填入原始 id 而非显示名）
        if (!limitTargetSugBounds.isEmpty()) {
            for (int i = 0; i < limitTargetSugBounds.size(); i++) {
                int[] b = limitTargetSugBounds.get(i);
                if (mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3]) {
                    if (nameBox != null && i < limitTargetSugItems.size()) {
                        nameBox.setValue(sugTargetId(limitTargetSugItems.get(i)));
                    }
                    limitTargetSugIdx = -1;
                    return true;
                }
            }
        }
        // 通用 id 补全点击（刷新波/设置页/序列弹窗的 id 类输入框；多值框追加，单值框替换）
        if (!idSugBounds.isEmpty()) {
            for (int i = 0; i < idSugBounds.size(); i++) {
                int[] b = idSugBounds.get(i);
                if (mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3]) {
                    if (idSugBox != null && i < idSugItems.size()) {
                        idSugBox.setValue(applyIdSug(idSugBox.getValue(), sugTargetId(idSugItems.get(i))));
                    }
                    idSugIdx = -1;
                    return true;
                }
            }
        }
        // 非管理员直接拦截所有管理操作（服务端仍有二次校验兜底）
        if (!ClientCharacterState.isAdmin()) {
            return super.mouseClicked(mx, my, button);
        }
        // 设定（开关 + serverconfig）统一滚动条
        if (tab == TAB_SETTINGS) {
            int total = settingsRows();
            int ns = RpScrollbar.clickV(
                    (int) mx,
                    (int) my,
                    px2 - 14,
                    px2 - 9,
                    settingsYTop(),
                    py2 - 70,
                    total,
                    settingsMaxVisible(),
                    settingsScroll,
                    7);
            if (ns >= 0) {
                settingsScroll = (int) Math.max(0, Math.min(ns, Math.max(0, total - settingsMaxVisible())));
                rebuild();
                return true;
            }
        }
        // 列表滚动条：按住游标拖拽 / 点击轨道跳转（经验规则/关系管理页签无通用列表行高，跳过）
        if (tab != TAB_SETTINGS && tab != TAB_XP && tab != TAB_RELATION) {
            int maxRows = Math.max(1, (listY2 - listY1) / rowHeight());
            int ns = RpScrollbar.clickV(
                    (int) mx,
                    (int) my,
                    listX2 - 6,
                    listX2 - 1,
                    listY1,
                    listY2,
                    listItems().size(),
                    maxRows,
                    scroll,
                    0);
            if (ns >= 0) {
                scroll = ns;
                return true;
            }
        }
        if (impactOpen) {
            if (mx >= okX1 && mx <= okX2 && my >= okY1 && my <= okY2) {
                impactOpen = false;
                sendCrud(pendingKind, pendingAction, pendingPayload);
                return true;
            }
            if (mx >= noX1 && mx <= noX2 && my >= noY1 && my <= noY2) {
                impactOpen = false;
                return true;
            }
            return true;
        }
        if (radioModalOpen) {
            radioModalClick(mx, my, button); // 无线电编辑弹窗：命中按钮/输入框处理，未命中也不放行到底层
            return true;
        }
        if (seqModalOpen) {
            sequenceModalClick(mx, my, button); // 流程编辑器弹窗：命中按钮/输入框处理，未命中也不放行到底层
            return true;
        }
        if (spawnModalOpen) {
            spawnModalClick(mx, my); // 命中弹窗按钮则处理；未命中也不放行到底层
            return true;
        }
        // 关系页签阵营下拉/注入：弹层会盖住输入框等 widget，须在 super（widget 分发）之前命中
        if (tab == TAB_RELATION && relationTab().mouseClickedOverlay((int) mx, (int) my, button)) {
            return true;
        }
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        if (mx >= closeX1 && mx <= closeX2 && my >= closeY1 && my <= closeY2) {
            onClose();
            return true;
        }
        // 页签滚动条：点拇指拖动 / 点轨道跳转
        if (maxTabScroll > 0
                && my >= py1 + TAB_SB_Y
                && my <= py1 + TAB_SB_Y + TAB_SB_H
                && mx >= px1 + 12
                && mx <= px2 - 12) {
            int sbX1 = px1 + 12;
            int sbX2 = px2 - 12;
            int travel = sbX2 - sbX1 - tabThumbW;
            if (mx >= tabThumbX1 && mx <= tabThumbX1 + tabThumbW) {
                tabDrag = true;
                tabDragStartX = (int) mx;
                tabDragStartScroll = tabScroll;
            } else {
                int target = travel <= 0 ? 0 : (int) ((long) (mx - sbX1 - tabThumbW / 2) * maxTabScroll / travel);
                tabScroll = Math.max(0, Math.min(target, maxTabScroll));
                tabDrag = true;
                tabDragStartX = (int) mx;
                tabDragStartScroll = tabScroll;
                rebuild();
            }
            return true;
        }
        for (int i = 0; i < TABS.length; i++) {
            int[] b = rowBounds.get(i);
            if (mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3]) {
                tab = i;
                scroll = 0;
                selProfId = "";
                selFactionId = "";
                selSelId = "";
                selLimitId = "";
                rebuild();
                return true;
            }
        }
        if (tab == TAB_XP) {
            if (rulesTab().mouseClicked((int) mx, (int) my, button)) {
                return true;
            }
        }
        if (tab == TAB_RELATION) {
            if (relationTab().mouseClicked((int) mx, (int) my, button)) {
                return true;
            }
        }
        if (!ClientCharacterState.isAdmin() && tab != TAB_SETTINGS) {
            notice = Component.translatable("ccnr_rp.gui.admin.no_perm").getString();
            return true;
        }
        for (int i = TABS.length; i < rowBounds.size(); i++) {
            int[] b = rowBounds.get(i);
            if (mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3]) {
                if (tab == TAB_SETTINGS) {
                    if (!ClientCharacterState.isAdmin()) {
                        notice = Component.translatable("ccnr_rp.gui.admin.no_perm")
                                .getString();
                        return true;
                    }
                    // 开关行（settings.json 全部键，程序化）：绝对下标存在 b[4]（含滚动偏移）
                    java.util.List<String> switches = ClientCharacterState.settingKeys();
                    int swIdx = b.length > 4 ? b[4] : -1;
                    if (swIdx >= 0
                            && swIdx < switches.size()
                            && "bool".equals(com.ccnrcom.rp.config.ManagerSettings.type(switches.get(swIdx)))) {
                        String key = switches.get(swIdx);
                        RpChannels.sendToServer(new RpPackets.ManagerSetC2S(key, String.valueOf(!value(key))));
                    }
                } else {
                    JsonObject item = visibleItem(i - TABS.length);
                    if (item != null) {
                        selectItem(item);
                    }
                }
                return true;
            }
        }
        return false;
    }

    /** 滚动条拖拽：按住游标移动即滚动；无线电弹窗打开时优先处理句子拖拽调序。 */
    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        // 无线电弹窗句子拖拽调序（优先于其它滚动条拖拽）
        if (radioModalOpen && radioDragIdx >= 0) {
            int target = radioDragTarget(my);
            if (target != radioDragIdx) {
                moveRadioLine(radioDragIdx, target);
            }
            return true;
        }
        if (tabDrag && maxTabScroll > 0) {
            int sbX1 = px1 + 12;
            int sbX2 = px2 - 12;
            int travel = sbX2 - sbX1 - tabThumbW;
            if (travel > 0) {
                int ns = tabDragStartScroll + (int) ((mx - tabDragStartX) * maxTabScroll / travel);
                tabScroll = Math.max(0, Math.min(ns, maxTabScroll));
                rebuild();
            }
            return true;
        }
        if (tab == TAB_SETTINGS) {
            int ns = RpScrollbar.dragV((int) my);
            if (ns >= 0 && RpScrollbar.dragId() == 7) {
                settingsScroll = (int) Math.max(
                        0,
                        Math.min(
                                ns,
                                Math.max(
                                        0,
                                        com.ccnrcom.rp.config.CCNRRPConfig.keys()
                                                        .size()
                                                - settingsMaxVisible())));
                rebuild();
                return true;
            }
            return super.mouseDragged(mx, my, button, dx, dy);
        }
        int ns = RpScrollbar.dragV((int) my);
        if (ns >= 0) {
            scroll = ns;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (radioModalOpen && radioDragIdx >= 0) {
            radioDragIdx = -1; // 结束句子拖拽调序
            return true;
        }
        tabDrag = false;
        RpScrollbar.endDrag();
        return super.mouseReleased(mx, my, button);
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
        editedSequence = null; // 切换条目：丢弃未保存的流程编辑结果（避免串到其他条目）
        if (tab == TAB_PROFESSION) {
            selectProfession(item);
            return;
        }
        if (tab == TAB_FACTION) {
            selectFaction(item);
            return;
        }
        if (tab == TAB_LIMITS) {
            selectLimit(item);
            return;
        }
        selSelId = str(item, "id");
        if (tab == TAB_EVENT) {
            evState = !item.has("enabled") || item.get("enabled").getAsBoolean();
        } else if (tab == TAB_WAVE) {
            String mode = str(item, "mode");
            // 兼容旧版持久化的 "RECRUIT"：映射到 RESURRECTION 索引，避免重存时静默变 SELF_DEPLOY
            if ("RECRUIT".equalsIgnoreCase(mode)) {
                mode = "RESURRECTION";
            }
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
        rebuild();
    }

    private void selectFaction(JsonObject f) {
        selFactionId = str(f, "id");
        syncFactionIconTier(f);
        rebuild();
    }

    /** 从选中阵营同步图标/等级到表单（选中/刷新后均调用，防保存时用默认值覆盖原数据）。 */
    private void syncFactionIconTier(JsonObject f) {
        String icon = str(f, "icon");
        java.util.List<String> opts = iconOptions();
        for (int i = 0; i < opts.size(); i++) {
            if (opts.get(i).equals(icon)) {
                iconIdx = i;
                break;
            }
        }
        tierIdx = Math.max(0, Math.min(2, tierOf(f) - 1));
    }

    private void selectLimit(JsonObject r) {
        selLimitId = str(r, "id");
        String type = str(r, "type", "").toUpperCase(java.util.Locale.ROOT);
        for (int i = 0; i < LIMIT_TYPES.length; i++) {
            if (LIMIT_TYPES[i].equals(type)) {
                limitTypeIdx = i;
                break;
            }
        }
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
        // 页签栏过窄时：滚轮在页签条/滚动条区域横向滚动
        if (maxTabScroll > 0 && mouseY >= py1 + 42 && mouseY <= py1 + TAB_SB_Y + TAB_SB_H) {
            tabScroll = Math.max(0, Math.min(tabScroll - (int) (delta * 6), maxTabScroll));
            rebuild();
            return true;
        }
        if (seqModalOpen) {
            // 流程编辑器：仅步骤列表区滚动
            if (mouseY >= sqListY1 && mouseY <= sqListY2) {
                seqScroll = (int)
                        Math.max(0, Math.min(seqScroll - delta / 8, Math.max(0, seqSteps.size() - seqMaxVisible())));
            }
            return true;
        }
        if (radioModalOpen) {
            // 无线电编辑器：句子列表区滚动
            if (mouseY >= rdY1 + 60 && mouseY <= rdY2 - 24) {
                int maxVis = Math.max(1, (rdY2 - 24 - rdY1 - 60) / 22);
                radioScroll =
                        (int) Math.max(0, Math.min(radioScroll - delta / 8, Math.max(0, radioLines.size() - maxVis)));
            }
            return true;
        }
        if (impactOpen || spawnModalOpen) {
            return true; // 弹窗打开时不滚动底层列表
        }
        if (tab == TAB_XP) {
            rulesTab().mouseScrolled((int) mouseX, (int) mouseY, delta);
            return true;
        }
        if (tab == TAB_RELATION) {
            relationTab().mouseScrolled((int) mouseX, (int) mouseY, delta);
            return true;
        }
        if (tab == TAB_PROFESSION) {
            scroll = (int) Math.max(0, scroll - delta / 8);
            rebuild();
        } else if (tab == TAB_SETTINGS) {
            int max = Math.max(0, settingsRows() - settingsMaxVisible());
            settingsScroll = (int) Math.max(0, Math.min(settingsScroll - delta, max));
            rebuild();
        }
        return true;
    }

    /** 设置区可见行数（开关 + 数值统一滚动）。 */
    private int settingsMaxVisible() {
        int yMax = py2 - 70;
        return Math.max(1, (yMax - settingsYTop()) / 30);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Esc：先关弹窗返回上层表单（影响确认/部署点/无线电/流程编辑器），而不是关闭整个管理面板
        if (impactOpen && keyCode == 256) {
            impactOpen = false; // Esc = 取消（等同「否」），不执行 CRUD
            return true;
        }
        if (spawnModalOpen && keyCode == 256) {
            spawnModalOpen = false;
            return true;
        }
        if (radioModalOpen && keyCode == 256) {
            closeRadioModal();
            return true;
        }
        if (seqModalOpen && keyCode == 256) {
            closeSequenceModal();
            return true;
        }
        if (tab == TAB_XP && rulesTab().keyPressed(keyCode, scanCode, modifiers)) {
            return true; // 经验规则页签：事件补全候选上/下/回车/Esc
        }
        if (tab == TAB_RELATION && relationTab().keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (!camSugItems.isEmpty() && camSugBox != null && camSugBox.isFocused()) {
            if (keyCode == 264) { // Down
                camSugIdx = (camSugIdx + 1) % camSugItems.size();
                return true;
            }
            if (keyCode == 265) { // Up
                camSugIdx = (camSugIdx - 1 + camSugItems.size()) % camSugItems.size();
                return true;
            }
            if (keyCode == 257 || keyCode == 335) { // Enter / Numpad Enter
                if (camSugIdx >= 0 && camSugIdx < camSugItems.size()) {
                    camSugBox.setValue(camSugItems.get(camSugIdx));
                }
                camSugIdx = -1;
                return true;
            }
            if (keyCode == 256) { // Esc
                camSugIdx = -1;
                return true;
            }
        }
        if (!musicSugItems.isEmpty() && musicBox != null && musicBox.isFocused()) {
            if (keyCode == 264) { // Down
                musicSugIdx = (musicSugIdx + 1) % musicSugItems.size();
                return true;
            }
            if (keyCode == 265) { // Up
                musicSugIdx = (musicSugIdx - 1 + musicSugItems.size()) % musicSugItems.size();
                return true;
            }
            if (keyCode == 257 || keyCode == 335) { // Enter / Numpad Enter
                if (musicSugIdx >= 0 && musicSugIdx < musicSugItems.size()) {
                    musicBox.setValue(musicSugItems.get(musicSugIdx));
                }
                musicSugIdx = -1;
                return true;
            }
            if (keyCode == 256) { // Esc
                musicSugIdx = -1;
                return true;
            }
        }
        if (!limitTargetSugItems.isEmpty() && nameBox != null && nameBox.isFocused()) {
            if (keyCode == 264) { // Down
                limitTargetSugIdx = (limitTargetSugIdx + 1) % limitTargetSugItems.size();
                return true;
            }
            if (keyCode == 265) { // Up
                limitTargetSugIdx = (limitTargetSugIdx - 1 + limitTargetSugItems.size()) % limitTargetSugItems.size();
                return true;
            }
            if (keyCode == 257 || keyCode == 335) { // Enter / Numpad Enter
                if (limitTargetSugIdx >= 0 && limitTargetSugIdx < limitTargetSugItems.size()) {
                    nameBox.setValue(sugTargetId(limitTargetSugItems.get(limitTargetSugIdx)));
                }
                limitTargetSugIdx = -1;
                return true;
            }
            if (keyCode == 256) { // Esc
                limitTargetSugIdx = -1;
                return true;
            }
        }
        if (!idSugItems.isEmpty() && idSugBox != null && idSugBox.isFocused()) {
            if (keyCode == 264) { // Down
                idSugIdx = (idSugIdx + 1) % idSugItems.size();
                return true;
            }
            if (keyCode == 265) { // Up
                idSugIdx = (idSugIdx - 1 + idSugItems.size()) % idSugItems.size();
                return true;
            }
            if (keyCode == 257 || keyCode == 335) { // Enter / Numpad Enter
                if (idSugIdx >= 0 && idSugIdx < idSugItems.size()) {
                    idSugBox.setValue(applyIdSug(idSugBox.getValue(), sugTargetId(idSugItems.get(idSugIdx))));
                }
                idSugIdx = -1;
                return true;
            }
            if (keyCode == 256) { // Esc
                idSugIdx = -1;
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ---------- 渲染 ----------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        // 模态（弹窗）打开时，仅保留深色背景 + 弹窗本身，彻底隐藏下层管理界面
        boolean modal = impactOpen || spawnModalOpen || seqModalOpen || radioModalOpen;
        if (!modal) {
            RpTheme.terminalPanel(g, px1, py1, px2, py2, RpTheme.RADIUS_LARGE);
            g.drawString(
                    font, title.getString().toUpperCase(java.util.Locale.ROOT), px1 + 12, py1 + 8, RpTheme.CYAN, true);
            // 素版：不再绘制黄色「● ADMIN」徽章；非管理员保留红色无权限提示
            if (!ClientCharacterState.isAdmin()) {
                g.drawString(
                        font,
                        "● "
                                + Component.translatable("ccnr_rp.gui.admin.no_perm")
                                        .getString(),
                        px1 + 12 + font.width(title.getString()) + 14,
                        py1 + 10,
                        RpTheme.RED,
                        true);
            }
            boolean hover = mouseX >= closeX1 && mouseX <= closeX2 && mouseY >= closeY1 && mouseY <= closeY2;
            if (hover) {
                g.fill(closeX1 - 2, closeY1 - 1, closeX2 + 2, closeY2 + 1, 0xE66F1613);
            }
            g.drawString(
                    font,
                    "X",
                    (closeX1 + closeX2) / 2 - 2,
                    closeY1 + 4,
                    hover ? 0xFFFFFFFF : RpTheme.TEXT_SECONDARY,
                    true);
            g.fill(px1 + 8, py1 + 26, px2 - 8, py1 + 27, RpTheme.CYAN_DIM);

            // 裁剪到页签区：滚动时被推出面板边界的页签不画出界
            g.enableScissor(px1 + 8, py1 + 40, px2 - 8, py1 + 66);
            for (int i = 0; i < TABS.length; i++) {
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
            g.disableScissor();

            // 页签过窄时：底部横向滚动条（可拖拽），仅溢出时显示
            if (maxTabScroll > 0) {
                int avail = px2 - px1 - 24;
                int sbX1 = px1 + 12;
                int sbX2 = px2 - 12;
                int sbY = py1 + TAB_SB_Y;
                g.fill(sbX1, sbY, sbX2, sbY + TAB_SB_H, 0x24FFFFFF); // 轨道
                int total = maxTabScroll + avail;
                tabThumbW = Math.max(24, (sbX2 - sbX1) * avail / total);
                int travel = sbX2 - sbX1 - tabThumbW;
                tabThumbX1 = travel <= 0 ? sbX1 : sbX1 + (int) ((long) travel * tabScroll / maxTabScroll);
                g.fill(tabThumbX1, sbY, tabThumbX1 + tabThumbW, sbY + TAB_SB_H, RpTheme.PANEL_BORDER_BRIGHT);
            }

            if (tab == TAB_SETTINGS) {
                renderSettings(g, mouseX, mouseY);
            } else if (tab == TAB_XP) {
                rulesTab().render(g, mouseX, mouseY);
            } else if (tab == TAB_RELATION) {
                relationTab().render(g, mouseX, mouseY);
            } else {
                renderListTab(g, mouseX, mouseY);
            }
            renderFieldLabels(g);
            if (!notice.isBlank()) {
                g.drawCenteredString(font, "[ 系统 ] " + notice, (px1 + px2) / 2, py2 - 46, RpTheme.RED_LINE);
            }
            super.render(g, mouseX, mouseY, partialTick);
            // 输入补全框置顶渲染：super.render 会绘制所有 widget（输入框/按钮），
            // 若补全框先画会被盖住；这里在 widget 之后绘制，保证下拉框始终在最上层可点可看。
            renderMusicSuggestions(g);
            renderCamSceneSuggestions(g);
            renderLimitTargetSuggestions(g);
            resolveIdSugSource();
            renderIdSuggestions(g);
            // 关系页签阵营下拉弹层：同样需盖住输入框，放最后绘制
            if (tab == TAB_RELATION) {
                relationTab().renderOverlay(g, mouseX, mouseY);
            }
        }
        if (impactOpen) {
            renderImpactModal(g, mouseX, mouseY);
        }
        if (spawnModalOpen) {
            renderSpawnModal(g, mouseX, mouseY);
        }
        if (radioModalOpen) {
            renderRadioModal(g, mouseX, mouseY, partialTick);
        }
        if (seqModalOpen) {
            renderSequenceModal(g, mouseX, mouseY, partialTick);
            // 序列弹窗内 id 输入框（波 ID/职业/阵营）补全：在弹窗内容之后绘制，置顶于弹窗控件之上
            resolveIdSugSource();
            renderIdSuggestions(g);
        }
    }

    /** 音乐补全提示：职业/阵营表单的音乐框聚焦时按输入过滤已上传音乐列表并绘制下拉。 */
    private void renderMusicSuggestions(GuiGraphics g) {
        musicSugBounds.clear();
        boolean form = tab == TAB_PROFESSION || tab == TAB_FACTION;
        if (!form || musicBox == null || !musicBox.isFocused()) {
            musicSugItems = new ArrayList<>();
            musicSugIdx = -1;
            lastMusicQuery = null;
            return;
        }
        String q = musicBox.getValue() == null ? "" : musicBox.getValue().toLowerCase(java.util.Locale.ROOT);
        if (!q.equals(lastMusicQuery)) {
            lastMusicQuery = q;
            musicSugIdx = -1;
        }
        musicSugItems = new ArrayList<>();
        for (String m : ClientCharacterState.musicList()) {
            if (q.isBlank() || m.toLowerCase(java.util.Locale.ROOT).contains(q)) {
                musicSugItems.add(m);
            }
        }
        if (musicSugIdx >= musicSugItems.size()) {
            musicSugIdx = musicSugItems.size() - 1;
        }
        if (musicSugItems.isEmpty()) {
            return;
        }
        int sx = musicBox.getX();
        int sy = musicBox.getY() + 20;
        int sw = musicBox.getWidth();
        int n = Math.min(6, musicSugItems.size());
        g.fill(sx - 1, sy - 1, sx + sw + 1, sy + n * 12 + 1, 0xE0323232);
        g.fill(sx - 1, sy - 1, sx + sw + 1, sy, 0xFF5F5F5F);
        for (int i = 0; i < n; i++) {
            int yy = sy + i * 12;
            g.drawString(font, musicSugItems.get(i), sx + 4, yy + 2, RpTheme.CYAN, false);
            musicSugBounds.add(new int[] {sx, yy, sx + sw, yy + 12});
        }
    }

    /** CMDCam 场景补全提示：场景输入框（camSceneBox）聚焦时按输入过滤服务端已保存场景名并绘制下拉。 */
    private void renderCamSceneSuggestions(GuiGraphics g) {
        camSugBounds.clear();
        camSugBox = null;
        boolean form = tab == TAB_FACTION || tab == TAB_PROFESSION || tab == TAB_WAVE;
        if (!form || camSceneBox == null || !camSceneBox.isFocused()) {
            camSugItems = new ArrayList<>();
            camSugIdx = -1;
            lastCamQuery = null;
            return;
        }
        String q = camSceneBox.getValue() == null ? "" : camSceneBox.getValue().toLowerCase(java.util.Locale.ROOT);
        if (!q.equals(lastCamQuery)) {
            lastCamQuery = q;
            camSugIdx = -1;
        }
        camSugItems = new ArrayList<>();
        for (String s : ClientCharacterState.camScenes()) {
            if (q.isBlank() || s.toLowerCase(java.util.Locale.ROOT).contains(q)) {
                camSugItems.add(s);
            }
        }
        if (camSugIdx >= camSugItems.size()) {
            camSugIdx = camSugItems.size() - 1;
        }
        if (camSugItems.isEmpty()) {
            return;
        }
        camSugBox = camSceneBox;
        int sx = camSceneBox.getX();
        int sy = camSceneBox.getY() + 20;
        int sw = camSceneBox.getWidth();
        int n = Math.min(6, camSugItems.size());
        g.fill(sx - 1, sy - 1, sx + sw + 1, sy + n * 12 + 1, 0xE0323232);
        g.fill(sx - 1, sy - 1, sx + sw + 1, sy, 0xFF5F5F5F);
        for (int i = 0; i < n; i++) {
            int yy = sy + i * 12;
            g.drawString(font, camSugItems.get(i), sx + 4, yy + 2, RpTheme.CYAN, false);
            camSugBounds.add(new int[] {sx, yy, sx + sw, yy + 12});
        }
    }

    /** 限制目标补全：限制页「目标」输入框（nameBox）聚焦时按当前类型（FACTION/PROFESSION）补全对应 id；GLOBAL 无目标不触发。 */
    private void renderLimitTargetSuggestions(GuiGraphics g) {
        limitTargetSugBounds.clear();
        boolean form = tab == TAB_LIMITS && nameBox != null && nameBox.isFocused();
        String type = LIMIT_TYPES[limitTypeIdx];
        if (!form || "GLOBAL".equals(type)) {
            limitTargetSugItems = new ArrayList<>();
            limitTargetSugIdx = -1;
            lastLimitTargetQuery = null;
            return;
        }
        String q = nameBox.getValue() == null ? "" : nameBox.getValue().toLowerCase(java.util.Locale.ROOT);
        if (!q.equals(lastLimitTargetQuery)) {
            lastLimitTargetQuery = q;
            limitTargetSugIdx = -1;
        }
        limitTargetSugItems = new ArrayList<>();
        if ("FACTION".equals(type)) {
            for (JsonObject f : ClientCharacterState.factions()) {
                String id = str(f, "id");
                String label = str(f, "name").isBlank() ? id : str(f, "name") + "(" + id + ")";
                if (q.isBlank()
                        || id.toLowerCase(java.util.Locale.ROOT).contains(q)
                        || str(f, "name").toLowerCase(java.util.Locale.ROOT).contains(q)) {
                    limitTargetSugItems.add(label);
                }
            }
        } else {
            for (JsonObject p : ClientCharacterState.professions()) {
                String id = str(p, "id");
                String label = str(p, "name").isBlank() ? id : str(p, "name") + "(" + id + ")";
                if (q.isBlank()
                        || id.toLowerCase(java.util.Locale.ROOT).contains(q)
                        || str(p, "name").toLowerCase(java.util.Locale.ROOT).contains(q)) {
                    limitTargetSugItems.add(label);
                }
            }
        }
        if (limitTargetSugIdx >= limitTargetSugItems.size()) {
            limitTargetSugIdx = limitTargetSugItems.size() - 1;
        }
        if (limitTargetSugItems.isEmpty()) {
            return;
        }
        int sx = nameBox.getX();
        int sy = nameBox.getY() + 20;
        int sw = nameBox.getWidth();
        int n = Math.min(6, limitTargetSugItems.size());
        g.fill(sx - 1, sy - 1, sx + sw + 1, sy + n * 12 + 1, 0xE0323232);
        g.fill(sx - 1, sy - 1, sx + sw + 1, sy, 0xFF5F5F5F);
        for (int i = 0; i < n; i++) {
            int yy = sy + i * 12;
            g.drawString(font, limitTargetSugItems.get(i), sx + 4, yy + 2, RpTheme.CYAN, false);
            limitTargetSugBounds.add(new int[] {sx, yy, sx + sw, yy + 12});
        }
    }

    /** 通用 id 补全数据源：按枚举返回候选（显示名(id) 形式，含名称与 id 双匹配）。 */
    private List<String> idSugCandidates(SugSource src) {
        List<String> out = new ArrayList<>();
        if (src == SugSource.PROFESSION) {
            for (JsonObject p : ClientCharacterState.professions()) {
                String id = str(p, "id");
                out.add(str(p, "name").isBlank() ? id : str(p, "name") + "(" + id + ")");
            }
        } else if (src == SugSource.FACTION) {
            for (JsonObject f : ClientCharacterState.factions()) {
                String id = str(f, "id");
                out.add(str(f, "name").isBlank() ? id : str(f, "name") + "(" + id + ")");
            }
        } else if (src == SugSource.WAVE) {
            for (JsonObject w : ClientCharacterState.managerWaves()) {
                out.add(str(w, "id"));
            }
        } else if (src == SugSource.DIMENSION) {
            out.add("minecraft:overworld");
            out.add("minecraft:the_nether");
            out.add("minecraft:the_end");
        }
        return out;
    }

    /**
     * 推断通用 id 补全源（每帧渲染前调用）：
     * 主表单按 tab + 聚焦框识别（刷新波：维度/职业ID/阵营ID；设置页：首次入服职业）；
     * 序列弹窗打开时按 seqBoxes 键识别（WAVE=波ID / FORCE_PICK professions=职业 / faction=阵营）。
     */
    private void resolveIdSugSource() {
        idSugSource = SugSource.NONE;
        idSugBox = null;
        if (seqModalOpen) {
            for (java.util.Map.Entry<String, net.minecraft.client.gui.components.EditBox> e : seqBoxes.entrySet()) {
                if (e.getValue().isFocused()) {
                    String k = e.getKey();
                    if ("wave".equals(k)) {
                        idSugSource = SugSource.WAVE;
                    } else if ("professions".equals(k)) {
                        idSugSource = SugSource.PROFESSION;
                    } else if ("faction".equals(k)) {
                        idSugSource = SugSource.FACTION;
                    }
                    idSugBox = e.getValue();
                    return;
                }
            }
            return;
        }
        if (tab == TAB_WAVE) {
            if (nameBox != null && nameBox.isFocused()) {
                idSugSource = SugSource.DIMENSION;
                idSugBox = nameBox;
            } else if (descBox != null && descBox.isFocused()) {
                idSugSource = SugSource.PROFESSION;
                idSugBox = descBox;
            } else if (musicBox != null && musicBox.isFocused()) {
                idSugSource = SugSource.FACTION;
                idSugBox = musicBox;
            }
        } else if (tab == TAB_SETTINGS) {
            // 首次入服自动部署职业（字符串设置项）：补全职业 id
            net.minecraft.client.gui.components.EditBox fj = cfgBoxes.get("firstJoinProfession");
            if (fj != null && fj.isFocused()) {
                idSugSource = SugSource.PROFESSION;
                idSugBox = fj;
            }
        }
    }

    /** 通用 id 补全渲染：idSugBox（主表单或序列弹窗的 id 类输入框）聚焦时按数据源过滤绘制下拉。 */
    private void renderIdSuggestions(GuiGraphics g) {
        idSugBounds.clear();
        if (idSugSource == SugSource.NONE || idSugBox == null || !idSugBox.isFocused()) {
            idSugItems = new ArrayList<>();
            idSugIdx = -1;
            lastIdSugQuery = null;
            return;
        }
        String q = idSugBox.getValue() == null ? "" : idSugBox.getValue().toLowerCase(java.util.Locale.ROOT);
        if (!q.equals(lastIdSugQuery)) {
            lastIdSugQuery = q;
            idSugIdx = -1;
        }
        idSugItems = new ArrayList<>();
        for (String cand : idSugCandidates(idSugSource)) {
            if (q.isBlank() || cand.toLowerCase(java.util.Locale.ROOT).contains(q)) {
                idSugItems.add(cand);
            }
        }
        if (idSugIdx >= idSugItems.size()) {
            idSugIdx = idSugItems.size() - 1;
        }
        if (idSugItems.isEmpty()) {
            return;
        }
        int sx = idSugBox.getX();
        int sy = idSugBox.getY() + 20;
        int sw = idSugBox.getWidth();
        int n = Math.min(6, idSugItems.size());
        g.fill(sx - 1, sy - 1, sx + sw + 1, sy + n * 12 + 1, 0xE0323232);
        g.fill(sx - 1, sy - 1, sx + sw + 1, sy, 0xFF5F5F5F);
        for (int i = 0; i < n; i++) {
            int yy = sy + i * 12;
            g.drawString(font, idSugItems.get(i), sx + 4, yy + 2, RpTheme.CYAN, false);
            idSugBounds.add(new int[] {sx, yy, sx + sw, yy + 12});
        }
    }

    /** 影响确认弹窗：显示波及清单 + 确认/取消。 */
    private void renderImpactModal(GuiGraphics g, int mouseX, int mouseY) {
        g.fill(0, 0, width, height, 0xAA000000);
        int w = Math.min(520, width - 80);
        int lines = Math.max(1, impactLines.size());
        int h = 96 + lines * 12 + 40;
        mX1 = (width - w) / 2;
        mY1 = (height - h) / 2;
        mX2 = mX1 + w;
        mY2 = mY1 + h;
        RpTheme.terminalPanel(g, mX1, mY1, mX2, mY2, RpTheme.RADIUS_LARGE);
        g.drawString(
                font,
                Component.translatable("ccnr_rp.gui.admin.impact.title")
                        .getString()
                        .toUpperCase(java.util.Locale.ROOT),
                mX1 + 14,
                mY1 + 10,
                RpTheme.RED_LINE,
                true);
        g.fill(mX1 + 8, mY1 + 28, mX2 - 8, mY1 + 29, RpTheme.CYAN_DIM);
        int y = mY1 + 38;
        g.drawString(
                font,
                Component.translatable("ccnr_rp.gui.admin.impact.hint").getString(),
                mX1 + 14,
                y,
                RpTheme.TEXT_DIM);
        y += 14;
        for (String line : impactLines) {
            g.drawString(font, "• " + line, mX1 + 18, y, RpTheme.RED_LINE);
            y += 12;
        }
        int bw = Math.max(90, (w - 48) / 2);
        int by = mY2 - 34;
        okX1 = mX1 + 14;
        okY1 = by;
        okX2 = okX1 + bw;
        okY2 = by + 20;
        noX1 = mX2 - 14 - bw;
        noY1 = by;
        noX2 = mX2 - 14;
        noY2 = by + 20;
        boolean hOk = mouseX >= okX1 && mouseX <= okX2 && mouseY >= okY1 && mouseY <= okY2;
        boolean hNo = mouseX >= noX1 && mouseX <= noX2 && mouseY >= noY1 && mouseY <= noY2;
        RpButton.draw(
                g,
                okX1,
                okY1,
                okX2,
                okY2,
                Component.translatable("ccnr_rp.gui.admin.impact.confirm").getString(),
                hOk ? RpTheme.RED : RpTheme.CYAN,
                true);
        RpButton.draw(
                g,
                noX1,
                noY1,
                noX2,
                noY2,
                Component.translatable("ccnr_rp.gui.admin.impact.cancel").getString(),
                hNo ? RpTheme.RED : RpTheme.TEXT_SECONDARY,
                false);
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
            int[] b = rowBounds.get(TABS.length + i);
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
            if (tab == TAB_FACTION) {
                // 阵营行：左侧徽章 + 文字右移
                RpIcons.factionBadge(g, b[0] + 16, b[1] + 11, 8, item, sel);
                fx = b[0] + 28;
            }
            String main = str(item, "name").isBlank() ? str(item, "id") : str(item, "name");
            if (tab == TAB_LIMITS) {
                // 限制行：主文案 = 类型 + 目标；右侧上限
                main = str(item, "type", "") + (str(item, "target", "").isBlank() ? "" : " / " + str(item, "target"));
            }
            g.drawString(font, main, fx, b[1] + 1, sel ? 0xFFFFFFFF : RpTheme.TEXT_PRIMARY, true);
            g.drawString(font, str(item, "id"), fx, b[1] + 11, sel ? 0xFFFFFFFF : RpTheme.TEXT_DIM, true);
            if (tab == TAB_LIMITS) {
                String lim = "上限 " + num(item, "limit", 0);
                g.drawString(
                        font, lim, b[2] - 8 - font.width(lim), b[1] + 1, sel ? 0xFFFFFFFF : RpTheme.STATUS_ALIVE, true);
            }
            // 职业缺装备设定 → 右侧小标记
            if (tab == TAB_PROFESSION && isProfessionLoadoutEmpty(item)) {
                String warn = "缺装备";
                g.drawString(font, warn, b[2] - 8 - font.width(warn), b[1] + 11, 0xFFFF8C42, true);
            }
        }
        RpScrollbar.draw(g, listX2 - 6, listY1, listY2, items.size(), maxVisible, off);
    }

    /** 职业 loadout 是否缺装备设定（无 loadout 或 inventory/armor/offhand 全空）。 */
    private static boolean isProfessionLoadoutEmpty(JsonObject prof) {
        if (prof == null || !prof.has("loadout") || !prof.get("loadout").isJsonObject()) {
            return true;
        }
        JsonObject lo = prof.getAsJsonObject("loadout");
        boolean invEmpty = !lo.has("inventory")
                || !lo.get("inventory").isJsonArray()
                || lo.getAsJsonArray("inventory").isEmpty();
        boolean armEmpty = !lo.has("armor")
                || !lo.get("armor").isJsonArray()
                || lo.getAsJsonArray("armor").isEmpty();
        boolean ohEmpty = !lo.has("offhand")
                || !lo.get("offhand").isJsonObject()
                || lo.getAsJsonObject("offhand").size() == 0;
        return invEmpty && armEmpty && ohEmpty;
    }

    private String currentSelId() {
        return switch (tab) {
            case TAB_PROFESSION -> selProfId;
            case TAB_FACTION -> selFactionId;
            case TAB_LIMITS -> selLimitId;
            default -> selSelId;
        };
    }

    /** 设置内容区起点 y（开关 + 数值统一滚动，共用起点）。 */
    private int settingsYTop() {
        return py1 + 80;
    }

    /** 设置总行数（开关 + serverconfig 数值）。 */
    private int settingsRows() {
        return ClientCharacterState.settingKeys().size()
                + com.ccnrcom.rp.config.CCNRRPConfig.keys().size();
    }

    /** 设置内容右边界（滚动条 px2-14 左侧留 4px 间隙，防横向溢出）。 */
    private int settingsRight() {
        return px2 - 18;
    }

    private void renderSettings(GuiGraphics g, int mouseX, int mouseY) {
        // 设置标签 = 开关（可点切换）+ serverconfig 数值，全部统一滚动（一个滚动条，行高 30）。
        int x = px1 + 12;
        int w = settingsRight() - x;
        int yMax = py2 - 70;
        int total = settingsRows();
        int maxVisible = Math.max(1, (yMax - settingsYTop()) / 30);
        settingsScroll = Math.max(0, Math.min(settingsScroll, Math.max(0, total - maxVisible)));
        java.util.List<String> switches = ClientCharacterState.settingKeys();
        java.util.List<String> cfgKeys = com.ccnrcom.rp.config.CCNRRPConfig.keys();
        JsonObject cfg = ClientCharacterState.serverConfig();
        int y = settingsYTop();
        for (int i = settingsScroll; i < total; i++) {
            if (y + 22 > yMax) {
                break;
            }
            if (i < switches.size()) {
                // settings.json 行：bool=开关（右对齐），string=标签 + 输入框（输入框由 buildSettingsForm 生成）
                String key = switches.get(i);
                RpRoundRect.outlined(g, x, y, x + w, y + 22, 4f, RpTheme.PANEL_BORDER, RpTheme.PANEL_BG);
                if ("bool".equals(com.ccnrcom.rp.config.ManagerSettings.type(key))) {
                    boolean on = value(key);
                    int swX = settingsRight() - 50; // 46 宽开关 + 4px 右距，右对齐
                    drawSwitch(g, swX, y + 4, on);
                }
                g.drawString(font, settingLabel(key), x + 8, y + 6, RpTheme.TEXT_PRIMARY, true);
            } else {
                // serverconfig 数值行（标签 + 输入框；输入框由 buildSettingsForm 生成并定位）
                String key = cfgKeys.get(i - switches.size());
                RpRoundRect.outlined(g, x, y, x + w, y + 22, 4f, RpTheme.PANEL_BORDER, RpTheme.PANEL_BG);
                g.drawString(font, cfgLabel(key), x + 6, y + 6, RpTheme.TEXT_PRIMARY, true);
            }
            y += 30;
        }
        // 统一滚动条（覆盖开关 + 数值）
        RpScrollbar.draw(g, px2 - 14, settingsYTop(), yMax, total, maxVisible, settingsScroll);
    }

    /** 开关行标签（settings.json 键 → 语言包翻译键；缺省回退原始键）。 */
    private static String settingLabel(String key) {
        return switch (key) {
            case "forceObserving" -> Component.translatable("ccnr_rp.gui.admin.setting.force_observing")
                    .getString();
            case "openPanelOnJoin" -> Component.translatable("ccnr_rp.gui.admin.setting.open_panel")
                    .getString();
            case "forceRetain" -> Component.translatable("ccnr_rp.gui.admin.setting.force_retain")
                    .getString();
            case "hudEnabled" -> Component.translatable("ccnr_rp.gui.admin.setting.hud_enabled")
                    .getString();
            case "hudProfessionText" -> Component.translatable("ccnr_rp.gui.admin.setting.hud_profession")
                    .getString();
            case "hudFactionText" -> Component.translatable("ccnr_rp.gui.admin.setting.hud_faction")
                    .getString();
            case "hudHealthText" -> Component.translatable("ccnr_rp.gui.admin.setting.hud_health")
                    .getString();
            case "firstJoinAutoDeploy" -> Component.translatable("ccnr_rp.gui.admin.setting.first_join_deploy")
                    .getString();
            case "firstJoinProfession" -> Component.translatable("ccnr_rp.gui.admin.setting.first_join_profession")
                    .getString();
            default -> key;
        };
    }

    private static String cfgLabel(String key) {
        return switch (key) {
            case "deathCooldownMinutes" -> "死亡冷却(分钟)";
            case "offlineGraceSeconds" -> "离线判死宽限(秒)";
            case "offlinePollSeconds" -> "离线判死轮询(秒)";
            case "evalIntervalTicks" -> "事件求值间隔(tick)";
            case "pollTicks" -> "复活波轮询(tick)";
            case "deployDelayTicks" -> "部署延迟(tick)";
            case "dutyXpPerSecond" -> "值班XP/秒";
            case "taskDefaultXp" -> "任务默认XP";
            case "evacSafeXp" -> "安全撤离XP";
            case "evacDiedXp" -> "阵亡XP(可负)";
            case "evacObservingXp" -> "观察结束XP";
            case "evacStayBehindXp" -> "留守XP";
            case "base" -> "等级基数";
            case "pow" -> "等级指数";
            case "enabled" -> "头顶标签开关";
            case "badgeSize" -> "头顶标签徽章大小";
            case "offset" -> "头顶标签高度(格)";
            default -> key;
        };
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
                on ? 0xCC3F3F3F : 0xCC323232);
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
            int[] b = rowBounds.get(TABS.length + i);
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
            int[] b = rowBounds.get(TABS.length + i);
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
        if (parent != null) {
            Minecraft.getInstance().setScreen(parent); // 返回上层（K 面板）；Esc/✕ 不直接回游戏
        } else {
            super.onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
