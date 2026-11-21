package com.ses.backup.quiesce;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RB-QUIESCE BWQ-002: {@link BackupWriteGate} の状態遷移・I-4 の数え方・I-3 の開け直しの規則・手元の期限・self-fence・
 * 入れ子の permit・保留一覧と解除後の書込み・metric を、Spring と DB なしで確かめる（design.md §6.1・§6.2・§6.5・§6.7）。
 */
class BackupWriteGateTest {

    private static final Duration FENCE_AFTER = Duration.ofSeconds(20);
    private static final long SECOND = 1_000_000_000L;

    private final AtomicLong now = new AtomicLong(1_000 * SECOND);

    private BackupWriteGate gate(BackupWriteGate.Mode mode, boolean prod) {
        return new BackupWriteGate(mode, prod, FENCE_AFTER, 10_000, now::get,
                Clock.fixed(Instant.parse("2026-11-21T00:00:00Z"), ZoneOffset.UTC));
    }

    private void advance(Duration duration) {
        now.addAndGet(duration.toNanos());
    }

    // ---------------------------------------------------------------- 状態遷移の表

    static Stream<Arguments> transitions() {
        return Stream.of(
                path("INERT（prod 以外の enabled=false）は常に OPEN で停止でも変わらない", BackupWriteGate.State.OPEN, t -> {
                    BackupWriteGate gate = t.gate(BackupWriteGate.Mode.INERT, false);
                    gate.shutdown();
                    assertThat(gate.beginClosing(1)).isFalse();
                    return gate;
                }),
                path("prod の UNARMED は閉じた扱い", BackupWriteGate.State.UNARMED, t -> {
                    BackupWriteGate gate = t.gate(BackupWriteGate.Mode.ARMED, true);
                    assertThat(gate.allowsNewPermits()).isFalse();
                    return gate;
                }),
                path("prod 以外の UNARMED は書ける", BackupWriteGate.State.UNARMED, t -> {
                    BackupWriteGate gate = t.gate(BackupWriteGate.Mode.ARMED, false);
                    assertThat(gate.allowsNewPermits()).isTrue();
                    return gate;
                }),
                path("UNARMED → OPEN（登録の後、有効な要求なし）", BackupWriteGate.State.OPEN, t -> {
                    BackupWriteGate gate = t.gate(BackupWriteGate.Mode.ARMED, true);
                    gate.armOpen();
                    return gate;
                }),
                path("UNARMED → CLOSING（登録の後、有効な ONLINE の要求あり）", BackupWriteGate.State.CLOSING, t -> {
                    BackupWriteGate gate = t.gate(BackupWriteGate.Mode.ARMED, true);
                    assertThat(gate.beginClosing(7)).isTrue();
                    return gate;
                }),
                path("OPEN → CLOSING", BackupWriteGate.State.CLOSING, t -> t.closing(7)),
                path("CLOSING → CLOSED（QUIESCED を試みた）", BackupWriteGate.State.CLOSED, t -> t.closed(7, 60)),
                path("CLOSING → OPEN（排水の上限。QUIESCED を試していない）", BackupWriteGate.State.OPEN, t -> {
                    BackupWriteGate gate = t.closing(7);
                    assertThat(gate.reopen(7)).isTrue();
                    return gate;
                }),
                path("CLOSED → OPEN（I-3 (a) 非 QUIESCED の確定）", BackupWriteGate.State.OPEN, t -> {
                    BackupWriteGate gate = t.closed(7, 60);
                    gate.confirmNonQuiesced(7);
                    assertThat(gate.reopen(7)).isTrue();
                    return gate;
                }),
                path("CLOSED → OPEN（I-3 (b) 手元の期限。参照する側が遅延評価する）", BackupWriteGate.State.OPEN, t -> {
                    BackupWriteGate gate = t.closed(7, 60);
                    t.advance(Duration.ofSeconds(19));
                    gate.recordHeartbeatSuccess();
                    t.advance(Duration.ofSeconds(19));
                    gate.recordHeartbeatSuccess();
                    t.advance(Duration.ofSeconds(19));
                    gate.recordHeartbeatSuccess();
                    t.advance(Duration.ofSeconds(4));
                    gate.recordHeartbeatSuccess();
                    return gate;
                }),
                path("CLOSED のまま（確定も期限も無ければ開けない）", BackupWriteGate.State.CLOSED, t -> {
                    BackupWriteGate gate = t.closed(7, 60);
                    assertThat(gate.reopen(7)).isFalse();
                    return gate;
                }),
                path("OPEN → FENCED（heartbeat を fence-after 書けない）", BackupWriteGate.State.FENCED, t -> {
                    BackupWriteGate gate = t.open();
                    t.advance(FENCE_AFTER.plusMillis(1));
                    return gate;
                }),
                path("CLOSING → FENCED", BackupWriteGate.State.FENCED, t -> {
                    BackupWriteGate gate = t.closing(7);
                    t.advance(FENCE_AFTER.plusMillis(1));
                    return gate;
                }),
                path("CLOSED で手元の期限に達しても FENCED が優先する", BackupWriteGate.State.FENCED, t -> {
                    BackupWriteGate gate = t.closed(7, 10);
                    t.advance(FENCE_AFTER.plusMillis(1));
                    assertThat(gate.localDeadlineReached()).isTrue();
                    return gate;
                }),
                path("FENCED → OPEN（heartbeat の回復）", BackupWriteGate.State.OPEN, t -> {
                    BackupWriteGate gate = t.open();
                    t.advance(FENCE_AFTER.plusMillis(1));
                    assertThat(gate.recordHeartbeatSuccess()).as("FENCED から戻った").isTrue();
                    return gate;
                }),
                path("FENCED → CLOSED（回復すると有効な要求と ack の状態へ戻る）", BackupWriteGate.State.CLOSED, t -> {
                    BackupWriteGate gate = t.closed(7, 120);
                    t.advance(FENCE_AFTER.plusMillis(1));
                    assertThat(gate.state()).isEqualTo(BackupWriteGate.State.FENCED);
                    gate.recordHeartbeatSuccess();
                    return gate;
                }),
                path("OPEN → CLOSED（APP_STOPPED の保持を読んだ。期限なし）", BackupWriteGate.State.CLOSED, t -> {
                    BackupWriteGate gate = t.open();
                    assertThat(gate.closeForStoppedHold(9)).isTrue();
                    t.advance(Duration.ofHours(1));
                    gate.recordHeartbeatSuccess();
                    return gate;
                }),
                path("任意 → SHUTDOWN（戻らない）", BackupWriteGate.State.SHUTDOWN, t -> {
                    BackupWriteGate gate = t.closed(7, 60);
                    gate.shutdown();
                    gate.confirmNonQuiesced(7);
                    assertThat(gate.reopen(7)).isFalse();
                    assertThat(gate.beginClosing(8)).isFalse();
                    gate.armOpen();
                    return gate;
                }),
                path("DISABLED（停止口）は閉じず、停止で SHUTDOWN", BackupWriteGate.State.SHUTDOWN, t -> {
                    BackupWriteGate gate = t.gate(BackupWriteGate.Mode.DISABLED, true);
                    assertThat(gate.state()).isEqualTo(BackupWriteGate.State.DISABLED);
                    assertThat(gate.allowsNewPermits()).isTrue();
                    assertThat(gate.beginClosing(1)).isFalse();
                    t.advance(FENCE_AFTER.multipliedBy(3));
                    assertThat(gate.state()).as("DISABLED は fence しない").isEqualTo(BackupWriteGate.State.DISABLED);
                    gate.shutdown();
                    return gate;
                }));
    }

