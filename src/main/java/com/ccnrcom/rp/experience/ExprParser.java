/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.experience;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 表达式解析器（纯逻辑）：词法 + 递归下降 + 静态类型检查。
 *
 * <p>语法（优先级从低到高）：<br>
 * or := and ('||' and)* &nbsp;|&nbsp; and := not ('&&' not)* &nbsp;|&nbsp; not := '!' not | cmp<br>
 * cmp := add (('=='|'!='|'<'|'<='|'>'|'>=') add)? &nbsp;|&nbsp; add := mul (('+'|'-') mul)*<br>
 * mul := unary (('*'|'/') unary)* &nbsp;|&nbsp; unary := '-' unary | primary<br>
 * primary := 数字 | "字符串" | 参数名 | 函数(参数列表) | '(' 表达式 ')'<br>
 *
 * <p>类型：NUM / STR / BOOL。静态检查需要事件参数表（名→类型）；未知参数、运算类型
 * 不匹配、顶层类型不符合期望（condition=BOOL / value=NUM / title=任意转字符串）都会
 * 抛 {@link ExprException}（带字符位置，供管理面板验证器定位）。
 */
public final class ExprParser {

    /** 表达式节点。 */
    public interface Expr {}

    /** 静态类型。 */
    public enum Kind {
        NUM,
        STR,
        BOOL
    }

    public record Num(double v, int pos) implements Expr {}

    public record Str(String v, int pos) implements Expr {}

    public record Bool(boolean v, int pos) implements Expr {}

    public record Param(String name, int pos) implements Expr {}

    public record Func(String name, List<Expr> args, int pos) implements Expr {}

    public record Neg(Expr e, int pos) implements Expr {}

    public record Not(Expr e, int pos) implements Expr {}

    /** 算术：+ - * /。 */
    public record Bin(String op, Expr l, Expr r, int pos) implements Expr {}

    /** 比较：== != < <= > >=。 */
    public record Cmp(String op, Expr l, Expr r, int pos) implements Expr {}

    /** 逻辑：&& ||。 */
    public record Logic(String op, Expr l, Expr r, int pos) implements Expr {}

    private ExprParser() {}

    /** 解析表达式；语法错误抛 {@link ExprException}。 */
    public static Expr parse(String text) {
        return new Parser(text).parseExpr();
    }

    /** 静态类型检查（参数类型来自事件注册表）；类型错误抛 {@link ExprException}。 */
    public static Kind kind(Expr e, Map<String, Kind> paramKinds) {
        if (e instanceof Num) {
            return Kind.NUM;
        }
        if (e instanceof Str) {
            return Kind.STR;
        }
        if (e instanceof Bool) {
            return Kind.BOOL;
        }
        if (e instanceof Param p) {
            Kind k = paramKinds.get(p.name());
            if (k == null) {
                throw new ExprException("未知参数: " + p.name(), p.pos());
            }
            return k;
        }
        if (e instanceof Func f) {
            return checkFunc(f, paramKinds);
        }
        if (e instanceof Neg n) {
            return checkNum(n.e(), paramKinds, n.pos());
        }
        if (e instanceof Not n) {
            if (kind(n.e(), paramKinds) != Kind.BOOL) {
                throw new ExprException("'!' 需要布尔操作数", n.pos());
            }
            return Kind.BOOL;
        }
        if (e instanceof Bin b) {
            return checkBin(b, paramKinds);
        }
        if (e instanceof Cmp c) {
            return checkCmp(c, paramKinds);
        }
        if (e instanceof Logic l) {
            return checkLogic(l, paramKinds);
        }
        throw new IllegalArgumentException("未知表达式节点");
    }

    /** 顶层类型期望：condition=BOOL、value=NUM、title=任意。 */
    public static void expect(Expr e, Kind expected, Map<String, Kind> paramKinds) {
        Kind actual = kind(e, paramKinds);
        if (expected != null && actual != expected) {
            throw new ExprException("类型不匹配：需要 " + expected + "，实际 " + actual, pos(e));
        }
    }

