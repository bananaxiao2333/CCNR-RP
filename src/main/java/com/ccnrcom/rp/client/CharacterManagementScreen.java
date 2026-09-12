/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.ccnrcom.rp.network.RpChannels;
import com.ccnrcom.rp.network.RpPackets;
import com.ccnrcom.rp.profession.ItemStackCodec;
import com.ccnrcom.rp.profession.ProfessionJson;
import com.ccnrcom.rp.status.CharacterStatus;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * K 面板 v3——「编制席位」征召终端（选角色/选职位与部署的唯一入口）。
 *
 * <p><b>结构取向（与横向滚动式「左导航｜中列表｜右预览」三栏布局彻底脱钩，见 docs/14 §5.6）：</b>
 * <ol>
 *   <li><b>顶部横向机构导轨</b>：机构过滤不再占用一整列，改成头部下方一条可横向滚动的芯片带；</li>
 *   <li><b>中部席位卡网格</b>：职位以「席位卡」多列铺开（每卡含序号码块、机构徽章、等级门控、
 *       在职/编制读数、紧凑装备图标条），而不是单列文本行；</li>
 *   <li><b>底部席位终端抽屉</b>：选中职位的身份读数 / 装备 / 画像 / 部署按钮横向排在底部一条
 *       抽屉里，随选中即时更新——不再有常驻右栏。</li>
 * </ol>
 * 信息层次仍为「机构 → 职位 → 详情 → 部署」，等级门控与在职上限的判定条件、服务端校验、
 * 网络包、语言键语义与旧版完全一致（纯客户端呈现层重构）。
 */
public class CharacterManagementScreen extends Screen {

    private static CharacterManagementScreen open;

    // ---------- 数据（服务端镜像，只读消费） ----------
    private final List<JsonObject> professions = new ArrayList<>();
    private final List<JsonObject> factionMeta = new ArrayList<>();

    // ---------- 交互状态 ----------
    private String selectedId = "";
    private String filterFaction = "";

    // ---------- 平滑滚动（像素单位 + 逐帧缓动）----------
    // 三处滚动区都保留"当前值 / 目标值"两档：滚轮与按键改目标值，渲染前按时间常数缓动逼近，
    // 于是内容是一格一格"滑"过去的（有中间态），而不是直接跳到下一格；拖滚动条则当前值直接跟随光标。
    /** 席位卡网格：当前/目标滚动量（px）。 */
    private float gridScroll, gridScrollTarget;
    /** 机构导轨：档位（首个可见芯片下标）+ 当前/目标滚动量（px）。 */
    private int railIndex;

    private float railScroll, railScrollTarget;
    /** 抽屉职业画像：当前/目标滚动量（px，文本行距 10px）；选中项变化时归零。 */
    private float profileScroll, profileScrollTarget;
    /** 上一帧时间戳（缓动按真实帧间隔计算，帧率高低手感一致）。 */
    private long lastFrameMs;
    /** 缓动时间常数（ms）：越小越跟手，越大越"飘"。 */
    private static final double SCROLL_TAU_MS = 55;
    /** 抽屉职业画像行距（px）。 */
    private static final int PROFILE_LINE_H = 10;

    private String notice = "";
    private long noticeUntil = 0;
    /** 重新部署确认弹窗（暂时隐藏，不触发）：非空=正在确认该职位；如需恢复，在场点部署时置 confirmDeployId。 */
    private String confirmDeployId = "";

    // ---------- 布局几何 ----------
    private int px1, py1, px2, py2;
    private int hdrY1, hdrY2;
    private int railY1, railY2;
    private int titleY;
    private int gridY1, gridY2;
    private int dockX1, dockY1, dockX2, dockY2;
    private int mgrX1, mgrY1, mgrX2, mgrY2;

    // ---------- 命中区（每次 relayout 重算） ----------
    /** {x1,y1,x2,y2, 机构下标(-1=全部)} */
    private final List<int[]> railBounds = new ArrayList<>();

    public CharacterManagementScreen() {
        super(Component.translatable("ccnr_rp.gui.character.title"));
    }

    public static void refreshIfOpen() {
        if (open != null && net.minecraft.client.Minecraft.getInstance() != null) {
            open.reloadData();
            open.rebuild();
        }
    }

    public static void closeIfOpen() {
        if (open != null) {
            open.onClose();
        }
    }

    @Override
    protected void init() {
        open = this;
        reloadData();
        px1 = 16;
        py1 = 16;
        px2 = width - 16;
        py2 = height - 16;
        hdrY1 = py1 + 6;
        hdrY2 = hdrY1 + 22;
        railY1 = hdrY2 + 6;
        railY2 = railY1 + 22;
        // 导轨下缘留出横向滚动条那一条（5px）+ 间隔，再进入「编制席位」小节标题行
        titleY = railY2 + 10;
        int bodyTop = titleY + 12 + 6;
        int bodyBottom = py2 - 10;
        int totalH = Math.max(40, bodyBottom - bodyTop);
        // 极矮窗口（GUI scale 很大）退化：收起机构导轨那一行，把高度让给席位卡与抽屉
        if (totalH < 140) {
            railY2 = railY1;
            titleY = railY2 + 2;
            bodyTop = titleY + 12 + 6;
            totalH = Math.max(40, bodyBottom - bodyTop);
        }
        // 席位终端抽屉：常态占下部 45%（下限 72 / 上限 126）；矮窗口下先保网格一行再收缩抽屉，
        // 极端矮窗口下抽屉不超过可用高度——任何情况下都不越出终端框。
        int dockH = Math.max(72, Math.min(126, totalH * 45 / 100));
        if (totalH - dockH < CARD_MIN_H + 8) {
            dockH = Math.max(40, totalH - CARD_MIN_H - 8);
        }
        dockH = Math.min(dockH, totalH);
        dockX1 = px1 + 8;
        dockX2 = px2 - 8;
        dockY2 = bodyBottom;
        gridY1 = bodyTop;
        // 网格只占**整数行**高度（不足一行时按一行压缩卡片），剩下的高度全部还给抽屉——
        // 否则网格底部会留出一条"什么都没有"的空白带，看起来就像多出来的空行。
        int availGrid = Math.max(CARD_MIN_H, bodyBottom - 8 - bodyTop - dockH);
        int rows = Math.max(1, Math.min(MAX_GRID_ROWS, (availGrid + GAP) / (CARD_H + GAP)));
        cardH = Math.min(CARD_H, Math.max(CARD_MIN_H, (availGrid - (rows - 1) * GAP) / rows));
        rowsVisible = rows;
        gridY2 = gridY1 + rows * cardH + (rows - 1) * GAP;
        dockY1 = Math.min(bodyBottom, gridY2 + 8);
        dockH = dockY2 - dockY1;
        String mgr = Component.translatable("ccnr_rp.gui.character.manage").getString();
        mgrX2 = px2 - 40;
        mgrX1 = mgrX2 - font.width(mgr) - 8;
        mgrY1 = hdrY1 - 1;
        mgrY2 = hdrY1 + 17;
        resetScroll();
        lastFrameMs = 0;
        rebuild();
    }

    private void reloadData() {
        professions.clear();
        professions.addAll(ClientCharacterState.professions());
        factionMeta.clear();
        factionMeta.addAll(ClientCharacterState.factions());
        if (filterFaction.isEmpty()
                || factionMeta.stream().noneMatch(f -> f.get("id").getAsString().equals(filterFaction))) {
            filterFaction = "";
        }
        if (selectedId.isEmpty()
                || professions.stream().noneMatch(p -> str(p, "id").equals(selectedId))) {
            selectedId = "";
        }
    }

    /**
     * 数据/选中变化时的完整重建：命中区 + 部署按钮 widget 一起重建。
     * 纯滚动位移（滚轮/拖滚动条/翻行）**不要**走这里——那些只调 {@link #relayout()}，
     * 否则每个鼠标事件都会 clearWidgets + new Button，拖起来就是"卡卡的"。
     */
    private void rebuild() {
        relayout();
        clearWidgets();
        buildDeployButton();
    }

    /** 只重算命中区与可见项缓存（不含 widget）：滚动/翻行等高频路径专用。 */
    private void relayout() {
        visible.clear();
        visible.addAll(computeVisible());
        railBounds.clear();
        buildRailBounds();
    }

    // ============================================================
    // 几何（网格 / 卡片 / 导轨）
    // ============================================================

    private static final int GAP = 6;
    /** 席位卡常态高度 / 最小高度（行数由 init() 定，卡片高度随之在最小~常态之间取值）。 */
    private static final int CARD_H = 62;

    private static final int CARD_MIN_H = 44;
    /** 一屏可见的最大行数（纯护栏：再高也够用；超出部分交给抽屉，不会出现空带）。 */
    private static final int MAX_GRID_ROWS = 12;

    /** 当前可见席位列数（init() 定，供 cardRect/rowCount 使用）。 */
    private int rowsVisible = 1;
    /** 当前席位卡高度（init() 定）。 */
    private int cardH = CARD_H;
    /** 当前过滤后的可见职位（relayout 时刷新；避免每个鼠标事件都重算一遍流式列表）。 */
    private final List<JsonObject> visible = new ArrayList<>();

    private int gridX1() {
        return px1 + 8;
    }

    private int gridX2() {
        return px2 - 8;
    }

    /**
     * 席位卡可用宽度：右侧恒定留 10px 滚动条槽，卡片因此永远不会被滚动条压住。
     * （若按"是否溢出"动态让位，会与 {@link #cols()} 形成递归，故常留。）
     */
    private int gridW() {
        return Math.max(40, gridX2() - gridX1() - 10);
    }

    private int gridH() {
        return Math.max(0, gridY2 - gridY1);
    }

    /** 机构导轨是否展开（极矮窗口下收起，见 {@link #init()}）。 */
    private boolean railShown() {
        return railY2 > railY1;
    }

