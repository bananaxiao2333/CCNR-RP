/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.ccnrcom.rp.animation.AnimationModels;
import com.ccnrcom.rp.animation.AnimationModels.Step;
import com.ccnrcom.rp.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** 客户端动画执行器：步骤队列 + 每 tick 步进；FADE/CAMERA 渲染与参数由 FadeOverlay/CameraEffect 承担。 */
public final class ClientAnimationPlayer {
    private static final Deque<Step> QUEUE = new ArrayDeque<>();
    private static int remainingTicks = 0;
    private static boolean playing = false;

    private ClientAnimationPlayer() {}

    /** 收到 S2C 后入队（客户端主线程）。 */
    public static void play(String payload) {
        List<Step> steps = parseSteps(payload);
        QUEUE.clear();
        QUEUE.addAll(steps);
        remainingTicks = 0;
        if (!steps.isEmpty()) {
            beginNext();
        }
    }

    public static List<Step> parseSteps(String payload) {
        List<Step> out = new ArrayList<>();
        try {
            JsonObject root = JsonUtil.GSON.fromJson(payload, JsonObject.class);
            if (root.has("steps")) {
                JsonArray arr = root.getAsJsonArray("steps");
                for (int i = 0; i < arr.size(); i++) {
                    Step s = AnimationModels.parseStep(arr.get(i), "s2c.steps[" + i + "]", new ArrayList<>());
                    if (s != null) {
                        out.add(s);
                    }
                }
            }
        } catch (Exception e) {
            // 客户端解析失败仅丢弃本次动画
        }
        return out;
    }

    private static void beginNext() {
        if (QUEUE.isEmpty()) {
            playing = false;
            return;
        }
        playing = true;
        Step s = QUEUE.pollFirst();
        remainingTicks = (int) s.durationTicks();
        execute(s);
    }

    private static void execute(Step s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        switch (s.type()) {
            case "TITLE" -> {
                mc.gui.setTitle(Component.literal(s.param("title", "")));
                mc.gui.setSubtitle(Component.literal(s.param("subtitle", "")));
            }
            case "ACTIONBAR" -> {
                // 服务端已直接发送 ActionBar 包；客户端不再处理
            }
            case "FADE" -> FadeOverlay.start(
                    s.param("color", "#000000"),
                    (float) s.paramLong("from", 0),
                    (float) s.paramLong("to", 1),
                    remainingTicks);
            case "CAMERA" -> CameraEffect.start(
                    s.paramLong("fovFrom", -1), s.paramLong("fovTo", -1), remainingTicks, s.param("shake", "0"));
            case "CAMS" -> {
                // CMDCam 场景：请求服务端播放（服务端反射下发 StartPathPacket；缺失 CMDCam 静默）
                String scene = s.param("scene", "");
                if (!scene.isBlank() && mc.player != null) {
                    com.ccnrcom.rp.network.RpChannels.sendToServer(
                            new com.ccnrcom.rp.network.RpPackets.CamScenePlayC2S(scene));
                }
            }
            case "GROUP" -> {
                // 子步骤顺序排入队首（并行简化为串行；GROUP.parallel 后续增强）
                List<Step> children = new ArrayList<>(s.children());
                for (int i = children.size() - 1; i >= 0; i--) {
                    QUEUE.addFirst(children.get(i));
                }
            }
            default -> {}
        }
    }

    /** 客户端每 tick 步进。 */
    public static void tick() {
        if (!playing) {
            return;
        }
        remainingTicks--;
        if (remainingTicks <= 0) {
            beginNext();
        }
    }

    public static boolean isPlaying() {
        return playing;
    }
}
