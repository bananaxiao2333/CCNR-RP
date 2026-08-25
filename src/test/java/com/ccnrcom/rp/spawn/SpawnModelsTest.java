/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** P8 扩展：刷新波 cmdcamScene 字段解析回显。 */
class SpawnModelsTest {

    private static JsonObject wave(String id, String mode, String cmdcamScene) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("mode", mode);
        if (cmdcamScene != null) {
            o.addProperty("cmdcamScene", cmdcamScene);
        }
        JsonObject d = new JsonObject();
        d.addProperty("type", "WORLD_SPAWN");
        o.add("deployAt", d);
        return o;
    }

    @Test
    void waveParsesCmdcamScene() {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        arr.add(wave("w1", "BOTH", "intro_cam"));
        root.add("waves", arr);
        List<SpawnModels.Wave> waves = new ArrayList<>();
        List<String> errors = SpawnModels.parseWaves(root, waves);
        assertEquals(List.of(), errors);
        assertEquals(1, waves.size());
        assertEquals("intro_cam", waves.get(0).cmdcamScene());
    }

    @Test
    void waveWithoutCmdcamSceneDefaultsEmpty() {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        arr.add(wave("w2", "SELF_DEPLOY", null));
        root.add("waves", arr);
        List<SpawnModels.Wave> waves = new ArrayList<>();
        assertEquals(List.of(), SpawnModels.parseWaves(root, waves));
        assertEquals("", waves.get(0).cmdcamScene());
    }
}
