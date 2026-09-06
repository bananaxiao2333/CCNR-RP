/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccnrcom.rp.config.ModeManager.ModeConfig;
import com.ccnrcom.rp.config.ModeManager.ModeDef;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** P15 验收：多模式登记解析。 */
class ModeManagerTest {

    @Test
    void parseRegisteredModesAndActive() {
        ModeConfig c = ModeManager.parseModes(JsonParser.parseString(
                        """
                {"version":1,"active":"scpsl","modes":[
                  {"id":"scpsl","name":"收容失效"},
                  {"id":"tac_comp","name":"战术团竞"}
                ]}
                """)
                .getAsJsonObject());
        assertEquals("scpsl", c.activeId());
        assertEquals(2, c.modes().size());
        assertEquals("scpsl", c.modes().get(0).id());
        assertEquals("收容失效", c.modes().get(0).name());
        assertEquals("tac_comp", c.modes().get(1).id());
    }

    @Test
    void missingActiveDefaultsEmpty() {
        ModeConfig c = ModeManager.parseModes(
                JsonParser.parseString("{\"modes\":[{\"id\":\"x\"}]}").getAsJsonObject());
        assertEquals("", c.activeId());
        assertEquals(1, c.modes().size());
    }

    @Test
    void missingModesIsEmpty() {
        ModeConfig c = ModeManager.parseModes(JsonParser.parseString("{}").getAsJsonObject());
        assertEquals("", c.activeId());
        assertTrue(c.modes().isEmpty());
    }

    @Test
    void invalidModeRowIsSkipped() {
        ModeConfig c = ModeManager.parseModes(
                JsonParser.parseString("{\"modes\":[{\"id\":\"ok\"},{\"name\":\"无id\"},{\"id\":\"scpsl\"}]}")
                        .getAsJsonObject());
        assertEquals(2, c.modes().size());
        assertEquals("ok", c.modes().get(0).id());
        assertEquals("scpsl", c.modes().get(1).id());
        // 无 name → 回退 id
        assertEquals("ok", c.modes().get(0).name());
    }

    @Test
    void modeDefNameFallsBackToId() {
        ModeConfig c = ModeManager.parseModes(
                JsonParser.parseString("{\"modes\":[{\"id\":\"m1\"}]}").getAsJsonObject());
        assertEquals("m1", c.modes().get(0).name());
        assertEquals(new ModeDef("m1", "m1"), c.modes().get(0));
    }
}
