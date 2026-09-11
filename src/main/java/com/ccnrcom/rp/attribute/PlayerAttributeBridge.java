/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.attribute;

import com.ccnrcom.rp.attribute.AttributeProfile.Entry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 玩家属性薄适配层（MC 类型只在本类出现）：把 {@link AttributeProfile} 的解析结果写进在线玩家的属性。
 *
 * <p><b>解耦边界（为什么这样做）</b>：属性 id 用注册名寻址——原版属性与任何 mod 注册的属性走**同一条**代码路径，
 * 本类不 import 任何第三方 mod 类型；未注册的 id 在执行期跳过并 WARN（不是配置错误：mod 暂时没装而已）。
 * 另外只有 mod 适配（FirstAid）需要特殊处理，收敛在下方的私有内部类里，且全部调用都在 try/catch 内——
 * 与 {@code CorpseBridge}/{@code CamSceneBridge} 同一套"可选依赖、缺失降级"纪律。
 *
 * <p><b>幂等</b>：本 mod 施加的修饰名一律带 {@link #MODIFIER_PREFIX} 前缀，每次套用先 {@link #purge} 清掉旧修饰
 * 再加新的（确定性 UUID）。这样"换阵营/改配置/重连/重复部署"都不会叠加成第二份加成。
 *
 * <p><b>血量满状态</b>：原版由部署流程 {@code resetPlayerState} 的 {@code setHealth(getMaxHealth())} 保证；
 * FirstAid 把血量换成"按部位"制，故 {@link FirstAidAdapter} 同步缩放部位上限并补满（详见该类注释）。
 */
public final class PlayerAttributeBridge {
    private static final Logger LOGGER = LogManager.getLogger();

    /** 本 mod 修饰名前缀（识别与清理的唯一依据）。 */
    private static final String MODIFIER_PREFIX = "ccnr_rp:attr:";

    /** 原版血量基值：FirstAid 部位血量按"目标血量 / 20"的倍率翻译。 */
    private static final double VANILLA_BASE_HEALTH = 20.0;

    private PlayerAttributeBridge() {}

    /** 全部已注册属性 id（命令/管理界面提示用；含原版与各 mod）。 */
    public static List<String> registeredIds() {
        List<String> ids = new ArrayList<>();
        for (ResourceLocation rl : ForgeRegistries.ATTRIBUTES.getKeys()) {
            ids.add(rl.toString());
        }
        ids.sort(String::compareTo);
        return ids;
    }

    /** 配置里存在但当前未注册（mod 未装/写错）的属性 id 列表。 */
    public static List<String> unknownIds(List<Entry> entries) {
        List<String> unknown = new ArrayList<>();
        if (entries == null) {
            return unknown;
        }
        for (Entry e : entries) {
            ResourceLocation rl = ResourceLocation.tryParse(e.id());
            if (rl == null || ForgeRegistries.ATTRIBUTES.getValue(rl) == null) {
                unknown.add(e.id());
            }
        }
        return unknown;
    }

    /** 移除本 mod 施加的全部属性修饰（退场/换阵营/重新部署前调用；幂等）。 */
    public static void purge(ServerPlayer player) {
        if (player == null) {
            return;
        }
        for (AttributeInstance inst : player.getAttributes().getSyncableAttributes()) {
            for (AttributeModifier m : new ArrayList<>(inst.getModifiers())) {
                if (m.getName().startsWith(MODIFIER_PREFIX)) {
                    inst.removeModifier(m);
                }
            }
        }
    }

    /**
     * 套用阵营属性配置（先清理后施加，幂等）。返回实际生效条目数。
     * 未注册的属性跳过（含 FirstAid 等 mod 属性未装的情况），不影响其余条目。
     */
    public static int apply(ServerPlayer player, String factionId, List<Entry> entries) {
        if (player == null) {
            return 0;
        }
        purge(player);
        String fac = factionId == null ? "" : factionId;
        List<Entry> list = entries == null ? List.of() : entries;
        List<String> unknown = new ArrayList<>();
        int applied = 0;
        for (Entry e : list) {
            ResourceLocation rl = ResourceLocation.tryParse(e.id());
            Attribute attribute = rl == null ? null : ForgeRegistries.ATTRIBUTES.getValue(rl);
            if (attribute == null) {
                unknown.add(e.id());
                continue;
            }
            AttributeInstance inst = player.getAttribute(attribute);
            if (inst == null) {
                unknown.add(e.id());
                continue;
            }
            AttributeModifier.Operation op =
                    switch (e.operation()) {
                        case ADD -> AttributeModifier.Operation.ADDITION;
                        case MULTIPLY_BASE -> AttributeModifier.Operation.MULTIPLY_BASE;
                        case MULTIPLY_TOTAL -> AttributeModifier.Operation.MULTIPLY_TOTAL;
                    };
            inst.addPermanentModifier(new AttributeModifier(
                    modifierId(fac, e),
                    MODIFIER_PREFIX + fac + ":" + e.id() + ":" + e.operation().id(),
                    e.amount(),
                    op));
            applied++;
        }
        if (!unknown.isEmpty()) {
            LOGGER.warn("[CCNR-RP] 阵营 {} 的属性配置引用了未注册/不适用于玩家的属性（已跳过）：{}（装对应 mod 后生效）", fac, unknown);
        }
        // 血量：原版由属性修饰直接生效；FirstAid 走部位血量翻译（未安装时本调用为空操作）。
        // 未配置血量时传 -1 = 复位到 FirstAid 默认上限并回满（换到无属性阵营不会残留上一阵营的放大血量）。
        // 注意：即使一条属性都没配（list 为空）也必须走到这里——否则 FirstAid 玩家会带着上一局的残血/
        // 旧上限出门，破坏"出门是满状态的"。
        double healthRatio = -1;
        if (AttributeProfile.has(list, AttributeProfile.MAX_HEALTH)) {
            healthRatio = AttributeProfile.resolve(list, AttributeProfile.MAX_HEALTH, VANILLA_BASE_HEALTH)
                    / VANILLA_BASE_HEALTH;
        }
        FirstAidAdapter.applyHealth(player, healthRatio);
        return applied;
    }

    /** 确定性修饰 UUID：同一（阵营+属性+运算）恒定，重复套用不会叠加。 */
    private static UUID modifierId(String factionId, Entry e) {
        String key =
                MODIFIER_PREFIX + factionId + ":" + e.id() + ":" + e.operation().id();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * FirstAid 适配（可选 mod，modid=firstaid）：FirstAid 把原版血量换成 8 个部位各自的血量上限
     * （原版 {@code generic.max_health} 只用于显示钳制），所以"改血量"必须翻译成"缩放部位血量上限"。
     *
     * <p>为什么用反射而非编译期依赖：本 mod 不为一个可选适配引入新的 mods.toml 依赖/构建期 jar
     * （对齐 {@code CamSceneBridge} 的纯反射桥做法——缺失时零副作用、不影响 CI 取依赖）。反射面只有
     * capability 静态字段 + 部位公开字段 {@code initialMaxHealth} + 两个公开方法
     * （{@code setMaxHealth}/{@code heal}），任一步骤失败都只记一次 WARN 并保持 FirstAid 原血量
     * （不崩服、不半途改一半）。
     */
    private static final class FirstAidAdapter {
        private static Boolean present;
        /** 反射失败后置位：不再反复尝试（限频，避免每次部署刷日志）。 */
        private static boolean broken;

        private static java.lang.reflect.Method mSetMax;
        private static java.lang.reflect.Method mHeal;
        private static java.lang.reflect.Field fInitialMax;

        private FirstAidAdapter() {}

        static boolean available() {
            if (present == null) {
                boolean ok = false;
                try {
                    if (ModList.get().isLoaded("firstaid")) {
                        Class.forName(
                                "ichttt.mods.firstaid.api.CapabilityExtendedHealthSystem",
                                false,
                                PlayerAttributeBridge.class.getClassLoader());
                        ok = true;
                    }
                } catch (Throwable t) {
                    ok = false;
                }
                present = ok;
            }
            return present;
        }

        /**
         * 按倍率缩放全部部位血量上限并补满（出门即满状态）；{@code ratio <= 0} = 复位到 FirstAid 默认上限。
         * 未安装 FirstAid 时不做任何事（零副作用）。
         */
        static void applyHealth(ServerPlayer player, double ratio) {
            if (broken || !available()) {
                return;
            }
            // 不做"倍率=1 就跳过"的优化：部署时必须把部位血量**补满**（上一局的残血会随 NBT 保留），
            // 因此每次部署/登录都从基准重算上限并回满——这正是"出门是满状态的"。
            boolean reset = !(ratio > 0);
            try {
                init();
                Object model = damageModel(player);
                if (model == null) {
                    return;
                }
                int parts = 0;
                for (Object part : (Iterable<?>) model) {
                    if (part == null) {
                        continue;
                    }
                    // 基准取 FirstAid 自己的 initialMaxHealth（配置默认值）：每次都从基准算，
                    // 反复套用不会累积放大，换到无属性阵营也能复位。
                    int base = fInitialMax.getInt(part);
                    int next = reset ? base : Math.max(1, (int) Math.round(base * ratio));
                    mSetMax.invoke(part, next);
                    mHeal.invoke(part, (float) next, player, false); // 补满：出门即满状态
                    parts++;
                }
                LOGGER.info("[CCNR-RP] FirstAid 血量适配：{} 已套用到 {} 个部位并补满", reset ? "复位默认上限" : "倍率 " + ratio, parts);
            } catch (Throwable t) {
                broken = true;
                LOGGER.warn("[CCNR-RP] FirstAid 血量适配失败（保持 FirstAid 原血量，属性其余部分照常生效）: {}", t.toString());
            }
        }

        /** 取玩家的 FirstAid 伤害模型（capability）；未提供则返回 null。 */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private static Object damageModel(ServerPlayer player) throws Exception {
            Class<?> capClass = Class.forName("ichttt.mods.firstaid.api.CapabilityExtendedHealthSystem");
            Object cap = capClass.getField("INSTANCE").get(null);
            if (!(cap instanceof net.minecraftforge.common.capabilities.Capability)) {
                return null;
            }
            return player.getCapability((net.minecraftforge.common.capabilities.Capability) cap);
        }

        private static void init() throws Exception {
            if (mSetMax != null) {
                return;
            }
            Class<?> partClass = Class.forName("ichttt.mods.firstaid.api.damagesystem.AbstractDamageablePart");
            fInitialMax = partClass.getField("initialMaxHealth");
            mSetMax = partClass.getMethod("setMaxHealth", int.class);
            mHeal = partClass.getMethod(
                    "heal", float.class, net.minecraft.world.entity.player.Player.class, boolean.class);
        }
    }
}
