/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.data;

/** 数据库后端类型：SQLite（默认，内嵌单文件）或 MySQL（网络后端）。 */
public enum DbType {
    SQLITE,
    MYSQL;

    /** 解析属性值（忽略大小写）；未知返回 null。 */
    public static DbType parse(String s) {
        if (s == null) {
            return null;
        }
        try {
            return DbType.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            return null;
        }
    }
}
