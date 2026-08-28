/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.data.orm;

import com.google.gson.Gson;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 注解微 ORM 映射器（只针对 record 实体）。读取时按记录组件顺序经 canonical 构造器重建；
 * 写入时按组件顺序产出绑定参数。支持 JsonColumn（Gson→TEXT）与 Blob（byte[]）。
 */
public final class SqlMapper<T> {

    private static final Gson GSON = new Gson();

    private final Class<T> type;
    private final String table;
    private final List<Col> cols = new ArrayList<>();
    private final List<String> idCols = new ArrayList<>();
    private final Constructor<T> ctor;

    private record Col(
            RecordComponent rc, String column, boolean id, boolean json, boolean blob, Class<?> jt, Type gtype) {}

    public SqlMapper(Class<T> type) {
        this.type = type;
        Table tableAnn = type.getAnnotation(Table.class);
        if (tableAnn == null) {
            throw new IllegalArgumentException(type.getName() + " 缺少 @Table");
        }
        this.table = tableAnn.value();
        RecordComponent[] rc = type.getRecordComponents();
        if (rc == null || rc.length == 0) {
            throw new IllegalArgumentException(type.getName() + " 不是 record");
        }
        for (RecordComponent c : rc) {
            Column colAnn = c.getAnnotation(Column.class);
            String cname = colAnn != null && !colAnn.value().isBlank() ? colAnn.value() : c.getName();
            boolean id = c.isAnnotationPresent(Id.class);
            Col col = new Col(
                    c,
                    cname,
                    id,
                    c.isAnnotationPresent(JsonColumn.class),
                    c.isAnnotationPresent(Blob.class),
                    c.getType(),
                    c.getGenericType());
            cols.add(col);
            if (id) {
                idCols.add(cname);
            }
        }
        // 找 canonical（参数个数 == 组件数）构造器
        for (Constructor<?> c : type.getDeclaredConstructors()) {
            if (c.getParameterCount() == cols.size()) {
                @SuppressWarnings("unchecked")
                Constructor<T> tc = (Constructor<T>) c;
                try {
                    tc.setAccessible(true); // record 可能为包私有，公开其 canonical 构造器
                } catch (Exception ignored) {
                    // 已可访问则忽略
                }
                this.ctor = tc;
                return;
            }
        }
        throw new IllegalArgumentException(type.getName() + " 未找到 canonical 构造器");
    }

    public Class<T> type() {
        return type;
    }

    public String table() {
        return table;
    }

    public List<String> columnNames() {
        List<String> out = new ArrayList<>();
        for (Col c : cols) {
            out.add(c.column());
        }
        return out;
    }

    public List<String> idColumns() {
        return idCols;
    }

    /** 从结果集当前行映射为实体。 */
    public T map(ResultSet rs) throws SQLException {
        Object[] values = new Object[cols.size()];
        for (int i = 0; i < cols.size(); i++) {
            Col c = cols.get(i);
            values[i] = read(rs, c);
        }
        try {
            return ctor.newInstance(values);
        } catch (ReflectiveOperationException e) {
            throw new SQLException("映射失败: " + type.getName(), e);
        }
    }

    private Object read(ResultSet rs, Col c) throws SQLException {
        Class<?> jt = c.jt();
        if (c.blob()) {
            return rs.getBytes(c.column());
        }
        if (c.json()) {
            String s = rs.getString(c.column());
            return s == null || s.isBlank() ? null : GSON.fromJson(s, c.gtype());
        }
        if (jt == String.class) {
            return rs.getString(c.column());
        }
        if (jt == int.class || jt == Integer.class) {
            return rs.getInt(c.column());
        }
        if (jt == long.class || jt == Long.class) {
            return rs.getLong(c.column());
        }
        if (jt == double.class || jt == Double.class) {
            return rs.getDouble(c.column());
        }
        if (jt == boolean.class || jt == Boolean.class) {
            return rs.getInt(c.column()) != 0;
        }
        return rs.getObject(c.column());
    }

    /** 产出绑定参数（与 columnNames 顺序一致）。 */
    public List<Object> params(T obj) {
        List<Object> out = new ArrayList<>(cols.size());
        for (Col c : cols) {
            Object v = access(obj, c);
            if (c.blob()) {
                out.add(v);
            } else if (c.json()) {
                out.add(v == null ? null : GSON.toJson(v));
            } else if (v != null && (v.getClass() == Boolean.class)) {
                out.add(((Boolean) v) ? 1 : 0);
            } else {
                out.add(v);
            }
        }
        return out;
    }

    /** 绑定参数到 prepared statement（从第 1 个位置开始）。 */
    public void bind(PreparedStatement ps, T obj) throws SQLException {
        List<Object> p = params(obj);
        for (int i = 0; i < p.size(); i++) {
            Object v = p.get(i);
            if (v == null) {
                ps.setNull(i + 1, java.sql.Types.VARCHAR);
            } else if (v instanceof byte[] b) {
                ps.setBytes(i + 1, b);
            } else if (v instanceof Integer || v instanceof Long) {
                ps.setLong(i + 1, ((Number) v).longValue());
            } else {
                ps.setObject(i + 1, v);
            }
        }
    }

    private static Object access(Object obj, Col c) {
        try {
            java.lang.reflect.Method m = c.rc().getAccessor();
            m.setAccessible(true); // 声明类可能为包私有且位于其它包，公开访问器
            return m.invoke(obj);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("取值失败: " + c.rc().getName(), e);
        }
    }
}
