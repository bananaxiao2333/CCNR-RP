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
        // 入服自动打开角色面板（设置：openPanelOnJoin，且无存活角色时）
        if (ClientCharacterState.consumeAutoOpenPanel()
                && Minecraft.getInstance().screen == null
                && Minecraft.getInstance().player != null
                && ClientCharacterState.list().stream().noneMatch(c -> "alive"
                        .equals(c.has("status") ? c.get("status").getAsString() : ""))) {
            Minecraft.getInstance().setScreen(new CharacterManagementScreen());
        }
        while (ClientSetup.OPEN_CHARACTERS.consumeClick()) {
            Minecraft.getInstance().setScreen(new CharacterManagementScreen());
        }
    }

    /** 事件横幅滚轮：鼠标悬停横幅区域时横向滚动（并拦截向下传递）。 */
    @SubscribeEvent
    public static void onMouseScroll(net.minecraftforge.client.event.InputEvent.MouseScrollingEvent event) {
        if (EventBanner.active() && EventBanner.inArea(cursorX(), cursorY())) {
            EventBanner.scroll(event.getScrollDelta());
            event.setCanceled(true);
        }
    }

    private static int cursorX() {
        return (int) Minecraft.getInstance().mouseHandler.xpos();
    }

    private static int cursorY() {
        return (int) Minecraft.getInstance().mouseHandler.ypos();
    }

    /** 背包等任意界面打开时，事件横幅依然绘制在最上层。 */
    @SubscribeEvent
    public static void onScreenRender(net.minecraftforge.client.event.ScreenEvent.Render.Post event) {
        EventBanner.render(event.getGuiGraphics(), event.getScreen().width, event.getScreen().height);
    }
}
