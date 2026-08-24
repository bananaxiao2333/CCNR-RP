/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 右侧 3D 模型预览：真实玩家模型 + 战术重装（下界合金盔甲+剑盾），跟随鼠标旋转。
 * 皮肤优先使用上传的定制皮肤（SkinCache），未上传时回退默认 Steve。
 * 渲染复用原版 InventoryScreen.renderEntityInInventoryFollowsMouse（GUI 摄像机）。
 */
public final class CharacterPreview {

    private static String cacheKey = "";
    private static AbstractClientPlayer cached;

    private CharacterPreview() {}

    public static void invalidate() {
        cacheKey = "";
        cached = null;
    }

    /** 在 (cx,cy) 中心渲染模型；调用方负责 enableScissor 裁剪预览框。 */
    public static void render(
            GuiGraphics g, int cx, int cy, int scale, float mouseX, float mouseY, JsonObject character) {
        if (character == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        AbstractClientPlayer p = entity(level, character);
        if (p == null) {
            return;
        }
        // 旋转角钳制：yaw ≤45°，pitch ≤33°——限制鼠标拖拽幅度，避免模型前倾时头“穿出”预览框。
        float dx = Math.max(-40f, Math.min(40f, cx - mouseX));
        float dy = Math.max(-26f, Math.min(26f, cy - mouseY));
        InventoryScreen.renderEntityInInventoryFollowsMouse(g, cx, cy, scale, dx, dy, p);
    }

    private static AbstractClientPlayer entity(ClientLevel level, JsonObject c) {
        String id = str(c, "id");
        if (cached != null && cacheKey.equals(id)) {
            return cached;
        }
        cacheKey = id;
        cached = null;
        String name = str(c, "name");
        if (name.isBlank()) {
            name = "AGENT";
        }
        GameProfile gp =
                new GameProfile(UUID.nameUUIDFromBytes(("ccnr-rp:" + id).getBytes(StandardCharsets.UTF_8)), name);
        PreviewPlayer p = new PreviewPlayer(level, gp, SkinCache.textureOrNull(id));
        equipTactical(p);
        cached = p;
        return p;
    }

    /** 战术重装（预览展示用）：头盔/胸甲/护腿/靴子 + 剑盾。 */
    private static void equipTactical(AbstractClientPlayer p) {
        Inventory inv = p.getInventory();
        inv.setItem(36, new ItemStack(Items.NETHERITE_HELMET));
        inv.setItem(37, new ItemStack(Items.NETHERITE_CHESTPLATE));
        inv.setItem(38, new ItemStack(Items.NETHERITE_LEGGINGS));
        inv.setItem(39, new ItemStack(Items.NETHERITE_BOOTS));
        inv.setItem(0, new ItemStack(Items.NETHERITE_SWORD));
        inv.setItem(40, new ItemStack(Items.SHIELD));
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    /** 预览用假玩家：皮肤指向注入的定制纹理。 */
    static final class PreviewPlayer extends AbstractClientPlayer {
        private final RenderInfo info;

        PreviewPlayer(ClientLevel level, GameProfile gp, ResourceLocation skin) {
            super(level, gp);
            this.info = new RenderInfo(gp, skin);
        }

        @Override
        protected PlayerInfo getPlayerInfo() {
            return info;
        }
    }

    /** 覆写皮肤定位，使用 SkinCache 上传纹理（无则原版默认）。 */
    static final class RenderInfo extends PlayerInfo {
        private final ResourceLocation skin;

        RenderInfo(GameProfile gp, ResourceLocation skin) {
            super(gp, false);
            this.skin = skin;
        }

        @Override
        public ResourceLocation getSkinLocation() {
            return skin != null ? skin : super.getSkinLocation();
        }
    }
}
