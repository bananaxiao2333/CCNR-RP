/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.util;

import com.google.gson.JsonObject;

/**
 * 对局状态展示元数据（模式 / 阶段 / 事件共用）：**显示名 + 描述 + 图标**。
 *
 * <p><b>为什么需要它</b>：这三类实体过去在界面上只露内部 id——事件横幅直接画
 * {@code g.drawCenteredString(font, Component.literal(id)...)}，玩家看到的是
 * {@code qdf_support} 这种英文串；管理员想改个中文名也无处可填。把"给人看的三件套"
 * 收成一个值类型后，解析、网络投影与渲染各只有一份实现。
 *
 * <p><b>图标取值</b>：内置矢量图形名（{@code hex/shield/claw/storm/eye/target/cross/gear/helm/chest/back/heart}，
 * 见 {@code RpIcons.iconPolygon}），或 {@code img:<名>}（服务器素材库图片，进服自动下发）。
 * 空串 = 不画图标。
 *
 * <p><b>纯数据、无 MC import</b>，可直接 JUnit 测（docs/01 §4：规则/解析逻辑一律纯类）。
 */
public record DisplayInfo(String name, String desc, String icon) {

    /** 三项皆空的占位值（未配置时的缺省）。 */
    public static final DisplayInfo EMPTY = new DisplayInfo("", "", "");

    /**
     * 从 JSON 读取三件套（{@code name}/{@code desc}/{@code icon}，缺失或非字符串按空串）。
     * null / 非对象返回 {@link #EMPTY}——缺展示元数据不是错误，界面自然会回退 id。
     */
    public static DisplayInfo parse(JsonObject o) {
        if (o == null) {
            return EMPTY;
        }
        return new DisplayInfo(str(o, "name"), str(o, "desc"), str(o, "icon"));
    }

    /**
     * 显示名：空则回退 {@code fallback}（通常是实体 id）。
     * 界面永远不该露出空串，所以"回退"这件事收在这里，而不是每个渲染点各判一次。
     */
    public String nameOr(String fallback) {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return fallback == null ? "" : fallback;
    }

    public boolean hasIcon() {
        return icon != null && !icon.isBlank();
    }

    public boolean hasDesc() {
        return desc != null && !desc.isBlank();
    }

    /** 写入 JSON（三个键都写，空值写空串，便于管理面板回显与手改）。 */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("name", nz(name));
        o.addProperty("desc", nz(desc));
        o.addProperty("icon", nz(icon));
        return o;
    }

    private static String str(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            return "";
        }
        try {
            return o.get(key).getAsString();
        } catch (Exception e) {
            return ""; // 非字符串（数字/对象）按未配置处理，不抛异常
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
