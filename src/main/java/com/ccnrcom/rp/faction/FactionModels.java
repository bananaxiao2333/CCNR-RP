/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.faction;

import java.util.List;
import java.util.Map;

/** P1 阵营关系领域模型（无 MC 依赖的纯数据/纯逻辑，可直接 JUnit 测）。 */
public final class FactionModels {

    /** 阵营。icon 为客户端徽章图形(shield/claw/storm/hex/eye/target...)，tier 1..3 对应金/蓝/青徽章等级；music 为阵营出场音乐；cmdcamScene 为 CMDCam 出场摄像机场景（可选，空=不用）；cinematicBlackScreen 是否播放入场全屏黑；cinematicCompact 是否用入场电影「简洁模式」（信息缩小移到左下方、靠左对齐，图标仍在顶端）。 */
    public record Faction(
            String id,
            String name,
            String color,
            String description,
            String icon,
            int tier,
            String music,
            String cmdcamScene,
            boolean cinematicBlackScreen,
            boolean cinematicCompact) {
        public Faction(String id, String name, String color, String description) {
            this(id, name, color, description, "hex", 2, "", "", true, false);
        }
    }

    /** 阵营组：批量声明关系的容器。 */
    public record FactionGroup(String id, List<String> memberIds) {}

    /**
     * 关系声明（多对多）：from/to 各为一个 id 列表，列表项可以是阵营 id 或组 id（组自动展开为成员）；
     * 生效范围 = from 列表 × to 列表的**笛卡尔积**（双方所有组合），关系双向对称。
     * **内部关系**：省略 to（或 from=to 同一列表）时 = 该列表内所有阵营**两两互设**该关系
     * （如 {from:[a,b,c], type:friendly} → a↔b、a↔c、b↔c 全部友好）。
     * 列表按**从上到下优先级**：先声明（靠前）的规则优先，命中即生效，后面重复声明被忽略并 WARN。
     */
    public record RelationRule(List<String> from, List<String> to, RelationType type) {
        /** 兼容单对声明（命令/旧配置/测试）。 */
        public RelationRule(String from, String to, RelationType type) {
            this(List.of(from), List.of(to), type);
        }

        /** 内部关系：单列表内两两互设。 */
        public RelationRule(List<String> members, RelationType type) {
            this(members, members, type);
        }
    }

    /** 图边（已解析到阵营对，供关系测定图渲染）：a ↔ b 之间的生效关系。 */
    public record RelationEdge(String a, String b, RelationType type) {}

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
