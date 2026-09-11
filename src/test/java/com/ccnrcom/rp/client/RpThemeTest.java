/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * RpTheme 设计契约守护测试（docs/14 §2.1 / §6）。
 *
 * <p>docs/14 原话是「CI 无颜色守护（无快照测试），一致性靠人工 review」——本测试把该契约变成可执行断言：
 * ①除红色警戒与关系语义蓝外，全部令牌必须是纯中性灰（R=G=B，杜绝再次混入 v3 冷色/异色红）；
 * ②反白（亮底）之上的文字必须是深色 {@code ACCENT_TEXT}（白底白字不可读，2.25.0 修过一次）；
 * ③浮层卡片比面板更不透明、状态/关系/等级色互不混用等结构性不变量。
 * 渲染结果本身无法在无头环境断言（MC 为 int 色值 + 栅格绘制），故这里守的是「令牌语义」这一层。
 */
class RpThemeTest {

    /** 刻意保留的彩色令牌（docs/14 §6）：红色警戒族 + 关系友好蓝 + 观察态弱灰青。 */
    private static final Set<String> INTENTIONAL_NON_GRAY = Set.of(
            "RED",
            "RED_DIM",
            "RED_BG",
            "RED_LINE",
            "RED_BG_SOFT",
            "RED_BG_HOVER",
            "RED_BG_FILL",
            "DANGER",
            "DANGER_HOVER",
            "STATUS_DEAD",
            "HOSTILE",
            "FRIENDLY",
            "STATUS_OBSERVING");

