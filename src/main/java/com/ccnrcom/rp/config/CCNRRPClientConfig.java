/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.ConfigValue;

/** CCNR-RP 客户端调参配置（config/ccnr_rp-client.toml）。 */
public final class CCNRRPClientConfig {
    public static final ForgeConfigSpec SPEC;
    /** 本 mod 出场音乐音量（0..1；默认 0.55 保持原响度）。 */
    public static final ConfigValue<Double> MUSIC_VOLUME;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("audio");
        MUSIC_VOLUME = b.comment("本 mod 音乐音量（0..1；原版音乐和音效设置里也有对应滑块）").defineInRange("musicVolume", 0.55, 0.0, 1.0);
        b.pop();
        SPEC = b.build();
    }

    private CCNRRPClientConfig() {}
}
