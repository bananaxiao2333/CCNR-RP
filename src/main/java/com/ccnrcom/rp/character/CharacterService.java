/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.character;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.config.CCNRRPConfig;
import com.ccnrcom.rp.network.RpChannels;
import com.ccnrcom.rp.network.RpPackets;
import com.ccnrcom.rp.status.CharacterStatus;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** 角色服务（服务端）：CRUD 规则、皮肤上传与分发、与客户端的同步。 */
public final class CharacterService {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int MAX_SKIN_BYTES = 256 * 1024;
    private static final int MAX_SKIN_DIMENSION = 512;
    private static final int PART_SIZE = 32 * 1024;

    private final CharacterStore store;
    private final Path skinDir;
    private final Map<UUID, String> selected = new HashMap<>();
    private final Map<UUID, SkinUpload> uploads = new HashMap<>();

    public CharacterService(MinecraftServer server) {
        net.minecraft.world.level.storage.LevelResource dir =
                new net.minecraft.world.level.storage.LevelResource("ccnr_rp");
        this.store = new CharacterStore(server.getWorldPath(dir));
        this.skinDir = server.getWorldPath(dir).resolve("skins");
        store.load();
    }

    public CharacterStore store() {
        return store;
    }

    public Path skinDir() {
        return skinDir;
    }

    private record SkinUpload(String charId, byte[] parts, int received) {}

    // ---------- 入站 ----------

    public static void onRequestList(ServerPlayer player) {
        if (player != null) {
            service().sendList(player);
        }
    }

    public static void onCreate(
            ServerPlayer player, String name, String factionId, String professionId, String background) {
        if (player == null) {
            return;
        }
        CharacterService svc = service();
        List<String> errors = svc.validateCreate(player, name, factionId, professionId);
        if (!errors.isEmpty()) {
            errors.forEach(
                    e -> RpChannels.sendTo(player, new RpPackets.ErrorS2C("ccnr_rp.character.error.generic", e)));
            return;
        }
        CharacterData c = svc.store.create(
                player.getUUID().toString(),
                name.trim(),
                factionId,
                professionId,
                background == null ? "" : background.trim(),
                CCNRRPConfig.MAX_CHARACTERS_PER_PLAYER.get(),
                UUID::randomUUID);
        svc.store.save();
        RpChannels.sendTo(player, new RpPackets.ErrorS2C("ccnr_rp.character.create.ok", c.name()));
        svc.sendList(player);
    }

    public static void onSelect(ServerPlayer player, String charId) {
        CharacterService svc = service();
        if (svc.owns(player, charId)) {
            svc.selected.put(player.getUUID(), charId);
            svc.sendList(player);
        }
    }

    public static void onDelete(ServerPlayer player, String charId) {
        CharacterService svc = service();
        if (!svc.owns(player, charId)) {
            svc.sendError(player, "ccnr_rp.character.error.ownership");
            return;
        }
        svc.selected.remove(player.getUUID());
        svc.store.delete(charId);
        svc.store.save();
        RpChannels.sendTo(player, new RpPackets.CharacterRemoveS2C(charId));
    }

    public static void onObserve(ServerPlayer player, String charId) {
        CharacterService svc = service();
        Optional<CharacterData> c = svc.store.find(charId);
        if (c.isEmpty() || !c.get().playerUuid().equals(player.getUUID().toString())) {
            svc.sendError(player, "ccnr_rp.character.error.ownership");
            return;
        }
        CharacterData data = c.get();
        if (data.status() == CharacterStatus.ALIVE) {
            svc.updateCharacter(data.withStatus(CharacterStatus.OBSERVING), player);
            svc.sendError(player, "ccnr_rp.character.observe.ok", data.name());
        } else if (data.status() == CharacterStatus.OBSERVING) {
            svc.sendError(player, "ccnr_rp.character.error.status", data.name(), "观察状态无需切换".toString());
        } else {
            svc.sendError(player, "ccnr_rp.character.error.status", data.name(), "死亡角色不可切换观察");
        }
    }

