/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.experience;

import com.ccnrcom.rp.experience.ExprParser.Bin;
import com.ccnrcom.rp.experience.ExprParser.Cmp;
import com.ccnrcom.rp.experience.ExprParser.Expr;
import com.ccnrcom.rp.experience.ExprParser.Func;
import com.ccnrcom.rp.experience.ExprParser.Logic;
import com.ccnrcom.rp.experience.ExprParser.Neg;
import com.ccnrcom.rp.experience.ExprParser.Not;
import com.ccnrcom.rp.experience.ExprParser.Num;
import com.ccnrcom.rp.experience.ExprParser.Param;
import com.ccnrcom.rp.experience.ExprParser.Str;
import java.util.List;
import java.util.Map;

/** 表达式求值器（纯逻辑）：参数表 → 数值/字符串/布尔。 */
public final class ExprEvaluator {

    private ExprEvaluator() {}

    /** 求值为数值；非数值类型抛 {@link ExprException}。 */
    public static double evalNum(Expr e, Map<String, Object> params) {
        Object v = eval(e, params);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        throw new ExprException("期望数值，实际 " + typeName(v), ExprParser.pos(e));
    }

    /** 求值为布尔；非布尔抛 {@link ExprException}。 */
    public static boolean evalBool(Expr e, Map<String, Object> params) {
        Object v = eval(e, params);
        if (v instanceof Boolean b) {
            return b;
        }
        throw new ExprException("期望布尔，实际 " + typeName(v), ExprParser.pos(e));
    }

    /** 求值并转字符串（数值去掉多余小数）。 */
    public static String evalString(Expr e, Map<String, Object> params) {
        return stringify(eval(e, params));
    }

    /** 通用求值。 */
    public static Object eval(Expr e, Map<String, Object> params) {
        if (e instanceof Num n) {
            return n.v();
        }
        if (e instanceof Str s) {
            return s.v();
        }
        if (e instanceof com.ccnrcom.rp.experience.ExprParser.Bool b) {
            return b.v();
        }
        if (e instanceof Param p) {
            Object v = params == null ? null : params.get(p.name());
            if (v == null) {
                throw new ExprException("参数缺失: " + p.name(), p.pos());
            }
            return v;
        }
        if (e instanceof Neg n) {
            return -num(n.e(), params, n.pos());
        }
        if (e instanceof Not n) {
            return !bool(n.e(), params, n.pos());
        }
        if (e instanceof Bin b) {
            return bin(b, params);
        }
        if (e instanceof Cmp c) {
            return cmp(c, params);
        }
        if (e instanceof Logic l) {
            return logic(l, params);
        }
        if (e instanceof Func f) {
            return func(f, params);
        }
        throw new IllegalArgumentException("未知表达式节点");
    }

    private static double num(Expr e, Map<String, Object> params, int pos) {
        Object v = eval(e, params);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        throw new ExprException("期望数值，实际 " + typeName(v), pos);
    }

    private static boolean bool(Expr e, Map<String, Object> params, int pos) {
        Object v = eval(e, params);
        if (v instanceof Boolean b) {
            return b;
        }
        throw new ExprException("期望布尔，实际 " + typeName(v), pos);
    }

    /** 已求值对象转数值（bin/cmp 用）。 */
    private static double toNum(Object v, int pos) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        throw new ExprException("期望数值，实际 " + typeName(v), pos);
    }

    private static Object bin(Bin b, Map<String, Object> params) {
        Object l = eval(b.l(), params);
        Object r = eval(b.r(), params);
        switch (b.op()) {
            case "+" -> {
                if (l instanceof String || r instanceof String) {
                    return stringify(l) + stringify(r);
                }
                return toNum(l, b.pos()) + toNum(r, b.pos());
            }
            case "-" -> {
                return toNum(l, b.pos()) - toNum(r, b.pos());
            }
            case "*" -> {
                return toNum(l, b.pos()) * toNum(r, b.pos());
            }
            case "/" -> {
                double d = toNum(r, b.pos());
                if (d == 0.0) {
                    throw new ExprException("除以零", b.pos());
                }
                return toNum(l, b.pos()) / d;
            }
            default -> throw new ExprException("未知运算符: " + b.op(), b.pos());
        }
    }

    private static Object cmp(Cmp c, Map<String, Object> params) {
        Object l = eval(c.l(), params);
        Object r = eval(c.r(), params);
        switch (c.op()) {
            case "==" -> {
                return equalsVal(l, r);
            }
            case "!=" -> {
                return !equalsVal(l, r);
            }
            case "<", "<=", ">", ">=" -> {
                double a = toNum(l, c.pos());
                double b = toNum(r, c.pos());
                switch (c.op()) {
                    case "<" -> {
                        return a < b;
                    }
                    case "<=" -> {
                        return a <= b;
                    }
                    case ">" -> {
                        return a > b;
                    }
                    default -> {
                        return a >= b;
                    }
                }
            }
            default -> throw new ExprException("未知比较运算符: " + c.op(), c.pos());
        }
    }

    private static Object logic(Logic l, Map<String, Object> params) {
        boolean a = bool(l.l(), params, l.pos());
        if ("&&".equals(l.op())) {
            return a && bool(l.r(), params, l.pos());
        }
        return a || bool(l.r(), params, l.pos());
    }

    private static Object func(Func f, Map<String, Object> params) {
        String name = f.name();
        List<Expr> args = f.args();
        double first = num(args.get(0), params, f.pos());
        switch (name) {
            case "round" -> {
                return (double) Math.round(first);
            }
            case "floor" -> {
                return Math.floor(first);
            }
            case "ceil" -> {
                return Math.ceil(first);
            }
            case "max", "min" -> {
                double acc = first;
                for (int i = 1; i < args.size(); i++) {
                    double v = num(args.get(i), params, f.pos());
                    acc = "max".equals(name) ? Math.max(acc, v) : Math.min(acc, v);
                }
                return acc;
            }
            default -> throw new ExprException("未知函数: " + name, f.pos());
        }
    }

    private static boolean equalsVal(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            return na.doubleValue() == nb.doubleValue();
        }
        return a.equals(b);
    }

    /** 数值转字符串：整数值不带小数（60.0 → "60"）。 */
    public static String stringify(Object v) {
        if (v instanceof Double d) {
            if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                return String.valueOf(d.longValue());
            }
            return String.valueOf(d);
        }
        if (v instanceof Long l) {
            return String.valueOf(l);
        }
        if (v instanceof Integer i) {
            return String.valueOf(i);
        }
        if (v instanceof Boolean b) {
            return String.valueOf(b);
        }
        return String.valueOf(v);
    }

    private static String typeName(Object v) {
        if (v instanceof Number) {
            return "数值";
        }
        if (v instanceof String) {
            return "字符串";
        }
        if (v instanceof Boolean) {
            return "布尔";
        }
        return v == null ? "null" : v.getClass().getSimpleName();
    }
}
