/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.profession;

import com.ccnrcom.rp.profession.ProfessionJson.SlotItem;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Loadout 采集与发放（MC 薄适配层）。槽位：0-35 背包，36 鞋 37 裤 38 胸甲 39 头盔，40 副手。 */
public final class LoadoutManager {

    private LoadoutManager() {}

    /** 从玩家背包/护甲/副手采集装备 JSON（--full 含全部背包，默认仅快捷栏 0-8）。 */
    public static JsonObject capture(ServerPlayer player, boolean full) {
        Inventory inv = player.getInventory();
        List<SlotItem> inventory = new ArrayList<>();
        int max = full ? 35 : 8;
        for (int i = 0; i <= max; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) {
                inventory.add(ItemStackCodec.fromStack(i, s));
            }
        }
        List<SlotItem> armor = new ArrayList<>();
        // 36 鞋 37 裤 38 胸甲 39 头盔
        for (int i = 36; i <= 39; i++) {
            if (!inv.getItem(i).isEmpty()) {
                armor.add(ItemStackCodec.fromStack(i, inv.getItem(i)));
            }
        }
        ItemStack offhand = inv.getItem(40);
        JsonObject o = new JsonObject();
        o.add("inventory", ProfessionJson.listToJson(inventory));
        o.add("armor", ProfessionJson.listToJson(armor));
        if (offhand.isEmpty()) {
            o.add("offhand", new JsonObject());
        } else {
            o.add("offhand", ProfessionJson.slotToJson(ItemStackCodec.fromStack(40, offhand)));
        }
        return o;
    }

    /** 按 loadout 发放到玩家（不清理原有装备——保留决定权给调用方）。 */
    public static void apply(ServerPlayer player, JsonObject loadout) {
        Inventory inv = player.getInventory();
        if (loadout.has("inventory")) {
            for (SlotItem s :
                    ProfessionJson.listFromJson(loadout.getAsJsonArray("inventory"), "inventory", new ArrayList<>())) {
                inv.setItem(s.slot(), ItemStackCodec.toStack(s));
            }
        }
        if (loadout.has("armor")) {
            for (SlotItem s :
                    ProfessionJson.listFromJson(loadout.getAsJsonArray("armor"), "armor", new ArrayList<>())) {
                inv.setItem(s.slot(), ItemStackCodec.toStack(s));
            }
        }
        if (loadout.has("offhand")) {
            JsonElement oh = loadout.get("offhand");
            if (oh.isJsonObject() && oh.getAsJsonObject().size() > 0) {
                SlotItem s = ProfessionJson.slotFromJson(oh.getAsJsonObject(), "offhand", new ArrayList<>());
                if (s != null) {
                    inv.setItem(40, ItemStackCodec.toStack(s));
                }
            }
        }
    }
}
