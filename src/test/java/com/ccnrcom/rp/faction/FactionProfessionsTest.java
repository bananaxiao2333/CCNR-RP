/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.faction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

/** P3 扩展：职业 cmdcamScene 字段 upsert 写盘/回读（空串=移除字段）。 */
class FactionProfessionsTest {

    @Test
    void upsertWritesAndReadsCmdcamScene() {
        JsonObject root = new JsonObject();
        List<String> errors = FactionProfessions.upsert(
                root, "medic", "军医", "a", false, 0, null, "", "", "intro_cam", id -> id.equals("a"));
        assertTrue(errors.isEmpty(), () -> errors.toString());
        JsonObject def = FactionProfessions.find(root, "medic").orElseThrow();
        assertEquals("intro_cam", FactionProfessions.cmdcamScene(def));

        // 空串 → 移除字段，回读为空
        FactionProfessions.upsert(root, "medic", "军医", "a", false, 0, null, "", "", "", id -> id.equals("a"));
        def = FactionProfessions.find(root, "medic").orElseThrow();
        assertEquals("", FactionProfessions.cmdcamScene(def));
    }
}
