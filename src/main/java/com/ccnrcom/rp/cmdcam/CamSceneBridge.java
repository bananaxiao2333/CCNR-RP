/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.cmdcam;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * CMDCam（modid=cmdcam）可选联动桥：部署入场电影结束后的「阵营 SCENE 出场」。
 * 未安装 CMDCam 时 isAvailable()=false，调用方静默跳过（不崩服、无副作用）。
 *
 * <p>播放链路：客户端在入场电影播完（渐变黑屏转场）时发 CamScenePlayC2S → 服务端此处
 * 通过 ModList 探测 + 反射调用，把已保存的 CMDCam 场景包（StartPathPacket）发给该玩家，
 * 由客户端 CMDCam 播放摄像机路径（scene），随后玩家视角回到部署点。
 *
 * <p>依赖：CMDCam（team.creative.cmdcam）+ CreativeCore。均为可选编译期依赖（反射调用，
 * 缺失不会导致类加载失败），与 Corpse 桥思路一致。
 */
public final class CamSceneBridge {
    private static final Logger LOGGER = LogManager.getLogger();

    private static Boolean cmdcamPresent = null;

    private CamSceneBridge() {}

    /** CMDCam 是否已安装（含其网络/场景系统可用）。缓存结果，避免每 tick 探测。 */
    public static boolean available() {
        if (cmdcamPresent == null) {
            boolean ok = false;
            try {
                if (ModList.get().isLoaded("cmdcam")) {
                    Class.forName("team.creative.cmdcam.CMDCam", true, CamSceneBridge.class.getClassLoader());
                    Class.forName(
                            "team.creative.cmdcam.server.CMDCamServer", false, CamSceneBridge.class.getClassLoader());
                    ok = true;
                }
            } catch (Throwable t) {
                LOGGER.debug("[CCNR-RP] CMDCam 探测失败: {}", t.toString());
                ok = false;
            }
            cmdcamPresent = ok;
        }
        return cmdcamPresent;
    }

    /**
     * 已保存场景名列表（按名称不区分大小写排序）；CMDCam 未装/反射失败/无场景时返回空列表
     * （管理面板补全提示用，调用方安全降级为无提示）。
     *
     * @param level 任意服务器世界（CMDCam 场景保存于世界维度数据，取同一 server 即可）
     * @return 已保存场景名；异常/未安装时空列表
     */
    public static List<String> savedSceneNames(Level level) {
        if (!available() || level == null) {
            return List.of();
        }
        try {
            Class<?> serverCls = Class.forName(
                    "team.creative.cmdcam.server.CMDCamServer", true, CamSceneBridge.class.getClassLoader());
            java.lang.reflect.Method getSavedPaths = serverCls.getMethod("getSavedPaths", Level.class);
            Object paths = getSavedPaths.invoke(null, level);
            if (!(paths instanceof java.util.Collection<?> col)) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (Object o : col) {
                if (o instanceof String s && !s.isBlank() && !out.contains(s)) {
                    out.add(s);
                }
            }
            out.sort(String.CASE_INSENSITIVE_ORDER);
            return out;
        } catch (Throwable t) {
            LOGGER.debug("[CCNR-RP] CMDCam 场景名列表读取失败: {}", t.toString());
            return List.of();
        }
    }

    /**
     * 播放已保存的 CMDCam 场景给指定玩家（服务端调用，反射避免硬依赖）。
     *
     * @param level     玩家所在世界（用于取已保存场景数据）
     * @param sceneName 阵营配置里的 CMDCam 场景名
     * @param player    目标玩家
     * @return true=已发送播放；false=CMDCam 未装/场景不存在/反射失败（调用方静默）
     */
    public static boolean playScene(Level level, String sceneName, ServerPlayer player) {
        if (!available() || level == null || sceneName == null || sceneName.isBlank() || player == null) {
            return false;
        }
        try {
            Class<?> serverCls = Class.forName(
                    "team.creative.cmdcam.server.CMDCamServer", true, CamSceneBridge.class.getClassLoader());
            java.lang.reflect.Method get = serverCls.getMethod("get", Level.class, String.class);
            Object scene = get.invoke(null, level, sceneName);
            if (scene == null) {
                LOGGER.info("[CCNR-RP] CMDCam 场景不存在（跳过）: {}", sceneName);
                return false;
            }
            Class<?> startPkt = Class.forName(
                    "team.creative.cmdcam.common.packet.StartPathPacket", true, CamSceneBridge.class.getClassLoader());
            Object packet = startPkt.getConstructor(scene.getClass()).newInstance(scene);
            Class<?> cmdcamCls =
                    Class.forName("team.creative.cmdcam.CMDCam", true, CamSceneBridge.class.getClassLoader());
            java.lang.reflect.Field netField = cmdcamCls.getField("NETWORK");
            Object network = netField.get(null);
            java.lang.reflect.Method send = findSendToClient(network.getClass(), packet.getClass());
            send.invoke(network, packet, player);
            LOGGER.info(
                    "[CCNR-RP] CMDCam 场景播放已下发: {} → {}",
                    sceneName,
                    player.getName().getString());
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[CCNR-RP] CMDCam 场景播放失败（跳过，不阻断）: {}", t.toString());
            return false;
        }
    }

    /**
     * 查找 CreativeNetwork.sendToClient(packet, player) 方法。
     * 参数类型可能声明为基类 CreativePacket（而非具体包类型），getMethod 按声明类型精确匹配会
     * NoSuchMethodException；这里按方法名 + 参数可赋值性扫描，兼容基类/具体类两种签名。
     */
    private static java.lang.reflect.Method findSendToClient(Class<?> networkCls, Class<?> packetCls)
            throws NoSuchMethodException {
        for (java.lang.reflect.Method m : networkCls.getMethods()) {
            if (!m.getName().equals("sendToClient")) {
                continue;
            }
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length == 2 && pt[0].isAssignableFrom(packetCls) && pt[1].equals(ServerPlayer.class)) {
                return m;
            }
        }
        throw new NoSuchMethodException(
                "CreativeNetwork.sendToClient(assignable-from " + packetCls.getName() + ", ServerPlayer)");
    }
}
