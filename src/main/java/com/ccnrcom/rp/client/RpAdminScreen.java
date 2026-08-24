/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.ccnrcom.rp.network.RpChannels;
import com.ccnrcom.rp.network.RpPackets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * CCNR-RP 管理器（管理员，需权限节点）：
 * 入服规则——强制观察者入服 / 入服默认打开面板（选择部署）/ 强制保留角色（弃演·离服 → 判死+遗体落地）。
 * 从 K 面板页眉「管理」进入；仅管理员可修改（服务端二次校验）。
 */
public class RpAdminScreen extends Screen {

    private static RpAdminScreen open;

    private final List<int[]> rowBounds = new ArrayList<>();
    private int px1, py1, px2, py2;
    private int closeX1, closeY1, closeX2, closeY2;
    private String notice = "";
    private long noticeUntil = 0;

    private static final String[][] ROWS = {
        {"forceObserving", "ccnr_rp.gui.admin.setting.force_observing", "ccnr_rp.gui.admin.setting.force_observing.desc"
        },
        {"openPanelOnJoin", "ccnr_rp.gui.admin.setting.open_panel", "ccnr_rp.gui.admin.setting.open_panel.desc"},
        {"forceRetain", "ccnr_rp.gui.admin.setting.force_retain", "ccnr_rp.gui.admin.setting.force_retain.desc"}
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
        int pw = Math.max(420, Math.min(width - 60, 620));
        int ph = Math.max(280, Math.min(height - 100, 400));
        px1 = (width - pw) / 2;
        py1 = (height - ph) / 2;
        px2 = px1 + pw;
        py2 = py1 + ph;
        closeX1 = px2 - 26;
        closeY1 = py1 + 4;
        closeX2 = px2 - 8;
        closeY2 = py1 + 22;
        RpChannels.sendToServer(new RpPackets.ManagerRequestC2S());
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        rowBounds.clear();
        int y = py1 + 44;
        int rowH = 52;
        for (int i = 0; i < ROWS.length; i++) {
            rowBounds.add(new int[] {px1 + 12, y, px2 - 12, y + rowH});
            y += rowH + 6;
        }
        addRenderableWidget(RpButton.secondary(
                px1 + 12,
                py2 - 32,
                130,
                20,
                Component.translatable("ccnr_rp.gui.admin.back"),
                b -> net.minecraft.client.Minecraft.getInstance().setScreen(new CharacterManagementScreen())));
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
        for (int i = 0; i < rowBounds.size(); i++) {
            int[] b = rowBounds.get(i);
            if (mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3]) {
                if (!ClientCharacterState.isAdmin()) {
                    notice = Component.translatable("ccnr_rp.gui.admin.no_perm").getString();
                    return true;
                }
                String key = ROWS[i][0];
                boolean next = !value(key);
                RpChannels.sendToServer(new RpPackets.ManagerSetC2S(key, String.valueOf(next)));
                return true;
            }
        }
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        RpTheme.terminalPanel(g, px1, py1, px2, py2, RpTheme.RADIUS_LARGE);
        // 标题行
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
        // 关闭
        boolean hover = mouseX >= closeX1 && mouseX <= closeX2 && mouseY >= closeY1 && mouseY <= closeY2;
        if (hover) {
            g.fill(closeX1 - 2, closeY1 - 1, closeX2 + 2, closeY2 + 1, 0xE66F1613);
        }
        g.drawString(
                font, "X", (closeX1 + closeX2) / 2 - 2, closeY1 + 4, hover ? 0xFFFFFFFF : RpTheme.TEXT_SECONDARY, true);
        g.fill(px1 + 8, py1 + 26, px2 - 8, py1 + 27, RpTheme.CYAN_DIM);

        // 设置行
        for (int i = 0; i < rowBounds.size(); i++) {
            int[] b = rowBounds.get(i);
            boolean hoverRow = mouseX >= b[0] && mouseX <= b[2] && mouseY >= b[1] && mouseY <= b[3];
            RpRoundRect.outlined(
                    g,
                    b[0],
                    b[1],
                    b[2],
                    b[3],
                    6f,
                    hoverRow && admin ? RpTheme.PANEL_BORDER_BRIGHT : RpTheme.PANEL_BORDER,
                    hoverRow ? RpTheme.PANEL_BG_ALT : RpTheme.PANEL_BG);
            String key = ROWS[i][0];
            boolean on = value(key);
            g.drawString(
                    font,
                    Component.translatable(ROWS[i][1]).getString(),
                    b[0] + 10,
                    b[1] + 8,
                    on ? RpTheme.CYAN : RpTheme.TEXT_PRIMARY,
                    true);
            g.drawString(font, Component.translatable(ROWS[i][2]).getString(), b[0] + 10, b[1] + 24, RpTheme.TEXT_DIM);
            // 开关
            int sw = 46;
            int sx = b[2] - sw - 10;
            int sy = b[1] + (b[3] - b[1] - 14) / 2;
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
        // 底部提示
        g.drawString(
                font,
                Component.translatable("ccnr_rp.gui.admin.footer").getString(),
                px1 + 12,
                py2 - 38,
                RpTheme.TEXT_DIM);
        if (!notice.isBlank()) {
            g.drawCenteredString(font, "[ 系统 ] " + notice, (px1 + px2) / 2, py2 - 26, RpTheme.RED_LINE);
        }
        super.render(g, mouseX, mouseY, partialTick);
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
