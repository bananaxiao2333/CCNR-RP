/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.experience;

import com.ccnrcom.rp.experience.XpChangeList.XpChange;
import java.util.List;

/**
 * 疏散结算的纯逻辑部分（无 MC import，可脱机单测）。
 *
 * <p><b>契约（docs/06 §6 / docs/09 疏散）</b>：
 * <ul>
 *   <li><b>其他分数照算</b>：疏散只往待结算列表**追加一条**条目，不丢弃、不覆盖既有的值班/击杀等条目——
 *       最终结算仍是 {@link XpChangeList#sum} 对整份列表求和。</li>
 *   <li><b>不扣死亡分</b>：疏散走**非死亡路径**（不广播 {@code character_death}），因此阵亡规则
 *       （如 {@code death_penalty} -10）不会在疏散时被触发；本类也不会向列表里注入任何负分条目。
 *       已经阵亡过的人，其扣分在死亡当时就已随死亡结算入账，疏散不追溯、不回冲。</li>
 *   <li><b>同标题合并</b>：疏散分用固定前缀 {@link #RULE_PREFIX} 作为 ruleId，与规则条目
 *       （ruleId = 规则 id）和手动记分（{@code manual:<标题>}）互不冲突；同一次疏散重复执行时按
 *       {@link XpChangeList#add} 语义相加，不会产生重复行。</li>
 * </ul>
 *
 * <p><b>为什么单独一个类</b>：这三条语义是"疏散给分"的规则核心，属于 AGENTS.md 要求的
 * "规则逻辑必须是无 MC 依赖的纯类、可 JUnit 直测"；把它放在这里，服务端编排
 * （{@link ExperienceService#evacuateAlive}）只剩遍历与状态迁移。
 */
public final class EvacSettlement {

    /** 疏散条目的 ruleId 前缀（与规则 id、{@code manual:} 手动记分区分开）。 */
    public static final String RULE_PREFIX = "evac:";

    private EvacSettlement() {}

    /** 疏散条目的 ruleId（同标题即同一条，重复疏散相加）。 */
    public static String ruleId(String title) {
        return RULE_PREFIX + (title == null ? "" : title);
    }

    /**
     * 把疏散分并入待结算列表：返回新列表（既有条目原样保留、顺序不变，疏散条目追加或按 ruleId 合并）。
     *
     * @param pending 该用户当前的待结算列表（不可变，返回新列表）
     * @param title   疏散分标题（同时作为合并键与 HUD 显示文案；调用方需保证非空）
     * @param xp      疏散分（可为负，但疏散语义上只应给正分）
     */
    public static List<XpChange> apply(List<XpChange> pending, String title, long xp) {
        return XpChangeList.add(pending == null ? List.of() : pending, ruleId(title), title, xp);
    }
}
