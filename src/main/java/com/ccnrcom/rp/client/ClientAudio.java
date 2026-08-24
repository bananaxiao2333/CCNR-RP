/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 客户端配置音乐播放器：职业出场音乐（config/ccnr_rp/ 相对路径或绝对路径，WAV）。
 * play() 60 秒后淡出（1.5s）；重复 play 会先停旧曲。
 */
public final class ClientAudio {
    private static final Logger LOGGER = LogManager.getLogger();

    private static Clip active;
    private static Thread activeThread;

    private ClientAudio() {}

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
                        try (AudioInputStream in = AudioSystem.getAudioInputStream(p.toFile())) {
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
                return Files.exists(p) ? p : null;
            }
            Path rel = FMLPaths.CONFIGDIR.get().resolve("ccnr_rp").resolve(cfgPath);
            return Files.exists(rel) ? rel : null;
        } catch (Exception e) {
            return null;
        }
    }
}
