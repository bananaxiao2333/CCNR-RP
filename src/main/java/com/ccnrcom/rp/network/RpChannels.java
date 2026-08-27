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
        CHANNEL.messageBuilder(RpPackets.MusicUploadPartC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.MusicUploadPartC2S::encode)
                .decoder(RpPackets.MusicUploadPartC2S::new)
                .consumerNetworkThread(RpPackets.MusicUploadPartC2S::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.MusicUploadCommitC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.MusicUploadCommitC2S::encode)
                .decoder(RpPackets.MusicUploadCommitC2S::new)
                .consumerNetworkThread(RpPackets.MusicUploadCommitC2S::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.MusicListS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RpPackets.MusicListS2C::encode)
                .decoder(RpPackets.MusicListS2C::new)
                .consumerNetworkThread(RpPackets.MusicListS2C::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.CharacterListS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RpPackets.CharacterListS2C::encode)
                .decoder(RpPackets.CharacterListS2C::new)
                .consumerNetworkThread(RpPackets.CharacterListS2C::handle)
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
        CHANNEL.messageBuilder(RpPackets.RecruitPickCharacterC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.RecruitPickCharacterC2S::encode)
                .decoder(RpPackets.RecruitPickCharacterC2S::new)
                .consumerNetworkThread(RpPackets.RecruitPickCharacterC2S::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.DeployPositionC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.DeployPositionC2S::encode)
                .decoder(RpPackets.DeployPositionC2S::new)
                .consumerNetworkThread(RpPackets.DeployPositionC2S::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.KillDeployC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.KillDeployC2S::encode)
                .decoder(RpPackets.KillDeployC2S::new)
                .consumerNetworkThread(RpPackets.KillDeployC2S::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.ServerConfigSetC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.ServerConfigSetC2S::encode)
                .decoder(RpPackets.ServerConfigSetC2S::new)
                .consumerNetworkThread(RpPackets.ServerConfigSetC2S::handle)
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
        CHANNEL.messageBuilder(RpPackets.UserAnySupportC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.UserAnySupportC2S::encode)
                .decoder(RpPackets.UserAnySupportC2S::new)
                .consumerNetworkThread(RpPackets.UserAnySupportC2S::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.UserXpS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RpPackets.UserXpS2C::encode)
                .decoder(RpPackets.UserXpS2C::new)
                .consumerNetworkThread(RpPackets.UserXpS2C::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.ConscriptStateS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RpPackets.ConscriptStateS2C::encode)
                .decoder(RpPackets.ConscriptStateS2C::new)
                .consumerNetworkThread(RpPackets.ConscriptStateS2C::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.AdminSelfProfessionC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.AdminSelfProfessionC2S::encode)
                .decoder(RpPackets.AdminSelfProfessionC2S::new)
                .consumerNetworkThread(RpPackets.AdminSelfProfessionC2S::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.AdminProfessionSaveFullC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.AdminProfessionSaveFullC2S::encode)
                .decoder(RpPackets.AdminProfessionSaveFullC2S::new)
                .consumerNetworkThread(RpPackets.AdminProfessionSaveFullC2S::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.AdminFactionSpawnC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.AdminFactionSpawnC2S::encode)
                .decoder(RpPackets.AdminFactionSpawnC2S::new)
                .consumerNetworkThread(RpPackets.AdminFactionSpawnC2S::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.AdminProfessionSpawnC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.AdminProfessionSpawnC2S::encode)
                .decoder(RpPackets.AdminProfessionSpawnC2S::new)
                .consumerNetworkThread(RpPackets.AdminProfessionSpawnC2S::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.AdminEventTriggerC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.AdminEventTriggerC2S::encode)
                .decoder(RpPackets.AdminEventTriggerC2S::new)
                .consumerNetworkThread(RpPackets.AdminEventTriggerC2S::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.AdminWaveTriggerC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.AdminWaveTriggerC2S::encode)
                .decoder(RpPackets.AdminWaveTriggerC2S::new)
                .consumerNetworkThread(RpPackets.AdminWaveTriggerC2S::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.AssetManifestS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RpPackets.AssetManifestS2C::encode)
                .decoder(RpPackets.AssetManifestS2C::new)
                .consumerNetworkThread(RpPackets.AssetManifestS2C::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.AssetRequestC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.AssetRequestC2S::encode)
                .decoder(RpPackets.AssetRequestC2S::new)
                .consumerNetworkThread(RpPackets.AssetRequestC2S::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.AssetPartS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RpPackets.AssetPartS2C::encode)
                .decoder(RpPackets.AssetPartS2C::new)
                .consumerNetworkThread(RpPackets.AssetPartS2C::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.AssetSyncDoneC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.AssetSyncDoneC2S::encode)
                .decoder(RpPackets.AssetSyncDoneC2S::new)
                .consumerNetworkThread(RpPackets.AssetSyncDoneC2S::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.CamScenePlayC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.CamScenePlayC2S::encode)
                .decoder(RpPackets.CamScenePlayC2S::new)
                .consumerNetworkThread(RpPackets.CamScenePlayC2S::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.DeployLandC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.DeployLandC2S::encode)
                .decoder(RpPackets.DeployLandC2S::new)
                .consumerNetworkThread(RpPackets.DeployLandC2S::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.PlayerTagsS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RpPackets.PlayerTagsS2C::encode)
                .decoder(RpPackets.PlayerTagsS2C::new)
                .consumerNetworkThread(RpPackets.PlayerTagsS2C::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.DeployNoticeS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RpPackets.DeployNoticeS2C::encode)
                .decoder(RpPackets.DeployNoticeS2C::new)
                .consumerNetworkThread(RpPackets.DeployNoticeS2C::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.KillFriendlyNoticeS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RpPackets.KillFriendlyNoticeS2C::encode)
                .decoder(RpPackets.KillFriendlyNoticeS2C::new)
                .consumerNetworkThread(RpPackets.KillFriendlyNoticeS2C::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.DeathNoticeS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RpPackets.DeathNoticeS2C::encode)
                .decoder(RpPackets.DeathNoticeS2C::new)
                .consumerNetworkThread(RpPackets.DeathNoticeS2C::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.XpListS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RpPackets.XpListS2C::encode)
                .decoder(RpPackets.XpListS2C::new)
                .consumerNetworkThread(RpPackets.XpListS2C::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.XpSettleAnimS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RpPackets.XpSettleAnimS2C::encode)
                .decoder(RpPackets.XpSettleAnimS2C::new)
                .consumerNetworkThread(RpPackets.XpSettleAnimS2C::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.RulesStateS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RpPackets.RulesStateS2C::encode)
                .decoder(RpPackets.RulesStateS2C::new)
                .consumerNetworkThread(RpPackets.RulesStateS2C::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.RuleEditC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.RuleEditC2S::encode)
                .decoder(RpPackets.RuleEditC2S::new)
                .consumerNetworkThread(RpPackets.RuleEditC2S::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.FactionGraphOpenS2C.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RpPackets.FactionGraphOpenS2C::encode)
                .decoder(RpPackets.FactionGraphOpenS2C::new)
                .consumerNetworkThread(RpPackets.FactionGraphOpenS2C::handle)
                .add();
        CHANNEL.messageBuilder(RpPackets.RelationEditC2S.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RpPackets.RelationEditC2S::encode)
                .decoder(RpPackets.RelationEditC2S::new)
                .consumerNetworkThread(RpPackets.RelationEditC2S::handle)
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
