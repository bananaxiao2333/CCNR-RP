/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 本 mod 音乐音量 → 增益控件的换算回归（实机反馈："音量条拉到 0 也不静音"）。
 *
 * <p>语义与 MC 原版对齐：音量是**线性** 0..1（原版 {@code SoundEngine.calculateVolume} =
 * {@code Mth.clamp(instanceVolume * sourceVolume, 0, 1)}，再原样交给 OpenAL 的线性增益；
 * 音量 0 时原版直接不播）。分贝只出现在 Java Sound 这一侧（Master Gain 是 dB 控件）——
 * 旧代码写成 {@code gain.setValue(gain.getMaximum() * volume)}：`maximum` 就是 0dB，于是任何音量都等于满音量。
 * 本用例把"线性 → 分贝"这条换算钉死，防止再退回乘法写法。
 */
class ClientAudioVolumeTest {

    @Test
    void fullVolumeIsUnityGain() {
        assertEquals(0.0f, ClientAudio.linearToDb(1.0f), 0.01f, "音量 1.0 应为 0dB（不增不减）");
    }

    @Test
    void halfVolumeIsMinusSixDb() {
        // 线性幅度减半 = −6.02dB（20·log10(0.5)）
        assertEquals(-6.02f, ClientAudio.linearToDb(0.5f), 0.05f);
    }

    @Test
    void tenthVolumeIsMinusTwentyDb() {
        assertEquals(-20.0f, ClientAudio.linearToDb(0.1f), 0.1f);
    }

    @Test
    void onlyZeroIsMute() {
        assertEquals(ClientAudio.MUTE_DB, ClientAudio.linearToDb(0f), 0.001f, "音量 0 必须落到静音下限");
        assertTrue(ClientAudio.MUTE_DB <= -60f, "静音下限至少 −60dB 才算听不见");
        // 1% 与 0 必须不同：原版里 0.01 是"很轻"而不是"静音"（只有 0 才跳过播放）
        assertEquals(-40.0f, ClientAudio.linearToDb(0.01f), 0.2f);
    }

    @Test
    void monotonicAndNeverAboveUnity() {
        // 音量 0→1 时，分贝必须单调不减（−80dB 静音 → −40 → −20 → 0），且永远不超过 0dB（不允许放大）
        float prev = -Float.MAX_VALUE;
        for (int i = 0; i <= 100; i++) {
            float db = ClientAudio.linearToDb(i / 100f);
            assertTrue(db <= 0.001f, "音量不得超过 0dB（不允许放大）: " + db);
            assertTrue(db >= prev, "音量必须单调不减: " + db + " < " + prev);
            prev = db;
        }
        assertEquals(0.0f, prev, 0.01f, "音量 1.0 应回到 0dB");
    }
}
