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

/**
 * 经验 HUD（经验系统 v3）：右下角内收（不贴角）、水平居中、文字中心对齐。
 * 底部一行 = 白色经验数字（当前总经验）；其上 = 经验变化项目列（正=绿、负=红、带符号）。
 * 结算动画（纯视觉；服务端结算瞬时完成）：最底一项缓慢移入数字并消失 → 数字更新 →
 * 列表下移补齐 → 停顿 → 循环至全部吸入 → 数字停在新总值。ALIVE 时常驻显示，
 * 死亡结算动画期间（含死亡界面）仍可见，结束后隐藏。
 */
public final class XpHudOverlay {

    private record Item(String title, long value) {}

    /** 空闲态：当前待结算列表 + 当前总经验。 */
    private static List<Item> items = List.of();

    private static long total = 0;

    /** 动画态：剩余待吸入项目 / 动画中数字 / 正在飞入的项目 / 下一项允许起飞时刻。 */
    private static List<Item> animRemaining = List.of();

    private static long animTotal = 0;
    private static Item flying = null;
    private static long flyStart = 0;
    private static long nextFlyAt = 0;
    /** 单项飞入时长（ms）：放慢，让结算过程看得清。 */
    private static final long FLY_MS = 700;
    /** 项目间停顿（ms）。 */
    private static final long GAP_MS = 150;

    private static final int ROW_H = 15;
    private static final int NUM_H = 12;
    private static final int MAX_ROWS = 12;
    /** 水平居中锚点（距右缘内收，靠近中心不贴角）。 */
    private static final int CX_INSET = 260;
    /** 底部数字行底边距屏幕底的高度（状态栏上方）。 */
    private static final int BOTTOM_INSET = 116;

    private XpHudOverlay() {}

    /** 服务端推送当前待结算列表 + 当前总经验（XpListS2C）。 */
    public static synchronized void setList(String payload) {
        JsonObject root = parse(payload);
        if (root == null) {
            return;
        }
        items = parseItems(root);
        if (root.has("total")) {
            total = root.get("total").getAsLong();
        }
        if (!animating()) {
            animTotal = total;
        }
    }

    /** 结算动画（XpSettleAnimS2C）：服务端已瞬时结算，此处仅视觉逐项吸入。 */
    public static synchronized void playSettle(String payload) {
        JsonObject root = parse(payload);
        if (root == null) {
            return;
        }
        animRemaining = new ArrayList<>(parseItems(root));
        if (root.has("total")) {
            total = root.get("total").getAsLong();
        }
        if (animRemaining.isEmpty()) {
            // 无变化：直接刷新数字
            items = List.of();
            animTotal = total;
            flying = null;
            return;
        }
        items = List.of(); // 动画期间空闲列表由动画状态接管
        nextFlyAt = System.currentTimeMillis();
        startNextFly();
    }

    private static boolean animating() {
        return flying != null || !animRemaining.isEmpty();
    }

    /** 起飞最底下一个（列表末尾 = 最靠近数字的一行）。 */
    private static void startNextFly() {
        if (animRemaining.isEmpty()) {
            flying = null;
            return;
        }
        flying = animRemaining.get(animRemaining.size() - 1);
        animRemaining = animRemaining.subList(0, animRemaining.size() - 1);
        flyStart = System.currentTimeMillis();
    }

    public static void render(GuiGraphics g, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || CinematicController.active()) {
            return;
        }
        boolean alive = ClientCharacterState.isDeployed();
        boolean anim = animating();
        if (!alive && !anim) {
            return; // 仅 ALIVE 常驻；结算动画（死亡瞬间）期间仍可见
        }
        if (anim) {
            long now = System.currentTimeMillis();
            if (flying != null) {
                float t = (float) (now - flyStart) / (float) FLY_MS;
                if (t >= 1f) {
                    animTotal = Math.max(0, animTotal + flying.value()); // 数字更新
                    flying = null;
                    nextFlyAt = now + GAP_MS; // 项目间停顿
                    t = 1f;
                }
                drawAnim(g, w, h, Math.min(1f, t));
                return;
            }
            if (animRemaining.isEmpty()) {
                animTotal = total; // 全部吸入：数字停在终值
                return; // 非存活：动画结束即隐藏
            }
            if (now >= nextFlyAt) {
                startNextFly();
                drawAnim(g, w, h, 0f);
                return;
            }
            drawAnim(g, w, h, 1f); // 停顿：剩余项目静止在终位
            return;
        }
        drawIdle(g, w, h);
    }

    private static void drawIdle(GuiGraphics g, int w, int h) {
        var font = Minecraft.getInstance().font;
        int cx = w - CX_INSET;
        int numTop = h - BOTTOM_INSET - NUM_H;
        int m = items.size();
        for (int i = 0; i < m; i++) {
            if (m - i > MAX_ROWS) {
                continue; // 顶部裁剪：只显示近底部若干条
            }
            drawItem(g, items.get(i), cx, numTop - ROW_H * (m - i), 255);
        }
        g.drawCenteredString(
                font, net.minecraft.network.chat.Component.literal(String.valueOf(total)), cx, numTop, 0xFFFFFFFF);
    }

    private static void drawAnim(GuiGraphics g, int w, int h, float t) {
        var font = Minecraft.getInstance().font;
        int cx = w - CX_INSET;
        int numTop = h - BOTTOM_INSET - NUM_H;
        int n = animRemaining.size();
        // 缓动：先快后慢收尾（飞入/下移共用），停顿阶段 t=1 恒为静止
        float e = t >= 1f ? 1f : t * (2f - t);
        // 剩余项目整体下移一格补齐空位
        for (int i = 0; i < n; i++) {
            if (n + 1 - i > MAX_ROWS) {
                continue;
            }
            int y = numTop - ROW_H * (n + 1 - i) + Math.round(e * ROW_H);
            drawItem(g, animRemaining.get(i), cx, y, 255);
        }
        // 飞入项：从底部槽位移向数字行，渐隐消失
        if (flying != null) {
            int y = numTop - ROW_H + Math.round(e * ROW_H);
            int alpha = Math.max(0, Math.min(255, Math.round(255 * (1f - e))));
            drawItem(g, flying, cx, y, alpha);
        }
        g.drawCenteredString(
                font, net.minecraft.network.chat.Component.literal(String.valueOf(animTotal)), cx, numTop, 0xFFFFFFFF);
    }

    /** 一行：标题 + 带符号数值；正=绿、负=红。 */
    private static void drawItem(GuiGraphics g, Item it, int cx, int y, int alpha) {
        var font = Minecraft.getInstance().font;
        String sign = it.value() >= 0 ? "+" : "-";
        String text = it.title() + " " + sign + Math.abs(it.value());
        int base = it.value() >= 0 ? 0xFF35E07A : 0xFFFF4C4C;
        int color = (base & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
        g.drawCenteredString(font, net.minecraft.network.chat.Component.literal(text), cx, y, color);
    }

    private static JsonObject parse(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return com.ccnrcom.rp.util.JsonUtil.GSON.fromJson(payload, JsonObject.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Item> parseItems(JsonObject root) {
        List<Item> out = new ArrayList<>();
        if (root != null && root.has("items") && root.get("items").isJsonArray()) {
            for (var el : root.getAsJsonArray("items")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject o = el.getAsJsonObject();
                try {
                    String title = o.has("title") ? o.get("title").getAsString() : "";
                    long value = o.has("value") ? o.get("value").getAsLong() : 0;
                    out.add(new Item(title == null ? "" : title, value));
                } catch (Exception ignored) {
                    // 坏条目跳过
                }
            }
        }
        return out;
    }
}
