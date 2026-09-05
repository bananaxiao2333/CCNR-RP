/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.faction;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 入场电影「阵营关系」行的合并逻辑（纯逻辑，无 MC import，可 JUnit 直测）。
 *
 * <p>把玩家阵营对其它阵营的非中立关系，按「阵营组 + 关系」合并可合并项，避免逐阵营罗列过长：
 * <ul>
 *   <li>组内所有非中立成员对玩家阵营的关系<b>一致</b>时，整组合并为一条「组名 + 关系」；
 *   <li>组内关系<b>混杂</b>（敌对/友好并存）则不改写，逐成员按其阵营名显示；
 *   <li>无组的阵营逐一按其阵营名显示。
 * </ul>
 *
 * <p>返回条目携带 {@code name}（组名或阵营名）与 {@code type}（小写关系名），供客户端渲染。
 */
public final class CinematicRelations {

    private CinematicRelations() {}

    /**
     * 合并玩家阵营对其它阵营的非中立关系。
     *
     * @param graph 已解析的关系测定图
     * @param myFactionId 玩家所属阵营 id
     * @return 有序的 {@code {name, type}} 列表；保首见顺序，同「名称+关系」去重
     */
    public static List<JsonObject> collapse(FactionGraph graph, String myFactionId) {
        // 阵营 → 首个归属组（组与阵营共享 id 命名空间，首个非空归属即可）
        Map<String, String> facGroupId = new HashMap<>();
        for (FactionModels.FactionGroup grp : graph.groups().values()) {
            for (String m : grp.memberIds()) {
                facGroupId.putIfAbsent(m, grp.id());
            }
        }
        // 组 → 唯一非中立关系名；仅当组内非中立成员关系一致时设置（全中立 / 关系混杂则不设置）
        Map<String, String> groupUniform = new HashMap<>();
        for (FactionModels.FactionGroup grp : graph.groups().values()) {
            String uniform = null;
            boolean hasNonNeutral = false;
            boolean mixed = false;
            for (String m : grp.memberIds()) {
                if (m.equals(myFactionId)) {
                    continue;
                }
                RelationType mt = graph.resolve(myFactionId, m);
                if (mt == null || mt == RelationType.NEUTRAL) {
                    continue;
                }
                String tn = mt.name().toLowerCase(Locale.ROOT);
                if (uniform == null) {
                    uniform = tn;
                } else if (!uniform.equals(tn)) {
                    mixed = true;
                    break;
                }
                hasNonNeutral = true;
            }
            if (hasNonNeutral && !mixed) {
                groupUniform.put(grp.id(), uniform);
            }
        }
        // 输出：整组合并条目插在首个成员出现处；同「名称+关系」去重（保首见顺序）
        Map<String, JsonObject> merged = new LinkedHashMap<>();
        Set<String> handled = new HashSet<>();
        for (FactionModels.Faction other : graph.factions().values()) {
            if (other.id().equals(myFactionId)) {
                continue;
            }
            String gid = facGroupId.get(other.id());
            if (gid != null && groupUniform.containsKey(gid)) {
                if (handled.add(gid)) {
                    put(merged, gid, groupUniform.get(gid));
                }
                handled.add(other.id());
                continue;
            }
            RelationType type = graph.resolve(myFactionId, other.id());
            if (type == null || type == RelationType.NEUTRAL) {
                continue;
            }
            put(merged, other.name(), type.name().toLowerCase(Locale.ROOT));
        }
        return new ArrayList<>(merged.values());
    }

    /** 追加一条关系：按「名称+关系」去重并保首见顺序。 */
    private static void put(Map<String, JsonObject> out, String label, String typeName) {
        String key = label + "\u0000" + typeName;
        if (!out.containsKey(key)) {
            JsonObject o = new JsonObject();
            o.addProperty("name", label);
            o.addProperty("type", typeName);
            out.put(key, o);
        }
    }
}
