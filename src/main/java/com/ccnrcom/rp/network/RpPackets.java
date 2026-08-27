/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.network;

import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/** ccnr_rp:main 通道的全部消息（P3 起追加；编码使用 JSON 字符串与字节数组，字段以 JsonObject 起名）。 */
public final class RpPackets {

    private RpPackets() {}

    // ---------- C2S ----------

    /** 请求用户档案列表（K 面板打开/刷新）。 */
    public static final class RequestCharacterListC2S {
        public RequestCharacterListC2S() {}

        public static void encode(RequestCharacterListC2S msg, FriendlyByteBuf buf) {}

        public static RequestCharacterListC2S decode(FriendlyByteBuf buf) {
            return new RequestCharacterListC2S();
        }

        public static void handle(RequestCharacterListC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.character.CharacterService.onRequestList(
                            ctx.get().getSender()));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 自刷新部署（C2S）：以「职位」为维度部署到游戏内。 */
    public static final class DeployPositionC2S {
        public final String professionId;

        public DeployPositionC2S(String professionId) {
            this.professionId = professionId;
        }

        public DeployPositionC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(64));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(professionId, 64);
        }

        public static void handle(DeployPositionC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.character.CharacterService.onDeployPosition(
                            ctx.get().getSender(), msg.professionId));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 重新部署（C2S）：玩家在场（ALIVE）请求直接重新部署为选定职位（不处死/不留遗体，服务端换装+传送）。 */
    public static final class KillDeployC2S {
        public final String professionId;

        public KillDeployC2S(String professionId) {
            this.professionId = professionId;
        }

