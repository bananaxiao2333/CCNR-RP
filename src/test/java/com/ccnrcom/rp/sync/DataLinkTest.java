/*
 * Copyright (c) 2026 CCNR
 * SPDX-License-Identifier: MIT
 */
package com.ccnrcom.rp.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ccnrcom.rp.data.grpc.v1.ApplyExperienceResponse;
import com.ccnrcom.rp.data.grpc.v1.GetPlayerRequest;
import com.ccnrcom.rp.data.grpc.v1.GetPlayerResponse;
import com.ccnrcom.rp.data.grpc.v1.ModDataServiceGrpc;
import com.ccnrcom.rp.data.grpc.v1.PlayerData;
import com.ccnrcom.rp.data.grpc.v1.RecordEventRequest;
import com.ccnrcom.rp.data.grpc.v1.RecordEventResponse;
import com.ccnrcom.rp.data.grpc.v1.RecordSettlementRequest;
import com.ccnrcom.rp.data.grpc.v1.RecordSettlementResponse;
import com.ccnrcom.rp.data.grpc.v1.SetRoleOverrideRequest;
import com.ccnrcom.rp.data.grpc.v1.SetRoleOverrideResponse;
import com.google.protobuf.Timestamp;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DataLink 与 RemoteProfiles：对着**真实 gRPC 通道**验证（不 mock 序列化/状态码/传输）。
 *
 * <p>为什么这些行为必须有测试：它们全是「出错时才看得见」的分支 —— 上报失败该不该入队、
 * 数据服务说 NOT_FOUND 时等级该不该显示、越界等级会不会回绕成负数。这些分支在实机上很难复现
 * （要真的把数据服务停掉、真的让它返回错误码），而它们一旦错了就是「玩家等级莫名消失」或
 * 「等级显示成 -21」这种现场无法解释的现象。
 *
 * <p>测试里的服务端是**实现同一份生成接口**的真实 gRPC 服务（跑在 netty 上、走真实 HTTP/2 与
 * protobuf 编解码），按需返回成功值或指定状态码 —— 它验证的是客户端的决策，不是服务端的业务。
 */
class DataLinkTest {

    /** 测试服务端的可编程行为。 */
    private static final class FakeService extends ModDataServiceGrpc.ModDataServiceImplBase {
        volatile Status settlementFailure;
        volatile boolean settlementDiverged;
        long storedXpBefore = 500;
        volatile Status getPlayerFailure;
        volatile long playerXp = 1234;
        volatile long playerLevel = 3;
        final List<RecordSettlementRequest> settlements = new ArrayList<>();
        final AtomicInteger events = new AtomicInteger();
        volatile boolean eventFailsUnavailable;

        @Override
        public void recordSettlement(RecordSettlementRequest req, StreamObserver<RecordSettlementResponse> obs) {
            if (settlementFailure != null) {
                obs.onError(settlementFailure.asRuntimeException());
                return;
            }
            settlements.add(req);
            obs.onNext(RecordSettlementResponse.newBuilder()
                    .setSuccess(true)
                    .setXpAfter(req.getXpAfter())
                    .setLevelAfter(req.getLevelAfter())
                    .setVersion(1)
                    .setLineCount(req.getLinesCount())
                    .setXpDiverged(settlementDiverged)
                    .setStoredXpBefore(storedXpBefore)
                    .build());
            obs.onCompleted();
        }

        @Override
        public void recordEvent(RecordEventRequest req, StreamObserver<RecordEventResponse> obs) {
            if (eventFailsUnavailable) {
                obs.onError(Status.UNAVAILABLE.asRuntimeException());
                return;
            }
            obs.onNext(RecordEventResponse.newBuilder()
                    .setSuccess(true)
                    .setSeq(events.incrementAndGet())
                    .build());
            obs.onCompleted();
        }

