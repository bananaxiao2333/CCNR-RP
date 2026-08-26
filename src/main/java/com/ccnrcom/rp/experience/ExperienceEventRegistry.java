/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.experience;

import com.ccnrcom.rp.experience.ExprParser.Kind;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 经验事件注册表（纯逻辑）：内置广播事件与固定参数表（名+类型）。
 * 客户端/服务端共用：管理面板事件补全、参数面板、表达式静态类型检查、试算默认值。
 */
public final class ExperienceEventRegistry {

    /** 参数类型（管理面板展示与试算默认值用）。 */
    public enum ParamType {
        STRING,
        LONG
    }

    /** 事件参数。 */
    public record Param(String name, ParamType type) {}

    /** 事件定义。 */
    public record EventDef(String id, List<Param> params) {}

    /** 内置事件（顺序即管理面板下拉顺序）。 */
    public static final List<EventDef> EVENTS = List.of(
            new EventDef(
                    "character_alive",
                    List.of(
                            new Param("uuid", ParamType.STRING),
                            new Param("playerName", ParamType.STRING),
                            new Param("professionId", ParamType.STRING),
                            new Param("factionId", ParamType.STRING),
                            new Param("aliveSeconds", ParamType.LONG),
                            new Param("intervalSeconds", ParamType.LONG))),
            new EventDef(
                    "character_kill",
                    List.of(
                            new Param("uuid", ParamType.STRING),
                            new Param("playerName", ParamType.STRING),
                            new Param("professionId", ParamType.STRING),
                            new Param("factionId", ParamType.STRING),
                            new Param("victimType", ParamType.STRING),
                            new Param("victimName", ParamType.STRING),
                            new Param("victimUuid", ParamType.STRING),
                            new Param("victimProfessionId", ParamType.STRING),
                            new Param("victimFactionId", ParamType.STRING))),
            new EventDef(
                    "character_death",
                    List.of(
                            new Param("uuid", ParamType.STRING),
                            new Param("playerName", ParamType.STRING),
                            new Param("professionId", ParamType.STRING),
                            new Param("factionId", ParamType.STRING),
                            new Param("reason", ParamType.STRING))));

    private ExperienceEventRegistry() {}

    public static Optional<EventDef> byId(String eventId) {
        for (EventDef d : EVENTS) {
            if (d.id().equals(eventId)) {
                return Optional.of(d);
            }
        }
        return Optional.empty();
    }

    /** 事件参数表（名→静态类型），供表达式类型检查。 */
    public static Map<String, Kind> paramKinds(String eventId) {
        Map<String, Kind> out = new LinkedHashMap<>();
        byId(eventId).ifPresent(d -> {
            for (Param p : d.params()) {
                out.put(p.name(), p.type() == ParamType.LONG ? Kind.NUM : Kind.STR);
            }
        });
        return out;
    }

    /** 事件参数试算默认值（LONG→60、STRING→空串），供管理面板验证器预填。 */
    public static Map<String, Object> defaultSample(String eventId) {
        Map<String, Object> out = new LinkedHashMap<>();
        byId(eventId).ifPresent(d -> {
            for (Param p : d.params()) {
                out.put(p.name(), p.type() == ParamType.LONG ? 60L : "");
            }
        });
        return out;
    }

    /** 是否存在该事件。 */
    public static boolean exists(String eventId) {
        return byId(eventId).isPresent();
    }
}
