/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.profession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

/** P2 验收：NBT ↔ base64 字节往返（含自定义 NBT / 嵌套结构），无注册表依赖。 */
class NbtCodecTest {

    @Test
    void roundTripWithCustomAndNestedNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putString("custom", "ccnr-val");
        tag.putInt("level", 12);
        ListTag lore = new ListTag();
        lore.add(StringTag.valueOf("line1"));
        lore.add(StringTag.valueOf("line2"));
        tag.put("Lore", lore);
        CompoundTag child = new CompoundTag();
        child.putBoolean("enabled", true);
        tag.put("child", child);

        String b64 = ItemStackCodec.encodeNbt(tag);
        CompoundTag back = ItemStackCodec.decodeNbt(b64);
        assertEquals(tag, back);
        assertEquals("ccnr-val", back.getString("custom"));
        assertEquals(12, back.getInt("level"));
        assertEquals(2, back.getList("Lore", 8).size());
        assertEquals(true, back.getCompound("child").getBoolean("enabled"));
    }

    @Test
    void emptyCompoundSurvives() {
        CompoundTag tag = new CompoundTag();
        assertEquals(tag, ItemStackCodec.decodeNbt(ItemStackCodec.encodeNbt(tag)));
    }

    @Test
    void corruptBase64Throws() {
        assertThrows(IllegalStateException.class, () -> ItemStackCodec.decodeNbt("not-base64!!"));
    }
}
