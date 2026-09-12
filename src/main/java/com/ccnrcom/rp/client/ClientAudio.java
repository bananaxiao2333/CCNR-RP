/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 客户端配置音乐播放器：出场音乐（OGG 为主，兼容 WAV）。相对路径 = 服务器下发的素材（自动下载缓存）；
 * 绝对路径 = 启动程序/启动器指定（本地媒体，优先级最高）。play() 60 秒后淡出（1.5s）。
 *
 * <p>javax.sound 原生不支持 OGG（Minecraft SoundEngine 支持，但那是资源包/音效系统，不适用动态下载的
 * 配置文件音乐）；OGG 用内嵌的 jorbis（纯 Java 解码）转为 PCM 后走同一 Clip 播放。
 */
public final class ClientAudio {
    private static final Logger LOGGER = LogManager.getLogger();

    private static Clip active;
    private static Thread activeThread;
    /** 本 mod 音乐音量（0..1；由客户端配置与原版「音乐和音效设置」滑块驱动，播放中可实时调整）。 */
    private static volatile float musicVolume = 1.0f;

    private ClientAudio() {}

    /**
     * 启动程序（启动器）指定音乐：最高优先级。
     * 优先读 JVM 参数 -Dccnr_rp.entrance_music=...，其次环境变量 CCNR_RP_ENTRANCE_MUSIC；未指定返回空串。
     */
    public static String launcherMusic() {
        String prop = System.getProperty("ccnr_rp.entrance_music");
        if (prop != null && !prop.isBlank()) {
            return prop.trim();
        }
        String env = System.getenv("CCNR_RP_ENTRANCE_MUSIC");
        return env == null ? "" : env.trim();
    }

