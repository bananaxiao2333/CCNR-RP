/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.network;

import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/** ccnr_rp:main 通道与消息注册（P3 起）。协议版本不匹配时两端自动降级禁用联动。 */
public final class RpChannels {
    public static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("ccnr_rp", "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private static int id = 0;

    private RpChannels() {}

    public static void register() {
        CHANNEL.messageBuilder(RpPackets.RequestCharacterListC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.RequestCharacterListC2S::encode)
                .decoder(RpPackets.RequestCharacterListC2S::decode)
                .consumerNetworkThread(RpPackets.RequestCharacterListC2S::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.CharacterCreateC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.CharacterCreateC2S::encode)
                .decoder(RpPackets.CharacterCreateC2S::new)
                .consumerNetworkThread(RpPackets.CharacterCreateC2S::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.CharacterSelectC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.CharacterSelectC2S::encode)
                .decoder(RpPackets.CharacterSelectC2S::new)
                .consumerNetworkThread(RpPackets.CharacterSelectC2S::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.CharacterDeleteC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.CharacterDeleteC2S::encode)
                .decoder(RpPackets.CharacterDeleteC2S::new)
                .consumerNetworkThread(RpPackets.CharacterDeleteC2S::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.CharacterObserveC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.CharacterObserveC2S::encode)
                .decoder(RpPackets.CharacterObserveC2S::new)
                .consumerNetworkThread(RpPackets.CharacterObserveC2S::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.CharacterActivateC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.CharacterActivateC2S::encode)
                .decoder(RpPackets.CharacterActivateC2S::new)
                .consumerNetworkThread(RpPackets.CharacterActivateC2S::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.SkinUploadPartC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.SkinUploadPartC2S::encode)
                .decoder(RpPackets.SkinUploadPartC2S::new)
                .consumerNetworkThread(RpPackets.SkinUploadPartC2S::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.SkinUploadCommitC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.SkinUploadCommitC2S::encode)
                .decoder(RpPackets.SkinUploadCommitC2S::new)
                .consumerNetworkThread(RpPackets.SkinUploadCommitC2S::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.CharacterListS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RpPackets.CharacterListS2C::encode)
                .decoder(RpPackets.CharacterListS2C::new)
                .consumerNetworkThread(RpPackets.CharacterListS2C::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.CharacterUpdateS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RpPackets.CharacterUpdateS2C::encode)
                .decoder(RpPackets.CharacterUpdateS2C::new)
                .consumerNetworkThread(RpPackets.CharacterUpdateS2C::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.CharacterRemoveS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RpPackets.CharacterRemoveS2C::encode)
                .decoder(RpPackets.CharacterRemoveS2C::new)
                .consumerNetworkThread(RpPackets.CharacterRemoveS2C::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.SkinSyncS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RpPackets.SkinSyncS2C::encode)
                .decoder(RpPackets.SkinSyncS2C::new)
                .consumerNetworkThread(RpPackets.SkinSyncS2C::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.CinematicS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RpPackets.CinematicS2C::encode)
                .decoder(RpPackets.CinematicS2C::new)
                .consumerNetworkThread(RpPackets.CinematicS2C::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.AnimationPlayS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RpPackets.AnimationPlayS2C::encode)
                .decoder(RpPackets.AnimationPlayS2C::new)
                .consumerNetworkThread(RpPackets.AnimationPlayS2C::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.XpUpdateS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RpPackets.XpUpdateS2C::encode)
                .decoder(RpPackets.XpUpdateS2C::new)
                .consumerNetworkThread(RpPackets.XpUpdateS2C::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.RecruitOfferS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RpPackets.RecruitOfferS2C::encode)
                .decoder(RpPackets.RecruitOfferS2C::new)
                .consumerNetworkThread(RpPackets.RecruitOfferS2C::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.RecruitAnswerC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.RecruitAnswerC2S::encode)
                .decoder(RpPackets.RecruitAnswerC2S::new)
                .consumerNetworkThread(RpPackets.RecruitAnswerC2S::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.CharacterDeployC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.CharacterDeployC2S::encode)
                .decoder(RpPackets.CharacterDeployC2S::new)
                .consumerNetworkThread(RpPackets.CharacterDeployC2S::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.CharacterRetireC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.CharacterRetireC2S::encode)
                .decoder(RpPackets.CharacterRetireC2S::new)
                .consumerNetworkThread(RpPackets.CharacterRetireC2S::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.ManagerRequestC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.ManagerRequestC2S::encode)
                .decoder(RpPackets.ManagerRequestC2S::new)
                .consumerNetworkThread(RpPackets.ManagerRequestC2S::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.ManagerSetC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.ManagerSetC2S::encode)
                .decoder(RpPackets.ManagerSetC2S::new)
                .consumerNetworkThread(RpPackets.ManagerSetC2S::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.ManagerStateS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RpPackets.ManagerStateS2C::encode)
                .decoder(RpPackets.ManagerStateS2C::new)
                .consumerNetworkThread(RpPackets.ManagerStateS2C::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.ManagerCrudC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.ManagerCrudC2S::encode)
                .decoder(RpPackets.ManagerCrudC2S::new)
                .consumerNetworkThread(RpPackets.ManagerCrudC2S::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.EventStateS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RpPackets.EventStateS2C::encode)
                .decoder(RpPackets.EventStateS2C::new)
                .consumerNetworkThread(RpPackets.EventStateS2C::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.ErrorS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RpPackets.ErrorS2C::encode)
                .decoder(RpPackets.ErrorS2C::new)
                .consumerNetworkThread(RpPackets.ErrorS2C::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.ManagerImpactC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.ManagerImpactC2S::encode)
                .decoder(RpPackets.ManagerImpactC2S::new)
                .consumerNetworkThread(RpPackets.ManagerImpactC2S::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.ManagerImpactS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RpPackets.ManagerImpactS2C::encode)
                .decoder(RpPackets.ManagerImpactS2C::new)
                .consumerNetworkThread(RpPackets.ManagerImpactS2C::handle)
                .add();
    }

    public static void sendTo(ServerPlayer player, Object msg) {
        if (CHANNEL.isRemotePresent(player.connection.connection)) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
        }
    }

    /** 客户端发往服务端。 */
    public static void sendToServer(Object msg) {
        CHANNEL.sendToServer(msg);
    }

    public static void sendToAll(Object msg) {
        CHANNEL.send(PacketDistributor.ALL.with(() -> null), msg);
    }

    public static boolean hasChannel(Connection connection) {
        return CHANNEL.isRemotePresent(connection);
    }
}
