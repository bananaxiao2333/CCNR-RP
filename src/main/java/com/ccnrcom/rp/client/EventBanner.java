/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 激活事件横幅：顶部居中，事件为一个 4:3 横向长方形排开（边框红色警戒）。
 * 仅在背包（InventoryScreen）打开时绘制（ScreenEvent.Render.Post）；游戏内 HUD 与其他界面不显示。
 */
public final class EventBanner {

    private static int offsetPx = 0;
    private static int lastX1, lastY1, lastX2, lastY2;
    private static int lastOverflow = 0;
    /** 上一次绘制出的滚动条几何 + 滚动域（命中/拖拽用；未溢出时 lastOverflow=0）。 */
    private static int barX1, barX2, barY;

    private static int lastTotal = 0;
    private static int lastVisible = 0;
    /** 滚动条拖拽 id（与各界面的 id 互不冲突；横幅只在背包界面之上交互）。 */
    private static final int SCROLL_ID = 41;

    private EventBanner() {}

    public static boolean active() {
        return !ClientCharacterState.activeEvents().isEmpty();
    }

    /** 鼠标是否在横幅区域内（滚轮捕获用）。 */
    public static boolean inArea(int mx, int my) {
        return mx >= lastX1 - 4 && mx <= lastX2 + 4 && my >= lastY1 - 2 && my <= lastY2 + 16;
    }

    /** 滚轮横向滚动（delta>0 向左看）。 */
    public static void scroll(double delta) {
        offsetPx = (int) Math.max(0, Math.min(lastOverflow, offsetPx + (int) (delta * -24)));
    }

    /**
     * 左键按下：命中滚动条游标/轨道则进入拖拽并消费事件（返回 true）；
     * 未溢出或未命中返回 false，让背包界面照常处理本次点击。
     */
    public static boolean mousePressed(int mx, int my) {
        if (lastOverflow <= 0 || lastTotal <= lastVisible) {
            return false;
        }
        int ns = RpScrollbar.clickH(mx, my, barX1, barX2, barY, barY + 5, lastTotal, lastVisible, offsetPx, SCROLL_ID);
        if (ns < 0) {
            return false;
        }
        offsetPx = (int) Math.max(0, Math.min(ns, lastOverflow));
        return true;
    }

    /** 拖拽中：按鼠标横向位移换算偏移（未在拖拽返回 false）。 */
    public static boolean mouseDragged(int mx) {
        int ns = RpScrollbar.dragH(mx);
        if (ns < 0) {
            return false;
        }
        offsetPx = (int) Math.max(0, Math.min(ns, lastOverflow));
        return true;
    }

    /** 左键抬起：结束滚动条拖拽；返回抬起前是否处于拖拽中（上层据此决定是否消费事件）。 */
    public static boolean mouseReleased() {
        boolean was = RpScrollbar.isDragging();
        RpScrollbar.endDrag();
        return was;
    }

    public static void render(GuiGraphics g, int w, int h) {
        if (CinematicController.active()) {
            return;
        }
        List<String> ids = ClientCharacterState.activeEvents();
        if (ids.isEmpty()) {
            return;
        }
        int bw = 96;
        int bh = 72;
        int gap = 6;
        int total = ids.size() * bw + (ids.size() - 1) * gap;
        int areaW = w - 24;
        lastOverflow = Math.max(0, total - areaW);
        offsetPx = (int) Math.max(0, Math.min(lastOverflow, offsetPx));
        int x = (w - total) / 2 - offsetPx;
        int y = 8;
        lastX1 = 8;
        lastY1 = y;
        lastX2 = w - 8;
        lastY2 = y + bh;
        var font = Minecraft.getInstance().font;
        g.enableScissor(8, y - 2, w - 8, y + bh + 4);
        for (String id : ids) {
            drawEventCard(g, font, x, y, bw, bh, id);
            x += bw + gap;
        }
        g.disableScissor();
        if (lastOverflow > 0) {
            g.drawCenteredString(font, "‹", 10, y + bh / 2 - 4, offsetPx > 0 ? RpTheme.CYAN : RpTheme.TRANSPARENT);
            g.drawCenteredString(
                    font, "›", w - 10, y + bh / 2 - 4, offsetPx < lastOverflow ? RpTheme.CYAN : RpTheme.TRANSPARENT);
            g.drawCenteredString(
                    font,
                    Component.translatable("ccnr_rp.event.banner.scroll").getString(),
                    w / 2,
                    y + bh + 2,
                    RpTheme.TEXT_DIM);
            // 横向滚动条：滚轮之外**还可以直接拖游标**（几何记下来供命中/拖拽复用）
            barX1 = 40;
            barX2 = w - 40;
            barY = y + bh + 9;
            lastTotal = total;
            lastVisible = areaW;
            RpScrollbar.drawH(g, barX1, barX2, barY, total, areaW, offsetPx);
        } else {
            lastTotal = 0;
            lastVisible = 0;
        }
    }

