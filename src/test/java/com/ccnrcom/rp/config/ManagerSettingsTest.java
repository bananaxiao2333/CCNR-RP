/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * 管理器设置（settings.json）键表与默认值的结构守卫：2.26.14 新增「部署后冒险模式」开关时加。
 *
 * <p>只读静态成员 + 反射读 {@code defaults()}，**不构造 ManagerSettings 实例**（构造会经
 * {@link com.ccnrcom.rp.data.ConfigStore} 落盘，单元测试不该写用户的配置文件）。
 *
 * <p>挡的是两类静默事故：
 * <ol>
 *   <li>新增开关只写进 {@code defaults()} 却忘了 {@code keys()}（面板根本不显示该行 → 开关"不存在"）；</li>
 *   <li>{@code deployAdventureMode} 的默认值被改成 true——那会让所有服务器的在场玩家一律失去破坏/交互能力，
 *       属于"默认值改变既有体验"，docs/01 §9.5 明确禁止。</li>
 * </ol>
 */
class ManagerSettingsTest {

    /** 反射读私有 defaults()（不实例化，避免 ConfigStore 写盘）。 */
    private static JsonObject defaults() throws Exception {
        Method m = ManagerSettings.class.getDeclaredMethod("defaults");
        m.setAccessible(true);
        return (JsonObject) m.invoke(null);
    }

    @Test
    void everyKeyInKeysHasADefault() throws Exception {
        JsonObject d = defaults();
        for (String key : ManagerSettings.keys()) {
            assertTrue(d.has(key), "管理面板会渲染该键，但 defaults() 里没有它: " + key);
        }
    }

    @Test
    void deployAdventureModeIsPresentAndBoolean() {
        assertTrue(ManagerSettings.keys().contains("deployAdventureMode"), "部署模式开关必须出现在管理面板「设置」页的键表里");
        assertEquals("bool", ManagerSettings.type("deployAdventureMode"), "部署模式开关必须是开关行（bool）");
    }

    @Test
    void deployAdventureModeDefaultsToOff() throws Exception {
        // 默认关闭 = 部署仍下发生存（原行为）；打开才切冒险
        assertFalse(defaults().get("deployAdventureMode").getAsBoolean(), "默认值必须是 false：打开会让所有在场玩家默认不能破坏/交互");
    }
}
