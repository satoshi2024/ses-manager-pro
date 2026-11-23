package com.ses.privacy.production.retention.hold;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Journal と application DB の {@code @@server_uuid} の比較（{@link PrivacyHoldJournal}）の single-flight（A1H-1 delta review
 * P3-N1）。期限が来たとき比較するのは1つの thread だけで、前回の結果がある間は他の thread は待たずに前回の結果を使い、
 * 前回の結果が無い最初の比較だけは比較中の thread の結果を待つ。DB は使わない（索引は {@link InMemoryHoldJournal}、application DB の
 * 照会は待ち合わせのできる supplier）。
 */
class PrivacyHoldJournalInstanceCheckTest {

    private static final String APPLICATION_UUID = "application-server-uuid";
    private static final long TIMEOUT_SECONDS = 10;

    private final PrivacyHoldTestFixture.MutableClock clock = new PrivacyHoldTestFixture.MutableClock(PrivacyHoldTestFixture.T0);
    private final InMemoryHoldJournal index = new InMemoryHoldJournal(clock);
    private final BlockingApplicationUuid application = new BlockingApplicationUuid();
    private final PrivacyHoldJournal journal = new PrivacyHoldJournal(
            () -> new PrivacyJournalStartupVerifier.Result(PrivacyJournalStartupVerifier.Availability.AVAILABLE, List.of()),
            () -> index, index, PrivacyHoldTestFixture.JOURNAL_ID, Duration.ofMinutes(2), Duration.ofSeconds(35),
            application, clock);
    private final ExecutorService executor = Executors.newFixedThreadPool(9);

    @AfterEach
    void tearDown() {
        application.release();
        executor.shutdownNow();
    }

    /**
     * review rb-fe-c P3-2: reconciler の実行ごとに起動時検査の結果を再検査する（保持・保全が閉じていて評価器が呼ばなくても、Journal が
     * 直れば hold の効力の結果が回復する）。再検査の失敗は結果を変えず、比べ直しを止めない。
     */
    @Test
    void reconcilerの実行ごとに起動時検査の結果を再検査し失敗しても続ける() {
        java.util.concurrent.atomic.AtomicReference<PrivacyJournalStartupVerifier.Result> result =
                new java.util.concurrent.atomic.AtomicReference<>(new PrivacyJournalStartupVerifier.Result(
                        PrivacyJournalStartupVerifier.Availability.UNAVAILABLE, List.of("journal.unreachable")));
        PrivacyHoldJournal recovering = new PrivacyHoldJournal(result::get, () -> index, index,
                PrivacyHoldTestFixture.JOURNAL_ID, Duration.ofMinutes(2), Duration.ofSeconds(35), () -> APPLICATION_UUID,
                clock);
        AtomicInteger refreshed = new AtomicInteger();
        recovering.resultRefresher = () -> {
            refreshed.incrementAndGet();
            result.set(new PrivacyJournalStartupVerifier.Result(PrivacyJournalStartupVerifier.Availability.AVAILABLE,
                    List.of()));
        };
        assertThat(recovering.availability()).isNotEqualTo(PrivacyHoldJournal.Availability.AVAILABLE);

        recovering.refreshInstanceCheck();

        assertThat(refreshed).hasValue(1);
        assertThat(recovering.availability()).isEqualTo(PrivacyHoldJournal.Availability.AVAILABLE);
        recovering.resultRefresher = () -> {
            throw new IllegalStateException("journal down");
        };
        org.assertj.core.api.Assertions.assertThatCode(recovering::refreshInstanceCheck).doesNotThrowAnyException();
        assertThat(recovering.availability()).isEqualTo(PrivacyHoldJournal.Availability.AVAILABLE);
    }

