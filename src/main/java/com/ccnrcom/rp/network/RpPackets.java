/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.network;

import com.google.gson.JsonObject;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/** ccnr_rp:main 通道的全部消息（P3 起追加；编码使用 JSON 字符串与字节数组，字段以 JsonObject 起名）。 */
public final class RpPackets {

    private RpPackets() {}

    // ---------- C2S ----------

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

    /** 创建角色。 */
    public static final class CharacterCreateC2S {
        public final String name;
        public final String factionId;
        public final String professionId;
        public final String background;

        public CharacterCreateC2S(String name, String factionId, String professionId, String background) {
            this.name = name;
            this.factionId = factionId;
            this.professionId = professionId;
            this.background = background;
        }

        public CharacterCreateC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(64), buf.readUtf(64), buf.readUtf(64), buf.readUtf(512));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(name, 64);
            buf.writeUtf(factionId, 64);
            buf.writeUtf(professionId, 64);
            buf.writeUtf(background, 512);
        }

        public static void handle(CharacterCreateC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.character.CharacterService.onCreate(
                            ctx.get().getSender(), msg.name, msg.factionId, msg.professionId, msg.background));
            ctx.get().setPacketHandled(true);
        }
    }

    public static final class CharacterSelectC2S {
        public final String charId;

        public CharacterSelectC2S(String charId) {
            this.charId = charId;
        }

        public CharacterSelectC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(256));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(charId, 256);
        }

        public static void handle(CharacterSelectC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.character.CharacterService.onSelect(
                            ctx.get().getSender(), msg.charId));
            ctx.get().setPacketHandled(true);
        }
    }

    public static final class CharacterDeleteC2S {
        public final String charId;

        public CharacterDeleteC2S(String charId) {
            this.charId = charId;
        }

        public CharacterDeleteC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(256));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(charId, 256);
        }

        public static void handle(CharacterDeleteC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.character.CharacterService.onDelete(
                            ctx.get().getSender(), msg.charId));
            ctx.get().setPacketHandled(true);
        }
    }

    public static final class CharacterObserveC2S {
        public final String charId;

        public CharacterObserveC2S(String charId) {
            this.charId = charId;
        }

        public CharacterObserveC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(256));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(charId, 256);
        }

        public static void handle(CharacterObserveC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.character.CharacterService.onObserve(
                            ctx.get().getSender(), msg.charId));
            ctx.get().setPacketHandled(true);
        }
    }

    public static final class CharacterActivateC2S {
        public final String charId;

        public CharacterActivateC2S(String charId) {
            this.charId = charId;
        }

        public CharacterActivateC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(256));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(charId, 256);
        }

        public static void handle(CharacterActivateC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.character.CharacterService.onActivate(
                            ctx.get().getSender(), msg.charId));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 皮肤上传分包。 */
    public static final class SkinUploadPartC2S {
        public final String charId;
        public final int index;
        public final int total;
        public final byte[] data;

        public SkinUploadPartC2S(String charId, int index, int total, byte[] data) {
            this.charId = charId;
            this.index = index;
            this.total = total;
            this.data = data;
        }

        public SkinUploadPartC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(256), buf.readInt(), buf.readInt(), buf.readByteArray());
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(charId, 256);
            buf.writeInt(index);
            buf.writeInt(total);
            buf.writeByteArray(data);
        }

        public static void handle(SkinUploadPartC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.character.CharacterService.onSkinPart(
                            ctx.get().getSender(), msg));
            ctx.get().setPacketHandled(true);
        }
    }

    public static final class SkinUploadCommitC2S {
        public final String charId;
        public final int expectedSize;
        public final int total;

        public SkinUploadCommitC2S(String charId, int expectedSize, int total) {
            this.charId = charId;
            this.expectedSize = expectedSize;
            this.total = total;
        }

        public SkinUploadCommitC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(256), buf.readInt(), buf.readInt());
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(charId, 256);
            buf.writeInt(expectedSize);
            buf.writeInt(total);
        }

        public static void handle(SkinUploadCommitC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.character.CharacterService.onSkinCommit(
                            ctx.get().getSender(), msg));
            ctx.get().setPacketHandled(true);
        }
    }

    // ---------- S2C ----------

    /** 全量角色列表（JSON 数组字符串，client 端轻量重组）。 */
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

    public static final class CharacterUpdateS2C {
        public final JsonObject data;

        public CharacterUpdateS2C(JsonObject data) {
            this.data = data;
        }

        public CharacterUpdateS2C(FriendlyByteBuf buf) {
            this(com.ccnrcom.rp.util.JsonUtil.GSON.fromJson(buf.readUtf(262144), JsonObject.class));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(data.toString(), 262144);
        }

        public static void handle(CharacterUpdateS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                            net.minecraftforge.api.distmarker.Dist.CLIENT,
                            () -> () -> com.ccnrcom.rp.client.ClientPacketHandlers.onCharacterUpdate(msg.data)));
            ctx.get().setPacketHandled(true);
        }
    }

    public static final class CharacterRemoveS2C {
        public final String charId;

        public CharacterRemoveS2C(String charId) {
            this.charId = charId;
        }

        public CharacterRemoveS2C(FriendlyByteBuf buf) {
            this(buf.readUtf(256));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(charId, 256);
        }

        public static void handle(CharacterRemoveS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                            net.minecraftforge.api.distmarker.Dist.CLIENT,
                            () -> () -> com.ccnrcom.rp.client.ClientPacketHandlers.onCharacterRemove(msg.charId)));
            ctx.get().setPacketHandled(true);
        }
    }

    public static final class SkinSyncS2C {
        public final String charId;
        public final byte[] data;
        public final String hash;

        public SkinSyncS2C(String charId, byte[] data, String hash) {
            this.charId = charId;
            this.data = data;
            this.hash = hash;
        }

        public SkinSyncS2C(FriendlyByteBuf buf) {
            this(buf.readUtf(256), buf.readByteArray(), buf.readUtf(128));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(charId, 256);
            buf.writeByteArray(data);
            buf.writeUtf(hash, 128);
        }

        public static void handle(SkinSyncS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                            net.minecraftforge.api.distmarker.Dist.CLIENT,
                            () -> () -> com.ccnrcom.rp.client.ClientPacketHandlers.onSkinSync(
                                    msg.charId, msg.data, msg.hash)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 经验/等级更新（owner 定向）。 */
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

    public static final class XpUpdateS2C {
        public final String charId;
        public final long xp;
        public final int level;

        public XpUpdateS2C(String charId, long xp, int level) {
            this.charId = charId;
            this.xp = xp;
            this.level = level;
        }

        public XpUpdateS2C(FriendlyByteBuf buf) {
            this(buf.readUtf(256), buf.readLong(), buf.readVarInt());
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(charId, 256);
            buf.writeLong(xp);
            buf.writeVarInt(level);
        }

        public static void handle(XpUpdateS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                            net.minecraftforge.api.distmarker.Dist.CLIENT,
                            () -> () ->
                                    com.ccnrcom.rp.client.ClientPacketHandlers.onXp(msg.charId, msg.xp, msg.level)));
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

    /** 招募 offer（S2C）。 */
    public static final class RecruitOfferS2C {
        public final String offerId;
        public final String charId;
        public final String charName;
        public final String professionId;
        public final int initialTicks;
        public final String waveId;

        public RecruitOfferS2C(
                String offerId, String charId, String charName, String professionId, int initialTicks, String waveId) {
            this.offerId = offerId;
            this.charId = charId;
            this.charName = charName;
            this.professionId = professionId;
            this.initialTicks = initialTicks;
            this.waveId = waveId;
        }

        public RecruitOfferS2C(FriendlyByteBuf buf) {
            this(buf.readUtf(64), buf.readUtf(256), buf.readUtf(64), buf.readUtf(64), buf.readInt(), buf.readUtf(64));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(offerId, 64);
            buf.writeUtf(charId, 256);
            buf.writeUtf(charName, 64);
            buf.writeUtf(professionId, 64);
            buf.writeInt(initialTicks);
            buf.writeUtf(waveId, 64);
        }

        public static void handle(RecruitOfferS2C msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                            net.minecraftforge.api.distmarker.Dist.CLIENT,
                            () -> () -> com.ccnrcom.rp.client.ClientPacketHandlers.onRecruitOffer(msg)));
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

    /** 自刷新部署（C2S）。 */
    public static final class CharacterDeployC2S {
        public final String charId;

        public CharacterDeployC2S(String charId) {
            this.charId = charId;
        }

        public CharacterDeployC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(256));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(charId, 256);
        }

        public static void handle(CharacterDeployC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.character.CharacterService.onDeploy(
                            ctx.get().getSender(), msg.charId));
            ctx.get().setPacketHandled(true);
        }
    }

    /** 转生/退役（C2S，强制保留角色）。 */
    public static final class CharacterRetireC2S {
        public final String charId;

        public CharacterRetireC2S(String charId) {
            this.charId = charId;
        }

        public CharacterRetireC2S(FriendlyByteBuf buf) {
            this(buf.readUtf(256));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(charId, 256);
        }

        public static void handle(CharacterRetireC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.character.CharacterService.onRetire(
                            ctx.get().getSender(), msg.charId));
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
            this(buf.readUtf(64), buf.readUtf(16));
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(key, 64);
            buf.writeUtf(value, 16);
        }

        public static void handle(ManagerSetC2S msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get()
                    .enqueueWork(() -> com.ccnrcom.rp.character.CharacterService.onManagerSet(
                            ctx.get().getSender(), msg.key, msg.value));
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
            buf.writeVarInt(args.length);
            for (String a : args) {
                buf.writeUtf(a, 512);
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
}