        @Override
        public void getPlayer(GetPlayerRequest req, StreamObserver<GetPlayerResponse> obs) {
            if (getPlayerFailure != null) {
                obs.onError(getPlayerFailure.asRuntimeException());
                return;
            }
            obs.onNext(GetPlayerResponse.newBuilder()
                    .setPlayer(PlayerData.newBuilder()
                            .setPlayerUuid(req.getPlayerUuid())
                            .setServerId(req.getServerId())
                            .setExperience(playerXp)
                            .setLevel(playerLevel)
                            .setVersion(1)
                            .setUpdatedAt(Timestamp.newBuilder().setSeconds(1).build())
                            .build())
                    .build());
            obs.onCompleted();
        }

        @Override
        public void applyExperience(
                com.ccnrcom.rp.data.grpc.v1.ApplyExperienceRequest req, StreamObserver<ApplyExperienceResponse> obs) {
            obs.onNext(ApplyExperienceResponse.newBuilder()
                    .setSuccess(true)
                    .setExperience(req.getDelta())
                    .setLevel(1)
                    .setVersion(1)
                    .build());
            obs.onCompleted();
        }

        @Override
        public void setRoleOverride(SetRoleOverrideRequest req, StreamObserver<SetRoleOverrideResponse> obs) {
            obs.onNext(SetRoleOverrideResponse.newBuilder()
                    .setSuccess(true)
                    .setVersion(1)
                    .build());
            obs.onCompleted();
        }
    }

    @TempDir
    Path dir;

