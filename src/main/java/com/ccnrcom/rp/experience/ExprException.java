/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.experience;

/** 表达式错误（语法/类型/运行时求值），带字符位置（0 起），供验证器定位。 */
public class ExprException extends RuntimeException {

    private final int position;

    public ExprException(String message, int position) {
        super(message);
        this.position = position;
    }

    /** 错误发生的字符位置（0 起）；-1 = 无具体位置。 */
    public int position() {
        return position;
    }
}
