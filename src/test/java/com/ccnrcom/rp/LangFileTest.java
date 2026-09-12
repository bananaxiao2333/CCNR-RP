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
 * 镜像自 CCNR-Com 的 LangFileTest 思路。键命名空间允许 ccnr_rp.* 与 MC 键位约定 key.*。
 */
class LangFileTest {

    private static final Set<String> REQUIRED_KEYS =
            Set.of("ccnr_rp.mod.name", "ccnr_rp.command.help", "ccnr_rp.status.alive", "ccnr_rp.status.dead");

    /**
     * 自定义设定（docs/17）的必需键：命令输出与页签标签漏掉任何一个都会在游戏里显示成原始键。
     * 新增页签/命令时在这里登记，漏翻会被本用例挡下。
     */
    private static final Set<String> VARIABLE_KEYS = Set.of(
            "ccnr_rp.command.usage.var",
            "ccnr_rp.command.var.list_header",
            "ccnr_rp.command.var.unknown",
            "ccnr_rp.command.var.saved",
            "ccnr_rp.command.var.preset_applied",
            "ccnr_rp.command.var.scheme_applied",
            "ccnr_rp.gui.admin.tab.variables",
            "ccnr_rp.gui.admin.var.list",
            "ccnr_rp.gui.admin.var.id",
            "ccnr_rp.gui.admin.var.preset_value",
            "ccnr_rp.gui.admin.var.scheme_id");

    /** 已删除功能（区域 / 弹头许可，docs/16 §5；入场电影版式切换开关，docs/14 §6）的键必须清理干净，不得留死键。 */
    private static final Set<String> REMOVED_KEYS = Set.of(
            "ccnr_rp.command.usage.area",
            "ccnr_rp.command.area.list_header",
            "ccnr_rp.command.area.info",
            "ccnr_rp.command.faction.warhead",
            "ccnr_rp.gui.admin.area.list",
            "ccnr_rp.gui.admin.warhead.list",
            "ccnr_rp.gui.admin.faction.warhead",
            "ccnr_rp.gui.admin.tab.extension",
            "ccnr_rp.gui.admin.faction.cinematic_compact",
            // K 面板三栏结构重设计（docs/14 §5.6）：旧三栏列头与外部参考界面的窗体名不再存在，
            // 对应键必须随结构一起清掉，避免"删结构留死键"。
            "ccnr_rp.gui.character.nav",
            "ccnr_rp.gui.character.position_list",
            "ccnr_rp.gui.character.db_header",
            "ccnr_rp.gui.character.net_header");

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
            assertTrue(key.startsWith("ccnr_rp.") || key.startsWith("key."), "键未遵循命名空间: " + key);
            assertFalse(key.equals(zh.get(key)), "值渲染成了原始键（漏翻译）: " + key);
        }
        assertTrue(
                zh.keySet().containsAll(REQUIRED_KEYS),
                "缺少必需键，差异: "
                        + new TreeSet<>(REQUIRED_KEYS)
                                .stream().filter(k -> !zh.containsKey(k)).toList());
    }

    @Test
    void variableFeatureKeysExistInBothLanguages() {
        for (String path : new String[] {"/assets/ccnr_rp/lang/zh_cn.json", "/assets/ccnr_rp/lang/en_us.json"}) {
            Map<String, String> lang = load(path);
            assertTrue(
                    lang.keySet().containsAll(VARIABLE_KEYS),
                    path + " 缺少自定义设定键: "
                            + new TreeSet<>(VARIABLE_KEYS)
                                    .stream().filter(k -> !lang.containsKey(k)).toList());
        }
    }

    @Test
    void removedFeatureKeysAreGone() {
        for (String path : new String[] {"/assets/ccnr_rp/lang/zh_cn.json", "/assets/ccnr_rp/lang/en_us.json"}) {
            Map<String, String> lang = load(path);
            Set<String> leftovers = new TreeSet<>(REMOVED_KEYS);
            leftovers.retainAll(lang.keySet());
            assertTrue(leftovers.isEmpty(), path + " 残留已删除功能的死键: " + leftovers);
        }
    }
}
