/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.sync;

/**
 * 事件上报时可由调用方补填的字段（**不带 protobuf**）。
 *
 * <h2>为什么要有这个接口，而不直接把 {@code RecordEventRequest.Builder} 交给调用方</h2>
 * 那是 2.27.0 一次**服务端崩溃**的根因：{@code Builder} 是 protoc 生成的类型，
 * 出现在方法签名里就意味着**每个调用点都必须先解析 protobuf 类**才能执行到方法体，
 * 于是「数据服务不可用就静默跳过」那条短路根本轮不到执行 ——
 * 普通 jar（不含嵌套依赖）在第一次阶段推进时直接崩在 tick 循环里
 * （{@code NoClassDefFoundError: com/google/protobuf/ProtocolMessageEnum}）。
 *
 * <p>换成这个只有字符串 setter 的接口之后，调用点不再引用任何通信类型；
 * 真正的 protobuf builder 只在 {@link DataLink} 内部被适配一次，
 * 而 {@code DataLink} 只在探测通过后才构造。约束由 {@code ProtoIsolationTest} 静态守住。
 *
 * <p>方法返回 {@code this} 以便链式书写，但调用点按语句逐个 set 也完全等价。
 */
public interface EventPayload {

    /** 对应的配置定义 id（事件/阶段/职业/阵营，视种类而定）。 */
    EventPayload setDefinitionId(String value);

    /** 事件发生时所处的幕。 */
    EventPayload setPhaseId(String value);

    /** 上一幕（仅阶段推进类事件有意义）。 */
    EventPayload setPreviousPhaseId(String value);

    /** 行为主体（击杀者、部署者…）的 UUID。 */
    EventPayload setActorUuid(String value);

    /** 承受方（被击杀者、被部署者…）的 UUID。 */
    EventPayload setTargetUuid(String value);

    /**
     * 结构化补充载荷（JSON 字符串）。
     *
     * <p><b>必须由调用方自己保证是合法 JSON</b>——不要用字符串拼接去造它，
     * 理由见 docs/19 §8.1（一个引号就能让落库的载荷非法）。用 Gson 的 {@code JsonObject} 构造。
     */
    EventPayload setPayloadJson(String value);
}
