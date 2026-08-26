/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.experience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccnrcom.rp.experience.ExprParser.Kind;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 表达式引擎：语法/静态类型/求值（数值/判断/标题）。 */
class ExprTest {

    private static final Map<String, Kind> KILL_KINDS = Map.of(
            "uuid", Kind.STR,
            "playerName", Kind.STR,
            "victimType", Kind.STR,
            "victimName", Kind.STR,
            "victimUuid", Kind.STR,
            "aliveSeconds", Kind.NUM,
            "intervalSeconds", Kind.NUM);

    // ---------------- 数值表达式 ----------------

    @Test
    void arithmeticPrecedence() {
        assertEquals(7.0, evalNum("1+2*3", Map.of()));
        assertEquals(9.0, evalNum("(1+2)*3", Map.of()));
        assertEquals(-2.0, evalNum("-5+3", Map.of()));
        assertEquals(2.5, evalNum("10/4", Map.of()));
    }

    @Test
    void functions() {
        assertEquals(3.0, evalNum("max(1,2,3)", Map.of()));
        assertEquals(2.0, evalNum("min(4,2)", Map.of()));
        assertEquals(3.0, evalNum("round(2.5)", Map.of()));
        assertEquals(2.0, evalNum("floor(2.9)", Map.of()));
        assertEquals(3.0, evalNum("ceil(2.1)", Map.of()));
    }

    @Test
    void paramArithmetic() {
        assertEquals(60.0, evalNum("aliveSeconds * 0.1", Map.of("aliveSeconds", 600L)));
        assertEquals(20.0, evalNum("max(0, aliveSeconds - 40)", Map.of("aliveSeconds", 60L)));
    }

    // ---------------- 判断表达式 ----------------

    @Test
    void comparisons() {
        assertEquals(true, evalBool("aliveSeconds >= 60", Map.of("aliveSeconds", 60L)));
        assertEquals(false, evalBool("aliveSeconds >= 60", Map.of("aliveSeconds", 59L)));
        assertEquals(true, evalBool("victimType == \"zombie\"", Map.of("victimType", "zombie")));
        assertEquals(true, evalBool("victimUuid != \"\"", Map.of("victimUuid", "abc")));
    }

    @Test
    void logicOps() {
        assertEquals(
                true,
                evalBool(
                        "victimType == \"zombie\" && aliveSeconds >= 60",
                        Map.of("victimType", "zombie", "aliveSeconds", 120L)));
        assertEquals(false, evalBool("!true", Map.of()));
        assertEquals(true, evalBool("a == \"x\" || b == \"y\"", Map.of("a", "z", "b", "y")));
        // 短路：|| 左侧 true 时不求值右侧（1/0 不报错）
        assertEquals(true, evalBool("true || (1/0 == 0)", Map.of()));
        assertEquals(false, evalBool("false && (1/0 == 0)", Map.of()));
    }

    // ---------------- 标题表达式 ----------------

    @Test
    void stringConcat() {
        assertEquals("击杀 zombie", evalString("\"击杀 \" + victimType", Map.of("victimType", "zombie")));
        assertEquals("v5", evalString("\"v\" + 5", Map.of()));
        assertEquals("600", evalString("\"\" + aliveSeconds", Map.of("aliveSeconds", 600L)));
    }

    // ---------------- 错误路径 ----------------

    @Test
    void syntaxErrorCarriesPosition() {
        ExprException e = assertThrows(ExprException.class, () -> ExprParser.parse("1 +"));
        assertTrue(e.position() >= 0);
        assertThrows(ExprException.class, () -> ExprParser.parse("(1+2"));
        assertThrows(ExprException.class, () -> ExprParser.parse("\"abc"));
    }

    @Test
    void staticTypeErrors() {
        assertThrows(
                ExprException.class, () -> ExprParser.expect(ExprParser.parse("1 < \"a\""), Kind.BOOL, KILL_KINDS));
        assertThrows(
                ExprException.class, () -> ExprParser.expect(ExprParser.parse("1 && true"), Kind.BOOL, KILL_KINDS));
        assertThrows(ExprException.class, () -> ExprParser.expect(ExprParser.parse("nope + 1"), Kind.NUM, KILL_KINDS));
        assertThrows(ExprException.class, () -> ExprParser.expect(ExprParser.parse("unknown()"), Kind.NUM, KILL_KINDS));
    }

    @Test
    void topLevelKindMismatch() {
        // condition 必须是布尔
        assertThrows(ExprException.class, () -> ExprParser.expect(ExprParser.parse("1"), Kind.BOOL, KILL_KINDS));
        // value 必须是数值
        assertThrows(ExprException.class, () -> ExprParser.expect(ExprParser.parse("a == b"), Kind.NUM, KILL_KINDS));
    }

    @Test
    void evalRuntimeErrors() {
        assertThrows(ExprException.class, () -> evalNum("1/0", Map.of()));
        assertThrows(ExprException.class, () -> evalNum("missing + 1", Map.of()));
    }

    private static double evalNum(String src, Map<String, Object> params) {
        return ExprEvaluator.evalNum(ExprParser.parse(src), params);
    }

    private static boolean evalBool(String src, Map<String, Object> params) {
        return ExprEvaluator.evalBool(ExprParser.parse(src), params);
    }

    private static String evalString(String src, Map<String, Object> params) {
        return ExprEvaluator.evalString(ExprParser.parse(src), params);
    }
}
