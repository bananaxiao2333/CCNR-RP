/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.mixin;

import com.ccnrcom.rp.CCNRRPMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 观察者禁止入包（服务端）：better_looting 等模组的批拾取直接
 * {@link Inventory#add(int, ItemStack)} 绕过可取消的 {@code EntityItemPickupEvent}——
 * 本 mixin 在 Inventory.add 入口拦截：观察者（无在场身份的用户/征召兵）一律返回 false 拒绝入包。
 * better_looting 忽略 add 返回值，被拒后按"未添加"处理 → 原物品实体保持完整、原地不动（不消失不重复）。
 * 其余路径（命令/合成等对观察者发物品）同样被拒——观察者在本系统不持有物品。
 */
@Mixin(Inventory.class)
public abstract class InventoryObserverMixin {

    @Inject(method = "add(ILnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true)
    private void ccnr_rp$blockObserverPickup(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        Player owner = ((Inventory) (Object) this).player;
        if (owner instanceof ServerPlayer sp && CCNRRPMod.users != null) {
            String uuid = sp.getUUID().toString();
            boolean deployed =
                    CCNRRPMod.users.isAlive(uuid) || com.ccnrcom.rp.sequence.SequenceEngine.isConscripted(uuid);
            if (!deployed) {
                cir.setReturnValue(false); // 观察者：禁止物品入包
            }
        }
    }
}
