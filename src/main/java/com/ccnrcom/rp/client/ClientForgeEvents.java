/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.ccnrcom.rp.CCNRRPMod;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/** 客户端 Forge 总线事件（Dist.CLIENT）：动画步进、招募列表自动弹出、角色界面按键。 */
@EventBusSubscriber(modid = CCNRRPMod.MODID, value = Dist.CLIENT)
public final class ClientForgeEvents {

    private ClientForgeEvents() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        ClientAnimationPlayer.tick();
        CameraEffect.tick();
        if (!RecruitOverlayHud.isEmpty()
                && Minecraft.getInstance().screen == null
                && Minecraft.getInstance().player != null) {
            Minecraft.getInstance().setScreen(new RecruitPopupScreen());
        }
        while (ClientSetup.OPEN_CHARACTERS.consumeClick()) {
            Minecraft.getInstance().setScreen(new CharacterManagementScreen());
        }
    }
}
