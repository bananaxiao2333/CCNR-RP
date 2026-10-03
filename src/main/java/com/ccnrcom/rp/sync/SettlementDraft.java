/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.sync;

import java.util.List;

/**
 * 一次玩家结算的**纯数据草稿**（无 MC、**无 protobuf**）。
 *
 * <p>与 {@link EventPayload} 同一理由：结算原先由调用方（{@code ExperienceService}）
 * 自己 {@code RecordSettlementRequest.newBuilder()...build()} 出一个 protobuf 消息，
 * 那条路径让「必然会加载的类」引用了可选依赖的类型，于是普通 jar 一走到结算就崩。
 * 现在调用方只填这个 record，protobuf 消息由 {@link DataLink} 内部构造。
 *
 * <p>{@code serverId} / {@code matchId} / {@code requestId} **不在这里**：
 * 它们只有 {@link DataLink} 知道（连接配置与幂等键生成），由它在盖章处统一填。
 */
public record SettlementDraft(
        String playerUuid,
        String username,
        String reason,
        long xpBefore,
        long xpAfter,
        int levelBefore,
        int levelAfter,
        long gain,
        List<Line> changes) {

    /**
     * 一条结算明细（「+100 击杀奖金」）。
     *
     * @param ruleId 经验规则 id（可能为空：手动调整等没有规则）
     * @param title  面板上显示的一行标题
     * @param value  正负分值
     */
    public record Line(String ruleId, String title, long value) {
        public Line {
            ruleId = ruleId == null ? "" : ruleId;
            title = title == null ? "" : title;
        }
    }

    public SettlementDraft {
        playerUuid = playerUuid == null ? "" : playerUuid;
        username = username == null ? "" : username;
        reason = reason == null ? "" : reason;
        changes = changes == null ? List.of() : List.copyOf(changes);
    }
}
