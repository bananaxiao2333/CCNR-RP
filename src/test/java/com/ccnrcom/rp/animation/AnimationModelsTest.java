/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** P7 验收：动画解析/未知类型拒载/参数化/时长钳制。 */
class AnimationModelsTest {

    private JsonObject step(String type, Object... kv) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        for (int i = 0; i < kv.length; i += 2) {
            o.addProperty((String) kv[i], (String) kv[i + 1]);
        }
        return o;
    }

    @Test
    void validSequenceParses() {
        JsonObject root = new JsonObject();
        JsonObject seq = new JsonObject();
        var steps = new com.google.gson.JsonArray();
        steps.add(step("FADE", "color", "#000000", "durationTicks", "30"));
        steps.add(step("ACTIONBAR", "text", "hi", "durationTicks", "20"));
        seq.add("steps", steps);
        root.add("sequences", new JsonObject());
        root.getAsJsonObject("sequences").add("demo", seq);
        Map<String, AnimationModels.Sequence> out = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        AnimationModels.parseAll(root, out);
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(1, out.size());
        assertEquals(2, out.get("demo").steps().size());
        assertEquals(30, out.get("demo").steps().get(0).durationTicks());
    }

    @Test
    void unknownTypeRejectedWithPath() {
        List<String> errors = new ArrayList<>();
        assertNull(AnimationModels.parseStep(step("FLYING_CAR", "x", "1"), "s[0]", errors));
        assertFalse(errors.isEmpty());
        assertTrue(errors.get(0).contains("FLYING_CAR"));
    }

    @Test
    void durationClamped() {
        List<String> errors = new ArrayList<>();
        AnimationModels.Step s =
                AnimationModels.parseStep(step("TITLE", "title", "t", "durationTicks", "999999"), "s[0]", errors);
        assertEquals(12000, s.durationTicks());
        AnimationModels.Step s2 = AnimationModels.parseStep(step("TITLE", "durationTicks", "0"), "s[0]", errors);
        assertEquals(1, s2.durationTicks());
    }

    @Test
    void parameterInjection() {
        assertEquals("hello ${name}", AnimationEngine.inject("hello ${name}", Map.of()));
        assertEquals("keep ${missing}", AnimationEngine.inject("keep ${missing}", Map.of("name", "x")));
    }

    @Test
    void groupParsesChildren() {
        JsonObject g = step("GROUP", "durationTicks", "10");
        var children = new com.google.gson.JsonArray();
        children.add(step("ACTIONBAR", "text", "a"));
        g.add("steps", children);
        List<String> errors = new ArrayList<>();
        AnimationModels.Step s = AnimationModels.parseStep(g, "s[0]", errors);
        assertEquals(1, s.children().size());
    }
}
