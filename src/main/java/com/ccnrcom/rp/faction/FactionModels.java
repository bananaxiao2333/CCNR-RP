/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.faction;

import java.util.List;
import java.util.Map;

/** P1 阵营关系领域模型（无 MC 依赖的纯数据/纯逻辑，可直接 JUnit 测）。 */
public final class FactionModels {

    /**
     * 阵营。icon 为客户端徽章图形(shield/claw/storm/hex/eye/target...)，tier 1..3 对应金/蓝/青徽章等级；
     * music 为阵营出场音乐；cmdcamScene 为 CMDCam 出场摄像机场景（可选，空=不用）；
     * cinematicBlackScreen 是否播放入场全屏黑。
     *
     * <p>入场电影版式自 2.25.3 起唯一（左下方简洁版式），原 per-faction 开关字段 {@code cinematicCompact}
     * 已删除：存量 factions.json 里的该键不再被读取，也不会被写路径清除（无害孤儿键，同 docs/16 §5 的
     * warheadEnabled 处理方式）。
     */
    public record Faction(
            String id,
            String name,
            String color,
            String description,
            String icon,
            int tier,
            String music,
            String cmdcamScene,
            boolean cinematicBlackScreen) {
        public Faction(String id, String name, String color, String description) {
            this(id, name, color, description, "hex", 2, "", "", true);
        }
    }

    /**
     * 阵营组：批量声明关系的容器。name 为外显名称（缺省回退 id；管理面板可编辑）。
     * 组与阵营共享 id 命名空间（name 仅用于展示，不参与 id 引用）。
     */
    public record FactionGroup(String id, String name, List<String> memberIds) {
        /** 兼容无外显名称的构造（name 回退为 id）。 */
        public FactionGroup(String id, List<String> memberIds) {
            this(id, id, memberIds);
        }
    }

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
