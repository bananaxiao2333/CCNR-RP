/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** 客户端 S2C 包处理（仅客户端侧调用，网络线程 enqueue 到主线程后执行）。 */
public final class ClientPacketHandlers {

    private ClientPacketHandlers() {}

    public static void onCharacterList(String payload) {
        ClientCharacterState.setList(payload);
        CharacterManagementScreen.refreshIfOpen();
    }

    public static void onCharacterUpdate(JsonObject data) {
        ClientCharacterState.upsert(data);
        CharacterManagementScreen.refreshIfOpen();
    }

    public static void onCharacterRemove(String charId) {
        ClientCharacterState.remove(charId);
        CharacterManagementScreen.refreshIfOpen();
    }

    public static void onSkinSync(String charId, byte[] data, String hash) {
        SkinCache.store(charId, data, hash);
        CharacterManagementScreen.refreshIfOpen();
    }

    public static void onAnimation(String payload) {
        ClientAnimationPlayer.play(payload);
    }

    public static void onXp(String charId, long xp, int level) {
        ClientCharacterState.setXp(charId, xp, level);
        CharacterManagementScreen.refreshIfOpen();
    }

    public static void onError(String messageKey, String[] args) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable(messageKey, (Object[]) args), false);
        }
    }
}
