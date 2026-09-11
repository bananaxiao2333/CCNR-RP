/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 终端向量图标（纯填充栅格化：圆形/多边形/扫描线）。
 * 用于机构徽章（盾牌/爪印/风暴/六边形/之眼/靶心…）与战术装备槽（头/胸/腿/背）。
 */
public final class RpIcons {

    private RpIcons() {}

    /** 实心圆盘。 */
    public static void circle(GuiGraphics g, int cx, int cy, int r, int color) {
        if (r < 1) {
            return;
        }
        for (int dy = -r; dy <= r; dy++) {
            int h = (int) Math.floor(Math.sqrt(r * r - dy * dy));
            g.fill(cx - h, cy + dy, cx + h + 1, cy + dy + 1, color);
        }
    }

    /** 圆环（外圆 r，内圆 r-1 用 disc 色挖空）。 */
    public static void ring(GuiGraphics g, int cx, int cy, int r, int ringColor, int discColor) {
        circle(g, cx, cy, r, ringColor);
        circle(g, cx, cy, Math.max(0, r - 1), discColor);
    }

    /** 偶数奇偶扫描线多边形填充。 */
    private static void poly(GuiGraphics g, int[][] p, int color) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int[] pt : p) {
            minY = Math.min(minY, pt[1]);
            maxY = Math.max(maxY, pt[1]);
        }
        int n = p.length;
        for (int y = minY; y <= maxY; y++) {
            double sy = y + 0.5;
            List<Double> xs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int[] a = p[i];
                int[] b = p[(i + 1) % n];
                if ((a[1] < sy && b[1] >= sy) || (b[1] < sy && a[1] >= sy)) {
                    xs.add(a[0] + (sy - a[1]) * (double) (b[0] - a[0]) / (b[1] - a[1]));
                }
            }
            Collections.sort(xs);
            for (int i = 0; i + 1 < xs.size(); i += 2) {
                g.fill((int) Math.ceil(xs.get(i)), y, (int) Math.floor(xs.get(i + 1)) + 1, y + 1, color);
            }
        }
    }

    /**
     * 图标多边形顶点（16 单位盒内，供 GUI 与世界空间渲染共用）；未知/图片图标回退 hex。
     * 返回的是 16 单位盒坐标，调用方自行缩放映射。
     */
    public static int[][] iconPolygon(String name) {
        return switch (name == null ? "" : name) {
            case "shield" -> new int[][] {{8, 1}, {13, 3}, {15, 5}, {15, 11}, {8, 15}, {1, 11}, {1, 5}, {3, 3}};
            case "storm" -> new int[][] {{9, 1}, {3, 9}, {7, 9}, {6, 15}, {13, 7}, {8, 7}};
            case "hex" -> new int[][] {{8, 1}, {14, 4}, {14, 12}, {8, 15}, {2, 12}, {2, 4}};
            case "eye" -> new int[][] {{1, 8}, {8, 3}, {15, 8}, {8, 13}};
            case "claw" -> new int[][] {{1, 12}, {4, 12}, {13, 4}, {10, 4}, {8, 7}, {6, 7}, {5, 4}, {3, 7}};
            case "cross" -> new int[][] {
                {5, 1}, {11, 1}, {11, 5}, {15, 5}, {15, 11}, {11, 11}, {11, 15}, {5, 15}, {5, 11}, {1, 11}, {1, 5},
                {5, 5}
            };
            case "helm" -> new int[][] {{3, 10}, {3, 5}, {6, 2}, {10, 2}, {13, 5}, {13, 10}};
            case "chest" -> new int[][] {{2, 3}, {14, 3}, {15, 7}, {15, 13}, {8, 16}, {1, 13}, {1, 7}};
            case "back" -> new int[][] {{2, 2}, {14, 2}, {15, 13}, {1, 13}};
            case "gear" -> new int[][] {
                {5, 3}, {11, 3}, {11, 5}, {13, 5}, {13, 8}, {16, 8}, {16, 11}, {13, 11}, {13, 14}, {11, 14},
                {11, 16}, {5, 16}, {5, 14}, {3, 14}, {3, 11}, {0, 11}, {0, 8}, {3, 8}, {3, 5}, {5, 5}
            };
            case "heart" -> new int[][] {{8, 14}, {2, 8}, {3, 4}, {5, 3}, {8, 6}, {11, 3}, {13, 4}, {14, 8}};
            default -> new int[][] {{8, 1}, {14, 4}, {14, 12}, {8, 15}, {2, 12}, {2, 4}};
        };
    }

    /** 在 (x0,y0)..(x0+s,y0+s) 的 16 单位盒内绘制图标。 */
    private static void glyph(GuiGraphics g, int x0, int y0, int s, String name, int color, int punchColor) {
        int[][] p = iconPolygon(name);
        int[][] mapped = new int[p.length][2];
        for (int i = 0; i < p.length; i++) {
            mapped[i][0] = x0 + p[i][0] * s / 16;
            mapped[i][1] = y0 + p[i][1] * s / 16;
        }
        poly(g, mapped, color);
        switch (name) {
            case "eye" -> circle(g, x0 + 8 * s / 16, y0 + 8 * s / 16, Math.max(1, s / 16), color);
            case "helm" -> g.fill(
                    x0 + 3 * s / 16, y0 + 7 * s / 16, x0 + 13 * s / 16 + 1, y0 + 8 * s / 16 + 1, punchColor);
            case "legs" -> {
                g.fill(x0 + 3 * s / 16, y0 + 3 * s / 16, x0 + 7 * s / 16 + 1, y0 + 13 * s / 16 + 1, color);
                g.fill(x0 + 9 * s / 16, y0 + 3 * s / 16, x0 + 13 * s / 16 + 1, y0 + 13 * s / 16 + 1, color);
            }
            case "back" -> {
                g.fill(x0 + 4 * s / 16, y0, x0 + 6 * s / 16 + 1, y0 + 3 * s / 16 + 1, color);
                g.fill(x0 + 10 * s / 16, y0, x0 + 12 * s / 16 + 1, y0 + 3 * s / 16 + 1, color);
            }
            case "gear" -> circle(g, x0 + 8 * s / 16, y0 + 8 * s / 16, Math.max(1, s / 8), punchColor);
            default -> {}
        }
    }

    /** 机构徽章：环(等级色) + 盘 + 图形 + 右下等级刻度。selected=红色警戒态。 */
    public static void badge(GuiGraphics g, int cx, int cy, int r, String icon, int tier, boolean selected) {
        int ring = selected ? RpTheme.RED_LINE : RpTheme.tierColor(tier);
        ring(g, cx, cy, r, ring, RpTheme.BADGE_DISC);
        polygon(g, cx, cy, r - 1, icon, selected ? RpTheme.CYAN : ring, RpTheme.BADGE_PUNCH);
        int n = Math.max(2, r / 4);
        g.fill(cx + r - n - 1, cy + r - n - 1, cx + r, cy + r, ring);
    }

    /** 阵营徽章（统一入口）：从阵营 JSON 读 icon/tier（icon 空→hex；tier 钳制 1..3；faction 为 null 用默认）。 */
    public static void factionBadge(
            GuiGraphics g, int cx, int cy, int r, com.google.gson.JsonObject faction, boolean selected) {
        int tier = 2;
        String icon = "";
        if (faction != null) {
            if (faction.has("tier") && faction.get("tier").isJsonPrimitive()) {
                tier = faction.get("tier").getAsInt();
            }
            icon = faction.has("icon") && !faction.get("icon").isJsonNull()
                    ? faction.get("icon").getAsString()
                    : "";
        }
        factionBadge(g, cx, cy, r, icon, tier, selected);
    }

    /** 阵营徽章（底层）：icon 空→hex；tier 钳制 1..3。 */
    public static void factionBadge(GuiGraphics g, int cx, int cy, int r, String icon, int tier, boolean selected) {
        if (icon == null || icon.isBlank()) {
            icon = "hex";
        }
        badge(g, cx, cy, r, icon, Math.max(1, Math.min(3, tier)), selected);
    }

    /** 大号阵营徽章（入场电影）：外晕 + 等级色环 + 全息同心环 + 大图形 + 刻度。alpha 0..255。 */
    public static void bigBadge(GuiGraphics g, int cx, int cy, int r, String icon, int tier, int alpha) {
        int ring = RpTheme.tierColor(tier);
        circle(g, cx, cy, r + 3, RpTheme.alphaBlend(ring, alpha * 2 / 5));
        circle(g, cx, cy, r + 1, RpTheme.alphaBlend(ring, alpha));
        ring(g, cx, cy, r, RpTheme.alphaBlend(ring, alpha), RpTheme.alphaBlend(RpTheme.BADGE_DISC, alpha));
        int inner = Math.max(4, r * 2 / 3);
        circle(g, cx, cy, inner, RpTheme.alphaBlend(ring, alpha * 2 / 5));
        polygon(
                g,
                cx,
                cy,
                r - 1,
                icon,
                RpTheme.alphaBlend(RpTheme.CYAN, alpha),
                RpTheme.alphaBlend(RpTheme.BADGE_DISC, alpha));
        int n = Math.max(3, r / 3);
        g.fill(cx + r - n - 1, cy + r - n - 1, cx + r, cy + r, RpTheme.alphaBlend(ring, alpha));
    }

    /** 战术装备槽图标（头/胸/腿/背）。 */
    public static void slot(GuiGraphics g, int x1, int y1, int size, String name, int color) {
        RpRoundRect.outlined(g, x1, y1, x1 + size, y1 + size, 4f, RpTheme.PANEL_BORDER, RpTheme.SLOT_BG);
        glyph(g, x1 + 2, y1 + 2, Math.max(4, size - 4), name, color, RpTheme.SLOT_PUNCH);
    }

    /** 在盒子内画多边形图标（16 单位盒映射）；img:<名> 时绘制服务器下发的图片徽章。 */
    private static void polygon(GuiGraphics g, int cx, int cy, int r, String name, int color, int punchColor) {
        if (name != null && name.startsWith("img:")) {
            drawImageBadge(g, cx, cy, r, name.substring(4), color);
            return;
        }
        int s = r * 2 - 2;
        if (s < 4) {
            return;
        }
        glyph(g, cx - s / 2, cy - s / 2, s, name, color, punchColor);
    }

    /** 图片徽章：底色盘 + 方形纹理（alpha 随 color 的整体透明度走）。素材由服务器中央下发。 */
    private static void drawImageBadge(GuiGraphics g, int cx, int cy, int r, String fileName, int color) {
        try {
            if (fileName == null || !fileName.matches("[A-Za-z0-9_-]+")) {
                return;
            }
            // img:<名> 图标由服务器素材库下发（config/ccnr_rp/textures/），客户端缓存后使用；未就绪时仅画底盘。
            net.minecraft.resources.ResourceLocation loc = ClientAssetCache.serverIcon(fileName);
            if (loc == null) {
                return;
            }
            int tw = ClientAssetCache.iconSize(fileName);
            // 触发纹理注册加载
            net.minecraft.client.Minecraft.getInstance().getTextureManager().getTexture(loc);
            int th = tw;
            int s = r * 2;
            if (s < 4) {
                return;
            }
            // 底盘（保持徽章底色质感）+ 纹理；alpha 为整体透明度（入场电影淡出用）
            circle(g, cx, cy, r - 1, color);
            float a = ((color >>> 24) & 0xFF) / 255f;
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, a);
            g.blit(loc, cx - s / 2, cy - s / 2, s, s, 0, 0, tw, th, tw, th);
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        } catch (Exception ignored) {
            // 图片徽章缺失回退（仅画底盘）
        }
    }
}
