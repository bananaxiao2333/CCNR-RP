/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.sync;

/**
 * 对局事件种类的**纯字符串常量**（不带 protobuf）。
 *
 * <h2>为什么不用生成的 {@code EventKind} 枚举</h2>
 * 这是 2.27.0 一次**服务端崩溃**的修复产物，值得写清楚：
 *
 * <p>{@code EventKind} 是 protoc 生成的 protobuf 枚举，它继承
 * {@code com.google.protobuf.ProtocolMessageEnum}。只要某个类的**参数类型或方法体**里出现它，
 * JVM 就必须在**执行到那一行**时解析 protobuf 类。而普通 jar（不含嵌套的 protobuf/gRPC）
 * 里没有这个类 —— 于是即使代码里写着「{@code CCNRRPMod.data == null} 就静默跳过」，
 * 崩溃也发生在**求值那个短路判断之前**：
 *
 * <pre>
 * java.lang.NoClassDefFoundError: com/google/protobuf/ProtocolMessageEnum
 *   at com.ccnrcom.rp.event.EventManager.evaluateAll(EventManager.java:361)
 *   at com.ccnrcom.rp.event.EventManager.onServerTick(EventManager.java:329)
 * </pre>
 *
 * 服务器启动约 3 秒后、第一次阶段推进时崩在 tick 循环里。根因不是探测写错了
 * （探测明确报了「未找到 gRPC 运行时」），而是**上报 API 的签名里带了可选依赖的类型**。
 *
 * <p>因此对外（任何「必然会加载」的类）一律用这里的字符串常量；字符串 → {@code EventKind}
 * 的映射只发生在 {@link DataLink} 内部——那是唯一允许接触通信类型的地方，
 * 且它只在探测通过之后才会被构造。这条约束由 {@code ProtoIsolationTest} 静态守住。
 *
 * <p>取值必须与 {@code moddata.proto} 的 {@code EventKind} 一一对应（去掉 {@code EVENT_KIND_} 前缀、
 * 转小写）。对不上时 {@link DataLink} 会退回 {@code EVENT_KIND_UNSPECIFIED} 并记一条 WARN，
 * 而不是抛异常——上报是旁路，标错一个种类不该让主流程受影响。
 */
public final class EventKinds {

    /** proto 里的 {@code EVENT_KIND_UNSPECIFIED}：兜底值，正常配置不该用到。 */
    public static final String UNSPECIFIED = "unspecified";

    public static final String ROUND_ENDED = "round_ended";
    public static final String GAME_OVER = "game_over";
    public static final String PHASE_ADVANCED = "phase_advanced";
    public static final String CONFIG_EVENT_TRIGGERED = "config_event_triggered";
    public static final String EVENT_ENDED = "event_ended";
    public static final String EVENTS_CLEARED = "events_cleared";
    public static final String CHARACTER_DEPLOYED = "character_deployed";
    public static final String CHARACTER_DEATH = "character_death";
    public static final String CHARACTER_KILL = "character_kill";
    public static final String EVACUATION = "evacuation";
    public static final String SPAWN_WAVE_TRIGGERED = "spawn_wave_triggered";

    private EventKinds() {}
}
