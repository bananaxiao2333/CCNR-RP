/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.assets;

import com.ccnrcom.rp.network.RpChannels;
import com.ccnrcom.rp.network.RpPackets;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 素材库（服务端权威）：config/ccnr_rp/audio/*.ogg（音乐）与 config/ccnr_rp/textures/*.png（阵营图标）。
 * 全部素材由服务器控制：客户端按清单（AssetManifestS2C）对比本地缓存，缺失/变更时逐个请求下载
 * （AssetRequestC2S → AssetPartS2C 分片），播放/渲染一律使用服务器下发版本。
 */
public final class AssetLibrary {
    private static final Logger LOGGER = LogManager.getLogger();

    /** 分片大小（与皮肤/音乐上传一致）。 */
    public static final int PART_SIZE = 32 * 1024;
    /** 素材名：字母/数字/下划线/连字符 ≤64 字符，.ogg（音乐）或 .png（图标）。 */
    private static final java.util.regex.Pattern NAME_PATTERN =
            java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,64}\\.(ogg|png)");
    /** 内嵌默认阵营图标（首次启动写入服务器素材目录，保证 img: 图标开箱可用且受服务器控制）。 */
    private static final String[] DEFAULT_ICONS = {"admin_hq", "madison"};
    /** 同步完成确认超时（毫秒）：超时自动放行部署，防老客户端/异常永久锁死。 */
    private static final long SYNC_TIMEOUT_MS = 60_000L;
    /** 待同步玩家：UUID → 标记时刻（登录下发清单时标记；确认/超时/登出移除）。 */
    private static final java.util.Map<java.util.UUID, Long> pendingSync =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 素材流式下发后台线程（读取/发送不阻塞服务器主线程）。 */
    private static final java.util.concurrent.ExecutorService STREAMER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ccnr-rp-asset-stream");
                t.setDaemon(true);
                return t;
            });
    /** 哈希缓存：名称 → (size, mtime, hash)，文件未变不重复计算（进服不再全量哈希）。 */
    private record CacheEntry(long size, long mtime, String hash) {}

    private static final java.util.Map<String, CacheEntry> hashCache = new java.util.HashMap<>();

    private AssetLibrary() {}

    public static Path rootDir() {
        return FMLPaths.CONFIGDIR.get().resolve("ccnr_rp");
    }

    public static Path audioDir() {
        return rootDir().resolve("audio");
    }

    public static Path texturesDir() {
        return rootDir().resolve("textures");
    }

    /** 首次启动把内嵌默认阵营图标写入 config/ccnr_rp/textures/（服务器控制起点，客户端不再依赖 jar 内嵌）。 */
    public static void ensureDefaults() {
        try {
            Files.createDirectories(texturesDir());
            for (String name : DEFAULT_ICONS) {
                Path target = texturesDir().resolve(name + ".png");
                if (Files.exists(target)) {
                    continue;
                }
                try (var in =
                        AssetLibrary.class.getResourceAsStream("/assets/ccnr_rp/textures/faction/" + name + ".png")) {
                    if (in != null) {
                        Files.write(target, in.readAllBytes());
                        LOGGER.info("[CCNR-RP] 默认阵营图标已写入服务器素材目录: {}", name);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[CCNR-RP] 默认阵营图标写入失败: {}", e.getMessage());
        }
    }

    /** 素材文件列表：audio/*.ogg + textures/*.png。 */
    private static List<Path> files() {
        List<Path> out = new ArrayList<>();
        try {
            if (Files.isDirectory(audioDir())) {
                try (var s = Files.list(audioDir())) {
                    s.filter(p -> p.getFileName().toString().endsWith(".ogg")).forEach(out::add);
                }
            }
        } catch (Exception ignored) {
            // 目录不可读跳过
        }
        try {
            if (Files.isDirectory(texturesDir())) {
                try (var s = Files.list(texturesDir())) {
                    s.filter(p -> p.getFileName().toString().endsWith(".png")).forEach(out::add);
                }
            }
        } catch (Exception ignored) {
            // 目录不可读跳过
        }
        return out;
    }

    /** 素材清单 JSON：[{name,size,hash}]（按名称排序，稳定且可对比；哈希按 size+mtime 缓存）。 */
    public static String manifestJson() {
        JsonArray a = new JsonArray();
        List<Path> fs = files();
        fs.sort((x, y) -> x.getFileName().toString().compareTo(y.getFileName().toString()));
        for (Path p : fs) {
            try {
                JsonObject o = new JsonObject();
                o.addProperty("name", p.getFileName().toString());
                o.addProperty("size", Files.size(p));
                o.addProperty("hash", hashOf(p));
                a.add(o);
            } catch (Exception ignored) {
                // 单个素材读取失败跳过（不阻塞清单）
            }
        }
        return a.toString();
    }

    /** 哈希（带 stat 缓存：size/mtime 未变直接复用，登录/广播不再重复全量哈希）。 */
    private static String hashOf(Path p) throws Exception {
        String name = p.getFileName().toString();
        long size = Files.size(p);
        long mtime = Files.getLastModifiedTime(p).toMillis();
        CacheEntry e = hashCache.get(name);
        if (e != null && e.size() == size && e.mtime() == mtime) {
            return e.hash();
        }
        String h = sha256(p);
        hashCache.put(name, new CacheEntry(size, mtime, h));
        return h;
    }

    private static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[65536];
        try (var in = Files.newInputStream(p)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 下发清单给单个玩家（登录时）。 */
    public static void sendManifest(ServerPlayer player) {
        if (player == null) {
            return;
        }
        RpChannels.sendTo(player, new RpPackets.AssetManifestS2C(manifestJson()));
    }

    /** 全服下发清单（素材变更后：音乐上传/管理端 CRUD 后，客户端即时感知新素材）。 */
    public static void broadcastManifest() {
        RpChannels.sendToAll(new RpPackets.AssetManifestS2C(manifestJson()));
    }

    /** 素材下载请求（C2S）：名称校验（防路径穿越）后交后台线程按 32KB 分片流式下发（不阻塞主线程）。 */
    public static void onRequest(ServerPlayer player, String name) {
        if (player == null || name == null || !NAME_PATTERN.matcher(name).matches()) {
            return;
        }
        Path file = resolveFile(name);
        if (file == null) {
            return;
        }
        STREAMER.execute(() -> streamTo(player, file, name));
    }

    private static void streamTo(ServerPlayer player, Path file, String name) {
        try {
            if (player.connection == null
                    || player.connection.connection == null
                    || !player.connection.connection.isConnected()
                    || !RpChannels.hasChannel(player.connection.connection)) {
                return; // 已掉线/无通道：放弃下发
            }
            byte[] data = Files.readAllBytes(file);
            int total = Math.max(1, (data.length + PART_SIZE - 1) / PART_SIZE);
            for (int i = 0; i < total; i++) {
                if (player.connection == null || !player.connection.connection.isConnected()) {
                    return; // 中途掉线：中断下发
                }
                byte[] chunk =
                        java.util.Arrays.copyOfRange(data, i * PART_SIZE, Math.min((i + 1) * PART_SIZE, data.length));
                RpChannels.sendTo(player, new RpPackets.AssetPartS2C(name, i, total, chunk));
            }
            LOGGER.debug(
                    "[CCNR-RP] 素材下发 {}（{} 分片）→ {}",
                    name,
                    total,
                    player.getName().getString());
        } catch (Exception e) {
            LOGGER.warn("[CCNR-RP] 素材读取失败: {} —— {}", name, e.getMessage());
        }
    }

    // ---------- 同步门（同步完成前禁用部署/复活） ----------

    /** 登录下发清单时标记为待同步（仅当客户端有通道时调用）。 */
    public static void markPending(ServerPlayer player) {
        if (player != null) {
            pendingSync.put(player.getUUID(), System.currentTimeMillis());
        }
    }

    /** 客户端同步完成确认（AssetSyncDoneC2S）。 */
    public static void onSyncDone(ServerPlayer player) {
        if (player != null) {
            pendingSync.remove(player.getUUID());
            LOGGER.debug("[CCNR-RP] 素材同步完成: {}", player.getName().getString());
        }
    }

    /** 登出清理（防映射残留）。 */
    public static void clearPending(ServerPlayer player) {
        if (player != null) {
            pendingSync.remove(player.getUUID());
        }
    }

    /** 玩家素材同步是否完成：未标记=完成；超时兜底自动放行（防老客户端/异常永久锁死）。 */
    public static boolean isSynced(ServerPlayer player) {
        if (player == null) {
            return true;
        }
        Long since = pendingSync.get(player.getUUID());
        if (since == null) {
            return true;
        }
        if (System.currentTimeMillis() - since > SYNC_TIMEOUT_MS) {
            pendingSync.remove(player.getUUID());
            return true;
        }
        return false;
    }

    private static Path resolveFile(String name) {
        Path p = audioDir().resolve(name);
        if (Files.isRegularFile(p)) {
            return p;
        }
        p = texturesDir().resolve(name);
        return Files.isRegularFile(p) ? p : null;
    }
}
