/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.character;

import com.ccnrcom.rp.status.CharacterStatus;
import com.ccnrcom.rp.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 角色存储（内存 + JSON 持久化，原子写）。
 * 纯平台：路径由调用方注入（服务端为 world/ccnr_rp/characters.json），可用临时目录直接测试。
 */
public final class CharacterStore {
    private final Path file;
    private final List<CharacterData> characters = new ArrayList<>();

    public CharacterStore(Path worldDir) {
        this.file = worldDir.resolve("characters.json");
    }

    public Path file() {
        return file;
    }

    /** 从磁盘加载（损坏 → 保留 .bak 并空载）。 */
    public void load() {
        characters.clear();
        JsonUtil.readObject(file).ifPresent(root -> {
            if (root.has("characters")) {
                JsonArray a = root.getAsJsonArray("characters");
                for (int i = 0; i < a.size(); i++) {
                    if (a.get(i).isJsonObject()) {
                        characters.add(CharacterData.fromJson(a.get(i).getAsJsonObject()));
                    }
                }
            }
        });
    }

    public boolean save() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonArray a = new JsonArray();
        characters.forEach(c -> a.add(c.toJson()));
        root.add("characters", a);
        return JsonUtil.atomicWrite(file, root);
    }

    /** 从磁盘重载（保留内存里对文件的修改请先 save）。 */
    public void reloadFromDisk() {
        load();
    }

    public List<CharacterData> all() {
        return List.copyOf(characters);
    }

    public List<CharacterData> ofPlayer(String playerUuid) {
        return characters.stream()
                .filter(c -> c.playerUuid().equals(playerUuid))
                .toList();
    }

    public Optional<CharacterData> find(String id) {
        return characters.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    public Optional<CharacterData> findAlive(String playerUuid) {
        return characters.stream()
                .filter(c -> c.playerUuid().equals(playerUuid) && c.status() == CharacterStatus.ALIVE)
                .findFirst();
    }

    /** 创建角色（服务端校验默认 OBSERVING；id 由管理层生成）。 */
    public CharacterData create(
            String playerUuid,
            String name,
            String factionId,
            String professionId,
            String background,
            int maxPerPlayer,
            java.util.function.Supplier<UUID> idSupplier) {
        String id = idSupplier.get().toString();
        CharacterData c = new CharacterData(
                id,
                playerUuid,
                name,
                factionId,
                professionId,
                background,
                null,
                null,
                CharacterStatus.OBSERVING,
                0,
                0,
                0,
                new java.util.LinkedHashMap<>(),
                "none",
                System.currentTimeMillis());
        characters.add(c);
        return c;
    }

    public boolean delete(String id) {
        return characters.removeIf(c -> c.id().equals(id));
    }

    /** 替换角色记录并持久化（返回 true=变更成功）。 */
    public boolean update(CharacterData updated) {
        for (int i = 0; i < characters.size(); i++) {
            if (characters.get(i).id().equals(updated.id())) {
                characters.set(i, updated);
                return true;
            }
        }
        return false;
    }

    public int countOf(String playerUuid) {
        return (int) characters.stream()
                .filter(c -> c.playerUuid().equals(playerUuid))
                .count();
    }
}
