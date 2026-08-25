/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 玩家头顶标签（旁观者/观察者视角可见）：阵营徽章 + 职业名(阵营色) + 玩家名 + 等级。
 * 数据来自 PlayerTagsS2C 下发的 ClientCharacterState.playerTag(uuid)；非观察者视角或数据缺失时跳过。
 * 用屏幕投影：把玩家 3D 头顶位置投影到屏幕坐标，在 HUD 层用 GuiGraphics 绘制（阵营徽章为真实图标，非文本）。
 */
public final class PlayerNametagRenderer {

    private PlayerNametagRenderer() {}

    /** HUD 层渲染：旁观者视角下为每个其他玩家绘制头顶标签。 */
    public static void render(GuiGraphics gfx, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getEntityRenderDispatcher().camera == null) {
            return;
        }
        if (!isObserverView()) {
            return;
        }
        Font font = mc.font;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof AbstractClientPlayer other) || other == mc.player) {
                continue;
            }
            ClientCharacterState.PlayerTag tag =
                    ClientCharacterState.playerTag(other.getUUID().toString());
            if (tag == null || tag.name() == null || tag.name().isBlank()) {
                continue;
            }
            // 头顶位置（脚底上方一个身高 + 0.4）
            Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
            double dx = other.getX() - cam.x;
            double dy = other.getY() + other.getBbHeight() + 0.45 - cam.y;
            double dz = other.getZ() - cam.z;
            if (mc.getEntityRenderDispatcher().camera.isDetached() || !isInView(mc, other)) {
                continue;
            }
            // 投影到屏幕
            float yaw =
                    (float) Math.toRadians(mc.getEntityRenderDispatcher().camera.getYRot());
            float pitch =
                    (float) Math.toRadians(mc.getEntityRenderDispatcher().camera.getXRot());
            // 相机朝向基（Y 轴向上）
            double cosY = Math.cos(yaw), sinY = Math.sin(yaw);
            double cosP = Math.cos(pitch), sinP = Math.sin(pitch);
            // 转到相机空间（前=Z 负，右=X 正，上=Y 正）
            double fwdX = -sinY * cosP, fwdY = sinP, fwdZ = -cosY * cosP;
            double rightX = cosY, rightY = 0, rightZ = -sinY;
            double upX = sinY * sinP, upY = cosP, upZ = cosY * sinP;
            double depth = dx * fwdX + dy * fwdY + dz * fwdZ;
            if (depth >= -0.1) {
                continue; // 在相机后方
            }
            double rx = dx * rightX + dy * rightY + dz * rightZ;
            double uy = dx * upX + dy * upY + dz * upZ;
            float fov = (float) Math.toRadians(mc.options.fov().get());
            float tanHalf = (float) Math.tan(fov / 2.0);
            double scale = (h / 2.0) / (Math.abs(depth) * tanHalf);
            int sx = (int) Math.round(w / 2.0 + rx * scale);
            int sy = (int) Math.round(h / 2.0 - uy * scale);
            // 距离缩放：太远缩小
            float distScale = (float) Math.max(0.6, Math.min(1.4, 12.0 / Math.max(1.0, Math.abs(depth))));
            // 视口外剔除
            if (sx < -120 || sx > w + 120 || sy < -60 || sy > h + 60) {
                continue;
            }
            drawTag(gfx, font, sx, sy, tag, distScale);
        }
    }

    private static void drawTag(
            GuiGraphics gfx, Font font, int cx, int topY, ClientCharacterState.PlayerTag tag, float scale) {
        int factionColor = factionColor(tag.factionId());
        String profession = professionDisplay(tag.professionId());
        int badgeR = (int) Math.round(7 * scale);
        // 第一行：徽章(左) + 职业名(阵营色)
        int pW = font.width(profession);
        int row1W = badgeR * 2 + 4 + pW;
        int y1 = topY;
        // 底衬
        gfx.fill(cx - row1W / 2 - 3, y1 - 2, cx + row1W / 2 + 3, y1 + 10, 0x66000000);
        // 徽章（真实阵营图标）
        com.google.gson.JsonObject faction = factionJson(tag.factionId());
        RpIcons.factionBadge(gfx, cx - row1W / 2 + badgeR, y1 + 4, badgeR, faction, false);
        // 职业名（阵营色，在徽章右侧）
        gfx.drawString(font, profession, cx - row1W / 2 + badgeR * 2 + 4, y1, factionColor, true);
        // 第二行：玩家名（白）
        int y2 = y1 + 12;
        int w2 = font.width(tag.name());
        gfx.fill(cx - w2 / 2 - 3, y2 - 2, cx + w2 / 2 + 3, y2 + 10, 0x66000000);
        gfx.drawString(font, tag.name(), cx - w2 / 2, y2, 0xFFFFFFFF, true);
        // 第三行：等级（青）
        String lv = "Lv." + tag.level();
        int y3 = y2 + 12;
        int w3 = font.width(lv);
        gfx.fill(cx - w3 / 2 - 3, y3 - 2, cx + w3 / 2 + 3, y3 + 10, 0x66000000);
        gfx.drawString(font, lv, cx - w3 / 2, y3, 0xFF3DD2FF, true);
    }

    private static boolean isObserverView() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }
        return ClientCharacterState.userStatus() == com.ccnrcom.rp.status.CharacterStatus.OBSERVING
                && !ClientCharacterState.isDeployed();
    }

    /** 粗略可见性判断（在相机前方，距离合理）。 */
    private static boolean isInView(Minecraft mc, Entity e) {
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        return e.distanceToSqr(cam) < 144.0; // 12 格内
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