    /** 节点起始位置（错误定位用）。 */
    public static int pos(Expr e) {
        if (e instanceof Num n) {
            return n.pos();
        }
        if (e instanceof Str s) {
            return s.pos();
        }
        if (e instanceof Bool b) {
            return b.pos();
        }
        if (e instanceof Param p) {
            return p.pos();
        }
        if (e instanceof Func f) {
            return f.pos();
        }
        if (e instanceof Neg n) {
            return n.pos();
        }
        if (e instanceof Not n) {
            return n.pos();
        }
        if (e instanceof Bin b) {
            return b.pos();
        }
        if (e instanceof Cmp c) {
            return c.pos();
        }
        if (e instanceof Logic l) {
            return l.pos();
        }
        return 0;
    }

    private static Kind checkFunc(Func f, Map<String, Kind> paramKinds) {
        String n = f.name();
        boolean numeric = List.of("round", "floor", "ceil", "max", "min").contains(n);
        if (!numeric) {
            throw new ExprException("未知函数: " + n, f.pos());
        }
        if (f.args().isEmpty()) {
            throw new ExprException("函数 " + n + " 至少需要一个参数", f.pos());
        }
        for (Expr a : f.args()) {
            checkNum(a, paramKinds, f.pos());
        }
        return Kind.NUM;
    }

    private static Kind checkNum(Expr e, Map<String, Kind> paramKinds, int pos) {
        if (kind(e, paramKinds) != Kind.NUM) {
            throw new ExprException("需要数值操作数", pos(e));
        }
        return Kind.NUM;
    }

    private static Kind checkBin(Bin b, Map<String, Kind> paramKinds) {
        Kind l = kind(b.l(), paramKinds);
        Kind r = kind(b.r(), paramKinds);
        if ("+".equals(b.op())) {
            // 任一操作数为字符串 → 拼接（另一个转字符串）；否则数值相加
            if (l == Kind.STR || r == Kind.STR) {
                return Kind.STR;
            }
            if (l == Kind.NUM && r == Kind.NUM) {
                return Kind.NUM;
            }
            throw new ExprException("'+' 不能用于布尔值", b.pos());
        }
        if (l != Kind.NUM || r != Kind.NUM) {
            throw new ExprException("运算符 '" + b.op() + "' 需要数值操作数", b.pos());
        }
        return Kind.NUM;
    }

    private static Kind checkCmp(Cmp c, Map<String, Kind> paramKinds) {
        Kind l = kind(c.l(), paramKinds);
        Kind r = kind(c.r(), paramKinds);
        switch (c.op()) {
            case "==", "!=" -> {
                return Kind.BOOL; // 跨类型比较合法（运行时 false）
            }
            case "<", "<=", ">", ">=" -> {
                if (l != Kind.NUM || r != Kind.NUM) {
                    throw new ExprException("运算符 '" + c.op() + "' 需要数值操作数", c.pos());
                }
                return Kind.BOOL;
            }
            default -> throw new ExprException("未知比较运算符: " + c.op(), c.pos());
        }
    }

    private static Kind checkLogic(Logic l, Map<String, Kind> paramKinds) {
        if (kind(l.l(), paramKinds) != Kind.BOOL || kind(l.r(), paramKinds) != Kind.BOOL) {
            throw new ExprException("运算符 '" + l.op() + "' 需要布尔操作数", l.pos());
        }
        return Kind.BOOL;
    }

    // ------------------------------------------------------------------ 词法/语法

    private static final class Parser {
        private final String src;
        private int i = 0;

        Parser(String text) {
            this.src = text == null ? "" : text;
        }

        private void skipWs() {
            while (i < src.length() && Character.isWhitespace(src.charAt(i))) {
                i++;
            }
        }

        private char peek() {
            return i < src.length() ? src.charAt(i) : '\0';
        }

        private boolean atEnd() {
            skipWs();
            return i >= src.length();
        }

        private char take() {
            if (i >= src.length()) {
                throw err("表达式意外结束");
            }
            return src.charAt(i++);
        }

        private ExprException err(String msg) {
            return new ExprException(msg, i);
        }

        Expr parseExpr() {
            Expr e = parseOr();
            if (!atEnd()) {
                throw err("无法解析的字符: '" + peek() + "'");
            }
            return e;
        }

        private Expr parseOr() {
            Expr l = parseAnd();
            while (true) {
                skipWs();
                if (startsWith("||")) {
                    int pos = i;
                    i += 2;
                    Expr r = parseAnd();
                    l = new Logic("||", l, r, pos);
                } else {
                    return l;
                }
            }
        }

