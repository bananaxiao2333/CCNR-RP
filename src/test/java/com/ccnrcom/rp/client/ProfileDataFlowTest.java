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

/** 数据流回归：服务端 sendList 下发的职业 JSON 含 profile 字段，客户端镜像可读（K 面板/招募弹窗展示依赖）。 */
class ProfileDataFlowTest {

    private static String sendListStylePayload() {
        JsonObject root = new JsonObject();
        JsonArray pa = new JsonArray();
        JsonObject p = new JsonObject();
        p.addProperty("id", "hq_ceo");
        p.addProperty("name", "量子科学CEO");
        p.addProperty("factionId", "admin_hq");
        p.addProperty("unlockLevel", 0);
        p.addProperty("music", "");
        p.addProperty("profile", "设施内最高权限：封锁区域、部署QDF/QSA、降职决议（样板职业）。");
        p.addProperty("cmdcamScene", "");
        p.addProperty("radioDisabled", false);
        JsonObject loadout = new JsonObject();
        loadout.add("inventory", new JsonArray());
        loadout.add("armor", new JsonArray());
        loadout.add("offhand", new JsonObject());
        p.add("loadout", loadout);
        pa.add(p);
        root.add("professions", pa);
        root.addProperty("admin", false);
        root.addProperty("userLevel", 0);
        return JsonUtil.GSON.toJson(root);
    }

    @Test
    void professionsMirrorCarriesProfile() {
        ClientCharacterState.setList(sendListStylePayload());
        assertEquals(1, ClientCharacterState.professions().size());
        JsonObject prof = ClientCharacterState.professions().get(0);
        String profile = prof.has("profile") && !prof.get("profile").isJsonNull()
                ? prof.get("profile").getAsString()
                : "";
        assertEquals("设施内最高权限：封锁区域、部署QDF/QSA、降职决议（样板职业）。", profile);
        assertEquals(profile, ClientCharacterState.professionProfile("hq_ceo"));
    }
}
