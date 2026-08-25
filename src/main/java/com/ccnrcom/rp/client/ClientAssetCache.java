/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import com.ccnrcom.rp.network.RpChannels;
import com.ccnrcom.rp.network.RpPackets;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 客户端素材缓存（服务器中央下发）：config/ccnr_rp/assets-cache/。
 * 全异步：清单解析/磁盘比对/写盘/拼接在后台线程执行，分片到达（网络线程）仅 O(1) 累积，
 * 绝不阻塞主线程——进服不会卡顿。下载完成后向服务端确认（AssetSyncDoneC2S），
 * 服务端在确认前禁用部署（复活）。播放/渲染一律使用本缓存（服务器控制素材）。
 */
public final class ClientAssetCache {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Path DIR = FMLPaths.CONFIGDIR.get().resolve("ccnr_rp").resolve("assets-cache");
    /** 单个素材请求超时（分片未到达则放弃，避免卡死）。 */
    private static final long REQUEST_TIMEOUT_MS = 15_000L;

    private record Meta(long size, String hash) {}

    /** 分片累积：chunk 列表（避免主线程 O(n²) 数组拷贝），末片一次性拼接。 */
    private record PartBuffer(List<byte[]> chunks, int expected) {}

    /** 服务端清单：素材名 → 元数据。 */
    private static final Map<String, Meta> manifest = new LinkedHashMap<>();
    /** 待下载队列。 */
    private static final Queue<String> pending = new ArrayDeque<>();
    /** 正在下载的素材名（null=空闲）。 */
    private static String downloading = null;

    private static long requestedAt = 0;
    /** 分片累积缓冲（按素材名）。 */
    private static final Map<String, PartBuffer> parts = new HashMap<>();
    /** 已缓存：素材名 → hash（内存加速，跨会话时从磁盘边车恢复）。 */
    private static final Map<String, Meta> downloaded = new ConcurrentHashMap<>();
    /** 服务器图标动态纹理缓存：文件名 → [ResourceLocation, 边长]。 */
    private static final Map<String, Object[]> iconTextures = new ConcurrentHashMap<>();
    /** 下载失败/超时已放弃的素材（本清单内不再重试）。 */
    private static final java.util.Set<String> skipped = ConcurrentHashMap.newKeySet();
    /** 本清单是否已向服务端确认同步完成。 */
    private static boolean syncDoneSent = false;

