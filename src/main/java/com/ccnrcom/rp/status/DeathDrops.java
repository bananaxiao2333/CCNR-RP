/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.status;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 死亡背包处置薄适配（MC 类型只在本类出现）：把玩家背包（0-35 背包 + 36-39 护甲 + 40 副手）全部爆出并清空。
 *
 * <p>与原版 {@code Player.dropEquipment()} 的差异：原版先 {@code destroyVanishingCursedItems()} 再 {@code inventory.dropAll()}，
 * 本类按同样语义逐槽处理（带消失诅咒的直接销毁、其余落地），保证"死亡掉落"的行为与原版一致。
 *
 * <p>调用时机：必须在死亡事件（LivingDeathEvent）内、原版掉落之前，或离线判死时立即调用。
 * 幂等：处理完即清空槽位；重复调用只会遇到空背包（无落地、无副作用）。
 */
public final class DeathDrops {

    private static final Logger LOGGER = LogManager.getLogger();

    private DeathDrops() {}

    /**
     * 爆出并清空背包。返回落地物品组数（销毁的消失诅咒物品不计入）。
     * 在线玩家会同步背包变化给客户端（死亡界面/旁观视角下立即看到背包已空）。
     */
    public static int dropAll(ServerPlayer player) {
        if (player == null) {
            return 0;
        }
        Inventory inv = player.getInventory();
        int dropped = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            inv.setItem(i, ItemStack.EMPTY);
            if (stack.getEnchantmentLevel(Enchantments.VANISHING_CURSE) > 0) {
                continue; // 消失诅咒：与原版一致直接销毁，不落地
            }
            player.drop(stack, true, false);
            dropped++;
        }
        if (dropped > 0 && player.connection != null) {
            try {
                player.inventoryMenu.broadcastChanges(); // 背包变化同步（在线时立即可见）
            } catch (Throwable t) {
                // 掉线判死时连接可能已在收尾：同步失败不影响"物品已落地 + 背包已清空"这一权威结果
                LOGGER.debug("[CCNR-RP] 死亡背包同步跳过（连接不可用）", t);
            }
        }
        return dropped;
    }
}
