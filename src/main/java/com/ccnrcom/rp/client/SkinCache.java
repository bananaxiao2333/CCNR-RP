/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.mojang.blaze3d.platform.NativeImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;

/** 客户端皮肤缓存：内存 NativeImage + 本地文件（config/ccnr_rp/skins-cache/）。 */
public final class SkinCache {
    private static final Map<String, ResourceLocation> TEXTURES = new ConcurrentHashMap<>();

    private SkinCache() {}

    public static void store(String charId, byte[] data, String hash) {
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("ccnr_rp").resolve("skins-cache");
            Files.createDirectories(dir);
            Files.write(dir.resolve(charId + ".png.hash"), hash.getBytes(StandardCharsets.UTF_8));
            Files.write(dir.resolve(charId + ".png"), data);
            NativeImage img = NativeImage.read(data);
            ResourceLocation loc = new ResourceLocation("ccnr_rp", "skins/" + charId);
            Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(img));
            TEXTURES.put(charId, loc);
        } catch (Exception ignored) {
            // 缓存失败不致命（渲染时回退默认）
        }
    }

    /** 若已缓存则返回纹理（供界面头像渲染）；未缓存返回 null。 */
    /** 若已缓存则返回纹理（供界面头像渲染）；未缓存返回 null。 */
    public static ResourceLocation textureOrNull(String charId) {
        return TEXTURES.get(charId);
    }

    public static ResourceLocation texture(String charId) {
        return TEXTURES.get(charId);
    }
}
