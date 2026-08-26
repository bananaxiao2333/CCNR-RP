/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.ccnrcom.rp.network.RpPackets;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** 客户端 S2C 包处理（仅客户端侧调用，网络线程 enqueue 到主线程后执行）。 */
public final class ClientPacketHandlers {

    private ClientPacketHandlers() {}

    public static void onCharacterList(String payload) {
        ClientCharacterState.setList(payload);
        CharacterManagementScreen.refreshIfOpen();
        RpAdminScreen.refreshIfOpen();
    }

    public static void onRecruitOffer(RpPackets.RecruitOfferS2C msg) {
        RecruitOverlayHud.add(
                msg.offerId, msg.charId, msg.charName, msg.professionId, msg.initialTicks, msg.waveId, msg.kind);
        // 不再强制弹邀请菜单（避免影响战斗）；仅当菜单已打开时刷新。
        RecruitPopupScreen.refreshIfOpen();
    }

    public static void onAnimation(String payload) {
        CharacterManagementScreen.closeIfOpen();
        ClientAnimationPlayer.play(payload);
    }

    /** 用户经验/等级更新（经验随用户走）。 */
    public static void onUserXp(long xp, int level) {
        ClientCharacterState.setUserXp(xp, level);
        CharacterManagementScreen.refreshIfOpen();
    }

    /** 经验列表状态推送（HUD 常驻显示待结算项目 + 当前总经验）。 */
    public static void onXpList(String payload) {
        XpHudOverlay.setList(payload);
    }

    /** 经验结算动画（HUD 逐项吸入，纯视觉）。 */
    public static void onXpSettleAnim(String payload) {
        XpHudOverlay.playSettle(payload);
    }

    /** 打开关系测定图（全屏，管理命令/面板按钮触发）。 */
    public static void onFactionGraphOpen() {
        Minecraft.getInstance().setScreen(new FactionGraphScreen());
    }

    /** 经验规则集（管理面板「经验规则」页）。 */
    public static void onRulesState(String payload) {
        ClientCharacterState.setXpRules(payload);
        RpAdminScreen.refreshIfOpen();
    }

    /** 征召兵身份状态：非空=在场（HUD 显示征召编制），空串=清除（阵亡/结束）。 */
    public static void onConscriptState(String payload) {
        ClientCharacterState.setConscript(payload);
    }

    /** 部署完成通知：显示常驻「已部署」横幅，并清空侧面/背包邀请面板、关闭邀请弹窗（已部署不再保留待处理邀请）。 */
    public static void onDeployNotice(String professionName, String factionId) {
        DeployNoticeBanner.show(professionName, factionId);
        RecruitOverlayHud.clear();
        net.minecraft.client.gui.screens.Screen s = Minecraft.getInstance().screen;
        if (s instanceof RecruitPopupScreen) {
            s.onClose();
        }
    }

    public static void onEventState(String payload) {
        ClientCharacterState.setActiveEvents(payload);
    }

    public static void onManagerState(String payload) {
        ClientCharacterState.setManager(payload);
    }

    /** 全玩家头顶标签数据更新。 */
    public static void onPlayerTags(String payload) {
        ClientCharacterState.setPlayerTags(payload);
    }

    public static void onMusicList(String payload) {
        ClientCharacterState.setMusicList(payload);
        RpAdminScreen.refreshIfOpen();
    }

    /** 素材清单（服务器中央下发）：对比本地缓存，缺失/变更自动请求下载。 */
    public static void onAssetManifest(String payload) {
        ClientAssetCache.applyManifest(payload);
    }

    /** 素材分片到达：累积写盘，完成后继续下一个待下载素材。 */
    public static void onAssetPart(RpPackets.AssetPartS2C msg) {
        ClientAssetCache.onPart(msg.name, msg.index, msg.total, msg.data);
    }

    public static void onManagerImpact(String payload) {
        RpAdminScreen.onImpact(payload);
    }

    public static void onCinematic(String payload) {
        CharacterManagementScreen.closeIfOpen();
        try {
            CinematicController.start(com.ccnrcom.rp.util.JsonUtil.GSON.fromJson(payload, JsonObject.class));
        } catch (Exception ignored) {
            // 数据异常直接跳过电影
        }
    }

    public static void onError(String messageKey, String[] args) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable(messageKey, (Object[]) args), false);
        }
    }
}