    /** 后台线程：清单解析、分片拼接、磁盘写入、请求调度。 */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ccnr-rp-assets");
        t.setDaemon(true);
        return t;
    });

    private ClientAssetCache() {}

    /** 服务端清单到达：解析与磁盘比对放到后台线程，主线程零开销。 */
    public static void applyManifest(String payload) {
        EXECUTOR.execute(() -> {
            synchronized (ClientAssetCache.class) {
                manifest.clear();
                pending.clear();
                skipped.clear();
                syncDoneSent = false;
                try {
                    JsonArray a = com.ccnrcom.rp.util.JsonUtil.GSON.fromJson(payload, JsonArray.class);
                    if (a != null) {
                        for (JsonElement e : a) {
                            if (!e.isJsonObject()) {
                                continue;
                            }
                            JsonObject o = e.getAsJsonObject();
                            String name = o.has("name") && !o.get("name").isJsonNull()
                                    ? o.get("name").getAsString()
                                    : "";
                            if (name.isBlank()) {
                                continue;
                            }
                            long size = o.has("size") ? o.get("size").getAsLong() : 0;
                            String hash = o.has("hash") && !o.get("hash").isJsonNull()
                                    ? o.get("hash").getAsString()
                                    : "";
                            Meta m = new Meta(size, hash);
                            manifest.put(name, m);
                            if (!isCached(name, m)) {
                                pending.add(name);
                            }
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("[CCNR-RP] 素材清单解析失败: {}", e.getMessage());
                }
                pump();
                maybeAckDone();
            }
        });
    }

    private static boolean isCached(String name, Meta meta) {
        if (name == null || name.isBlank() || meta == null) {
            return false;
        }
        Meta mem = downloaded.get(name);
        if (mem != null && mem.hash().equals(meta.hash()) && Files.isRegularFile(cacheFile(name))) {
            return true;
        }
        // 冷启动：磁盘已有跨会话缓存（文件 + .hash 边车一致才认可）
        if (meta.hash() != null && !meta.hash().isBlank()) {
            try {
                String disk = Files.readString(cacheFile(name + ".hash"), StandardCharsets.UTF_8)
                        .trim();
                if (disk.equals(meta.hash()) && Files.isRegularFile(cacheFile(name))) {
                    downloaded.put(name, meta);
                    return true;
                }
            } catch (Exception ignored) {
                // 无 hash 边车 → 视为未缓存
            }
        }
        return false;
    }

    /** 依次请求下载（一次一个，避免分片洪泛；完成后自动请求下一个）。 */
    private static void pump() {
        if (downloading != null) {
            return;
        }
        while (!pending.isEmpty()) {
            String name = pending.poll();
            Meta m = manifest.get(name);
            if (m == null || skipped.contains(name)) {
                continue;
            }
            if (isCached(name, m)) {
                continue;
            }
            downloading = name;
            requestedAt = System.currentTimeMillis();
            RpChannels.sendToServer(new RpPackets.AssetRequestC2S(name));
            return;
        }
    }

    /** 分片到达（网络线程，主线程零参与）：O(1) 累积；末片交给后台线程拼接写盘。 */
    public static void onPart(String name, int index, int total, byte[] data) {
        List<byte[]> collected = null;
        synchronized (ClientAssetCache.class) {
            if (name == null || data == null) {
                return;
            }
            PartBuffer cur = parts.get(name);
            if (cur == null) {
                cur = new PartBuffer(new ArrayList<>(), total);
                parts.put(name, cur);
            }
            cur.chunks().add(data);
            if (cur.chunks().size() >= cur.expected()) {
                parts.remove(name);
                collected = new ArrayList<>(cur.chunks());
            }
        }
        if (collected != null) {
            final List<byte[]> chunks = collected;
            EXECUTOR.execute(() -> finishDownload(name, chunks));
        }
    }

    private static void finishDownload(String name, List<byte[]> chunks) {
        String hash = "";
        try {
            int size = 0;
            for (byte[] c : chunks) {
                size += c.length;
            }
            byte[] all = new byte[size];
            int off = 0;
            for (byte[] c : chunks) {
                System.arraycopy(c, 0, all, off, c.length);
                off += c.length;
            }
            Files.createDirectories(DIR);
            Files.write(cacheFile(name), all);
            synchronized (ClientAssetCache.class) {
                Meta m = manifest.get(name);
                hash = m == null ? "" : m.hash();
            }
            Files.writeString(cacheFile(name + ".hash"), hash, StandardCharsets.UTF_8);
            synchronized (ClientAssetCache.class) {
                downloaded.put(name, new Meta(all.length, hash));
                LOGGER.info("[CCNR-RP] 素材已下载: {}（{} 字节）", name, all.length);
            }
        } catch (Exception e) {
            LOGGER.warn("[CCNR-RP] 素材写入失败: {} —— {}", name, e.getMessage());
        }
        synchronized (ClientAssetCache.class) {
            if (name.equals(downloading)) {
                downloading = null;
                requestedAt = 0;
            }
            pump();
            maybeAckDone();
        }
    }

    /** 每 tick（主线程，开销极小）：请求超时未到达 → 放弃该素材继续下一个，防卡死。 */
    public static void tick() {
        synchronized (ClientAssetCache.class) {
            if (downloading != null && System.currentTimeMillis() - requestedAt > REQUEST_TIMEOUT_MS) {
                LOGGER.warn("[CCNR-RP] 素材下载超时，跳过: {}", downloading);
                skipped.add(downloading);
                parts.remove(downloading);
                downloading = null;
                requestedAt = 0;
                pump();
                maybeAckDone();
            }
        }
    }

    /** 全部待下载项完成 → 向服务端确认（服务端据此放行部署/复活），并武装自动开面板。 */
    private static void maybeAckDone() {
        if (syncDoneSent) {
            return;
        }
        if (pending.isEmpty() && downloading == null) {
            syncDoneSent = true;
            RpChannels.sendToServer(new RpPackets.AssetSyncDoneC2S());
            LOGGER.info("[CCNR-RP] 素材同步完成，已向服务端确认");
            // 同步完成立刻开启 K 面板（走按键同一入口，由 ClientForgeEvents 消费）
            ClientCharacterState.armAutoOpenPanel();
        }
    }

    private static Path cacheFile(String name) {
        return DIR.resolve(name);
    }

    /** 素材是否已就绪（清单内且本地缓存匹配）。 */
    public static synchronized boolean isReady(String name) {
        Meta m = manifest.get(name);
        return m != null && isCached(name, m);
    }

    /** 解析素材路径：就绪返回缓存文件；清单内未就绪 → 触发请求并返回 null（下次就绪后生效）。 */
    public static synchronized Path resolve(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        if (isReady(name)) {
            return cacheFile(name);
        }
        if (manifest.containsKey(name)) {
            pump();
        }
        return null;
    }

    /** 是否正在同步素材（HUD 提示「同步数据中」；服务端同步完成前禁用部署）。 */
    public static synchronized boolean isSyncing() {
        return downloading != null || !pending.isEmpty();
    }

    /** 服务器阵营图标（img:<名>）：已下载则注册动态纹理并返回；未就绪触发请求并返回 null（回退内嵌）。 */
    public static ResourceLocation serverIcon(String fileName) {
        String asset = fileName + ".png";
        if (!isReady(asset)) {
            if (manifest.containsKey(asset)) {
                pump();
            }
            return null;
        }
        Object[] cached = iconTextures.get(fileName);
        if (cached != null) {
            return (ResourceLocation) cached[0];
        }
        try (var in = Files.newInputStream(cacheFile(asset))) {
            com.mojang.blaze3d.platform.NativeImage img = com.mojang.blaze3d.platform.NativeImage.read(in);
            ResourceLocation loc =
                    new ResourceLocation("ccnr_rp", "server_textures/" + fileName.toLowerCase(Locale.ROOT));
            Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(img));
            iconTextures.put(fileName, new Object[] {loc, img.getWidth()});
            LOGGER.info("[CCNR-RP] 服务器图标就绪: {}", fileName);
            return loc;
        } catch (Exception e) {
            LOGGER.warn("[CCNR-RP] 服务器图标加载失败: {} —— {}", fileName, e.getMessage());
            return null;
        }
    }

    /** 服务器图标边长（像素；未加载时默认 512 与内嵌图标一致）。 */
    public static int iconSize(String fileName) {
        Object[] cached = iconTextures.get(fileName);
        return cached == null ? 512 : (Integer) cached[1];
    }

    /** 服务器素材库中的图标名列表（img:<名> 可选值，去扩展名排序）；未同步时为空列表。 */
    public static synchronized java.util.List<String> iconNames() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String name : manifest.keySet()) {
            if (name.endsWith(".png")) {
                out.add("img:" + name.substring(0, name.length() - 4));
            }
        }
        out.sort(String::compareTo);
        return out;
    }
}
