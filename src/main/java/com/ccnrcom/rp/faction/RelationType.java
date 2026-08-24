/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.faction;

import java.util.Locale;

/** 阵营关系类型：敌对 / 中立 / 友好。 */
public enum RelationType {
    HOSTILE,
    NEUTRAL,
    FRIENDLY;

    /** 解析大小写不敏感的类型名；无效返回 null。 */
    public static RelationType parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
