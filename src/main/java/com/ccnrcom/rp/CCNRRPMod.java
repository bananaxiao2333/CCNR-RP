/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp;

import com.ccnrcom.rp.character.CharacterService;
import com.ccnrcom.rp.command.RpCommand;
import com.ccnrcom.rp.config.CCNRRPClientConfig;
import com.ccnrcom.rp.config.CCNRRPConfig;
import com.ccnrcom.rp.faction.FactionManager;
import com.ccnrcom.rp.network.RpChannels;
import com.ccnrcom.rp.util.Permissions;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** CCNR-RP（RolePlay）模组入口。服务端运行时服务在此装配（P1 起按阶段扩展）。 */
@Mod(CCNRRPMod.MODID)
public class CCNRRPMod {
    public static final String MODID = "ccnr_rp";
    private static final Logger LOGGER = LogManager.getLogger();

    /** 阵营配置管理器（仅服务端/服务器线程访问；ServerStopping 清空）。 */
    public static FactionManager factions;
    /** 管理器设置（settings.json：入服规则/强制保留；仅服务端）。 */
    public static com.ccnrcom.rp.config.ManagerSettings managerSettings;
    /** 多模式（P15）：mode 登记/热切换；剧本配置（阶段/事件/波次/动画）按其路由。 */
    public static com.ccnrcom.rp.config.ModeManager modes;
    /** 规则变更（P15 §4.5）：ruleChange 的幕作用域状态层（限职业/阵营、目标区、招募方式）。 */
    public static com.ccnrcom.rp.rule.RuleService rules;
    /** 序列引擎（序列编辑器）。 */
    public static com.ccnrcom.rp.sequence.SequenceEngine sequenceEngine;
    /** 角色服务（P3）。 */
    public static CharacterService characters;
    /** 用户服务（P9）：经验随用户走、创建冷却、支援开关。 */
    public static com.ccnrcom.rp.user.UserService users;
    /** 状态管理（P4，注册到 Forge 总线；ServerStopping 注销）。 */
    public static com.ccnrcom.rp.status.StatusManager statusManager;
    /** 经验服务（P5）。 */
    public static com.ccnrcom.rp.experience.ExperienceService experience;
    /** 事件管理（P6）。 */
    public static com.ccnrcom.rp.event.EventManager eventManager;
    /** 刷新框架（P8 完整实现；P6 起为占位）。 */
    public static com.ccnrcom.rp.spawn.SpawnFramework spawnFramework;
    /** 动画引擎（P7）。 */
    public static com.ccnrcom.rp.animation.AnimationEngine animationEngine;
    /** 数据库后端（P0 起）；未启用（db.enabled=false）时为 disabled 实例。 */
    public static com.ccnrcom.rp.data.Database database;
    /** 自定义设定服务（变量/预设方案；服务端权威，ServerStopping 置空）。同时是对外只读取值接口。 */
    public static com.ccnrcom.rp.variable.VariableService variables;
    /**
     * 数据服务（CCNR-Data）链路；未启用（serverconfig data.enabled=false）时为 disabled 实例。
     *
     * <p>它同时是**等级显示的数据源**：界面上的等级只能从它的 {@code profiles()} 里取，
     * 取不到就不显示（见 {@code sync.RemoteProfiles}）。
     */
    public static com.ccnrcom.rp.sync.DataLink data;