    /** 播放配置中的出场音乐（60s 后淡出）。路径相对 config/ccnr_rp/ 或绝对路径。 */
    public static void playEntrance(String cfgPath) {
        Path p = resolve(cfgPath);
        if (p == null) {
            return;
        }
        stop();
        // 音量来自客户端配置（原版「音乐和音效设置」里的本 mod 滑块）：客户端主线程读取（背景线程不碰配置对象）。
        musicVolume =
                com.ccnrcom.rp.config.CCNRRPClientConfig.MUSIC_VOLUME.get().floatValue();
        if (musicVolume <= 0f) {
            // 与原版 `SoundEngine.play` 一致：算出的音量是 0 → 不播（原版日志 "Skipped playing sound, volume was zero."）
            LOGGER.debug("[CCNR-RP] 跳过出场音乐（音量 0）: {}", p);
            return;
        }
        Thread t = new Thread(
                () -> {
                    try {
                        try (AudioInputStream in = openAudio(p)) {
                            Clip clip = AudioSystem.getClip();
                            clip.open(in);
                            synchronized (ClientAudio.class) {
                                active = clip;
                            }
                            if (!applyVolumeForStart(clip, musicVolume)) {
                                // 该平台没有任何音量控件：无法保证"音量 0 = 不发声"，宁可不起播
                                clip.close();
                                synchronized (ClientAudio.class) {
                                    active = null;
                                }
                                return;
                            }
                            clip.start();
                            // 60 秒后淡出（1.5s）
                            Thread.sleep(60_000L);
                            if (clip.isRunning() && same(clip)) {
                                fadeOut(clip, 1500);
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.warn("[CCNR-RP] 出场音乐播放失败: {} - {}", p, e.getMessage());
                    } finally {
                        synchronized (ClientAudio.class) {
                            if (active == null || !active.isOpen()) {
                                active = null;
                            }
                        }
                    }
                },
                "ccnr-rp-audio");
        t.setDaemon(true);
        activeThread = t;
        t.start();
    }

    /**
     * 实时调整本 mod 音乐音量（0..1 线性）。
     *
     * <p><b>与 MC 原版对齐</b>：原版音量就是**线性**值——`SoundEngine.play` 起播时算
     * {@code Mth.clamp(instanceVolume * options.getSoundSourceVolume(source), 0, 1)} 并把它**原样**交给
     * OpenAL（`Channel.setVolume → AL10.alSourcef(AL_GAIN, v)`，线性增益）；结果为 0 时原版**直接不播**
     * （日志 "Skipped playing sound, volume was zero."）。拖滑块时原版的
     * {@code SoundEngine.updateCategoryVolume} 会**逐个更新正在播放的声道**（`instanceToChannel.forEach`），
     * 即"拖动即时生效"，且 0 只是把增益压到 0（正在播的那条不因此停止）。
     * 本方法按同一套语义执行：0 → 真静音（见 {@link #applyVolume}），其余值为线性增益。
     */
    public static void setVolume(float v) {
        musicVolume = v;
        Clip clip;
        synchronized (ClientAudio.class) {
            clip = active;
        }
        if (clip == null || !clip.isRunning()) {
            return; // 未在播放：新值作为下次播放音量（与原版"起播时取值"一致）
        }
        applyVolume(clip, v);
    }

    /** 线性音量下限（分贝域兜底用）：Master Gain 量程是 dB（macOS 实测 −80..+6.02dB）。 */
    public static final float MUTE_DB = -80f;

    /**
     * 线性音量（0..1）→ 分贝。线性是"真值"（与原版一致），分贝只是 Java Sound 的表达方式：
     * {@code 20·log10(v)}：1.0→0dB、0.5→−6.02dB、0.1→−20dB、0→{@link #MUTE_DB}。
     * 超过 1.0 会被钳到 0dB（与原版 `clamp(...,0,1)` 一致，不允许放大）。
     */
    public static float linearToDb(float v) {
        if (v <= 0f) {
            return MUTE_DB;
        }
        return (float) (20.0 * Math.log10(Math.min(1.0, v)));
    }

    /**
     * 把线性音量写进 Java Sound（**优先用 Mute 控件表达 0**，与"原版将增益置 0"等价）：
     * <ol>
     *   <li>`Mute = (v &lt;= 0)`：macOS/Win 的 DirectClip 都提供该布尔控件 → 音量 0 是**精确静音**；</li>
     *   <li>Master Gain：分贝控件，写 {@link #linearToDb} 并钳到量程（−80..+6.02dB）。</li>
     * </ol>
     * 两条控件都不存在时无法衰减，返回 false 交给调用方决定（起播前 → 不播；播放中 → 停播）。
     */
    private static boolean applyVolume(Clip clip, float v) {
        boolean handled = false;
        try {
            javax.sound.sampled.BooleanControl mute =
                    (javax.sound.sampled.BooleanControl) clip.getControl(javax.sound.sampled.BooleanControl.Type.MUTE);
            mute.setValue(v <= 0f);
            handled = true;
        } catch (Exception ignored) {
            // 无 Mute 控件：交给 Master Gain
        }
        try {
            FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), linearToDb(v))));
            handled = true;
        } catch (Exception ignored) {
            // 无 Master Gain 控件：只能靠 Mute
        }
        return handled;
    }

    /** 起播时应用音量；返回 false 表示该平台无法衰减到"音量 0 = 不发声"，调用方应放弃起播。 */
    private static boolean applyVolumeForStart(Clip clip, float v) {
        boolean handled = applyVolume(clip, v);
        return handled || v > 0f; // v>0 时即使无控件也能按系统默认音量播；v==0 必须能静音
    }

    /** OGG 用 Minecraft 自带 OggAudioStream（原生 STB Vorbis，立体声正确）解码；其余格式（WAV/AIFF 等）走 AudioSystem。 */
    private static AudioInputStream openAudio(Path p) throws Exception {
        if (p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ogg")) {
            return decodeOgg(p);
        }
        return AudioSystem.getAudioInputStream(p.toFile());
    }

    /** MC 原生 OGG 解码 → PCM AudioInputStream（格式/声道/字节序取自 getFormat，整段解码到内存，播放逻辑与 WAV 一致）。 */
    private static AudioInputStream decodeOgg(Path p) throws Exception {
        try (com.mojang.blaze3d.audio.OggAudioStream stream =
                new com.mojang.blaze3d.audio.OggAudioStream(Files.newInputStream(p))) {
            AudioFormat fmt = stream.getFormat();
            java.nio.ByteBuffer pcm = stream.readAll();
            byte[] raw = new byte[pcm.remaining()];
            pcm.get(raw);
            return new AudioInputStream(new ByteArrayInputStream(raw), fmt, raw.length / fmt.getFrameSize());
        }
    }

    private static boolean same(Clip clip) {
        synchronized (ClientAudio.class) {
            return active == clip;
        }
    }

    /**
     * 淡出：在**线性音量域**从当前音量线性降到 0（与音量滑块同一套线性语义），逐步写进 Java Sound。
     * 此前写成 `max * volume * (1 − (i+1)/steps)`——既用错了 dB/线性（见 {@link #linearToDb}），
     * 方向也反了（越"淡"越接近 0dB = 越响），最后再硬停。
     */
    private static void fadeOut(Clip clip, long ms) {
        int steps = 20;
        try {
            for (int i = 0; i < steps && clip.isRunning(); i++) {
                applyVolume(clip, musicVolume * (1f - (i + 1f) / steps));
                Thread.sleep(ms / steps);
            }
        } catch (Exception ignored) {
            // 淡出中断不致命
        }
        clip.stop();
        clip.close();
    }

    /** 停止当前音乐（立即）。 */
    public static synchronized void stop() {
        if (activeThread != null) {
            activeThread.interrupt();
            activeThread = null;
        }
        if (active != null) {
            Clip c = active;
            active = null;
            try {
                c.stop();
                c.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private static Path resolve(String cfgPath) {
        if (cfgPath == null || cfgPath.isBlank()) {
            return null;
        }
        try {
            Path p = Path.of(cfgPath);
            if (p.isAbsolute()) {
                // 启动程序/启动器指定（客户端本地媒体，优先级最高）
                return Files.exists(p) ? p : null;
            }
            // 相对路径 = 服务器下发的素材（音乐由服务器控制）：一律从素材缓存解析，
            // 未下载完成返回 null（跳过本次播放，清单已自动排队下载）。
            String asset = cfgPath.startsWith("audio/") ? cfgPath.substring("audio/".length()) : cfgPath;
            return ClientAssetCache.resolve(asset);
        } catch (Exception e) {
            return null;
        }
    }
}
