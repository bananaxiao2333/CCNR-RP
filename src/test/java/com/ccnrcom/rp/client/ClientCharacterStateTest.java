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
}
