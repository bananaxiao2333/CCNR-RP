/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RegisterShadersEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * SDF 圆角矩形渲染（移植自 CCNR-Com RoundRectRenderer → E33Chat，MIT）。
 * shader 加载失败时回退为普通矩形。
 */
public final class RpRoundRect {
    private static ShaderInstance shader;

    private RpRoundRect() {}

    public static void registerShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(
                    new ShaderInstance(
                            event.getResourceProvider(),
                            new ResourceLocation("ccnr_rp", "rendertype_round_rect"),
                            DefaultVertexFormat.POSITION_COLOR),
                    s -> shader = s);
        } catch (Exception e) {
            // 回退：方角
        }
    }

    public static void fill(GuiGraphics g, int x1, int y1, int x2, int y2, float radius, int argb) {
        ShaderInstance sh = shader;
        radius = Math.min(radius, Math.min((x2 - x1) / 2f, (y2 - y1) / 2f));
        if (sh == null || radius <= 0) {
            g.fill(x1, y1, x2, y2, argb);
            return;
        }
        g.flush();

        Matrix4f pose = g.pose().last().pose();
        float poseScale = Math.abs(pose.m00());
        Vector4f center = pose.transform(new Vector4f((x1 + x2) / 2f, (y1 + y2) / 2f, 0f, 1f));
        sh.safeGetUniform("u_Rect").set(center.x(), center.y(), (x2 - x1) / 2f * poseScale, (y2 - y1) / 2f * poseScale);
        sh.safeGetUniform("u_Radius").set(radius * poseScale);

        float a = (argb >>> 24) / 255f;
        float r = (argb >> 16 & 0xFF) / 255f;
        float gr = (argb >> 8 & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(() -> sh);
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        bb.vertex(pose, x1, y1, 0).color(r, gr, b, a).endVertex();
        bb.vertex(pose, x1, y2, 0).color(r, gr, b, a).endVertex();
        bb.vertex(pose, x2, y2, 0).color(r, gr, b, a).endVertex();
        bb.vertex(pose, x2, y1, 0).color(r, gr, b, a).endVertex();
        BufferUploader.drawWithShader(bb.end());
        RenderSystem.disableBlend();
    }

    /** 描边（内部两次填充模拟：先描边色外扩再主题色内缩）。 */
    public static void outlined(GuiGraphics g, int x1, int y1, int x2, int y2, float radius, int border, int fill) {
        fill(g, x1, y1, x2, y2, radius, border);
        fill(g, x1 + 1, y1 + 1, x2 - 1, y2 - 1, Math.max(0, radius - 1), fill);
    }
}
