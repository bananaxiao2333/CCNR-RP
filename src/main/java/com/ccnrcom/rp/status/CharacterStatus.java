/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.status;

/** 角色状态（P3 引入，P4 完成状态机与掉线判死）。 */
public enum CharacterStatus {
    /** 存活在玩：在场可操作。 */
    ALIVE,
    /** 已死亡：阴间（尸体/等待复活/冷却中）。 */
    DEAD,
    /** 观察状态：阳间待命，默认状态；非自部署角色须处于该状态才可能被复活波选中。 */
    OBSERVING;

    public static CharacterStatus parse(String value) {
        if (value == null) {
            return OBSERVING;
        }
        try {
            return valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return OBSERVING;
        }
    }

    public String key() {
        return "ccnr_rp.status." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
