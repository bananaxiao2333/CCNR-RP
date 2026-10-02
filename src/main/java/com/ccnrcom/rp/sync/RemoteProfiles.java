/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.sync;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「数据服务确认过的玩家数值」的本地镜像 —— **界面显示的等级只能从这里取**。
 *
 * <h2>为什么不直接用模组自己算出来的等级</h2>
 * 模组服务端是等级曲线的权威端（曲线在 {@code experience.LevelCurve}），但它算出来只是一个
 * 内存里的数，**没有任何一条路径保证这个数已经落到了数据侧**。上报可能还在重试队列里、
 * 可能被数据服务拒绝。此时把本地数画到界面上，玩家看到的就是一个「账上并不存在」的等级。
 *
 * <p>所以规则是：**要求界面显示的值必须已经由数据服务确认过**（结算响应里的
 * {@code xp_after}/{@code level_after} 回显，或 {@code GetPlayer} 的返回）。
 * 确认不了就 {@link #forget} —— 界面不显示等级，而不是回退本地推算。
 * 回退会更糟：本地推算与确认值不一致时，玩家看到的是「一会儿 7 级一会儿 6 级」。
 *
 * <h2>为什么按 uuid 而不是一个全局开关</h2>
 * 「数据服务通不通」是全局面，但「这个玩家的数确认了没有」是玩家面的：断线重连、迟到的大批
 * 结算、单个玩家被数据服务拒绝（INVALID_ARGUMENT）都会造成某个玩家的数没确认，而其他玩家一切正常。
 * 用全局开关会让一个人的问题把全服的等级显示一起关掉。
 *
 * <p><b>线程</b>：只在服务端主线程访问（所有写入都来自 gRPC 回调被派发回主线程之后）。
 */
public final class RemoteProfiles {

    /** 数据服务确认过的快照。 */
    public record Snapshot(long xp, int level) {}

    private final Map<String, Snapshot> byUuid = new HashMap<>();
    private final Map<String, String> lastError = new HashMap<>();

    /** 写值：数据服务确认后调用。 */
    public void put(String uuid, long xp, long level) {
        if (uuid == null || uuid.isBlank()) {
            return;
        }
        byUuid.put(uuid, new Snapshot(xp, clampLevel(level)));
        lastError.remove(uuid);
    }

    /**
     * 契约上是 int64、模组域内是 int（{@code LevelCurve.level()} 就返回 int，
     * 且曲线上限已经钳到 {@link Integer#MAX_VALUE}）。这里做一次钳制转换：
     * 直接 {@code (int)} 强转在越界时会**回绕成负数**，界面上就会出现「-21 级」。
     */
    private static int clampLevel(long level) {
        if (level < 0) {
            return 0;
        }
        return level > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) level;
    }

    /** 忘掉：确认失败 / 连接断开 / 该玩家被数据服务拒绝时调用，界面随即不再显示等级。 */
    public void forget(String uuid) {
        if (uuid != null) {
            byUuid.remove(uuid);
        }
    }

    /** 忘掉该玩家并记下原因（诊断用）。 */
    public void forget(String uuid, String reason) {
        forget(uuid);
        if (uuid != null && reason != null) {
            lastError.put(uuid, reason);
        }
    }

    /** 已确认的快照；从未确认过返回 null（调用方据此**不显示**而不是显示 0）。 */
    public Snapshot get(String uuid) {
        return uuid == null ? null : byUuid.get(uuid);
    }

    public boolean known(String uuid) {
        return uuid != null && byUuid.containsKey(uuid);
    }

    /** 诊断快照（/rp data 用）。 */
    public JsonObject describe() {
        JsonObject o = new JsonObject();
        o.addProperty("known", byUuid.size());
        o.addProperty("failed", lastError.size());
        var it = lastError.entrySet().iterator();
        List<String> sample = new ArrayList<>();
        while (it.hasNext() && sample.size() < 5) {
            var e = it.next();
            sample.add(e.getKey().substring(0, Math.min(8, e.getKey().length())) + "=" + e.getValue());
        }
        if (!sample.isEmpty()) {
            o.addProperty("recentFailures", String.join(", ", sample));
        }
        return o;
    }

    /** 复位（换局 / 停机）。 */
    public void clear() {
        byUuid.clear();
        lastError.clear();
    }
}
