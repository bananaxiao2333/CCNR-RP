/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * 玩家头顶标签（旁观者/观察者视角可见）：阵营徽章 + 职业名(阵营色) + 玩家名 + 等级。
 * 数据来自 PlayerTagsS2C 下发的 ClientCharacterState.playerTag(uuid)；非观察者视角或数据缺失时跳过。
 * 投影使用相机官方正交基（getLookVector/getUpVector/getLeftVector），像素缩放用 FOV 静态设置值；
 * 标签尺寸按透视距离缩放（远小近大，同原版名字牌），可见距离 64 格，稳定钉在玩家头顶不乱飘。
 */
public final class PlayerNametagRenderer {

    /** 标签设计距离：该距离下缩放系数 = 1.0（6 格内放大、6 格外缩小，同原版透视）。 */
    private static final double TAG_BASE_DIST = 6.0;

    private PlayerNametagRenderer() {}

    /** HUD 层渲染：旁观者视角下为每个其他玩家绘制头顶标签。 */
    public static void render(GuiGraphics gfx, int w, int h, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getEntityRenderDispatcher().camera == null) {
            return;
        }
        if (!isObserverView()) {
            return;
        }
        Font font = mc.font;
        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 camPos = cam.getPosition();
        // FOV 半角正切（mc.options.fov 为静态设置值；GameRenderer.getProjectionMatrix 的参数是
        // FOV 度数而非 partialTick，不可直接传入）
        double fov = mc.options.fov().get();
        float tanHalf = (float) Math.tan(Math.toRadians(fov) / 2.0);
        // 相机正交基（1.20.1 官方 API，方向保证正确；right = -left）
        Vector3f look = cam.getLookVector();
        Vector3f up = cam.getUpVector();
        Vector3f left = cam.getLeftVector();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof AbstractClientPlayer other) || other == mc.player) {
                continue;
            }
            ClientCharacterState.PlayerTag tag =
                    ClientCharacterState.playerTag(other.getUUID().toString());
            if (tag == null || tag.name() == null || tag.name().isBlank()) {
                continue;
            }
            if (cam.isDetached() || !isInView(mc, other)) {
                continue;
            }
            // 头顶位置（脚底上方一个身高 + 0.45），partialTick 插值避免移动滞后
            double tx = Mth.lerp(partialTick, other.xo, other.getX());
            double ty = Mth.lerp(partialTick, other.yo, other.getY()) + other.getBbHeight() + 0.45;
            double tz = Mth.lerp(partialTick, other.zo, other.getZ());
            double dx = tx - camPos.x;
            double dy = ty - camPos.y;
            double dz = tz - camPos.z;
            double depth = dx * look.x + dy * look.y + dz * look.z;
            if (depth <= 0.1) {
                continue; // 在相机后方
            }
            double rx = dx * (-left.x) + dy * (-left.y) + dz * (-left.z);
            double uy = dx * up.x + dy * up.y + dz * up.z;
            double scale = (h / 2.0) / (depth * tanHalf);
            int sx = (int) Math.round(w / 2.0 + rx * scale);
            int sy = (int) Math.round(h / 2.0 - uy * scale);
            // 视口外剔除
            if (sx < -120 || sx > w + 120 || sy < -60 || sy > h + 60) {
                continue;
            }
            // 透视距离缩放：远小近大（同原版名字牌），6 格处为 1.0
            float distScale = (float) Math.max(0.3, Math.min(2.5, TAG_BASE_DIST / Math.max(1.0, depth)));
            drawTag(gfx, font, sx, sy, tag, distScale);
        }
    }

    private static void drawTag(
            GuiGraphics gfx, Font font, int cx, int topY, ClientCharacterState.PlayerTag tag, float scale) {
        // 整体缩放（徽章/文字/底衬一起远小近大），以 (cx, topY) 为标签左上角原点
        PoseStack pose = gfx.pose();
        pose.pushPose();
        pose.translate(cx, topY, 0.0f);
        pose.scale(scale, scale, 1.0f);
        int factionColor = factionColor(tag.factionId());
        String profession = professionDisplay(tag.professionId());
        int badgeR = 7;
        // 第一行：徽章(左) + 职业名(阵营色)
        int pW = font.width(profession);
        int row1W = badgeR * 2 + 4 + pW;
        // 底衬
        gfx.fill(-row1W / 2 - 3, -2, row1W / 2 + 3, 10, 0x66000000);
        // 徽章（真实阵营图标）
        com.google.gson.JsonObject faction = factionJson(tag.factionId());
        RpIcons.factionBadge(gfx, -row1W / 2 + badgeR, 4, badgeR, faction, false);
        // 职业名（阵营色，在徽章右侧）
        gfx.drawString(font, profession, -row1W / 2 + badgeR * 2 + 4, 0, factionColor, true);
        // 第二行：玩家名（白）
        int y2 = 12;
        int w2 = font.width(tag.name());
        gfx.fill(-w2 / 2 - 3, y2 - 2, w2 / 2 + 3, y2 + 10, 0x66000000);
        gfx.drawString(font, tag.name(), -w2 / 2, y2, 0xFFFFFFFF, true);
        // 第三行：等级（青）
        int y3 = y2 + 12;
        String lv = "Lv." + tag.level();
        int w3 = font.width(lv);
        gfx.fill(-w3 / 2 - 3, y3 - 2, w3 / 2 + 3, y3 + 10, 0x66000000);
        gfx.drawString(font, lv, -w3 / 2, y3, 0xFF3DD2FF, true);
        pose.popPose();
    }

    private static boolean isObserverView() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }
        return ClientCharacterState.userStatus() == com.ccnrcom.rp.status.CharacterStatus.OBSERVING
                && !ClientCharacterState.isDeployed();
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
            return 0xFF3DD2FF;
        }
        String c =
                f.has("color") && !f.get("color").isJsonNull() ? f.get("color").getAsString() : "";
        try {
            return 0xFF000000 | Integer.parseInt(c.replace("#", ""), 16);
        } catch (Exception ignored) {
            return 0xFF3DD2FF;
        }
    }

    private static String professionDisplay(String professionId) {
        if (professionId == null || professionId.isBlank()) {
            return "?";
        }
        return ClientCharacterState.professionName(professionId);
    }
}
