/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** CCNR-RP（RolePlay）模组入口 —— 目前仅为项目框架，功能开发中。 */
@Mod(CCNRRPMod.MODID)
public class CCNRRPMod {
    public static final String MODID = "ccnr_rp";
    private static final Logger LOGGER = LogManager.getLogger();

    public CCNRRPMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::commonSetup);
        LOGGER.info("[CCNR-RP] 模组框架初始化完成（功能开发中）");
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> LOGGER.info("[CCNR-RP] 公共初始化完成（服务端 + 客户端）"));
    }
}
