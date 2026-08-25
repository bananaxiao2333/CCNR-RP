/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.corpse;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Corpse（henkelmax，modid=corpse）可选联动桥。
 * 未安装时 isAvailable()=false，调用方降级为原生死亡处理；已安装时在玩家位置生成可搜刮遗体。
 * 编译期依赖来自 libs/（fileTree）；仅在执行到方法体时才解析 Corpse 类型，缺失不会导致类加载失败。
 *
 * <p>死亡身份注入：Corpse 模组会在玩家自然死亡（LivingDropsEvent）时自动生成一具遗体（默认用玩家 UUID/姓名），
 * 尸体名仍为玩家名。本桥在 {@code PlayerDeathEvent}（HIGH 优先级，先于 Corpse 模组生成遗体）
 * 把 Death 的 playerName 改写为「死亡角色」名——尸体名称显示角色名（Corpse of 角色名），不再残留玩家名。
 */
public final class CorpseBridge {
    private static final Logger LOGGER = LogManager.getLogger();

    private CorpseBridge() {}

    private static Boolean corpsePresent = null;

    /** 死亡时暂存的角色名，供 PlayerDeathEvent 注入遗体。 */
    private record DeathChar(String charName) {}

    private static final Map<UUID, DeathChar> DEATH_CHARS = new ConcurrentHashMap<>();

    private static boolean hookRegistered = false;

    public static boolean available() {
        if (corpsePresent == null) {
            boolean ok = false;
            try {
                if (ModList.get().isLoaded("corpse")) {
                    Class.forName(
                            "de.maxhenkel.corpse.entities.CorpseEntity", false, CorpseBridge.class.getClassLoader());
                    ok = true;
                }
            } catch (Throwable t) {
                ok = false;
            }
            corpsePresent = ok;
        }
        return corpsePresent;
    }

    /** 记录玩家自然死亡时的角色名（在 LivingDeathEvent 阶段捕获，供 PlayerDeathEvent 注入遗体）。 */
    public static void captureDeathChar(UUID playerUuid, String charName) {
        if (playerUuid != null) {
            DEATH_CHARS.put(playerUuid, new DeathChar(charName == null ? "" : charName));
        }
    }

    /** 注册 PlayerDeathEvent 注入钩子（仅 Corpse 模组存在时生效，幂等）。 */
    public static void registerDeathHook() {
        if (!available() || hookRegistered) {
            return;
        }
        hookRegistered = true;
        try {
            DeathHookRegistrar.register();
        } catch (Throwable t) {
            LOGGER.error("[CCNR-RP] 遗体身份注入钩子注册失败（遗体命名降级）", t);
        }
    }

    /** 清空死亡身份暂存（服务端停止/世界切换时调用，防跨世界残留）。 */
    public static void clearCaptured() {
        DEATH_CHARS.clear();
    }

    /**
     * 在玩家当前位置生成遗体（复制背包/护甲/副手）。用于非自然死亡（命令/退役/离线），
     * 此时 Corpse 模组不会自动生成，由本桥补位。charName=死亡角色名（尸体名字 tag 显示）；
     * skinHash=角色皮肤哈希（派生 UUID 写入尸体，客户端按「哈希派生 UUID → 角色皮肤」直接展示）。
     *
     * @return true=遗体已生成；false=未安装 Corpse 或生成失败（调用方降级）
     */
    public static boolean spawnCorpse(ServerPlayer player, String charName, String skinHash) {
        if (!available()) {
            return false;
        }
        try {
            CorpseSpawner.spawn(player, charName == null ? "" : charName, skinHash == null ? "" : skinHash);
            return true;
        } catch (Throwable t) {
            LOGGER.error("[CCNR-RP] 遗体生成失败（降级为原生死亡）", t);
            return false;
        }
    }

    /**
     * 独立内部类：仅此处引用 Corpse 类型。类验证/链接错误发生在 spawnCorpse 的 try 内，
     * 可被捕获——避免"未装 Corpse 模组时 NoClassDefFoundError 逃逸崩服"。
     */
    private static final class CorpseSpawner {
        static void spawn(ServerPlayer player, String charName, String skinHash) {
            if (!(player.level() instanceof ServerLevel level)) {
                return;
            }
            de.maxhenkel.corpse.corelib.death.Death death = de.maxhenkel.corpse.corelib.death.Death.fromPlayer(player);
            de.maxhenkel.corpse.entities.CorpseEntity corpse =
                    de.maxhenkel.corpse.entities.CorpseEntity.createFromDeath(player, death);
            if (!charName.isBlank()) {
                corpse.setCorpseName(charName);
                // 自定义名字：DataWatcher 随实体数据包同步到客户端 → 尸体头顶显示角色名
                corpse.setCustomName(net.minecraft.network.chat.Component.literal(charName));
            }
            if (!skinHash.isBlank()) {
                // 皮肤按哈希写入：派生 UUID 进尸体数据（持久化），客户端按同一派生规则直接查角色皮肤
                corpse.setCorpseUUID(
                        UUID.nameUUIDFromBytes(("ccnr-skin:" + skinHash).getBytes(StandardCharsets.UTF_8)));
            }
            level.addFreshEntity(corpse);
            LOGGER.info(
                    "[CCNR-RP] 已生成遗体: {}（角色 {}，皮肤哈希 {}） @ {}",
                    player.getGameProfile().getName(),
                    charName,
                    skinHash.substring(0, Math.min(12, skinHash.length())),
                    player.blockPosition());
        }
    }

    /**
     * PlayerDeathEvent 注入钩子：在 Corpse 模组生成遗体前，把 Death 的身份改为死亡角色的身份。
     * 这样 Corpse 模组自动生成的遗体从创建起就携带角色身份（角色名 + 哈希派生 UUID），客户端即刻按角色皮肤渲染。
     */
    private static final class DeathHookRegistrar {
        static void register() {
            MinecraftForge.EVENT_BUS.addListener(
                    EventPriority.HIGH,
                    false,
                    de.maxhenkel.corpse.corelib.death.PlayerDeathEvent.class,
                    DeathHookRegistrar::onPlayerDeath);
        }

        private static void onPlayerDeath(de.maxhenkel.corpse.corelib.death.PlayerDeathEvent event) {
            var player = event.getPlayer();
            if (player == null) {
                return;
            }
            DeathChar dc = DEATH_CHARS.remove(player.getUUID());
            if (dc == null) {
                return; // 非本桥管理的死亡（征召/无角色）沿用 Corpse 默认行为
            }
            var death = event.getDeath();
            try {
                java.lang.reflect.Field fUuid =
                        de.maxhenkel.corpse.corelib.death.Death.class.getDeclaredField("playerUUID");
                fUuid.setAccessible(true);
                UUID derived = UUID.nameUUIDFromBytes(("ccnr-char:" + dc.charName()).getBytes(StandardCharsets.UTF_8));
                fUuid.set(death, derived);
                java.lang.reflect.Field fName =
                        de.maxhenkel.corpse.corelib.death.Death.class.getDeclaredField("playerName");
                fName.setAccessible(true);
                fName.set(death, dc.charName());
                LOGGER.info(
                        "[CCNR-RP] 遗体身份注入（死亡角色）: {} → 角色 {}",
                        player.getGameProfile().getName(),
                        dc.charName());
            } catch (Throwable t) {
                LOGGER.error("[CCNR-RP] 遗体身份注入失败（尸体将沿用玩家身份）", t);
            }
        }
    }
}