    private static Arguments path(String name, BackupWriteGate.State expected, Function<BackupWriteGateTest, BackupWriteGate> run) {
        return Arguments.of(name, expected, run);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("transitions")
    void 状態遷移の全経路(String name, BackupWriteGate.State expected, Function<BackupWriteGateTest, BackupWriteGate> run) {
        BackupWriteGate gate = run.apply(new BackupWriteGateTest());
        assertThat(gate.state()).as(name).isEqualTo(expected);
        boolean writable = expected == BackupWriteGate.State.OPEN || expected == BackupWriteGate.State.DISABLED;
        if (expected != BackupWriteGate.State.UNARMED) {
            assertThat(gate.allowsNewPermits()).as("permit の無い書込みを許すか: " + name).isEqualTo(writable);
        }
    }

    // ---------------------------------------------------------------- I-4（競合）

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void 百threadでpermitと書込み可能な接続を取りながら閉じても排水の確定の後に新しいpermitが出ない() throws Exception {
        BackupWriteGate gate = new BackupWriteGate(BackupWriteGate.Mode.ARMED, true, Duration.ofDays(1), 100);
        gate.armOpen();
        int workers = 100;
        int rounds = 10_000;
        AtomicBoolean drained = new AtomicBoolean();
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger violations = new AtomicInteger();
        AtomicLong granted = new AtomicLong();
        CountDownLatch started = new CountDownLatch(workers);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            boolean connection = i % 2 == 0;
            Thread thread = new Thread(() -> {
                started.countDown();
                while (!stop.get()) {
                    // 短い試行の連続の後に少し眠る（100 thread が CPU を占めて閉じる側の thread を飢えさせないため）
                    for (int attempt = 0; attempt < 50 && !stop.get(); attempt++) {
                        if (connection) {
                            if (gate.tryRegisterWritableConnection()) {
                                granted.incrementAndGet();
                                if (drained.get()) {
                                    violations.incrementAndGet();
                                }
                                Thread.onSpinWait();
                                if (drained.get()) {
                                    violations.incrementAndGet();
                                }
                                gate.releaseWritableConnection();
                            }
                        } else {
                            BackupWritePermit permit = gate.tryAcquire(BackupWritePermit.Kind.HTTP, "worker");
                            if (permit != null) {
                                granted.incrementAndGet();
                                if (drained.get()) {
                                    violations.incrementAndGet();
                                }
                                Thread.onSpinWait();
                                if (drained.get()) {
                                    violations.incrementAndGet();
                                }
                                permit.close();
                            }
                        }
                    }
                    LockSupport.parkNanos(200_000);
                }
            }, "gate-race-" + i);
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }
        started.await();
        // 試験が終わらない場合でも worker の thread を止めるため、閉じる側の待ちに上限を置く
        long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        try {
            for (int round = 1; round <= rounds; round++) {
                assertThat(gate.beginClosing(round)).isTrue();
                while (!gate.isDrained()) {
                    Thread.onSpinWait();
                    if (System.nanoTime() - giveUp > 0) {
                        throw new AssertionError("排水が終わらない（round " + round + "、" + gate + "）");
                    }
                }
                drained.set(true);
                // 排水を確定した後は、閉じている間に新しい permit・書込み可能な接続が1つも出ない
                for (int i = 0; i < 20; i++) {
                    Thread.onSpinWait();
                }
                assertThat(gate.tryAcquire(BackupWritePermit.Kind.HTTP, "closer")).isNull();
                drained.set(false);
                assertThat(gate.reopen(round)).isTrue();
                // 開いている間に worker が permit を取れる時間を少し置く
                for (int i = 0; i < 200; i++) {
                    Thread.onSpinWait();
                }
            }
        } finally {
            stop.set(true);
            for (Thread thread : threads) {
                thread.join(10_000);
            }
        }
        assertThat(violations.get()).as("排水の確定の後に有効だった permit・接続").isZero();
        assertThat(granted.get()).as("開いている間は permit が出る（試験の非空虚性）").isPositive();
        assertThat(gate.permitCount()).isZero();
        assertThat(gate.writableConnectionCount()).isZero();
    }

