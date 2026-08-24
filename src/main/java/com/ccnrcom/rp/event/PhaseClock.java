/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.event;

import com.ccnrcom.rp.event.EventModels.GamePhase;
import java.util.List;

/** 阶段时钟（纯逻辑）：按 durationMinutes 自动推进；手动 set/advance 产生迁移信息。 */
public final class PhaseClock {
    private final List<GamePhase> phases;
    private int index = 0;
    private long ticksInPhase = 0;

    public PhaseClock(List<GamePhase> phases) {
        this.phases = phases;
    }

    /** 阶段迁移信息（供触发器判定阶段开始/结束）。 */
    public record Transition(boolean changed, String started, String ended) {}

    public GamePhase current() {
        return phases.isEmpty() ? null : phases.get(Math.min(index, phases.size() - 1));
    }

    public Transition tick() {
        return tick(1L);
    }

    public Transition tick(long ticks) {
        if (phases.isEmpty()) {
            return new Transition(false, null, null);
        }
        GamePhase p = current();
        long limit = p.durationMinutes() * 1200L;
        ticksInPhase += ticks;
        if (ticksInPhase >= limit) {
            String ended = p.id();
            index = Math.min(index + 1, phases.size() - 1);
            ticksInPhase = 0;
            return new Transition(true, current().id(), ended);
        }
        return new Transition(false, null, null);
    }

    public Transition set(int newIndex) {
        if (phases.isEmpty()) {
            return new Transition(false, null, null);
        }
        int clamped = Math.min(Math.max(newIndex, 0), phases.size() - 1);
        if (clamped == index) {
            return new Transition(false, null, null);
        }
        String ended = current().id();
        index = clamped;
        ticksInPhase = 0;
        return new Transition(true, current().id(), ended);
    }

    public Transition advance() {
        return set(index + 1);
    }

    public String phaseId() {
        return current() == null ? "" : current().id();
    }

    public long ticksInPhase() {
        return ticksInPhase;
    }

    public int index() {
        return index;
    }

    public List<GamePhase> phases() {
        return List.copyOf(phases);
    }
}
