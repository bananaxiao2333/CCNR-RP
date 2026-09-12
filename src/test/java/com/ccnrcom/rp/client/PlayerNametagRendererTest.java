/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * 世界空间头顶标签的**颜色 alpha 不变量**。
 *
 * <p><b>为什么要钉这一条（2.26.11 的实机缺陷）</b>：2.25.1 主题迁移时把这些常量写成了
 * {@code RpTheme.XXX & 0xFFFFFF}，把 alpha 抹成了 0。这个错误**只在部分绘制路径上可见**，所以极易漏判：
 * <ul>
 *   <li><b>文字</b>走 {@code Font.drawInBatch}，而原版 {@code Font} 有兜底——颜色 alpha 为 0 时会被强制
 *       或上 {@code 0xFF000000}（{@code Font.m_92719_}，字节码 {@code (color & 0xFC000000) == 0 → color | 0xFF000000}），
 *       所以**文字照常显示**；</li>
 *   <li><b>几何图形</b>（{@code RenderType.gui()} 的矩形/圆盘/多边形）与<b>纹理</b>
 *       （{@code RenderType.entityTranslucent} 的 {@code img:} 图片徽章）不走 Font，
 *       alpha 0 原样生效 → <b>整块全透明</b>。</li>
 * </ul>
 * 于是现象是"标签文字都在、只有图标看不见"，很容易被误判成"素材没下发/图片没加载"。
 * 本用例直接读常量断言 alpha，任何一次"顺手 & 0xFFFFFF"都会被挡下。
 *
 * <p>只反射静态 int 常量，不触碰任何 MC 渲染类型，因此可脱机运行。
 */
class PlayerNametagRendererTest {

    @Test
    void worldSpaceColoursKeepTheirAlpha() throws Exception {
        for (Field f : PlayerNametagRenderer.class.getDeclaredFields()) {
            if (!f.getName().startsWith("COLOR_") || f.getType() != int.class) {
                continue;
            }
            f.setAccessible(true);
            int argb = f.getInt(null);
            int alpha = (argb >>> 24) & 0xFF;
            if (f.getName().equals("COLOR_LINE_BG")) {
                // 底衬是**刻意**半透明的（0x66......），(0,255) 之间都算对
                assertTrue(alpha > 0, "COLOR_LINE_BG 必须可见（alpha=" + alpha + "）");
                continue;
            }
            assertEquals(
                    0xFF,
                    alpha,
                    f.getName() + " 的 alpha=" + alpha + "：几何/纹理绘制会整块全透明" + "（只有文字会被 Font 兜底救回来，所以症状是「文字在、图标不在」）");
        }
    }
}
