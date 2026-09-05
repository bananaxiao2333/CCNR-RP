/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * 玩家头顶悬浮标签（世界空间 billboard，客户端本地渲染）：第一行「阵营图标（左）+ 职业名（右，阵营色）」，
 * 第二行「血量 / 血量上限」。玩家名由原版名字牌显示，本标签整体上移一个文本行高，不与系统名称标签重叠。
 * 数据来自 PlayerTagsS2C 下发的 ClientCharacterState.playerTag(uuid)；服务端已过滤，仅非观察者（已部署）玩家有数据。
 * 血量读取实体同步数据 getHealth()/getMaxHealth()，仅本地展示、不回传服务端。
 * 渲染方式仿原版名字牌：在实体头顶上方 mulPose(cameraOrientation) 使其始终面向相机 + scale(-0.025,-0.025,0.025)。
 * 徽章矢量图形用扫描线填充（同 RpIcons 视觉），img: 图片徽章用纹理 quad 绘制。只有本地客户端渲染，其他玩家看不到。
 * 可配置项（服务端权威，随 CharacterListS2C 同步）：enabled 总开关 / badgeSize 徽章大小 / offset 标签高度。
 */
public final class PlayerNametagRenderer {

    // ---- 布局（世界单位；scale 0.025 下 1 单位 ≈ 0.025 格）----
    /** 一个文本行高（行距）。 */
    private static final int LINE_HEIGHT = 12;
    /** 行1（阵营图标 + 职业名）文字顶部 Y（负=向上；较旧版职业名行整体上移一个行高，为原版玩家名标签让位）。 */
    private static final int LINE_MAIN_Y = -20;
    /** 行2（血量 / 血量上限）文字顶部 Y。 */
    private static final int LINE_HP_Y = LINE_MAIN_Y + LINE_HEIGHT;
    /** 行1 中阵营图标与职业名文字的间距。 */
    private static final int ICON_TEXT_GAP = 3;

    // ---- 缩放（仿原版名字牌）----
    /** 世界缩放（原版名字牌同款：文字/几何整体缩放，远小近大）。 */
    private static final float TAG_SCALE = -0.025F;

    // ---- 颜色（复用 RpTheme；避免散落魔法数字）----
    /** 玩家名（白）。 */
    private static final int COLOR_NAME = 0xFFFFFFFF;
    /** 血量心形（红；文字颜色按 RGB 使用，高位 alpha 被忽略）。 */
    private static final int COLOR_HEART = 0xFFFF5555;
    /** 血量分隔符（灰）。 */
    private static final int COLOR_HP_DIVIDER = 0xFFAAAAAA;
    /** 徽章盘底色（深灰）。 */
    private static final int COLOR_BADGE_DISC = 0xFF2E2E2E;
    /** 徽章图形挖空色（深蓝黑）。 */
    private static final int COLOR_BADGE_PUNCH = 0xFF161616;
    /** 文字行半透明底衬。 */
    private static final int COLOR_LINE_BG = 0x66000000;
    /** 默认阵营色（青，与 RpTheme.CYAN 一致）。 */
    private static final int COLOR_FACTION_DEFAULT = RpTheme.CYAN & 0xFFFFFF;

    // ---- 徽章默认值（与 RpIcons 一致）----
    /** 徽章默认等级（tier）。 */
    private static final int BADGE_DEFAULT_TIER = 2;
    /** 徽章默认图形（hex）。 */
    private static final String BADGE_DEFAULT_ICON = "hex";

    private PlayerNametagRenderer() {}

