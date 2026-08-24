/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.profession;

import com.ccnrcom.rp.profession.ProfessionJson.SlotItem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Base64;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.item.ItemStack;

/** ItemStack ↔ SlotItem（含完整 NBT，base64 序列化）。仅编译期依赖 NBT 类（运行时必定存在）。 */
public final class ItemStackCodec {

    private ItemStackCodec() {}

    public static SlotItem fromStack(int slot, ItemStack stack) {
        String nbt = null;
        if (stack.getTag() != null) {
            CompoundTag full = stack.save(new CompoundTag());
            nbt = encodeNbt(full);
        }
        net.minecraft.resources.ResourceLocation key =
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return new SlotItem(slot, key == null ? "minecraft:air" : key.toString(), stack.getCount(), nbt);
    }

    public static ItemStack toStack(SlotItem s) {
        if (s.nbtBase64() != null) {
            return ItemStack.of(decodeNbt(s.nbtBase64()));
        }
        // 无 NBT：按 id/count 构造（注册名不在此校验，交给 MC 处理为 air）
        ItemStack stack = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getOptional(net.minecraft.resources.ResourceLocation.tryParse(s.item()))
                .orElse(net.minecraft.world.level.block.Blocks.AIR.asItem()));
        stack.setCount(Math.min(Math.max(s.count(), 1), 64));
        return stack;
    }

    /** NBT → base64（NbtIo 字节，独立可测，无注册表依赖）。 */
    public static String encodeNbt(CompoundTag tag) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            NbtIo.write(tag, new DataOutputStream(bos));
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("NBT 序列化失败", e);
        }
    }

    /** base64 → NBT。 */
    public static CompoundTag decodeNbt(String b64) {
        try {
            return NbtIo.read(new DataInputStream(
                    new ByteArrayInputStream(Base64.getDecoder().decode(b64))));
        } catch (Exception e) {
            throw new IllegalStateException("NBT 反序列化失败", e);
        }
    }
}
