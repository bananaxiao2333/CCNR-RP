/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccnrcom.rp.event.EventModels.EventDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * 事件定义解析：hooks 段兼容（docs/07 §3、docs/15 §6）。
 *
 * <p>回归背景：`hooks.notify.titleKey` 此前**不被读取**——docs/07 §3 的示例写法在游戏里
 * 没有任何效果（通报静默丢失），只有顶层 `notifyTitleKey` 生效。本用例把两种写法都钉住，
 * 防止文档与实现的偏差再次出现。
 */
class EventModelsTest {

    private static EventDefinition eventWith(JsonObject hooks) {
        JsonObject o = new JsonObject();
        o.addProperty("id", "evac_alert");
        o.addProperty("enabled", true);
        o.add("triggers", new JsonArray());
        if (hooks != null) {
            o.add("hooks", hooks);
        }
        return EventDefinitionHolder.parse(o);
    }

    /** docs/07 §3 的写法：通报键写在 hooks.notify.titleKey。 */
    @Test
    void readsNotifyTitleKeyFromHooksSection() {
        JsonObject notify = new JsonObject();
        notify.addProperty("titleKey", "ccnr_rp.event.evac.title");
        JsonObject hooks = new JsonObject();
        hooks.add("notify", notify);

        assertEquals("ccnr_rp.event.evac.title", eventWith(hooks).notifyTitleKey());
    }

    /** 顶层 notifyTitleKey 优先（旧写法不被 hooks 段覆盖）。 */
    @Test
    void topLevelNotifyTitleKeyWins() {
        JsonObject notify = new JsonObject();
        notify.addProperty("titleKey", "from.hooks");
        JsonObject hooks = new JsonObject();
        hooks.add("notify", notify);
        JsonObject o = new JsonObject();
        o.addProperty("id", "evac_alert");
        o.addProperty("notifyTitleKey", "from.top");
        o.add("hooks", hooks);

        assertEquals("from.top", EventDefinitionHolder.parse(o).notifyTitleKey());
    }

    /** startAnimation / spawnWave 同样支持 hooks 段（此前已有行为，一并钉住）。 */
    @Test
    void readsAnimationAndWaveFromHooksSection() {
        JsonObject hooks = new JsonObject();
        hooks.addProperty("startAnimation", "scp_alarm");
        hooks.addProperty("spawnWave", "scp_mtf_reinforce");

        EventDefinition def = eventWith(hooks);
        assertEquals("scp_alarm", def.startAnimation());
        assertEquals("scp_mtf_reinforce", def.spawnWave());
    }

    /** 边界：没有 hooks / notify 不是对象 / titleKey 为空 → 通报键为空串，不抛异常。 */
    @Test
    void missingNotifyYieldsEmptyKey() {
        assertEquals("", eventWith(null).notifyTitleKey());

        JsonObject notifyNotObject = new JsonObject();
        notifyNotObject.addProperty("notify", "oops");
        assertEquals("", eventWith(notifyNotObject).notifyTitleKey());

        JsonObject emptyNotify = new JsonObject();
        emptyNotify.add("notify", new JsonObject());
        assertEquals("", eventWith(emptyNotify).notifyTitleKey());
    }

    /** 边界：hooks 存在但为空对象（配置里写了 "hooks": {}）→ 各字段空串，不抛异常。 */
    @Test
    void emptyHooksObjectIsHarmless() {
        EventDefinition def = eventWith(new JsonObject());
        assertEquals("", def.notifyTitleKey());
        assertEquals("", def.startAnimation());
        assertEquals("", def.spawnWave());
        assertTrue(def.enabled());
    }

    /** 解析入口的小包装：把 Optional 拆开，让失败看起来像断言失败而不是 NPE。 */
    private static final class EventDefinitionHolder {
        static EventDefinition parse(JsonObject o) {
            return EventModels.parseEvent(o).orElseThrow(() -> new AssertionError("parseEvent 返回空（缺 id？）"));
        }
    }
}
