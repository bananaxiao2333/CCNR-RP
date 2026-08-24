/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.spawn;

import com.ccnrcom.rp.spawn.SpawnModels.Candidate;
import com.ccnrcom.rp.spawn.SpawnModels.Selection;
import com.ccnrcom.rp.spawn.SpawnModels.Wave;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 选人算法（纯逻辑）：
 * 1. 阴间池（DEAD 且冷却结束且匹配职业/阵营/等级）→ 等级高→冷却早→随机，取 count。
 * 2. 不足部分 → 阳间池（OBSERVING 且非自部署）随机，作为"招募候选"。
 */
public final class WaveSelector {

    private WaveSelector() {}

    public static Selection select(List<Candidate> pool, Wave wave, long now, Random rng) {
        List<Candidate> dead = pool.stream()
                .filter(Candidate::dead)
                .filter(c -> c.cooldownUntil() <= now)
                .filter(c -> wave.matchesProfession(c.professionId(), c.factionId()))
                .filter(c -> c.level() >= wave.minLevel())
                .sorted(Comparator.comparingInt(Candidate::level)
                        .reversed()
                        .thenComparingLong(Candidate::cooldownUntil))
                .toList();
        List<Candidate> deploy = new ArrayList<>(dead.subList(0, Math.min(dead.size(), wave.count())));

        int shortage = wave.count() - deploy.size();
        List<Candidate> observers = new ArrayList<>(pool.stream()
                .filter(Candidate::observing)
                .filter(c -> !c.professionSelfDeploy())
                .filter(c -> wave.matchesProfession(c.professionId(), c.factionId()))
                .filter(c -> c.level() >= wave.minLevel())
                .filter(Candidate::online)
                .toList());
        // 随机化（Fisher–Yates 式简易洗牌）
        for (int i = observers.size() - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            Candidate tmp = observers.get(i);
            observers.set(i, observers.get(j));
            observers.set(j, tmp);
        }
        List<Candidate> recruit = new ArrayList<>(observers.subList(0, Math.min(observers.size(), shortage)));
        return new Selection(deploy, recruit);
    }
}
