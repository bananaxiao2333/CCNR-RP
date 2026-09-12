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
 * 背包处置薄适配（MC 类型只在本类出现）：玩家背包 = 0-35 背包 + 36-39 护甲 + 40 副手。
 *
 * <p><b>两种处置方式，语义互斥</b>（调用方按"是否死亡"二选一）：
 * <ul>
 *   <li>{@link #dropAll} —— <b>爆到地上</b>：死亡路径用。与原版 {@code Player.dropEquipment()} 同语义
 *       （带消失诅咒的直接销毁、其余落地），保证"死亡掉落"与原版一致。</li>
 *   <li>{@link #clearAll} —— <b>直接删除</b>：非死亡的一切退场（退役 / 疏散 / 旁观者兜底 / 归一化）用。
 *       不产生任何掉落物 —— 观察者不该把上一局的装备留在世界里。</li>
 * </ul>
 *
 * <p>调用时机：{@link #dropAll} 必须在死亡事件（LivingDeathEvent）内、原版掉落之前，或离线判死时立即调用。
 * 两个方法都幂等：处理完即清空槽位，重复调用只会遇到空背包（无落地、无副作用）。
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

    /**
     * 清空背包（全 41 槽）而**不**产生任何掉落物。返回被清掉的物品组数。
     *
     * <p>用在非死亡的退场路径：物品直接消失，不会在世界里留下掉落物，也不会进遗体。
     * 与 {@link #dropAll} 相对，二者是"死亡爆一地 / 其它直接删"的分工（docs/05 §3）。
     */
    public static int clearAll(ServerPlayer player) {
        if (player == null) {
            return 0;
        }
        Inventory inv = player.getInventory();
        int cleared = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (!inv.getItem(i).isEmpty()) {
                cleared++;
                inv.setItem(i, ItemStack.EMPTY);
            }
        }
        if (cleared > 0 && player.connection != null) {
            try {
                player.inventoryMenu.broadcastChanges(); // 在线时立即看到背包已空
            } catch (Throwable t) {
                LOGGER.debug("[CCNR-RP] 观察者背包同步跳过（连接不可用）", t);
            }
        }
        return cleared;
    }
}
