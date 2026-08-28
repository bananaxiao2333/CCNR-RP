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

/** 音乐库（服务端）：config/ccnr_rp/audio/*.ogg 的校验与存取；名称/格式校验为纯逻辑（可单测）。 */
public final class MusicStore {

    public static final int MAX_BYTES = 20 * 1024 * 1024;
    /** 文件名：小写/大写字母、数字、下划线、连字符，≤48 字符，必须 .ogg（WAV 体积过大已弃用）。 */
    private static final java.util.regex.Pattern NAME_PATTERN =
            java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,48}\\.ogg");

    private MusicStore() {}

    public static Path audioDir() {
        return FMLPaths.CONFIGDIR.get().resolve("ccnr_rp").resolve("audio");
    }

    /** 校验文件名；返回错误消息，null=通过。 */
    public static String validateName(String name) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            return "文件名仅允许字母/数字/下划线/连字符（≤48 字符）且必须以 .ogg 结尾";
        }
        return null;
    }

    /** 校验 OGG 内容（OggS 魔数 + 大小上限）；返回错误消息，null=通过。 */
    public static String validate(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "音乐为空";
        }
        if (bytes.length > MAX_BYTES) {
            return "大小超限（≤ " + (MAX_BYTES / 1024 / 1024) + "MB）";
        }
        if (bytes.length < 4 || bytes[0] != 'O' || bytes[1] != 'g' || bytes[2] != 'g' || bytes[3] != 'S') {
            return "不是 OGG 文件（缺少 OggS 头）";
        }
        return null;
    }

    /** 已上传音乐名列表（按名称排序）。DB 启用时读 assets 表。 */
    public static List<String> list() {
        List<String> out = new ArrayList<>();
        if (com.ccnrcom.rp.data.AssetRepository.enabled0()) {
            for (com.ccnrcom.rp.data.AssetRepository.AssetMeta m : com.ccnrcom.rp.data.AssetRepository.list()) {
                if ("music".equals(m.kind())) {
                    out.add(m.name());
                }
            }
            out.sort(String::compareTo);
            return out;
        }
        try {
            Path dir = audioDir();
            if (Files.isDirectory(dir)) {
                try (var s = Files.list(dir)) {
                    s.filter(p -> p.getFileName().toString().endsWith(".ogg"))
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

    /** 保存音乐（覆盖同名文件，临时文件+rename 原子写；DB 启用时存 assets 表）。 */
    public static void save(String name, byte[] bytes) throws Exception {
        if (com.ccnrcom.rp.data.AssetRepository.enabled0()) {
            String sha = sha256(bytes);
            com.ccnrcom.rp.data.AssetRepository.save(name, "music", bytes, sha);
            return;
        }
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

    private static String sha256(byte[] data) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
