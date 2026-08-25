/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 部署入场电影（客户端）：
 * 全屏黑 1s → 突然阵营徽章图标，停留 3s → 主标题打字显示职业 → 副标题逐行打字（项目名字/项目阵营/阵营关系/项目简历）
 * → 全部完成停留 3s → 黑屏渐退 1.6s → 文字与图标在 2s 后开始缓慢淡出。
 */
public final class CinematicController {

    // 时间轴（ms）
    private static final long T_BLACK_HOLD = 1000;
    private static final long T_ICON_HOLD = 3000;
    private static final long T_TYPE_MS = 45; // 每字
    private static final long T_LINE_GAP = 250;
    private static final long T_AFTER_ALL = 3000;
    private static final long T_BLACK_FADE = 1600;
    private static final long T_TEXT_WAIT = 2000;
    private static final long T_TEXT_FADE = 2000;

    private static JsonObject data;
    private static long startMs;

    private CinematicController() {}

    public static boolean active() {
        return data != null;
    }

    public static void start(JsonObject payload) {
        data = payload;
        startMs = System.currentTimeMillis();
        // 音乐传递（高→低）：启动程序指定音乐 > 职业音乐 > 阵营音乐；均未配置则静默跳过
        ClientAudio.playEntrance(resolveMusic(payload));
    }

    /** 按优先级取第一个非空的音乐路径（payload 为 null 时全部为空）。 */
    private static String resolveMusic(JsonObject payload) {
        String launcher = ClientAudio.launcherMusic();
        if (!launcher.isBlank()) {
            return launcher;
        }
        if (payload != null) {
            String prof = str(payload, "music");
            if (!prof.isBlank()) {
                return prof;
            }
            return str(payload, "factionMusic");
        }
        return "";
    }

    private static long t() {
        return System.currentTimeMillis() - startMs;
    }

    // ---------- 文本 ----------

    private static String titleText() {
        return str(data, "professionName");
    }

    private static List<String> lines() {
        List<String> out = new ArrayList<>();
        out.add("项目名字：" + str(data, "name"));
        out.add("项目阵营：" + str(data, "factionName"));
        out.add("阵营关系：" + relationsText());
        out.add("项目简历：" + str(data, "background"));
        return out;
    }

    private static String relationsText() {
        if (!data.has("relations") || !data.get("relations").isJsonArray()) {
            return "—";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonElement e : data.getAsJsonArray("relations")) {
            JsonObject o = e.getAsJsonObject();
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(str(o, "name"))
                    .append(" ")
                    .append(Component.translatable("ccnr_rp.relation." + str(o, "type"))
                            .getString());
        }
        return sb.length() == 0 ? "—" : sb.toString();
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    /** 打字进度：第 start 毫秒开始，逐字填充。 */
    private static int typedCount(String text, long start, long now) {
        if (now < start) {
            return 0;
        }
        if (text == null) {
            return 0;
        }
        return Math.max(0, Math.min(text.length(), (int) ((now - start) / T_TYPE_MS)));
    }

    // ---------- 渲染 ----------

    public static void render(GuiGraphics g, int w, int h) {
        if (data == null) {
            return;
        }
        long now = t();
        String title = titleText();
        List<String> lines = lines();

        // 总时长：标题 + 全部行 + 停留 + 黑屏渐退 + 等待 + 文字渐退
        long typeEnd = T_BLACK_HOLD + T_ICON_HOLD + (long) title.length() * T_TYPE_MS + totalType(lines) + T_AFTER_ALL;
        long blackEnd = typeEnd + T_BLACK_FADE;
        long textEnd = blackEnd + T_TEXT_WAIT + T_TEXT_FADE;

        float blackA = 1.0f;
        if (now >= typeEnd && now < blackEnd) {
            blackA = 1.0f - (now - typeEnd) / (float) T_BLACK_FADE;
        } else if (now >= blackEnd) {
            blackA = 0f;
        }
        float textA = 1.0f;
        long fadeStart = blackEnd + T_TEXT_WAIT;
        if (now >= fadeStart) {
            textA = Math.max(0f, 1.0f - (now - fadeStart) / (float) T_TEXT_FADE);
        }

        // 全屏黑（覆盖 HUD）
        if (blackA > 0f) {
            int a = Math.round(blackA * 255f) << 24;
            g.fill(0, 0, w, h, 0x00000000 | a);
        }
        if (blackA <= 0.01f && textA <= 0.01f) {
            data = null;
            return;
        }
        int ta = (int) (textA * 255f);
        int cy = RpTheme.alphaBlend(RpTheme.CYAN, ta);
        int cp = RpTheme.alphaBlend(RpTheme.TEXT_PRIMARY, ta);
        int cs = RpTheme.alphaBlend(RpTheme.TEXT_SECONDARY, ta);

        // 阵营图标（突然出现）
        if (now >= T_BLACK_HOLD) {
            int r = Math.max(40, Math.min(w, h) / 10);
            int iconY = (int) (h * 0.30);
            RpIcons.bigBadge(g, w / 2, iconY, r, str(data, "icon"), tier(), ta);
            if (textA > 0f) {
                String fn = str(data, "factionName");
                g.drawCenteredString(Minecraft.getInstance().font, "▌ " + fn + " ▌", w / 2, iconY + r + 14, cs);
            }
        }

        // 副标题：四行（逐行打字）
        long titleStart = T_BLACK_HOLD + T_ICON_HOLD;
        long lineStart = titleStart + (long) title.length() * T_TYPE_MS + T_LINE_GAP;
        int ly = (int) (h * 0.57);
        float lineScale = 1.4f;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int c = typedCount(line, lineStart, now);
            if (c > 0) {
                g.pose().pushPose();
                g.pose().translate(w / 2f, ly + 6f, 0f);
                g.pose().scale(lineScale, lineScale, 1f);
                g.drawCenteredString(Minecraft.getInstance().font, line.substring(0, c), 0, 0, cs);
                g.pose().popPose();
            }
            ly += 21;
            lineStart += (long) line.length() * T_TYPE_MS + T_LINE_GAP;
        }

        // 主标题：职业（打字）——屏幕正中央，绘于最上层（徽标位置不变）
        int tc = typedCount(title, titleStart, now);
        if (tc > 0) {
            String typed = title.substring(0, tc);
            float scale = 3.4f;
            g.pose().pushPose();
            g.pose().translate(w / 2f, h * 0.50f, 0f);
            g.pose().scale(scale, scale, 1f);
            g.drawCenteredString(Minecraft.getInstance().font, typed, 0, 0, cy);
            g.pose().popPose();
        }
    }

    private static long totalType(List<String> lines) {
        long sum = 0;
        for (String l : lines) {
            sum += (long) l.length() * T_TYPE_MS + T_LINE_GAP;
        }
        return Math.max(0, sum - T_LINE_GAP);
    }

    private static int tier() {
        try {
            return data.has("tier") ? Math.max(1, Math.min(3, data.get("tier").getAsInt())) : 2;
        } catch (Exception e) {
            return 2;
        }
    }
}