    /** 席位卡列数：按宽度自适应（1~4 列），窄窗口退化为单列。 */
    private int cols() {
        return Math.max(1, Math.min(4, gridW() / 176));
    }

    private int cardW() {
        return (gridW() - GAP * (cols() - 1)) / cols();
    }

    private int rowCount() {
        return (visible.size() + cols() - 1) / cols();
    }

    /** 行距（卡片高 + 行间隔）。 */
    private float rowStride() {
        return cardH + GAP;
    }

    /** 席位卡内容总高（含末行，不含行后间隔）。 */
    private float gridContentH() {
        return Math.max(0, rowCount() * rowStride() - GAP);
    }

    private float maxGridScroll() {
        return Math.max(0, gridContentH() - gridH());
    }

    /** 第 row 行的卡片顶边 y（含平滑滚动偏移，可为负=部分移出上边界）。 */
    private int rowTopY(int row) {
        return Math.round(gridY1 + row * rowStride() - gridScroll);
    }

    /** 当前可见的首/末行（含部分露出的行；渲染与命中都用它）。 */
    private int firstVisibleRow() {
        return Math.max(0, (int) Math.floor((gridScroll - CARD_MIN_H) / rowStride()));
    }

    private int lastVisibleRow() {
        int last = (int) Math.floor((gridScroll + gridH()) / rowStride());
        return Math.max(0, Math.min(rowCount() - 1, last));
    }

    /** 席位卡矩形（按可见列表下标）＋ 命中用下标。 */
    private int[] cardRect(int index) {
        int per = cols();
        int row = index / per;
        int col = index % per;
        int x = gridX1() + col * (cardW() + GAP);
        int y = rowTopY(row);
        return new int[] {x, y, x + cardW(), y + cardH, index};
    }

    /** 网格命中：返回被点到的可见下标（未命中 -1）。滚动是像素级的，命中必须按当前像素位置算。 */
    private int cardIndexAt(int mx, int my) {
        if (visible.isEmpty() || my < gridY1 || my > gridY2) {
            return -1;
        }
        int per = cols();
        for (int row = firstVisibleRow(); row <= lastVisibleRow(); row++) {
            int y = rowTopY(row);
            if (my < y || my > y + cardH) {
                continue;
            }
            for (int col = 0; col < per; col++) {
                int idx = row * per + col;
                if (idx >= visible.size()) {
                    break;
                }
                int x = gridX1() + col * (cardW() + GAP);
                if (mx >= x && mx <= x + cardW()) {
                    return idx;
                }
            }
        }
        return -1;
    }

    /** 缓动一帧：把三个滚动区的当前值向目标值逼近（render 每帧调用一次）。 */
    private void advanceScroll() {
        long now = net.minecraft.Util.getMillis();
        float dt = lastFrameMs == 0 ? 16f : Math.min(100f, now - lastFrameMs);
        lastFrameMs = now;
        float k = (float) (1.0 - Math.exp(-dt / SCROLL_TAU_MS));
        gridScroll = easeTo(gridScroll, gridScrollTarget, k);
        railScroll = easeTo(railScroll, railScrollTarget, k);
        profileScroll = easeTo(profileScroll, profileScrollTarget, k);
    }

    private static float easeTo(float cur, float target, float k) {
        float d = target - cur;
        if (Math.abs(d) < 0.4f) {
            return target;
        }
        return cur + d * k;
    }

