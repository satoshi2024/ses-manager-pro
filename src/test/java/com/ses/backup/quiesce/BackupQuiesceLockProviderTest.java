package com.ses.backup.quiesce;

import net.javacrumbs.shedlock.core.ClockProvider;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RB-QUIESCE BWQ-006（design.md §6.6・D-P3-4）: ShedLock の lock provider の包みは、静止中は委譲せずに解除まで待ち、開くと
 * 委譲し、unlock の後で scheduler の permit を返す。SHUTDOWN・割込みでは待たずに空を返す。
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class BackupQuiesceLockProviderTest {

    /** 呼ばれた lock の設定を覚え、lock を返す（{@code grant=false} なら空）。 */
    static final class FakeProvider implements LockProvider {
        final List<LockConfiguration> calls = new CopyOnWriteArrayList<>();
        final AtomicInteger unlocks = new AtomicInteger();
        volatile boolean grant = true;
        volatile BackupWriteGate observedGate;
        volatile boolean heldPermitWhileLocking;

        @Override
        public Optional<SimpleLock> lock(LockConfiguration configuration) {
            calls.add(configuration);
            if (observedGate != null) {
                heldPermitWhileLocking = observedGate.currentThreadHoldsPermit();
            }
            return grant ? Optional.of(unlocks::incrementAndGet) : Optional.empty();
        }
    }

    private final BackupWriteGate gate = BackupQuiesceTestSupport.openGate(false, () -> 0L, Clock.systemUTC());
    private final FakeProvider delegate = new FakeProvider();

    private BackupQuiesceLockProvider provider(Duration maxWait) {
        delegate.observedGate = gate;
        return new BackupQuiesceLockProvider(delegate, () -> gate, () -> null, maxWait);
    }

    private static LockConfiguration config(String name) {
        return new LockConfiguration(ClockProvider.now(), name, Duration.ofMinutes(30), Duration.ZERO);
    }

    @Test
    void 開いている間はschedulerのpermitを取って委譲しunlockの後で返す() {
        BackupQuiesceLockProvider provider = provider(Duration.ofSeconds(5));

        Optional<SimpleLock> lock = provider.lock(config("job-a"));

        assertThat(lock).isPresent();
        assertThat(delegate.heldPermitWhileLocking).as("ShedLock の表へ書く間は permit を持つ").isTrue();
        assertThat(gate.permitCount()).isEqualTo(1);
        assertThat(gate.oldestPermitAgeNanos(BackupWritePermit.Kind.SCHEDULER)).isPresent();
        lock.get().unlock();
        assertThat(delegate.unlocks).hasValue(1);
        assertThat(gate.permitCount()).isZero();
        assertThat(gate.currentThreadHoldsPermit()).isFalse();
    }

    @Test
    void 委譲が空ならpermitを返す() {
        delegate.grant = false;

        assertThat(provider(Duration.ofSeconds(5)).lock(config("job-b"))).isEmpty();
        assertThat(gate.permitCount()).isZero();
    }

    @Test
    void 閉じている間は委譲せず待ち開くと開始時刻を取り直して委譲する() throws Exception {
        BackupQuiesceLockProvider provider = provider(Duration.ofSeconds(20));
        BackupQuiesceTestSupport.close(gate, 1, Duration.ofMinutes(5));
        LockConfiguration original = new LockConfiguration(Instant.now().minusSeconds(120), "job-c",
                Duration.ofMinutes(30), Duration.ZERO);

        CompletableFuture<Optional<SimpleLock>> waiting = CompletableFuture.supplyAsync(() -> provider.lock(original));
        Thread.sleep(400);
        assertThat(waiting).as("静止中は開始を待つ").isNotDone();
        assertThat(delegate.calls).as("待っている間は ShedLock の表へ書かない").isEmpty();

        Instant reopenedAt = Instant.now().minusMillis(5);
        BackupQuiesceTestSupport.reopen(gate, 1);
        Optional<SimpleLock> lock = waiting.get(10, TimeUnit.SECONDS);

        assertThat(lock).isPresent();
        assertThat(delegate.calls).singleElement().satisfies(called -> {
            assertThat(called.getName()).isEqualTo("job-c");
            assertThat(called.getLockAtMostUntil()).as("lockAtMostFor は待ちの後から数える")
                    .isAfter(reopenedAt.plus(Duration.ofMinutes(29)));
        });
        assertThat(gate.permitCount()).isEqualTo(1);
        lock.get().unlock();
        assertThat(gate.permitCount()).isZero();
    }

    @Test
    void permitを持つthreadは閉じていても待たずに委譲する() {
        BackupQuiesceLockProvider provider = provider(Duration.ofSeconds(20));
        BackupWritePermit outer = gate.tryAcquire(BackupWritePermit.Kind.ASYNC, "outer");
        BackupQuiesceTestSupport.beginClosing(gate, 1);
        try {
            Optional<SimpleLock> lock = provider.lock(config("job-d"));
            assertThat(lock).isPresent();
            lock.get().unlock();
        } finally {
            outer.close();
        }
        assertThat(delegate.calls).hasSize(1);
    }

    @Test
    void SHUTDOWNでは待たずに委譲せず空を返す() {
        BackupQuiesceLockProvider provider = provider(Duration.ofMinutes(60));
        BackupQuiesceTestSupport.close(gate, 1, Duration.ofMinutes(5));
        gate.shutdown();

        long start = System.nanoTime();
        assertThat(provider.lock(config("job-e"))).isEmpty();

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        assertThat(delegate.calls).isEmpty();
        assertThat(gate.permitCount()).isZero();
    }

    @Test
    void 割り込まれたthreadは待たずに空を返し割込みの印を戻す() throws Exception {
        BackupQuiesceLockProvider provider = provider(Duration.ofMinutes(60));
        BackupQuiesceTestSupport.close(gate, 1, Duration.ofMinutes(5));
        AtomicBoolean interruptedAfter = new AtomicBoolean();
        AtomicBoolean empty = new AtomicBoolean();

        Thread waiter = new Thread(() -> {
            empty.set(provider.lock(config("job-f")).isEmpty());
            interruptedAfter.set(Thread.currentThread().isInterrupted());
        });
        waiter.start();
        Thread.sleep(300);
        long start = System.nanoTime();
        waiter.interrupt();
        waiter.join(5_000);

        assertThat(waiter.isAlive()).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        assertThat(empty).isTrue();
        assertThat(interruptedAfter).as("割込みの印を戻す").isTrue();
        assertThat(delegate.calls).isEmpty();

        Thread.currentThread().interrupt();
        try {
            assertThat(provider.lock(config("job-g"))).as("待つ前に割り込まれていても空").isEmpty();
        } finally {
            assertThat(Thread.interrupted()).isTrue();
        }
    }

    @Test
    void 最長の保持時間を待っても開かなければ空を返す() {
        // 待ちの上限は gate の単調時計で数える（この試験だけ時計を進める。20 秒より短いので FENCED にはならない）
        BackupWriteGate moving = BackupQuiesceTestSupport.openGate(false, System::nanoTime, Clock.systemUTC());
        BackupQuiesceLockProvider provider = new BackupQuiesceLockProvider(delegate, () -> moving, () -> null,
                Duration.ofMillis(400));
        BackupQuiesceTestSupport.close(moving, 1, Duration.ofMinutes(5));

        long start = System.nanoTime();
        assertThat(provider.lock(config("job-h"))).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(delegate.calls).isEmpty();
        assertThat(moving.permitCount()).isZero();
    }

    @Test
    void INERTのgateとgateの無いcontextではそのまま委譲する() {
        BackupWriteGate inert = new BackupWriteGate(BackupWriteGate.Mode.INERT, false, Duration.ofSeconds(20), 10);
        assertThat(new BackupQuiesceLockProvider(delegate, () -> inert, () -> null, Duration.ofSeconds(1))
                .lock(config("job-i"))).isPresent();
        assertThat(inert.permitCount()).isZero();
        assertThat(new BackupQuiesceLockProvider(delegate, () -> null, () -> null, Duration.ofSeconds(1))
                .lock(config("job-j"))).isPresent();
        assertThat(delegate.calls).hasSize(2);
    }
}