    public CCNRRPMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, CCNRRPConfig.SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CCNRRPClientConfig.SPEC);
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::commonSetup);
        RpChannels.register();
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(Permissions.class);
        LOGGER.info("[CCNR-RP] 模组初始化完成");
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> LOGGER.info("[CCNR-RP] 公共初始化完成（服务端 + 客户端）"));
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        RpCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        // 数据库后端：配置读取 + 连接（各 manager 构造前；未启用时 connect() 返回 false 无副作用）
        database = new com.ccnrcom.rp.data.Database(com.ccnrcom.rp.data.DbConfig.load(
                net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().resolve("db.properties")));
        database.connect();
        // serverconfig 调参：数据库启用时先从 server_settings（当前配置档）覆盖各 ConfigValue 运行时值
        com.ccnrcom.rp.config.CCNRRPConfig.applyDbOverrides();
        managerSettings = new com.ccnrcom.rp.config.ManagerSettings();
        // 多模式（P15）：登记 + 激活（剧本配置路由的依据；须在各剧本管理器构造前初始化）
        modes = new com.ccnrcom.rp.config.ModeManager();
        // 规则变更（P15 §4.5）：幕作用域规则层（事件/波次/部署查询用）
        rules = new com.ccnrcom.rp.rule.RuleService();
        // 素材库（服务器权威：音乐/阵营图标）——首次启动写入内嵌默认图标
        com.ccnrcom.rp.assets.AssetLibrary.ensureDefaults();
        factions = new FactionManager();
        factions.load();
        // 自定义设定（全局变量 + 预设方案）：命令与管理面板共用，也是外部功能按 id 取值的唯一入口
        variables = new com.ccnrcom.rp.variable.VariableService();
        characters = new CharacterService(event.getServer());
        users = new com.ccnrcom.rp.user.UserService(
                event.getServer().getWorldPath(new net.minecraft.world.level.storage.LevelResource("ccnr_rp")));
        statusManager = new com.ccnrcom.rp.status.StatusManager(event.getServer());
        MinecraftForge.EVENT_BUS.register(statusManager);
        // Corpse 联动：注册遗体钩子（死亡身份注入 + 名字牌；仅 Corpse 模组存在时生效）
        com.ccnrcom.rp.corpse.CorpseBridge.registerHooks();
        experience = new com.ccnrcom.rp.experience.ExperienceService(event.getServer());
        MinecraftForge.EVENT_BUS.register(experience);
        spawnFramework = new com.ccnrcom.rp.spawn.SpawnFramework(event.getServer());
        MinecraftForge.EVENT_BUS.register(spawnFramework);
        animationEngine = new com.ccnrcom.rp.animation.AnimationEngine();
        eventManager = new com.ccnrcom.rp.event.EventManager(event.getServer());
        MinecraftForge.EVENT_BUS.register(eventManager);
        sequenceEngine = new com.ccnrcom.rp.sequence.SequenceEngine(event.getServer());
        MinecraftForge.EVENT_BUS.register(sequenceEngine);
        // 数据服务链路：放在所有服务之后装配 —— 上报点会读 users/characters/eventManager，
        // 早装配会让「服务还没建好时的上报」读到 null。回调统一派发回服务端主线程。
        data = buildDataLink(event.getServer());
        if (data != null) {
            data.start();
        }
        LOGGER.info(
                "[CCNR-RP] 服务端运行时就绪：阵营 {} 个 / 组 {} 个",
                factions.graph().factions().size(),
                factions.graph().groups().size());
    }

    /**
     * 装配数据服务链路；**gRPC 运行时不在时安全降级**（等价于关闭）。
     *
     * <h2>为什么必须有这一层</h2>
     * gRPC 运行时是打进 `-all.jar` 的嵌套 jar。只放普通 `ccnr_rp-<版本>.jar` 时
     * `io.grpc.*` 根本不在类路径上，直接 `new DataLink(...)` 会抛 {@code NoClassDefFoundError}
     * —— 那是个 **Error**，会在 ServerAboutToStart 里把**整个服务端启动**打掉。
     * 一个可选功能缺依赖导致服务器起不来，比功能不可用严重得多（对照：Corpse 缺失走原生降级、
     * CCNR-PM 与 CCNR-RP 的反射桥在对方未装时全部降级，都是同一条纪律）。
     *
     * <p>因此：先按类名探测（不加载本模组的 sync 包），再捕获 {@code Throwable} 兜底
     * （类存在但传递依赖缺失时，NoClassDefFoundError 会在构造期才出现）。
     * 降级后的行为与 `data.enabled=false` 完全一致：不上报、界面不显示等级。
     */
    private static com.ccnrcom.rp.sync.DataLink buildDataLink(net.minecraft.server.MinecraftServer server) {
        try {
            Class.forName("io.grpc.ManagedChannelBuilder");
        } catch (Throwable t) {
            LOGGER.error(
                    "[CCNR-RP] 未找到 gRPC 运行时：数据服务不可用，界面将不显示等级。" + "要启用请使用 ccnr_rp-{}-all.jar（内含嵌套依赖），而不是普通 jar",
                    gradleVersion());
            return null;
        }
        try {
            return new com.ccnrcom.rp.sync.DataLink(
                    server.getWorldPath(new net.minecraft.world.level.storage.LevelResource("ccnr_rp")),
                    com.ccnrcom.rp.config.CCNRRPConfig.DATA_ENABLED.get(),
                    com.ccnrcom.rp.config.CCNRRPConfig.DATA_TARGET.get(),
                    com.ccnrcom.rp.config.CCNRRPConfig.DATA_SERVER_ID.get(),
                    com.ccnrcom.rp.config.CCNRRPConfig.DATA_TIMEOUT_MS.get(),
                    com.ccnrcom.rp.config.CCNRRPConfig.DATA_OUTBOX_MAX.get(),
                    com.ccnrcom.rp.config.CCNRRPConfig.DATA_RECENT_EVENTS.get(),
                    server::execute); // 所有 gRPC 回调都派发回服务端主线程（见 sync.DataLink 的线程模型）
        } catch (Throwable t) {
            LOGGER.error("[CCNR-RP] 数据服务链路装配失败（已降级为不可用）: {}", t.toString());
            return null;
        }
    }

    /** 仅用于日志：mods.toml 注入的实现版本。 */
    private static String gradleVersion() {
        return net.minecraftforge.fml.ModList.get()
                .getModContainerById(MODID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("<版本>");
    }

    /** 重放节奏：每 100 tick（5 秒）试一条。刻意不在 tick 里直接发 —— 只投一次，等回调。 */
    private static final int DATA_FLUSH_TICKS = 100;

    private int dataFlushCounter = 0;

    @SubscribeEvent
    public void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END || data == null) {
            return;
        }
        if (++dataFlushCounter < DATA_FLUSH_TICKS) {
            return;
        }
        dataFlushCounter = 0;
        data.tick();
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            // 首次入服自动部署入队：必须在用户档案被惰性创建（下方 status/cooldown 查询）之前判定「首次」
            if (spawnFramework != null) {
                spawnFramework.maybeQueueFirstJoin(player);
            }
        }
        if (characters != null && event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            // 登录归一化：用户级状态 DEAD 且冷却结束 → 立即回观察者（阴间），随后同步档案
            long now = System.currentTimeMillis();
            String uuid = player.getUUID().toString();
            if (CCNRRPMod.users != null
                    && CCNRRPMod.users.status(uuid) == com.ccnrcom.rp.status.CharacterStatus.DEAD
                    && CCNRRPMod.users.cooldownUntil(uuid) <= now) {
                CCNRRPMod.users.setStatus(uuid, com.ccnrcom.rp.status.CharacterStatus.OBSERVING);
                CCNRRPMod.users.setCooldown(uuid, 0);
                CCNRRPMod.users.save();
                // 归一到观察者 = 切观察者：清背包 + 卸下阵营属性（非死亡路径 → 直接删除，不留掉落物）
                com.ccnrcom.rp.status.StatusManager.purgeOnObserving(player, false);
            }
            characters.sendList(player);
            characters.broadcastPlayerTags();
            // 对局状态（模式/当前幕/计时/激活事件）：晚加入与重连必须立刻拿到当前有效状态（docs/01 §9.2）
            if (eventManager != null) {
                eventManager.sendMatchState(player);
            }
            // 素材同步：音乐/阵营图标由服务器中央下发，客户端异步下载（左上角「同步数据中」提示）；
            // 同步完成确认前禁用部署/复活（有通道才需要同步）
            if (com.ccnrcom.rp.network.RpChannels.hasChannel(player.connection.connection)) {
                com.ccnrcom.rp.assets.AssetLibrary.markPending(player);
                com.ccnrcom.rp.assets.AssetLibrary.sendManifest(player);
            }
            // 死亡强制旁观者：登录时若用户处于复活冷却（近期死亡/判死）且无在场 → 旁观者模式（不传送）
            if (CCNRRPMod.users != null && !CCNRRPMod.users.isAlive(uuid) && CCNRRPMod.users.onCooldown(uuid)) {
                player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
                // 登录即观察者：清背包（非死亡路径 → 直接删除）+ 卸下阵营属性，
                // 防止上一局的装备与加成随玩家存档被带回来（docs/01 §9.4 对称清理）。
                com.ccnrcom.rp.status.StatusManager.purgeOnObserving(player, false);
            }
            // 阵营属性（docs/16）：在场玩家重连后按当前配置重套一次（永久修饰随存档保留，这里保证与配置一致）
            if (CCNRRPMod.users != null && CCNRRPMod.users.isAlive(uuid)) {
                com.ccnrcom.rp.attribute.AttributeService.applyTo(
                        player, CCNRRPMod.users.factionId(uuid), CCNRRPMod.users.professionId(uuid));
                // 部署模式开关的重放（docs/01 §9.5）：游戏模式随 playerdata 持久化，跨服务端重启、
                // 手改 settings.json、切换配置档（DB server_settings）后都可能残留开关的上一个取值——
                // 没有任何兜底会纠正在场玩家（2s 轮询刻意不干预在场者），所以登录时按当前开关纠正一次。
                // 判据同部署落位：只在生存↔冒险之间迁移，创造/旁观一律不碰。
                if (spawnFramework != null) {
                    spawnFramework.reapplyDeployedGameMode(player);
                }
            }
        }
        // 登录即向数据服务确认该玩家的等级：确认不到就推 levelKnown=false（界面不显示等级）。
        // 先推一次「未知」，避免重连时客户端沿用上一局的值；确认回来后再覆盖。
        if (data != null && event.getEntity() instanceof net.minecraft.server.level.ServerPlayer p3) {
            if (experience != null) {
                experience.pushUserXp(p3.getUUID().toString());
            }
            // 走 DataReports 而不是 data.fetchPlayer：后者签名里带着生成的 protobuf 类型，
            // 会让本类（必然加载）也引用可选依赖（见 sync.DataReports 的类注释）。
            com.ccnrcom.rp.sync.DataReports.fetchPlayer(
                    p3.getUUID().toString(),
                    () -> experience.pushUserXp(p3.getUUID().toString()));
        }
        // 补发离线期间的结算通知（死亡/断联结算结果）+ 经验规则集（管理面板展示）
        if (experience != null && event.getEntity() instanceof net.minecraft.server.level.ServerPlayer p2) {
            experience.flushPending(p2);
            com.ccnrcom.rp.network.RpChannels.sendTo(
                    p2,
                    new com.ccnrcom.rp.network.RpPackets.RulesStateS2C(
                            experience.rulesPayload().toString()));
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            // 清理素材同步待定标记（防映射残留）
            com.ccnrcom.rp.assets.AssetLibrary.clearPending(player);
            // 离服：取消其全部招募邀请（含已接受）并视同拒绝
            if (spawnFramework != null) {
                spawnFramework.recruit().onPlayerDisconnect(player.getUUID().toString());
            }
            // 其余在线玩家头顶标签刷新（去掉离服者）
            if (characters != null) {
                characters.broadcastPlayerTags();
            }
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (data != null) {
            data.stop(); // 停通道 + 把未发出的上报落盘（对称清理；丢了就是永久丢账）
            data = null;
        }
        if (database != null) {
            database.disconnect(); // 关闭数据库连接与写线程（对称清理）
        }
        com.ccnrcom.rp.character.CharacterService.shutdownBroadcaster(); // 关闭配置广播后台线程（docs/01 §9.4 对称清理）
        if (users != null) {
            users.save();
        }
        if (statusManager != null) {
            MinecraftForge.EVENT_BUS.unregister(statusManager);
            com.ccnrcom.rp.status.StatusManager.clearDeathSpots();
        }
        com.ccnrcom.rp.corpse.CorpseBridge.clearCaptured(); // 清空未消费的死亡角色身份暂存
        com.ccnrcom.rp.sequence.SequenceEngine.clearConscripts();
        if (spawnFramework != null) {
            MinecraftForge.EVENT_BUS.unregister(spawnFramework);
        }
        if (experience != null) {
            MinecraftForge.EVENT_BUS.unregister(experience);
        }
        if (eventManager != null) {
            MinecraftForge.EVENT_BUS.unregister(eventManager);
        }
        if (sequenceEngine != null) {
            MinecraftForge.EVENT_BUS.unregister(sequenceEngine);
        }
        sequenceEngine = null;
        eventManager = null;
        animationEngine = null;
        spawnFramework = null;
        experience = null;
        modes = null;
        rules = null;
        statusManager = null;
        characters = null;
        users = null;
        factions = null;
        if (variables != null) {
            variables.clear(); // 自定义设定缓存对称清理（静态态不跨世界残留）
        }
        variables = null;
        managerSettings = null;
        database = null;
        LOGGER.info("[CCNR-RP] 服务端运行时清理完成");
    }
}