    @Test
    void 閉じている間はpermitの無いthreadの接続を書込み可能に数えずpermitを持つthreadは数える() {
        BackupWriteGate gate = closing(3);
        assertThat(gate.tryRegisterWritableConnection()).isFalse();
        assertThat(gate.writableConnectionCount()).isZero();
        assertThat(gate.tryAcquire(BackupWritePermit.Kind.HTTP, "closed")).isNull();
        assertThat(gate.permitCount()).as("取れなかった permit の数は戻す").isZero();

        BackupWriteGate open = open();
        BackupWritePermit permit = open.tryAcquire(BackupWritePermit.Kind.HTTP, "POST /api/x");
        assertThat(open.beginClosing(4)).isTrue();
        assertThat(open.tryRegisterWritableConnection()).as("permit を持つ thread は閉じていても書ける").isTrue();
        assertThat(open.isDrained()).isFalse();
        open.releaseWritableConnection();
        permit.close();
        assertThat(open.isDrained()).isTrue();
    }

    // ---------------------------------------------------------------- I-3・手元の期限

    @Test
    void QUIESCEDを試した要求は確定か手元の期限でしか開かない() {
        BackupWriteGate gate = closed(5, 30);
        assertThat(gate.quiescedAttempted(5)).isTrue();
        assertThat(gate.reopen(5)).as("確定も期限も無い").isFalse();
        assertThat(gate.state()).isEqualTo(BackupWriteGate.State.CLOSED);
        assertThat(gate.reopen(6)).as("別の要求の番号では開かない").isFalse();

        advance(Duration.ofSeconds(15));
        gate.recordHeartbeatSuccess();
        assertThat(gate.reopen(5)).isFalse();
        advance(Duration.ofSeconds(15));
        gate.recordHeartbeatSuccess();
        assertThat(gate.state()).as("手元の期限で参照する側は開いたと見る").isEqualTo(BackupWriteGate.State.OPEN);
        assertThat(gate.reopen(5)).isTrue();
        assertThat(gate.phase()).isEqualTo(BackupWriteGate.State.OPEN);

        BackupWriteGate confirmed = closed(8, 30);
        confirmed.confirmNonQuiesced(8);
        assertThat(confirmed.reopen(8)).isTrue();
    }

