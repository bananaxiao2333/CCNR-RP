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
        assertEquals(
                new ModeDef("m1", com.ccnrcom.rp.util.DisplayInfo.EMPTY),
                c.modes().get(0));
    }

    /** 展示三件套（docs/15 §4.9）：name/desc/icon 都解析；显示名缺省回退 id（界面不露空串）。 */
    @Test
    void modeDefParsesDisplayTriple() {
        ModeConfig c = ModeManager.parseModes(JsonParser.parseString(
                        "{\"modes\":[{\"id\":\"evac\",\"name\":\"定时疏散\",\"desc\":\"人数达标开局\",\"icon\":\"shield\"}]}")
                .getAsJsonObject());
        ModeDef m = c.modes().get(0);
        assertEquals("evac", m.id());
        assertEquals("定时疏散", m.name());
        assertEquals("人数达标开局", m.display().desc());
        assertEquals("shield", m.display().icon());
    }

    /** 显示名空串（显式写了 ""）也回退 id——不能把空串画到界面上。 */
    @Test
    void blankNameFallsBackToId() {
        ModeConfig c = ModeManager.parseModes(
                JsonParser.parseString("{\"modes\":[{\"id\":\"m2\",\"name\":\"  \",\"icon\":\"hex\"}]}")
                        .getAsJsonObject());
        assertEquals("m2", c.modes().get(0).name());
        assertEquals("hex", c.modes().get(0).display().icon());
    }
}
