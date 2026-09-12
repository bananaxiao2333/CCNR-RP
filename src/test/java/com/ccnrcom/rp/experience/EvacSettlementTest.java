/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.experience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccnrcom.rp.experience.XpChangeList.XpChange;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 疏散结算的纯逻辑契约（docs/06 §6）：其他分数照算、不引入阵亡扣分、同标题合并、前缀隔离。
 *
 * <p>这几个断言钉住的是**语义**而不是实现细节——它们同时是"以后有人改疏散给分方式"的回退网。
 */
class EvacSettlementTest {

    /** 正常路径：疏散分追加在既有条目之后，总值 = 其他分数 + 疏散分（其他分数照算）。 */
    @Test
    void evacKeepsExistingEntriesAndAddsOnTop() {
        List<XpChange> pending = List.of(new XpChange("alive_duty", "值班", 12), new XpChange("kill_bonus", "击杀敌人", 25));
        List<XpChange> out = EvacSettlement.apply(pending, "疏散", 200);

        assertEquals(3, out.size());
        assertEquals(
                List.of("alive_duty", "kill_bonus", "evac:疏散"),
                out.stream().map(XpChange::ruleId).toList());
        assertEquals(12 + 25 + 200, XpChangeList.sum(out));
    }

    /** 不扣死亡分：疏散不向列表注入任何负分条目（阵亡扣分只在 character_death 时产生）。 */
    @Test
    void evacInjectsNoNegativeEntry() {
        List<XpChange> out = EvacSettlement.apply(List.of(), "疏散", 200);
        assertEquals(1, out.size());
        assertTrue(out.get(0).value() > 0);
        assertEquals(200, XpChangeList.sum(out));
    }

    /** 边界：空列表/上一次疏散残留——同标题按 ruleId 合并相加，不产生重复行。 */
    @Test
    void repeatedEvacMergesIntoSingleEntry() {
        List<XpChange> out = EvacSettlement.apply(EvacSettlement.apply(List.of(), "疏散", 200), "疏散", 50);
        assertEquals(1, out.size());
        assertEquals(250, out.get(0).value());
    }

    /** 边界：不同标题是两条独立条目（同局两次疏散给不同名义的分，不互相吞掉）。 */
    @Test
    void differentTitlesStaySeparate() {
        List<XpChange> out = EvacSettlement.apply(EvacSettlement.apply(List.of(), "疏散", 200), "加时结算", 30);
        assertEquals(2, out.size());
        assertEquals(230, XpChangeList.sum(out));
    }

    /** 非法输入边界：null 待结算列表按空处理（不抛异常）；xp=0 是合法用法（只疏散不加分）。 */
    @Test
    void nullPendingTreatedAsEmptyAndZeroXpAllowed() {
        assertEquals(200, XpChangeList.sum(EvacSettlement.apply(null, "疏散", 200)));
        List<XpChange> zero = EvacSettlement.apply(List.of(new XpChange("alive_duty", "值班", 7)), "疏散", 0);
        assertEquals(2, zero.size());
        assertEquals(7, XpChangeList.sum(zero));
    }

    /** 前缀隔离：疏散条目不与规则条目、manual: 手动记分同 id（保证合并语义不被串台）。 */
    @Test
    void ruleIdPrefixIsIsolated() {
        assertEquals("evac:疏散", EvacSettlement.ruleId("疏散"));
        assertTrue(EvacSettlement.ruleId("疏散").startsWith(EvacSettlement.RULE_PREFIX));
        assertEquals("evac:", EvacSettlement.ruleId(null));
    }
}
