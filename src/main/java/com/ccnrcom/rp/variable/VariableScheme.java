/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.variable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 预设方案（整套变量预设，纯逻辑）：一份"多个变量 → 目标值"的快照，一键把整组变量切过去。
 *
 * <p>与 {@link Variable.Preset}（单变量候选值）的分工：单变量预设解决"这个开关换档"，
 * 方案解决"整套参数一起换场"（例如「常规」/「演习」/「收容失效」三套对局参数）。
 *
 * @param id     方案 id（同变量 id 的命名规则）
 * @param name   显示名（空则回退 id）
 * @param values 变量 id → 目标值（写入时按各变量自身类型归一化；未列出的变量保持原值）
 */
public record VariableScheme(String id, String name, Map<String, String> values) {

    public VariableScheme {
        values = values == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(values));
    }

    public String displayName() {
        return name == null || name.isBlank() ? id : name;
    }
}