    private Server server;
    private FakeService fake;
    private DataLink link;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeService();
        server = ServerBuilder.forPort(0).addService(fake).build().start();
        link = newLink("127.0.0.1:" + server.getPort());
        link.start();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (link != null) {
            link.stop();
        }
        if (server != null) {
            server.shutdownNow().awaitTermination();
        }
    }

    /**
     * 等条件成立。上报是**异步**的（gRPC 回调在 netty 线程上），所以断言前必须等 ——
     * 直接断言等于在赌网络往返比断言快，本地跑通常"运气好"过，CI 上随机红。
     * 不用 sleep 固定时长：慢机器上照样红，快机器上白等。
     */
    private static void await(String what, java.util.function.BooleanSupplier cond) {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        org.junit.jupiter.api.Assertions.fail("等待超时：" + what);
    }

    /** 回调直接在当前线程跑：测试要的是确定性，不是主线程派发。 */
    private DataLink newLink(String target) {
        return new DataLink(dir, true, target, "test-server", 2000, 16, 8, Runnable::run);
    }

    /**
     * 造一份结算草稿。
     *
     * <p>刻意**不再自己 build protobuf 消息**：那正是 2.27.0 崩掉服务器 tick 循环的写法
     * （调用方签名里带生成类型 → 普通 jar 必然 NoClassDefFoundError）。
     * 现在 serverId/matchId/requestId 都由 {@link DataLink} 盖章，调用方只给纯数据。
     */
    private static SettlementDraft settlement(String uuid, long before, long after, long level) {
        return new SettlementDraft(
                uuid,
                "玩家",
                "death",
                before,
                after,
                (int) Math.max(0, level - 1),
                (int) level,
                after - before,
                java.util.List.of(new SettlementDraft.Line("kill", "击杀奖金", after - before)));
    }

    // ------------------------------------------------------------------ 状态码分类

    @Test
    void retryableOnlyCoversTransientFailures() {
        assertTrue(DataLink.retryable(Status.Code.UNAVAILABLE));
        assertTrue(DataLink.retryable(Status.Code.DEADLINE_EXCEEDED));
        assertTrue(DataLink.retryable(Status.Code.INTERNAL));
        // 不可重试：重放一万次也不会成功，进队列只会把该重试的挤出去
        assertFalse(DataLink.retryable(Status.Code.INVALID_ARGUMENT));
        assertFalse(DataLink.retryable(Status.Code.FAILED_PRECONDITION));
        assertFalse(DataLink.retryable(Status.Code.PERMISSION_DENIED));
        assertFalse(DataLink.retryable(Status.Code.NOT_FOUND));
        assertFalse(DataLink.retryable(Status.Code.UNIMPLEMENTED));
    }

    // ------------------------------------------------------------------ 等级确认

    @Test
    void settlementAckIsWhatMakesTheLevelDisplayable() {
        String uuid = "11111111-1111-1111-1111-111111111111";
        assertFalse(link.profiles().known(uuid), "结算之前不该有任何可显示的等级");

        link.recordSettlement(settlement(uuid, 500, 800, 2), null);
        await("结算确认回落", () -> link.profiles().known(uuid));

        assertTrue(link.profiles().known(uuid), "确认后等级才可显示");
        assertEquals(800, link.profiles().get(uuid).xp());
        assertEquals(2, link.profiles().get(uuid).level());
    }

    @Test
    void unavailableSettlementHidesLevelAndQueuesForReplay() {
        String uuid = "22222222-2222-2222-2222-222222222222";
        link.profiles().put(uuid, 100, 5); // 先有一个已确认的值
        fake.settlementFailure = Status.UNAVAILABLE;

        link.recordSettlement(settlement(uuid, 100, 200, 6), null);
        await("失败回调入队", () -> link.pendingCount() == 1);

        assertFalse(link.profiles().known(uuid), "上报没成功就不能再显示那个值");
        assertEquals(1, link.pendingCount(), "可重试的失败必须入队，否则这笔账永久丢失");
    }

    @Test
    void invalidArgumentIsNotQueued() {
        fake.settlementFailure = Status.INVALID_ARGUMENT;
        link.recordSettlement(settlement("33333333-3333-3333-3333-333333333333", 0, 1, 1), null);
        await("拒绝回调", () -> link.statusPayload().get("sentFail").getAsLong() == 1);
        assertEquals(0, link.pendingCount(), "不可重试的拒绝不该堆在队列里");
    }

    @Test
    void getPlayerNotFoundMeansNoDisplayRatherThanAnError() {
        String uuid = "44444444-4444-4444-4444-444444444444";
        link.profiles().put(uuid, 100, 5);
        fake.getPlayerFailure = Status.NOT_FOUND;

        link.fetchPlayer(uuid, null);
        await("NOT_FOUND 清掉本地值", () -> !link.profiles().known(uuid));

        assertFalse(link.profiles().known(uuid));
        // NOT_FOUND 不是故障：不该污染 lastError，否则会把排查引向「连不上」
        assertEquals("", link.statusPayload().get("lastError").getAsString());
    }

    @Test
    void getPlayerSuccessConfirmsLevel() {
        String uuid = "55555555-5555-5555-5555-555555555555";
        link.fetchPlayer(uuid, null);
        await("档案取回", () -> link.profiles().known(uuid));
        assertTrue(link.profiles().known(uuid));
        assertEquals(1234, link.profiles().get(uuid).xp());
        assertEquals(3, link.profiles().get(uuid).level());
    }

    // ------------------------------------------------------------------ 重放

    @Test
    void queuedRequestIsReplayedAndConfirmsLevel() {
        String uuid = "66666666-6666-6666-6666-666666666666";
        fake.settlementFailure = Status.UNAVAILABLE;
        link.recordSettlement(settlement(uuid, 500, 900, 4), null);
        await("首次失败入队", () -> link.pendingCount() == 1);

        fake.settlementFailure = null;
        link.tick(); // 重放队头
        await("重放出队", () -> link.pendingCount() == 0);

        assertEquals(0, link.pendingCount(), "重放成功后队列必须出队");
        assertTrue(link.profiles().known(uuid), "重放确认后等级恢复显示");
        assertEquals(4, link.profiles().get(uuid).level());
    }

    @Test
    void outboxSurvivesRestart() {
        String uuid = "77777777-7777-7777-7777-777777777777";
        fake.settlementFailure = Status.UNAVAILABLE;
        link.recordSettlement(settlement(uuid, 500, 900, 4), null);
        await("首次失败入队", () -> link.pendingCount() == 1);
        link.stop();

        DataLink restarted = newLink("127.0.0.1:" + server.getPort());
        restarted.start();
        try {
            assertEquals(1, restarted.pendingCount(), "模组重启不能丢掉还没上报的结算");
            fake.settlementFailure = null;
            restarted.tick();
            await("重放出队", () -> restarted.pendingCount() == 0);
            assertEquals(0, restarted.pendingCount());
            assertTrue(restarted.profiles().known(uuid));
        } finally {
            restarted.stop();
        }
    }

    @Test
    void outboxDropsOldestOverCap() {
        DataOutbox box = new DataOutbox(dir.resolve("cap.json"), 2);
        box.load();
        box.add(new DataOutbox.Entry("RecordEvent", new byte[] {1}));
        box.add(new DataOutbox.Entry("RecordEvent", new byte[] {2}));
        box.add(new DataOutbox.Entry("RecordEvent", new byte[] {3}));
        assertEquals(2, box.size());
        assertEquals(1, box.dropped());
        assertEquals(2, box.peek().payload()[0], "丢的是最旧的一条");
    }

    @Test
    void outboxSurvivesCorruptPayloadWithoutLosingTheRest() throws IOException {
        Path file = dir.resolve("corrupt.json");
        Files.writeString(
                file,
                "{\"pending\":[{\"method\":\"RecordEvent\",\"payload\":\"!!!\"},"
                        + "{\"method\":\"RecordEvent\",\"payload\":\"AQ==\"}]}");
        DataOutbox box = new DataOutbox(file, 8);
        box.load();
        assertEquals(1, box.size(), "坏一条跳过，不阻塞其余");
    }

    // ------------------------------------------------------------------ 事件流与对局标识

    @Test
    void eventFeedIsRecordedEvenWhenReportingIsDown() {
        fake.eventFailsUnavailable = true;
        link.recordEvent(EventKinds.CHARACTER_KILL, "A 击杀了 B", b -> b.setActorUuid("a"));
        await("事件失败入队", () -> link.pendingCount() == 1);
        assertEquals(1, link.recentEvents().size(), "面板可见性不该依赖数据服务的连通性");
        assertEquals("A 击杀了 B", link.recentEvents().get(0).get("text").getAsString());
        assertEquals(1, link.pendingCount());
    }

    @Test
    void eventFeedIsCapped() {
        for (int i = 0; i < 20; i++) {
            link.recordEvent(EventKinds.PHASE_ADVANCED, "p" + i);
        }
        assertEquals(8, link.recentEvents().size(), "上限来自构造参数（recentCap）");
        assertEquals("p19", link.recentEvents().get(7).get("text").getAsString());
    }

    @Test
    void newMatchRotatesTheIdAndClearsTheFeed() {
        String first = link.matchId();
        link.recordEvent(EventKinds.GAME_OVER, "x");
        link.newMatch();
        assertNotNull(link.matchId());
        assertFalse(first.equals(link.matchId()));
        link.clearRecentEvents();
        assertEquals(0, link.recentEvents().size());
    }

    // ------------------------------------------------------------------ 关闭

    @Test
    void disabledLinkReportsDisabledAndNeverSends() {
        DataLink off = new DataLink(dir, false, "127.0.0.1:1", "off", 500, 4, 4, Runnable::run);
        off.start();
        String uuid = "88888888-8888-8888-8888-888888888888";
        off.recordSettlement(settlement(uuid, 0, 1, 1), null);
        assertEquals(DataLink.State.DISABLED, off.state());
        assertEquals(0, off.pendingCount(), "关闭 = 完全不上报，也不该攒队列");
        assertFalse(off.profiles().known(uuid));
    }

    @Test
    void unreachableTargetYieldsDownWithoutThrowing() {
        DataLink dead = new DataLink(dir, true, "127.0.0.1:1", "dead", 300, 4, 4, Runnable::run);
        dead.start();
        try {
            assertNull(dead.profiles().get("nobody"));
            dead.tick(); // 不该抛
            assertFalse(dead.statusPayload().get("enabled").getAsBoolean() == false);
        } finally {
            dead.stop();
        }
    }
}
