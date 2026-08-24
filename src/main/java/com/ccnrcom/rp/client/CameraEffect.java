/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

/**
 * 镜头步骤（CAMERA）占位实现：1.20.1 客户端相机/FOV 均受控于 Minecraft 内部私有状态，
 * 表现层采用"无侵入占位"——步骤在序列中正常计时，视觉扩展（抖动/滤镜）由后续增强。
 */
public class CameraEffect {
    private CameraEffect() {}

    public static void start(long from, long to, int durationTicks, String shake) {
        // 预留：真实 FOV/相机驱动需 mixin 或客户端控制通道
    }

    public static void tick() {}
}