    public static void onActivate(ServerPlayer player, String charId) {
        CharacterService svc = service();
        Optional<CharacterData> c = svc.store.find(charId);
        if (c.isEmpty() || !c.get().playerUuid().equals(player.getUUID().toString())) {
            svc.sendError(player, "ccnr_rp.character.error.ownership");
            return;
        }
        CharacterData data = c.get();
        Optional<CharacterData> alive = svc.store.findAlive(player.getUUID().toString());
        if (data.status() != CharacterStatus.OBSERVING) {
            svc.sendError(
                    player,
                    "ccnr_rp.character.error.status",
                    data.name(),
                    data.status().name());
            return;
        }
        if (alive.isPresent()) {
            svc.sendError(
                    player, "ccnr_rp.character.error.alive_exists", alive.get().name());
            return;
        }
        svc.updateCharacter(data.withStatus(CharacterStatus.ALIVE), player);
        svc.sendError(player, "ccnr_rp.character.activate.ok", data.name());
    }

    public static void onSkinPart(ServerPlayer player, RpPackets.SkinUploadPartC2S msg) {
        if (player == null) {
            return;
        }
        CharacterService svc = service();
        SkinUpload up = svc.uploads.getOrDefault(player.getUUID(), new SkinUpload(msg.charId, new byte[0], 0));
        if (!up.charId().equals(msg.charId)) {
            up = new SkinUpload(msg.charId, new byte[0], 0);
        }
        byte[] next = new byte[up.parts().length + msg.data.length];
        System.arraycopy(up.parts(), 0, next, 0, up.parts().length);
        System.arraycopy(msg.data, 0, next, up.parts().length, msg.data.length);
        svc.uploads.put(player.getUUID(), new SkinUpload(msg.charId, next, msg.index + 1));
    }

    public static void onSkinCommit(ServerPlayer player, RpPackets.SkinUploadCommitC2S msg) {
        if (player == null) {
            return;
        }
        CharacterService svc = service();
        SkinUpload up = svc.uploads.remove(player.getUUID());
        if (up == null || !up.charId().equals(msg.charId) || up.parts().length != msg.expectedSize) {
            svc.sendError(player, "ccnr_rp.character.error.skin", "数据不完整");
            return;
        }
        Optional<CharacterData> c = svc.store.find(msg.charId);
        if (c.isEmpty() || !c.get().playerUuid().equals(player.getUUID().toString())) {
            svc.sendError(player, "ccnr_rp.character.error.ownership");
            return;
        }
        String error = svc.validateSkin(up.parts());
        if (error != null) {
            svc.sendError(player, "ccnr_rp.character.error.skin", error);
            return;
        }
        try {
            Files.createDirectories(svc.skinDir);
            String fileName = c.get().id() + ".png";
            Files.write(svc.skinDir.resolve(fileName), up.parts());
            String hash = sha256(up.parts());
            CharacterData updated = c.get().withSkin(fileName, hash);
            svc.store.update(updated);
            svc.store.save();
            // 在全服广播皮肤（其他玩家用于招募列表头像等）
            RpChannels.sendToAll(new RpPackets.SkinSyncS2C(c.get().id(), up.parts(), hash));
            svc.sendError(player, "ccnr_rp.character.skin.ok", fileName);
        } catch (Exception e) {
            LOGGER.error("[CCNR-RP] 皮肤写入失败", e);
            svc.sendError(player, "ccnr_rp.character.error.skin", e.toString());
        }
    }

    // ---------- 内部 ----------

    private static CharacterService service() {
        CharacterService s = CCNRRPMod.characters;
        if (s == null) {
            throw new IllegalStateException("角色服务未初始化");
        }
        return s;
    }

    private boolean owns(ServerPlayer player, String charId) {
        return store.find(charId)
                .map(c -> c.playerUuid().equals(player.getUUID().toString()))
                .orElse(false);
    }

