/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.sync;

import com.ccnrcom.rp.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 上报失败时的本地重试队列（FIFO，落盘）。
 *
 * <h2>为什么存的是 protobuf 字节而不是字段</h2>
 * 每条待重放请求按 {@code (方法名, 消息字节)} 存。用 {@code toByteArray()} 而不是把字段摊成
 * JSON：这样**重放与首次发送是同一条消息**，不存在「JSON 往返把某个字段丢了/改了类型」这类
 * 静默失真，也就不会出现「第一次上报是对的、重放后 payload 少了一项」。
 *
 * <h2>为什么必须落盘</h2>
 * 数据服务重启、网络抖动期间攒下的结算与事件，如果只放在内存里，模组一重启就永久丢失，
 * 而玩家看到的等级/战绩已经算过了——账就永久对不上。落盘用的是本模组统一的原子写
 * （{@link JsonUtil#atomicWrite}：tmp + rename，损坏保留 .bak）。
 *
 * <h2>为什么是严格 FIFO</h2>
 * 队列头一条发不出去就不发后面的：上位机（本类）只负责保持顺序，去重靠数据服务侧的
 * {@code request_id} 幂等键。乱序重放会让同一玩家的事件时间线错乱，而重复重放是无害的——
 * 这个取舍是有意的。
 *
 * <p><b>线程</b>：只在服务端主线程访问（与模组的其他存储一致），因此内部不做同步。
 */
public final class DataOutbox {
    private static final Logger LOGGER = LogManager.getLogger();

    /** 一条待重放请求。{@code method} 是 {@link DataLink} 里的方法标识，不是 HTTP 路径。 */
    public record Entry(String method, byte[] payload) {}

    private final Path file;
    private final int max;
    private final Deque<Entry> queue = new ArrayDeque<>();
    /** 因超限被丢弃的累计条数（诊断用；只增不减）。 */
    private long dropped = 0;

    public DataOutbox(Path file, int max) {
        this.file = file;
        this.max = Math.max(1, max);
    }

    /** 启动时载入。文件缺失/损坏 = 空队列（损坏时 {@link JsonUtil} 已留 .bak）。 */
    public void load() {
        queue.clear();
        JsonUtil.readObject(file).ifPresent(root -> {
            JsonElement arr = root.get("pending");
            if (arr == null || !arr.isJsonArray()) {
                return;
            }
            for (JsonElement el : arr.getAsJsonArray()) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject o = el.getAsJsonObject();
                if (!o.has("method") || !o.has("payload")) {
                    continue;
                }
                try {
                    queue.addLast(new Entry(
                            o.get("method").getAsString(),
                            Base64.getDecoder().decode(o.get("payload").getAsString())));
                } catch (IllegalArgumentException e) {
                    // 单条坏数据跳过，不阻塞其余（坏一条就整队作废是更糟的选择）
                    LOGGER.warn("[CCNR-RP] 重试队列有一条 payload 不是合法 base64，已跳过");
                }
            }
        });
        if (!queue.isEmpty()) {
            LOGGER.info("[CCNR-RP] 载入未发送的上报 {} 条，将按序重放", queue.size());
        }
    }

    /** 入队并立即落盘。超限时丢**最旧**的一条：新的比旧的有用，且旧的那条多半已被保留策略覆盖。 */
    public void add(Entry e) {
        queue.addLast(e);
        while (queue.size() > max) {
            queue.pollFirst();
            dropped++;
            if (dropped == 1 || dropped % 100 == 0) {
                LOGGER.warn("[CCNR-RP] 上报队列超过 {} 条，已丢弃最旧的累计 {} 条（数据服务长时间不可达？）", max, dropped);
            }
        }
        save();
    }

    /** 队首（没有则 null）。 */
    public Entry peek() {
        return queue.peekFirst();
    }

    /** 出队一条并落盘（仅在发送成功后调用）。 */
    public void removeFirst() {
        if (queue.pollFirst() != null) {
            save();
        }
    }

    public int size() {
        return queue.size();
    }

    public long dropped() {
        return dropped;
    }

    public void clear() {
        queue.clear();
        dropped = 0;
    }

    /** 原子写盘。 */
    public void save() {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (Entry e : queue) {
            JsonObject o = new JsonObject();
            o.addProperty("method", e.method());
            o.addProperty("payload", Base64.getEncoder().encodeToString(e.payload()));
            arr.add(o);
        }
        root.addProperty("note", "CCNR-RP 上报重试队列（勿手改：payload 是 protobuf 字节的 base64）");
        root.add("pending", arr);
        JsonUtil.atomicWrite(file, root);
    }

    /** 诊断快照（/rp data 与面板用）。 */
    public List<String> describe() {
        return List.of("pending=" + queue.size(), "dropped=" + dropped);
    }
}