        public KillDeployC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(64));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(professionId, 64);
        }

        public static void handle(KillDeployC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.character.CharacterService.onKillDeploy(
                            ctx.get().getSender(), msg.professionId));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 音乐上传分包（管理员，存 config/ccnr_rp/audio/）。 */
    public static final class MusicUploadPartC2S {
        public final String name;
        public final int index;
        public final int total;
        public final byte[] data;

        public MusicUploadPartC2S(String name, int index, int total, byte[] data) {
            this.name = name;
            this.index = index;
            this.total = total;
            this.data = data;
        }

        public MusicUploadPartC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(64), buf.readInt(), buf.readInt(), buf.readByteArray(65536));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(name, 64);
            buf.writeInt(index);
            buf.writeInt(total);
            buf.writeByteArray(data);
        }

        public static void handle(MusicUploadPartC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.character.CharacterService.onMusicPart(
                            ctx.get().getSender(), msg));
            ctx.get().setPacketHandled(true);
        }
    }

    public static final class MusicUploadCommitC2S {
        public final String name;
        public final int expectedSize;
        public final int total;

        public MusicUploadCommitC2S(String name, int expectedSize, int total) {
            this.name = name;
            this.expectedSize = expectedSize;
            this.total = total;
        }

        public MusicUploadCommitC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(64), buf.readInt(), buf.readInt());
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(name, 64);
            buf.writeInt(expectedSize);
            buf.writeInt(total);
        }

        public static void handle(MusicUploadCommitC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.character.CharacterService.onMusicCommit(
                            ctx.get().getSender(), msg));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 招募接受/拒绝（C2S）。 */
    public static final class RecruitAnswerC2S {
        public final String offerId;
        public final boolean accept;

        public RecruitAnswerC2S(String offerId, boolean accept) {
            this.offerId = offerId;
            this.accept = accept;
        }

        public RecruitAnswerC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(64), buf.readBoolean());
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(offerId, 64);
            buf.writeBoolean(accept);
        }

        public static void handle(RecruitAnswerC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.spawn.SpawnFramework.onRecruitAnswer(
                            ctx.get().getSender(), msg.offerId, msg.accept));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 管理面板程序化设定（C2S）：设置 serverconfig 项（CCNRRPConfig）。 */
    public static final class ServerConfigSetC2S {
        public final String key;
        public final String value;

        public ServerConfigSetC2S(String key, String value) {
            this.key = key;
            this.value = value;
        }

        public ServerConfigSetC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(64), buf.readUtf(96));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(key, 64);
            buf.writeUtf(value, 96);
        }

        public static void handle(ServerConfigSetC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.character.CharacterService.onServerConfigSet(
                            ctx.get().getSender(), msg.key, msg.value));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 通用复活波选岗（C2S）：接受邀请并指定自己要上岗的职业（charId 语义=职业 id）。 */
    public static final class RecruitPickCharacterC2S {
        public final String offerId;
        public final String charId;

        public RecruitPickCharacterC2S(String offerId, String charId) {
            this.offerId = offerId;
            this.charId = charId;
        }

        public RecruitPickCharacterC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(64), buf.readUtf(256));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(offerId, 64);
            buf.writeUtf(charId, 256);
        }

        public static void handle(RecruitPickCharacterC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.spawn.SpawnFramework.onRecruitPick(
                            ctx.get().getSender(), msg.offerId, msg.charId));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 管理器状态请求（C2S，管理员）。 */
    public static final class ManagerRequestC2S {

        public ManagerRequestC2S() {}

        public ManagerRequestC2S(FriendlyByteBuf buf) {}

        public void encode(FriendlyByteBuf buf) {}

        public static void handle(ManagerRequestC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.character.CharacterService.onManagerRequest(
                            ctx.get().getSender()));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 管理器设置修改（C2S，管理员）。 */
    public static final class ManagerSetC2S {
        public final String key;
        public final String value;

        public ManagerSetC2S(String key, String value) {
            this.key = key;
            this.value = value;
        }

        public ManagerSetC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(64), buf.readUtf(128));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(key, 64);
            buf.writeUtf(value, 128);
        }

        public static void handle(ManagerSetC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.character.CharacterService.onManagerSet(
                            ctx.get().getSender(), msg.key, msg.value));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 管理器 CRUD（C2S，管理员）：kind=faction|profession，action=create|update|delete。 */
    public static final class ManagerCrudC2S {
        public final String kind;
        public final String action;
        public final String payload;

        public ManagerCrudC2S(String kind, String action, String payload) {
            this.kind = kind;
            this.action = action;
            this.payload = payload;
        }

        public ManagerCrudC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(32), buf.readUtf(32), buf.readUtf(8192));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(kind, 32);
            buf.writeUtf(action, 32);
            buf.writeUtf(payload, 8192);
        }

        public static void handle(ManagerCrudC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.character.CharacterService.onManagerCrud(
                            ctx.get().getSender(), msg.kind, msg.action, msg.payload));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 管理端影响预检（C2S）：kind/action/payload → 服务端计算波及清单。 */
    public static final class ManagerImpactC2S {
        public final String kind;
        public final String action;
        public final String payload;

        public ManagerImpactC2S(String kind, String action, String payload) {
            this.kind = kind;
            this.action = action;
            this.payload = payload;
        }

        public ManagerImpactC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(32), buf.readUtf(32), buf.readUtf(8192));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(kind, 32);
            buf.writeUtf(action, 32);
            buf.writeUtf(payload, 8192);
        }

        public static void handle(ManagerImpactC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.character.CharacterService.onManagerImpact(
                            ctx.get().getSender(), msg.kind, msg.action, msg.payload));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 用户开关：「以任何支援身份复活」（C2S）——开启后未匹配职业也能收到复活波/征召邀请。 */
    public static final class UserAnySupportC2S {
        public final boolean on;

        public UserAnySupportC2S(boolean on) {
            this.on = on;
        }

        public UserAnySupportC2S(FriendlyByteBuf buf) {
            this(buf.readBoolean());
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeBoolean(on);
        }

        public static void handle(UserAnySupportC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.character.CharacterService.onUserAnySupport(
                            ctx.get().getSender(), msg.on));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 管理端操作：把自己的角色刷成指定职业（C2S，管理员）。 */
    public static final class AdminSelfProfessionC2S {
        public final String professionId;

        public AdminSelfProfessionC2S(String professionId) {
            this.professionId = professionId;
        }

        public AdminSelfProfessionC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(64));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(professionId, 64);
        }

        public static void handle(AdminSelfProfessionC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.character.CharacterService.onAdminSelfProfession(
                            ctx.get().getSender(), msg.professionId));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 管理端操作：把当前背包/护甲/副手（含 NBT）全量保存为所选职业 loadout（C2S，管理员）。 */
    public static final class AdminProfessionSaveFullC2S {
        public final String professionId;

        public AdminProfessionSaveFullC2S(String professionId) {
            this.professionId = professionId;
        }

        public AdminProfessionSaveFullC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(64));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(professionId, 64);
        }

        public static void handle(AdminProfessionSaveFullC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.character.CharacterService.onAdminSaveProfessionFull(
                            ctx.get().getSender(), msg.professionId));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 管理端操作：设置阵营出生点（列表 + 规则 SPREAD/SINGLE）（C2S，管理员）。 */
    public static final class AdminFactionSpawnC2S {
        public final String factionId;
        public final String rule;
        public final String pointsJson;

        public AdminFactionSpawnC2S(String factionId, String rule, String pointsJson) {
            this.factionId = factionId;
            this.rule = rule;
            this.pointsJson = pointsJson;
        }

        public AdminFactionSpawnC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(64), buf.readUtf(16), buf.readUtf(65536));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(factionId, 64);
            buf.writeUtf(rule, 16);
            buf.writeUtf(pointsJson, 65536);
        }

        public static void handle(AdminFactionSpawnC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.character.CharacterService.onAdminFactionSpawn(
                            ctx.get().getSender(), msg.factionId, msg.rule, msg.pointsJson));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 管理端操作：设置职业部署点（列表 + 规则 SPREAD/SINGLE）（C2S，管理员）。 */
    public static final class AdminProfessionSpawnC2S {
        public final String professionId;
        public final String rule;
        public final String pointsJson;

        public AdminProfessionSpawnC2S(String professionId, String rule, String pointsJson) {
            this.professionId = professionId;
            this.rule = rule;
            this.pointsJson = pointsJson;
        }

        public AdminProfessionSpawnC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(64), buf.readUtf(16), buf.readUtf(65536));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(professionId, 64);
            buf.writeUtf(rule, 16);
            buf.writeUtf(pointsJson, 65536);
        }

        public static void handle(AdminProfessionSpawnC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.character.CharacterService.onAdminProfessionSpawn(
                            ctx.get().getSender(), msg.professionId, msg.rule, msg.pointsJson));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 管理端操作：手动触发事件（C2S，管理员）。 */
    public static final class AdminEventTriggerC2S {
        public final String eventId;

        public AdminEventTriggerC2S(String eventId) {
            this.eventId = eventId;
        }

        public AdminEventTriggerC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(64));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(eventId, 64);
        }

        public static void handle(AdminEventTriggerC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.event.EventManager.onAdminTrigger(
                            ctx.get().getSender(), msg.eventId));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 管理端操作：手动召唤复活波（C2S，管理员）。 */
    public static final class AdminWaveTriggerC2S {
        public final String waveId;

        public AdminWaveTriggerC2S(String waveId) {
            this.waveId = waveId;
        }

        public AdminWaveTriggerC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(64));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(waveId, 64);
        }

        public static void handle(AdminWaveTriggerC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.spawn.SpawnFramework.onAdminTrigger(
                            ctx.get().getSender(), msg.waveId));
            ctx.get().setPacketHandled(true);
        }
    }

    // ---------- S2C ----------

    /** 用户档案全量（JSON 字符串，client 端轻量重组）。 */
    public static final class CharacterListS2C {
        public final String payload;

        public CharacterListS2C(String payload) {
            this.payload = payload;
        }

        public CharacterListS2C(FriendlyByteBuf buf) {
            this(buf.readUtf(262144));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(payload, 262144);
        }

        public static void handle(CharacterListS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                            net.minecraftforge.api.distmarker.Dist.CLIENT,
                            () -> () -> com.ccnrcom.rp.client.ClientPacketHandlers.onCharacterList(msg.payload)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 音乐列表（S2C）：JSON 数组字符串（已上传音乐名）。 */
    public static final class MusicListS2C {
        public final String payload;

        public MusicListS2C(String payload) {
            this.payload = payload;
        }

        public MusicListS2C(FriendlyByteBuf buf) {
            this(buf.readUtf(65536));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(payload, 65536);
        }

        public static void handle(MusicListS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                            net.minecraftforge.api.distmarker.Dist.CLIENT,
                            () -> () -> com.ccnrcom.rp.client.ClientPacketHandlers.onMusicList(msg.payload)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 动画播放（客户端执行序列）。 */
    public static final class AnimationPlayS2C {
        public final String payload;

        public AnimationPlayS2C(String payload) {
            this.payload = payload;
        }

        public AnimationPlayS2C(FriendlyByteBuf buf) {
            this(buf.readUtf(65536));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(payload, 65536);
        }

        public static void handle(AnimationPlayS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                            net.minecraftforge.api.distmarker.Dist.CLIENT,
                            () -> () -> com.ccnrcom.rp.client.ClientPacketHandlers.onAnimation(msg.payload)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 部署入场电影（S2C）：JSON（名字/职业/阵营/图标/等级/简历/阵营关系）。 */
    public static final class CinematicS2C {
        public final String payload;

        public CinematicS2C(String payload) {
            this.payload = payload;
        }

        public CinematicS2C(FriendlyByteBuf buf) {
            this(buf.readUtf(65536));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(payload, 65536);
        }

        public static void handle(CinematicS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                            net.minecraftforge.api.distmarker.Dist.CLIENT,
                            () -> () -> com.ccnrcom.rp.client.ClientPacketHandlers.onCinematic(msg.payload)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 招募 offer（S2C）；kind=wave|conscript（区分复活波与征召，客户端展示不同标签）。 */
    public static final class RecruitOfferS2C {
        public final String offerId;
        public final String charId;
        public final String charName;
        public final String professionId;
        public final int initialTicks;
        public final String waveId;
        public final String kind;

        public RecruitOfferS2C(
                String offerId,
                String charId,
                String charName,
                String professionId,
                int initialTicks,
                String waveId,
                String kind) {
            this.offerId = offerId;
            this.charId = charId;
            this.charName = charName;
            this.professionId = professionId;
            this.initialTicks = initialTicks;
            this.waveId = waveId;
            this.kind = kind;
        }

        public RecruitOfferS2C(FriendlyByteBuf buf) {
            this(
                    buf.readUtf(64),
                    buf.readUtf(256),
                    buf.readUtf(64),
                    buf.readUtf(64),
                    buf.readInt(),
                    buf.readUtf(64),
                    buf.readUtf(16));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(offerId, 64);
            buf.writeUtf(charId, 256);
            buf.writeUtf(charName, 64);
            buf.writeUtf(professionId, 64);
            buf.writeInt(initialTicks);
            buf.writeUtf(waveId, 64);
            buf.writeUtf(kind, 16);
        }

        public static void handle(RecruitOfferS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                            net.minecraftforge.api.distmarker.Dist.CLIENT,
                            () -> () -> com.ccnrcom.rp.client.ClientPacketHandlers.onRecruitOffer(msg)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 激活事件横幅（S2C）。 */
    public static final class EventStateS2C {
        public final String payload;

        public EventStateS2C(String payload) {
            this.payload = payload;
        }

        public EventStateS2C(FriendlyByteBuf buf) {
            this(buf.readUtf(8192));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(payload, 8192);
        }

        public static void handle(EventStateS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                            net.minecraftforge.api.distmarker.Dist.CLIENT,
                            () -> () -> com.ccnrcom.rp.client.ClientPacketHandlers.onEventState(msg.payload)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 管理器状态（S2C）。 */
    public static final class ManagerStateS2C {
        public final String payload;

        public ManagerStateS2C(String payload) {
            this.payload = payload;
        }

        public ManagerStateS2C(FriendlyByteBuf buf) {
            this(buf.readUtf(8192));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(payload, 8192);
        }

        public static void handle(ManagerStateS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                            net.minecraftforge.api.distmarker.Dist.CLIENT,
                            () -> () -> com.ccnrcom.rp.client.ClientPacketHandlers.onManagerState(msg.payload)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 管理端影响预检返回（S2C）：JSON {kind, action, payload, lines:[...]}；lines 空=可直接执行。 */
    public static final class ManagerImpactS2C {
        public final String payload;

        public ManagerImpactS2C(String payload) {
            this.payload = payload;
        }

        public ManagerImpactS2C(FriendlyByteBuf buf) {
            this(buf.readUtf(8192));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(payload, 8192);
        }

        public static void handle(ManagerImpactS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                            net.minecraftforge.api.distmarker.Dist.CLIENT,
                            () -> () -> com.ccnrcom.rp.client.ClientPacketHandlers.onManagerImpact(msg.payload)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 征召兵身份状态（S2C）：部署征召兵时下发在场身份，阵亡/结束时下发空串清除（征召兵不在角色库，HUD 靠此显示）。 */
    public static final class ConscriptStateS2C {
        public final String payload;

        public ConscriptStateS2C(String payload) {
            this.payload = payload;
        }

        public ConscriptStateS2C(FriendlyByteBuf buf) {
            this(buf.readUtf(512));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(payload, 512);
        }

        public static void handle(ConscriptStateS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                            net.minecraftforge.api.distmarker.Dist.CLIENT,
                            () -> () -> com.ccnrcom.rp.client.ClientPacketHandlers.onConscriptState(msg.payload)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 用户经验/等级更新（S2C，经验随用户走）。 */
    public static final class UserXpS2C {
        public final long xp;
        public final int level;

        public UserXpS2C(long xp, int level) {
            this.xp = xp;
            this.level = level;
        }

        public UserXpS2C(FriendlyByteBuf buf) {
            this(buf.readLong(), buf.readVarInt());
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeLong(xp);
            buf.writeVarInt(level);
        }

        public static void handle(UserXpS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                            net.minecraftforge.api.distmarker.Dist.CLIENT,
                            () -> () -> com.ccnrcom.rp.client.ClientPacketHandlers.onUserXp(msg.xp, msg.level)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 经验列表状态推送（S2C，经验系统 v3）：JSON {total, items:[{title,value}]}。 */
    public static final class XpListS2C {
        public final String payload;

        public XpListS2C(String payload) {
            this.payload = payload;
        }

        public XpListS2C(FriendlyByteBuf buf) {
            this(buf.readUtf(8192));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(payload, 8192);
        }

        public static void handle(XpListS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                            net.minecraftforge.api.distmarker.Dist.CLIENT,
                            () -> () -> com.ccnrcom.rp.client.ClientPacketHandlers.onXpList(msg.payload)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 经验结算动画（S2C，经验系统 v3）：JSON {total, items:[{title,value}]}，客户端逐项吸入动画。 */
    public static final class XpSettleAnimS2C {
        public final String payload;

        public XpSettleAnimS2C(String payload) {
            this.payload = payload;
        }

        public XpSettleAnimS2C(FriendlyByteBuf buf) {
            this(buf.readUtf(8192));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(payload, 8192);
        }

        public static void handle(XpSettleAnimS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                            net.minecraftforge.api.distmarker.Dist.CLIENT,
                            () -> () -> com.ccnrcom.rp.client.ClientPacketHandlers.onXpSettleAnim(msg.payload)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 经验规则集（S2C）：JSON {version, rules:[...]}，管理面板「经验规则」页展示。 */
    public static final class RulesStateS2C {
        public final String payload;

        public RulesStateS2C(String payload) {
            this.payload = payload;
        }

        public RulesStateS2C(FriendlyByteBuf buf) {
            this(buf.readUtf(16384));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(payload, 16384);
        }

        public static void handle(RulesStateS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                            net.minecraftforge.api.distmarker.Dist.CLIENT,
                            () -> () -> com.ccnrcom.rp.client.ClientPacketHandlers.onRulesState(msg.payload)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 经验规则编辑（C2S）：{action: add|update|remove|toggle, rule: {...}|id}；服务端校验+落盘+回执。 */
    public static final class RuleEditC2S {
        public final String payload;

        public RuleEditC2S(String payload) {
            this.payload = payload;
        }

        public RuleEditC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(8192));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(payload, 8192);
        }

        public static void handle(RuleEditC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.experience.ExperienceService.onRuleEdit(
                            ctx.get().getSender(), msg.payload));
            ctx.get().setPacketHandled(true);
        }
    }

    /**
     * 关系规则编辑（C2S）：{action: add|update|remove, rule: {from[],to?,type}, original?{from[],to?}}；
     * update 携带 original（选中规则的原始 from/to）时服务端原位替换；无 original 回退按新值 upsert。
     * 服务端校验+落盘+回执。
     */
    public static final class RelationEditC2S {
        public final String payload;

        public RelationEditC2S(String payload) {
            this.payload = payload;
        }

        public RelationEditC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(8192));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(payload, 8192);
        }

        public static void handle(RelationEditC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.faction.FactionManager.onRelationEdit(
                            ctx.get().getSender(), msg.payload));
            ctx.get().setPacketHandled(true);
        }
    }

    public static final class ErrorS2C {
        public final String messageKey;
        public final String[] args;

        public ErrorS2C(String messageKey, String... args) {
            this.messageKey = messageKey;
            this.args = args;
        }

        public ErrorS2C(FriendlyByteBuf buf) {
            this(buf.readUtf(128), readArgs(buf));
        }

        private static String[] readArgs(FriendlyByteBuf buf) {
            int n = buf.readVarInt();
            String[] out = new String[Math.min(n, 16)];
            for (int i = 0; i < out.length; i++) {
                out[i] = buf.readUtf(512);
            }
            return out;
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(messageKey, 128);
            int n = Math.min(args.length, 16); // 与 decode 上限一致，防缓冲残留错位
            buf.writeVarInt(n);
            for (int i = 0; i < n; i++) {
                buf.writeUtf(args[i] == null ? "" : args[i], 512);
            }
        }

        public static void handle(ErrorS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                            net.minecraftforge.api.distmarker.Dist.CLIENT,
                            () -> () -> com.ccnrcom.rp.client.ClientPacketHandlers.onError(msg.messageKey, msg.args)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 击杀友好提示（S2C，击杀者定向）：击杀者击杀友好阵营玩家时发送，客户端左下角弹出提示。 */
    public static final class KillFriendlyNoticeS2C {
        public final String victimName;
        public final String victimUuid;
        public final String victimFactionId;
        public final String victimProfessionId;

        public KillFriendlyNoticeS2C(
                String victimName, String victimUuid, String victimFactionId, String victimProfessionId) {
            this.victimName = victimName == null ? "" : victimName;
            this.victimUuid = victimUuid == null ? "" : victimUuid;
            this.victimFactionId = victimFactionId == null ? "" : victimFactionId;
            this.victimProfessionId = victimProfessionId == null ? "" : victimProfessionId;
        }

        public KillFriendlyNoticeS2C(FriendlyByteBuf buf) {
            this(buf.readUtf(64), buf.readUtf(64), buf.readUtf(64), buf.readUtf(64));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(victimName, 64);
            buf.writeUtf(victimUuid, 64);
            buf.writeUtf(victimFactionId, 64);
            buf.writeUtf(victimProfessionId, 64);
        }

        public static void handle(KillFriendlyNoticeS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                            net.minecraftforge.api.distmarker.Dist.CLIENT,
                            () -> () -> com.ccnrcom.rp.client.ClientPacketHandlers.onKillFriendlyNotice(msg)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 部署完成通知（S2C，部署者定向）：部署成功后发送，客户端显示常驻「已部署」横幅（30s）。 */
    public static final class DeployNoticeS2C {
        public final String professionName;
        public final String factionId;

        public DeployNoticeS2C(String professionName, String factionId) {
            this.professionName = professionName == null ? "" : professionName;
            this.factionId = factionId == null ? "" : factionId;
        }

        public DeployNoticeS2C(FriendlyByteBuf buf) {
            this(buf.readUtf(128), buf.readUtf(64));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(professionName, 128);
            buf.writeUtf(factionId, 64);
        }

        public static void handle(DeployNoticeS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                            net.minecraftforge.api.distmarker.Dist.CLIENT,
                            () -> () -> com.ccnrcom.rp.client.ClientPacketHandlers.onDeployNotice(
                                    msg.professionName, msg.factionId)));
            ctx.get().setPacketHandled(true);
        }
    }

    // ---------- 素材中央下发（服务器控制：音乐 / 阵营图标） ----------

    /** 素材清单（S2C）：[{name,size,hash}]，客户端对比本地缓存后请求缺失/变更项。 */
    public static final class AssetManifestS2C {
        public final String payload;

        public AssetManifestS2C(String payload) {
            this.payload = payload;
        }

        public AssetManifestS2C(FriendlyByteBuf buf) {
            this(buf.readUtf(131072));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(payload, 131072);
        }

        public static void handle(AssetManifestS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                            net.minecraftforge.api.distmarker.Dist.CLIENT,
                            () -> () -> com.ccnrcom.rp.client.ClientPacketHandlers.onAssetManifest(msg.payload)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 素材下载请求（C2S）。 */
    public static final class AssetRequestC2S {
        public final String name;

        public AssetRequestC2S(String name) {
            this.name = name;
        }

        public AssetRequestC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(128));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(name, 128);
        }

        public static void handle(AssetRequestC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.assets.AssetLibrary.onRequest(
                            ctx.get().getSender(), msg.name));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 素材分片（S2C）：32KB 分片，index 0..total-1。 */
    public static final class AssetPartS2C {
        public final String name;
        public final int index;
        public final int total;
        public final byte[] data;

        public AssetPartS2C(String name, int index, int total, byte[] data) {
            this.name = name;
            this.index = index;
            this.total = total;
            this.data = data;
        }

        public AssetPartS2C(FriendlyByteBuf buf) {
            this(buf.readUtf(128), buf.readInt(), buf.readInt(), buf.readByteArray());
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(name, 128);
            buf.writeInt(index);
            buf.writeInt(total);
            buf.writeByteArray(data);
        }

        public static void handle(AssetPartS2C msg, Supplier<NetworkEvent.Context> ctx) {
            net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                    net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> () -> com.ccnrcom.rp.client.ClientPacketHandlers.onAssetPart(msg));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 素材同步完成确认（C2S）：客户端清单处理完毕（无可下载项或全部下载完成）。 */
    public static final class AssetSyncDoneC2S {

        public AssetSyncDoneC2S() {}

        public AssetSyncDoneC2S(FriendlyByteBuf buf) {}

        public void encode(FriendlyByteBuf buf) {}

        public static void handle(AssetSyncDoneC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.assets.AssetLibrary.onSyncDone(
                            ctx.get().getSender()));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 全玩家头顶标签数据（S2C）：{uuid: {name, professionId, factionId, level}}，供客户端 nametag 渲染。 */
    public static final class PlayerTagsS2C {
        public final String payload;

        public PlayerTagsS2C(String payload) {
            this.payload = payload;
        }

        public PlayerTagsS2C(FriendlyByteBuf buf) {
            this(buf.readUtf(16384));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(payload, 16384);
        }

        public static void handle(PlayerTagsS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                            net.minecraftforge.api.distmarker.Dist.CLIENT,
                            () -> () -> com.ccnrcom.rp.client.ClientPacketHandlers.onPlayerTags(msg.payload)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** CMDCam 场景播放请求（C2S）：客户端动画步骤 CAMS 或部署电影结束时请求播放已保存场景。 */
    public static final class CamScenePlayC2S {
        public final String scene;

        public CamScenePlayC2S(String scene) {
            this.scene = scene == null ? "" : scene;
        }

        public CamScenePlayC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(128));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(scene, 128);
        }

        public static void handle(CamScenePlayC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.cmdcam.CamSceneBridge.playScene(
                            ctx.get().getSender() == null
                                    ? null
                                    : ctx.get().getSender().level(),
                            msg.scene,
                            ctx.get().getSender()));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 部署落位通知（C2S，空载荷）：客户端入场电影播完时发送，服务端据此传送到出生点并播放 CMDCam 出场场景。 */
    public static final class DeployLandC2S {
        public DeployLandC2S() {}

        public DeployLandC2S(FriendlyByteBuf buf) {}

        public void encode(FriendlyByteBuf buf) {}

        public static void handle(DeployLandC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                var p = ctx.get().getSender();
                if (p != null && com.ccnrcom.rp.CCNRRPMod.spawnFramework != null) {
                    com.ccnrcom.rp.CCNRRPMod.spawnFramework.onDeployLand(p);
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** 打开关系测定图（S2C，空载荷）：/rp faction graph 或管理面板按钮触发，客户端弹出全屏图。 */
    public static final class FactionGraphOpenS2C {
        public FactionGraphOpenS2C() {}

        public FactionGraphOpenS2C(FriendlyByteBuf buf) {}

        public void encode(FriendlyByteBuf buf) {}

        public static void handle(FactionGraphOpenS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                            net.minecraftforge.api.distmarker.Dist.CLIENT,
                            () -> () -> com.ccnrcom.rp.client.ClientPacketHandlers.onFactionGraphOpen()));
            ctx.get().setPacketHandled(true);
        }
    }
}
