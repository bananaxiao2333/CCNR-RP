/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ccnrcom.rp.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/** ClientCharacterState 职业简历（profile）读取回归测试：存在/缺失/空串/未知职业四种路径。 */
class ClientCharacterStateTest {

    /** 构造仅含 professions 的最小列表载荷；profile 为 null 表示不写该字段。 */
    private static void setProfessions(String... profiles) {
        JsonObject root = new JsonObject();
        JsonArray pa = new JsonArray();
        for (int i = 0; i < profiles.length; i++) {
            JsonObject p = new JsonObject();
            p.addProperty("id", "prof_" + i);
            p.addProperty("factionId", "fac");
            if (profiles[i] != null) {
                p.addProperty("profile", profiles[i]);
            }
            pa.add(p);
        }
        root.add("professions", pa);
        ClientCharacterState.setList(JsonUtil.GSON.toJson(root));
    }

    @Test
    void profilePresent() {
        setProfessions("前量子安保，现驻守 C-3 区");
        assertEquals("前量子安保，现驻守 C-3 区", ClientCharacterState.professionProfile("prof_0"));
    }

    @Test
    void profileMissingReturnsEmpty() {
        setProfessions((String) null);
        assertEquals("", ClientCharacterState.professionProfile("prof_0"));
    }

    @Test
    void profileBlankReturnsEmpty() {
        setProfessions("");
        assertEquals("", ClientCharacterState.professionProfile("prof_0"));
    }

    @Test
    void unknownProfessionReturnsEmpty() {
        setProfessions("某简历");
        assertEquals("", ClientCharacterState.professionProfile("no_such_prof"));
    }
    // ---------- 已注册属性 id 镜像（「阵营属性档案」属性 id 输入框补全数据源） ----------

    private static void setManagerWithAttributeIds(String... ids) {
        JsonObject root = new JsonObject();
        root.addProperty("admin", true);
        JsonArray arr = new JsonArray();
        for (String id : ids) {
            arr.add(id);
        }
        root.add("attributeIds", arr);
        ClientCharacterState.setManager(JsonUtil.GSON.toJson(root));
    }

    /** 正常路径：服务端下发的已注册属性 id 按序镜像。 */
    @Test
    void attributeIdsMirroredInOrder() {
        setManagerWithAttributeIds("minecraft:generic.max_health", "minecraft:generic.armor", "firstaid:part_head");
        assertEquals(
                java.util.List.of("minecraft:generic.max_health", "minecraft:generic.armor", "firstaid:part_head"),
                ClientCharacterState.attributeIds());
    }

    /** 兼容边界：旧服务端载荷没有 attributeIds → 空列表（不抛异常、不残留上一次的值）。 */
    @Test
    void missingAttributeIdsClearsInsteadOfKeepingStaleValues() {
        setManagerWithAttributeIds("minecraft:generic.max_health");
        assertEquals(1, ClientCharacterState.attributeIds().size());
        JsonObject root = new JsonObject();
        root.addProperty("admin", true);
        ClientCharacterState.setManager(JsonUtil.GSON.toJson(root));
        assertEquals(java.util.List.of(), ClientCharacterState.attributeIds());
    }
}
