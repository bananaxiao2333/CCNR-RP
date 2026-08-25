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
        ClientAssetCache.tick(); // 素材下载请求超时看门狗（防卡死）
        RecruitOverlayHud.prune(); // 过期邀请清理（防残留弹窗死锁）
        tickObserverActionbar(); // 观察者常驻提示（观察中，按键部署）
        // 邀请菜单不再强制弹出（避免影响战斗）：热键（默认 R）开关；右侧常驻悬浮 HUD 始终显示。
        while (ClientSetup.OPEN_RECRUIT.consumeClick()) {
            Minecraft mc = Minecraft.getInstance();
            net.minecraft.client.gui.screens.Screen s = mc.screen;
            if (s instanceof RecruitPopupScreen) {
                s.onClose();
            } else if (s == null && mc.player != null && !RecruitOverlayHud.isEmpty()) {
                mc.setScreen(new RecruitPopupScreen());
            }
        }
        // 入服自动打开角色面板（服务端设置 openPanelOnJoin；仅观察者可开）。
        // 待定标记只在真正打开面板时消费：进服瞬间玩家实体/界面未就绪时不丢标记，下一 tick 重试；
        // 招募弹窗（CCNR-RP 自己的界面）打开期间等待其关闭后再开；玩家打开其他界面或状态变为
        // 非观察者（在场/阴间/征召在场）时取消，绝不强抢界面。
        if (ClientCharacterState.isAutoOpenPending()) {
            net.minecraft.client.gui.screens.Screen s = Minecraft.getInstance().screen;
            if (s != null && !(s instanceof RecruitPopupScreen) && !(s instanceof CharacterManagementScreen)) {
                ClientCharacterState.cancelAutoOpenPanel();
            } else if (s instanceof CharacterManagementScreen) {
                ClientCharacterState.consumeAutoOpenPanel(); // 已通过按键打开，标记完成
            } else if (s == null && Minecraft.getInstance().player != null && !ClientCharacterState.panelLocked()) {
                ClientCharacterState.consumeAutoOpenPanel();
                openCharacterPanel(false); // 与按键同一入口
            }
        }
        while (ClientSetup.OPEN_CHARACTERS.consumeClick()) {
            openCharacterPanel(false);
        }
    }

    /** K 面板统一入口（按键与同步完成后自动打开共用）：非观察者仅提示，不打开。 */
    private static void openCharacterPanel(boolean silent) {
        if (ClientCharacterState.panelLocked()) {
            if (!silent && Minecraft.getInstance().player != null) {
                Minecraft.getInstance()
                        .player
                        .displayClientMessage(
                                net.minecraft.network.chat.Component.translatable("ccnr_rp.gui.character.panel_locked"),
                                true);
            }
            return;
        }
        Minecraft.getInstance().setScreen(new CharacterManagementScreen());
    }

    /** 观察者常驻 actionbar 提示（约每 1.5s 刷新）：观察中，按绑定键进行部署。 */
    private static int observerHintCounter = 0;

    private static void tickObserverActionbar() {
        if (++observerHintCounter < 30) {
            return;
        }
        observerHintCounter = 0;
        var mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        // 仅当玩家处于「观察（OBSERVING）」状态且非征召在场时才显示“观察中，按 K 部署”
        if (ClientCharacterState.userStatus() != com.ccnrcom.rp.status.CharacterStatus.OBSERVING
                || ClientCharacterState.conscript() != null) {
            return;
        }
        String keyName = ClientSetup.OPEN_CHARACTERS.getTranslatedKeyMessage().getString();
        mc.player.displayClientMessage(
                net.minecraft.network.chat.Component.translatable("ccnr_rp.hud.observer_actionbar", keyName), true);
    }

    /** 客户端登出：清理过期的招募/征召邀请（防止重进服务器后残留已失效状态）。 */
    @SubscribeEvent
    public static void onClientLogout(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        RecruitOverlayHud.clear();
        ClientCharacterState.resetForJoin();
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

    /** 状态栏与事件横幅仅在「背包」（InventoryScreen）打开时绘制最上层；其余界面与正常游戏内不显示。 */
    @SubscribeEvent
    public static void onScreenRender(net.minecraftforge.client.event.ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen)) {
            return;
        }
        StatusHud.render(event.getGuiGraphics(), event.getScreen().width, event.getScreen().height);
        EventBanner.render(event.getGuiGraphics(), event.getScreen().width, event.getScreen().height);
        // 背包右上角：已同意玩家列表（接受后不可取消）
        RecruitOverlayHud.renderAcceptedList(event.getGuiGraphics(), event.getScreen().width, event.getScreen().height);
    }
}
