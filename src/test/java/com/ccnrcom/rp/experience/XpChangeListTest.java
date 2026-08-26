/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.experience;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ccnrcom.rp.experience.XpChangeList.XpChange;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 经验变化列表：同规则合并（数值相加/标题取后来者）、顺序、负值、求和。 */
class XpChangeListTest {

    @Test
    void sameRuleMergesWithLaterTitle() {
        List<XpChange> l = List.of();
        l = XpChangeList.add(l, "alive_duty", "值班", 5);
        l = XpChangeList.add(l, "alive_duty", "值班 2 分钟", 3);
        assertEquals(1, l.size());
        assertEquals("值班 2 分钟", l.get(0).title()); // 标题取后来者
        assertEquals(8, l.get(0).value()); // 数值相加
    }

    @Test
    void differentRulesAppendInOrder() {
        List<XpChange> l = List.of();
        l = XpChangeList.add(l, "r1", "a", 1);
        l = XpChangeList.add(l, "r2", "b", 2);
        l = XpChangeList.add(l, "r3", "c", -3);
        assertEquals(List.of("r1", "r2", "r3"), l.stream().map(XpChange::ruleId).toList());
        assertEquals(0, XpChangeList.sum(l));
    }

    @Test
    void mergeKeepsFirstPosition() {
        List<XpChange> l = List.of();
        l = XpChangeList.add(l, "r1", "a", 1);
        l = XpChangeList.add(l, "r2", "b", 2);
        l = XpChangeList.add(l, "r1", "a2", 10);
        assertEquals(2, l.size());
        assertEquals("r1", l.get(0).ruleId()); // 原位合并
        assertEquals(11, l.get(0).value());
        assertEquals("a2", l.get(0).title());
    }

    @Test
    void negativeValuesSum() {
        List<XpChange> l = List.of();
        l = XpChangeList.add(l, "death", "阵亡", -10);
        l = XpChangeList.add(l, "death", "阵亡", -5);
        assertEquals(-15, XpChangeList.sum(l));
    }

    @Test
    void emptyListSumZero() {
        assertEquals(0, XpChangeList.sum(List.of()));
    }
}
