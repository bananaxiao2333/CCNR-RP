/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.ccnrcom.rp.faction.RelationType;
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

    /** 部署电影数据里的 CMDCam 场景名（空=本次部署无场景，HUD 播完即落位）。 */
    private static String pendingScene = "";

    /** 待落位：HUD 电影播完且（有场景时）CMDCam 场景也播完后发 DeployLandC2S。 */
    private static boolean landingArmed = false;
    /** CMDCam 场景是否已开始播放过（短场景可能在 HUD 结束前就播完，需记录）。 */
    private static boolean sceneSeen = false;
    /** HUD 播完时刻（用于场景未启动/未结束时的安全兜底）。 */
    private static long landWaitStart = 0;
    /** 安全兜底：HUD 播完后若场景始终未启动/未结束，最多再等这么久即落位。 */
    private static final long LAND_SAFETY_MS = 20_000L;

    private CinematicController() {}

    public static boolean active() {
        return data != null;
    }

    public static void start(JsonObject payload) {
        data = payload;
        startMs = System.currentTimeMillis();
        pendingScene = payload == null ? "" : str(payload, "cmdcamScene");
        landingArmed = false;
        sceneSeen = false;
        // 音乐传递（高→低）：启动程序指定音乐 > 职业音乐 > 阵营音乐；均未配置则静默跳过
        ClientAudio.playEntrance(resolveMusic(payload));
    }

    /**
     * 客户端每 tick 调用：跟踪 CMDCam 场景播放状态，HUD 与场景全部播完后触发落位。
     * 场景与 HUD 由服务端同一时刻下发——若场景未配置，HUD 播完即落位。
     */
    public static void tickLanding() {
        boolean playing = CamSceneClient.playing();
        if (playing) {
            sceneSeen = true;
        }
        if (!landingArmed) {
            return;
        }
        if (pendingScene.isBlank()) {
            return; // 无场景：HUD 结束分支已直接落位
        }
        long now = System.currentTimeMillis();
        // 场景已开始并已结束 → 落位；场景始终未开始/未结束（缺失/异常）→ 安全兜底落位
        if ((sceneSeen && !playing) || now - landWaitStart > LAND_SAFETY_MS) {
            landNow();
        }
    }

    /** 全部动画播完 → 通知服务端落位（移动玩家到部署点 + 设置生存）。 */
    private static void landNow() {
        landingArmed = false;
        // 确保落位后 HUD 恢复（防 CMDCam 场景结束恢复值异常导致 HUD 持续隐藏）
        net.minecraft.client.Minecraft.getInstance().options.hideGui = false;
        com.ccnrcom.rp.network.RpChannels.sendToServer(new com.ccnrcom.rp.network.RpPackets.DeployLandC2S());
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

    /** 一行中的着色片段：text 片段文本，key 颜色键（0=副标题灰 / 1=敌对红 / 2=友好绿 / 3=中立白）。 */
    private record Seg(String text, int key) {}

    /** 四行副标题（分段着色：阵营关系按关系类型着色，其余整行副标题色）。 */
    private static List<List<Seg>> segLines() {
        List<List<Seg>> out = new ArrayList<>();
        out.add(List.of(new Seg("项目名字：" + str(data, "name"), 0)));
        out.add(List.of(new Seg("项目阵营：" + str(data, "factionName"), 0)));
        List<Seg> rel = new ArrayList<>();
        rel.add(new Seg("阵营关系：", 0));
        if (data.has("relations") && data.get("relations").isJsonArray()) {
            boolean first = true;
            for (JsonElement e : data.getAsJsonArray("relations")) {
                JsonObject o = e.getAsJsonObject();
                if (!first) {
                    rel.add(new Seg(" · ", 0));
                }
                first = false;
                rel.add(new Seg(
                        str(o, "name") + " "
                                + Component.translatable("ccnr_rp.relation." + str(o, "type"))
                                        .getString(),
                        relationKey(str(o, "type"))));
            }
        }
        if (rel.size() == 1) {
            rel.add(new Seg("—", 0));
        }
        out.add(rel);
        out.add(List.of(new Seg("项目简历：" + str(data, "background"), 0)));
        return out;
    }

    /** 关系类型 → 着色键（敌对红 / 友好绿 / 中立白）。 */
    private static int relationKey(String type) {
        RelationType t = RelationType.parse(type);
        if (t == RelationType.HOSTILE) {
            return 1;
        }
        if (t == RelationType.FRIENDLY) {
            return 2;
        }
        return 3;
    }

    /** 逐行拼接全文（打字进度/时长按整行字符推进，换行只是视觉分行）。 */
    private static List<String> lineTexts(List<List<Seg>> segLines) {
        List<String> out = new ArrayList<>();
        for (List<Seg> line : segLines) {
            StringBuilder sb = new StringBuilder();
            for (Seg s : line) {
                sb.append(s.text);
            }
            out.add(sb.toString());
        }
        return out;
    }

    /** 贪心按像素宽度换行：返回每行 [start,end) 字符区间（相对整行文本）。 */
    private static List<int[]> wrapRanges(String text, int maxW) {
        var font = Minecraft.getInstance().font;
        List<int[]> out = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = start;
            int width = 0;
            while (end < text.length()) {
                int cw = font.width(String.valueOf(text.charAt(end)));
                if (width + cw > maxW && end > start) {
                    break;
                }
                width += cw;
                end++;
            }
            out.add(new int[] {start, end});
            start = end;
        }
        return out;
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
        List<List<Seg>> segLines = segLines();
        List<String> texts = lineTexts(segLines);

        // 总时长：标题 + 全部行 + 停留 + 黑屏渐退 + 等待 + 文字渐退
        long typeEnd = T_BLACK_HOLD + T_ICON_HOLD + (long) title.length() * T_TYPE_MS + totalType(texts) + T_AFTER_ALL;
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
            // HUD 电影播完：有 CMDCam 场景则等场景播完再落位；无场景立即落位
            landingArmed = true;
            landWaitStart = System.currentTimeMillis();
            if (pendingScene.isBlank()) {
                landNow();
            }
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

        // 副标题：逐行打字（行宽不足时自动换行；阵营关系按类型着色）
        long titleStart = T_BLACK_HOLD + T_ICON_HOLD;
        float lineScale = 1.4f;
        int maxW = Math.max(80, (int) ((w - 40) / lineScale));
        int[] rowCounts = new int[texts.size()];
        for (int i = 0; i < texts.size(); i++) {
            rowCounts[i] = wrapRanges(texts.get(i), maxW).size();
        }
        long lineStart = titleStart + (long) title.length() * T_TYPE_MS + T_LINE_GAP;
        int ly = (int) (h * 0.57);
        int[] segColors = {
            cs,
            RpTheme.alphaBlend(RpTheme.RED, ta),
            RpTheme.alphaBlend(RpTheme.GREEN, ta),
            RpTheme.alphaBlend(0xFFFFFFFF, ta),
        };
        for (int i = 0; i < segLines.size(); i++) {
            String text = texts.get(i);
            int c = typedCount(text, lineStart, now);
            if (c > 0) {
                String typed = text.substring(0, c);
                int ry = ly;
                for (int[] range : wrapRanges(typed, maxW)) {
                    renderRow(g, w, ry, typed, range[0], range[1], segLines.get(i), segColors, lineScale);
                    ry += ROW_H;
                }
            }
            ly += rowCounts[i] * ROW_H;
            lineStart += (long) text.length() * T_TYPE_MS + T_LINE_GAP;
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

    /** 副标题每行（视觉行）间距（未缩放坐标）。 */
    private static final int ROW_H = 21;

    /**
     * 绘制副标题一行：按片段着色、整行居中。range 为 [start,end) 字符区间（相对整行文本，
     * 只取已打字前缀内的部分）。
     */
    private static void renderRow(
            GuiGraphics g, int w, int y, String typed, int s, int e, List<Seg> segs, int[] segColors, float scale) {
        var font = Minecraft.getInstance().font;
        String row = typed.substring(s, e);
        int rowW = font.width(row);
        g.pose().pushPose();
        g.pose().translate(w / 2f, y + 6f, 0f);
        g.pose().scale(scale, scale, 1f);
        int x = -rowW / 2;
        int off = 0;
        for (Seg seg : segs) {
            int segStart = off;
            int segEnd = off + seg.text.length();
            int ss = Math.max(s, segStart);
            int ee = Math.min(e, segEnd);
            if (ss < ee) {
                String part = typed.substring(ss, ee);
                g.drawString(font, part, x, 0, segColors[seg.key]);
                x += font.width(part);
            }
            off = segEnd;
        }
        g.pose().popPose();
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