    /**
     * 单张事件卡片（4:3 横向，红色警戒边框 + 警示底）。
     *
     * <p><b>不再直接画 id</b>：优先用服务端下发的展示三件套（显示名 + 描述 + 图标，见 docs/15 §4.9），
     * 显示名为空才回退 id；描述按像素断行、最多两行，超出走 {@link RpTheme#clip} 裁剪。
     * 图标**半遮挡在卡片左下角**（圆心落在左下角顶点 + 裁到卡片矩形 → 伸出卡片的一半被裁掉），
     * 与左侧对局状态面板同一画法（{@link MatchStatusPanel}）。
     */
    private static void drawEventCard(
            GuiGraphics g, net.minecraft.client.gui.Font font, int x, int y, int bw, int bh, String id) {
        com.google.gson.JsonObject d = ClientCharacterState.matchEventDisplay(id);
        String name = display(d, "name");
        String desc = display(d, "desc");
        String icon = display(d, "icon");
        if (name.isBlank()) {
            name = id; // 未配置显示名：回退 id（旧配置照常工作）
        }
        RpRoundRect.outlined(g, x, y, x + bw, y + bh, 4f, RpTheme.RED_LINE, RpTheme.SURFACE_ALERT);
        g.fill(x, y + 3, x + 4, y + bh - 3, RpTheme.RED);
        // 图标预留带：底部 13px 留给左下角半遮挡图标，文字不越过它
        int textW = bw - 16;
        int ty = y + 8;
        g.drawString(font, RpTheme.clip(font, name, textW), x + 8, ty, RpTheme.ACCENT_FILL, false);
        ty += 11;
        g.drawString(
                font,
                Component.translatable("ccnr_rp.event.banner.active").getString(),
                x + 8,
                ty,
                RpTheme.RED_LINE,
                false);
        ty += 11;
        if (!desc.isBlank()) {
            int shown = 0;
            for (String line : RpTheme.wrapText(font, desc, textW)) {
                if (shown >= 2 || ty + 9 > y + bh - 14) {
                    break; // 最多两行、且不越过图标预留带
                }
                g.drawString(font, line, x + 8, ty, RpTheme.TEXT_SECONDARY, false);
                ty += 9;
                shown++;
            }
        }
        g.fill(x + 8, y + bh - 4, x + bw - 8, y + bh - 3, RpTheme.RED_LINE);
        if (!icon.isBlank()) {
            // 半遮挡图标：圆心 = 卡片左下角顶点，裁到卡片内 → 只剩卡片里的部分
            g.enableScissor(x, y, x + bw, y + bh);
            RpIcons.icon(g, x + 1, y + bh - 1, 12, icon, RpTheme.RED_LINE);
            g.disableScissor();
        }
    }

    private static String display(com.google.gson.JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return "";
        }
        try {
            return o.get(key).getAsString();
        } catch (Exception e) {
            return "";
        }
    }
}
