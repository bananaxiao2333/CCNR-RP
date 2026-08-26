/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * 玩家头顶悬浮标签（世界空间 billboard，客户端本地渲染）：阵营徽章 + 职业名(阵营色) + 玩家名 + 等级。
 * 数据来自 PlayerTagsS2C 下发的 ClientCharacterState.playerTag(uuid)；服务端已过滤，仅非观察者（已部署）玩家有数据。
 * 渲染方式仿原版名字牌：在实体头顶上方 mulPose(cameraOrientation) 使其始终面向相机 + scale(-0.025,-0.025,0.025) +
 * font.drawInBatch 绘制文字。只有本地客户端渲染，其他玩家看不到；自带透视（远小近大）。
 */
public final class PlayerNametagRenderer {

    /** 标签顶端离头顶的世界偏移（格）：0.9 格起，避免遮挡头部。 */
    private static final double TAG_OFFSET = 0.9;

    private PlayerNametagRenderer() {}

    /** 世界空间渲染：RenderLevelStageEvent.AFTER_ENTITIES 阶段为每个其他玩家绘制头顶悬浮标签。 */
    public static void renderWorld(PoseStack poseStack, Camera cam, float partialTick, MultiBufferSource buffer) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.font == null || cam == null) {
            return;
        }
        Font font = mc.font;
        Vec3 camPos = cam.getPosition();
        // 相机朝向（billboard：标签始终面向相机）
        Quaternionf camRot = mc.getEntityRenderDispatcher().cameraOrientation();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof AbstractClientPlayer other) || other == mc.player) {
                continue;
            }
            ClientCharacterState.PlayerTag tag =
                    ClientCharacterState.playerTag(other.getUUID().toString());
            if (tag == null || tag.name() == null || tag.name().isBlank()) {
                continue;
            }
            if (!isInView(mc, other)) {
                continue;
            }
            // 头顶位置（脚底上方一个身高 + 偏移），partialTick 插值避免移动滞后
            double tx = Mth.lerp(partialTick, other.xo, other.getX());
            double ty = Mth.lerp(partialTick, other.yo, other.getY()) + other.getBbHeight() + TAG_OFFSET;
            double tz = Mth.lerp(partialTick, other.zo, other.getZ());
            poseStack.pushPose();
            poseStack.translate(tx - camPos.x, ty - camPos.y, tz - camPos.z);
            poseStack.mulPose(camRot);
            poseStack.scale(-0.025F, -0.025F, 0.025F);
            Matrix4f matrix = poseStack.last().pose();
            drawTag(font, matrix, buffer, tag);
            poseStack.popPose();
        }
    }

    private static void drawTag(
            Font font, Matrix4f matrix, MultiBufferSource buffer, ClientCharacterState.PlayerTag tag) {
        int factionColor = factionColor(tag.factionId());
        String profession = professionDisplay(tag.professionId());
        // 第一行：职业名（阵营色，居中；徽章简化为一枚阵营色圆点前缀）
        Component line1 = Component.literal("● " + profession).withStyle(s -> s.withColor(factionColor));
        // 第二行：玩家名（白）
        Component line2 = Component.literal(tag.name());
        // 第三行：等级（青）
        Component line3 = Component.literal("Lv." + tag.level()).withStyle(s -> s.withColor(0x3DD2FF));
        int lineGap = 10;
        int total = lineGap * 3;
        float y = -total + lineGap; // 从标签底部向上排，锚点在头顶上方
        // 每行半透明底衬（对齐原版名字牌的背景）
        int bg = 0x66000000; // 40% 黑
        int light = 0xF000F0; // FULL_BRIGHT 附近，保证任何光照下可读
        drawCentered(font, matrix, buffer, line1, y, bg, light);
        drawCentered(font, matrix, buffer, line2, y + lineGap, bg, light);
        drawCentered(font, matrix, buffer, line3, y + lineGap * 2, bg, light);
    }

    private static void drawCentered(
            Font font, Matrix4f matrix, MultiBufferSource buffer, Component text, float y, int bg, int light) {
        float w = font.width(text);
        font.drawInBatch(
                text,
                -w / 2.0F,
                y,
                0xFFFFFFFF,
                false,
                matrix,
                buffer,
                net.minecraft.client.gui.Font.DisplayMode.NORMAL,
                bg,
                light);
    }

    /** 粗略可见性判断：与游戏渲染距离一致（人物在该距离内才渲染，标签随之显示/隐藏）。 */
    private static boolean isInView(Minecraft mc, Entity e) {
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        double dist = mc.gameRenderer.getRenderDistance(); // 游戏实际渲染距离（方块）
        return e.distanceToSqr(cam) < dist * dist;
    }

    private static com.google.gson.JsonObject factionJson(String factionId) {
        if (factionId == null || factionId.isBlank()) {
            return null;
        }
        for (com.google.gson.JsonObject f : ClientCharacterState.factions()) {
            if (factionId.equals(
                    f.has("id") && !f.get("id").isJsonNull() ? f.get("id").getAsString() : "")) {
                return f;
            }
        }
        return null;
    }

    private static int factionColor(String factionId) {
        com.google.gson.JsonObject f = factionJson(factionId);
        if (f == null) {
            return 0x3DD2FF;
        }
        String c =
                f.has("color") && !f.get("color").isJsonNull() ? f.get("color").getAsString() : "";
        try {
            return 0xFFFFFF & Integer.parseInt(c.replace("#", ""), 16);
        } catch (Exception ignored) {
            return 0x3DD2FF;
        }
    }

    private static String professionDisplay(String professionId) {
        if (professionId == null || professionId.isBlank()) {
            return "?";
        }
        return ClientCharacterState.professionName(professionId);
    }
}
