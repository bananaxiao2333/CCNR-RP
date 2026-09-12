/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.corpse;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Corpse（henkelmax，modid=corpse）可选联动桥（非入侵：不改 corpse 模组本体/jar，不重打尸体）。
 * 未安装时 isAvailable()=false，调用方降级为原生死亡处理（物品按原版正常爆出）；
 * 已安装时在玩家位置生成可搜刮遗体。编译期依赖来自 libs/（fileTree）；
 * 仅在执行到方法体时才解析 Corpse 类型，缺失不会导致类加载失败。
 *
 * <p>死亡身份注入：Corpse 模组会在玩家自然死亡（PlayerDeathEvent）时自动生成一具遗体
 * （默认用玩家 UUID/姓名）。本桥在 {@code PlayerDeathEvent}（HIGH 优先级，先于 Corpse 模组生成遗体）
 * 保持 playerUUID 为玩家真实 UUID——客户端按 UUID 从 tab 列表解析到玩家档案（LittleSkin 纹理），
 * 尸体即渲染玩家本人皮肤；playerName 改写为「职位 + 玩家名」（如「警察 小明」）。
 *
 * <p>遗体名字牌：遗体加入世界时（{@link EntityJoinLevelEvent}）把 corpseName 移到 customName 并置可见。
 * 1.20.1 名字牌仅 customNameVisible=true 才渲染；corpseName 置空后 vanilla getDisplayName() 回落
 * customName，头顶名字与搜尸 GUI 标题一致显示「职位 + 玩家名」，不再拼 "Corpse of " 前缀。
 */
public final class CorpseBridge {
    private static final Logger LOGGER = LogManager.getLogger();

    private CorpseBridge() {}

    private static Boolean corpsePresent = null;

    /** 死亡时暂存的玩家 UUID（标记本桥管理的自然死亡，供 PlayerDeathEvent 注入遗体身份）。 */
    private static final Set<UUID> DEATH_MARKERS = ConcurrentHashMap.newKeySet();

    /**
     * 待抑制遗体的玩家（UUID → 过期时刻）。用于世界规则 {@code keepInventory=true}（"死亡不掉落"）：
     * 该规则下本 mod 要求"一点装备都不留"，**连遗体也不生成**。
     *
     * <p><b>为什么只能在实体加入世界时拦</b>：Corpse 的 {@code PlayerDeathEvent} 虽继承 Forge 的
     * {@code Event}，但**没有 {@code @Cancelable}**（只有单向的 {@code storeDeath()}/{@code removeDrops()}，
     * 读它们的 getter 还是包私有），{@code ServerConfig} 里也没有"是否生成遗体"的开关，而
     * {@code de.maxhenkel.corpse.events.DeathEvents#playerDeath} 是**无条件**创建
     * {@code CorpseEntity} 并 {@code addFreshEntity} 的。唯一可拦的地方就是遗体加入世界的那一刻
     * （{@code EntityJoinLevelEvent} 可取消）。
     *
     * <p>带过期时间是为了不误杀后续的正常遗体：标记只在"该玩家这次 keepInventory 死亡"后的
     * {@link #SUPPRESS_TTL_MS} 内有效，且命中一次即消费掉。
     */
    private static final Map<UUID, Long> SUPPRESSED_CORPSES = new ConcurrentHashMap<>();

    /** 抑制标记的有效期：远大于"死亡→遗体入世界"的间隔（同 tick ~ 2 tick），又短到不会影响下一次死亡。 */
    private static final long SUPPRESS_TTL_MS = 10_000L;

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

    /** 记录玩家自然死亡（在 LivingDeathEvent 阶段捕获，供 PlayerDeathEvent 注入遗体身份）。 */
    public static void captureDeath(UUID playerUuid) {
        if (playerUuid != null) {
            DEATH_MARKERS.add(playerUuid);
        }
    }

    /**
     * 请求抑制该玩家接下来的遗体生成（世界规则 {@code keepInventory=true} 的死亡）。
     * 由 {@code StatusManager} 在死亡事件内调用，随后 {@code EntityJoinLevelEvent} 会拦下那具遗体。
     */
    public static void suppressCorpse(UUID playerUuid) {
        if (playerUuid != null) {
            SUPPRESSED_CORPSES.put(playerUuid, System.currentTimeMillis() + SUPPRESS_TTL_MS);
        }
    }

