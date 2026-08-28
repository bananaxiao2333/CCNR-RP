/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.data;

import com.ccnrcom.rp.CCNRRPMod;

/** 配置档切换/管理器 CRUD 后的全量热重载：调用所有配置管理器的 reload/load 钩子。 */
public final class ConfigReloader {

    private ConfigReloader() {}

    /** 重载全部配置管理器（服务端主线程调用）。 */
    public static void reloadAll() {
        if (CCNRRPMod.factions != null) {
            CCNRRPMod.factions.reload();
        }
        if (CCNRRPMod.managerSettings != null) {
            CCNRRPMod.managerSettings.load();
        }
        if (CCNRRPMod.eventManager != null) {
            CCNRRPMod.eventManager.reload();
        }
        if (CCNRRPMod.spawnFramework != null) {
            CCNRRPMod.spawnFramework.reload();
        }
        if (CCNRRPMod.sequenceEngine != null) {
            CCNRRPMod.sequenceEngine.reload();
        }
        if (CCNRRPMod.animationEngine != null) {
            CCNRRPMod.animationEngine.reload();
        }
        if (CCNRRPMod.experience != null) {
            CCNRRPMod.experience.reload();
        }
    }
}
