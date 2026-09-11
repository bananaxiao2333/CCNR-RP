/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.area;

/**
 * 区域（拓展设定：目标区）纯数据模型——轴对齐长方体，坐标闭区间。
 * 无 MC import：判定只用 double，可直接 JUnit 测（坐标维度以注册名保存，如 "minecraft:overworld"）。
 *
 * <p>为什么用闭区间 + 归一化 min/max：管理员在面板上按任意两个角点填写（先填最大角还是最小角都行），
 * 判定时统一归一化，避免"填反了就不生效"这类静默失效。
 */
public record Area(
        String id, String name, String dim, double x1, double y1, double z1, double x2, double y2, double z2) {

    public double minX() {
        return Math.min(x1, x2);
    }

    public double maxX() {
        return Math.max(x1, x2);
    }

    public double minY() {
        return Math.min(y1, y2);
    }

    public double maxY() {
        return Math.max(y1, y2);
    }

    public double minZ() {
        return Math.min(z1, z2);
    }

    public double maxZ() {
        return Math.max(z1, z2);
    }

    /** 点是否落在区域内（维度必须一致；维度为空的区域不匹配任何维度）。 */
    public boolean contains(String dimension, double x, double y, double z) {
        if (dim == null || dim.isBlank() || !dim.equals(dimension)) {
            return false;
        }
        return x >= minX() && x <= maxX() && y >= minY() && y <= maxY() && z >= minZ() && z <= maxZ();
    }

    /** 展示用范围（管理面板/命令回显）。 */
    public String boundsText() {
        return String.format(
                java.util.Locale.ROOT,
                "%.1f %.1f %.1f → %.1f %.1f %.1f",
                minX(),
                minY(),
                minZ(),
                maxX(),
                maxY(),
                maxZ());
    }
}
