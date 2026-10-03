/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.sync;

import com.ccnrcom.rp.CCNRRPMod;
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
 *
 * <h2>签名里为什么一个 protobuf 类型都没有（2.27.0 崩溃的修复）</h2>
 * 曾经的签名是 {@code event(EventKind, String, Consumer<RecordEventRequest.Builder>)}。
 * 那让**每一个调用点**都必须先解析 protobuf 类才能执行到这里 —— 于是上面那句
 * 「{@code data == null} 就静默跳过」根本轮不到执行，普通 jar（不含嵌套依赖）
 * 在第一次阶段推进时直接崩掉：
 *
 * <pre>
 * java.lang.NoClassDefFoundError: com/google/protobuf/ProtocolMessageEnum
 *   at com.ccnrcom.rp.event.EventManager.evaluateAll(EventManager.java:361)
 *   at com.ccnrcom.rp.event.EventManager.onServerTick(EventManager.java:329)
 * </pre>
 *
 * <p>现在调用点只说两种**语言自带的类型**：{@code String}（种类，取值见 {@link EventKinds}）
 * 与 {@link EventPayload}（一个只有字符串 setter 的接口）。通信类型全部收敛在
 * {@link DataLink} 内部，而 {@code DataLink} 只在探测通过之后才被构造。
 * 这条约束不是靠自觉：{@code ProtoIsolationTest} 会扫描编译产物，任何「必然会加载的类」
 * 一旦引用 {@code com.ccnrcom.rp.data.grpc.v1} 或 {@code io.grpc} 就构建失败。
 */
public final class DataReports {

    private DataReports() {}

    /** 上报一条对局事件（无附加字段）。 */
    public static void event(String kind, String summary) {
        event(kind, summary, null);
    }

    /**
     * 上报一条对局事件。
     *
     * @param kind    见 {@link EventKinds}
     * @param summary 面板事件流里显示的一行（由调用方拼，因为它才知道玩家名）
     * @param fill    填充 actor/target/definitionId/payload 等（可为 null）
     */
    public static void event(String kind, String summary, Consumer<EventPayload> fill) {
        DataLink data = CCNRRPMod.data;
        if (data == null) {
            return;
        }
        data.recordEvent(kind, summary, fill);
    }

    /**
     * 上报一次玩家结算（**不含 protobuf**，理由同上）。
     *
     * @param draft 由调用方填好的纯数据草稿；{@code serverId}/{@code matchId}/{@code requestId}
     *              由 {@link DataLink} 统一盖章
     * @param onAck 确认回调（主线程），收到即表示「数据服务已确认，可以刷新界面了」；可为 null
     */
    public static void settlement(SettlementDraft draft, Runnable onAck) {
        DataLink data = CCNRRPMod.data;
        if (data == null) {
            return;
        }
        data.recordSettlement(draft, onAck);
    }

    /**
     * 拉一次玩家档案（登录时刷新等级显示用）。取不到就 {@link RemoteProfiles#forget}——界面不显示等级。
     *
     * @param onOk 成功回调（主线程）；可为 null
     */
    public static void fetchPlayer(String uuid, Runnable onOk) {
        DataLink data = CCNRRPMod.data;
        if (data == null) {
            return;
        }
        data.fetchPlayer(uuid, onOk);
    }

    /**
     * 这个玩家**被数据服务确认过**的档案；没有则 null（界面据此不显示等级）。
     *
     * <p>为什么要在这里开个口子，而不是让调用方写 {@code CCNRRPMod.data.profiles().get(...)}：
     * 那样调用点就得引用 {@link DataLink} 类型。它虽然本身不带 protobuf，但它是**唯一**
     * 允许接触通信类型的类——把「谁能碰它」收窄到本门面，静态门禁才守得住
     * （{@code ProtoIsolationTest}）。
     */
    public static RemoteProfiles.Snapshot confirmedProfile(String uuid) {
        DataLink data = CCNRRPMod.data;
        return data == null ? null : data.profiles().get(uuid);
    }

    /** 这个玩家是否有**被确认过**的档案（与 {@link #confirmedProfile} 同一判据的两个问法）。 */
    public static boolean profileKnown(String uuid) {
        DataLink data = CCNRRPMod.data;
        return data != null && data.profiles().known(uuid);
    }
}
