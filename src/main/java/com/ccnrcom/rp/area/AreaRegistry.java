/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.area;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 区域注册表（纯逻辑，无 MC import，可脱机 JUnit 测）：解析/校验/查询 config/ccnr_rp/areas.json 的 areas 数组。
 *
 * <p>存在意义：本 mod 不含核弹等功能本体，但"目标区"是这些外部功能唯一需要本 mod 提供的东西——
 * 管理员在**拓展设定**里把区域定义好，外部功能按 id 引用区域（见 docs/16）。区域定义是本仓库对外发布的**数据接口**，
 * 判定（谁在区域内）也由本仓库提供，外部 mod 不再各自维护坐标。
 */
public final class AreaRegistry {
    /** 区域数量上限（防配置滥用；每次区域查询都做线性扫描，故需有界）。 */
    public static final int MAX_AREAS = 64;

    /** 维度/区域 id 通用格式：小写字母数字下划线点横线，1-64 字符（维度含命名空间冒号，故另放宽 ':'）。 */
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_.-]{1,64}");

    private static final Pattern DIM_PATTERN = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private AreaRegistry() {}

    /** 解析结果：成功时 areas 可用；失败时 errors 非空（调用方拒绝落盘，不做部分提交）。 */
    public record ParseResult(List<Area> areas, List<String> errors, List<String> warnings) {
        public ParseResult {
            areas = areas == null ? List.of() : List.copyOf(areas);
            errors = errors == null ? List.of() : List.copyOf(errors);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public boolean success() {
            return errors.isEmpty();
        }
    }

    /** 解析 areas 数组（缺省/非数组 = 空注册表，不算错误）。同 id 重复声明时后者覆盖并记 WARN。 */
    public static ParseResult parse(JsonElement element) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Area> merged = new LinkedHashMap<>();
        if (element == null || element.isJsonNull()) {
            return new ParseResult(List.of(), errors, warnings);
        }
        if (!element.isJsonArray()) {
            return new ParseResult(List.of(), List.of("areas 必须是数组"), warnings);
        }
        JsonArray arr = element.getAsJsonArray();
        if (arr.size() > MAX_AREAS) {
            return new ParseResult(List.of(), List.of("区域数量超上限（" + MAX_AREAS + "）：" + arr.size()), warnings);
        }
        for (int i = 0; i < arr.size(); i++) {
            JsonElement el = arr.get(i);
            if (!el.isJsonObject()) {
                errors.add("areas[" + i + "] 不是对象");
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String id = str(o, "id");
            if (id.isBlank()) {
                errors.add("areas[" + i + "] 缺少 id");
                continue;
            }
            if (!ID_PATTERN.matcher(id).matches()) {
                errors.add("areas[" + i + "] id 非法（小写字母/数字/下划线，1-64）：" + id);
                continue;
            }
            String name = str(o, "name");
            String dim = str(o, "dim");
            if (dim.isBlank() || !DIM_PATTERN.matcher(dim).matches()) {
                errors.add("areas[" + i + "] dim 非法（需 namespace:path）：" + id + " → " + dim);
                continue;
            }
            double[] c = new double[6];
            String[] keys = {"x1", "y1", "z1", "x2", "y2", "z2"};
            boolean coordOk = true;
            for (int k = 0; k < keys.length; k++) {
                try {
                    c[k] = o.has(keys[k]) ? o.get(keys[k]).getAsDouble() : Double.NaN;
                } catch (Exception e) {
                    c[k] = Double.NaN;
                }
                if (!Double.isFinite(c[k])) {
                    errors.add("areas[" + i + "] 坐标非法（需有限数字）：" + id + " → " + keys[k]);
                    coordOk = false;
                    break;
                }
            }
            if (!coordOk) {
                continue;
            }
            Area area = new Area(id, name.isBlank() ? id : name, dim, c[0], c[1], c[2], c[3], c[4], c[5]);
            // 退化区域（任一轴零厚度）永远是"看不见的墙"：直接拒绝，避免配了却不生效
            if (area.maxX() - area.minX() == 0 || area.maxY() - area.minY() == 0 || area.maxZ() - area.minZ() == 0) {
                errors.add("areas[" + i + "] 区域退化（三个轴都必须有厚度）：" + id);
                continue;
            }
            Area prev = merged.put(id, area);
            if (prev != null) {
                warnings.add("区域重复声明后者覆盖：" + id);
            }
        }
        return new ParseResult(new ArrayList<>(merged.values()), errors, warnings);
    }

    /** 序列化单个区域（写盘用）。 */
    public static JsonObject toJson(Area a) {
        JsonObject o = new JsonObject();
        o.addProperty("id", a.id());
        o.addProperty("name", a.name());
        o.addProperty("dim", a.dim());
        o.addProperty("x1", a.x1());
        o.addProperty("y1", a.y1());
        o.addProperty("z1", a.z1());
        o.addProperty("x2", a.x2());
        o.addProperty("y2", a.y2());
        o.addProperty("z2", a.z2());
        return o;
    }

    public static JsonArray toJsonArray(List<Area> areas) {
        JsonArray arr = new JsonArray();
        if (areas == null) {
            return arr;
        }
        for (Area a : areas) {
            arr.add(toJson(a));
        }
        return arr;
    }

    /** 按 id 查区域（不存在返回空）。 */
    public static Optional<Area> find(List<Area> areas, String id) {
        if (areas == null || id == null || id.isBlank()) {
            return Optional.empty();
        }
        for (Area a : areas) {
            if (a.id().equals(id)) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    /** 点命中的第一个区域（按声明顺序；未命中返回空）。 */
    public static Optional<Area> at(List<Area> areas, String dim, double x, double y, double z) {
        if (areas == null) {
            return Optional.empty();
        }
        for (Area a : areas) {
            if (a.contains(dim, x, y, z)) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    /** 校验 id 格式（命令/管理界面新建区域前用；与 parse 同一套规则）。 */
    public static boolean validId(String id) {
        return id != null && ID_PATTERN.matcher(id).matches();
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString().trim() : "";
    }
}
