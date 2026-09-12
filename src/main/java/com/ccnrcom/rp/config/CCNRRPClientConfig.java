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
    /** 本 mod 出场音乐音量（0..1 线性百分比；默认 1.0 = 原声满音量 0dB，0 = 静音）。 */
    public static final ConfigValue<Double> MUSIC_VOLUME;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("audio");
        MUSIC_VOLUME = b.comment(
                        "本 mod 音乐音量（0..1 线性百分比；原版「音乐和音效设置」里也有对应滑块，0~100%）。",
                        "1.0 = 原声满音量（0dB，默认），0.5 = 半幅（−6dB），0 = 禁音。",
                        "旧版本沿用错误的增益换算，滑块任何位置都等于满音量；若你的配置是那时生成的 0.55，",
                        "想保持同样的响度把滑块拉到 100%（或把这里改成 1.0）。")
                .defineInRange("musicVolume", 1.0, 0.0, 1.0);
        b.pop();
        SPEC = b.build();
    }

    private CCNRRPClientConfig() {}
}
