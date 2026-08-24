/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.faction;

import java.util.List;
import java.util.Map;

/** P1 阵营关系领域模型（无 MC 依赖的纯数据/纯逻辑，可直接 JUnit 测）。 */
public final class FactionModels {

    /** 阵营。icon 为客户端徽章图形(shield/claw/storm/hex/eye/target...)，tier 1..3 对应金/蓝/青徽章等级。 */
    public record Faction(String id, String name, String color, String description, String icon, int tier) {
        public Faction(String id, String name, String color, String description) {
            this(id, name, color, description, "hex", 2);
        }
    }

    /** 阵营组：批量声明关系的容器。 */
    public record FactionGroup(String id, List<String> memberIds) {}

    /** 关系声明：from/to 可以是阵营或组（两端同种类），type 为关系类型；组×组覆盖全成员组合。 */
    public record RelationRule(String from, String to, RelationType type) {}

    /** 图解析结果：成功时为图；失败时给出错误（拒绝加载）+ 警告。 */
    public record ParseResult(FactionGraph graph, List<String> errors, List<String> warnings) {
        public boolean success() {
            return errors.isEmpty();
        }

        public static ParseResult failure(List<String> errors) {
            return new ParseResult(null, errors, List.of());
        }
    }

    /** 内部零件：仅 FactionGraph 使用。 */
    public record Snapshot(Map<String, Faction> factions, Map<String, FactionGroup> groups, List<RelationRule> rules) {}
}
