/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.corpse;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Corpse（henkelmax，modid=corpse）可选联动桥。
 * 未安装时 isAvailable()=false，调用方降级为原生死亡处理；已安装时在玩家位置生成可搜刮遗体。
 * 编译期依赖来自 libs/（fileTree）；仅在执行到方法体时才解析 Corpse 类型，缺失不会导致类加载失败。
 */
public final class CorpseBridge {
    private static final Logger LOGGER = LogManager.getLogger();

    private CorpseBridge() {}

    public static boolean available() {
        return ModList.get().isLoaded("corpse");
    }

    /**
     * 在玩家当前位置生成遗体（复制背包/护甲/副手）。
     *
     * @return true=遗体已生成；false=未安装 Corpse 或生成失败（调用方降级）
     */
    public static boolean spawnCorpse(ServerPlayer player) {
        if (!available()) {
            return false;
        }
        try {
            if (!(player.level() instanceof ServerLevel level)) {
                return false;
            }
            de.maxhenkel.corpse.corelib.death.Death death = de.maxhenkel.corpse.corelib.death.Death.fromPlayer(player);
            de.maxhenkel.corpse.entities.CorpseEntity corpse =
                    de.maxhenkel.corpse.entities.CorpseEntity.createFromDeath(player, death);
            level.addFreshEntity(corpse);
            LOGGER.info("[CCNR-RP] 已生成遗体: {} @ {}", player.getGameProfile().getName(), player.blockPosition());
            return true;
        } catch (Throwable t) {
            LOGGER.error("[CCNR-RP] 遗体生成失败（降级为原生死亡）", t);
            return false;
        }
    }
}
