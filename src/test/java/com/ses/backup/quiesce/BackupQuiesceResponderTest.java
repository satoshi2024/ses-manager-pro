package com.ses.backup.quiesce;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RB-QUIESCE BWQ-003: 静止応答の状態機械（{@link BackupQuiesceResponder}）を fake の repository と差し替えた時計で確かめる
 * （design.md §6.8・§6.9・§7.4。I-3・I-6）。要求→ack、REFUSED、解除、lease の期限切れ、最大保持時間、要求の消失、二重処理の無さ、
 * 結果不明の ack の解決、hold 期限の延長の失敗、RESUMED の 0 行・例外、起動の3分岐、排水の間の heartbeat、停止口、
 * 解除後の書込みの投入、保存期間の削除。1 tick に実行する文は高々1つであることも数える。
 */
class BackupQuiesceResponderTest {

    private static final long SECOND = 1_000_000_000L;
    private static final LocalDateTime DB_EPOCH = LocalDateTime.of(2026, 11, 21, 0, 0);

    private final AtomicLong now = new AtomicLong(10_000 * SECOND);
    private BackupQuiesceProperties properties;
    private FakeRepository repository;
    private BackupWriteGate gate;
    private BackupQuiesceLifecycleAnchor anchor;
    private BackupQuiesceResponder responder;
    private SimpleMeterRegistry registry;
    private final List<Runnable> dispatched = new ArrayList<>();
    /** true なら executor が投入した thread で task を実行する（CallerRunsPolicy の再現。P3-4）。 */
    private boolean callerRuns;
    private int heartbeats;
    private long nextHeartbeat;
    private int maxStatementsPerTick;

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        properties = new BackupQuiesceProperties();
        logger = (Logger) LoggerFactory.getLogger(BackupQuiesceResponder.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        build(BackupWriteGate.Mode.ARMED, true);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private void build(BackupWriteGate.Mode mode, boolean prod) {
        repository = new FakeRepository(now);
        gate = new BackupWriteGate(mode, prod, properties.getFenceAfter(), properties.getDeferredWritesMax(), now::get,
                Clock.fixed(Instant.parse("2026-11-21T00:00:00Z"), ZoneOffset.UTC));
        anchor = new BackupQuiesceLifecycleAnchor(properties, gate, repository, true);
        BackupQuiesceMetrics metrics = new BackupQuiesceMetrics(gate);
        registry = new SimpleMeterRegistry();
        metrics.bindTo(registry);
        responder = new BackupQuiesceResponder(anchor, metrics, () -> task -> {
            if (callerRuns) {
                task.run();
            } else {
                dispatched.add(task);
            }
        }, false);
    }

    // ---------------------------------------------------------------- 要求 → ack → 解除

    @Test
    void 要求を読むと排水してQUIESCEDを書き解除でRESUMEDを書いて開く() {
        started();
        long id = repository.request("ONLINE", "CHECKPOINT", 60);
        runUntil(() -> gate.phase() == BackupWriteGate.State.CLOSED && repository.ack(id) != null, Duration.ofSeconds(5));

        FakeRepository.Ack ack = repository.ack(id);
        assertThat(ack.state).isEqualTo("QUIESCED");
        assertThat(ack.schedulerState).isEqualTo("PAUSED");
        assertThat(ack.holdUntil).as("hold 期限は lease の期限").isEqualTo(repository.dbTime(repository.get(id).leaseDeadline));
        assertThat(ack.hosts).isEqualTo("10.0.0.1=2");
        assertThat(gate.state()).isEqualTo(BackupWriteGate.State.CLOSED);
        assertThat(gate.remainingLocalDeadlineNanos()).isPresent();
        assertThat(gate.tryAcquire(BackupWritePermit.Kind.HTTP, "POST /api/x")).as("静止中は permit が出ない").isNull();
        assertThat(messages(Level.INFO)).anyMatch(m -> m.startsWith("errorCode=BACKUP_QUIESCE_REQUESTED"))
                .anyMatch(m -> m.startsWith("errorCode=BACKUP_QUIESCE_ACKED"));

        repository.get(id).status = "RELEASED";
        runUntil(() -> gate.phase() == BackupWriteGate.State.OPEN, Duration.ofSeconds(3));
        assertThat(repository.ack(id).state).isEqualTo("RESUMED");
        assertThat(repository.ack(id).resumeReason).isEqualTo("RELEASED");
        assertThat(messages(Level.INFO)).anyMatch(m -> m.startsWith("errorCode=BACKUP_QUIESCE_RESUMED"));
        assertThat(messages(Level.ERROR)).noneMatch(m -> m.contains("AUTO_RESUMED"));
        assertThat(registry.get(BackupQuiesceMetrics.REQUESTS).tag("result", "acked").counter().count()).isEqualTo(1.0);
        assertThat(maxStatementsPerTick).as("1 tick に短い文を高々1つ").isLessThanOrEqualTo(1);

        // 同じ要求を二度処理しない（RELEASED にした行が REQUESTED に見えても ack を書き直さない）
        repository.get(id).status = "REQUESTED";
        repository.get(id).leaseDeadline = now.get() + 60 * SECOND;
        int inserts = repository.ackInserts;
        run(Duration.ofSeconds(3));
        assertThat(repository.ackInserts).isEqualTo(inserts);
        assertThat(gate.phase()).isEqualTo(BackupWriteGate.State.OPEN);
    }

    @Test
    void 排水の上限を超えるとREFUSEDを書き待っていた処理をlogに出して開く() {
        started();
        BackupWritePermit longJob = gate.tryAcquireDetached(BackupWritePermit.Kind.SCHEDULER, "MonthlyClosingJob#run");
        long id = repository.request("ONLINE", "CHECKPOINT", 60);
        run(Duration.ofSeconds(29));
        assertThat(gate.phase()).isEqualTo(BackupWriteGate.State.CLOSING);
        run(Duration.ofSeconds(2));

        assertThat(gate.phase()).isEqualTo(BackupWriteGate.State.OPEN);
        assertThat(repository.ack(id).state).isEqualTo("REFUSED");
        assertThat(repository.ack(id).refuseReason).isEqualTo("DRAIN_TIMEOUT");
        assertThat(messages(Level.WARN)).anyMatch(m -> m.startsWith("errorCode=BACKUP_QUIESCE_REFUSED")
                && m.contains("scheduler:MonthlyClosingJob#run"));
        assertThat(registry.get(BackupQuiesceMetrics.REQUESTS).tag("result", "refused_drain_timeout").counter().count())
                .isEqualTo(1.0);
        longJob.close();
    }

    @Test
    void leaseの延長が止まると手元の期限で再開しRESUMED_LEASE_EXPIREDを書く() {
        started();
        long id = acked("CHECKPOINT", 60);
        run(Duration.ofSeconds(61));
        assertThat(gate.phase()).isEqualTo(BackupWriteGate.State.OPEN);
        assertThat(repository.ack(id).state).isEqualTo("RESUMED");
        assertThat(repository.ack(id).resumeReason).isEqualTo("LEASE_EXPIRED");
        assertThat(messages(Level.ERROR)).anyMatch(m -> m.startsWith("errorCode=BACKUP_QUIESCE_AUTO_RESUMED"));
    }

    @Test
    void 最大保持時間を超えるとMAX_HOLDで再開する() {
        started();
        long id = repository.request("ONLINE", "CHECKPOINT", 60);
        runUntil(() -> repository.ack(id) != null, Duration.ofSeconds(5));
        // lease を延ばし続ける（保持 process）。hold 期限は最大保持時間（300 秒）で止まる
        for (int i = 0; i < 400 && gate.phase() != BackupWriteGate.State.OPEN; i++) {
            repository.get(id).leaseDeadline = now.get() + 60 * SECOND;
            run(Duration.ofSeconds(1));
        }
        assertThat(gate.phase()).isEqualTo(BackupWriteGate.State.OPEN);
        assertThat(repository.ack(id).resumeReason).isEqualTo("MAX_HOLD");
        assertThat(repository.ack(id).holdUntil).isEqualTo(repository.dbTime(repository.get(id).maxHoldDeadline));
    }

    @Test
    void 要求が消えるとREQUEST_GONEで再開しfullの中止はWARNを出す() {
        started();
        long id = acked("FULL", 60);
        repository.get(id).status = "ABORTED";
        repository.get(id).releaseReason = "NEW_INSTANCE";
        run(Duration.ofSeconds(2));
        assertThat(gate.phase()).isEqualTo(BackupWriteGate.State.OPEN);
        assertThat(repository.ack(id).resumeReason).isEqualTo("REQUEST_GONE");
        assertThat(messages(Level.WARN)).anyMatch(m -> m.startsWith("errorCode=BACKUP_QUIESCE_FULL_ABORTED"));

        long second = acked("CHECKPOINT", 60);
        repository.requests.remove(second);
        run(Duration.ofSeconds(2));
        assertThat(gate.phase()).as("行が無くなっても再開する").isEqualTo(BackupWriteGate.State.OPEN);
    }

    // ---------------------------------------------------------------- 結果不明の ack（I-3）

    @Test
    void QUIESCEDのINSERTがstoreに入った後で例外を投げると手元の期限まで開かない() {
        started();
        repository.quiescedFailure = () -> new QueryTimeoutException("socket timeout");
        repository.storeQuiescedBeforeFailure = true;
        repository.refusedFailure = () -> new QueryTimeoutException("lock wait timeout");
        long id = repository.request("ONLINE", "CHECKPOINT", 60);
        runUntil(() -> repository.ack(id) != null, Duration.ofSeconds(5));
        assertThat(messages(Level.WARN)).anyMatch(m -> m.startsWith("errorCode=BACKUP_QUIESCE_ACK_UNKNOWN"));

        run(Duration.ofSeconds(55));
        assertThat(gate.state()).as("REFUSED を確定できない間は閉じたまま").isEqualTo(BackupWriteGate.State.CLOSED);
        assertThat(repository.refusedAttempts).as("REFUSED の INSERT の timeout は次の tick へ回る").isGreaterThan(100);
        run(Duration.ofSeconds(6));
        assertThat(gate.state()).as("手元の期限（hold 期限）で開く").isEqualTo(BackupWriteGate.State.OPEN);
        assertThat(heartbeats).as("その間も heartbeat は止まらない").isGreaterThanOrEqualTo(12);
        assertThat(maxStatementsPerTick).isLessThanOrEqualTo(1);
    }

    @Test
    void 結果不明の後にREFUSEDの重複を読むとQUIESCEDとして続ける() {
        started();
        repository.quiescedFailure = () -> new QueryTimeoutException("socket timeout");
        repository.storeQuiescedBeforeFailure = true;
        long id = repository.request("ONLINE", "CHECKPOINT", 60);
        run(Duration.ofSeconds(3));
        assertThat(repository.ack(id).state).isEqualTo("QUIESCED");
        assertThat(gate.state()).isEqualTo(BackupWriteGate.State.CLOSED);
        assertThat(responder.currentStep()).isEqualTo(BackupQuiesceResponder.Step.QUIESCED);

        repository.get(id).status = "RELEASED";
        run(Duration.ofSeconds(2));
        assertThat(gate.phase()).isEqualTo(BackupWriteGate.State.OPEN);
        assertThat(repository.ack(id).state).isEqualTo("RESUMED");
    }

    @Test
    void 結果不明の後にREFUSEDが成功すれば期限の前に開く() {
        started();
        repository.quiescedFailure = () -> new QueryTimeoutException("socket timeout");
        repository.storeQuiescedBeforeFailure = false;
        AtomicInteger attempts = new AtomicInteger();
        repository.refusedFailure = () -> attempts.incrementAndGet() <= 3 ? new QueryTimeoutException("lock wait") : null;
        long id = repository.request("ONLINE", "CHECKPOINT", 60);
        run(Duration.ofSeconds(3));
        assertThat(gate.phase()).isEqualTo(BackupWriteGate.State.OPEN);
        assertThat(repository.ack(id).state).isEqualTo("REFUSED");
        assertThat(repository.ack(id).refuseReason).isEqualTo("ERROR");
        assertThat(attempts.get()).as("timeout の後も次の tick で繰り返した").isEqualTo(4);
        assertThat(registry.get(BackupQuiesceMetrics.REQUESTS).tag("result", "refused_error").counter().count())
                .isEqualTo(1.0);
    }

    // ---------------------------------------------------------------- hold 期限・RESUMED の失敗

    @Test
    void hold期限のUPDATEが失敗しても手元の期限は先に延びている() {
        started();
        long id = acked("CHECKPOINT", 60);
        long before = gate.remainingLocalDeadlineNanos().getAsLong();
        repository.holdFailure = () -> new QueryTimeoutException("lock wait");
        repository.get(id).leaseDeadline = now.get() + 120 * SECOND;
        run(Duration.ofSeconds(2));
        assertThat(repository.holdAttempts).isPositive();
        assertThat(repository.ack(id).holdUntil).as("DB の hold 期限は古いまま")
                .isBefore(repository.dbTime(repository.get(id).leaseDeadline));
        assertThat(gate.remainingLocalDeadlineNanos().getAsLong()).as("手元の期限は新しい hold 期限まで延びた")
                .isGreaterThan(before + 50 * SECOND);

        repository.holdFailure = () -> null;
        run(Duration.ofSeconds(2));
        assertThat(repository.ack(id).holdUntil).isEqualTo(repository.dbTime(repository.get(id).leaseDeadline));
    }

    @Test
    void RESUMEDのUPDATEが0行なら行を読みRESUMEDなら開き例外なら手元の期限まで閉じる() {
        started();
        long id = acked("CHECKPOINT", 60);
        repository.ack(id).state = "RESUMED";
        repository.get(id).status = "RELEASED";
        run(Duration.ofSeconds(2));
        assertThat(gate.phase()).as("0行の後に行を読み RESUMED なら開く").isEqualTo(BackupWriteGate.State.OPEN);

        long second = acked("CHECKPOINT", 30);
        repository.resumeFailure = () -> new QueryTimeoutException("lock wait");
        repository.get(second).status = "RELEASED";
        run(Duration.ofSeconds(10));
        assertThat(gate.state()).as("RESUMED を確定できない間は閉じたまま").isEqualTo(BackupWriteGate.State.CLOSED);
        run(Duration.ofSeconds(25));
        assertThat(gate.state()).as("手元の期限で開く").isEqualTo(BackupWriteGate.State.OPEN);
    }

    // ---------------------------------------------------------------- 排水の間の heartbeat（I-6）

    @Test
    void 排水を60秒続けてもheartbeatが5秒ごとに書かれFENCEDにならない() {
        started();
        BackupWritePermit longJob = gate.tryAcquireDetached(BackupWritePermit.Kind.SCHEDULER, "DocumentArchive#run");
        long id = repository.request("ONLINE", "FULL", 900);
        int beatsBefore = heartbeats;
        for (long elapsed = 0; elapsed < 60 * SECOND; elapsed += 200_000_000L) {
            step();
            assertThat(gate.state()).as("排水中（%d ms）", elapsed / 1_000_000).isEqualTo(BackupWriteGate.State.CLOSING);
        }
        assertThat(heartbeats - beatsBefore).isEqualTo(12);
        assertThat(gate.isFenced()).isFalse();
        assertThat(repository.ack(id)).as("full の排水の上限（600 秒）までは ack も拒否もしない").isNull();
        assertThat(maxStatementsPerTick).isLessThanOrEqualTo(1);
        longJob.close();
        run(Duration.ofSeconds(2));
        assertThat(repository.ack(id).state).isEqualTo("QUIESCED");
    }

    // ---------------------------------------------------------------- 起動の3分岐（§6.8）

    @Test
    void 起動時に有効な要求が無ければstartup_permitを取って開く() {
        responder.afterSingletonsInstantiated();
        assertThat(repository.registeredGateState).isEqualTo("UNARMED");
        assertThat(gate.phase()).isEqualTo(BackupWriteGate.State.OPEN);
        assertThat(gate.currentThreadHoldsPermit()).as("main の thread が startup permit を持つ").isTrue();
        assertThat(gate.permitCount()).isEqualTo(1);
        responder.onApplicationReady(null);
        assertThat(gate.permitCount()).isZero();
        anchor.heartbeatOnce();
        assertThat(repository.lastHeartbeatStatus).isEqualTo("RUNNING");
    }

    @Test
    void 起動時にAPP_STOPPEDの保持があれば起動を中止する() {
        repository.request("APP_STOPPED", "FULL", 60);
        assertThatThrownBy(responder::afterSingletonsInstantiated).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_STOPPED");
        assertThat(messages(Level.ERROR)).anyMatch(m -> m.startsWith("errorCode=BACKUP_QUIESCE_STARTUP_ABORT"));
        assertThat(gate.phase()).as("開かない").isEqualTo(BackupWriteGate.State.UNARMED);
        assertThat(gate.permitCount()).isZero();
    }

    @Test
    void 起動時の登録が書けなければ起動を中止する() {
        repository.registerFailure = new QueryTimeoutException("db down");
        assertThatThrownBy(responder::afterSingletonsInstantiated).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("instance の登録");
    }

    @Test
    void 起動時にONLINEの要求があれば応答して解除まで起動を待つ() throws Exception {
        long id = repository.request("ONLINE", "CHECKPOINT", 60);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread main = startInBackground(failure);
        runUntil(() -> repository.ack(id) != null, Duration.ofSeconds(5));
        assertThat(repository.ack(id).state).isEqualTo("QUIESCED");
        assertThat(main.isAlive()).as("解除まで起動を待つ").isTrue();
        repository.get(id).status = "RELEASED";
        runUntil(() -> gate.phase() == BackupWriteGate.State.OPEN, Duration.ofSeconds(3));
        main.join(10_000);
        assertThat(main.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat(gate.permitCount()).as("startup permit を取った").isEqualTo(1);
        assertThat(messages(Level.WARN)).anyMatch(m -> m.startsWith("errorCode=BACKUP_QUIESCE_STARTUP_WAIT"));
    }

    @Test
    void 起動時のONLINEの静止がstartup_wait_maxを過ぎても解除されなければ起動を中止する() throws Exception {
        long held = repository.request("ONLINE", "FULL", 900);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread waiting = startInBackground(failure);
        runUntil(() -> repository.ack(held) != null, Duration.ofSeconds(5));
        run(Duration.ofSeconds(61));
        waiting.join(10_000);
        assertThat(waiting.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOf(IllegalStateException.class).hasMessageContaining("解除されません");
    }

    /** 起動の処理を別の thread で始め、静止の解除の待ちに入るまで待つ（その後で試験の thread が状態機械を進める）。 */
    private Thread startInBackground(AtomicReference<Throwable> failure) throws InterruptedException {
        Thread main = new Thread(() -> {
            try {
                responder.afterSingletonsInstantiated();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "test-startup");
        main.start();
        long giveUp = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (main.getState() != Thread.State.TIMED_WAITING && main.isAlive()) {
            assertThat(System.nanoTime() - giveUp).as("起動の処理が待ちに入らない").isNegative();
            Thread.sleep(5);
        }
        return main;
    }

    // ---------------------------------------------------------------- 停止口（U-7）

    @Test
    void 停止口ではDISABLEDで登録しheartbeatを続け要求にREFUSED_DISABLEDを返しgateを閉じない() {
        build(BackupWriteGate.Mode.DISABLED, true);
        responder.afterSingletonsInstantiated();
        assertThat(repository.registeredGateState).isEqualTo("DISABLED");
        assertThat(messages(Level.ERROR)).anyMatch(m -> m.startsWith("errorCode=BACKUP_QUIESCE_DISABLED"));
        long id = repository.request("ONLINE", "CHECKPOINT", 60);
        run(Duration.ofSeconds(3));
        assertThat(repository.ack(id).state).isEqualTo("REFUSED");
        assertThat(repository.ack(id).refuseReason).isEqualTo("DISABLED");
        assertThat(gate.state()).isEqualTo(BackupWriteGate.State.DISABLED);
        assertThat(repository.lastHeartbeatGateState).isEqualTo("DISABLED");
        assertThat(gate.tryAcquire(BackupWritePermit.Kind.HTTP, "POST")).isNotNull().satisfies(BackupWritePermit::close);
    }

    @Test
    void 停止口でもAPP_STOPPEDの保持中は起動を中止する() {
        build(BackupWriteGate.Mode.DISABLED, true);
        repository.request("APP_STOPPED", "FULL", 60);
        assertThatThrownBy(responder::afterSingletonsInstantiated).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 動いているinstanceがAPP_STOPPEDの保持を読むとackを書かずに閉じ保持が終われば開く() {
        started();
        long id = repository.request("APP_STOPPED", "FULL", 60);
        run(Duration.ofSeconds(2));
        assertThat(gate.state()).isEqualTo(BackupWriteGate.State.CLOSED);
        assertThat(repository.ack(id)).isNull();
        assertThat(messages(Level.ERROR)).anyMatch(m -> m.startsWith("errorCode=BACKUP_QUIESCE_STOPPED_HOLD_SEEN"));
        repository.get(id).status = "ABORTED";
        run(Duration.ofSeconds(2));
        assertThat(gate.state()).isEqualTo(BackupWriteGate.State.OPEN);
    }

    // ---------------------------------------------------------------- 解除後の書込み・保存期間

    @Test
    void 解除の後に保留した処理と解除後の書込みをexecutorへ投入する() {
        started();
        long id = acked("CHECKPOINT", 60);
        List<String> written = new ArrayList<>();
        gate.deferUntilOpen(() -> written.add("async"));
        gate.deferredWrites().add(new BackupQuiesceDeferredWrites.DeferredWrite() {
            @Override
            public String summary() {
                return "user=1 session=abcdef01";
            }

            @Override
            public void write() {
                written.add("logout");
            }
        });
        run(Duration.ofSeconds(1));
        assertThat(dispatched).as("閉じている間は投入しない").isEmpty();
        repository.get(id).status = "RELEASED";
        run(Duration.ofSeconds(2));
        assertThat(gate.phase()).isEqualTo(BackupWriteGate.State.OPEN);
        assertThat(dispatched).hasSize(2);
        dispatched.forEach(Runnable::run);
        assertThat(written).containsExactly("async", "logout");
        assertThat(gate.deferredWrites().pending()).isZero();
    }

    /** 2a review P3-4: 投入した task を CallerRunsPolicy で状態機械の thread が実行しかけても、実行せず保留のまま次の tick へ回す。 */
    @Test
    void 状態機械のthreadがCallerRunsで実行しかけたtaskは実行せず保留のまま次のtickで投入し直す() {
        started();
        long id = acked("CHECKPOINT", 60);
        List<String> written = new ArrayList<>();
        gate.deferUntilOpen(() -> written.add("async"));
        gate.deferredWrites().add(new BackupQuiesceDeferredWrites.DeferredWrite() {
            @Override
            public String summary() {
                return "user=1 session=abcdef01";
            }

            @Override
            public void write() {
                written.add("logout");
            }
        });
        callerRuns = true;
        repository.get(id).status = "RELEASED";
        run(Duration.ofSeconds(2));

        assertThat(gate.phase()).isEqualTo(BackupWriteGate.State.OPEN);
        assertThat(written).as("状態機械の thread では実行しない").isEmpty();
        assertThat(gate.pendingTaskCount()).isEqualTo(1);
        assertThat(gate.deferredWrites().pending()).isEqualTo(1);

        callerRuns = false;
        run(Duration.ofSeconds(1));
        assertThat(dispatched).hasSize(2);
        dispatched.forEach(Runnable::run);
        assertThat(written).containsExactlyInAnyOrder("async", "logout");
        assertThat(gate.pendingTaskCount()).isZero();
        assertThat(gate.deferredWrites().pending()).isZero();
    }

    @Test
    void 有効な要求が無い時に1日1回保存期間を過ぎた行を消す() {
        started();
        run(Duration.ofSeconds(59));
        assertThat(repository.purgedRequests).isZero();
        run(Duration.ofSeconds(3));
        assertThat(repository.purgedRequests).isEqualTo(1);
        assertThat(repository.purgedInstances).isEqualTo(1);
        assertThat(repository.purgeRetention).isEqualTo(Duration.ofDays(30));
        assertThat(repository.purgeOwnInstance).isEqualTo(anchor.instanceId());
        run(Duration.ofHours(1));
        assertThat(repository.purgedRequests).as("次は1日後").isEqualTo(1);
        assertThat(maxStatementsPerTick).isLessThanOrEqualTo(1);
    }

    // ---------------------------------------------------------------- 接続元の解釈（D-P3-1）

    @Test
    void PROCESSLISTのHOSTだけportを外しUSERのhostはそのまま使う() {
        assertThat(BackupQuiesceHosts.fromProcesslist("fe80::1:54321")).isEqualTo("fe80::1");
        assertThat(BackupQuiesceHosts.fromProcesslist("[::1]:3306")).isEqualTo("::1");
        assertThat(BackupQuiesceHosts.fromProcesslist("10.0.0.5:51000")).isEqualTo("10.0.0.5");
        assertThat(BackupQuiesceHosts.fromProcesslist("localhost")).isEqualTo("localhost");
        assertThat(BackupQuiesceHosts.fromUserFunction("ses_app@fe80::1")).isEqualTo("fe80::1");
        assertThat(BackupQuiesceHosts.fromUserFunction("ses_app@10.0.0.5")).isEqualTo("10.0.0.5");
        assertThat(BackupQuiesceHosts.fromUserFunction("SA")).isEmpty();
        assertThat(BackupQuiesceHosts.aggregate(List.of("10.0.0.6:1", "10.0.0.5:2", "10.0.0.5:3")))
                .isEqualTo("10.0.0.5=2,10.0.0.6=1");
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            many.add("10.0." + i / 250 + "." + i + ":1000");
        }
        assertThat(BackupQuiesceHosts.aggregate(many)).as("長さを超えたら照合に必ず失敗する値").isEqualTo("OVERFLOW=200");
    }

    // ---------------------------------------------------------------- helper

    private void started() {
        responder.afterSingletonsInstantiated();
        responder.onApplicationReady(null);
        nextHeartbeat = now.get();
    }

    /** 承認済み（QUIESCED）の要求を作る。 */
    private long acked(String jobKind, long leaseSeconds) {
        long id = repository.request("ONLINE", jobKind, leaseSeconds);
        runUntil(() -> repository.ack(id) != null && "QUIESCED".equals(repository.ack(id).state), Duration.ofSeconds(5));
        assertThat(gate.state()).isEqualTo(BackupWriteGate.State.CLOSED);
        return id;
    }

    /** 200 ms 進めて tick し、5 秒ごとに heartbeat を書く（heartbeat の thread の代わり）。 */
    private void step() {
        now.addAndGet(BackupQuiesceProperties.TICK.toNanos());
        if (now.get() - nextHeartbeat >= 0) {
            anchor.heartbeatOnce();
            heartbeats++;
            nextHeartbeat = now.get() + properties.getHeartbeatInterval().toNanos();
        }
        int before = repository.statements;
        responder.tick();
        maxStatementsPerTick = Math.max(maxStatementsPerTick, repository.statements - before);
    }

    private void run(Duration duration) {
        long end = now.get() + duration.toNanos();
        while (now.get() - end < 0) {
            step();
        }
    }

    private void runUntil(Supplier<Boolean> condition, Duration max) {
        long end = now.get() + max.toNanos();
        while (!condition.get()) {
            assertThat(now.get() - end).as("条件が満たされない").isNegative();
            step();
        }
    }

    private List<String> messages(Level level) {
        return appender.list.stream().filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** 信号表の fake。時刻は gate と同じ単調時計を DB の時刻へ写して扱う。 */
    static class FakeRepository implements BackupQuiesceSignalRepository {

        static final class Request {
            long id;
            String mode;
            String jobKind;
            String status = "REQUESTED";
            String releaseReason;
            long leaseDeadline;
            long maxHoldDeadline;
            LocalDateTime verifyRequestedAt;
        }

        static final class Ack {
            String state;
            String schedulerState;
            LocalDateTime holdUntil;
            String hosts;
            String refuseReason;
            String resumeReason;
        }

        private final AtomicLong clock;
        private final long epoch;
        final Map<Long, Request> requests = new LinkedHashMap<>();
        final Map<Long, Ack> acks = new LinkedHashMap<>();
        long nextId = 1;
        int statements;
        int ackInserts;
        int refusedAttempts;
        int holdAttempts;
        int purgedRequests;
        int purgedInstances;
        Duration purgeRetention;
        String purgeOwnInstance;
        String registeredGateState;
        String lastHeartbeatStatus;
        String lastHeartbeatGateState;
        RuntimeException registerFailure;
        Supplier<RuntimeException> quiescedFailure = () -> null;
        boolean storeQuiescedBeforeFailure;
        Supplier<RuntimeException> refusedFailure = () -> null;
        Supplier<RuntimeException> holdFailure = () -> null;
        Supplier<RuntimeException> resumeFailure = () -> null;
        Supplier<RuntimeException> observeFailure = () -> null;

        FakeRepository(AtomicLong clock) {
            this.clock = clock;
            this.epoch = clock.get();
        }

        LocalDateTime dbTime(long nanos) {
            return DB_EPOCH.plusNanos(((nanos - epoch) / 1000) * 1000);
        }

        long request(String mode, String jobKind, long leaseSeconds) {
            Request request = new Request();
            request.id = nextId++;
            request.mode = mode;
            request.jobKind = jobKind;
            request.leaseDeadline = clock.get() + leaseSeconds * SECOND;
            request.maxHoldDeadline = clock.get() + ("FULL".equals(jobKind) ? 3600 : 300) * SECOND;
            requests.put(request.id, request);
            return request.id;
        }

        Request get(long id) {
            return requests.get(id);
        }

        Ack ack(long id) {
            return acks.get(id);
        }

        private RequestView view(Request request) {
            long now = clock.get();
            long hold = Math.min(request.leaseDeadline, request.maxHoldDeadline);
            return new RequestView(request.id, request.mode, request.jobKind, request.status, request.releaseReason, 1,
                    now < request.leaseDeadline, now >= request.maxHoldDeadline,
                    request.leaseDeadline >= request.maxHoldDeadline, dbTime(hold), (hold - now) / 1000,
                    request.verifyRequestedAt);
        }

        private static void fail(Supplier<RuntimeException> failure) {
            RuntimeException exception = failure.get();
            if (exception != null) {
                throw exception;
            }
        }

        @Override
        public Registered registerInstance(InstanceRegistration registration) {
            statements++;
            if (registerFailure != null) {
                throw registerFailure;
            }
            registeredGateState = registration.gateState();
            return new Registered("10.0.0.1", 42);
        }

        @Override
        public boolean heartbeat(String instanceId, String status, String gateState) {
            lastHeartbeatStatus = status;
            lastHeartbeatGateState = gateState;
            return true;
        }

        @Override
        public boolean markStopped(String instanceId) {
            return true;
        }

        @Override
        public Optional<RequestView> findActiveRequest() {
            statements++;
            return requests.values().stream().filter(r -> "REQUESTED".equals(r.status)).reduce((a, b) -> b)
                    .map(this::view);
        }

        @Override
        public Optional<RequestView> findRequest(long requestId) {
            statements++;
            return Optional.ofNullable(requests.get(requestId)).map(this::view);
        }

        @Override
        public Observation observeAppHosts() {
            statements++;
            fail(observeFailure);
            return new Observation(BackupQuiesceHosts.aggregate(List.of("10.0.0.1:50001", "10.0.0.1:50002")),
                    dbTime(clock.get()));
        }

        @Override
        public void insertQuiescedAck(long requestId, String instanceId, String schedulerState, int drainMillis,
                                      LocalDateTime holdUntil, Observation observation) {
            statements++;
            ackInserts++;
            RuntimeException failure = quiescedFailure.get();
            if (failure == null || storeQuiescedBeforeFailure) {
                if (acks.containsKey(requestId)) {
                    throw new DuplicateKeyException("ack");
                }
                Ack ack = new Ack();
                ack.state = "QUIESCED";
                ack.schedulerState = schedulerState;
                ack.holdUntil = holdUntil;
                ack.hosts = observation.hosts();
                acks.put(requestId, ack);
            }
            if (failure != null) {
                quiescedFailure = () -> null;
                throw failure;
            }
        }

        @Override
        public void insertRefusedAck(long requestId, String instanceId, String schedulerState, int drainMillis,
                                     String refuseReason, Observation observation) {
            statements++;
            refusedAttempts++;
            fail(refusedFailure);
            if (acks.containsKey(requestId)) {
                throw new DuplicateKeyException("ack");
            }
            Ack ack = new Ack();
            ack.state = "REFUSED";
            ack.schedulerState = schedulerState;
            ack.refuseReason = refuseReason;
            acks.put(requestId, ack);
        }

        @Override
        public Optional<AckState> findAckState(long requestId, String instanceId) {
            statements++;
            return Optional.ofNullable(acks.get(requestId)).map(ack -> AckState.valueOf(ack.state));
        }

        @Override
        public int updateHoldUntil(long requestId, String instanceId, LocalDateTime holdUntil) {
            statements++;
            holdAttempts++;
            fail(holdFailure);
            Ack ack = acks.get(requestId);
            if (ack != null && "QUIESCED".equals(ack.state) && ack.holdUntil.isBefore(holdUntil)) {
                ack.holdUntil = holdUntil;
                return 1;
            }
            return 0;
        }

        @Override
        public int updateObservation(long requestId, String instanceId, Observation observation) {
            statements++;
            Ack ack = acks.get(requestId);
            if (ack != null && "QUIESCED".equals(ack.state)) {
                ack.hosts = observation.hosts();
                return 1;
            }
            return 0;
        }

        @Override
        public int markResumed(long requestId, String instanceId, String resumeReason) {
            statements++;
            fail(resumeFailure);
            Ack ack = acks.get(requestId);
            if (ack != null && "QUIESCED".equals(ack.state)) {
                ack.state = "RESUMED";
                ack.resumeReason = resumeReason;
                return 1;
            }
            return 0;
        }

        @Override
        public int purgeRequests(Duration retention) {
            statements++;
            purgedRequests++;
            purgeRetention = retention;
            return 0;
        }

        @Override
        public int purgeInstances(Duration retention, String ownInstanceId) {
            statements++;
            purgedInstances++;
            purgeOwnInstance = ownInstanceId;
            return 0;
        }
    }

    // ---------------------------------------------------------------- 2a review の指摘（P3-2・P3-3・P3-8）

    /** P3-2: 排水の後の準備が排水の上限まで失敗し続けた REFUSED（ERROR）も、固定の errorCode と最後の失敗の型を WARN に出す。 */
    @Test
    void 準備の失敗が排水の上限まで続いたREFUSED_ERRORは固定のerrorCodeと最後の失敗の型をWARNに出す() {
        started();
        repository.observeFailure = () -> new QueryTimeoutException("observe");
        long id = repository.request("ONLINE", "CHECKPOINT", 120);
        run(properties.getDrainTimeoutCheckpoint().plusSeconds(2));

        assertThat(repository.ack(id)).isNotNull();
        assertThat(repository.ack(id).state).isEqualTo("REFUSED");
        assertThat(gate.state()).isEqualTo(BackupWriteGate.State.OPEN);
        assertThat(messages(Level.WARN)).anyMatch(m -> m.startsWith("errorCode=BACKUP_QUIESCE_REFUSED")
                && m.contains("REFUSED（ERROR）") && m.contains("QueryTimeoutException"));
    }

    /** P3-3: 信号表が無い（1146・42S02）ために登録できなければ、§3.4 と schema guard を指して起動を中止する。 */
    @Test
    void 信号表が無いため登録できなければmigrationとschema_guardを指して起動を中止する() {
        repository.registerFailure = new org.springframework.jdbc.BadSqlGrammarException("register", "INSERT ...",
                new java.sql.SQLSyntaxErrorException("Table 'ses.sys_backup_quiesce_instance' doesn't exist", "42S02", 1146));

        assertThatThrownBy(responder::afterSingletonsInstantiated).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("信号表").hasMessageContaining("§3.4").hasMessageContaining("schema guard");
        assertThat(BackupQuiesceResponder.missingSignalTable(new RuntimeException(
                new java.sql.SQLException("x", "42S02", 42102)))).as("H2 の表が無い").isTrue();
        assertThat(BackupQuiesceResponder.missingSignalTable(new QueryTimeoutException("db down"))).isFalse();
    }

    /** P3-8: 解除の log の件数は、開いて投入し直す前の値を出す。 */
    @Test
    void 解除のlogの保留と解除後の書込みの件数は開く前の値を出す() {
        started();
        long id = acked("CHECKPOINT", 60);
        gate.deferUntilOpen(() -> { });
        gate.deferUntilOpen(() -> { });
        gate.deferredWrites().add(new BackupQuiesceDeferredWrites.DeferredWrite() {
            @Override
            public String summary() {
                return "user=1 session=abcdef01";
            }

            @Override
            public void write() {
            }
        });
        repository.get(id).status = "RELEASED";
        run(Duration.ofSeconds(2));

        assertThat(messages(Level.INFO)).anyMatch(m -> m.startsWith("errorCode=BACKUP_QUIESCE_RESUMED")
                && m.contains("保留した非同期の処理 2 件") && m.contains("解除後の書込み 1 件"));
    }
}