    /** 世界空间渲染：RenderLevelStageEvent.AFTER_ENTITIES 阶段为每个其他玩家绘制头顶悬浮标签。 */
    public static void renderWorld(PoseStack poseStack, Camera cam, float partialTick, MultiBufferSource buffer) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.font == null || cam == null) {
            return;
        }
        if (!ClientCharacterState.nametagEnabled()) {
            return; // 服务端配置：头顶悬浮标签总开关关闭
        }
        // 仅旁观者模式（观察者视角）渲染头顶标记：非旁观者（在场/普通玩家）不渲染，避免信息暴露
        if (mc.gameMode == null || mc.gameMode.getPlayerMode() != net.minecraft.world.level.GameType.SPECTATOR) {
            return;
        }
        Font font = mc.font;
        Vec3 camPos = cam.getPosition();
        Quaternionf camRot = mc.getEntityRenderDispatcher().cameraOrientation();
        double tagOffset = ClientCharacterState.nametagOffset();
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
            double tx = Mth.lerp(partialTick, other.xo, other.getX());
            double ty = Mth.lerp(partialTick, other.yo, other.getY()) + other.getBbHeight() + tagOffset;
            double tz = Mth.lerp(partialTick, other.zo, other.getZ());
            poseStack.pushPose();
            poseStack.translate(tx - camPos.x, ty - camPos.y, tz - camPos.z);
            poseStack.mulPose(camRot);
            poseStack.scale(TAG_SCALE, TAG_SCALE, -TAG_SCALE);
            Matrix4f matrix = poseStack.last().pose();
            drawTag(font, matrix, buffer, tag, other.getHealth(), other.getMaxHealth());
            poseStack.popPose();
        }
    }

    private static void drawTag(
            Font font,
            Matrix4f matrix,
            MultiBufferSource buffer,
            ClientCharacterState.PlayerTag tag,
            float hp,
            float maxHp) {
        int badgeR = ClientCharacterState.nametagBadgeSize();
        int factionColor = factionColor(tag.factionId());
        Component line1 =
                Component.literal(professionDisplay(tag.professionId())).withStyle(s -> s.withColor(factionColor));
        // 行1：阵营图标（左）+ 职业名（右）；行2：血量 / 血量上限
        drawMainLine(font, matrix, buffer, line1, LINE_MAIN_Y, badgeR, tag.factionId(), factionColor);
        drawCentered(font, matrix, buffer, healthComponent(hp, maxHp), LINE_HP_Y);
    }

    /** 行1：阵营图标在左、职业名在右，整行水平居中（badgeSize=0 时仅职业名居中）。 */
    private static void drawMainLine(
            Font font,
            Matrix4f matrix,
            MultiBufferSource buffer,
            Component text,
            float y,
            int badgeR,
            String factionId,
            int factionColor) {
        if (badgeR <= 0) {
            drawCentered(font, matrix, buffer, text, y);
            return;
        }
        float textW = font.width(text);
        float totalW = badgeR * 2 + ICON_TEXT_GAP + textW;
        float left = -totalW / 2.0F;
        float cy = y + 4.5F; // 文字行垂直中心（行高 9）
        // 整行半透明底衬（覆盖图标 + 文字），与其余文字行底衬视觉一致
        rectFill(
                matrix,
                buffer,
                Math.round(left) - 2,
                Math.round(cy) - badgeR - 1,
                Math.round(left + totalW) + 2,
                Math.round(cy) + badgeR + 1,
                COLOR_LINE_BG);
        // 阵营图标（左）
        drawBadge(matrix, buffer, Math.round(left) + badgeR, Math.round(cy), badgeR, factionId);
        // 职业名（右）
        font.drawInBatch(
                text,
                left + badgeR * 2 + ICON_TEXT_GAP,
                y,
                factionColor,
                false,
                matrix,
                buffer,
                net.minecraft.client.gui.Font.DisplayMode.NORMAL,
                0,
                LightTexture.FULL_BRIGHT);
    }

    /** 血量行文字：❤ 当前 / 上限（心形红、数字白、分隔灰）。 */
    private static Component healthComponent(float hp, float maxHp) {
        int cur = Math.max(0, Math.round(hp));
        int max = Math.max(0, Math.round(maxHp));
        return Component.literal("\u2764 ")
                .withStyle(s -> s.withColor(COLOR_HEART))
                .append(Component.literal(String.valueOf(cur)).withStyle(s -> s.withColor(COLOR_NAME)))
                .append(Component.literal(" / ").withStyle(s -> s.withColor(COLOR_HP_DIVIDER)))
                .append(Component.literal(String.valueOf(max)).withStyle(s -> s.withColor(COLOR_NAME)));
    }

    /** 徽章（世界空间）：环(等级色) + 盘(深色) + 图形(img 图片或矢量扫描线) + 右下角等级刻度。 */
    private static void drawBadge(Matrix4f matrix, MultiBufferSource buffer, int cx, int cy, int r, String factionId) {
        com.google.gson.JsonObject faction = factionJson(factionId);
        int tier = BADGE_DEFAULT_TIER;
        String icon = BADGE_DEFAULT_ICON;
        if (faction != null) {
            if (faction.has("tier") && faction.get("tier").isJsonPrimitive()) {
                tier = faction.get("tier").getAsInt();
            }
            icon = faction.has("icon") && !faction.get("icon").isJsonNull()
                    ? faction.get("icon").getAsString()
                    : BADGE_DEFAULT_ICON;
        }
        if (icon == null || icon.isBlank()) {
            icon = BADGE_DEFAULT_ICON;
        }
        int ring = RpTheme.tierColor(tier);
        // 环 + 盘（先画 r 环色，再画 r-1 盘色挖空，同 RpIcons.ring）
        circleFill(matrix, buffer, cx, cy, r, ring);
        circleFill(matrix, buffer, cx, cy, r - 1, COLOR_BADGE_DISC);
        // 中央图形
        if (icon.startsWith("img:")) {
            drawImageBadge(matrix, buffer, cx, cy, r - 1, icon.substring(4), ring);
        } else {
            fillIconPolygon(matrix, buffer, cx, cy, r - 1, icon, ring, COLOR_BADGE_PUNCH);
        }
        // 右下角等级刻度
        int n = Math.max(2, r / 4);
        rectFill(matrix, buffer, cx + r - n - 1, cy + r - n - 1, cx + r, cy + r, ring);
    }

    /** 图片徽章：底色盘 + 方形纹理（img:<名>，服务器素材库下发，无内嵌回退）。 */
    private static void drawImageBadge(
            Matrix4f matrix, MultiBufferSource buffer, int cx, int cy, int r, String fileName, int color) {
        try {
            if (fileName == null || !fileName.matches("[A-Za-z0-9_-]+")) {
                return;
            }
            ResourceLocation loc = ClientAssetCache.serverIcon(fileName);
            if (loc == null) {
                return;
            }
            int tw = ClientAssetCache.iconSize(fileName);
            Minecraft.getInstance().getTextureManager().getTexture(loc);
            circleFill(matrix, buffer, cx, cy, r, color);
            int s = r * 2;
            if (s < 4) {
                return;
            }
            float x0 = cx - s / 2.0F;
            float y0 = cy - s / 2.0F;
            VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucent(loc));
            quad(vc, matrix, x0, y0, x0 + s, y0 + s, 0.0F, 1.0F, COLOR_NAME, LightTexture.FULL_BRIGHT);
        } catch (Exception ignored) {
            // 图片徽章缺失回退（仅画底盘）
        }
    }

    /** 实心圆盘（世界空间扫描线填充，同 RpIcons.circle 视觉）。 */
    private static void circleFill(Matrix4f matrix, MultiBufferSource buffer, int cx, int cy, int r, int color) {
        if (r < 1) {
            return;
        }
        VertexConsumer vc = buffer.getBuffer(RenderType.gui());
        for (int dy = -r; dy <= r; dy++) {
            int h = (int) Math.floor(Math.sqrt(r * r - dy * dy));
            rectVertex(vc, matrix, cx - h, cy + dy, cx + h + 1, cy + dy + 1, color);
        }
    }

    /** 图标多边形填充（16 单位盒 → 徽章内，扫描线 even-odd，同 RpIcons.poly 视觉）。 */
    private static void fillIconPolygon(
            Matrix4f matrix, MultiBufferSource buffer, int cx, int cy, int r, String icon, int color, int punch) {
        int[][] pts = RpIcons.iconPolygon(icon);
        int s = r * 2 - 2;
        if (s < 4) {
            return;
        }
        // 16 单位盒映射到以 cx,cy 为中心、边长 s 的方盒
        int[][] mapped = new int[pts.length][2];
        for (int i = 0; i < pts.length; i++) {
            mapped[i][0] = cx - s / 2 + pts[i][0] * s / 16;
            mapped[i][1] = cy - s / 2 + pts[i][1] * s / 16;
        }
        scanlinePoly(matrix, buffer, mapped, color);
        switch (icon) {
            case "eye" -> circleFill(matrix, buffer, cx, cy, Math.max(1, s / 16), color);
            case "helm" -> rectFill(
                    matrix,
                    buffer,
                    cx - s / 2 + 3 * s / 16,
                    cy - s / 2 + 7 * s / 16,
                    cx - s / 2 + 13 * s / 16 + 1,
                    cy - s / 2 + 8 * s / 16 + 1,
                    punch);
            case "gear" -> circleFill(matrix, buffer, cx, cy, Math.max(1, s / 8), punch);
            default -> {}
        }
    }

    /** 偶数奇偶扫描线多边形填充（同 RpIcons.poly）。 */
    private static void scanlinePoly(Matrix4f matrix, MultiBufferSource buffer, int[][] p, int color) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int[] pt : p) {
            minY = Math.min(minY, pt[1]);
            maxY = Math.max(maxY, pt[1]);
        }
        int n = p.length;
        VertexConsumer vc = buffer.getBuffer(RenderType.gui());
        for (int y = minY; y <= maxY; y++) {
            double sy = y + 0.5;
            java.util.List<Double> xs = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                int[] a = p[i];
                int[] b = p[(i + 1) % n];
                if ((a[1] < sy && b[1] >= sy) || (b[1] < sy && a[1] >= sy)) {
                    xs.add(a[0] + (sy - a[1]) * (double) (b[0] - a[0]) / (b[1] - a[1]));
                }
            }
            java.util.Collections.sort(xs);
            for (int i = 0; i + 1 < xs.size(); i += 2) {
                rectVertex(
                        vc, matrix, (int) Math.ceil(xs.get(i)), y, (int) Math.floor(xs.get(i + 1)) + 1, y + 1, color);
            }
        }
    }

    /** 填充矩形（世界空间，POSITION_COLOR）。 */
    private static void rectFill(Matrix4f matrix, MultiBufferSource buffer, int x1, int y1, int x2, int y2, int color) {
        VertexConsumer vc = buffer.getBuffer(RenderType.gui());
        rectVertex(vc, matrix, x1, y1, x2, y2, color);
    }

    private static void rectVertex(VertexConsumer vc, Matrix4f matrix, int x1, int y1, int x2, int y2, int color) {
        float r = ((color >>> 16) & 0xFF) / 255.0F;
        float g = ((color >>> 8) & 0xFF) / 255.0F;
        float b = (color & 0xFF) / 255.0F;
        float a = ((color >>> 24) & 0xFF) / 255.0F;
        vc.vertex(matrix, x1, y1, 0.0F).color(r, g, b, a).endVertex();
        vc.vertex(matrix, x2, y1, 0.0F).color(r, g, b, a).endVertex();
        vc.vertex(matrix, x2, y2, 0.0F).color(r, g, b, a).endVertex();
        vc.vertex(matrix, x1, y2, 0.0F).color(r, g, b, a).endVertex();
    }

    /** 纹理 quad（图片徽章）。 */
    private static void quad(
            VertexConsumer vc,
            Matrix4f matrix,
            float x1,
            float y1,
            float x2,
            float y2,
            float minU,
            float maxU,
            int color,
            int light) {
        float r = ((color >>> 16) & 0xFF) / 255.0F;
        float g = ((color >>> 8) & 0xFF) / 255.0F;
        float b = (color & 0xFF) / 255.0F;
        float a = ((color >>> 24) & 0xFF) / 255.0F;
        vc.vertex(matrix, x1, y1, 0.0F)
                .color(r, g, b, a)
                .uv(minU, 0.0F)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(0.0F, 0.0F, 1.0F)
                .endVertex();
        vc.vertex(matrix, x2, y1, 0.0F)
                .color(r, g, b, a)
                .uv(maxU, 0.0F)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(0.0F, 0.0F, 1.0F)
                .endVertex();
        vc.vertex(matrix, x2, y2, 0.0F)
                .color(r, g, b, a)
                .uv(maxU, 1.0F)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(0.0F, 0.0F, 1.0F)
                .endVertex();
        vc.vertex(matrix, x1, y2, 0.0F)
                .color(r, g, b, a)
                .uv(minU, 1.0F)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(0.0F, 0.0F, 1.0F)
                .endVertex();
    }

    private static void drawCentered(Font font, Matrix4f matrix, MultiBufferSource buffer, Component text, float y) {
        float w = font.width(text);
        font.drawInBatch(
                text,
                -w / 2.0F,
                y,
                COLOR_NAME,
                false,
                matrix,
                buffer,
                net.minecraft.client.gui.Font.DisplayMode.NORMAL,
                COLOR_LINE_BG,
                LightTexture.FULL_BRIGHT);
    }

    private static boolean isInView(Minecraft mc, Entity e) {
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        double dist = mc.gameRenderer.getRenderDistance();
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
            return COLOR_FACTION_DEFAULT;
        }
        String c =
                f.has("color") && !f.get("color").isJsonNull() ? f.get("color").getAsString() : "";
        try {
            return 0xFFFFFF & Integer.parseInt(c.replace("#", ""), 16);
        } catch (Exception ignored) {
            return COLOR_FACTION_DEFAULT;
        }
    }

    private static String professionDisplay(String professionId) {
        if (professionId == null || professionId.isBlank()) {
            return "?";
        }
        return ClientCharacterState.professionName(professionId);
    }
}
