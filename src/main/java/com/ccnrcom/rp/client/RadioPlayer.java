/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 入场无线电（客户端播放器）：部署入场动画播完后，action bar 打字机逐句展示阵营/职业无线电。
 * 格式「说话人：内容」——说话人（默认“指挥官”）按阵营颜色，冒号与内容白色。
 * 多句队列：每句打字完成后停留该句 wait 秒（上一句保持完整显示），再播下一句；全部播完自动消失。
 * 播放数据来自 CinematicS2C 载荷的 radio 字段（服务端按 职业 > 阵营 解析，职业禁用则不播）。
 */
public final class RadioPlayer {

    /** 一句话：文本 + 打字完成后停留秒数。 */
    private record Line(String text, double waitSeconds) {}

    private static List<Line> queue = List.of();
    private static String speaker = "指挥官";
    private static int speakerColor = 0xFFFFFFFF;
    private static int idx = 0;
    private static long lineStartedAt = 0;
    private static long waitUntil = 0;

    private static final long TYPE_MS = 60; // 每字
    private static final double DEFAULT_WAIT = 1.5; // 未配置 wait 时默认停留（秒）
    private static final int ACTIONBAR_Y_OFFSET = 72; // 距屏底（actionbar 位置，hotbar 上方）

    private RadioPlayer() {}

    /** 开始播放（覆盖进行中的队列）；radio 为 null/空 lines 时静默清空。 */
    public static void start(JsonObject radio) {
        queue = parse(radio);
        speaker = radio != null
                        && radio.has("speaker")
                        && !radio.get("speaker").getAsString().isBlank()
                ? radio.get("speaker").getAsString()
                : "指挥官";
        speakerColor = radio != null && radio.has("color") && radio.get("color").isJsonPrimitive()
                ? parseColor(radio.get("color").getAsString())
                : 0xFFFFFFFF;
        idx = 0;
        lineStartedAt = System.currentTimeMillis();
        waitUntil = 0;
    }

    /** 停止并清空（登出/重复部署时清理）。 */
    public static void clear() {
        queue = List.of();
        idx = 0;
    }

    /** 是否正在播放。 */
    public static boolean active() {
        return idx < queue.size();
    }

    private static List<Line> parse(JsonObject radio) {
        List<Line> out = new ArrayList<>();
        if (radio == null || !radio.has("lines") || !radio.get("lines").isJsonArray()) {
            return out;
        }
        JsonArray arr = radio.getAsJsonArray("lines");
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            String text = o.has("text") && !o.get("text").getAsString().isBlank()
                    ? o.get("text").getAsString()
                    : "";
            double wait = o.has("wait") && o.get("wait").isJsonPrimitive()
                    ? Math.max(0, o.get("wait").getAsDouble())
                    : DEFAULT_WAIT;
            if (!text.isBlank()) {
                out.add(new Line(text, wait));
            }
        }
        return out;
    }

    private static int parseColor(String hex) {
        if (hex == null || !hex.startsWith("#") || hex.length() != 7) {
            return 0xFFFFFFFF;
        }
        try {
            return 0xFF000000 | Integer.parseInt(hex.substring(1), 16);
        } catch (NumberFormatException e) {
            return 0xFFFFFFFF;
        }
    }

    /** 每 tick 推进：打字 → 停留（wait）→ 下一句 → 结束。 */
    public static void tick() {
        if (!active() || Minecraft.getInstance().player == null) {
            return;
        }
        Line line = queue.get(idx);
        long now = System.currentTimeMillis();
        long typedMs = (now - lineStartedAt) / TYPE_MS;
        if (typedMs >= line.text().length()) {
            // 打字完成：进入停留；wait 结束后下一句
            if (waitUntil == 0) {
                waitUntil = now + Math.round(line.waitSeconds() * 1000);
            } else if (now >= waitUntil) {
                idx++;
                if (idx < queue.size()) {
                    lineStartedAt = now;
                    waitUntil = 0;
                }
            }
        }
    }

    /** action bar 渲染（打字机效果：逐字追加）。 */
    public static void render(GuiGraphics g, int w, int h) {
        if (!active() || Minecraft.getInstance().player == null || CinematicController.active()) {
            return; // 入场动画黑屏期间不显示
        }
        Line line = queue.get(idx);
        long typedMs = (System.currentTimeMillis() - lineStartedAt) / TYPE_MS;
        int typed = (int) Math.min(line.text().length(), typedMs);
        String shown = line.text().substring(0, typed);
        Component msg = Component.literal(speaker)
                .withStyle(s -> s.withColor(speakerColor))
                .append(Component.literal("：" + shown).withStyle(s -> s.withColor(0xFFFFFFFF)));
        int y = h - ACTIONBAR_Y_OFFSET;
        g.drawCenteredString(Minecraft.getInstance().font, msg, w / 2, y, 0xFFFFFFFF);
    }
}
