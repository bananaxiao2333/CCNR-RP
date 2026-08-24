/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.ccnrcom.rp.CCNRRPMod;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端装配（MOD 总线）：按键（默认 K）打开角色管理界面；动画遮罩与镜头步进。
 * 注意：本类只订阅 MOD 总线事件；Forge 总线事件（ClientTick 等）见 {@link ClientForgeEvents}。
 */
@EventBusSubscriber(modid = CCNRRPMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ClientSetup {
    public static final KeyMapping OPEN_CHARACTERS = new KeyMapping(
            "key.ccnr_rp.character_menu",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "key.categories.ccnr_rp");

    private ClientSetup() {}

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_CHARACTERS);
    }

    @SubscribeEvent
    public static void registerOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("ccnr_rp_fade", (gui, gfx, partial, w, h) -> FadeOverlay.render(gfx, w, h));
        event.registerAboveAll(
                "ccnr_rp_recruit",
                (gui, gfx, partial, w, h) -> com.ccnrcom.rp.client.RecruitOverlayHud.render(gfx, w, h));
    }
}
