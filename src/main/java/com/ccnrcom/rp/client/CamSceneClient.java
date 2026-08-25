/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import net.minecraftforge.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * CMDCam（modid=cmdcam）客户端可选联动（Dist.CLIENT）：
 * 反射探测 {@code team.creative.cmdcam.client.CMDCamClient.isPlaying()}，判断摄像机场景是否正在播放。
 * CMDCam 未安装/类缺失/反射失败时一律返回 false（调用方安全降级）。
 */
public final class CamSceneClient {
    private static final Logger LOGGER = LogManager.getLogger();

    private static Boolean cmdcamPresent = null;
    private static java.lang.reflect.Method isPlayingMethod = null;

    private CamSceneClient() {}

    private static boolean available() {
        if (cmdcamPresent == null) {
            boolean ok = false;
            try {
                if (ModList.get().isLoaded("cmdcam")) {
                    Class<?> cls = Class.forName(
                            "team.creative.cmdcam.client.CMDCamClient", false, CamSceneClient.class.getClassLoader());
                    isPlayingMethod = cls.getMethod("isPlaying");
                    ok = true;
                }
            } catch (Throwable t) {
                LOGGER.debug("[CCNR-RP] CMDCam 客户端探测失败: {}", t.toString());
                ok = false;
            }
            cmdcamPresent = ok;
        }
        return cmdcamPresent;
    }

    /** CMDCam 摄像机场景是否正在播放；未装/异常返回 false。 */
    public static boolean playing() {
        if (!available() || isPlayingMethod == null) {
            return false;
        }
        try {
            Object v = isPlayingMethod.invoke(null);
            return v instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }
}