    private static float clampF(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    /** 机构芯片宽度（含徽章 + 方括号名称）。 */
    private int chipW(String label) {
        return font.width(RpTheme.tag(label)) + 30;
    }

    private void buildRailBounds() {
        int y = railY1 + 1;
        int x = gridX1();
        int allW =
                chipW(Component.translatable("ccnr_rp.gui.character.filter.all").getString());
        railBounds.add(new int[] {x, y, x + allW, y + 20, -1});
        x += allW + 4;
        for (int i = 0; i < factionMeta.size(); i++) {
            int w = chipW(str(factionMeta.get(i), "name"));
            railBounds.add(new int[] {x, y, x + w, y + 20, i});
            x += w + 4;
        }
    }

    /** 导轨内容总宽（芯片带右缘 − 左缘）。 */
    private float railContentW() {
        return railBounds.isEmpty() ? 0 : railBounds.get(railBounds.size() - 1)[2] - gridX1();
    }

    /** 导轨最大横向偏移（px）：末项贴右缘即止，不会滚出一片空白。 */
    private float maxRailScroll() {
        return Math.max(0, railContentW() - (gridX2() - gridX1()));
    }

    /** 档位（首个可见芯片下标）→ 目标像素偏移。 */
    private float railTargetFor(int index) {
        if (index <= 0 || railBounds.isEmpty()) {
            return 0;
        }
        int i = Math.min(index, railBounds.size() - 1);
        return Math.min(railBounds.get(i)[0] - gridX1(), maxRailScroll());
    }

    /** 像素偏移 → 最接近的档位下标（拖动后再回写档位，滚轮下一格接着走）。 */
    private int railIndexFor(float offsetPx) {
        int best = 0;
        for (int i = 0; i < railBounds.size(); i++) {
            if (railBounds.get(i)[0] - gridX1() <= offsetPx + 2) {
                best = i;
            } else {
                break;
            }
        }
        return best;
    }

    /** 当前可见芯片数（按已位移坐标计，供导轨滚动条使用）。 */
    private int railVisible() {
        int dx = -Math.round(railScroll);
        int n = 0;
        for (int[] b : railBounds) {
            if (b[2] + dx > gridX2()) {
                break;
            }
            if (b[0] + dx >= gridX1()) {
                n++;
            }
        }
        return Math.max(1, n);
    }

    private void buildDeployButton() {
        JsonObject p = findProfession(selectedId);
        if (p == null) {
            return;
        }
        int[] r = deployButtonRect();
        int w = r[2] - r[0];
        int h = r[3] - r[1];
        boolean met = userLevel() >= unlockLevel(p);
        CharacterStatus st = ClientCharacterState.userStatus();
        boolean observing = st == CharacterStatus.OBSERVING;
        boolean alive = st == CharacterStatus.ALIVE;
        boolean onCd = ClientCharacterState.userCooldownUntil() > System.currentTimeMillis();
        // 部署人数限制（全局性，客户端预览；服务端 deploy 统一入口仍会强校验）：
        // 重新部署（在场换岗）时自己已占旧职业/阵营位，目标职业/阵营在职数按「不含自己」计算
        boolean selfAlive = alive;
        boolean selfInProf = selfAlive && selectedId.equals(ClientCharacterState.userProfessionId());
        boolean selfInFac = selfAlive && str(p, "factionId").equals(ClientCharacterState.userFactionId());
        int profOccupied = ClientCharacterState.professionOccupied(selectedId) - (selfInProf ? 1 : 0);
        int facOccupied = ClientCharacterState.factionOccupied(str(p, "factionId")) - (selfInFac ? 1 : 0);
        int profLimit = ClientCharacterState.professionLimit(selectedId);
        int facLimit = ClientCharacterState.factionLimit(str(p, "factionId"));
        boolean profFull = profLimit >= 0 && profOccupied >= profLimit;
        boolean facFull = facLimit >= 0 && facOccupied >= facLimit;
        boolean canDeploy = met && (observing || alive) && !onCd && !profFull && !facFull;
        RpButton deploy =
                RpButton.primary(r[0], r[1], w, h, Component.translatable("ccnr_rp.gui.character.deploy"), b -> {
                    if (ClientCharacterState.userStatus() == CharacterStatus.ALIVE) {
                        // 在场：重新部署（不处死/不留遗体/不结算死亡经验，服务端直接换装部署）
                        // 二次确认暂时隐藏（无处决后果，直接执行；恢复确认框可置 confirmDeployId）
                        RpChannels.sendToServer(new RpPackets.KillDeployC2S(selectedId));
                    } else {
                        RpChannels.sendToServer(new RpPackets.DeployPositionC2S(selectedId));
                    }
                    notice("");
                });
        deploy.active = canDeploy;
        if (!met) {
            // 等级未达标：按钮置红并提示需要等级
            deploy.setMessage(Component.translatable("ccnr_rp.gui.character.need_level", unlockLevel(p)));
        } else if (profFull || facFull) {
            // 空位不足：禁用并提示限制（优先职业上限，其次阵营上限）
            if (profFull) {
                deploy.setMessage(Component.translatable("ccnr_rp.gui.character.deploy.full", profOccupied, profLimit));
            } else {
                deploy.setMessage(Component.translatable("ccnr_rp.gui.character.deploy.full", facOccupied, facLimit));
            }
            deploy.active = false;
        } else if (!observing && !alive) {
            // 等级达标但当前状态不可部署（阴间等）：禁用（不误标为等级问题）
            deploy.setMessage(Component.translatable("ccnr_rp.gui.character.deploy"));
            deploy.active = false;
        } else if (alive) {
            // 在场可部署：按钮提示重新部署
            deploy.setMessage(Component.translatable("ccnr_rp.gui.character.deploy_kill"));
        }
        addRenderableWidget(deploy);
    }

    /** 抽屉右侧的部署按钮矩形（渲染与 widget 共用，避免两处坐标漂移）。 */
    private int[] deployButtonRect() {
        int dockW = dockX2 - dockX1;
        int btnW = Math.max(96, Math.min(150, dockW * 27 / 100));
        int bx2 = dockX2 - 12;
        int bh = 22;
        int by = dockY2 - 10 - bh;
        return new int[] {bx2 - btnW, by, bx2, by + bh};
    }

    /** 过滤后的职位（每次调用都新建列表，仅供 relayout 刷新缓存用）。 */
    private List<JsonObject> computeVisible() {
        if (filterFaction.isEmpty()) {
            return professions;
        }
        return professions.stream()
                .filter(p -> str(p, "factionId").equals(filterFaction))
                .toList();
    }

    /** 当前可见职位（relayout 时刷新的缓存；渲染/命中/滚动都用它，避免高频路径反复流式过滤）。 */
    private List<JsonObject> filtered() {
        return visible;
    }

    private JsonObject findProfession(String id) {
        for (JsonObject p : professions) {
            if (str(p, "id").equals(id)) {
                return p;
            }
        }
        return null;
    }

    private JsonObject factionMeta(String id) {
        for (JsonObject f : factionMeta) {
            if (str(f, "id").equals(id)) {
                return f;
            }
        }
        return null;
    }

    private int userLevel() {
        return ClientCharacterState.userLevel();
    }

    private int unlockLevel(JsonObject p) {
        try {
            return p.has("unlockLevel") ? Math.max(0, p.get("unlockLevel").getAsInt()) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 职业是否无可用部署余额：与部署按钮同条件——职业或阵营维度在职数（在场换岗按不含本人算）≥ 上限，或上限 0=禁止。
     * 职业维度上限 professionLimit() 已含 GLOBAL 兜底（未配置专属规则的职业按 GLOBAL 计），与部署按钮/服务端校验一致。
     */
    private boolean profNoBalance(JsonObject p) {
        String profId = str(p, "id");
        String facId = str(p, "factionId");
        boolean selfAlive = ClientCharacterState.userStatus() == CharacterStatus.ALIVE;
        boolean selfInProf = selfAlive && profId.equals(ClientCharacterState.userProfessionId());
        boolean selfInFac = selfAlive && facId.equals(ClientCharacterState.userFactionId());
        int profOcc = ClientCharacterState.professionOccupied(profId) - (selfInProf ? 1 : 0);
        int facOcc = ClientCharacterState.factionOccupied(facId) - (selfInFac ? 1 : 0);
        int profLim = ClientCharacterState.professionLimit(profId);
        int facLim = ClientCharacterState.factionLimit(facId);
        return (profLim >= 0 && profOcc >= profLim) || (facLim >= 0 && facOcc >= facLim);
    }

    /** 阵营是否无可用职业复活：该阵营下所有职业均无可用余额（无职业也视为不可用）。 */
    private boolean facNoAvailable(JsonObject fac) {
        for (JsonObject p : professions) {
            if (str(p, "factionId").equals(str(fac, "id")) && !profNoBalance(p)) {
                return false;
            }
        }
        return true;
    }

    private static String factionName(JsonObject p, List<JsonObject> metas) {
        String fid = str(p, "factionId");
        for (JsonObject f : metas) {
            if (str(f, "id").equals(fid)) {
                return str(f, "name");
            }
        }
        return fid;
    }

    /** 席位状态色：等级未达=弱灰 / 编制无余额=红 / 可部署=白。 */
    private int seatColor(JsonObject p) {
        if (userLevel() < unlockLevel(p)) {
            return RpTheme.BORDER_DIM;
        }
        return profNoBalance(p) ? RpTheme.RED_LINE : RpTheme.CYAN;
    }

    // ============================================================
    // 输入
    // ============================================================

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!confirmDeployId.isEmpty()) {
            // 重新部署确认弹窗（暂时隐藏）：确认/取消按钮，弹窗期间吞掉其余点击
            int[][] rects = confirmButtonRects();
            if (mx >= rects[0][0] && mx <= rects[0][2] && my >= rects[0][1] && my <= rects[0][3]) {
                String id = confirmDeployId;
                confirmDeployId = "";
                RpChannels.sendToServer(new RpPackets.KillDeployC2S(id));
                notice("");
            } else if (mx >= rects[1][0] && mx <= rects[1][2] && my >= rects[1][1] && my <= rects[1][3]) {
                confirmDeployId = "";
            }
            return true;
        }
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        // 标题栏 ✕：点击关闭（悬停只高亮，不关闭）
        if (mx >= px2 - 28 && mx <= px2 - 10 && my >= hdrY1 - 1 && my <= hdrY1 + 17) {
            onClose();
            return true;
        }
        // 管理按钮
        if (mx >= mgrX1 && mx <= mgrX2 && my >= mgrY1 && my <= mgrY2) {
            // 权限判据只在 RpAdminScreen.open() 里写一份（此处不再自行判一次，避免两处判据漂移）
            RpAdminScreen.open();
            return true;
        }
        // 机构导轨芯片（命中判定用与渲染同一套像素位移，否则横滚后点到的是别家机构）
        int railDx = -Math.round(railScroll);
        for (int[] b : railBounds) {
            if (mx >= b[0] + railDx && mx <= b[2] + railDx && my >= b[1] && my <= b[3]) {
                filterFaction = b[4] < 0 ? "" : str(factionMeta.get(b[4]), "id");
                resetScroll();
                select("");
                return true;
            }
        }
        // 机构导轨滚动条（横向拖拽；id=2，单位=像素）
        int railW = gridX2() - gridX1();
        int railNs = RpScrollbar.clickH(
                (int) mx,
                (int) my,
                gridX1(),
                gridX2(),
                railY2,
                railY2 + 5,
                (int) Math.ceil(railContentW()),
                railW,
                Math.round(railScroll),
                2);
        if (railNs >= 0) {
            railScrollTarget = clampF(railNs, 0, maxRailScroll());
            railScroll = railScrollTarget; // 拖拽=直接操作，当前值立刻跟上
            railIndex = railIndexFor(railScroll);
            return true;
        }
        // 网格滚动条（纵向拖拽；id=1，单位=像素）
        int ns = RpScrollbar.clickV(
                (int) mx,
                (int) my,
                gridX2() - 7,
                gridX2() - 2,
                gridY1,
                gridY2,
                (int) Math.ceil(gridContentH()),
                gridH(),
                Math.round(gridScroll),
                1);
        if (ns >= 0) {
            gridScrollTarget = clampF(ns, 0, maxGridScroll());
            gridScroll = gridScrollTarget;
            return true;
        }
        // 画像滚动条（纵向拖拽；id=3，单位=像素）
        int[] pr = profileRect();
        if (pr != null) {
            int contentH = pr[4] * PROFILE_LINE_H;
            int viewH = pr[5] * PROFILE_LINE_H;
            int pns = RpScrollbar.clickV(
                    (int) mx,
                    (int) my,
                    pr[2] - 7,
                    pr[2] - 2,
                    pr[1],
                    pr[3],
                    contentH,
                    viewH,
                    Math.round(profileScroll),
                    3);
            if (pns >= 0) {
                profileScrollTarget = clampF(pns, 0, contentH - viewH);
                profileScroll = profileScrollTarget;
                return true;
            }
        }
        // 席位卡（命中按当前像素位置算）
        int hit = cardIndexAt((int) mx, (int) my);
        if (hit >= 0) {
            select(str(visible.get(hit), "id"));
            return true;
        }
        return false;
    }

    /** 选中职位：切换选中项时把画像滚动归零（换人还停在上一份简历的中间行会读错内容）。 */
    private void select(String id) {
        if (!id.equals(selectedId)) {
            profileScroll = 0;
            profileScrollTarget = 0;
        }
        selectedId = id;
        rebuild();
    }

    /** 三个滚动区一起回顶（切换机构筛选时用）。 */
    private void resetScroll() {
        gridScroll = gridScrollTarget = 0;
        profileScroll = profileScrollTarget = 0;
        railIndex = 0;
        railScroll = railScrollTarget = 0;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int b, double dx, double dy) {
        // 轴向由 RpScrollbar 内部记录（同一个拖动只服务一条滚动条），按 dragId 分发到对应位移字段。
        // 纯位移只走 relayout()：不重建 widget，拖动才跟手。
        int hs = RpScrollbar.dragH((int) mx);
        if (hs >= 0) {
            railScrollTarget = clampF(hs, 0, maxRailScroll());
            railScroll = railScrollTarget; // 拖拽=直接操作：不做缓动，1:1 跟手
            railIndex = railIndexFor(railScroll);
            return true;
        }
        int ns = RpScrollbar.dragV((int) my);
        if (ns >= 0) {
            if (RpScrollbar.dragId() == 3) {
                int[] pr = profileRect();
                if (pr != null) {
                    int max = Math.max(0, (pr[4] - pr[5]) * PROFILE_LINE_H);
                    profileScrollTarget = clampF(ns, 0, max);
                    profileScroll = profileScrollTarget;
                }
            } else {
                gridScrollTarget = clampF(ns, 0, maxGridScroll());
                gridScroll = gridScrollTarget;
            }
            return true;
        }
        return super.mouseDragged(mx, my, b, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int b) {
        RpScrollbar.endDrag();
        return super.mouseReleased(mx, my, b);
    }

    /**
     * 滚轮：**每格一动**（`delta` 本身≈±1，此前写成 `delta / 10` 会被 int 截断成 0 → 滚轮像没反应），
     * 且只改**目标值**——真正的位移由 {@link #advanceScroll()} 逐帧缓动逼近，于是滚动有中间态（滑过去）。
     */
    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int dir = delta > 0 ? -1 : 1;
        // 机构导轨：滚轮横向走一个芯片档位
        if (railShown() && my >= railY1 - 2 && my <= railY2 + 7) {
            int maxIdx = Math.max(0, railBounds.size() - 1);
            railIndex = Math.max(0, Math.min(railIndex + dir, maxIdx));
            railScrollTarget = Math.min(railTargetFor(railIndex), maxRailScroll());
            return true;
        }
        // 抽屉画像区：滚轮翻画像行（命中区放宽到整个抽屉：过长简历不必先瞄着文本块滚）
        int[] pr = profileRect();
        if (pr != null && pr[4] > pr[5] && mx >= dockX1 && mx <= dockX2 && my >= dockY1 && my <= dockY2) {
            int max = Math.max(0, (pr[4] - pr[5]) * PROFILE_LINE_H);
            profileScrollTarget = clampF(profileScrollTarget + dir * PROFILE_LINE_H, 0, max);
            return true;
        }
        // 席位卡网格：滚轮整行翻动（目标值按行距走）
        if (mx >= gridX1() && mx <= gridX2() && my >= gridY1 - 4 && my <= gridY2 + 4) {
            gridScrollTarget = clampF(gridScrollTarget + dir * rowStride(), 0, maxGridScroll());
            return true;
        }
        return true;
    }

    /** 方向键在席位卡网格内移动选中（↑↓ 换行 / ←→ 换列），保证选中项始终滚入可视区。 */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        int step;
        switch (keyCode) {
            case 265 -> step = -cols(); // UP
            case 264 -> step = cols(); // DOWN
            case 263 -> step = -1; // LEFT
            case 262 -> step = 1; // RIGHT
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
        List<JsonObject> visible = filtered();
        if (visible.isEmpty()) {
            return true;
        }
        int idx = 0;
        for (int i = 0; i < visible.size(); i++) {
            if (str(visible.get(i), "id").equals(selectedId)) {
                idx = i;
                break;
            }
        }
        int next = Math.max(0, Math.min(visible.size() - 1, idx + step));
        boolean changed = !str(visible.get(next), "id").equals(selectedId);
        if (changed) {
            profileScroll = 0; // 换人即回到画像首行
            profileScrollTarget = 0;
        }
        selectedId = str(visible.get(next), "id");
        // 把选中行滑进可视区（只动目标值，实际位移交给缓动）
        int row = next / cols();
        float top = row * rowStride();
        float bottom = top + cardH;
        float t = gridScrollTarget;
        if (top < t) {
            t = top;
        } else if (bottom > t + gridH()) {
            t = bottom - gridH();
        }
        gridScrollTarget = clampF(t, 0, maxGridScroll());
        if (changed) {
            rebuild();
        } else {
            relayout();
        }
        return true;
    }

    private void notice(String key) {
        if (key == null || key.isBlank()) {
            notice = "";
            return;
        }
        notice = Component.translatable(key).getString();
        noticeUntil = System.currentTimeMillis() + 2600;
    }

    // ============================================================
    // 渲染
    // ============================================================

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        advanceScroll(); // 平滑滚动：每帧把当前位移向目标位移逼近（滚轮/按键才有"滑过去"的中间态）
        renderBackground(g);
        // 完整终端框（近黑底 + 灰描边 + 四角角标 + 顶部高光 rail）
        RpTheme.terminalFrame(g, px1, py1, px2, py2, RpTheme.RADIUS_LARGE);
        RpTheme.gridOverlay(g, px1 + 4, py1 + 4, px2 - 4, py2 - 4);
        // 内容层整体裁剪到终端框内：极端窗口（GUI scale 很大）下也不会把卡片/抽屉画到框外
        g.enableScissor(px1, py1, px2, py2);
        renderHeader(g, mx, my);
        renderRail(g, mx, my);
        renderGridTitle(g);
        renderCards(g, mx, my);
        renderDock(g, mx, my);
        g.disableScissor();
        super.render(g, mx, my, partial);
        // CRT 扫描线（在内容层之上，低透明度屏幕质感）
        RpTheme.scanlines(g, px1, py1, px2, py2);
        if (!confirmDeployId.isEmpty()) {
            renderKillConfirm(g, mx, my);
        }
    }