    /** 收集 RpTheme 的全部 public static final int 颜色令牌（几何常量是 float，故 int 即颜色）。 */
    private static Map<String, Integer> tokens() throws IllegalAccessException {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Field f : RpTheme.class.getDeclaredFields()) {
            int m = f.getModifiers();
            if (Modifier.isPublic(m) && Modifier.isStatic(m) && Modifier.isFinal(m) && f.getType() == int.class) {
                out.put(f.getName(), (Integer) f.get(null));
            }
        }
        return out;
    }

    private static int r(int argb) {
        return (argb >>> 16) & 0xFF;
    }

    private static int g(int argb) {
        return (argb >>> 8) & 0xFF;
    }

    private static int b(int argb) {
        return argb & 0xFF;
    }

    private static int alpha(int argb) {
        return (argb >>> 24) & 0xFF;
    }

    /** WCAG 相对亮度（0..1）。 */
    private static double luminance(int argb) {
        double r = r(argb) / 255.0;
        double g = g(argb) / 255.0;
        double b = b(argb) / 255.0;
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double contrast(int fg, int bg) {
        double l1 = luminance(fg);
        double l2 = luminance(bg);
        return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
    }

    @Test
    void allTokensAreNeutralGrayExceptIntentionalSemanticColors() throws Exception {
        for (Map.Entry<String, Integer> e : tokens().entrySet()) {
            String name = e.getKey();
            int v = e.getValue();
            boolean gray = r(v) == g(v) && g(v) == b(v);
            if (gray) {
                continue;
            }
            assertTrue(
                    INTENTIONAL_NON_GRAY.contains(name),
                    "令牌 " + name + " 是非中性彩色但不在刻意保留清单内（docs/14 §6：除红色警戒与关系语义蓝外禁止彩色）：0x" + Integer.toHexString(v));
        }
    }

    @Test
    void intentionalColorsAreStillPresent() throws Exception {
        Map<String, Integer> t = tokens();
        for (String name : INTENTIONAL_NON_GRAY) {
            assertTrue(t.containsKey(name), "刻意保留的语义色令牌缺失：" + name);
        }
        // 红色族：红通道必须显著高于蓝通道（防止误改成灰色/粉色）
        assertTrue(r(t.get("RED")) > b(t.get("RED")) + 100, "RED 必须是正红（红通道远高于蓝通道）");
        // 关系友好蓝：蓝通道高于红通道，且不与白色/红色混同
        assertTrue(b(t.get("FRIENDLY")) > r(t.get("FRIENDLY")) + 50, "FRIENDLY 必须是蓝系（蓝通道高于红通道）");
    }

    @Test
    void lightFillsUseDarkInkForLegibility() {
        // 2.25.0 修掉的缺陷：K 面板部署确认按钮曾「白底白字」。亮底必须深字。
        assertEquals(RpTheme.ACCENT_TEXT, RpTheme.popupRowText(true, false), "下拉当前项＝白底，文字必须走 ACCENT_TEXT");
        assertNotEquals(RpTheme.TEXT_PRIMARY, RpTheme.ACCENT_TEXT, "反白文字不得等于白色文字色");
        assertTrue(
                contrast(RpTheme.ACCENT_TEXT, RpTheme.ACCENT_FILL) >= 4.5, "主操作白底 + ACCENT_TEXT 的对比度必须 ≥ 4.5（WCAG AA）");
        assertTrue(luminance(RpTheme.ACCENT_FILL) > luminance(RpTheme.ACCENT_TEXT) + 0.5, "主操作填充必须是亮色、其上文字必须是深色");
        // 未命中键盘高亮的候选行仍是白字（浮层底足够暗）
        assertEquals(RpTheme.TEXT_PRIMARY, RpTheme.popupRowText(false, false));
    }

    @Test
    void surfaceLadderKeepsPanelsTranslucentAndOverlayCardsOpaque() {
        // 浮层/HUD 卡片浮在世界之上，必须比半透明面板更不透明，否则背景会穿透干扰阅读。
        assertTrue(
                alpha(RpTheme.SURFACE_CARD) > alpha(RpTheme.PANEL_BG), "SURFACE_CARD（浮层实心卡片）必须比 PANEL_BG（面板半透明底）更不透明");
        assertTrue(alpha(RpTheme.SURFACE_CARD) > alpha(RpTheme.PANEL_BG_ALT));
        // 列表行悬停必须比常态更亮/更醒目
        assertTrue(luminance(RpTheme.PANEL_BG_ALT) > luminance(RpTheme.PANEL_BG), "列表行悬停底必须比常态底更亮");
        // 滚动条：可滚动时轨道比无溢出时更明显
        assertTrue(alpha(RpTheme.SCROLL_TRACK_ACTIVE) > alpha(RpTheme.SCROLL_TRACK), "可滚动时滚动条轨道必须比无溢出时更明显");
        // 内陷槽（进度条/输入域）要比卡片底更亮，才能读作「凹槽」
        assertTrue(luminance(RpTheme.SURFACE_INSET) > luminance(RpTheme.SURFACE_CARD), "内陷槽底必须比浮层卡片底更亮");
    }

    @Test
    void tierColorIsMonotonicAndClamped() {
        assertEquals(RpTheme.TEXT_PRIMARY, RpTheme.tierColor(1));
        assertEquals(RpTheme.TEXT_SECONDARY, RpTheme.tierColor(2));
        assertEquals(RpTheme.TEXT_DIM, RpTheme.tierColor(3));
        assertEquals(RpTheme.tierColor(1), RpTheme.tierColor(0), "越界等级必须钳制到 1 级");
        assertEquals(RpTheme.tierColor(1), RpTheme.tierColor(-5));
        assertEquals(RpTheme.tierColor(3), RpTheme.tierColor(9), "越界等级必须钳制到 3 级");
        assertTrue(luminance(RpTheme.tierColor(1)) > luminance(RpTheme.tierColor(2)));
        assertTrue(luminance(RpTheme.tierColor(2)) > luminance(RpTheme.tierColor(3)));
    }

    @Test
    void relationColorKeepsThreeStatesDistinct() {
        assertEquals(RpTheme.NEUTRAL, RpTheme.relationColor("neutral"));
        assertEquals(RpTheme.HOSTILE, RpTheme.relationColor("hostile"));
        assertEquals(RpTheme.FRIENDLY, RpTheme.relationColor("friendly"));
        assertEquals(RpTheme.NEUTRAL, RpTheme.relationColor(""), "空/未知关系按中立处理");
        assertEquals(RpTheme.NEUTRAL, RpTheme.relationColor(null));
        // 三态必须两两不同（关系图/关系页签/入场电影/击杀播报同源，见 docs/14 §6）
        assertNotEquals(RpTheme.NEUTRAL, RpTheme.HOSTILE);
        assertNotEquals(RpTheme.NEUTRAL, RpTheme.FRIENDLY);
        assertNotEquals(RpTheme.HOSTILE, RpTheme.FRIENDLY);
        assertEquals(RpTheme.HOSTILE, RpTheme.RED, "敌对＝红，与红色警戒族同源");
    }

    @Test
    void statusColorFallsBackToObserving() {
        assertEquals(RpTheme.STATUS_ALIVE, RpTheme.statusColor("alive"));
        assertEquals(RpTheme.STATUS_DEAD, RpTheme.statusColor("dead"));
        assertEquals(RpTheme.STATUS_OBSERVING, RpTheme.statusColor("observing"));
        assertEquals(RpTheme.STATUS_OBSERVING, RpTheme.statusColor(null));
        assertEquals(RpTheme.STATUS_OBSERVING, RpTheme.statusColor("whatever"));
    }

    @Test
    void rowColorZebraAndHover() {
        assertEquals(RpTheme.PANEL_BG, RpTheme.rowColor(0, false));
        assertEquals(RpTheme.TRANSPARENT, RpTheme.rowColor(1, false));
        assertEquals(RpTheme.PANEL_BG, RpTheme.rowColor(2, false));
        assertEquals(RpTheme.PANEL_BG_ALT, RpTheme.rowColor(1, true), "悬停优先于斑马纹");
        assertEquals(RpTheme.PANEL_BG_ALT, RpTheme.rowColor(0, true));
        assertTrue(luminance(RpTheme.rowColor(0, true)) > luminance(RpTheme.rowColor(0, false)), "悬停行必须比常态行更亮");
    }

    @Test
    void alphaBlendKeepsRgbAndSetsAlpha() {
        assertEquals(0x80FF3B30, RpTheme.alphaBlend(RpTheme.RED, 0x80));
        assertEquals(RpTheme.CYAN & 0xFFFFFF, RpTheme.alphaBlend(RpTheme.CYAN, 0x00) & 0xFFFFFF);
        assertEquals(0x00, alpha(RpTheme.alphaBlend(RpTheme.CYAN, 0x00)));
        for (int a = 0; a <= 255; a += 51) {
            assertEquals(a, alpha(RpTheme.alphaBlend(RpTheme.TEXT_DIM, a)));
            assertEquals(RpTheme.TEXT_DIM & 0xFFFFFF, RpTheme.alphaBlend(RpTheme.TEXT_DIM, a) & 0xFFFFFF);
        }
    }

    @Test
    void terminalLabelFormatting() {
        assertEquals("[ 阵营 ]", RpTheme.tag("阵营"));
        assertEquals("// PROFILE", RpTheme.section("PROFILE"));
    }
}