    @Test
    void 間隔を過ぎた比較は1つのthreadだけが行い他のthreadは待たずに前回の結果を使う() throws Exception {
        assertThat(journal.availability()).isEqualTo(PrivacyHoldJournal.Availability.AVAILABLE);
        assertThat(application.calls).hasValue(1);

        clock.advance(PrivacyHoldJournal.INSTANCE_CHECK_INTERVAL);
        // 期限の来た比較の照会を止め、その間に Journal が application DB と同じ instance を指すようにする
        application.blockNext(index.serverUuid);
        Future<PrivacyHoldJournal.Availability> checking = executor.submit(journal::availability);
        assertThat(application.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("比較が照会に入った").isTrue();

        List<Future<PrivacyHoldJournal.Availability>> others = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            others.add(executor.submit(journal::availability));
        }
        for (Future<PrivacyHoldJournal.Availability> other : others) {
            assertThat(other.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("比較中の thread を待たず前回の結果を使う")
                    .isEqualTo(PrivacyHoldJournal.Availability.AVAILABLE);
        }
        assertThat(checking.isDone()).as("比較中の thread はまだ照会の中").isFalse();
        assertThat(application.calls).as("比較は1回だけ").hasValue(2);

        application.release();
        assertThat(checking.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(PrivacyHoldJournal.Availability.UNAVAILABLE);
        assertThat(journal.sameInstanceDetected()).isTrue();
        // 比べ終えた結果は全 thread に効き、間隔の間は比べ直さない
        assertThat(journal.availability()).isEqualTo(PrivacyHoldJournal.Availability.UNAVAILABLE);
        assertThat(application.calls).hasValue(2);
        assertThat(application.maxConcurrent).as("同時に比べない").hasValue(1);
    }

    @Test
    void 最初の比較は比較中のthreadの結果を待ち重ねて比べない() throws Exception {
        application.blockNext(APPLICATION_UUID);
        Future<PrivacyHoldJournal.Availability> first = executor.submit(journal::availability);
        assertThat(application.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        List<Future<PrivacyHoldJournal.Availability>> waiting = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            waiting.add(executor.submit(journal::availability));
        }
        awaitQueued(4);
        assertThat(waiting).as("前回の結果が無い間は根拠の無い判定を返さず待つ").noneMatch(Future::isDone);

        application.release();
        assertThat(first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(PrivacyHoldJournal.Availability.AVAILABLE);
        for (Future<PrivacyHoldJournal.Availability> future : waiting) {
            assertThat(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(PrivacyHoldJournal.Availability.AVAILABLE);
        }
        assertThat(application.calls).as("待った thread は比較中の thread の結果を使い、重ねて比べない").hasValue(1);
        assertThat(application.maxConcurrent).hasValue(1);
    }

    @Test
    void reconcilerの比べ直しは比較中のthreadの終了を待ってから比べ直す() throws Exception {
        assertThat(journal.availability()).isEqualTo(PrivacyHoldJournal.Availability.AVAILABLE);
        clock.advance(PrivacyHoldJournal.INSTANCE_CHECK_INTERVAL);
        application.blockNext(APPLICATION_UUID);
        Future<PrivacyHoldJournal.Availability> lazy = executor.submit(journal::availability);
        assertThat(application.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        // 比較中に Journal が application DB と同じ instance を指すようになった。reconciler の比べ直しは待った後に比べ、それを検出する
        index.serverUuid = APPLICATION_UUID;
        Future<?> refresh = executor.submit(journal::refreshInstanceCheck);
        awaitQueued(1);
        assertThat(refresh.isDone()).isFalse();

        application.release();
        assertThat(lazy.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNotNull();
        refresh.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(application.calls).hasValue(3);
        assertThat(application.maxConcurrent).hasValue(1);
        assertThat(journal.sameInstanceDetected()).isTrue();
        assertThat(journal.availability()).isEqualTo(PrivacyHoldJournal.Availability.UNAVAILABLE);
    }

    /** 比較の lock を待つ thread が {@code count} 件になるまで待つ。 */
    private void awaitQueued(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (journal.instanceCheckLock.getQueueLength() < count) {
            assertThat(System.nanoTime()).as("lock を待つ thread が %d 件にならない", count).isLessThan(deadline);
            Thread.sleep(5);
        }
    }

    /** application DB の {@code @@server_uuid}。次の1回だけ、解放されるまで照会の中で止められる。同時に入った数を数える。 */
    private static final class BlockingApplicationUuid implements java.util.function.Supplier<String> {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger maxConcurrent = new AtomicInteger();
        private final AtomicInteger concurrent = new AtomicInteger();
        private final CountDownLatch gate = new CountDownLatch(1);
        final CountDownLatch entered = new CountDownLatch(1);
        private volatile boolean blockNext;
        private volatile String value = APPLICATION_UUID;

        void blockNext(String valueWhileBlocked) {
            this.value = valueWhileBlocked;
            this.blockNext = true;
        }

        void release() {
            gate.countDown();
        }

        @Override
        public String get() {
            calls.incrementAndGet();
            int now = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            try {
                if (blockNext) {
                    blockNext = false;
                    entered.countDown();
                    try {
                        if (!gate.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test: 解放されない");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
                return value;
            } finally {
                concurrent.decrementAndGet();
            }
        }
    }
}