    private void renderHeader(GuiGraphics g, int mx, int my) {
        int y = hdrY1 + 5;
        g.drawString(font, "[CCNR]", px1 + 14, y, RpTheme.TEXT_SECONDARY, true);
        String head = Component.translatable("ccnr_rp.gui.character.term_title")
                .getString()
                .toUpperCase(Locale.ROOT);
        g.drawString(font, head, px1 + 14 + font.width("[CCNR]") + 16, y, RpTheme.CYAN, true);
        String statusLabel =
                switch (ClientCharacterState.userStatus()) {
                    case ALIVE -> Component.translatable("ccnr_rp.gui.character.status.alive")
                            .getString();
                    case DEAD -> Component.translatable("ccnr_rp.gui.character.status.dead")
                            .getString();
                    default -> Component.translatable("ccnr_rp.gui.character.status.observing")
                            .getString();
                };
        String lvl = "LV." + userLevel() + " // STATUS: " + statusLabel;
        g.drawString(
                font,
                lvl,
                px1 + 14 + font.width("[CCNR]") + 16 + font.width(head) + 24,
                y,
                RpTheme.TEXT_SECONDARY,
                true);
        g.fill(px1 + 8, hdrY2, px2 - 8, hdrY2 + 1, RpTheme.CYAN_DIM);
        // 管理按钮（管理员可开管理面板）
        boolean mgrHover = my >= mgrY1 && my <= mgrY2 && mx >= mgrX1 && mx <= mgrX2;
        if (mgrHover) {
            g.fill(mgrX1 - 2, mgrY1 - 1, mgrX2 + 2, mgrY2 + 1, RpTheme.alphaBlend(RpTheme.TEXT_DIM, 0x60));
        }
        g.drawString(
                font,
                Component.translatable("ccnr_rp.gui.character.admin_btn").getString(),
                mgrX1 + 4,
                hdrY1 + 4,
                ClientCharacterState.isAdmin() ? RpTheme.CYAN : RpTheme.TEXT_DIM,
                true);
        boolean hover = my >= hdrY1 - 1 && my <= hdrY1 + 17 && mx >= px2 - 28 && mx <= px2 - 10;
        if (hover) {
            g.fill(px2 - 30, hdrY1 - 2, px2 - 8, hdrY1 + 18, RpTheme.RED_BG_FILL);
        }
        g.drawString(font, "X", px2 - 20, hdrY1 + 4, hover ? RpTheme.CYAN : RpTheme.TEXT_SECONDARY, true);
        // 关闭由 mouseClicked 处理（点击才关；悬停只高亮，不关闭）
    }

    /**
     * 顶部机构导轨：横向芯片带（[ 全部 ] [ 机构 ] …），只在带内绘制（scissor 裁剪），
     * 溢出时右端出现细进度线；不再为机构过滤占用一整列。
     */
    private void renderRail(GuiGraphics g, int mx, int my) {
        if (!railShown()) {
            return;
        }
        int y = railY1;
        g.fill(gridX1(), y, gridX2(), y + 1, RpTheme.alphaBlend(RpTheme.PANEL_BORDER, 0x80));
        g.enableScissor(gridX1(), y - 1, gridX2(), railY2 + 1);
        int dx = -Math.round(railScroll);
        for (int[] b : railBounds) {
            JsonObject fac = b[4] < 0 ? null : factionMeta.get(b[4]);
            boolean sel = (fac == null && filterFaction.isEmpty())
                    || (fac != null && str(fac, "id").equals(filterFaction));
            boolean hover = mx >= b[0] + dx && mx <= b[2] + dx && my >= b[1] && my <= b[3];
            boolean noAvail = fac != null && facNoAvailable(fac);
            int x1 = b[0] + dx;
            int x2 = b[2] + dx;
            if (sel) {
                RpRoundRect.fill(g, x1, b[1], x2, b[3], 3f, RpTheme.CYAN);
                g.fill(x1, b[3] - 1, x2, b[3], RpTheme.TEXT_BRIGHT);
            } else {
                RpRoundRect.outlined(
                        g,
                        x1,
                        b[1],
                        x2,
                        b[3],
                        3f,
                        noAvail ? RpTheme.RED_LINE : (hover ? RpTheme.PANEL_BORDER_BRIGHT : RpTheme.PANEL_BORDER),
                        noAvail
                                ? RpTheme.alphaBlend(RpTheme.RED_DIM, 0x30)
                                : (hover ? RpTheme.SURFACE_CONTROL_HOVER : RpTheme.SURFACE_CONTROL));
            }
            int textColor = sel ? RpTheme.ACCENT_TEXT : (noAvail ? RpTheme.RED_LINE : RpTheme.TEXT_SECONDARY);
            if (fac == null) {
                RpIcons.badge(g, x1 + 11, (b[1] + b[3]) / 2, 6, "target", 2, sel);
                g.drawString(
                        font,
                        RpTheme.tag(Component.translatable("ccnr_rp.gui.character.filter.all")
                                .getString()),
                        x1 + 22,
                        b[1] + 6,
                        textColor,
                        true);
            } else {
                RpIcons.factionBadge(g, x1 + 11, (b[1] + b[3]) / 2, 6, fac, sel);
                g.drawString(
                        font,
                        RpTheme.tag(RpTheme.clip(font, str(fac, "name"), Math.max(24, b[2] - b[0] - 32))),
                        x1 + 22,
                        b[1] + 6,
                        textColor,
                        true);
            }
        }
        g.disableScissor();
        // 导轨溢出提示：横向可拖动滚动条（与网格/画像滚动条同一画法，可直接拖游标）
        int railW = gridX2() - gridX1();
        if (railContentW() > railW) {
            RpScrollbar.drawH(
                    g,
                    gridX1(),
                    gridX2(),
                    railY2,
                    (int) Math.ceil(railContentW()),
                    railW,
                    Math.round(railScrollTarget));
        }
    }

    private void renderGridTitle(GuiGraphics g) {
        int w = gridX2() - gridX1() - 10;
        String label = Component.translatable("ccnr_rp.gui.character.muster").getString();
        int used = sectionHead(
                g,
                gridX1(),
                titleY,
                w,
                String.format(Locale.ROOT, "%02d", filtered().size()),
                label);
        int rightX = gridX1() + used + 8;
        String scope = filterFaction.isEmpty()
                ? Component.translatable("ccnr_rp.gui.character.filter.all").getString()
                : factionNameForId(filterFaction);
        String tail =
                RpTheme.tag(scope) + " " + RpTheme.tag(String.valueOf(filtered().size()));
        int tw = font.width(tail);
        if (rightX + tw < gridX2() - 8) {
            g.drawString(font, tail, gridX2() - 8 - tw, titleY + 1, RpTheme.TEXT_DIM);
        }
    }

    private String factionNameForId(String id) {
        JsonObject f = factionMeta(id);
        return f == null ? id : str(f, "name");
    }

