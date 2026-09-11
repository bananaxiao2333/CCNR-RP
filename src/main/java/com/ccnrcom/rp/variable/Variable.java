/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.variable;

import java.util.List;

/**
 * 自定义设定变量（纯逻辑，无 MC import）。**全局唯一值**——本系统不区分阵营/职业/玩家作用域，
 * 需要"每方一份"的场景请用不同 id 各建一个变量（如 {@code permit_mtf} / {@code permit_chaos}）。
 *
 * @param id      变量 id（小写字母/数字/下划线点横线，1-64）
 * @param type    变量类型（决定取值校验与归一化）
 * @param name    显示名（空则回退 id）
 * @param desc    说明（可空；GUI 里作提示用）
 * @param value   当前值（已按 type 归一化的字符串）
 * @param presets 预设值（候选值，点击即切换；可空列表）
 */
public record Variable(String id, VariableType type, String name, String desc, String value, List<Preset> presets) {

    /**
     * 预设值：命名候选值，一键把变量切到 {@code value}（构成主义里的"档位"）。
     * 预设值同样受类型的归一化约束，落盘即规范化字符串。
     */
    public record Preset(String id, String name, String value) {
        /** 显示名（空则用 id；命令输出统一走这里）。 */
        public String displayName() {
            return name == null || name.isBlank() ? id : name;
        }
    }

    public Variable {
        presets = presets == null ? List.of() : List.copyOf(presets);
    }

    /** 复制并替换当前值（保持其余字段与预设不变）。 */
    public Variable withValue(String newValue) {
        return new Variable(id, type, name, desc, newValue, presets);
    }

    /** 复制并替换预设列表。 */
    public Variable withPresets(List<Preset> newPresets) {
        return new Variable(id, type, name, desc, value, newPresets);
    }

    /** 显示名（空则用 id；GUI/命令输出统一走这里）。 */
    public String displayName() {
        return name == null || name.isBlank() ? id : name;
    }

    /** 按 id 找预设（不存在返回 null）。 */
    public Preset preset(String presetId) {
        if (presetId == null) {
            return null;
        }
        for (Preset p : presets) {
            if (presetId.equals(p.id())) {
                return p;
            }
        }
        return null;
    }
}
