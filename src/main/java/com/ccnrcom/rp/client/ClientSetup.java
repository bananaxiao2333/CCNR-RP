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

    /** 招募邀请菜单开关（交互式弹窗）；右侧常驻悬浮 HUD 不受此热键控制。 */
    public static final KeyMapping OPEN_RECRUIT = new KeyMapping(
            "key.ccnr_rp.recruit_menu",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            "key.categories.ccnr_rp");

    private ClientSetup() {}

    @SubscribeEvent
    public static void registerShaders(net.minecraftforge.client.event.RegisterShadersEvent event) {
        RpRoundRect.registerShaders(event);
    }

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_CHARACTERS);
        event.register(OPEN_RECRUIT);
    }

    @SubscribeEvent
    public static void registerOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("ccnr_rp_fade", (gui, gfx, partial, w, h) -> FadeOverlay.render(gfx, w, h));
        event.registerAboveAll(
                "ccnr_rp_recruit",
                (gui, gfx, partial, w, h) -> com.ccnrcom.rp.client.RecruitOverlayHud.render(gfx, w, h));
        // 注意：状态栏与事件横幅不再注册 HUD 覆盖层——仅在背包（InventoryScreen）打开时绘制，
        // 见 ClientForgeEvents.onScreenRender
        // 结算明细逐行（右下角红/绿）：常驻覆盖层，非背包内也可见
        event.registerAboveAll("ccnr_rp_xp", (gui, gfx, partial, w, h) -> XpHudOverlay.render(gfx, w, h));
        // 刚入服 5 秒：右下角三状态栏（职位/阵营/血量）常驻显示，不管背包是否打开（内部按入服时刻门控）
        event.registerAboveAll(
                "ccnr_rp_status_join", (gui, gfx, partial, w, h) -> StatusHud.renderJoinOverlay(gfx, w, h));
        event.registerAboveAll("ccnr_rp_sync", (gui, gfx, partial, w, h) -> {
            // 素材同步中提示（左上角小标签；同步完成前服务端禁用部署）
            if (ClientAssetCache.isSyncing()) {
                String text = net.minecraft.network.chat.Component.translatable("ccnr_rp.gui.asset.syncing")
                        .getString();
                var font = net.minecraft.client.Minecraft.getInstance().font;
                int x = 8;
                int y = 10;
                // 终端小标签：控件底 + 细灰边（与其它浮层同语言）
                RpRoundRect.outlined(
                        gfx,
                        x,
                        y,
                        x + font.width(text) + 12,
                        y + 16,
                        6f,
                        RpTheme.PANEL_BORDER,
                        RpTheme.SURFACE_CONTROL_HOVER);
                gfx.drawString(font, text, x + 6, y + 4, RpTheme.TEXT_BRIGHT, true);
            }
        });
        event.registerAboveAll("ccnr_rp_cinematic", (gui, gfx, partial, w, h) -> CinematicController.render(gfx, w, h));
        // 部署完成常驻横幅（顶部居中，30s）
        event.registerAboveAll(
                "ccnr_rp_deploy_notice", (gui, gfx, partial, w, h) -> DeployNoticeBanner.render(gfx, w, h));
        // 击杀友好提示（左下角 toast，约 6s；服务端开关控制是否发包）
        event.registerAboveAll(
                "ccnr_rp_kill_friendly", (gui, gfx, partial, w, h) -> KillFriendlyNoticeHud.render(gfx, w, h));
        // 入场无线电（action bar 打字机逐句展示，入场动画播完后播放）
        event.registerAboveAll("ccnr_rp_radio", (gui, gfx, partial, w, h) -> RadioPlayer.render(gfx, w, h));
    }
}