    /** 席位卡网格（多列铺开；空态给出管理员可执行的补救指令）。 */
    private void renderCards(GuiGraphics g, int mx, int my) {
        if (visible.isEmpty()) {
            String msg = factionMeta.isEmpty()
                    ? Component.translatable("ccnr_rp.gui.character.faction.empty")
                            .getString()
                    : Component.translatable("ccnr_rp.gui.character.profession.empty")
                            .getString();
            g.drawCenteredString(font, msg, (gridX1() + gridX2()) / 2, gridY1 + gridH() / 2 - 4, RpTheme.TEXT_DIM);
            return;
        }
        // 半行滚动时卡片会被裁掉一截，故整块裁剪到网格带内；物品词条收集起来在裁剪之外画
        ItemStack hovered = ItemStack.EMPTY;
        int per = cols();
        g.enableScissor(gridX1(), gridY1, gridX2(), gridY2);
        for (int row = firstVisibleRow(); row <= lastVisibleRow(); row++) {
            int y = rowTopY(row);
            if (y > gridY2 || y + cardH < gridY1) {
                continue;
            }
            for (int col = 0; col < per; col++) {
                int idx = row * per + col;
                if (idx >= visible.size()) {
                    break;
                }
                int[] b = cardRect(idx);
                JsonObject p = visible.get(idx);
                boolean sel = str(p, "id").equals(selectedId);
                boolean hover = mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3];
                ItemStack h = renderCard(g, b, p, sel, hover, mx, my);
                if (!h.isEmpty()) {
                    hovered = h;
                }
            }
        }
        g.disableScissor();
        if (!hovered.isEmpty()) {
            g.renderTooltip(font, hovered, mx, my);
        }
        RpScrollbar.draw(
                g, gridX2() - 7, gridY1, gridY2, (int) Math.ceil(gridContentH()), gridH(), Math.round(gridScroll));
    }

    /**
     * 席位卡：左缘状态条（白=可部署 / 红=编制满 / 灰=等级未达）+ 序号码块 + 职位名 + 等级门控标签
     * + 机构行（徽章·机构名·在职/编制）+ 紧凑装备图标条。选中=反白（文字走 ACCENT_TEXT）。
     */
    private ItemStack renderCard(GuiGraphics g, int[] b, JsonObject p, boolean sel, boolean hover, int mx, int my) {
        int x1 = b[0];
        int y1 = b[1];
        int x2 = b[2];
        int y2 = b[3];
        int state = seatColor(p);
        if (sel) {
            RpRoundRect.fill(g, x1, y1, x2, y2, 4f, RpTheme.CYAN);
            g.fill(x1, y2 - 1, x2, y2, RpTheme.TEXT_BRIGHT);
        } else {
            RpRoundRect.outlined(
                    g,
                    x1,
                    y1,
                    x2,
                    y2,
                    4f,
                    hover ? RpTheme.PANEL_BORDER_BRIGHT : RpTheme.PANEL_BORDER,
                    hover ? RpTheme.PANEL_BG_ALT : RpTheme.PANEL_BG);
            // 左缘状态条：一眼看出该席位能否部署
            g.fill(x1, y1 + 2, x1 + 3, y2 - 2, state);
        }
        // 序号码块（构成主义序号）：常态结构红底反白；选中态卡片已是白底，改用深色块避免红块压过反白
        String no = String.format(Locale.ROOT, "%02d", b[4] + 1);
        int nw = font.width(no) + 6;
        g.fill(x1 + 6, y1 + 5, x1 + 6 + nw, y1 + 15, sel ? RpTheme.ACCENT_TEXT : STRUCT_RED);
        g.drawString(font, no, x1 + 9, y1 + 6, RpTheme.TEXT_PRIMARY, false);
        int textColor = sel ? RpTheme.ACCENT_TEXT : RpTheme.TEXT_PRIMARY;
        int subColor = sel ? RpTheme.ACCENT_TEXT : RpTheme.TEXT_SECONDARY;
        // 等级门控标签（右上）
        boolean met = userLevel() >= unlockLevel(p);
        String tag = "LV." + unlockLevel(p);
        int tagW = font.width(tag) + 8;
        int tx2 = x2 - 6;
        if (tx2 - tagW > x1 + 10 + nw + 4) {
            // 达标=低透明亮底深字 / 未达标=实心红底白字（与列表标红语义一致）
            int tagBg = sel
                    ? RpTheme.ACCENT_TEXT
                    : RpTheme.alphaBlend(met ? RpTheme.STATUS_ALIVE : RpTheme.RED_LINE, met ? 0x33 : 0xFF);
            RpRoundRect.fill(g, tx2 - tagW, y1 + 5, tx2, y1 + 15, 2f, tagBg);
            g.drawString(font, tag, tx2 - tagW + 4, y1 + 6, RpTheme.TEXT_PRIMARY, false);
        }
        // 职位名（字距放大；超宽裁剪）
        int nameX = x1 + 10 + nw + 6;
        int nameMax = Math.max(20, (tx2 - tagW - 6) - nameX);
        tracked(g, RpTheme.clip(font, str(p, "name"), nameMax), nameX, y1 + 6, textColor, 1);
        g.fill(x1 + 6, y1 + 16, x2 - 6, y1 + 17, RpTheme.alphaBlend(sel ? RpTheme.ACCENT_TEXT : RpTheme.CYAN, 0x44));
        // 机构行：徽章 + 机构名 + 在职/编制读数
        int ry = y1 + 22;
        boolean hasBadge = x2 - x1 > 120;
        int badgePad = 0;
        if (hasBadge) {
            RpIcons.factionBadge(g, x1 + 12, ry + 5, 6, factionMeta(str(p, "factionId")), false);
            badgePad = 16;
        }
        int lim = ClientCharacterState.professionLimit(str(p, "id"));
        String occTag = "";
        if (lim >= 0) {
            int occ = ClientCharacterState.professionOccupied(str(p, "id"));
            occTag = occ + "/" + lim;
        }
        int occW = occTag.isEmpty() ? 0 : font.width(occTag) + 10;
        g.drawString(
                font,
                RpTheme.clip(font, factionName(p, factionMeta), Math.max(20, x2 - 13 - badgePad - occW - (x1 + 6))),
                x1 + 6 + badgePad,
                ry,
                subColor);
        if (!occTag.isEmpty()) {
            boolean full = ClientCharacterState.professionOccupied(str(p, "id")) >= lim;
            int ow = font.width(occTag);
            g.drawString(
                    font,
                    occTag,
                    x2 - 7 - ow,
                    ry,
                    sel ? RpTheme.ACCENT_TEXT : (full ? RpTheme.RED_LINE : RpTheme.TEXT_BRIGHT),
                    false);
        }
        // 紧凑装备图标条（职位 loadout 前 5 件：头/胸/腿/靴/武器；卡片矮时不画槽名）
        return renderEquipStrip(g, x1 + 8, ry + 13, x2 - 8, y2 - 5, loadoutOf(p), sel, false, mx, my);
    }

    /** 底部席位终端抽屉：3D 立绘 + 身份读数 + 装备槽 + 部署按钮（选中项即时更新）。 */
    private void renderDock(GuiGraphics g, int mx, int my) {
        int dockW = dockX2 - dockX1;
        RpTheme.sectionCard(g, dockX1, dockY1, dockX2, dockY2);
        g.fill(dockX1, dockY1, dockX1 + 3, dockY2, STRUCT_RED);
        g.fill(dockX1 + 3, dockY1, dockX2, dockY1 + 1, RpTheme.alphaBlend(RpTheme.PANEL_BORDER_BRIGHT, 0x99));
        cornerTicks(g, dockX1 + 4, dockY1 + 1, dockX2 - 1, dockY2 - 1, 5, RpTheme.PANEL_BORDER_BRIGHT);
        tickScale(g, dockX1 + 6, dockX2 - 6, dockY2 - 3, 8, 32, RpTheme.alphaBlend(RpTheme.TEXT_DIM, 0xCC));
        JsonObject p = findProfession(selectedId);
        String title = Component.translatable("ccnr_rp.gui.character.dock").getString();
        String head = p == null ? title : title + " · " + str(p, "name");
        int headW = dockW - 16;
        // 右侧留出通知区
        if (!notice.isBlank() && System.currentTimeMillis() < noticeUntil) {
            String prefix =
                    "[ " + Component.translatable("ccnr_rp.gui.admin.system").getString() + " ] ";
            String msg = RpTheme.clip(font, prefix + notice, Math.max(40, dockW / 3));
            headW = Math.max(40, dockW - 24 - font.width(msg));
            g.drawString(font, msg, dockX2 - 10 - font.width(msg), dockY1 + 6, RpTheme.RED_LINE);
        }
        // 抽屉标题不做序号块（序号留给下方两个分区：01 装备 / 02 画像）
        sectionHead(g, dockX1 + 8, dockY1 + 6, headW, null, head);
        int contentTop = dockY1 + 20;
        int contentBottom = dockY2 - 6;
        if (p == null) {
            String empty =
                    Component.translatable("ccnr_rp.gui.character.detail.empty").getString();
            String hint = Component.translatable("ccnr_rp.gui.character.detail.empty_hint")
                    .getString();
            int cx = dockX1 + dockW / 2;
            g.drawCenteredString(font, empty, cx, contentTop + 10, RpTheme.TEXT_DIM);
            g.drawCenteredString(font, hint, cx, contentTop + 24, RpTheme.TEXT_DIM);
            return;
        }
        // 三段横向分区：立绘 | 读数 | 装备+部署（几何由 dockLayout 统一给出，输入侧复用同一套坐标）
        int[] lay = dockLayout();
        if (lay[5] == 1) {
            renderDockModel(g, p, lay[0], lay[1], lay[4], lay[6]);
        }
        renderDockReadouts(g, p, lay[2], lay[3], lay[4], lay[6], lay[8]);
        renderDockEquip(g, p, lay[7], lay[9], lay[4], lay[8] - 4, mx, my);
    }

    /**
     * 抽屉三段几何（渲染与命中判定共用同一套坐标，避免两处漂移）：
     * {0,1}=立绘列 x1/x2，{2,3}=中列（读数+画像）x1/x2，{4}=内容顶，{5}=立绘列是否显示，
     * {6}=内容底，{7,9}=部署按钮 x1/x2，{8}=按钮 y1。
     */
    private int[] dockLayout() {
        int[] btn = deployButtonRect();
        int contentTop = dockY1 + 20;
        int contentBottom = dockY2 - 6;
        int dockW = dockX2 - dockX1;
        int modelW = Math.max(56, Math.min(120, dockW * 22 / 100));
        int modelX1 = dockX1 + 10;
        int modelX2 = modelX1 + modelW;
        int midX1 = modelX2 + 10;
        int midX2 = btn[0] - 12;
        // 极窄窗口（三段放不下）时先让位给读数与部署按钮：立绘整列不画，绝不互相压字
        boolean showModel = midX2 - midX1 >= 60;
        if (!showModel) {
            midX1 = dockX1 + 10;
            midX2 = btn[0] - 12;
        }
        return new int[] {
            modelX1, modelX2, midX1, midX2, contentTop, showModel ? 1 : 0, contentBottom, btn[0], btn[1], btn[2]
        };
    }

    /**
     * 抽屉画像区（可滚动文本块）矩形：{0..3} = x1,y1,x2,y2（滚动条在 x2-7..x2-2 内的 y1..y2），
     * {4} = 画像总行数，{5} = 同时可见行数；无画像 / 无空间返回 null。
     */
    private int[] profileRect() {
        JsonObject p = findProfession(selectedId);
        if (p == null) {
            return null;
        }
        String profile = str(p, "profile");
        if (profile.isBlank()) {
            return null;
        }
        int[] lay = dockLayout();
        int midW = lay[3] - lay[2];
        if (midW < 60) {
            return null;
        }
        int top = lay[4] + 40 + 12; // 读数三行 + 画像小节头
        int bottom = Math.min(lay[6], lay[8] - 4);
        int visible = (bottom - top) / 10;
        if (visible < 1) {
            return null;
        }
        // 溢出时文本再让出 8px 滚动条槽
        int textW = midW - 6;
        int total = wrapText(profile, textW).size();
        textW = total > visible ? textW - 8 : textW;
        total = wrapText(profile, textW).size();
        return new int[] {lay[2], top, lay[2] + midW, bottom, total, Math.min(visible, total)};
    }

    private void renderDockModel(GuiGraphics g, JsonObject p, int x1, int x2, int top, int bottom) {
        JsonObject ch = new JsonObject();
        ch.addProperty("id", "pos-" + str(p, "id"));
        ch.addProperty("name", str(p, "name"));
        int h = bottom - top;
        if (h < 24 || x2 - x1 < 24) {
            return;
        }
        // 机构徽章作为立绘的归属底标（先画，模型叠在其上；保留旧版"预览区显示阵营图标"的信息供给）
        JsonObject facMeta = factionMeta(str(p, "factionId"));
        if (facMeta != null && h >= 56) {
            int r = Math.max(9, Math.min(16, (x2 - x1) / 4));
            RpIcons.factionBadge(g, (x1 + x2) / 2, bottom - r - 2, r, facMeta, false);
        }
        int scale = Math.max(10, Math.min(30, Math.min(h / 3 - 4, (x2 - x1) / 3)));
        int cx = (x1 + x2) / 2;
        // 立绘区自带 PREVIEW 标签（竖排空间不足时省略），模型整体下移让位给标签
        int beamTop = top;
        if (h >= 64) {
            g.drawString(
                    font,
                    RpTheme.section(Component.translatable("ccnr_rp.gui.character.section.preview")
                            .getString()),
                    x1,
                    top,
                    RpTheme.TEXT_DIM);
            beamTop = top + 11;
        }
        int cy = beamTop + (bottom - beamTop) / 2 + 4;
        g.enableScissor(x1 - 6, top, x2 + 6, bottom);
        renderHoloBeam(g, cx, beamTop, bottom, cy, scale);
        CharacterPreview.render(g, cx, cy, scale, ch, loadoutOf(p));
        g.disableScissor();
        // 立绘区右缘分隔线 + 角标（三段分区的结构线）
        g.fill(x2 + 5, top, x2 + 6, bottom, RpTheme.alphaBlend(RpTheme.PANEL_BORDER_BRIGHT, 0x66));
    }

    private void renderDockReadouts(GuiGraphics g, JsonObject p, int x1, int x2, int top, int bottom, int buttonTop) {
        int w = x2 - x1;
        if (w < 60) {
            return;
        }
        String idLine = Component.translatable(
                        "ccnr_rp.gui.character.term_id", str(p, "id"), factionName(p, factionMeta))
                .getString();
        String[] idPair = splitPair(idLine);
        readout(g, x1, top, w, 11, idPair[0], idPair[1], RpTheme.TEXT_PRIMARY, STRUCT_RED);
        boolean met = userLevel() >= unlockLevel(p);
        String req = Component.translatable("ccnr_rp.gui.character.term_lvl", unlockLevel(p), userLevel())
                .getString();
        readout(g, x1, top + 13, w, 11, req, "", RpTheme.TEXT_PRIMARY, met ? RpTheme.STATUS_ALIVE : RpTheme.RED);
        // 部署限制与当前在职（全局性限制预览；服务端 deploy 统一入口强校验）
        String profId = str(p, "id");
        String facId = str(p, "factionId");
        boolean selfAlive = ClientCharacterState.userStatus() == CharacterStatus.ALIVE;
        boolean selfInProf = selfAlive && profId.equals(ClientCharacterState.userProfessionId());
        boolean selfInFac = selfAlive && facId.equals(ClientCharacterState.userFactionId());
        int profLimit = ClientCharacterState.professionLimit(profId);
        int facLimit = ClientCharacterState.factionLimit(facId);
        int profOcc = ClientCharacterState.professionOccupied(profId) - (selfInProf ? 1 : 0);
        int facOcc = ClientCharacterState.factionOccupied(facId) - (selfInFac ? 1 : 0);
        String per = Component.translatable(
                        "ccnr_rp.gui.character.term_personnel", profOcc, profLimit >= 0 ? profLimit : "∞")
                .getString();
        String perFac;
        if (facLimit >= 0) {
            perFac = Component.translatable("ccnr_rp.gui.character.term_personnel_fac", facOcc, facLimit)
                    .getString();
        } else {
            perFac = Component.translatable("ccnr_rp.gui.character.term_personnel_unlim", facOcc)
                    .getString();
        }
        boolean profFull = profLimit >= 0 && profOcc >= profLimit;
        boolean facFull = facLimit >= 0 && facOcc >= facLimit;
        readout(
                g,
                x1,
                top + 26,
                w,
                11,
                per,
                perFac.replace(" // ", ""),
                RpTheme.TEXT_PRIMARY,
                (profFull || facFull) ? RpTheme.RED_LINE : RpTheme.STATUS_ALIVE);
        // 项目简历（职业 profile，可选）：读数之下折行展示；**过长时可滚动**（滚轮 / 右侧可拖动滚动条）
        int[] pr = profileRect();
        if (pr == null) {
            return;
        }
        sectionHead(
                g,
                x1,
                top + 40,
                w,
                "02",
                Component.translatable("ccnr_rp.gui.character.section.profile").getString());
        // 溢出时才让出滚动条槽，避免短简历被无谓缩窄
        int textW = w - 6 - (pr[4] > pr[5] ? 8 : 0);
        List<String> lines = wrapText(str(p, "profile"), textW);
        int viewTop = top + 40 + 12;
        int maxOff = Math.max(0, (pr[4] - pr[5]) * PROFILE_LINE_H);
        int offPx = Math.round(clampF(profileScroll, 0, maxOff));
        g.enableScissor(x1, viewTop, x1 + textW, pr[3]);
        int ly = viewTop - offPx;
        for (String line : lines) {
            if (ly + PROFILE_LINE_H >= viewTop && ly <= pr[3]) {
                g.fill(x1, ly + 4, x1 + 3, ly + 5, RpTheme.alphaBlend(STRUCT_RED, 0xCC));
                g.drawString(font, line, x1 + 6, ly, RpTheme.TEXT_SECONDARY);
            }
            ly += PROFILE_LINE_H;
        }
        g.disableScissor();
        // 画像滚动条（可拖动；仅在溢出时出现，轨道与列表滚动条同一画法；单位=像素）
        if (lines.size() > pr[5]) {
            RpScrollbar.draw(
                    g,
                    pr[2] - 7,
                    pr[1],
                    pr[3],
                    lines.size() * PROFILE_LINE_H,
                    pr[5] * PROFILE_LINE_H,
                    Math.round(profileScroll));
        }
    }

    private void renderDockEquip(GuiGraphics g, JsonObject p, int x1, int x2, int top, int bottom, int mx, int my) {
        int w = x2 - x1;
        if (w < 60) {
            return;
        }
        sectionHead(
                g,
                x1,
                top,
                w,
                "01",
                Component.translatable("ccnr_rp.gui.character.section.equipment")
                        .getString());
        int stripTop = top + 13;
        int stripBottom = Math.max(stripTop + 2, bottom);
        ItemStack hovered = renderEquipStrip(g, x1, stripTop, x2, stripBottom, loadoutOf(p), false, true, mx, my);
        if (!hovered.isEmpty()) {
            g.renderTooltip(font, hovered, mx, my);
        }
    }

    /**
     * 横向装备图标条（席位卡与抽屉共用）：head/chest/legs/boots/weapon 五格，
     * 空槽画 45° 排线，武器槽加红色楔形；悬停显示物品词条。槽位尺寸随可用宽度自适应；
     * {@code withLabels} 且竖向有余量时在槽下补槽名（席位卡太矮时自动省略，不溢出卡片）。
     */
    private ItemStack renderEquipStrip(
            GuiGraphics g,
            int x1,
            int y1,
            int x2,
            int y2,
            JsonObject loadout,
            boolean onAccent,
            boolean withLabels,
            int mx,
            int my) {
        ItemStack[] stacks = {
            armorStack(loadout, 39),
            armorStack(loadout, 38),
            armorStack(loadout, 37),
            armorStack(loadout, 36),
            weaponStack(loadout)
        };
        String[] labels = {
            Component.translatable("ccnr_rp.gui.character.equip.head").getString(),
            Component.translatable("ccnr_rp.gui.character.equip.chest").getString(),
            Component.translatable("ccnr_rp.gui.character.equip.legs").getString(),
            Component.translatable("ccnr_rp.gui.character.equip.boots").getString(),
            Component.translatable("ccnr_rp.gui.character.equip.weapon").getString()
        };
        int avail = x2 - x1;
        int n = stacks.length;
        int gap = 2;
        // 该职位**一件装备都没有**时，别铺 5 个空槽（看上去就是一排"空行"）：只给一条暗刻度 +
        // 「无装备」提示，信息量一样但视觉干净（docs/14 §5.6）。
        int loadedCount = 0;
        for (ItemStack st : stacks) {
            if (st != null && !st.isEmpty()) {
                loadedCount++;
            }
        }
        if (loadedCount == 0) {
            if (y2 - y1 < 6) {
                return ItemStack.EMPTY;
            }
            int midY = Math.min(y2, y1 + Math.max(3, (y2 - y1) / 2));
            g.fill(x1, midY, x2, midY + 1, RpTheme.alphaBlend(RpTheme.BORDER_DIM, 0x80));
            String none =
                    Component.translatable("ccnr_rp.gui.character.equip.none").getString();
            int tw = font.width(none);
            if (x2 - x1 > tw + 16) {
                g.drawString(font, none, x1 + (x2 - x1 - tw) / 2, midY - 8, RpTheme.TEXT_DIM, false);
            }
            return ItemStack.EMPTY;
        }
        int slot = Math.min(16, Math.max(8, (avail - gap * (n - 1)) / n));
        int totalW = slot * n + gap * (n - 1);
        int sx = x1 + Math.max(0, (avail - totalW) / 2);
        int sy = y1 + Math.max(0, Math.min(4, (y2 - y1 - slot) / 2));
        if (y2 - y1 < slot) {
            return ItemStack.EMPTY;
        }
        // 槽名只在竖向有余量、且每个槽名都能完整塞进槽宽时绘制（英文槽名比中文宽，放不下就整体省略）
        int labelW = 0;
        for (String l : labels) {
            labelW = Math.max(labelW, font.width(l));
        }
        boolean drawLabels = withLabels && y2 - (sy + slot) >= 10 && labelW <= slot;
        ItemStack hovered = ItemStack.EMPTY;
        for (int i = 0; i < n; i++) {
            int bx = sx + i * (slot + gap);
            ItemStack stack = stacks[i];
            boolean loaded = stack != null && !stack.isEmpty();
            boolean weapon = i == n - 1;
            int edge = loaded ? (weapon ? RpTheme.RED_LINE : RpTheme.CYAN_DIM) : RpTheme.BORDER_DIM;
            RpRoundRect.outlined(
                    g, bx, sy, bx + slot, sy + slot, 2f, edge, onAccent ? RpTheme.TRANSPARENT : RpTheme.SLOT_BG);
            if (loaded) {
                if (weapon) {
                    wedge(g, bx, sy, bx + slot, sy + slot, 1, Math.max(3, slot / 3), RpTheme.RED);
                }
                // 物品图标固定 16px：缩放靠 pose 平移（槽位尺寸变化时保持图标清晰）
                g.pose().pushPose();
                float sc = slot / 16f;
                g.pose().translate(bx, sy, 0);
                g.pose().scale(sc, sc, 1f);
                g.renderItem(stack, 0, 0);
                g.pose().popPose();
                if (mx >= bx && mx <= bx + slot && my >= sy && my <= sy + slot) {
                    hovered = stack;
                }
            } else {
                hatch(g, bx + 1, sy + 1, bx + slot - 1, sy + slot - 1, 4, RpTheme.alphaBlend(RpTheme.TEXT_DIM, 0x88));
            }
            if (drawLabels) {
                int lw = font.width(labels[i]);
                g.drawString(font, labels[i], bx + (slot - lw) / 2, sy + slot + 2, RpTheme.TEXT_DIM, false);
            }
        }
        return hovered;
    }

    /**
     * 全息投影光柱（纯绘制，不改模型渲染）：人物背后一条纵向投影，交代"立绘悬浮"。
     * 硬边取向：2px 核心线 + 两侧虚线柱，无柔和渐变。
     */
    private static void renderHoloBeam(GuiGraphics g, int cx, int top, int bottom, int cy, int scale) {
        int baseY = Math.min(bottom - 6, cy + scale);
        if (top >= baseY) {
            return;
        }
        int halfW = Math.max(18, scale * 3 / 2);
        g.fill(cx - 1, top, cx + 1, baseY, RpTheme.PREVIEW_BEAM);
        for (int i = 1; i <= 3; i++) {
            int dx = i * Math.max(6, halfW / 4);
            int c = RpTheme.alphaBlend(RpTheme.CYAN, Math.max(8, 0x24 - i * 7));
            for (int y = top + i * 3; y < baseY; y += 8) {
                g.fill(cx - dx, y, cx - dx + 1, Math.min(y + 4, baseY), c);
                g.fill(cx + dx, y, cx + dx + 1, Math.min(y + 4, baseY), c);
            }
        }
        g.fill(cx - 4, top, cx + 5, top + 1, RpTheme.alphaBlend(RpTheme.CYAN, 0x66));
    }

    private static JsonObject loadoutOf(JsonObject p) {
        if (p == null || !p.has("loadout") || !p.get("loadout").isJsonObject()) {
            return null;
        }
        return p.getAsJsonObject("loadout");
    }

    private static ItemStack armorStack(JsonObject loadout, int slot) {
        if (loadout == null || !loadout.has("armor") || !loadout.get("armor").isJsonArray()) {
            return ItemStack.EMPTY;
        }
        try {
            List<ProfessionJson.SlotItem> list =
                    ProfessionJson.listFromJson(loadout.getAsJsonArray("armor"), "armor", new ArrayList<>());
            for (ProfessionJson.SlotItem s : list) {
                if (s.slot() == slot) {
                    return ItemStackCodec.toStack(s);
                }
            }
        } catch (Exception ignored) {
            // 预览装备失败不致命
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack weaponStack(JsonObject loadout) {
        if (loadout == null) {
            return ItemStack.EMPTY;
        }
        try {
            if (loadout.has("inventory") && loadout.get("inventory").isJsonArray()) {
                List<ProfessionJson.SlotItem> inv = ProfessionJson.listFromJson(
                        loadout.getAsJsonArray("inventory"), "inventory", new ArrayList<>());
                for (ProfessionJson.SlotItem s : inv) {
                    ItemStack st = ItemStackCodec.toStack(s);
                    if (isWeapon(st)) {
                        return st;
                    }
                }
                for (ProfessionJson.SlotItem s : inv) {
                    ItemStack st = ItemStackCodec.toStack(s);
                    if (!st.isEmpty()) {
                        return st;
                    }
                }
            }
        } catch (Exception ignored) {
            // 预览装备失败不致命
        }
        return ItemStack.EMPTY;
    }

    private static boolean isWeapon(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        net.minecraft.world.item.Item it = stack.getItem();
        if (it instanceof net.minecraft.world.item.SwordItem
                || it instanceof net.minecraft.world.item.AxeItem
                || it instanceof net.minecraft.world.item.TridentItem
                || it instanceof net.minecraft.world.item.BowItem
                || it instanceof net.minecraft.world.item.CrossbowItem
                || it instanceof net.minecraft.world.item.ShieldItem) {
            return true;
        }
        net.minecraft.resources.ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it);
        String p2 = key == null ? "" : key.getPath().toLowerCase(Locale.ROOT);
        return p2.contains("gun")
                || p2.contains("rifle")
                || p2.contains("pistol")
                || p2.contains("weapon")
                || p2.contains("sword")
                || p2.contains("carbine");
    }

    // ---------- 重新部署确认弹窗（暂时隐藏） ----------

    /** 确认弹窗两个按钮矩形（确认/取消），渲染与点击共用。 */
    private int[][] confirmButtonRects() {
        int cw = Math.min(420, px2 - px1 - 40);
        int ch = 130;
        int cx = px1 + (px2 - px1 - cw) / 2;
        int cy = py1 + (py2 - py1 - ch) / 2;
        int bw = (cw - 40) / 2;
        int by = cy + ch - 36;
        return new int[][] {
            {cx + 12, by, cx + 12 + bw, by + 22},
            {cx + cw - 12 - bw, by, cx + cw - 12, by + 22}
        };
    }

    private void renderKillConfirm(GuiGraphics g, int mx, int my) {
        int cw = Math.min(420, px2 - px1 - 40);
        int ch = 130;
        int cx = px1 + (px2 - px1 - cw) / 2;
        int cy = py1 + (py2 - py1 - ch) / 2;
        RpTheme.lightScrim(g, width, height);
        RpTheme.terminalPanel(g, cx, cy, cx + cw, cy + ch, 10f);
        g.drawString(
                font,
                Component.translatable("ccnr_rp.gui.character.kill_confirm_title")
                        .getString(),
                cx + 14,
                cy + 12,
                RpTheme.CYAN,
                true);
        JsonObject p = findProfession(confirmDeployId);
        String name = p == null ? confirmDeployId : str(p, "name");
        String msg = Component.translatable("ccnr_rp.gui.character.kill_confirm_msg", name)
                .getString();
        int ly = cy + 40;
        for (String line : wrapText(msg, cw - 28)) {
            g.drawString(font, line, cx + 14, ly, RpTheme.TEXT_PRIMARY, true);
            ly += 13;
        }
        int[][] rects = confirmButtonRects();
        boolean hYes = mx >= rects[0][0] && mx <= rects[0][2] && my >= rects[0][1] && my <= rects[0][3];
        boolean hNo = mx >= rects[1][0] && mx <= rects[1][2] && my >= rects[1][1] && my <= rects[1][3];
        // 确认/取消走统一按钮配方（主操作=白底反白字 / 次操作=控件底白字）：
        // 反白底上的文字必须用 ACCENT_TEXT，此前的白底白字不可读。
        RpButton.draw(
                g,
                rects[0][0],
                rects[0][1],
                rects[0][2],
                rects[0][3],
                Component.translatable("ccnr_rp.gui.character.kill_confirm_yes").getString(),
                RpTheme.CYAN,
                true,
                hYes);
        RpButton.draw(
                g,
                rects[1][0],
                rects[1][1],
                rects[1][2],
                rects[1][3],
                Component.translatable("ccnr_rp.gui.character.kill_confirm_no").getString(),
                RpTheme.PANEL_BORDER,
                false,
                hNo);
    }

    // ============================================================
    // 构成主义硬边绘制原语（仅 K 面板使用）
    // ------------------------------------------------------------
    // 设计取向（docs/14 §1）：硬边、直角、45° 对角线、黑/白灰/红三色。
    // 刻意**不**放进 RpTheme/RpIcons：本界面是唯一需要这批原语的地方，留在本地可让改动面
    // 收敛在这一个文件内，不波及其它界面；若日后第二个界面需要，再提升为共享原语。
    // 红色在此是**结构色**（序号块/刻度/断弧/楔形），与「危险实心红」RED_BG_FILL 区分。
    // ============================================================

    /** 结构红：比警戒红略暗，用于线条与小块。 */
    private static final int STRUCT_RED = 0xFFE03028;

    /** 45° 排线（构成主义质感：空槽 / 水印扇区）。step 为线距，内部按面积抬步长做性能护栏。 */
    private static void hatch(GuiGraphics g, int x1, int y1, int x2, int y2, int step, int color) {
        int w = x2 - x1;
        int h = y2 - y1;
        if (w <= 1 || h <= 1) {
            return;
        }
        int st = Math.max(3, step);
        long area = (long) w * h;
        long budget = 1200L; // 单次排线的填充段上限
        if (area / st > budget) {
            st = (int) Math.max(st, (area + budget - 1) / budget);
        }
        for (int c = x1 - y2; c <= x2 - y1; c += st) {
            int sx = Math.max(x1, y1 + c);
            int ex = Math.min(x2, y2 + c);
            for (int x = sx; x < ex; x++) {
                int y = x - c;
                if (y >= y1 && y < y2) {
                    g.fill(x, y, x + 1, y + 1, color);
                }
            }
        }
    }

    /** 角落楔形（构成主义斜切块）。corner：0=左上 1=右上 2=右下 3=左下。 */
    private static void wedge(GuiGraphics g, int x1, int y1, int x2, int y2, int corner, int size, int color) {
        int s = Math.max(2, Math.min(size, Math.min(x2 - x1, y2 - y1)));
        for (int i = 0; i < s; i++) {
            int len = s - i;
            switch (corner) {
                case 0 -> g.fill(x1, y1 + i, x1 + len, y1 + i + 1, color);
                case 1 -> g.fill(x2 - len, y1 + i, x2, y1 + i + 1, color);
                case 2 -> g.fill(x2 - len, y2 - 1 - i, x2, y2 - i, color);
                default -> g.fill(x1, y2 - 1 - i, x1 + len, y2 - i, color);
            }
        }
    }

    /** 四角 L 型刻度（工程图角标；比 RpTheme.cornerBrackets 更细，用于卡片与槽位）。 */
    private static void cornerTicks(GuiGraphics g, int x1, int y1, int x2, int y2, int len, int color) {
        g.fill(x1, y1, x1 + len, y1 + 1, color);
        g.fill(x1, y1, x1 + 1, y1 + len, color);
        g.fill(x2 - len, y1, x2, y1 + 1, color);
        g.fill(x2 - 1, y1, x2, y1 + len, color);
        g.fill(x1, y2 - 1, x1 + len, y2, color);
        g.fill(x1, y2 - len, x1 + 1, y2, color);
        g.fill(x2 - len, y2 - 1, x2, y2, color);
        g.fill(x2 - 1, y2 - len, x2, y2, color);
    }

    /** 顶部刻度尺（工程图纸刻度）：minor 一格，major 处长刻度。 */
    private static void tickScale(GuiGraphics g, int x1, int x2, int y, int minor, int major, int color) {
        if (minor < 2 || x2 <= x1) {
            return;
        }
        for (int x = x1; x < x2; x += minor) {
            boolean maj = major > 0 && ((x - x1) % major) == 0;
            g.fill(x, y, x + 1, y + (maj ? 4 : 2), color);
        }
    }

    /** 全大写 + 字距放大的标题宽度。 */
    private int trackedWidth(String s, int spacing) {
        String up = s == null ? "" : s.toUpperCase(Locale.ROOT);
        if (up.isEmpty()) {
            return 0;
        }
        int w = 0;
        for (int i = 0; i < up.length(); i++) {
            w += font.width(up.substring(i, i + 1)) + spacing;
        }
        return w - spacing;
    }

    /** 全大写 + 字距放大的终端标题（字距拉开才有工程图/海报感）。 */
    private void tracked(GuiGraphics g, String s, int x, int y, int color, int spacing) {
        String up = s == null ? "" : s.toUpperCase(Locale.ROOT);
        int cx = x;
        for (int i = 0; i < up.length(); i++) {
            String ch = up.substring(i, i + 1);
            g.drawString(font, ch, cx, y, color, false);
            cx += font.width(ch) + spacing;
        }
    }

    /**
     * 小节头（构成主义）：红色序号块（反白数字）+ 字距放大标题 + 尾部细线（线首 2px 红）。
     * 返回自 x 起占用的宽度，便于调用方在右侧接续排版。
     */
    private int sectionHead(GuiGraphics g, int x, int y, int w, String index, String label) {
        int h = 10;
        if (w < 12) {
            return 0;
        }
        int cur = x;
        if (index != null && !index.isEmpty() && w >= 64) {
            int bw = font.width(index) + 4;
            g.fill(cur, y, cur + bw, y + h, STRUCT_RED);
            g.drawString(font, index, cur + 2, y + 1, RpTheme.TEXT_PRIMARY, false);
            cur += bw + 5;
        }
        String text = label == null ? "" : label;
        int spacing = 1;
        int tw = trackedWidth(text, spacing);
        int avail = x + w - cur;
        if (tw > avail) {
            spacing = 0;
            tw = trackedWidth(text, 0);
        }
        if (tw > avail) {
            text = font.plainSubstrByWidth(text.toUpperCase(Locale.ROOT), Math.max(0, avail - 2)) + "…";
            spacing = 0;
            tw = trackedWidth(text, 0);
        }
        tracked(g, text, cur, y + 1, RpTheme.TEXT_PRIMARY, spacing);
        cur += tw + 6;
        int end = x + w;
        if (end - cur >= 6) {
            g.fill(cur, y + h / 2, end, y + h / 2 + 1, RpTheme.alphaBlend(RpTheme.PANEL_BORDER_BRIGHT, 0x99));
            g.fill(cur, y + h / 2, cur + 3, y + h / 2 + 1, STRUCT_RED);
        }
        return cur - x;
    }

    /**
     * 终端读数框：内陷底 + 左 2px 结构红条 + 顶缘亮线 + 右缘刻度 + 左右两段文字（右段右对齐）。
     */
    private void readout(
            GuiGraphics g, int x, int y, int w, int h, String left, String right, int rightColor, int barColor) {
        if (w < 24 || h < 8) {
            return;
        }
        g.fill(x, y, x + w, y + h, RpTheme.SURFACE_INSET);
        g.fill(x, y, x + 2, y + h, barColor);
        g.fill(x + 2, y, x + w, y + 1, RpTheme.alphaBlend(RpTheme.PANEL_BORDER_BRIGHT, 0x66));
        int tx = x + 5;
        String rt = right == null ? "" : right;
        // 窄窗口下右侧字段先按 55% 宽度裁剪，保证左段（ID/等级等关键读数）仍有位置显示
        int cap = Math.max(20, w * 55 / 100);
        if (font.width(rt) > cap) {
            rt = font.plainSubstrByWidth(rt, cap - 1) + "…";
        }
        int rw = rt.isEmpty() ? 0 : font.width(rt);
        int maxLeft = Math.max(0, w - 12 - rw);
        String lab = left == null ? "" : left;
        if (font.width(lab) > maxLeft) {
            lab = font.plainSubstrByWidth(lab, Math.max(0, maxLeft - 1)) + "…";
        }
        int ty = y + (h - 8) / 2;
        g.drawString(font, lab, tx, ty, RpTheme.TEXT_SECONDARY, false);
        if (rw > 0) {
            g.drawString(font, rt, x + w - 6 - rw, ty, rightColor, false);
        }
        for (int i = 2; i < h - 2; i += 3) {
            g.fill(x + w - 3, y + i, x + w - 1, y + i + 1, RpTheme.alphaBlend(RpTheme.PANEL_BORDER_BRIGHT, 0x55));
        }
    }

    /** 把 "A: x // B: y" 形式的本地化终端行拆成左右两段（无分隔符则整段作左段）。 */
    private static String[] splitPair(String s) {
        if (s == null) {
            return new String[] {"", ""};
        }
        int i = s.indexOf(" // ");
        return i < 0 ? new String[] {s, ""} : new String[] {s.substring(0, i), s.substring(i + 4)};
    }

    /** 按像素宽度贪心断行（共享入口，算法见 RpTheme.wrapText）。 */
    private java.util.List<String> wrapText(String text, int maxW) {
        return RpTheme.wrapText(font, text, maxW);
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
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
