/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.variable;

import java.util.Locale;

/**
 * 自定义设定的变量类型（纯逻辑，无 MC import，可脱机 JUnit 测）。
 *
 * <p>设计取向：值一律以**规范化的字符串**落盘/传输（{@code "true"/"false"}、{@code "42"}、{@code "text"}），
 * 类型只决定「什么算合法」与「如何归一化」。这样存储层/网络层/命令层都不需要为三种类型各写一遍，
 * 外部功能读取时按语义取用（{@link VariableService#bool}/{@link VariableService#number}/{@link VariableService#text}）。
 */
public enum VariableType {
    /** 布尔：true/false（接受 on/off/yes/no/1/0 等常见写法，落盘统一为 true/false）。 */
    BOOL,
    /** 数值：有限浮点数（整数落盘不带小数点）。 */
    NUMBER,
    /** 文本：任意字符串（去首尾空白，长度上限见 {@link VariableRegistry#MAX_VALUE_LEN}）。 */
    TEXT;

    /** 解析类型名（大小写不敏感；未知返回 null，由调用方报错，不静默回退）。 */
    public static VariableType parse(String s) {
        if (s == null) {
            return null;
        }
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 归一化取值：合法返回规范化字符串，非法返回 null（调用方据此拒绝落盘）。
     * 布尔接受 true/false/on/off/yes/no/1/0；数值要求有限；文本去空白并按上限截断。
     */
    public String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        switch (this) {
            case BOOL -> {
                String low = v.toLowerCase(Locale.ROOT);
                return switch (low) {
                    case "true", "on", "yes", "1" -> "true";
                    case "false", "off", "no", "0" -> "false";
                    default -> null;
                };
            }
            case NUMBER -> {
                try {
                    double d = Double.parseDouble(v);
                    if (!Double.isFinite(d)) {
                        return null;
                    }
                    if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                        return Long.toString((long) d);
                    }
                    return Double.toString(d);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            default -> {
                if (v.length() > VariableRegistry.MAX_VALUE_LEN) {
                    return null;
                }
                return v;
            }
        }
    }

    /** 类型的人读名（用于命令/GUI 提示；未本地化，属终端风格标签）。 */
    public String label() {
        return switch (this) {
            case BOOL -> "BOOL";
            case NUMBER -> "NUMBER";
            default -> "TEXT";
        };
    }
}