    @Test
    void 手元の期限は縮まず期限に達した後は延ばせない() {
        BackupWriteGate gate = closing(5);
        long base = now.get();
        assertThat(gate.extendLocalDeadline(base + 60 * SECOND)).isTrue();
        assertThat(gate.extendLocalDeadline(base + 10 * SECOND)).as("短い値は縮めない（成功扱い）").isTrue();
        assertThat(gate.remainingLocalDeadlineNanos().getAsLong()).isEqualTo(60 * SECOND);
        assertThat(gate.markQuiescedAttempt(5)).isTrue();
        assertThat(gate.extendLocalDeadline(base + 90 * SECOND)).isTrue();
        assertThat(gate.remainingLocalDeadlineNanos().getAsLong()).isEqualTo(90 * SECOND);

        now.set(base + 90 * SECOND);
        gate.recordHeartbeatSuccess();
        assertThat(gate.state()).isEqualTo(BackupWriteGate.State.OPEN);
        assertThat(gate.extendLocalDeadline(base + 200 * SECOND))
                .as("参照する側が開いたと見た後に閉じ直さない").isFalse();
        assertThat(gate.state()).isEqualTo(BackupWriteGate.State.OPEN);
        assertThat(gate.remainingLocalDeadlineNanos()).isEmpty();
    }

    @Test
    void 手元の期限を決めずにQUIESCEDを試みることはできない() {
        BackupWriteGate gate = closing(5);
        assertThat(gate.markQuiescedAttempt(5)).isFalse();
        assertThat(gate.phase()).isEqualTo(BackupWriteGate.State.CLOSING);
        assertThat(gate.extendLocalDeadline(now.get() + SECOND)).isTrue();
        assertThat(gate.markQuiescedAttempt(6)).as("別の要求").isFalse();
        assertThat(gate.markQuiescedAttempt(5)).isTrue();
    }

    // ---------------------------------------------------------------- permit の入れ子・引継ぎ・持ち主

