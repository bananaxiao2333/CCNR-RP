/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.sync;

import com.ccnrcom.rp.data.grpc.v1.EventKind;
import com.ccnrcom.rp.data.grpc.v1.GetPlayerRequest;
import com.ccnrcom.rp.data.grpc.v1.GetPlayerResponse;
import com.ccnrcom.rp.data.grpc.v1.ModDataServiceGrpc;
import com.ccnrcom.rp.data.grpc.v1.PlayerData;
import com.ccnrcom.rp.data.grpc.v1.RecordEventRequest;
import com.ccnrcom.rp.data.grpc.v1.RecordEventResponse;
import com.ccnrcom.rp.data.grpc.v1.RecordSettlementRequest;
import com.ccnrcom.rp.data.grpc.v1.RecordSettlementResponse;
import com.ccnrcom.rp.data.grpc.v1.SettlementLine;
import com.google.gson.JsonObject;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Timestamp;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 与数据服务（CCNR-Data）的唯一通道。
 *
 * <h2>职责边界</h2>
 * 本类**只做**「把模组已经决定的事实发出去，把数据服务确认过的事实收回来」：不计算任何游戏数值
 * （等级曲线在 {@code experience.LevelCurve}）、不做业务判断（该不该结算由 {@code ExperienceService}
 * 决定）、不重试到成功为止（失败入队，见 {@link DataOutbox}）。
 *
 * <h2>线程模型</h2>
 * gRPC 的网络收发在 netty 自己的线程上，但**所有回调都被派发回 {@code callbackExecutor}**
 * （实机上是服务端主线程，见 {@code CCNRRPMod} 的装配）。于是 {@link RemoteProfiles} 与所有发包
 * 都发生在主线程，不需要任何锁 —— 对齐模组既有纪律「Level/NBT/网络/渲染必须回主线程」。
 * 反过来说：本类的任何方法**不得**在 netty 线程上被调用。
 *
 * <h2>为什么失败要分「可重试 / 不可重试」</h2>
 * {@code UNAVAILABLE} 是「现在不行」——入队，等数据服务回来。{@code INVALID_ARGUMENT} /
 * {@code FAILED_PRECONDITION} 是「这条请求本身不对」——重放一万次也不会成功，只会把队列塞满、
 * 把真正该重试的挤出去。所以判据写在唯一的 {@link #send} 出口上，而不是散在各调用点。
 *
 * <p><b>MC 无关</b>：本类不 import 任何 Minecraft 类型，因此可以直接 JUnit 测（对着真实 gRPC
 * 通道跑，见 {@code DataLinkTest}）。
 */
public final class DataLink {
    private static final Logger LOGGER = LogManager.getLogger();

    /** 重放队列里记录的方法标识（本模组内部用；不是网络路径）。 */
    private static final String M_EVENT = "RecordEvent";

    private static final String M_SETTLEMENT = "RecordSettlement";
    /** 重放卡死解锁的余量（超时 + 这个值之后仍未收到任何回调就强制解锁）。 */
    private static final long REPLAY_UNLOCK_SLACK_MS = 2_000L;

    /** 可用性状态。 */
    public enum State {
        /** 配置关闭：完全不上报，界面也不显示需要确认的数据。 */
        DISABLED,
        /** 通道还没连上（冷启动 / 数据服务重启中）。 */
        CONNECTING,
        /** 通道观测为 READY。 */
        READY,
        /** 通道处于 TRANSIENT_FAILURE 或已关闭。 */
        DOWN
    }

    private final boolean enabled;
    private final String target;
    private final String serverId;
    private final int timeoutMs;
    private final int recentCap;
    private final Executor callbackExecutor;

    private final DataOutbox outbox;
    private final RemoteProfiles profiles = new RemoteProfiles();
    private final Deque<JsonObject> recent = new ArrayDeque<>();

    private ManagedChannel channel;
    private ModDataServiceGrpc.ModDataServiceStub stub;
    private boolean stopped;

    /** 重放中：tick 不重复发同一条；由回调解锁，或超过 {@link #replayUnlockAt} 强制解锁。 */
    private boolean replayInFlight;

    private long replayUnlockAt;

    private String lastError = "";
    private long lastOkMs;
    private long sentOk;
    private long sentFail;
    private String matchId = UUID.randomUUID().toString();
    /** 幂等键的序号部分（见 {@link #nextRequestId}）；只在主线程自增。 */
    private long requestSeq;

    public DataLink(
            Path worldDir,
            boolean enabled,
            String target,
            String serverId,
            int timeoutMs,
            int outboxMax,
            int recentCap,
            Executor callbackExecutor) {
        this.enabled = enabled;
        this.target = target == null || target.isBlank() ? "127.0.0.1:8081" : target.trim();
        this.serverId = serverId == null || serverId.isBlank() ? "ccnr-rp" : serverId.trim();
        this.timeoutMs = Math.max(200, timeoutMs);
        this.recentCap = Math.max(1, recentCap);
        this.callbackExecutor = callbackExecutor;
        this.outbox = new DataOutbox(worldDir.resolve("data_outbox.json"), outboxMax);
    }

    // ------------------------------------------------------------------ 生命周期

    /** 启动：读队列 + 建通道。关闭时只读队列（便于 /rp data 显示），不建通道。 */
    public void start() {
        outbox.load();
        if (!enabled) {
            LOGGER.info("[CCNR-RP] 数据服务已关闭（serverconfig: data.enabled=false）：不上报，界面不显示等级");
            return;
        }
        try {
            channel = ManagedChannelBuilder.forTarget(target)
                    .usePlaintext() // 只连内网/回环（CCNR-Data docs/04 §1.1）
                    .build();
            // 超时在这里设一次：三个上报 RPC 都是短请求，没有需要长 deadline 的调用点
            stub = ModDataServiceGrpc.newStub(channel)
                    .withExecutor(callbackExecutor)
                    .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS);
            LOGGER.info("[CCNR-RP] 数据服务通道已建立：{}（serverId={}）", target, serverId);
        } catch (RuntimeException e) {
            // 地址非法等：不抛给启动流程，降级为 DOWN —— 后果是等级不显示，不是服务端起不来
            lastError = "建通道失败: " + e;
            LOGGER.error("[CCNR-RP] 数据服务通道建立失败（等级显示将不可用）: {}", e.toString());
            channel = null;
            stub = null;
        }
    }

    /** 对称清理：停通道 + 队列落盘（停机时队列里可能还有没发出去的）。 */
    public void stop() {
        stopped = true;
        outbox.save();
        ManagedChannel c = channel;
        channel = null;
        stub = null;
        profiles.clear();
        if (c != null) {
            c.shutdownNow();
            try {
                c.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public State state() {
        if (!enabled) {
            return State.DISABLED;
        }
        ManagedChannel c = channel;
        if (c == null || stopped) {
            return State.DOWN;
        }
        ConnectivityState cs;
        try {
            cs = c.getState(false);
        } catch (RuntimeException e) {
            return State.DOWN;
        }
        return switch (cs) {
            case READY -> State.READY;
            case TRANSIENT_FAILURE, SHUTDOWN -> State.DOWN;
                // IDLE：通道刚建好、还没尝试连接。算 CONNECTING 而不是 DOWN，
                // 否则「刚启动」会被显示成「连不上」，把诊断引向错误方向。
            case IDLE, CONNECTING -> State.CONNECTING;
        };
    }

    public RemoteProfiles profiles() {
        return profiles;
    }

    public int pendingCount() {
        return outbox.size();
    }

    // ------------------------------------------------------------------ 对局标识

    /** 当前对局标识。模组启动与每次重开一局都会换新的（见 CCNR-Data docs/04 §2.3）。 */
    public String matchId() {
        return matchId;
    }

    /** 换一局。在途上报不回填新 id —— 那是上一局的事。 */
    public void newMatch() {
        matchId = UUID.randomUUID().toString();
        LOGGER.info("[CCNR-RP] 新对局标识：{}", matchId);
    }

    /**
     * 生成幂等键（{@code receipt_id}）。由本类统一生成、**不由各调用点各写一份**。
     *
     * <h2>为什么不能让调用点自己拼</h2>
     * 这个键是「一次业务意图」的标识，重试时必须一字不差地复用（复用靠的是
     * {@link DataOutbox} 存的是整条消息字节，所以只要生成一次就一定稳定）。
     * 但它的**格式**受数据服务校验：{@code [A-Za-z0-9][A-Za-z0-9_.:-]*}、≤128 字符。
     * 第一版按文档草稿拼成 {@code matchId/uuid/时间戳}，实测被数据服务以
     * {@code INVALID_ARGUMENT} 全部拒绝 —— 斜杠不在字符集里。**每一条结算都会被丢掉**，
     * 而且症状是"等级一直不显示"（因为确认永远回不来），不是一条明确的错误。
     * 所以格式约束收在这一个方法里，改了也只有一处要改。
     *
     * <h2>为什么不会重复</h2>
     * {@code matchId} 是进程内首次构造与每次重开一局时新生成的 UUID，因此
     * 「换服、重启模组、重开一局」都不会沿用旧键；序号在进程内单调递增，
     * 于是「同一毫秒内的两次同种事件」也不会撞（用时间戳当键会撞，而撞了的事件会被
     * 数据服务当成重放**静默丢掉**）。
     *
     * <p>长度上限：kind(&lt;24) + 36 + 序号(&lt;20) ≤ 128，满足服务端校验。
     */
    private String nextRequestId(String kind) {
        return kind + ":" + matchId + ":" + (++requestSeq);
    }

    /** 事件种类的短名（{@code EVENT_KIND_CHARACTER_KILL} → {@code character-kill}）。 */
    private static String shortKind(EventKind kind) {
        String n = kind.name();
        return n.startsWith("EVENT_KIND_")
                ? n.substring("EVENT_KIND_".length()).toLowerCase(java.util.Locale.ROOT)
                : n.toLowerCase(java.util.Locale.ROOT);
    }

    /** 事件种类的短名（字符串形态：{@code character_kill} → {@code character-kill}）。 */
    private static String shortKind(String kind) {
        return kind.replace('_', '-').toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 字符串种类 → protobuf 枚举。
     *
     * <p>**只在本类内发生**：把通信类型收敛在一处，调用点就永远不必解析 protobuf 类
     * （这是 2.27.0 崩溃的修复要点，见 {@link EventKinds} 的类注释）。
     *
     * <p>认不出来时退回 {@code EVENT_KIND_UNSPECIFIED} 并记一条 WARN，而不是抛异常：
     * 上报是旁路，标错一个种类不该影响击杀/部署/阶段推进。真出现 WARN 说明
     * {@code EventKinds} 与 proto 漂移了，应当修常量而不是忽略日志。
     */
    private static EventKind toEventKind(String kind) {
        String k = kind == null ? "" : kind.trim();
        if (k.isEmpty() || EVENT_KINDS_BY_SHORT_NAME.isEmpty()) {
            return EventKind.EVENT_KIND_UNSPECIFIED;
        }
        EventKind found = EVENT_KINDS_BY_SHORT_NAME.get(k.toLowerCase(java.util.Locale.ROOT));
        if (found == null) {
            LOGGER.warn("[CCNR-RP] 未知的事件种类 {}（EventKinds 与 proto 可能已漂移），按 UNSPECIFIED 上报", k);
            return EventKind.EVENT_KIND_UNSPECIFIED;
        }
        return found;
    }

    /** 短名 → 枚举的查表（由 {@link #buildEventKindIndex()} 在类初始化时建立一次）。 */
    private static final java.util.Map<String, EventKind> EVENT_KINDS_BY_SHORT_NAME = buildEventKindIndex();

    private static java.util.Map<String, EventKind> buildEventKindIndex() {
        java.util.Map<String, EventKind> out = new java.util.HashMap<>();
        for (EventKind kind : EventKind.values()) {
            out.put(shortKind(kind), kind);
        }
        return java.util.Map.copyOf(out);
    }

    /** 把 {@link EventPayload} 的 setter 适配到真正的 protobuf builder（只在本包内可见）。 */
    private static final class PayloadAdapter implements EventPayload {
        private final RecordEventRequest.Builder builder;

        private PayloadAdapter(RecordEventRequest.Builder builder) {
            this.builder = builder;
        }

        @Override
        public EventPayload setDefinitionId(String value) {
            builder.setDefinitionId(nz(value));
            return this;
        }

        @Override
        public EventPayload setPhaseId(String value) {
            builder.setPhaseId(nz(value));
            return this;
        }

        @Override
        public EventPayload setPreviousPhaseId(String value) {
            builder.setPreviousPhaseId(nz(value));
            return this;
        }

        @Override
        public EventPayload setActorUuid(String value) {
            builder.setActorUuid(nz(value));
            return this;
        }

        @Override
        public EventPayload setTargetUuid(String value) {
            builder.setTargetUuid(nz(value));
            return this;
        }

        @Override
        public EventPayload setPayloadJson(String value) {
            builder.setPayloadJson(nz(value));
            return this;
        }

        private static String nz(String v) {
            return v == null ? "" : v;
        }
    }

    // ------------------------------------------------------------------ 上报

    /**
     * 上报一条对局事件。
     *
     * <p><b>参数一律不带 protobuf 类型</b>（{@code kind} 是 {@link EventKinds} 里的字符串，
     * {@code fill} 收到的是 {@link EventPayload}）。这不是风格偏好：签名里出现生成类型会让
     * 每个调用点都必须解析 protobuf 类，普通 jar 就会在第一次上报时崩在 tick 循环里。
     *
     * @param kind    见 {@link EventKinds}
     * @param summary 面板事件流里显示的一行文字；也是「服务端权威之外」的唯一展示来源
     * @param fill    由调用方填好的 actor/target/definitionId/payload（它才知道玩家名等信息）
     */
    public void recordEvent(String kind, String summary, Consumer<EventPayload> fill) {
        long now = System.currentTimeMillis();
        EventKind enumKind = toEventKind(kind);
        // 事件流先记：面板要能在数据服务不可达时照常显示当局事件（可见性不该依赖连通性）。
        // 记的是**枚举名**（EVENT_KIND_X）——/rp data events 与 PM 的事件页签都按它显示短标签。
        pushRecent(enumKind.name(), summary, now);
        if (!enabled) {
            return;
        }
        RecordEventRequest.Builder b = RecordEventRequest.newBuilder();
        if (fill != null) {
            fill.accept(new PayloadAdapter(b));
        }
        RecordEventRequest req = b.setKind(enumKind)
                .setServerId(serverId) // 连接配置只有本类知道，统一在这里盖章
                .setMatchId(matchId)
                .setOccurredAt(timestamp(now))
                .setRequestId(nextRequestId("event-" + shortKind(enumKind)))
                .build();
        if (stub == null) {
            enqueue(M_EVENT, req);
            return;
        }
        deliverEvent(req, null);
    }

    /** 便捷重载：没有附加字段的事件（阶段推进 / 清空 / 对局收束…）。 */
    public void recordEvent(String kind, String summary) {
        recordEvent(kind, summary, null);
    }

    /**
     * 上报一次结算，并把数据服务**确认过的** xp/level 回填进 {@link RemoteProfiles}。
     *
     * <p>回填是本方法存在的全部理由：界面上的等级只能来自 {@code RecordSettlementResponse} 的回显
     * 或 {@link #fetchPlayer}。失败时该玩家会被 {@link RemoteProfiles#forget}，界面随即不显示等级。
     *
     * @param draft 纯数据草稿（**不带 protobuf**，见 {@link SettlementDraft}）
     * @param onAck 确认回调（主线程）；失败时也会被调一次（不再传响应对象——调用点从不读它）
     */
    public void recordSettlement(SettlementDraft draft, Runnable onAck) {
        if (draft == null) {
            return;
        }
        String uuid = draft.playerUuid();
        // serverId / matchId / requestId 由本类统一盖章：调用方拿不到 serverId（那是连接配置），
        // 让它去猜等于给「两个地方各写一份、将来只改一处」留口子。
        RecordSettlementRequest.Builder b = RecordSettlementRequest.newBuilder()
                .setPlayerUuid(uuid)
                .setUsername(draft.username())
                .setXpBefore(draft.xpBefore())
                .setXpAfter(draft.xpAfter())
                .setLevelBefore(draft.levelBefore())
                .setLevelAfter(draft.levelAfter())
                .setGain(draft.gain())
                .setSettledAt(timestamp(System.currentTimeMillis()))
                // reason 空串会被数据服务拒（INVALID_ARGUMENT）。兜底成 unspecified 而不是让它丢掉：
                // 模组漏填一个标注不该导致这局的经验**完全不入账**。「不知道原因」是合法信息。
                .setReason(draft.reason().isBlank() ? "unspecified" : draft.reason());
        for (SettlementDraft.Line line : draft.changes()) {
            b.addLines(SettlementLine.newBuilder()
                    .setRuleId(line.ruleId())
                    .setTitle(line.title())
                    .setValue(line.value()));
        }
        RecordSettlementRequest sealed = b.setServerId(serverId)
                .setMatchId(matchId)
                .setRequestId(nextRequestId("settle"))
                .build();
        if (!enabled) {
            ack(onAck);
            return;
        }
        if (stub == null) {
            enqueue(M_SETTLEMENT, sealed);
            profiles.forget(uuid, "通道不可用");
            ack(onAck);
            return;
        }
        // 先按「未知」清一次：在确认回来之前，界面不该继续显示上一笔的等级
        profiles.forget(uuid);
        deliverSettlement(sealed, onAck, null);
    }

    /** 确认回调的统一收口（null 安全）。 */
    private static void ack(Runnable onAck) {
        if (onAck != null) {
            onAck.run();
        }
    }

    /**
     * 读玩家档案（登录时刷新显示用）。取不到就 {@link RemoteProfiles#forget} —— 界面不显示等级。
     *
     * <p>{@code NOT_FOUND}（数据侧没有这个玩家）**不算故障**：它意味着「从来没有确认过的数」，
     * 结果与故障一致（不显示），但不会打错误日志误导排查。
     *
     * @param onOk 成功回调（主线程）。**不带档案对象**：调用点只用它触发一次「刷新等级显示」，
     *             权威值已经由本方法回填进 {@link RemoteProfiles}，再传一份出去只会多一个数据源。
     */
    public void fetchPlayer(String uuid, Runnable onOk) {
        if (!enabled || uuid == null || uuid.isBlank()) {
            profiles.forget(uuid);
            return;
        }
        if (stub == null) {
            profiles.forget(uuid, "通道不可用");
            return;
        }
        GetPlayerRequest req = GetPlayerRequest.newBuilder()
                .setPlayerUuid(uuid)
                .setServerId(serverId)
                .build();
        stub.getPlayer(req, new StreamObserver<>() {
            @Override
            public void onNext(GetPlayerResponse value) {
                markOk();
                PlayerData p = value.getPlayer();
                profiles.put(uuid, p.getExperience(), p.getLevel());
                if (onOk != null) {
                    onOk.run();
                }
            }

            @Override
            public void onError(Throwable t) {
                sentFail++;
                Status.Code code = Status.fromThrowable(t).getCode();
                if (code == Status.Code.NOT_FOUND) {
                    profiles.forget(uuid, "数据侧无档案");
                } else {
                    profiles.forget(uuid, code.name());
                    noteFailure("GetPlayer", t);
                }
            }

            @Override
            public void onCompleted() {}
        });
    }

    // ------------------------------------------------------------------ 发送与重放

    /**
     * 事件的唯一投递路径（首次发送与重放**共用**）。
     *
     * <p>共用不是为了少写几行：重放走另一条路径时，重放成功会**丢掉只在该路径上做的事**
     * （结算回填等级就是这样一个实例）。一处投递，副作用就只可能有一份。
     *
     * @param onDone 成功后回调（重放用来出队）；首次发送传 null
     */
    private void deliverEvent(RecordEventRequest req, Runnable onFinished) {
        stub.recordEvent(req, new StreamObserver<RecordEventResponse>() {
            @Override
            public void onNext(RecordEventResponse r) {
                markOk();
            }

            @Override
            public void onError(Throwable t) {
                finishPermanentFailure(M_EVENT, req, t, onFinished);
            }

            @Override
            public void onCompleted() {
                if (onFinished != null) {
                    onFinished.run();
                }
            }
        });
    }

    /**
     * 重放失败时的收尾：**永久失败必须让这条离开队列**。
     *
     * <p>这是实测踩出来的：第一版里「不可重试」只在首次发送路径上丢弃，重放路径上
     * 既不重入队也不出队 —— 于是队头那条永久失败的请求会**一直堵在那儿**。
     * 因为队列是严格 FIFO，整个队列从此再也不动，`/rp data` 会显示「待重放 N」永不下降，
     * 而后面所有正常的结算都被它挡住。症状与"数据服务挂了"完全一样，但数据服务其实是好的。
     */
    private void finishPermanentFailure(String method, MessageLite msg, Throwable t, Runnable onFinished) {
        boolean stillQueued = markFail(method, msg, t, onFinished != null);
        if (onFinished != null && !stillQueued) {
            onFinished.run();
        }
    }

    /**
     * 结算的唯一投递路径（首次发送与重放共用）。
     *
     * <p>成功时**必须回填 {@link RemoteProfiles}** —— 那是界面能否显示等级的唯一依据。
     * 用响应里的 {@code xp_after}/{@code level_after}（服务端回显的权威值）而不是请求里的值：
     * 万一服务端做了钳制，界面要跟它一致。
     *
     * @param onAck  业务回调（主线程，不带响应对象——调用点从不读它）；重放时为 null
     * @param onDone 成功后回调（重放用来出队）；首次发送传 null
     */
    private void deliverSettlement(RecordSettlementRequest req, Runnable onAck, Runnable onDone) {
        stub.recordSettlement(req, new StreamObserver<RecordSettlementResponse>() {
            @Override
            public void onNext(RecordSettlementResponse r) {
                markOk();
                profiles.put(req.getPlayerUuid(), r.getXpAfter(), r.getLevelAfter());
                if (r.getXpDiverged()) {
                    // 不静默对齐：本地账本与库中记录不一致是真实故障的信号，必须留痕
                    LOGGER.warn(
                            "[CCNR-RP] 账本漂移：玩家 {} 上报前 xp={}，数据侧为 {}（界面以数据侧为准）",
                            req.getPlayerUuid(),
                            req.getXpBefore(),
                            r.getStoredXpBefore());
                }
                ack(onAck);
            }

            @Override
            public void onError(Throwable t) {
                finishPermanentFailure(M_SETTLEMENT, req, t, onDone);
                profiles.forget(
                        req.getPlayerUuid(), Status.fromThrowable(t).getCode().name());
                ack(onAck);
            }

            @Override
            public void onCompleted() {
                if (onDone != null) {
                    onDone.run();
                }
            }
        });
    }

    /**
     * 失败处理：可重试的入队，不可重试的记日志丢弃（分类判据见 {@link #retryable}）。
     *
     * @param alreadyQueued 这条是从队列里取出来重放的 —— 失败时它**还在队里**，
     *                      再入队一次就会在故障期间把同一条复制成 N 份，队列无限增长
     */
    private boolean markFail(String method, MessageLite msg, Throwable t, boolean alreadyQueued) {
        sentFail++;
        Status.Code code = Status.fromThrowable(t).getCode();
        noteFailure(method, t);
        if (retryable(code)) {
            if (!alreadyQueued) {
                enqueue(method, msg);
            }
            return true;
        }
        LOGGER.error(
                "[CCNR-RP] 上报被数据服务拒绝（不重试，已丢弃）：{} {} —— {}",
                method,
                code,
                Status.fromThrowable(t).getDescription());
        return false;
    }

    private void enqueue(String method, MessageLite msg) {
        outbox.add(new DataOutbox.Entry(method, msg.toByteArray()));
    }

    /**
     * 状态码分类：可重试 = 「现在不行，等会儿可能行」。
     *
     * <p>不可重试的（INVALID_ARGUMENT / FAILED_PRECONDITION / PERMISSION_DENIED / UNIMPLEMENTED …）
     * 重放一万次也不会成功。
     */
    public static boolean retryable(Status.Code code) {
        return switch (code) {
            case UNAVAILABLE, DEADLINE_EXCEEDED, ABORTED, RESOURCE_EXHAUSTED, INTERNAL, UNKNOWN -> true;
            default -> false;
        };
    }

    private void noteFailure(String method, Throwable t) {
        String desc = t instanceof StatusRuntimeException sre
                ? sre.getStatus().getCode() + ": " + sre.getStatus().getDescription()
                : String.valueOf(t);
        lastError = method + " " + desc;
    }

    private void markOk() {
        lastOkMs = System.currentTimeMillis();
        sentOk++;
    }

    /**
     * 毫秒 → protobuf {@code Timestamp}。
     *
     * <p>刻意手写而不是用 {@code com.google.protobuf.util.Timestamps}：那个类在**单独的**
     * {@code protobuf-java-util} 工件里（protobuf-java 本身不含 util 包），而它又会拖进 Gson + Guava
     * —— 为了一个除法引入两个与 Minecraft 同族不同版本的依赖不值得。
     *
     * <p>用 {@code floorDiv/floorMod} 而不是 {@code /}、{@code %}：后者对负毫秒（1970 之前）
     * 会给出负数 nanos，而 protobuf 的 Timestamp 要求 nanos ∈ [0, 1e9)。
     */
    private static Timestamp timestamp(long epochMillis) {
        return Timestamp.newBuilder()
                .setSeconds(Math.floorDiv(epochMillis, 1000L))
                .setNanos((int) Math.floorMod(epochMillis, 1000L) * 1_000_000)
                .build();
    }

    // ------------------------------------------------------------------ 重放（由模组 tick 驱动）

    /**
     * 定期调用：把队列头一条重放出去。一次只发一条 —— FIFO 顺序是刻意的（见 {@link DataOutbox}），
     * 而且突发重放会在数据服务刚恢复时把它再打一遍。
     */
    public void tick() {
        if (!enabled || stopped || stub == null) {
            return;
        }
        if (replayInFlight) {
            // 超时兜底解锁：没有这一步，一次「连错误回调都没来」的重放会把队列永久卡住，
            // 症状是「数据服务恢复后队列再也不动」——比重复重放严重得多。
            if (System.currentTimeMillis() > replayUnlockAt) {
                replayInFlight = false;
            } else {
                return;
            }
        }
        DataOutbox.Entry head = outbox.peek();
        if (head == null) {
            return;
        }
        // **刻意不看通道状态**：一次 UNAVAILABLE 会把通道打到 TRANSIENT_FAILURE，而它要等自己的
        // 退避计时器才重连。若在这里按状态放行，队列就会跟着通道一起等 —— 症状是「数据服务恢复后
        // 队列还要再等半分钟才动」。直接投递的代价只是故障期间每 5 秒一次立刻失败的 RPC。
        MessageLite msg = parse(head);
        if (msg == null) {
            outbox.removeFirst(); // 解析不了（坏数据/版本漂移）：丢掉，不让它永远堵住队头
            return;
        }
        replayInFlight = true;
        replayUnlockAt = System.currentTimeMillis() + timeoutMs + REPLAY_UNLOCK_SLACK_MS;
        Runnable done = () -> {
            outbox.removeFirst();
            replayInFlight = false;
        };
        switch (head.method()) {
            case M_EVENT -> deliverEvent((RecordEventRequest) msg, done);
            case M_SETTLEMENT -> deliverSettlement((RecordSettlementRequest) msg, null, done);
            default -> {
                LOGGER.error("[CCNR-RP] 队列里有未知方法标识，已丢弃：{}", head.method());
                outbox.removeFirst();
                replayInFlight = false;
            }
        }
    }

    private MessageLite parse(DataOutbox.Entry e) {
        try {
            return switch (e.method()) {
                case M_EVENT -> RecordEventRequest.parseFrom(e.payload());
                case M_SETTLEMENT -> RecordSettlementRequest.parseFrom(e.payload());
                default -> null;
            };
        } catch (InvalidProtocolBufferException ex) {
            LOGGER.error("[CCNR-RP] 重放队列里有一条无法解析的 {}，已丢弃", e.method());
            return null;
        }
    }

    // ------------------------------------------------------------------ 事件流（面板可见）

    private void pushRecent(String kind, String summary, long atMs) {
        JsonObject o = new JsonObject();
        o.addProperty("at", atMs);
        o.addProperty("kind", kind);
        o.addProperty("text", summary == null ? "" : summary);
        recent.addLast(o);
        while (recent.size() > recentCap) {
            recent.pollFirst();
        }
    }

    /** 当局事件流（新的在后）。管理面板与 /rp data 共用同一份，不做第二份拷贝。 */
    public List<JsonObject> recentEvents() {
        return List.copyOf(recent);
    }

    /** 重开一局时清空事件流（上一局的事件不该留在本局面板里）。 */
    public void clearRecentEvents() {
        recent.clear();
    }

    // ------------------------------------------------------------------ 诊断

    /** 诊断快照（/rp data 命令与管理面板共用同一份，避免两处各算一遍）。 */
    public JsonObject statusPayload() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.addProperty("state", state().name());
        o.addProperty("target", target);
        o.addProperty("serverId", serverId);
        o.addProperty("matchId", matchId);
        o.addProperty("timeoutMs", timeoutMs);
        o.addProperty("pending", outbox.size());
        o.addProperty("dropped", outbox.dropped());
        o.addProperty("sentOk", sentOk);
        o.addProperty("sentFail", sentFail);
        o.addProperty("lastOkMs", lastOkMs);
        o.addProperty("lastError", lastError);
        o.add("profiles", profiles.describe());
        return o;
    }
}
