/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.sync;

import com.ccnrcom.rp.CCNRRPMod;
import com.ccnrcom.rp.data.grpc.v1.EventKind;
import com.ccnrcom.rp.data.grpc.v1.RecordEventRequest;
import java.util.function.Consumer;

/**
 * 对局事件上报的门面（**MC 感知**，与 MC 无关的 {@link DataLink} 分开放）。
 *
 * <h2>为什么要有这一层</h2>
 * {@link DataLink} 刻意不 import 任何 Minecraft 类型，因此它拿不到 {@code CCNRRPMod.data}。
 * 这一层就干一件事：把「数据链路没装好就静默跳过」这个判断收在一处，让调用点是一行。
 *
 * <p>「静默跳过」是刻意的：事件上报是**旁路**。模组启动顺序（服务实例还没装配）、
 * 配置关闭、链路建不起来——任何一种都不该让击杀/阵亡/阶段推进这些主流程受影响。
 * 反过来，数据对不对则是另一回事：确认不了的值界面就不显示（见 {@link RemoteProfiles}）。
 */
public final class DataReports {

    private DataReports() {}

    /** 上报一条对局事件（无附加字段）。 */
    public static void event(EventKind kind, String summary) {
        event(kind, summary, null);
    }

    /**
     * 上报一条对局事件。
     *
     * @param summary 面板事件流里显示的一行（由调用方拼，因为它才知道玩家名）
     * @param fill    填充 actor/target/definitionId/payload 等（可为 null）
     */
    public static void event(EventKind kind, String summary, Consumer<RecordEventRequest.Builder> fill) {
        DataLink data = CCNRRPMod.data;
        if (data == null) {
            return;
        }
        data.recordEvent(kind, summary, fill);
    }
}
