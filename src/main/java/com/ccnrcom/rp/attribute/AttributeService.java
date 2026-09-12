/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.attribute;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.attribute.AttributeProfile.Entry;
import com.ccnrcom.rp.status.CharacterStatus;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 阵营属性服务（指挥层）：声明"谁在什么时候套用阵营属性"，具体写入交给 {@link PlayerAttributeBridge} 薄适配。
 *
 * <p>套用时机（唯一入口，避免散落调用点）：
 * <ol>
 *   <li><b>部署</b>（{@code SpawnFramework.applyDeployCore}）：清空背包后、{@code resetPlayerState} 前套用 →
 *       满状态按新上限计算（"出门是满状态的"）；</li>
 *   <li><b>登录</b>（{@code CCNRRPMod.onPlayerLoggedIn}）：在场玩家重连后恢复属性（永久修饰本来会随存档保留，
 *       这里再套一次保证与当前配置一致）；</li>
 *   <li><b>配置保存</b>（管理面板/命令）：对该阵营在线成员立即生效（服务端权威 + 所见即所得）；</li>
 *   <li><b>退场</b>（死亡/判死/退役）：{@link #clear} 移除本 mod 施加的修饰（观察者不带上一局的加成）。</li>
 * </ol>
 *
 * <p>不做缓存：部署/登录/保存都是低频事件，阵营属性条目上限 {@link AttributeProfile#MAX_ENTRIES}，
 * 直接从阵营配置解析比维护一份需要失效的镜像更安全（docs/01 §9.3：缓存不得成为第二权威源）。
 * 解析错误按"阵营+错误"去重记一次日志，避免每次部署刷屏。
 */
public final class AttributeService {
    private static final Logger LOGGER = LogManager.getLogger();

    /** 已记录过的解析问题（键=阵营|消息；上限=阵营数级别，天然有界）。 */
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private AttributeService() {}

    /** 某阵营的属性配置（解析自阵营配置当前内容；未配置返回空列表）。 */
    public static List<Entry> entriesFor(String factionId) {
        if (factionId == null || factionId.isBlank() || CCNRRPMod.factions == null) {
            return List.of();
        }
        AttributeProfile.ParseResult parsed = AttributeProfile.parse(CCNRRPMod.factions.factionAttributes(factionId));
        parsed.warnings().forEach(w -> logOnce("faction:" + factionId, w));
        parsed.errors().forEach(e -> logOnce("faction:" + factionId, e));
        return parsed.entries();
    }

    /** 某职业的属性配置（factions.json 的 {@code professions[].attributes}；未配置返回空列表）。 */
    public static List<Entry> entriesForProfession(String professionId) {
        if (professionId == null || professionId.isBlank() || CCNRRPMod.factions == null) {
            return List.of();
        }
        AttributeProfile.ParseResult parsed =
                AttributeProfile.parse(CCNRRPMod.factions.professionAttributes(professionId));
        parsed.warnings().forEach(w -> logOnce("profession:" + professionId, w));
        parsed.errors().forEach(e -> logOnce("profession:" + professionId, e));
        return parsed.entries();
    }

    /** 实际生效的属性（阵营层 + 职业层合并，职业层同 id 覆盖阵营层）。 */
    public static List<Entry> effectiveFor(String factionId, String professionId) {
        return AttributeProfile.layer(entriesFor(factionId), entriesForProfession(professionId))
                .effective();
    }

    /** 套用属性到玩家（部署/登录统一入口）。职业层覆盖阵营层同 id 条目。 */
    public static void applyTo(ServerPlayer player, String factionId, String professionId) {
        if (player == null) {
            return;
        }
        PlayerAttributeBridge.apply(player, factionId, effectiveFor(factionId, professionId));
    }

    /** 移除本 mod 施加的属性修饰（退场时调用；幂等）。 */
    public static void clear(ServerPlayer player) {
        PlayerAttributeBridge.purge(player);
    }

    /** 配置保存后：对该阵营**在线**成员立即生效（离线者下次登录/部署时套用）。返回受影响人数。 */
    public static int applyToOnlineMembers(String factionId) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || CCNRRPMod.users == null || factionId == null || factionId.isBlank()) {
            return 0;
        }
        int n = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            String uuid = p.getUUID().toString();
            if (!factionId.equals(CCNRRPMod.users.factionId(uuid))) {
                continue; // 只处理属于该阵营的玩家（按用户档案的阵营判定，客户端说什么不算）
            }
            if (CCNRRPMod.users.status(uuid) != CharacterStatus.ALIVE) {
                continue; // 不在场（观察者）不套用：加成本来就只在部署后生效
            }
            applyTo(p, factionId, CCNRRPMod.users.professionId(uuid));
            n++;
        }
        if (n > 0) {
            LOGGER.info("[CCNR-RP] 阵营 {} 属性变更已即时套用到 {} 名在线成员", factionId, n);
        }
        return n;
    }

    /** 配置保存后：以该职业**在线**的成员立即重套（职业层属性变更入口，与阵营层同一纪律）。 */
    public static int applyToOnlineProfessionMembers(String professionId) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || CCNRRPMod.users == null || professionId == null || professionId.isBlank()) {
            return 0;
        }
        int n = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            String uuid = p.getUUID().toString();
            if (!professionId.equals(CCNRRPMod.users.professionId(uuid))) {
                continue; // 只处理该职业的在线成员
            }
            if (CCNRRPMod.users.status(uuid) != CharacterStatus.ALIVE) {
                continue; // 不在场（观察者）不套用
            }
            // 职业层覆盖阵营层，故重套时要带上该玩家自己的阵营（不是职业所属阵营）
            applyTo(p, CCNRRPMod.users.factionId(uuid), professionId);
            n++;
        }
        if (n > 0) {
            LOGGER.info("[CCNR-RP] 职业 {} 属性变更已即时套用到 {} 名在线成员", professionId, n);
        }
        return n;
    }

    /** 已注册属性 id（命令/界面提示用，透传适配层）。 */
    public static List<String> registeredIds() {
        return PlayerAttributeBridge.registeredIds();
    }

    /** 配置里引用了但当前未注册的属性 id（提醒管理员"装对应 mod 后生效"）。 */
    public static List<String> unknownIds(List<Entry> entries) {
        return PlayerAttributeBridge.unknownIds(entries);
    }

    /** 去重日志：同一阵营的同一问题只记一次（服务端运行期内）。 */
    private static void logOnce(String factionId, String message) {
        if (LOGGED.add(factionId + "|" + message)) {
            LOGGER.error("[CCNR-RP] 阵营 {} 属性配置问题（该条已忽略）：{}", factionId, message);
        }
    }
}
