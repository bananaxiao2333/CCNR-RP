/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.ccnrcom.rp.profession.ItemStackCodec;
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
import net.minecraft.world.entity.player.Inventory;

/**
 * 右侧 3D 模型预览：真实玩家模型 + 职位 loadout 装备（角色查看界面展示职位装备），XYZ 锁定正面视角（不跟随鼠标）。
 * 渲染复用原版 InventoryScreen.renderEntityInInventoryFollowsMouse（GUI 摄像机）。注意：该方法第 5/6 个参数是
 * 「鼠标相对模型锚点的像素增量」并按 atan(x/40) 求角，锁定正面视角必须传 0 增量，绝不能传绘制中心 cx/cy。
 */
public final class CharacterPreview {

    private static String cacheKey = "";
    private static AbstractClientPlayer cached;

    private CharacterPreview() {}

    public static void invalidate() {
        cacheKey = "";
        cached = null;
    }

    /** 在 (cx,cy) 中心渲染模型；调用方负责 enableScissor 裁剪预览框。loadout=职业装备定义（可 null）。XYZ 锁定正面视角（不跟随鼠标）。 */
    public static void render(GuiGraphics g, int cx, int cy, int scale, JsonObject character, JsonObject loadout) {
        if (character == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        AbstractClientPlayer p = entity(level, character, loadout);
        if (p == null) {
            return;
        }
        // XYZ 锁定：原版函数把第 5/6 个参数当成「鼠标相对模型锚点的像素增量」，并按 atan(x/40) 求角（*20° 后写入 yaw/pitch）。
        // 要得到 0 旋转的正面视角必须传 0 增量；若错误地传绘制中心 cx/cy（几百像素的大数），模型会被放大成近 90° 的俯仰 + 任意 yaw → 缩成一团/抽搐。
        InventoryScreen.renderEntityInInventoryFollowsMouse(g, cx, cy, scale, 0.0F, 0.0F, p);
    }

    /** 立绘渲染（按 charId/name，供无完整角色 JSON 的场景：招募卡片等）。XYZ 锁定正面视角（不跟随鼠标）。 */
    public static void renderPortrait(
            GuiGraphics g, int cx, int cy, int scale, String charId, String name, JsonObject loadout) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || charId == null || charId.isBlank()) {
            return;
        }
        AbstractClientPlayer p = entity(level, charId, name, loadout);
        if (p == null) {
            return;
        }
        // (cx, cy) 语义 = 立绘视觉中心：模型从脚底向上画（身高 ≈ 2×scale），脚底下移一个 scale 使人物居中于框；
        // XYZ 锁定：传 0 增量 → 角度为 0（正面、不倾斜）；勿传绘制中心（大像素值会被当增量放大成扭曲）。
        InventoryScreen.renderEntityInInventoryFollowsMouse(g, cx, cy + scale, scale, 0.0F, 0.0F, p);
    }

    /** 按「对应玩家本人皮肤」渲染立绘（已加入名单/已同意列表用）：识别 user-<uuid> 取该玩家皮肤，再装配 loadout 装备。
     *  不再清一色用本地玩家皮肤——每个候选显示自己的外观。非玩家身份（如征召兵 UID）回退到通用渲染。 */
    public static void renderPlayerSkin(
            GuiGraphics g, int cx, int cy, int scale, String charId, String name, JsonObject loadout) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || charId == null || charId.isBlank()) {
            return;
        }
        UUID uuid = playerUuid(charId);
        if (uuid == null) {
            renderPortrait(g, cx, cy, scale, charId, name, loadout); // 非玩家身份：回退通用立绘
            return;
        }
        if (name == null || name.isBlank()) {
            name = "AGENT";
        }
        GameProfile gp = null;
        PlayerInfo info = mc.getConnection() != null ? mc.getConnection().getPlayerInfo(uuid) : null;
        if (info != null) {
            gp = info.getProfile(); // 带 textures 属性 → 渲染该玩家皮肤
        }
        if (gp == null) {
            gp = new GameProfile(uuid, name);
        }
        PreviewPlayer p = new PreviewPlayer(level, gp);
        p.setCustomNameVisible(false);
        p.setCustomName(null);
        equipFromLoadout(p, loadout);
        InventoryScreen.renderEntityInInventoryFollowsMouse(g, cx, cy + scale, scale, 0.0F, 0.0F, p);
    }

    /** 从 charId 提取玩家 UUID（v2 唯一身份：user-<uuid>）；非玩家身份返回 null。 */
    private static UUID playerUuid(String charId) {
        if (charId == null || charId.isBlank()) {
            return null;
        }
        String s = charId.startsWith("user-") ? charId.substring(5) : charId;
        try {
            return UUID.fromString(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static AbstractClientPlayer entity(ClientLevel level, JsonObject c, JsonObject loadout) {
        String id = str(c, "id");
        String name = str(c, "name");
        if (name.isBlank()) {
            name = "AGENT";
        }
        return entity(level, id, name, loadout);
    }

    private static AbstractClientPlayer entity(ClientLevel level, String id, String name, JsonObject loadout) {
        if (cached != null && cacheKey.equals(id + "|" + (loadout == null ? "" : loadout.toString()))) {
            return cached;
        }
        cacheKey = id + "|" + (loadout == null ? "" : loadout.toString());
        cached = null;
        Minecraft mc = Minecraft.getInstance();
        GameProfile gp;
        if (mc.player != null) {
            // 模型统一用「玩家自己的皮肤」：取本地玩家带 textures 的 GameProfile，所有预览模型都显示其本人皮肤。
            gp = mc.player.getGameProfile();
        } else {
            if (name == null || name.isBlank()) {
                name = "AGENT";
            }
            gp = new GameProfile(UUID.nameUUIDFromBytes(("ccnr-rp:" + id).getBytes(StandardCharsets.UTF_8)), name);
        }
        PreviewPlayer p = new PreviewPlayer(level, gp);
        // K 面板 3D 预览只显示人物模型 + 职位装备，不显示玩家名名字板
        p.setCustomNameVisible(false);
        p.setCustomName(null);
        equipFromLoadout(p, loadout);
        cached = p;
        return p;
    }

    /** 职位装备（预览展示用）：按 loadout 槽位装配（0-35 背包 / 36-39 护甲 / 40 副手），无 loadout 则空装。 */
    private static void equipFromLoadout(AbstractClientPlayer p, JsonObject loadout) {
        if (loadout == null) {
            return;
        }
        Inventory inv = p.getInventory();
        try {
            if (loadout.has("inventory")) {
                for (com.ccnrcom.rp.profession.ProfessionJson.SlotItem s :
                        com.ccnrcom.rp.profession.ProfessionJson.listFromJson(
                                loadout.getAsJsonArray("inventory"), "inventory", new java.util.ArrayList<>())) {
                    if (s.slot() >= 0 && s.slot() <= 35) {
                        inv.setItem(s.slot(), ItemStackCodec.toStack(s));
                    }
                }
            }
            if (loadout.has("armor")) {
                for (com.ccnrcom.rp.profession.ProfessionJson.SlotItem s :
                        com.ccnrcom.rp.profession.ProfessionJson.listFromJson(
                                loadout.getAsJsonArray("armor"), "armor", new java.util.ArrayList<>())) {
                    if (s.slot() >= 36 && s.slot() <= 39) {
                        inv.setItem(s.slot(), ItemStackCodec.toStack(s));
                    }
                }
            }
            if (loadout.has("offhand")) {
                com.google.gson.JsonElement oh = loadout.get("offhand");
                if (oh.isJsonObject() && oh.getAsJsonObject().size() > 0) {
                    com.ccnrcom.rp.profession.ProfessionJson.SlotItem s =
                            com.ccnrcom.rp.profession.ProfessionJson.slotFromJson(
                                    oh.getAsJsonObject(), "offhand", new java.util.ArrayList<>());
                    if (s != null && s.slot() == 40) {
                        inv.setItem(40, ItemStackCodec.toStack(s));
                    }
                }
            }
        } catch (Exception ignored) {
            // 预览装备失败不致命（回退空装）
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    /** 预览用假玩家：按角色档案 GameProfile 构造，皮肤回退原版渲染。 */
    static final class PreviewPlayer extends AbstractClientPlayer {
        private final PlayerInfo info;

        PreviewPlayer(ClientLevel level, GameProfile gp) {
            super(level, gp);
            this.info = new PlayerInfo(gp, false);
        }

        @Override
        protected PlayerInfo getPlayerInfo() {
            return info;
        }
    }
}
