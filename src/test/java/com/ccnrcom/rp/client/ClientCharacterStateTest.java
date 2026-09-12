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

    // ---------- 管理权限镜像（K 面板 → 管理面板那道门） ----------

    /** 服务端列表载荷里的 admin 字段即管理权限来源。 */
    private static void setListWithAdmin(boolean admin) {
        JsonObject root = new JsonObject();
        root.addProperty("admin", admin);
        ClientCharacterState.setList(JsonUtil.GSON.toJson(root));
    }

    /** 服务端说是管理员 → 镜像为真。 */
    @Test
    void adminMirroredFromListPayload() {
        setListWithAdmin(true);
        org.junit.jupiter.api.Assertions.assertTrue(ClientCharacterState.isAdmin());
    }

    /**
     * **换服/重连必须清掉管理权限**（默认拒绝）。
     *
     * <p>回归点：不这么做的话，在 A 服是管理员、切到 B 服后（或重连后尚未收到列表前）会沿用上一个服的
     * {@code isAdmin=true}，管理面板就能被没有权限的人打开。服务端随后会用列表包重新下发真值，
     * 所以清成 false 不会误伤真管理员。
     */
    @Test
    void resetForJoinDropsAdminPermission() {
        setListWithAdmin(true);
        org.junit.jupiter.api.Assertions.assertTrue(ClientCharacterState.isAdmin());
        ClientCharacterState.resetForJoin();
        org.junit.jupiter.api.Assertions.assertFalse(
                ClientCharacterState.isAdmin(), "resetForJoin() 必须把管理权限清成 false（跨服不得沿用）");
    }
}
