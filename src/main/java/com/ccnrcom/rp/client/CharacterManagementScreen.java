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
 * K 面板 v2——「职位选择」机密终端（角色库已删除，改为按职位部署）。
 * 三栏：左＝机构过滤导航｜中＝职位列表（需求等级绿/红）｜右＝职位详情 + 部署按钮（未达等级置红）。
 * 部署＝选择职位，等级门控由服务端校验；客户端未达等级时部署按钮变红/禁用。
 */
public class CharacterManagementScreen extends Screen {

    private static CharacterManagementScreen open;

    private final List<JsonObject> professions = new ArrayList<>();
    private final List<JsonObject> factionMeta = new ArrayList<>();
    private final List<int[]> navBounds = new ArrayList<>();
    private final List<int[]> rowBounds = new ArrayList<>();

    private String selectedId = "";
    private String filterFaction = "";
    private int scroll = 0;
    private int navScroll = 0;
    private String notice = "";
    private long noticeUntil = 0;
    /** 重新部署确认弹窗（暂时隐藏，不触发）：非空=正在确认该职位；如需恢复，在场点部署时置 confirmDeployId。 */
    private String confirmDeployId = "";

    // 布局几何
    private int px1, py1, px2, py2;
    private int hdrY1, hdrY2;
    private int bodyY1, bodyY2;
    private int nlX1, nlX2;
    private int mlX1, mlX2;
    private int rlX1, rlX2;
    private int deployY;
    private int mgrX1, mgrY1, mgrX2, mgrY2;

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
        hdrY1 = py1 + 4;
        hdrY2 = hdrY1 + 20;
        bodyY1 = hdrY2 + 26;
        bodyY2 = py2 - 8;
        int innerW = px2 - px1 - 16;
        int nlw = Math.max(84, Math.min(200, innerW * 22 / 100));
        int mlw = Math.max(150, Math.min(300, innerW * 30 / 100));
        nlX1 = px1 + 8;
        nlX2 = nlX1 + nlw;
        mlX1 = nlX2 + 8;
        mlX2 = mlX1 + mlw;
        rlX1 = mlX2 + 8;
        rlX2 = px2 - 8;
        deployY = bodyY2 - 70;
        String mgr = Component.translatable("ccnr_rp.gui.character.manage").getString();
        mgrX2 = px2 - 40;
        mgrX1 = mgrX2 - font.width(mgr) - 8;
        mgrY1 = hdrY1 - 1;
        mgrY2 = hdrY1 + 17;
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

    private void rebuild() {
        clearWidgets();
        navBounds.clear();
        rowBounds.clear();
        int navH = 22;
        int navGap = 4;
        int y = bodyY1 + 2;
        navBounds.add(new int[] {nlX1, y, nlX2, y + navH, -1});
        y += navH + navGap;
        for (int i = 0; i < factionMeta.size(); i++) {
            navBounds.add(new int[] {nlX1, y, nlX2, y + navH, i});
            y += navH + navGap;
        }
        List<JsonObject> visible = filtered();
        int rowH = 44;
        int rowGap = 4;
        int listH = bodyY2 - bodyY1 - 2;
        int maxVisible = Math.max(1, (listH + rowGap) / (rowH + rowGap));
        int offset = Math.min(scroll, Math.max(0, visible.size() - maxVisible));
        for (int i = 0; i < visible.size() && i < maxVisible; i++) {
            int ry = bodyY1 + 2 + i * (rowH + rowGap);
            rowBounds.add(new int[] {mlX1, ry, mlX2, ry + rowH});
        }
        buildRight();
    }

