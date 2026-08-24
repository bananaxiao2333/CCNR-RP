/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccnrcom.rp.spawn.SpawnModels.Candidate;
import com.ccnrcom.rp.spawn.SpawnModels.Mode;
import com.ccnrcom.rp.spawn.SpawnModels.Selection;
import com.ccnrcom.rp.spawn.SpawnModels.Wave;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** P8 验收：选人算法（阴间优先/排序/冷却过滤/回退招募/超时语义）。 */
class WaveSelectorTest {

    private static final Wave WAVE = new Wave(
            "w",
            Mode.RESURRECTION,
            true,
            List.of("t"),
            List.of("qdf_guard"),
            List.of("qdf"),
            3,
            0,
            "WORLD_SPAWN",
            0,
            64,
            0,
            "minecraft:overworld",
            60);

    private static Candidate cand(
            String id, String status, long cooldown, int level, boolean selfDeploy, boolean online) {
        return new Candidate(id, "p-" + id, "N-" + id, status, cooldown, level, "qdf_guard", "qdf", selfDeploy, online);
    }

    @Test
    void deadPoolFirstByLevelThenCooldown() {
        long now = 1000;
        List<Candidate> pool = List.of(
                cand("a", "dead", 0, 2, false, true),
                cand("b", "dead", 500, 5, false, true),
                cand("c", "dead", 100, 5, false, true));
        Selection sel = WaveSelector.select(pool, WAVE, now, new Random(7));
        assertEquals(3, sel.deploy().size());
        assertEquals("c", sel.deploy().get(0).charId());
        assertEquals("b", sel.deploy().get(1).charId());
        assertTrue(sel.recruit().isEmpty());
    }

    @Test
    void cooldownNotOverFiltered() {
        long now = 1000;
        List<Candidate> pool =
                List.of(cand("hot", "dead", 5000, 9, false, true), cand("cool", "dead", 100, 1, false, true));
        Selection sel = WaveSelector.select(pool, WAVE, now, new Random(1));
        assertEquals(1, sel.deploy().size());
        assertEquals("cool", sel.deploy().get(0).charId());
    }

    @Test
    void shortageFallsBackToObservers() {
        long now = 1000;
        List<Candidate> pool = List.of(
                cand("d1", "dead", 0, 3, false, true),
                cand("o1", "observing", 0, 1, false, true),
                cand("o2", "observing", 0, 1, false, true),
                cand("selfd", "observing", 0, 99, true, true),
                cand("alive", "alive", 0, 99, false, true));
        Selection sel = WaveSelector.select(pool, WAVE, now, new Random(3));
        assertEquals(1, sel.deploy().size());
        assertEquals(2, sel.recruit().size());
        // 自部署类型与在场角色不出现在招募候选
        assertTrue(sel.recruit().stream().noneMatch(c -> c.charId().equals("selfd")));
        assertTrue(sel.recruit().stream().noneMatch(c -> c.charId().equals("alive")));
    }

    @Test
    void offlineObserversNotRecruited() {
        long now = 1000;
        List<Candidate> pool = List.of(cand("o1", "observing", 0, 1, false, false));
        Selection sel = WaveSelector.select(pool, WAVE, now, new Random(2));
        assertTrue(sel.recruit().isEmpty());
    }

    @Test
    void professionAndFactionMatching() {
        Wave other = new Wave(
                "w2",
                Mode.RESURRECTION,
                true,
                List.of(),
                List.of("nope_id"),
                List.of(),
                1,
                0,
                "WORLD_SPAWN",
                0,
                64,
                0,
                "minecraft:overworld",
                60);
        List<Candidate> pool = List.of(cand("d1", "dead", 0, 3, false, true));
        Selection sel = WaveSelector.select(pool, other, 1000, new Random(0));
        assertTrue(sel.deploy().isEmpty());
    }

    @Test
    void minLevelFiltered() {
        Wave high = new Wave(
                "w3",
                Mode.RESURRECTION,
                true,
                List.of(),
                List.of(),
                List.of(),
                1,
                10,
                "WORLD_SPAWN",
                0,
                64,
                0,
                "minecraft:overworld",
                60);
        Selection sel = WaveSelector.select(List.of(cand("d1", "dead", 0, 3, false, true)), high, 1000, new Random(0));
        assertTrue(sel.deploy().isEmpty());
    }
}
