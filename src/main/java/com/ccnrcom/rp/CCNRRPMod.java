/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp;

import com.ccnrcom.rp.character.CharacterService;
import com.ccnrcom.rp.command.RpCommand;
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
    /** 角色服务（P3）。 */
    public static CharacterService characters;
    /** 状态管理（P4，注册到 Forge 总线；ServerStopping 注销）。 */
    public static com.ccnrcom.rp.status.StatusManager statusManager;
    /** 经验服务（P5）。 */
    public static com.ccnrcom.rp.experience.ExperienceService experience;
    /** 事件管理（P6）。 */
    public static com.ccnrcom.rp.event.EventManager eventManager;
    /** 刷新框架（P8 完整实现；P6 起为占位）。 */
    public static com.ccnrcom.rp.spawn.SpawnFramework spawnFramework;

    public CCNRRPMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, CCNRRPConfig.SPEC);
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
        factions = new FactionManager();
        factions.load();
        characters = new CharacterService(event.getServer());
        statusManager = new com.ccnrcom.rp.status.StatusManager(event.getServer());
        MinecraftForge.EVENT_BUS.register(statusManager);
        experience = new com.ccnrcom.rp.experience.ExperienceService(event.getServer());
        MinecraftForge.EVENT_BUS.register(experience);
        spawnFramework = new com.ccnrcom.rp.spawn.SpawnFramework(event.getServer());
        eventManager = new com.ccnrcom.rp.event.EventManager(event.getServer());
        MinecraftForge.EVENT_BUS.register(eventManager);
        LOGGER.info(
                "[CCNR-RP] 服务端运行时就绪：阵营 {} 个 / 组 {} 个 / 角色 {} 个",
                factions.graph().factions().size(),
                factions.graph().groups().size(),
                characters.store().all().size());
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (characters != null && event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            characters.sendList(player);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (characters != null) {
            characters.store().save();
        }
        if (statusManager != null) {
            MinecraftForge.EVENT_BUS.unregister(statusManager);
        }
        if (experience != null) {
            MinecraftForge.EVENT_BUS.unregister(experience);
        }
        if (eventManager != null) {
            MinecraftForge.EVENT_BUS.unregister(eventManager);
        }
        eventManager = null;
        spawnFramework = null;
        experience = null;
        statusManager = null;
        characters = null;
        factions = null;
        LOGGER.info("[CCNR-RP] 服务端运行时清理完成");
    }
}
