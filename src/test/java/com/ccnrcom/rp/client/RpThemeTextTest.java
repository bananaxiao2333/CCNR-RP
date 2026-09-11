/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

/**
 * 文本布局原语的回归测试（docs/01 §4：修复回归必须有对应测试）。
 *
 * <p>针对 2.25.1 的实机缺陷：删除确认框正文用的是**按空格断行**，而中文句子没有空格 →
 * 整句被当成一个"词"永不换行 → 文字整行溢出弹窗、压到按钮上。现在断行只走 {@link RpTheme#wrapText}
 * 的**逐字符累计宽度**实现，本测试用"每字 6px"的假宽度函数把它钉死（不需要 MC 的 Font）。
 */
class RpThemeTextTest {

    /** 假宽度：每个字符 6px（等宽近似；中文/英文一视同仁，正好覆盖"中文没有空格"这一前提）。 */
    private static final ToIntFunction<String> W6 = s -> s.length() * 6;

    // ---------- wrapText ----------

    @Test
    void chineseSentenceWithoutSpacesWraps() {
        String msg = "删除关系管理「内部: admin_hq, madison, nuclear」？此操作直接落盘，无法撤销。";
        List<String> lines = RpTheme.wrapText(msg, 120, W6); // 120px / 6px = 每行 20 字
        assertTrue(lines.size() > 1, "无空格的中文长句必须断成多行，实际: " + lines.size() + " 行");
        for (String line : lines) {
            assertTrue(W6.applyAsInt(line) <= 120, "断行后每行都不得超过 maxWidth: " + line);
        }
        assertEquals(msg, String.join("", lines), "断行不得丢字符（拼接回原文）");
    }

    @Test
    void explicitNewlineForcesBreak() {
        List<String> lines = RpTheme.wrapText("a\nb", 600, W6);
        assertEquals(List.of("a", "b"), lines);
    }

    @Test
    void emptyAndBlankBecomeSingleEmptyLine() {
        assertEquals(List.of(""), RpTheme.wrapText("", 100, W6));
        assertEquals(List.of(""), RpTheme.wrapText("   ", 100, W6));
        assertEquals(List.of(""), RpTheme.wrapText(null, 100, W6));
    }

    @Test
    void shortTextStaysOneLine() {
        assertEquals(List.of("abc"), RpTheme.wrapText("abc", 100, W6));
    }

    @Test
    void singleCharWiderThanMaxStillEmitsLine() {
        // 极端窄列：一个字符都放不下时也必须输出该字符（否则会吞字）
        List<String> lines = RpTheme.wrapText("abcdef", 3, W6);
        assertEquals("abcdef", String.join("", lines));
        assertTrue(lines.size() >= 6);
    }

    // ---------- clip ----------

    @Test
    void clipTruncatesWithEllipsis() {
        String out = RpTheme.clip("abcdefghij", 36, W6); // 36px = 6 字 → "abcde…" 也是 6 字
        assertTrue(out.endsWith("…"), "超宽必须带省略号: " + out);
        assertTrue(W6.applyAsInt(out) <= 36, "裁剪后不得超过 maxWidth: " + out);
    }

    @Test
    void clipKeepsShortTextAndHandlesNull() {
        assertEquals("abc", RpTheme.clip("abc", 100, W6));
        assertEquals("", RpTheme.clip(null, 100, W6));
        assertEquals("", RpTheme.clip("", 100, W6));
    }

    @Test
    void clipNeverReturnsBareEllipsisOnNarrowColumn() {
        String out = RpTheme.clip("abcdef", 3, W6); // 连一个字符加省略号都放不下
        assertEquals("a", out, "极窄列至少要保留一个字符，不能只剩省略号");
    }

    // ---------- 确认框行数（弹窗高度依赖它） ----------

    @Test
    void dialogLineCountGrowsWithLongTarget() {
        int w = 400 - 28;
        int shortMsg = RpTheme.wrapText("删除职业「qdf_guard」？", w, W6).size();
        int longMsg = RpTheme.wrapText(
                        "删除关系管理「内部: admin_hq, madison, nuclear, rd_tech, qdf, qsa, qso, logistics 友好」？", w, W6)
                .size();
        assertEquals(1, shortMsg);
        assertTrue(longMsg > 1, "长目标必须撑高弹窗（按行数算高度），实际行数: " + longMsg);
    }
}
