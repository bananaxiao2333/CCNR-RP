/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/** EndingScript 解析：animation/notify/resetToPhase/reward 与缺省。 */
class EndingScriptTest {

    @Test
    void parsesFullScript() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("animation", "game_end");
        JsonObject notify = new JsonObject();
        notify.addProperty("titleKey", "ccnr_rp.mode.invasion.end");
        notify.addProperty("subtitleKey", "ccnr_rp.mode.invasion.end.sub");
        root.add("notify", notify);
        root.addProperty("resetToPhase", "approach");
        JsonObject reward = new JsonObject();
        reward.addProperty("xp", 20);
        reward.addProperty("applyTo", "@a");
        root.add("reward", reward);

        EndingScript.Script s = EndingScript.parse(root).orElseThrow();
        assertEquals("game_end", s.animation());
        assertEquals("ccnr_rp.mode.invasion.end", s.titleKey());
        assertEquals("approach", s.resetToPhase());
        assertEquals(20, s.rewardXp());
        assertEquals("@a", s.applyTo());
        assertTrue(s.hasNotify());
        assertTrue(s.hasReward());
    }

    @Test
    void defaultsWhenAbsent() {
        JsonObject root = new JsonObject();
        root.addProperty("animation", "game_end");
        EndingScript.Script s = EndingScript.parse(root).orElseThrow();
        assertEquals("", s.resetToPhase());
        assertEquals(0, s.rewardXp());
        assertEquals("@a", s.applyTo());
        assertFalse(s.hasNotify());
        assertFalse(s.hasReward());
    }

    @Test
    void emptyObjectParsesEmpty() {
        assertTrue(EndingScript.parse(new JsonObject()).isEmpty());
        assertTrue(EndingScript.parse(null).isEmpty());
    }
}
