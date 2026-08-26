/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccnrcom.rp.spawn.SpawnModels.ConscriptDeployMode;
import com.ccnrcom.rp.spawn.SpawnModels.Mode;
import com.ccnrcom.rp.spawn.SpawnModels.WaveQuota;
import com.ccnrcom.rp.status.CharacterStatus;
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

    /** 「触发事件」锚点数据模型：TRIGGER 首项保留在解析后的步骤列表（引擎侧跳过执行）。 */
    @Test
    void waveKeepsTriggerAnchorFirstInSteps() {
        JsonObject w = wave("w3", "BOTH", null);
        JsonArray seq = new JsonArray();
        JsonObject anchor = new JsonObject();
        anchor.addProperty("type", "TRIGGER");
        anchor.addProperty("source", "wave:w3");
        anchor.addProperty("label", "刷新波 w3");
        seq.add(anchor);
        JsonObject wait = new JsonObject();
        wait.addProperty("type", "WAIT");
        wait.addProperty("seconds", 5);
        seq.add(wait);
        w.add("sequence", seq);
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        arr.add(w);
        root.add("waves", arr);
        List<SpawnModels.Wave> waves = new ArrayList<>();
        assertEquals(List.of(), SpawnModels.parseWaves(root, waves));
        List<JsonObject> steps = waves.get(0).steps();
        assertEquals(2, steps.size());
        assertEquals("TRIGGER", steps.get(0).get("type").getAsString());
        assertEquals("wave:w3", steps.get(0).get("source").getAsString());
        assertEquals("WAIT", steps.get(1).get("type").getAsString());
    }

    // ---------- 波模式语义（v2.15.x 起：存活可收到 / 死亡可收到 / 皆可收到） ----------

    @Test
    void modeReceiveSemantics() {
        assertTrue(Mode.SELF_DEPLOY.aliveReceiveAllowed());
        assertFalse(Mode.SELF_DEPLOY.deadReceiveAllowed());
        assertFalse(Mode.RESURRECTION.aliveReceiveAllowed());
        assertTrue(Mode.RESURRECTION.deadReceiveAllowed());
        assertTrue(Mode.BOTH.aliveReceiveAllowed());
        assertTrue(Mode.BOTH.deadReceiveAllowed());
        // 自部署落点与「存活可收到」同义（存活人员可用该波落点）
        assertEquals(Mode.SELF_DEPLOY.aliveReceiveAllowed(), Mode.SELF_DEPLOY.selfDeployAllowed());
        assertEquals(Mode.RESURRECTION.aliveReceiveAllowed(), Mode.RESURRECTION.selfDeployAllowed());
        assertEquals(Mode.BOTH.aliveReceiveAllowed(), Mode.BOTH.selfDeployAllowed());
    }

    /** 存量配置兼容：旧管理面板持久化的 "RECRUIT" 必须映射为 RESURRECTION 且波不被丢弃。 */
    @Test
    void waveParsesLegacyRecruitMode() {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        arr.add(wave("legacy", "RECRUIT", null));
        root.add("waves", arr);
        List<SpawnModels.Wave> waves = new ArrayList<>();
        List<String> errors = SpawnModels.parseWaves(root, waves);
        assertEquals(1, waves.size(), "RECRUIT 波不应被丢弃");
        assertEquals(Mode.RESURRECTION, waves.get(0).mode());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("RECRUIT"));
        assertTrue(errors.get(0).contains("映射"));
    }

    /** 无效 mode 保持原行为：报错且该条跳过（服务不崩）。 */
    @Test
    void waveInvalidModeSkippedWithError() {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        arr.add(wave("bad", "NOPE", null));
        root.add("waves", arr);
        List<SpawnModels.Wave> waves = new ArrayList<>();
        List<String> errors = SpawnModels.parseWaves(root, waves);
        assertEquals(0, waves.size());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("无效 mode"));
    }

    // ---------- 通用波名额分配（存活征召 / 观察者选岗各半，防超招） ----------

    @Test
    void quotaCountZeroSkipsWholeWave() {
        WaveQuota q = WaveQuota.split(0, 5, true);
        assertFalse(q.hasAlive());
        assertFalse(q.hasPick());
        assertEquals(0, q.aliveShare());
        assertEquals(0, q.pickTarget());
    }

    @Test
    void quotaSplitsEvenly() {
        WaveQuota q = WaveQuota.split(4, 10, true);
        assertEquals(2, q.aliveShare());
        assertEquals(2, q.pickTarget());
        assertTrue(q.hasAlive());
        assertTrue(q.hasPick());
    }

    @Test
    void quotaOddCountGivesAliveTheExtraSlot() {
        WaveQuota q = WaveQuota.split(3, 10, true);
        assertEquals(2, q.aliveShare());
        assertEquals(1, q.pickTarget());
    }

    @Test
    void quotaSingleSlotGoesToAliveOnly() {
        WaveQuota q = WaveQuota.split(1, 10, true);
        assertEquals(1, q.aliveShare());
        assertEquals(0, q.pickTarget());
        assertTrue(q.hasAlive());
        assertFalse(q.hasPick(), "count=1 时观察者通道不应发邀请（防超招）");
    }

    @Test
    void quotaAlivePoolEmptyGivesAllToPick() {
        WaveQuota q = WaveQuota.split(4, 0, true);
        assertEquals(0, q.aliveShare());
        assertEquals(4, q.pickTarget());
        assertFalse(q.hasAlive());
        assertTrue(q.hasPick());
    }

    @Test
    void quotaNoObserversGivesAllToAlive() {
        WaveQuota q = WaveQuota.split(4, 2, false);
        assertEquals(2, q.aliveShare());
        assertEquals(2, q.pickTarget());
        assertTrue(q.hasAlive());
        assertFalse(q.hasPick(), "无观察者候选时 pick 通道不应发邀请");
    }

    @Test
    void quotaAlivePoolSmallCapsAliveShare() {
        WaveQuota q = WaveQuota.split(4, 1, true);
        assertEquals(1, q.aliveShare());
        assertEquals(3, q.pickTarget());
    }

    // ---------- 征召接受者部署方式（docs/09 §4.3） ----------

    @Test
    void conscriptDeployModeByStatus() {
        assertEquals(ConscriptDeployMode.FORMAL, ConscriptDeployMode.of(CharacterStatus.ALIVE));
        assertEquals(ConscriptDeployMode.TEMP, ConscriptDeployMode.of(CharacterStatus.OBSERVING));
        assertEquals(ConscriptDeployMode.TEMP, ConscriptDeployMode.of(CharacterStatus.DEAD));
        assertEquals(ConscriptDeployMode.SKIP, ConscriptDeployMode.of(null));
    }
}
