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
        Thread t = new Thread(
                () -> {
                    try {
                        try (AudioInputStream in = openAudio(p)) {
                            Clip clip = AudioSystem.getClip();
                            clip.open(in);
                            synchronized (ClientAudio.class) {
                                active = clip;
                            }
                            FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                            gain.setValue(gain.getMaximum() * 0.55f);
                            clip.start();
                            // 60 秒后淡出（1.5s）
                            Thread.sleep(60_000L);
                            if (clip.isRunning() && same(clip)) {
                                fadeOut(clip, gain, 1500);
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

    private static void fadeOut(Clip clip, FloatControl gain, long ms) {
        long steps = 20;
        try {
            float max = gain.getMaximum();
            for (int i = 0; i < steps && clip.isRunning(); i++) {
                gain.setValue(max * 0.55f * (1f - (i + 1f) / steps));
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