        private Expr parseAnd() {
            Expr l = parseNot();
            while (true) {
                skipWs();
                if (startsWith("&&")) {
                    int pos = i;
                    i += 2;
                    Expr r = parseNot();
                    l = new Logic("&&", l, r, pos);
                } else {
                    return l;
                }
            }
        }

        private Expr parseNot() {
            skipWs();
            if (peek() == '!') {
                int pos = i;
                i++;
                return new Not(parseNot(), pos);
            }
            return parseCmp();
        }

        private Expr parseCmp() {
            Expr l = parseAdd();
            skipWs();
            for (String op : new String[] {"==", "!=", "<=", ">=", "<", ">"}) {
                if (startsWith(op)) {
                    int pos = i;
                    i += op.length();
                    Expr r = parseAdd();
                    return new Cmp(op, l, r, pos);
                }
            }
            return l;
        }

        private Expr parseAdd() {
            Expr l = parseMul();
            while (true) {
                skipWs();
                char c = peek();
                if (c == '+' || c == '-') {
                    int pos = i;
                    i++;
                    Expr r = parseMul();
                    l = new Bin(String.valueOf(c), l, r, pos);
                } else {
                    return l;
                }
            }
        }

        private Expr parseMul() {
            Expr l = parseUnary();
            while (true) {
                skipWs();
                char c = peek();
                if (c == '*' || c == '/') {
                    int pos = i;
                    i++;
                    Expr r = parseUnary();
                    l = new Bin(String.valueOf(c), l, r, pos);
                } else {
                    return l;
                }
            }
        }

        private Expr parseUnary() {
            skipWs();
            if (peek() == '-') {
                int pos = i;
                i++;
                return new Neg(parseUnary(), pos);
            }
            return parsePrimary();
        }

        private Expr parsePrimary() {
            skipWs();
            char c = peek();
            if (c == '(') {
                i++;
                Expr e = parseOr(); // 括号内子表达式：不要求整串结束
                skipWs();
                if (take() != ')') {
                    throw err("缺少 ')'");
                }
                return e;
            }
            if (c == '"') {
                return parseString();
            }
            if (c == '-' || Character.isDigit(c)) {
                return parseNumber();
            }
            if (Character.isLetter(c) || c == '_') {
                return parseIdentOrFunc();
            }
            throw err("无法解析的字符: '" + c + "'");
        }

        private Expr parseString() {
            int pos = i;
            i++; // 跳过开引号
            StringBuilder sb = new StringBuilder();
            while (i < src.length() && src.charAt(i) != '"') {
                sb.append(src.charAt(i++));
            }
            if (i >= src.length()) {
                throw new ExprException("字符串缺少结束引号", pos);
            }
            i++; // 跳过闭引号
            return new Str(sb.toString(), pos);
        }

        private Expr parseNumber() {
            int pos = i;
            StringBuilder sb = new StringBuilder();
            if (peek() == '-') {
                sb.append(take());
            }
            boolean dot = false;
            while (i < src.length() && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '.')) {
                if (src.charAt(i) == '.') {
                    if (dot) {
                        throw err("数字格式错误");
                    }
                    dot = true;
                }
                sb.append(src.charAt(i++));
            }
            try {
                return new Num(Double.parseDouble(sb.toString()), pos);
            } catch (NumberFormatException e) {
                throw err("数字格式错误: " + sb);
            }
        }

        private Expr parseIdentOrFunc() {
            int pos = i;
            StringBuilder sb = new StringBuilder();
            while (i < src.length() && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) {
                sb.append(src.charAt(i++));
            }
            String name = sb.toString();
            if ("true".equals(name) || "false".equals(name)) {
                return new Bool("true".equals(name), pos);
            }
            skipWs();
            if (peek() == '(') {
                i++;
                List<Expr> args = new ArrayList<>();
                skipWs();
                if (peek() != ')') {
                    args.add(parseOr()); // 参数用 parseOr：停在逗号/右括号，不要求整串结束
                    while (true) {
                        skipWs();
                        if (peek() == ',') {
                            i++;
                            args.add(parseOr());
                        } else {
                            break;
                        }
                    }
                }
                skipWs();
                if (take() != ')') {
                    throw err("缺少 ')'");
                }
                return new Func(name, List.copyOf(args), pos);
            }
            return new Param(name, pos);
        }

        private boolean startsWith(String s) {
            return src.startsWith(s, i);
        }
    }
}
