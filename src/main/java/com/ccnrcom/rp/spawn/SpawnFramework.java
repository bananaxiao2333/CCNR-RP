/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.spawn;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** 刷新框架占位（P8 实现完整版；P6 事件钩子可先调用）。 */
public final class SpawnFramework {
    private static final Logger LOGGER = LogManager.getLogger();

    public SpawnFramework(net.minecraft.server.MinecraftServer server) {}

    public void triggerWave(String waveId) {
        LOGGER.info("[CCNR-RP] 刷新波钩子（P8 实现）：{}", waveId);
    }
}
