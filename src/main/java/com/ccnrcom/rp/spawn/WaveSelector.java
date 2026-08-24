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
 * 1. 阴间池（DEAD 旧数据 或 观察中带复活冷却标记 cooldownUntil>0，匹配职业/阵营/等级）→ 复活波强制抽取，无视冷却，等级高→冷却早→随机，取 count。
 * 2. 不足部分 → 阳间池（OBSERVING 无冷却且非自部署）随机，作为"招募候选"（可拒绝）。
 */
public final class WaveSelector {

    private WaveSelector() {}

    public static Selection select(List<Candidate> pool, Wave wave, long now, Random rng) {
        // 阴间池：DEAD（旧存档）或 观察中带冷却标记（最近死亡）→ 复活波无视冷却强制复活
        List<Candidate> underworld = pool.stream()
                .filter(c -> c.dead() || (c.observing() && c.cooldownUntil() > 0))
                .filter(c -> wave.matchesProfession(c.professionId(), c.factionId()))
                .filter(c -> c.level() >= wave.minLevel())
                .sorted(Comparator.comparingInt(Candidate::level)
                        .reversed()
                        .thenComparingLong(Candidate::cooldownUntil))
                .toList();
        List<Candidate> deploy = new ArrayList<>(underworld.subList(0, Math.min(underworld.size(), wave.count())));

        int shortage = wave.count() - deploy.size();
        List<Candidate> observers = new ArrayList<>(pool.stream()
                .filter(Candidate::observing)
                .filter(c -> c.cooldownUntil() == 0) // 阳间待命：从未死亡（无冷却标记）
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
