/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.music;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraftforge.fml.loading.FMLPaths;

/** 音乐库（服务端）：config/ccnr_rp/audio/*.wav 的校验与存取；名称/格式校验为纯逻辑（可单测）。 */
public final class MusicStore {

    public static final int MAX_BYTES = 20 * 1024 * 1024;
    /** 文件名：小写/大写字母、数字、下划线、连字符，≤48 字符，必须 .wav。 */
    private static final java.util.regex.Pattern NAME_PATTERN =
            java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,48}\\.wav");

    private MusicStore() {}

    public static Path audioDir() {
        return FMLPaths.CONFIGDIR.get().resolve("ccnr_rp").resolve("audio");
    }

    /** 校验文件名；返回错误消息，null=通过。 */
    public static String validateName(String name) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            return "文件名仅允许字母/数字/下划线/连字符（≤48 字符）且必须以 .wav 结尾";
        }
        return null;
    }

    /** 校验 WAV 内容（RIFF/WAVE 魔数 + 大小上限）；返回错误消息，null=通过。 */
    public static String validate(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "音乐为空";
        }
        if (bytes.length > MAX_BYTES) {
            return "大小超限（≤ " + (MAX_BYTES / 1024 / 1024) + "MB）";
        }
        if (bytes.length < 12
                || bytes[0] != 'R'
                || bytes[1] != 'I'
                || bytes[2] != 'F'
                || bytes[3] != 'F'
                || bytes[8] != 'W'
                || bytes[9] != 'A'
                || bytes[10] != 'V'
                || bytes[11] != 'E') {
            return "不是 WAV 文件（缺少 RIFF/WAVE 头）";
        }
        return null;
    }

    /** 已上传音乐名列表（按名称排序）。 */
    public static List<String> list() {
        List<String> out = new ArrayList<>();
        try {
            Path dir = audioDir();
            if (Files.isDirectory(dir)) {
                try (var s = Files.list(dir)) {
                    s.filter(p -> p.getFileName().toString().endsWith(".wav"))
                            .map(p -> p.getFileName().toString())
                            .sorted()
                            .forEach(out::add);
                }
            }
        } catch (Exception e) {
            // 列表失败返回空
        }
        return out;
    }

    /** 保存音乐（覆盖同名文件，临时文件+rename 原子写）。 */
    public static void save(String name, byte[] bytes) throws Exception {
        Path dir = audioDir();
        Files.createDirectories(dir);
        Path target = dir.resolve(name);
        Path tmp = dir.resolve(name + ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }
}
