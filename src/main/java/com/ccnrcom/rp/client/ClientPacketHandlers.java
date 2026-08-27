/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.ccnrcom.rp.network.RpPackets;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

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

    /** 击杀友好提示：击杀者击杀友好阵营玩家 → 聊天红字提示（被击杀者 玩家名/阵营/职业，可点击复制）+ 左下角弹出提示。 */
    public static void onKillFriendlyNotice(RpPackets.KillFriendlyNoticeS2C msg) {
        KillFriendlyNoticeHud.show(msg.victimName, msg.victimUuid, msg.victimFactionId, msg.victimProfessionId);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        // 红字提示击杀者：显示杀死了谁（玩家名）+ 阵营/职业（阵营色）；整个消息可点击复制到剪贴板。
        String fac = factionName(msg.victimFactionId);
        String prof = ClientCharacterState.professionName(msg.victimProfessionId);
        int facColor = factionColor(msg.victimFactionId);
        Component plain = Component.translatable("ccnr_rp.hud.kill_friendly.chat")
                .append(Component.literal(" " + msg.victimName))
                .append(Component.literal("（" + fac + " · " + prof + "）"));
        ClickEvent ev = new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, plain.getString());
        Component line = Component.translatable("ccnr_rp.hud.kill_friendly.chat")
                .withStyle(s -> s.withColor(net.minecraft.ChatFormatting.RED).withClickEvent(ev))
                .append(Component.literal(" " + msg.victimName)
                        .withStyle(s ->
                                s.withColor(net.minecraft.ChatFormatting.RED).withClickEvent(ev)))
                .append(Component.literal("（").withStyle(s -> s.withColor(net.minecraft.ChatFormatting.RED)
                        .withClickEvent(ev)))
                .append(Component.literal(fac)
                        .withStyle(s -> s.withColor(facColor).withClickEvent(ev)))
                .append(Component.literal(" · ").withStyle(s -> s.withColor(net.minecraft.ChatFormatting.RED)
                        .withClickEvent(ev)))
                .append(Component.literal(prof)
                        .withStyle(s -> s.withColor(facColor).withClickEvent(ev)))
                .append(Component.literal("）").withStyle(s -> s.withColor(net.minecraft.ChatFormatting.RED)
                        .withClickEvent(ev)));
        mc.player.displayClientMessage(line, false);
    }

    /** 死亡通知（死者定向）：本地聊天显示被谁以什么击杀 + 阵营·职业（阵营色）+ 关系色 + 队友举报提示；可点击复制。 */
    public static void onDeathNotice(RpPackets.DeathNoticeS2C msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        mc.player.displayClientMessage(buildDeathMessage(msg), false);
    }

    /** 组装死亡聊天消息：击杀者阵营·职业（阵营色）+ 名字（关系色白/红/绿）+ 武器；队友击杀追加举报提示。 */
    private static Component buildDeathMessage(RpPackets.DeathNoticeS2C msg) {
        Component plain = assembleDeathMessage(msg, null);
        return assembleDeathMessage(msg, plain.getString());
    }

    /** 组装死亡消息本体；copyText 非空时给每个文本段挂上「点击复制」事件（整条消息都可点复制）。 */
    private static Component assembleDeathMessage(RpPackets.DeathNoticeS2C msg, String copyText) {
        ClickEvent ev = copyText == null ? null : new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, copyText);
        // 阵营 · 职业（都按阵营颜色）：职业名追加在阵营之后
        String fac = factionName(msg.killerFactionId);
        String prof = ClientCharacterState.professionName(msg.killerProfessionId);
        Component factionComp = Component.literal(fac + (prof.isBlank() ? "" : " · " + prof))
                .withStyle(s -> s.withColor(factionColor(msg.killerFactionId)).withClickEvent(ev));
        // 击杀者名字（按关系：友好=绿/敌对=红/中立=白；非玩家击杀无关系=白）
        final int relColor = "friendly".equals(msg.relation)
                ? 0xFF35E07A
                : ("hostile".equals(msg.relation) ? 0xFFFF3B30 : 0xFFFFFFFF);
        Component killerComp = Component.literal(msg.killerName)
                .withStyle(s -> s.withColor(relColor).withClickEvent(ev));
        MutableComponent base;
        if (msg.killerName.isBlank() && !msg.envMsgId.isBlank()) {
            // 环境伤害（摔落/岩浆等）：本地化死亡消息（MC 内置 death.attack.<msgId>；参数=玩家名，可点击复制）
            base = Component.translatable(
                            "death.attack." + msg.envMsgId,
                            Component.literal(mcName()).withStyle(s -> s.withClickEvent(ev)))
                    .withStyle(s -> s.withClickEvent(ev));
        } else if (!msg.weapon.isBlank()) {
            base = Component.translatable(
                            "ccnr_rp.death.killed_by",
                            factionComp,
                            killerComp,
                            Component.literal(msg.weapon).withStyle(s -> s.withClickEvent(ev)))
                    .withStyle(s -> s.withClickEvent(ev));
        } else {
            base = Component.translatable("ccnr_rp.death.killed_by_hands", factionComp, killerComp)
                    .withStyle(s -> s.withClickEvent(ev));
        }
        if ("friendly".equals(msg.relation)) {
            // 队友击杀：追加一行举报提示（红字）
            base = base.append("\n")
                    .append(Component.translatable("ccnr_rp.death.by_ally")
                            .withStyle(s -> s.withColor(net.minecraft.ChatFormatting.RED)
                                    .withClickEvent(ev)));
        }
        return base;
    }

    private static String mcName() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? "" : mc.player.getName().getString();
    }

    private static String factionName(String factionId) {
        if (factionId == null || factionId.isBlank()) {
            return "";
        }
        for (JsonObject f : ClientCharacterState.factions()) {
            if (factionId.equals(
                    f.has("id") && !f.get("id").isJsonNull() ? f.get("id").getAsString() : "")) {
                String n = f.has("name") && !f.get("name").isJsonNull()
                        ? f.get("name").getAsString()
                        : "";
                return n.isBlank() ? factionId : n;
            }
        }
        return factionId;
    }

    private static int factionColor(String factionId) {
        if (factionId == null || factionId.isBlank()) {
            return 0xFFFFFFFF;
        }
        for (JsonObject f : ClientCharacterState.factions()) {
            if (factionId.equals(
                    f.has("id") && !f.get("id").isJsonNull() ? f.get("id").getAsString() : "")) {
                String c = f.has("color") && !f.get("color").isJsonNull()
                        ? f.get("color").getAsString()
                        : "";
                if (c != null && c.startsWith("#") && c.length() == 7) {
                    try {
                        return 0xFF000000 | Integer.parseInt(c.substring(1), 16);
                    } catch (NumberFormatException ignored) {
                        return 0xFFFFFFFF;
                    }
                }
                return 0xFFFFFFFF;
            }
        }
        return 0xFFFFFFFF;
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
