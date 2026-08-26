/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.experience;

import java.util.ArrayList;
import java.util.List;

/**
 * 经验变化列表（纯逻辑）：一个存活角色的待结算经验变化描述。
 * 合并语义：同 ruleId 的条目 value 相加、title 取后来者；不同规则追加（保持插入顺序）。
 */
public final class XpChangeList {

    /** 一条经验变化。 */
    public record XpChange(String ruleId, String title, long value) {}

    private XpChangeList() {}

    /** 新增一条变化（按规则合并）；返回新列表（不可变语义）。 */
    public static List<XpChange> add(List<XpChange> list, String ruleId, String title, long value) {
        List<XpChange> out = new ArrayList<>(list.size() + 1);
        boolean merged = false;
        for (XpChange c : list) {
            if (!merged && c.ruleId().equals(ruleId)) {
                // 合并：数值相加、标题取后来者（新条目标题）、保持原位置
                out.add(new XpChange(ruleId, title, c.value() + value));
                merged = true;
            } else {
                out.add(c);
            }
        }
        if (!merged) {
            out.add(new XpChange(ruleId, title, value));
        }
        return out;
    }

    /** 列表求和（可为负）。 */
    public static long sum(List<XpChange> list) {
        long s = 0;
        for (XpChange c : list) {
            s += c.value();
        }
        return s;
    }
}