    private List<String> validateCreate(ServerPlayer player, String name, String factionId, String professionId) {
        List<String> errors = new ArrayList<>();
        if (name == null || name.trim().length() < 1 || name.trim().length() > 32) {
            errors.add(tr("ccnr_rp.character.error.name"));
        }
        if (CCNRRPMod.factions == null || !CCNRRPMod.factions.graph().factions().containsKey(factionId)) {
            errors.add(tr("ccnr_rp.character.error.faction", factionId));
        } else {
            var def = CCNRRPMod.factions.findProfession(professionId);
            if (def.isEmpty()) {
                errors.add(tr("ccnr_rp.character.error.profession", professionId));
            } else if (!factionId.equals(com.ccnrcom.rp.faction.FactionProfessions.factionId(def.get()))) {
                errors.add(tr("ccnr_rp.character.error.profession", professionId));
            }
        }
        if (store.countOf(player.getUUID().toString()) >= CCNRRPConfig.MAX_CHARACTERS_PER_PLAYER.get()) {
            errors.add(tr("ccnr_rp.character.error.count", CCNRRPConfig.MAX_CHARACTERS_PER_PLAYER.get()));
        }
        return errors;
    }

    private String validateSkin(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_SKIN_BYTES) {
            return "大小超限（≤ " + MAX_SKIN_BYTES + " 字节）";
        }
        if (bytes.length < 8 || bytes[0] != (byte) 0x89 || bytes[1] != 'P' || bytes[2] != 'N' || bytes[3] != 'G') {
            return "不是 PNG 文件";
        }
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) {
                return "PNG 解码失败";
            }
            if (img.getWidth() > MAX_SKIN_DIMENSION || img.getHeight() > MAX_SKIN_DIMENSION) {
                return "尺寸超限（≤ " + MAX_SKIN_DIMENSION + "×" + MAX_SKIN_DIMENSION + "）";
            }
        } catch (Exception e) {
            return "PNG 解码失败: " + e.getMessage();
        }
        return null;
    }

    private void updateCharacter(CharacterData updated, ServerPlayer owner) {
        store.update(updated);
        store.save();
        RpChannels.sendTo(owner, new RpPackets.CharacterUpdateS2C(updated.toJson()));
    }

    public void sendList(ServerPlayer player) {
        JsonObject root = new JsonObject();
        JsonArray a = new JsonArray();
        for (CharacterData c : store.ofPlayer(player.getUUID().toString())) {
            a.add(c.toJson());
        }
        root.add("characters", a);
        root.addProperty("selected", selected.getOrDefault(player.getUUID(), ""));
        // 附带阵容/职业数据供客户端 GUI（阵营下拉/职业下拉使用）
        JsonArray fa = new JsonArray();
        if (CCNRRPMod.factions != null) {
            CCNRRPMod.factions.graph().factions().values().forEach(f -> {
                JsonObject o = new JsonObject();
                o.addProperty("id", f.id());
                o.addProperty("name", f.name());
                fa.add(o);
            });
        }
        root.add("factions", fa);
        JsonArray pa = new JsonArray();
        if (CCNRRPMod.factions != null) {
            for (String pid : CCNRRPMod.factions.professionIds()) {
                CCNRRPMod.factions.findProfession(pid).ifPresent(def -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("id", pid);
                    o.addProperty("name", com.ccnrcom.rp.faction.FactionProfessions.idsSafeName(def));
                    o.addProperty("factionId", com.ccnrcom.rp.faction.FactionProfessions.factionId(def));
                    pa.add(o);
                });
            }
        }
        root.add("professions", pa);
        RpChannels.sendTo(player, new RpPackets.CharacterListS2C(root.toString()));
    }

    public void sendError(ServerPlayer player, String key, String... args) {
        RpChannels.sendTo(player, new RpPackets.ErrorS2C(key, args));
    }

    /** 服务端错误文案（键+参数拼装；键由客户端翻译渲染）。 */
    public static String tr(String key, Object... args) {
        return args.length == 0
                ? key
                : key + " "
                        + String.join(
                                " ",
                                java.util.Arrays.stream(args)
                                        .map(String::valueOf)
                                        .toList());
    }

    public static String sha256(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
