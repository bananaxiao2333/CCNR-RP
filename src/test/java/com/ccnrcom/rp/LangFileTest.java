/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * 语言包一致性回归测试：防止"只更新 zh_cn 漏掉 en_us"（红字原始键）或键漂移。
 * 镜像自 CCNR-Com 的 LangFileTest 思路。
 */
class LangFileTest {

    private static final Set<String> REQUIRED_KEYS =
            Set.of("ccnr_rp.mod.name", "ccnr_rp.command.help", "ccnr_rp.status.alive", "ccnr_rp.status.dead");

    private Map<String, String> load(String path) {
        try (InputStream in = LangFileTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "资源不存在: " + path);
            return new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), (Type) Map.class);
        } catch (Exception e) {
            throw new AssertionError("语言包解析失败: " + path, e);
        }
    }

    @Test
    void zhAndEnHaveIdenticalKeys() {
        Map<String, String> zh = load("/assets/ccnr_rp/lang/zh_cn.json");
        Map<String, String> en = load("/assets/ccnr_rp/lang/en_us.json");
        assertEquals(new TreeSet<>(zh.keySet()), new TreeSet<>(en.keySet()), "zh_cn 与 en_us 的键集合不一致");
    }

    @Test
    void allKeysFollowNamespaceAndRequiredKeysExist() {
        Map<String, String> zh = load("/assets/ccnr_rp/lang/zh_cn.json");
        for (String key : zh.keySet()) {
            assertTrue(key.startsWith("ccnr_rp."), "键未遵循命名空间: " + key);
            assertFalse(key.equals(zh.get(key)), "值渲染成了原始键（漏翻译）: " + key);
        }
        assertTrue(
                zh.keySet().containsAll(REQUIRED_KEYS),
                "缺少必需键，差异: "
                        + new TreeSet<>(REQUIRED_KEYS)
                                .stream().filter(k -> !zh.containsKey(k)).toList());
    }
}
