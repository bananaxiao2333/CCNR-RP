/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.profession;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * 职业装备 Loadout 的 JSON 编解码（纯逻辑，无 MC 依赖）。
 * JSON 形态：{"inventory":[{"slot":0,"item":"minecraft:sword","count":1,"nbt":"base64|空"}],
 * "armor":[...同], "offhand":{...}}。槽位为 MC 背包索引：0-35 背包，36 鞋 37 裤 38 胸甲 39 头盔，40 副手。
 */
public final class ProfessionJson {

    /** 单槽物品（nbt 为 CompoundTag 的 NbtIo 字节 base64；可为 null）。 */
    public record SlotItem(int slot, String item, int count, String nbtBase64) {}

    private ProfessionJson() {}

    public static JsonObject slotToJson(SlotItem s) {
        JsonObject o = new JsonObject();
        o.addProperty("slot", s.slot());
        o.addProperty("item", s.item());
        o.addProperty("count", s.count());
        if (s.nbtBase64() != null) {
            o.addProperty("nbt", s.nbtBase64());
        }
        return o;
    }

    public static SlotItem slotFromJson(JsonObject o, String path, List<String> errors) {
        if (!o.has("slot") || !o.has("item")) {
            errors.add(path + ": 缺少 slot/item");
            return null;
        }
        int slot = o.get("slot").getAsInt();
        int count = o.has("count") ? o.get("count").getAsInt() : 1;
        String nbt = o.has("nbt") && !o.get("nbt").isJsonNull() ? o.get("nbt").getAsString() : null;
        return new SlotItem(slot, o.get("item").getAsString(), count, nbt);
    }

    public static JsonArray listToJson(List<SlotItem> items) {
        JsonArray a = new JsonArray();
        items.forEach(i -> a.add(slotToJson(i)));
        return a;
    }

    public static List<SlotItem> listFromJson(JsonArray array, String path, List<String> errors) {
        List<SlotItem> out = new ArrayList<>();
        if (array == null) {
            return out;
        }
        for (int i = 0; i < array.size(); i++) {
            if (!array.get(i).isJsonObject()) {
                errors.add(path + "[" + i + "]: 不是对象");
                continue;
            }
            SlotItem s = slotFromJson(array.get(i).getAsJsonObject(), path + "[" + i + "]", errors);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    /** 校验 loadout 结构合法性（槽位边界、数量范围）。 */
    public static List<String> validate(JsonObject loadout) {
        List<String> errors = new ArrayList<>();
        if (loadout == null || !loadout.isJsonObject()) {
            errors.add("loadout: 不是 JSON 对象");
            return errors;
        }
        JsonArray inv = loadout.has("inventory") ? loadout.getAsJsonArray("inventory") : new JsonArray();
        JsonArray armor = loadout.has("armor") ? loadout.getAsJsonArray("armor") : new JsonArray();
        checkSlots(listFromJson(inv, "inventory", errors), 0, 35, "inventory", errors);
        checkSlots(listFromJson(armor, "armor", errors), 36, 40, "armor", errors);
        if (loadout.has("offhand")) {
            JsonElement oh = loadout.get("offhand");
            if (oh.isJsonNull()) {
                // 允许 null 表示无
            } else if (!oh.isJsonObject()) {
                errors.add("offhand: 不是对象");
            }
        }
        return errors;
    }

    private static void checkSlots(List<SlotItem> items, int min, int max, String path, List<String> errors) {
        for (SlotItem s : items) {
            if (s.slot() < min || s.slot() > max) {
                errors.add(path + ": 槽位越界 " + s.slot() + " (期望 " + min + "-" + max + ")");
            }
            if (s.count() < 1 || s.count() > 64) {
                errors.add(path + ": 数量非法 " + s.count());
            }
        }
    }

    public static JsonObject emptyLoadout() {
        JsonObject o = new JsonObject();
        o.add("inventory", new JsonArray());
        o.add("armor", new JsonArray());
        o.add("offhand", new JsonObject());
        return o;
    }
}