    /**
     * 该遗体是否应当被拦下（命中即消费标记）。过期标记一律不抑制。
     * 任何异常都返回 false —— 宁可留下遗体，也不能误删玩家的正常遗体。
     */
    private static boolean consumeSuppressed(de.maxhenkel.corpse.entities.CorpseEntity corpse) {
        try {
            var death = corpse.getDeath();
            UUID owner = death == null ? null : death.getPlayerUUID();
            if (owner == null) {
                return false;
            }
            Long until = SUPPRESSED_CORPSES.remove(owner);
            return until != null && System.currentTimeMillis() <= until;
        } catch (Throwable t) {
            LOGGER.debug("[CCNR-RP] 遗体抑制判定跳过", t);
            return false;
        }
    }

    /** 注册 Corpse 联动钩子（仅 Corpse 模组存在时生效，幂等）：死亡身份注入 + 遗体名字牌。 */
    public static void registerHooks() {
        if (!available() || hookRegistered) {
            return;
        }
        hookRegistered = true;
        try {
            DeathHookRegistrar.register();
            JoinNameplateHook.register();
        } catch (Throwable t) {
            LOGGER.error("[CCNR-RP] 遗体联动钩子注册失败（尸体降级为原版行为）", t);
        }
    }

    /** 清空死亡身份暂存与遗体抑制标记（服务端停止/世界切换时调用，防跨世界残留）。 */
    public static void clearCaptured() {
        DEATH_MARKERS.clear();
        SUPPRESSED_CORPSES.clear();
    }

    /** 尸体显示名：职位 + 玩家名（如「警察 小明」）；无职位/读取失败时仅玩家名。 */
    private static String displayName(ServerPlayer player) {
        if (player == null) {
            return "";
        }
        String playerName = player.getName().getString();
        String prof = professionName(player);
        return prof.isBlank() ? playerName : prof + " " + playerName;
    }

