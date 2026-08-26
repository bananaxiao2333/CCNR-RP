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
                // 空位不足：禁用并提示限制（优先职业上限，其次阵营上限）
                if (profFull) {
                    deploy.setMessage(
                            Component.translatable("ccnr_rp.spawn.limit.profession_full", str(p, "name"), profLimit));
                } else {
                    deploy.setMessage(Component.translatable(
                            "ccnr_rp.spawn.limit.faction_full", factionName(p, factionMeta), facLimit));
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
        RpTheme.terminalPanel(g, px1, py1, px2, py2, RpTheme.RADIUS_LARGE);
        renderHeader(g, mx, my);
        renderColHeaders(g);
        renderNav(g, mx, my);
        renderList(g, mx, my);
        renderDetail(g, mx, my);
        renderNotice(g);
        super.render(g, mx, my, partial);
        if (!confirmDeployId.isEmpty()) {
            renderKillConfirm(g, mx, my);
        }
    }

    private void renderHeader(GuiGraphics g, int mx, int my) {
        int y = hdrY1 + 5;
        RpIcons.badge(g, px1 + 17, hdrY1 + 10, 8, "hex", 1, false);
        String head = Component.translatable("ccnr_rp.gui.character.db_header")
                .getString()
                .toUpperCase(Locale.ROOT);
        g.drawString(font, head, px1 + 30, y, RpTheme.CYAN, true);
        String statusLabel =
                switch (ClientCharacterState.userStatus()) {
                    case ALIVE -> Component.translatable("ccnr_rp.gui.character.status.alive")
                            .getString();
                    case DEAD -> Component.translatable("ccnr_rp.gui.character.status.dead")
                            .getString();
                    default -> Component.translatable("ccnr_rp.gui.character.status.observing")
                            .getString();
                };
        String lvl = "Lv " + userLevel() + "  " + statusLabel;
        g.drawString(font, lvl, px1 + 30 + font.width(head) + 40, y, RpTheme.TEXT_SECONDARY, true);
        g.fill(px1 + 8, hdrY2 - 1, px2 - 8, hdrY2, RpTheme.CYAN_DIM);
        // 管理按钮（管理员可开管理面板）
        boolean mgrHover = my >= mgrY1 && my <= mgrY2 && mx >= mgrX1 && mx <= mgrX2;
        if (mgrHover) {
            g.fill(mgrX1 - 2, mgrY1 - 1, mgrX2 + 2, mgrY2 + 1, 0x605A5A5A);
        }
        g.drawString(
                font,
                Component.translatable("ccnr_rp.gui.character.manage").getString(),
                mgrX1 + 4,
                hdrY1 + 4,
                ClientCharacterState.isAdmin() ? RpTheme.CYAN : RpTheme.TEXT_DIM,
                true);
        boolean hover = my >= hdrY1 - 1 && my <= hdrY1 + 17 && mx >= px2 - 28 && mx <= px2 - 10;
        if (hover) {
            g.fill(px2 - 30, hdrY1 - 2, px2 - 8, hdrY1 + 18, 0xE66F1613);
        }
        g.drawString(font, "X", px2 - 20, hdrY1 + 4, hover ? 0xFFFFFFFF : RpTheme.TEXT_SECONDARY, true);
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
                                .getString()) + " (" + filtered().size() + ")",
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
                        hover ? RpTheme.PANEL_BORDER_BRIGHT : RpTheme.PANEL_BORDER,
                        hover ? RpTheme.PANEL_BG_ALT : RpTheme.PANEL_BG);
            }
            if (fac == null) {
                RpIcons.badge(g, b[0] + 12, b[1] + 11, 8, "target", 2, sel);
                g.drawString(
                        font,
                        Component.translatable("ccnr_rp.gui.character.filter.all")
                                .getString(),
                        b[0] + 25,
                        b[1] + 7,
                        sel ? 0xFFFFFFFF : RpTheme.TEXT_SECONDARY,
                        true);
            } else {
                RpIcons.factionBadge(g, b[0] + 12, b[1] + 11, 8, fac, sel);
                String name = str(fac, "name");
                int maxW = b[2] - b[0] - 28;
                if (font.width(name) > maxW) {
                    name = font.plainSubstrByWidth(name, maxW - 1) + "…";
                }
                g.drawString(font, name, b[0] + 25, b[1] + 7, sel ? 0xFFFFFFFF : RpTheme.TEXT_SECONDARY, true);
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
                        hover ? RpTheme.PANEL_BORDER_BRIGHT : RpTheme.PANEL_BORDER,
                        hover ? RpTheme.PANEL_BG_ALT : (i % 2 == 0 ? RpTheme.PANEL_BG : RpTheme.PANEL_BG_EVEN));
            }
            JsonObject fac = factionMeta(str(p, "factionId"));
            RpIcons.factionBadge(g, b[0] + 14, b[1] + 14, 7, fac, false);
            g.drawString(font, str(p, "name"), b[0] + 34, b[1] + 6, sel ? 0xFFFFFFFF : RpTheme.TEXT_PRIMARY, true);
            g.drawString(
                    font, factionName(p, factionMeta), b[0] + 34, b[1] + 20, sel ? 0xFFFFFFFF : RpTheme.TEXT_SECONDARY);
            // 灰色职位 ID
            g.drawString(font, str(p, "id"), b[0] + 34, b[1] + 32, sel ? 0xFFFFFFFF : RpTheme.TEXT_DIM);
            boolean met = userLevel() >= unlockLevel(p);
            String tag = "Lv " + unlockLevel(p);
            int tagW = font.width(tag) + 8;
            int tx2 = b[2] - 7;
            int color = met ? RpTheme.STATUS_ALIVE : RpTheme.RED_LINE;
            RpRoundRect.fill(g, tx2 - tagW, b[1] + 6, tx2, b[1] + 18, 3f, RpTheme.alphaBlend(color, met ? 0x66 : 0xFF));
            g.drawString(font, tag, tx2 - tagW + 4, b[1] + 8, met ? color : 0xFFFFFFFF, true);
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
                g.drawString(font, occTag, ox2 - oTagW + 4, b[1] + 8, full ? 0xFFFFFFFF : oCol, true);
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
        RpTheme.card(g, x, y, x + w, y + 44, 8f, RpTheme.PANEL_BG);
        if (p == null) {
            g.drawString(font, "— 选择一个职位 —", x + 10, y + 18, RpTheme.TEXT_DIM, true);
            g.drawString(font, "点选职位后可按部署", x + 10, y + 30, RpTheme.TEXT_DIM);
            return;
        }
        g.drawString(font, str(p, "name"), x + 8, y + 5, RpTheme.CYAN, true);
        String facName = factionName(p, factionMeta);
        g.drawString(
                font,
                "ID " + str(p, "id") + "  /  " + facName,
                x + 8 + font.width(str(p, "name")) + 10,
                y + 7,
                RpTheme.TEXT_DIM);
        boolean met = userLevel() >= unlockLevel(p);
        String req = "需求等级：Lv " + unlockLevel(p) + "（当前 Lv " + userLevel() + "）";
        g.drawString(font, req, x + 8, y + 24, met ? RpTheme.STATUS_ALIVE : RpTheme.RED_LINE);
        // 部署限制与当前在职（全局性限制预览；服务端 deploy 统一入口强校验）
        int profLimit = ClientCharacterState.professionLimit(str(p, "id"));
        int facLimit = ClientCharacterState.factionLimit(str(p, "factionId"));
        int profOcc = ClientCharacterState.professionOccupied(str(p, "id"));
        int facOcc = ClientCharacterState.factionOccupied(str(p, "factionId"));
        StringBuilder lim = new StringBuilder("在职 ");
        if (profLimit >= 0) {
            lim.append(profOcc).append("/").append(profLimit).append(" 职业");
        } else {
            lim.append(profOcc).append("（职业不限）");
        }
        if (facLimit >= 0) {
            lim.append("  ·  阵营 ").append(facOcc).append("/").append(facLimit);
        }
        boolean profFull = profLimit >= 0 && profOcc >= profLimit;
        boolean facFull = facLimit >= 0 && facOcc >= facLimit;
        g.drawString(
                font, lim.toString(), x + 8, y + 36, (profFull || facFull) ? RpTheme.RED_LINE : RpTheme.STATUS_ALIVE);
        g.drawString(
                font,
                Component.translatable("ccnr_rp.gui.character.preview").getString(),
                x + 8,
                y + 48,
                RpTheme.TEXT_DIM,
                true);
        JsonObject loadout = loadoutOf(p);
        int contentTop = bodyY1 + 50;
        int contentBottom = deployY - 12;
        int modelW = Math.max(88, w * 38 / 100);
        // 预览区背景阵营徽章（身份归属视觉提示，参考 t-mt8dmt3a 统一徽章封装）：
        // 大号半透明水印徽章置于装备槽区右侧空白背景，先画背景再画内容（模型/装备槽在上层不遮挡）；
        // 用 bigBadge(alpha) 实现半透明水印；未知阵营（无元数据）跳过。
        JsonObject facMeta = factionMeta(str(p, "factionId"));
        if (facMeta != null) {
            g.enableScissor(x, contentTop, x + w, contentBottom);
            String fIcon = facMeta.has("icon") && !facMeta.get("icon").isJsonNull()
                    ? facMeta.get("icon").getAsString()
                    : "hex";
            int fTier = facMeta.has("tier") ? facMeta.get("tier").getAsInt() : 2;
            int badgeCx = x + modelW + (w - modelW) * 3 / 4;
            int badgeCy = contentBottom - 26;
            int badgeR = Math.max(22, Math.min(40, (contentBottom - contentTop) / 4));
            com.ccnrcom.rp.client.RpIcons.bigBadge(g, badgeCx, badgeCy, badgeR, fIcon, fTier, 36);
            g.disableScissor();
        }
        // 3D 人物立绘（使用玩家自己的皮肤，来自 CharacterPreview）
        g.enableScissor(x, contentTop, x + modelW, contentBottom);
        JsonObject ch = new JsonObject();
        ch.addProperty("id", "pos-" + selectedId);
        ch.addProperty("name", str(p, "name"));
        int modelH = contentBottom - contentTop;
        int scale = Math.max(12, Math.min(36, Math.min(modelH / 3 - 6, modelW / 3)));
        int cy = contentTop + modelH / 2 + 4;
        CharacterPreview.render(g, x + modelW / 2, cy, scale, mx, my, ch, loadout);
        g.disableScissor();
        // 战术装备实物预览（职位 loadout：头/胸/腿/靴/武器，悬停显示词条）
        renderEquipList(g, x + modelW + 8, x + w - 8, contentTop, contentBottom, loadout, mx, my);
    }

    private static JsonObject loadoutOf(JsonObject p) {
        if (p == null || !p.has("loadout") || !p.get("loadout").isJsonObject()) {
            return null;
        }
        return p.getAsJsonObject("loadout");
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
        int rowH = Math.max(16, Math.min(24, availH / labels.length));
        int slotS = Math.min(18, rowH - 3);
        int iy = (slotS - 16) / 2;
        ItemStack hovered = ItemStack.EMPTY;
        for (int i = 0; i < labels.length; i++) {
            int ry = top + 6 + i * rowH;
            RpRoundRect.outlined(g, ex, ry, ex + slotS, ry + slotS, 3f, RpTheme.PANEL_BORDER, 0xFF2F2F2F);
            ItemStack stack = stacks[i];
            if (stack != null && !stack.isEmpty()) {
                g.renderItem(stack, ex + iy, ry + iy);
                if (mx >= ex && mx <= ex + slotS && my >= ry && my <= ry + slotS) {
                    hovered = stack;
                }
            } else {
                g.drawString(font, "—", ex + slotS / 2 - 2, ry + slotS / 2 - 4, RpTheme.TEXT_DIM);
            }
            g.drawString(font, labels[i], ex + slotS + 6, ry + slotS / 2 - 4, RpTheme.TEXT_SECONDARY, true);
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
            g.drawCenteredString(font, "[ 系统 ] " + notice, (px1 + px2) / 2, py2 - 20, RpTheme.RED_LINE);
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
        g.fill(0, 0, width, height, 0x99000000); // 半透明遮罩
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
        RpRoundRect.fill(
                g,
                rects[0][0],
                rects[0][1],
                rects[0][2],
                rects[0][3],
                5f,
                hYes ? RpTheme.ACCENT_HOVER : RpTheme.alphaBlend(RpTheme.ACCENT, 0xAA));
        g.drawCenteredString(
                font,
                Component.translatable("ccnr_rp.gui.character.kill_confirm_yes").getString(),
                (rects[0][0] + rects[0][2]) / 2,
                rects[0][1] + 6,
                0xFFFFFFFF);
        RpRoundRect.fill(
                g,
                rects[1][0],
                rects[1][1],
                rects[1][2],
                rects[1][3],
                5f,
                hNo ? RpTheme.PANEL_BORDER_BRIGHT : RpTheme.PANEL_BORDER);
        g.drawCenteredString(
                font,
                Component.translatable("ccnr_rp.gui.character.kill_confirm_no").getString(),
                (rects[1][0] + rects[1][2]) / 2,
                rects[1][1] + 6,
                RpTheme.TEXT_PRIMARY);
    }

    /** 按像素宽度折行（中文/长职位名）。 */
    private java.util.List<String> wrapText(String text, int maxW) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (text == null || text.isBlank()) {
            out.add("");
            return out;
        }
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || font.width(cur.toString() + c) > maxW) {
                out.add(cur.toString());
                cur.setLength(0);
                if (c == '\n') {
                    continue;
                }
            }
            cur.append(c);
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
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