    private void buildRight() {
        clearWidgets();
        JsonObject p = findProfession(selectedId);
        int x = rlX1;
        int w = rlX2 - rlX1;
        if (p != null) {
            int ay = deployY;
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
                    RpButton.primary(x, ay, w, 22, Component.translatable("ccnr_rp.gui.character.deploy"), b -> {
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
                // 空位不足：禁用并提示限制（优先职业上限，其次阵营上限）——[ FULL occ/lim ]
                if (profFull) {
                    deploy.setMessage(
                            Component.translatable("ccnr_rp.gui.character.deploy.full", profOccupied, profLimit));
                } else {
                    deploy.setMessage(
                            Component.translatable("ccnr_rp.gui.character.deploy.full", facOccupied, facLimit));
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
    }

    private List<JsonObject> filtered() {
        if (filterFaction.isEmpty()) {
            return professions;
        }
        return professions.stream()
                .filter(p -> str(p, "factionId").equals(filterFaction))
                .toList();
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
            if (ClientCharacterState.isAdmin()) {
                net.minecraft.client.Minecraft.getInstance().setScreen(new RpAdminScreen());
            } else {
                notice("ccnr_rp.command.no_permission");
            }
            return true;
        }
        // 滚动条点击（分类=1，职位列表=2）
        int navNs = RpScrollbar.clickV(
                (int) mx, (int) my, nlX2 - 7, nlX2 - 2, bodyY1, bodyY2, navBounds.size(), navVisible(), navScroll, 1);
        if (navNs >= 0) {
            navScroll = (int) Math.max(0, Math.min(navNs, Math.max(0, navBounds.size() - navVisible())));
            return true;
        }
        int listNs = RpScrollbar.clickV(
                (int) mx,
                (int) my,
                mlX2 - 7,
                mlX2 - 2,
                bodyY1,
                bodyY2,
                filtered().size(),
                maxVisibleRows(),
                scroll,
                2);
        if (listNs >= 0) {
            scroll = (int) Math.max(0, Math.min(listNs, Math.max(0, filtered().size() - maxVisibleRows())));
            rebuild();
            return true;
        }
        int navEnd = Math.min(navBounds.size(), navScroll + navVisible());
        for (int i = navScroll; i < navEnd; i++) {
            int[] b = navRect(i);
            if (mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3]) {
                filterFaction = b[4] < 0 ? "" : str(factionMeta.get(b[4]), "id");
                scroll = 0;
                rebuild();
                return true;
            }
        }
        int offset = offsetOfRows();
        for (int i = 0; i < rowBounds.size(); i++) {
            int[] b = rowBounds.get(i);
            if (mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3]) {
                List<JsonObject> visible = filtered();
                if (offset + i < visible.size()) {
                    selectedId = str(visible.get(offset + i), "id");
                }
                rebuild();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int b, double dx, double dy) {
        int ns = RpScrollbar.dragV((int) my);
        if (ns >= 0) {
            if (RpScrollbar.dragId() == 1) {
                navScroll = (int) Math.max(0, Math.min(ns, Math.max(0, navBounds.size() - navVisible())));
            } else {
                scroll = (int) Math.max(0, Math.min(ns, Math.max(0, filtered().size() - maxVisibleRows())));
                rebuild();
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

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (mx >= nlX1 && mx <= nlX2 && my >= bodyY1 - 4 && my <= bodyY2) {
            navScroll =
                    (int) Math.max(0, Math.min(navScroll - delta / 10, Math.max(0, navBounds.size() - navVisible())));
            return true;
        }
        if (mx >= mlX1 && mx <= mlX2 && my >= bodyY1 - 4 && my <= bodyY2) {
            scroll = (int) Math.max(0, scroll - delta / 10);
            rebuild();
        }
        return true;
    }

    private int maxVisibleRows() {
        int rowH = 44;
        int gap = 4;
        int listH = bodyY2 - bodyY1 - 2;
        return Math.max(1, (listH + gap) / (rowH + gap));
    }

    private int offsetOfRows() {
        return Math.min(scroll, Math.max(0, filtered().size() - maxVisibleRows()));
    }

    private void notice(String key) {
        if (key == null || key.isBlank()) {
            notice = "";
            return;
        }
        notice = Component.translatable(key).getString();
        noticeUntil = System.currentTimeMillis() + 2600;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g);
        // 完整终端框（近黑底 + 灰描边 + 四角角标 + 顶部高光 rail）
        RpTheme.terminalFrame(g, px1, py1, px2, py2, RpTheme.RADIUS_LARGE);
        // 面板网格叠层（背景纹理，内容层之上不再叠加，保证文字可读性与参考一致的「电子屏」质感）
        RpTheme.gridOverlay(g, px1 + 4, py1 + 4, px2 - 4, py2 - 4);
        renderHeader(g, mx, my);
        renderColHeaders(g);
        renderNav(g, mx, my);
        renderList(g, mx, my);
        renderDetail(g, mx, my);
        renderNotice(g);
        super.render(g, mx, my, partial);
        // CRT 扫描线（在内容层之上，低透明度屏幕质感；对齐前端 body::before）
        RpTheme.scanlines(g, px1, py1, px2, py2);
        if (!confirmDeployId.isEmpty()) {
            renderKillConfirm(g, mx, my);
        }
    }

    private void renderHeader(GuiGraphics g, int mx, int my) {
        int y = hdrY1 + 5;
        // Reference header: [CCNR]  TERMINAL  LV.x // STATUS: y   |   [ ADMIN ]  X
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
        g.fill(px1 + 8, hdrY2 - 1, px2 - 8, hdrY2, RpTheme.CYAN_DIM);
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

    private void renderColHeaders(GuiGraphics g) {
        int y = hdrY2 + 8;
        g.drawString(
                font,
                RpTheme.tag(Component.translatable("ccnr_rp.gui.character.nav").getString()),
                nlX1,
                y,
                RpTheme.TEXT_DIM);
        g.drawString(
                font,
                RpTheme.tag(Component.translatable("ccnr_rp.gui.character.position_list")
                                .getString()) + " "
                        + RpTheme.tag(String.valueOf(filtered().size())),
                mlX1,
                y,
                RpTheme.TEXT_DIM);
        g.drawString(
                font,
                RpTheme.tag(Component.translatable("ccnr_rp.gui.character.detail_header")
                        .getString()),
                rlX1,
                y,
                RpTheme.TEXT_DIM);
        g.fill(nlX1, y + 12, rlX2, y + 13, RpTheme.PANEL_BORDER);
    }

    private int[] navRect(int i) {
        int navH = 22;
        int gap = 4;
        int y = bodyY1 + 2 + (i - navScroll) * (navH + gap);
        int facIdx = navBounds.get(i)[4]; // 存储的阵营索引（-1=全部），非位置下标
        return new int[] {nlX1, y, nlX2, y + navH, facIdx};
    }

    private void renderNav(GuiGraphics g, int mx, int my) {
        int end = Math.min(navBounds.size(), navScroll + navVisible());
        for (int i = navScroll; i < end; i++) {
            int[] b = navRect(i);
            JsonObject fac = b[4] < 0 ? null : factionMeta.get(b[4]);
            boolean sel =
                    !filterFaction.isEmpty() && fac != null && str(fac, "id").equals(filterFaction);
            boolean hover = mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3];
            // 阵营无可用职业复活（该阵营所有职业均无部署余额）→ 行标红
            boolean noAvail = fac != null && facNoAvailable(fac);
            if (sel) {
                RpTheme.selectedBar(g, b[0], b[1], b[2], b[3], 6f);
            } else {
                RpRoundRect.outlined(
                        g,
                        b[0],
                        b[1],
                        b[2],
                        b[3],
                        6f,
                        noAvail ? RpTheme.RED_LINE : (hover ? RpTheme.PANEL_BORDER_BRIGHT : RpTheme.PANEL_BORDER),
                        noAvail
                                ? RpTheme.alphaBlend(RpTheme.RED_DIM, 0x30)
                                : (hover ? RpTheme.PANEL_BG_ALT : RpTheme.PANEL_BG));
            }
            if (fac == null) {
                RpIcons.badge(g, b[0] + 12, b[1] + 11, 8, "target", 2, sel);
                g.drawString(
                        font,
                        RpTheme.tag(Component.translatable("ccnr_rp.gui.character.filter.all")
                                .getString()),
                        b[0] + 25,
                        b[1] + 7,
                        sel ? RpTheme.ACCENT_TEXT : RpTheme.TEXT_SECONDARY,
                        true);
            } else {
                RpIcons.factionBadge(g, b[0] + 12, b[1] + 11, 8, fac, sel);
                String name = str(fac, "name");
                int maxW = b[2] - b[0] - 28;
                if (font.width(name) > maxW) {
                    name = font.plainSubstrByWidth(name, maxW - 1) + "…";
                }
                g.drawString(
                        font,
                        RpTheme.tag(name),
                        b[0] + 25,
                        b[1] + 7,
                        sel ? RpTheme.ACCENT_TEXT : (noAvail ? RpTheme.RED_LINE : RpTheme.TEXT_SECONDARY),
                        true);
            }
        }
    }

    private void renderList(GuiGraphics g, int mx, int my) {
        RpRoundRect.outlined(g, mlX1 - 3, bodyY1 - 2, mlX2 + 3, bodyY2, 8f, RpTheme.PANEL_BORDER, RpTheme.PANEL_BG);
        List<JsonObject> visible = filtered();
        int offset = offsetOfRows();
        for (int i = 0; i < rowBounds.size(); i++) {
            int[] b = rowBounds.get(i);
            int idx = offset + i;
            if (idx >= visible.size()) {
                break;
            }
            JsonObject p = visible.get(idx);
            boolean sel = str(p, "id").equals(selectedId);
            boolean hover = mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3];
            // 职业无可用部署余额（上限 0=禁止 或在职满）→ 行标红
            boolean noBal = profNoBalance(p);
            if (sel) {
                RpTheme.selectedBar(g, b[0], b[1], b[2], b[3], 8f);
            } else {
                RpRoundRect.outlined(
                        g,
                        b[0],
                        b[1],
                        b[2],
                        b[3],
                        8f,
                        noBal ? RpTheme.RED_LINE : (hover ? RpTheme.PANEL_BORDER_BRIGHT : RpTheme.PANEL_BORDER),
                        noBal
                                ? RpTheme.alphaBlend(RpTheme.RED_DIM, 0x26)
                                : (hover
                                        ? RpTheme.PANEL_BG_ALT
                                        : (i % 2 == 0 ? RpTheme.PANEL_BG : RpTheme.PANEL_BG_EVEN)));
            }
            JsonObject fac = factionMeta(str(p, "factionId"));
            RpIcons.factionBadge(g, b[0] + 14, b[1] + 14, 7, fac, false);
            g.drawString(
                    font,
                    str(p, "name"),
                    b[0] + 34,
                    b[1] + 6,
                    sel ? RpTheme.ACCENT_TEXT : (noBal ? RpTheme.RED_LINE : RpTheme.TEXT_PRIMARY),
                    true);
            g.drawString(
                    font,
                    factionName(p, factionMeta),
                    b[0] + 34,
                    b[1] + 20,
                    sel ? RpTheme.ACCENT_TEXT : RpTheme.TEXT_SECONDARY);
            // 灰色职位 ID
            g.drawString(font, str(p, "id"), b[0] + 34, b[1] + 32, sel ? RpTheme.ACCENT_TEXT : RpTheme.TEXT_DIM);
            boolean met = userLevel() >= unlockLevel(p);
            String tag = "Lv " + unlockLevel(p);
            int tagW = font.width(tag) + 8;
            int tx2 = b[2] - 7;
            int color = met ? RpTheme.STATUS_ALIVE : RpTheme.RED_LINE;
            RpRoundRect.fill(g, tx2 - tagW, b[1] + 6, tx2, b[1] + 18, 3f, RpTheme.alphaBlend(color, met ? 0x66 : 0xFF));
            g.drawString(font, tag, tx2 - tagW + 4, b[1] + 8, met ? RpTheme.ACCENT_TEXT : RpTheme.TEXT_PRIMARY, true);
            // 在职/上限小标签（部署限制预览；服务端 deploy 强校验）
            int lim = ClientCharacterState.professionLimit(str(p, "id"));
            if (lim >= 0) {
                int occ = ClientCharacterState.professionOccupied(str(p, "id"));
                String occTag = occ + "/" + lim;
                boolean full = occ >= lim;
                int oTagW = font.width(occTag) + 8;
                int ox2 = tx2 - tagW - 8;
                int oCol = full ? RpTheme.RED_LINE : RpTheme.STATUS_ALIVE;
                RpRoundRect.fill(
                        g, ox2 - oTagW, b[1] + 6, ox2, b[1] + 18, 3f, RpTheme.alphaBlend(oCol, full ? 0xFF : 0x66));
                g.drawString(
                        font,
                        occTag,
                        ox2 - oTagW + 4,
                        b[1] + 8,
                        full ? RpTheme.TEXT_PRIMARY : RpTheme.ACCENT_TEXT,
                        true);
            }
        }
        // 职位列表滚动条（可拖拽）
        RpScrollbar.draw(g, mlX2 - 7, bodyY1, bodyY2, visible.size(), maxVisibleRows(), offsetOfRows());
        // 左侧机构/团队分类滚动条（可拖拽）
        RpScrollbar.draw(g, nlX2 - 7, bodyY1, bodyY2, navBounds.size(), navVisible(), navScroll);
    }

    private int navVisible() {
        int navH = 22;
        int gap = 4;
        int listH = bodyY2 - bodyY1 - 2;
        return Math.max(1, (listH + gap) / (navH + gap));
    }

    private void renderDetail(GuiGraphics g, int mx, int my) {
        JsonObject p = findProfession(selectedId);
        int x = rlX1;
        int w = rlX2 - rlX1;
        int y = bodyY1;
        // Reference detail header: name / ID // FACTION / [ LV.REQ | CURRENT ] / PERSONNEL // FACTION
        int cardH = 80;
        RpTheme.sectionCard(g, x, y, x + w, y + cardH);
        // 构成主义档案卡框架：左缘红色结构条 + 四角刻度 + 右下斜切楔形 + 顶缘亮线 + 底缘刻度尺
        g.fill(x, y, x + 3, y + cardH, STRUCT_RED);
        g.fill(x + 3, y, x + w, y + 1, RpTheme.alphaBlend(RpTheme.PANEL_BORDER_BRIGHT, 0x99));
        cornerTicks(g, x + 4, y + 1, x + w - 1, y + cardH - 1, 5, RpTheme.PANEL_BORDER_BRIGHT);
        wedge(g, x, y, x + w, y + cardH, 2, 10, RpTheme.alphaBlend(STRUCT_RED, 0xCC));
        tickScale(g, x + 6, x + w - 6, y + cardH - 3, 8, 32, RpTheme.alphaBlend(RpTheme.TEXT_DIM, 0xCC));
        if (p == null) {
            g.drawString(
                    font,
                    Component.translatable("ccnr_rp.gui.character.detail.empty").getString(),
                    x + 10,
                    y + 18,
                    RpTheme.TEXT_DIM,
                    true);
            g.drawString(
                    font,
                    Component.translatable("ccnr_rp.gui.character.detail.empty_hint")
                            .getString(),
                    x + 10,
                    y + 30,
                    RpTheme.TEXT_DIM);
            return;
        }
        // 档案标题：字距放大 + 白色，下方一条硬边细线（构成主义标题带）
        String pname = str(p, "name");
        String nameFit = font.width(pname) > w - 20 ? font.plainSubstrByWidth(pname, w - 24) + "…" : pname;
        tracked(g, nameFit, x + 7, y + 4, RpTheme.TEXT_PRIMARY, 1);
        g.fill(x + 7, y + 15, x + w - 7, y + 16, RpTheme.alphaBlend(RpTheme.CYAN, 0x66));
        String facName = factionName(p, factionMeta);
        String idLine = Component.translatable("ccnr_rp.gui.character.term_id", str(p, "id"), facName)
                .getString();
        String[] idPair = splitPair(idLine);
        readout(g, x + 7, y + 18, w - 14, 11, idPair[0], idPair[1], RpTheme.TEXT_PRIMARY, STRUCT_RED);
        boolean met = userLevel() >= unlockLevel(p);
        String req = Component.translatable("ccnr_rp.gui.character.term_lvl", unlockLevel(p), userLevel())
                .getString();
        readout(g, x + 7, y + 31, w - 14, 11, req, "", RpTheme.TEXT_PRIMARY, met ? RpTheme.STATUS_ALIVE : RpTheme.RED);
        // 部署限制与当前在职（全局性限制预览；服务端 deploy 统一入口强校验）
        // 与部署按钮同条件：在职数按「不含本人」计算（在场换岗不重复占位），职业或阵营任一满即标红
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
                x + 7,
                y + 44,
                w - 14,
                11,
                per,
                perFac.replace(" // ", ""),
                RpTheme.TEXT_PRIMARY,
                (profFull || facFull) ? RpTheme.RED_LINE : RpTheme.STATUS_ALIVE);
        sectionHead(
                g,
                x + 7,
                y + 58,
                w - 14,
                "01",
                Component.translatable("ccnr_rp.gui.character.section.preview").getString());
        JsonObject loadout = loadoutOf(p);
        int contentTop = y + cardH + 2;
        // 项目简历（职业 profile，可选）：展示在战术装备预览之下、部署按钮之上；未配置的职位不占位
        String profile = str(p, "profile");
        List<String> resumeLines = profile.isBlank() ? List.of() : wrapText(profile, w - 16);
        int maxFit = Math.max(1, (deployY - 12 - contentTop - 40) / 10);
        int resumeLineCount = Math.min(resumeLines.size(), Math.min(MAX_PROFILE_LINES, maxFit));
        int resumeH = resumeLineCount == 0 ? 0 : 15 + resumeLineCount * 10;
        int contentBottom = deployY - 12 - resumeH;
        int modelW = Math.max(88, w * 38 / 100);
        // 预览区右侧：**阵营图标**（身份归属提示）。用该阵营配置的真实徽章（等级色环 + 图标 + 右下等级刻度），
        // 而不是抽象水印几何——验收要求此处直接显示图标。先画背景、再画模型/装备槽，故不遮挡内容。
        JsonObject facMeta = factionMeta(str(p, "factionId"));
        if (facMeta != null) {
            g.enableScissor(x, contentTop, x + w, contentBottom);
            int badgeCx = x + modelW + (w - modelW) * 3 / 4;
            int badgeCy = contentBottom - 26;
            int badgeR = Math.max(22, Math.min(40, (contentBottom - contentTop) / 4));
            RpIcons.factionBadge(g, badgeCx, badgeCy, badgeR, facMeta, false);
            g.disableScissor();
        }
        // 3D 人物立绘（使用玩家自己的皮肤，来自 CharacterPreview；XYZ 锁定正面视角）
        g.enableScissor(x, contentTop, x + modelW, contentBottom);
        JsonObject ch = new JsonObject();
        ch.addProperty("id", "pos-" + selectedId);
        ch.addProperty("name", str(p, "name"));
        int modelH = contentBottom - contentTop;
        int scale = Math.max(12, Math.min(36, Math.min(modelH / 3 - 6, modelW / 3)));
        int cy = contentTop + modelH / 2 + 4;
        // A档：全息投影底座（人物背后光束 + 底部发光地格；纯绘制层，置于模型之后）
        renderHoloBeam(g, x + modelW / 2, contentTop, contentBottom, cy, scale);
        CharacterPreview.render(g, x + modelW / 2, cy, scale, ch, loadout);
        g.disableScissor();
        // 战术装备实物预览（职位 loadout：头/胸/腿/靴/武器，悬停显示词条）——EQUIPMENT 区块
        int equipTop = contentTop + 10;
        int equipX = x + modelW + 8;
        sectionHead(
                g,
                equipX,
                contentTop,
                (x + w - 8) - equipX,
                "02",
                Component.translatable("ccnr_rp.gui.character.section.equipment")
                        .getString());
        renderEquipList(g, equipX, x + w - 8, equipTop, contentBottom, loadout, mx, my);
        // 项目简历（装备预览之下、部署按钮之上）
        if (resumeLineCount > 0) {
            renderProfileResume(g, x, w, contentBottom, deployY, resumeLines, resumeLineCount);
        }
    }

    /** 项目简历展示区最多行数（超长裁剪；显示区域受限时自动减少行数）。 */
    private static final int MAX_PROFILE_LINES = 4;

    /** 在装备预览之下、部署按钮之上渲染项目简历：小节头 + 折行文本（裁剪到展示区）。 */
    private void renderProfileResume(
            GuiGraphics g, int x, int w, int top, int buttonTop, List<String> lines, int lineCount) {
        int bottom = buttonTop - 8;
        sectionHead(
                g,
                x + 2,
                top + 1,
                w - 4,
                "03",
                Component.translatable("ccnr_rp.gui.character.section.profile").getString());
        g.enableScissor(x, top, x + w, bottom);
        int ly = top + 16;
        for (int i = 0; i < lineCount; i++) {
            // 行首刻度：构成主义「条目」标记
            g.fill(x + 2, ly + 4, x + 5, ly + 5, RpTheme.alphaBlend(STRUCT_RED, 0xCC));
            g.drawString(font, lines.get(i), x + 9, ly, RpTheme.TEXT_SECONDARY);
            ly += 10;
        }
        g.disableScissor();
    }

    private static JsonObject loadoutOf(JsonObject p) {
        if (p == null || !p.has("loadout") || !p.get("loadout").isJsonObject()) {
            return null;
        }
        return p.getAsJsonObject("loadout");
    }

    /**
     * 全息投影光柱（纯绘制，不改模型渲染）：人物背后一条纵向投影，交代"立绘悬浮"。
     * 只保留光柱本身——脚下的底座环 / 透视地格 / 汇聚斜线 / 十字 / 刻度尺按验收意见**全部删除**
     * （那些装饰与人物脚部叠在一起显得杂乱）；硬边取向不变：2px 核心线 + 两侧虚线柱，无柔和渐变。
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
        // 顶部定位刻度（投影源）
        g.fill(cx - 4, top, cx + 5, top + 1, RpTheme.alphaBlend(RpTheme.CYAN, 0x66));
    }

    private void renderEquipList(
            GuiGraphics g, int ex, int er, int top, int bottom, JsonObject loadout, int mx, int my) {
        String[] labels = {
            Component.translatable("ccnr_rp.gui.character.equip.head").getString(),
            Component.translatable("ccnr_rp.gui.character.equip.chest").getString(),
            Component.translatable("ccnr_rp.gui.character.equip.legs").getString(),
            Component.translatable("ccnr_rp.gui.character.equip.boots").getString(),
            Component.translatable("ccnr_rp.gui.character.equip.weapon").getString()
        };
        ItemStack[] stacks = {
            armorStack(loadout, 39),
            armorStack(loadout, 38),
            armorStack(loadout, 37),
            armorStack(loadout, 36),
            weaponStack(loadout)
        };
        int availH = bottom - top - 8;
        if (availH < 10) {
            return;
        }
        int rowH = Math.max(18, Math.min(26, availH / labels.length));
        int slotS = Math.min(20, rowH - 4);
        int iy = (slotS - 16) / 2;
        int railX = ex - 4;
        // 负载轨：贯穿细线 + 每槽一个刻度（构成主义工程导轨，替代原先一根裸线）
        int railTop = top + 6;
        int railBot = top + 6 + (labels.length - 1) * rowH + slotS;
        g.fill(railX, railTop, railX + 1, railBot, RpTheme.alphaBlend(RpTheme.TEXT_DIM, 0xCC));
        int railCap = Math.max(2, rowH / 8);
        g.fill(railX - railCap, railTop, railX + 1 + railCap, railTop + 1, RpTheme.TEXT_DIM);
        g.fill(railX - railCap, railBot - 1, railX + 1 + railCap, railBot, RpTheme.TEXT_DIM);
        ItemStack hovered = ItemStack.EMPTY;
        for (int i = 0; i < labels.length; i++) {
            int ry = top + 6 + i * rowH;
            ItemStack stack = stacks[i];
            boolean loaded = stack != null && !stack.isEmpty();
            boolean weapon = i == labels.length - 1;
            // 状态着色：空槽暗灰 / 已装备亮灰 / 武器红（一眼定位武器）
            int accent;
            int inner;
            if (!loaded) {
                accent = RpTheme.BORDER_DIM;
                inner = RpTheme.SLOT_BG;
            } else if (weapon) {
                accent = RpTheme.RED_LINE;
                inner = RpTheme.alphaBlend(RpTheme.RED, 40);
            } else {
                accent = RpTheme.CYAN_DIM;
                inner = RpTheme.alphaBlend(RpTheme.CYAN, 36);
            }
            // 槽位卡片：硬边方框 + 顶部门闩色条 + 已装槽四角刻度 / 空槽 45° 排线
            RpRoundRect.outlined(g, ex, ry, ex + slotS, ry + slotS, 3f, accent, inner);
            g.fill(ex + 1, ry + 1, ex + slotS - 1, ry + 2, accent);
            // 导轨刻度：把槽位与导轨连成一体
            g.fill(railX, ry + slotS / 2 - 1, ex, ry + slotS / 2 + 1, accent);
            if (loaded) {
                cornerTicks(
                        g, ex + 1, ry + 1, ex + slotS - 1, ry + slotS - 1, 3, RpTheme.alphaBlend(RpTheme.CYAN, 0xAA));
                // 武器槽右上角红色楔形（构成主义标记；替代单纯红框）
                if (weapon) {
                    wedge(g, ex, ry, ex + slotS, ry + slotS, 1, 6, RpTheme.RED);
                }
                g.renderItem(stack, ex + iy, ry + iy);
                if (mx >= ex && mx <= ex + slotS && my >= ry && my <= ry + slotS) {
                    hovered = stack;
                }
            } else {
                hatch(g, ex + 1, ry + 2, ex + slotS - 1, ry + slotS - 1, 4, RpTheme.alphaBlend(RpTheme.TEXT_DIM, 0x88));
                g.drawString(font, "--", ex + slotS / 2 - 3, ry + slotS / 2 - 4, RpTheme.TEXT_DIM);
            }
            // 序号 + 标签（序号为构成主义「条目编号」；宽度不足时自动省略序号）
            int labelX = ex + slotS + 6;
            int textW = font.width(labels[i]);
            int numColor = loaded ? (weapon ? RpTheme.RED : RpTheme.TEXT_SECONDARY) : RpTheme.TEXT_DIM;
            if (er - labelX > textW + 18) {
                g.drawString(
                        font, String.format(Locale.ROOT, "%02d", i + 1), labelX, ry + slotS / 2 - 4, numColor, false);
                labelX += 14;
            }
            int labelColor = loaded ? (weapon ? RpTheme.RED : RpTheme.TEXT_PRIMARY) : RpTheme.TEXT_DIM;
            g.drawString(font, labels[i], labelX, ry + slotS / 2 - 4, labelColor, true);
        }
        // 悬停物品显示词条
        if (!hovered.isEmpty()) {
            g.renderTooltip(font, hovered, mx, my);
        }
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

    private void renderNotice(GuiGraphics g) {
        if (!notice.isBlank() && System.currentTimeMillis() < noticeUntil) {
            String prefix =
                    "[ " + Component.translatable("ccnr_rp.gui.admin.system").getString() + " ] ";
            g.drawCenteredString(font, prefix + notice, (px1 + px2) / 2, py2 - 20, RpTheme.RED_LINE);
        }
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

    /** 1px 硬边圆环（仅描边，不填充）：替代多层 alpha 同心圆叠出来的「模糊灰斑」。 */
    /** 圆弧描边（角度制：0=正右，逆时针为正）：构成主义「未闭合圆」断弧 / 仪表刻度。 */
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
     * 返回占用高度（10），调用方按原节标签位置摆放即可，不改变既有布局。
     */
    private int sectionHead(GuiGraphics g, int x, int y, int w, String index, String label) {
        int h = 10;
        if (w < 12) {
            return h;
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
        return h;
    }

    /**
     * 终端读数框：内陷底 + 左 2px 结构红条 + 顶缘亮线 + 右缘刻度 + 左右两段文字（右段右对齐）。
     * 替代此前「灰底上一行行裸文字」，让档案/等级/编制读起来像机器读数。
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