    /** 玩家当前职位显示名（factions.json professions）；无职位返回空串。 */
    private static String professionName(ServerPlayer player) {
        try {
            var users = com.ccnrcom.rp.CCNRRPMod.users;
            var factions = com.ccnrcom.rp.CCNRRPMod.factions;
            if (users == null || factions == null) {
                return "";
            }
            String pid = users.professionId(player.getUUID().toString());
            if (pid == null || pid.isBlank()) {
                return "";
            }
            var def = factions.findProfession(pid).orElse(null);
            return def == null ? "" : com.ccnrcom.rp.faction.FactionProfessions.idsSafeName(def);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 在玩家当前位置生成遗体（复制背包/护甲/副手）。用于非自然死亡（命令/退役/离线），
     * 此时 Corpse 模组不会自动生成，由本桥补位。charName=玩家名（预留，实际显示「职位 + 玩家名」）。
     * 未安装 Corpse 时返回 false——调用方跳过遗体生成，物品按原版正常爆出。
     *
     * @return true=遗体已生成；false=未安装 Corpse 或生成失败（调用方降级为原版爆装备）
     */
    public static boolean spawnCorpse(ServerPlayer player, String charName) {
        if (!available()) {
            return false;
        }
        try {
            CorpseSpawner.spawn(player, charName == null ? "" : charName);
            return true;
        } catch (Throwable t) {
            LOGGER.error("[CCNR-RP] 遗体生成失败（降级为原生死亡爆装备）", t);
            return false;
        }
    }

    /**
     * 独立内部类：仅此处引用 Corpse 类型。类验证/链接错误发生在 spawnCorpse 的 try 内，
     * 可被捕获——避免"未装 Corpse 模组时 NoClassDefFoundError 逃逸崩服"。
     */
    private static final class CorpseSpawner {
        static void spawn(ServerPlayer player, String charName) {
            if (!(player.level() instanceof ServerLevel level)) {
                return;
            }
            de.maxhenkel.corpse.corelib.death.Death death = de.maxhenkel.corpse.corelib.death.Death.fromPlayer(player);
            de.maxhenkel.corpse.entities.CorpseEntity corpse =
                    de.maxhenkel.corpse.entities.CorpseEntity.createFromDeath(player, death);
            // 身份名 = 职位 + 玩家名（如「警察 小明」）；名字牌由 JoinNameplateHook 统一处理
            String displayName = displayName(player);
            if (!displayName.isBlank()) {
                corpse.setCorpseName(displayName);
            }
            level.addFreshEntity(corpse);
            LOGGER.info(
                    "[CCNR-RP] 已生成遗体: {}（显示 {}） @ {}",
                    player.getGameProfile().getName(),
                    displayName,
                    player.blockPosition());
        }
    }

    /**
     * PlayerDeathEvent 注入钩子：在 Corpse 模组生成遗体前，把 Death 的身份改为死亡玩家的身份。
     * playerUUID 保持玩家真实 UUID——客户端按 UUID 从 tab 列表解析到玩家档案（LittleSkin 纹理），
     * 尸体渲染玩家本人皮肤；playerName 改为「职位 + 玩家名」。
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
            if (!DEATH_MARKERS.remove(player.getUUID())) {
                return; // 非本桥管理的死亡（征召/无角色）沿用 Corpse 默认行为
            }
            var death = event.getDeath();
            try {
                // 皮肤：保持玩家真实 UUID（哈希派生 UUID 无档案 → 客户端渲染默认史蒂夫纹理）
                java.lang.reflect.Field fUuid =
                        de.maxhenkel.corpse.corelib.death.Death.class.getDeclaredField("playerUUID");
                fUuid.setAccessible(true);
                fUuid.set(death, player.getUUID());
                // 名字：职位 + 玩家名（如「警察 小明」）；无职位时仅玩家名
                String display = displayName(player);
                java.lang.reflect.Field fName =
                        de.maxhenkel.corpse.corelib.death.Death.class.getDeclaredField("playerName");
                fName.setAccessible(true);
                fName.set(death, display);
                LOGGER.info(
                        "[CCNR-RP] 遗体身份注入（玩家身份）: {} → {}",
                        player.getGameProfile().getName(),
                        display);
            } catch (Throwable t) {
                LOGGER.error("[CCNR-RP] 遗体身份注入失败（尸体将沿用玩家身份）", t);
            }
        }
    }

    /**
     * 遗体入世界钩子：
     * <ol>
     *   <li><b>抑制</b>：世界规则 {@code keepInventory=true} 的死亡不该留遗体 —— `EntityJoinLevelEvent`
     *       是唯一可拦点，取消即"不生成"（见 {@link #SUPPRESSED_CORPSES} 的说明）；</li>
     *   <li><b>名字牌</b>：把 corpseName（职位 + 玩家名）移到 customName 并置可见。1.20.1 名字牌仅当
     *       customNameVisible=true 才渲染；corpseName 置空后 vanilla getDisplayName() 回落 customName，
     *       头顶名字与搜尸 GUI 标题一致显示「职位 + 玩家名」，不再出现 "Corpse of " 前缀。</li>
     * </ol>
     */
    private static final class JoinNameplateHook {
        static void register() {
            MinecraftForge.EVENT_BUS.addListener(
                    EventPriority.LOW, false, EntityJoinLevelEvent.class, JoinNameplateHook::onJoin);
        }

        private static void onJoin(EntityJoinLevelEvent event) {
            try {
                if (!(event.getEntity() instanceof de.maxhenkel.corpse.entities.CorpseEntity corpse)) {
                    return;
                }
                if (consumeSuppressed(corpse)) {
                    event.setCanceled(true); // keepInventory 死亡：不留遗体（其背包已被直接清空）
                    LOGGER.info("[CCNR-RP] keepInventory=true：已抑制该次死亡的遗体生成");
                    return;
                }
                String name = corpse.getCorpseName();
                if (name == null || name.isBlank()) {
                    return;
                }
                corpse.setCorpseName("");
                corpse.setCustomName(Component.literal(name));
                corpse.setCustomNameVisible(true);
            } catch (Throwable t) {
                LOGGER.debug("[CCNR-RP] 遗体入世界处理跳过", t);
            }
        }
    }
}