    @Test
    void 入れ子のpermitは数えず閉じていても出て外側を返すまで排水は終わらない() {
        BackupWriteGate gate = open();
        BackupWritePermit outer = gate.tryAcquire(BackupWritePermit.Kind.SCHEDULER, "job");
        assertThat(gate.beginClosing(2)).isTrue();
        BackupWritePermit nested = gate.tryAcquire(BackupWritePermit.Kind.HTTP, "nested");
        assertThat(nested).isNotNull();
        assertThat(nested.isCounted()).isFalse();
        assertThat(gate.permitCount()).isEqualTo(1);
        nested.close();
        nested.close();
        assertThat(gate.currentThreadHoldsPermit()).isTrue();
        assertThat(gate.isDrained()).isFalse();
        outer.close();
        assertThat(gate.currentThreadHoldsPermit()).isFalse();
        assertThat(gate.isDrained()).isTrue();
    }

    @Test
    void 投入時に数えたpermitは実行するthreadが引き継ぎ閉じた後の投入はpermitを持つthreadだけが数えられる() throws Exception {
        BackupWriteGate gate = open();
        BackupWritePermit detached = gate.tryAcquireDetached(BackupWritePermit.Kind.ASYNC, "task");
        assertThat(gate.beginClosing(2)).isTrue();
        assertThat(gate.tryAcquireDetached(BackupWritePermit.Kind.ASYNC, "late")).as("permit の無い thread の閉じた後の投入")
                .isNull();
        AtomicBoolean heldOnWorker = new AtomicBoolean();
        AtomicBoolean childCounted = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            detached.attachToCurrentThread();
            heldOnWorker.set(gate.currentThreadHoldsPermit());
            BackupWritePermit child = gate.tryAcquireDetached(BackupWritePermit.Kind.ASYNC, "child");
            childCounted.set(child != null && child.isCounted());
            if (child != null) {
                child.close();
            }
            detached.close();
        });
        worker.start();
        worker.join();
        assertThat(heldOnWorker.get()).as("実行する thread が引き継ぐ").isTrue();
        assertThat(childCounted.get()).as("permit を持つ thread の投入は閉じていても数える").isTrue();
        assertThat(gate.isDrained()).isTrue();
        assertThatThrownBy(detached::attachToCurrentThread).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 呼んだthreadのpermitを全て返せる() {
        BackupWriteGate gate = gate(BackupWriteGate.Mode.ARMED, true);
        BackupWritePermit startup = gate.acquireStartupPermitWhileUnarmed("main");
        assertThat(startup.kind()).isEqualTo(BackupWritePermit.Kind.STARTUP);
        gate.armOpen();
        gate.tryAcquire(BackupWritePermit.Kind.HTTP, "nested");
        assertThat(gate.releaseCurrentThreadPermits()).isEqualTo(2);
        assertThat(gate.permitCount()).isZero();
        assertThat(gate.currentThreadHoldsPermit()).isFalse();
        assertThatThrownBy(() -> gate.acquireStartupPermitWhileUnarmed("again")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 最も古いpermitの保持時間と持ち主をkindごとに出す() {
        BackupWriteGate gate = open();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new BackupQuiesceMetrics(gate).bindTo(registry);
        BackupWritePermit job = gate.tryAcquireDetached(BackupWritePermit.Kind.SCHEDULER, "OutboxWorker#poll");
        advance(Duration.ofSeconds(42));
        gate.recordHeartbeatSuccess();

        assertThat(registry.get(BackupQuiesceMetrics.OLDEST_PERMIT).tag("kind", "scheduler").gauge().value())
                .isEqualTo(42.0);
        assertThat(registry.get(BackupQuiesceMetrics.OLDEST_PERMIT).tag("kind", "http").gauge().value()).isZero();
        assertThat(gate.describePermitHolders()).singleElement().asString()
                .contains("scheduler:OutboxWorker#poll").contains("42000ms");
        assertThat(registry.get(BackupQuiesceMetrics.GATE_STATE).gauge().value()).isEqualTo(1.0);
        gate.beginClosing(3);
        assertThat(registry.get(BackupQuiesceMetrics.GATE_STATE).gauge().value()).isEqualTo(2.0);
        job.close();
        assertThat(registry.get(BackupQuiesceMetrics.OLDEST_PERMIT).tag("kind", "scheduler").gauge().value()).isZero();

        BackupQuiesceMetrics metrics = new BackupQuiesceMetrics(gate);
        metrics.requestResult("CHECKPOINT", "acked");
        SimpleMeterRegistry later = new SimpleMeterRegistry();
        metrics.bindTo(later);
        metrics.requestResult("CHECKPOINT", "acked");
        metrics.requestResult("weekly", "unexpected");
        assertThat(later.get(BackupQuiesceMetrics.REQUESTS).tag("job_kind", "checkpoint").tag("result", "acked")
                .counter().count()).isEqualTo(1.0);
        assertThat(later.get(BackupQuiesceMetrics.REQUESTS).tag("job_kind", "other").tag("result", "other")
                .counter().count()).as("label は有限の集合").isEqualTo(1.0);
    }

    // ---------------------------------------------------------------- 保留一覧・解除後の書込み・開いた時の callback

    @Test
    void 閉じた後の保留一覧と解除後の書込みは開いた時に投入される() {
        BackupWriteGate gate = closing(4);
        List<String> executed = new ArrayList<>();
        AtomicInteger opened = new AtomicInteger();
        gate.setDispatcher(task -> {
            task.run();
            return true;
        });
        gate.addOpenListener(opened::incrementAndGet);
        gate.addOpenListener(() -> gate.deferredWrites().flush(gate));
        gate.deferUntilOpen(() -> executed.add("async-1"));
        gate.deferUntilOpen(() -> executed.add("async-2"));
        gate.deferredWrites().add(write("logout-1", executed));
        assertThat(executed).as("閉じている間は投入しない").isEmpty();
        assertThat(gate.pendingTaskCount()).isEqualTo(2);

        assertThat(gate.reopen(4)).isTrue();
        assertThat(executed).containsExactly("async-1", "async-2", "logout-1");
        assertThat(opened.get()).isEqualTo(1);
        assertThat(gate.pendingTaskCount()).isZero();
        assertThat(gate.deferredWrites().pending()).isZero();

        gate.deferUntilOpen(() -> executed.add("async-3"));
        assertThat(executed).as("開いていれば直ちに投入する").endsWith("async-3");
    }

    @Test
    void 投入を受け付けない間は保留を残し次の機会に投入し停止では捨てる() {
        BackupWriteGate gate = closing(4);
        List<String> executed = new ArrayList<>();
        AtomicBoolean accepting = new AtomicBoolean(false);
        gate.setDispatcher(task -> {
            if (!accepting.get()) {
                return false;
            }
            task.run();
            return true;
        });
        gate.deferUntilOpen(() -> executed.add("a"));
        gate.deferUntilOpen(() -> executed.add("b"));
        gate.reopen(4);
        assertThat(gate.pendingTaskCount()).isEqualTo(2);
        accepting.set(true);
        assertThat(gate.dispatchPending()).isEqualTo(2);
        assertThat(executed).containsExactly("a", "b");

        gate.beginClosing(5);
        gate.deferUntilOpen(() -> executed.add("c"));
        gate.shutdown();
        assertThat(gate.pendingTaskCount()).isZero();
        gate.deferUntilOpen(() -> executed.add("d"));
        assertThat(gate.pendingTaskCount()).isZero();
        assertThat(executed).containsExactly("a", "b");
    }

    @Test
    void 書込みを許すまで待ち停止では直ちに戻る() throws Exception {
        BackupWriteGate gate = new BackupWriteGate(BackupWriteGate.Mode.ARMED, true, Duration.ofDays(1), 10);
        gate.armOpen();
        assertThat(gate.awaitWritable(Duration.ofMillis(10))).isTrue();
        gate.beginClosing(1);
        Thread opener = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            gate.reopen(1);
        });
        opener.start();
        assertThat(gate.awaitWritable(Duration.ofSeconds(10))).isTrue();
        opener.join();
        gate.beginClosing(2);
        assertThat(gate.awaitWritable(Duration.ofMillis(50))).isFalse();
        gate.shutdown();
        long start = System.nanoTime();
        assertThat(gate.awaitWritable(Duration.ofSeconds(10))).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
    }

    // ---------------------------------------------------------------- 入れ子の permit の fail-closed（2a review P3-1）

    @Test
    void 外側のpermitを先に返すと入れ子のpermitだけでは持っているとみなさない() {
        BackupWriteGate gate = open();
        BackupWritePermit outer = gate.tryAcquire(BackupWritePermit.Kind.HTTP, "outer");
        BackupWritePermit nested = gate.tryAcquire(BackupWritePermit.Kind.ASYNC, "nested");
        assertThat(nested.isCounted()).isFalse();

        outer.close();

        assertThat(gate.currentThreadHoldsPermit()).isFalse();
        assertThat(gate.permitCount()).isZero();
        assertThat(gate.beginClosing(1)).isTrue();
        assertThat(gate.tryRegisterWritableConnection()).as("閉じた後に書込み可能な接続を渡さない").isFalse();
        assertThat(gate.tryAcquire(BackupWritePermit.Kind.HTTP, "again")).as("新しい permit も出ない").isNull();
        nested.close();
        assertThat(gate.permitCount()).isZero();
        assertThat(gate.writableConnectionCount()).isZero();
    }

    @Test
    void 返し損ねた入れ子のpermitはpoolのthreadに持越されず次の要求はI4の順で数える() {
        BackupWriteGate gate = open();
        BackupWritePermit outer = gate.tryAcquire(BackupWritePermit.Kind.HTTP, "request-1");
        gate.tryAcquire(BackupWritePermit.Kind.SCHEDULER, "leaked");
        outer.close();

        // 同じ thread の次の要求（pool の thread の再利用）
        BackupWritePermit next = gate.tryAcquire(BackupWritePermit.Kind.HTTP, "request-2");
        assertThat(next.isCounted()).as("漏れた入れ子の上の入れ子にならず、数える permit になる").isTrue();
        assertThat(gate.permitCount()).isEqualTo(1);
        next.close();

        assertThat(gate.beginClosing(2)).isTrue();
        assertThat(gate.currentThreadHoldsPermit()).isFalse();
        assertThat(gate.writesBlockedForCurrentThread()).isTrue();
        assertThat(gate.tryRegisterWritableConnection()).isFalse();
    }

    @Test
    void 数えたpermitが残っている間の入れ子は従来どおり待たずに出る() {
        BackupWriteGate gate = open();
        BackupWritePermit outer = gate.tryAcquire(BackupWritePermit.Kind.HTTP, "outer");
        assertThat(gate.beginClosing(3)).isTrue();

        BackupWritePermit nested = gate.tryAcquire(BackupWritePermit.Kind.ASYNC, "nested");
        assertThat(nested).isNotNull();
        nested.close();
        assertThat(gate.currentThreadHoldsPermit()).as("外側は残る").isTrue();
        assertThat(gate.tryRegisterWritableConnection()).isTrue();
        gate.releaseWritableConnection();
        outer.close();
        assertThat(gate.currentThreadHoldsPermit()).isFalse();
    }

    // ---------------------------------------------------------------- helper

    BackupWriteGate open() {
        BackupWriteGate gate = gate(BackupWriteGate.Mode.ARMED, true);
        gate.recordHeartbeatSuccess();
        gate.armOpen();
        return gate;
    }

    BackupWriteGate closing(long requestId) {
        BackupWriteGate gate = open();
        assertThat(gate.beginClosing(requestId)).isTrue();
        return gate;
    }

    /** QUIESCED を試みた（手元の期限を {@code holdSeconds} 秒先にした）CLOSED の gate。 */
    BackupWriteGate closed(long requestId, long holdSeconds) {
        BackupWriteGate gate = closing(requestId);
        assertThat(gate.isDrained()).isTrue();
        assertThat(gate.extendLocalDeadline(now.get() + holdSeconds * SECOND)).isTrue();
        assertThat(gate.markQuiescedAttempt(requestId)).isTrue();
        return gate;
    }

    private static BackupQuiesceDeferredWrites.DeferredWrite write(String name, List<String> sink) {
        return new BackupQuiesceDeferredWrites.DeferredWrite() {
            @Override
            public String summary() {
                return name;
            }

            @Override
            public void write() {
                sink.add(name);
            }
        };
    }
}
