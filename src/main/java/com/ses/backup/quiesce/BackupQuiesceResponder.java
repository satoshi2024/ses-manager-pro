package com.ses.backup.quiesce;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.ses.backup.quiesce.BackupQuiesceSignalRepository.AckState;
import com.ses.backup.quiesce.BackupQuiesceSignalRepository.Observation;
import com.ses.backup.quiesce.BackupQuiesceSignalRepository.RequestView;

/**
 * backup の書込み静止の応答（design.md §5・§6.8・§6.9・§6.11・§7.4）。
 *
 * <p>状態機械の thread（{@code backup-quiesce-responder}）が {@link BackupQuiesceProperties#TICK}（200 ms）ごとの tick で動く
 * <b>塞がない状態機械</b>である。1 tick に短い文を高々1つ実行し、排水の完了は memory の数で毎 tick 確かめる（待つための
 * sleep・繰返しの loop を持たない。I-6）。結果不明の解決・RESUMED の繰返しも次の tick へ回す。heartbeat は別の thread と接続で
 * {@link BackupQuiesceLifecycleAnchor} が書く。
 *
 * <p>開け直しの規則（I-3）は {@link BackupWriteGate} が強制する: QUIESCED を一度試みた要求は、非 QUIESCED の確定（RESUMED が
 * 1行に効いた・REFUSED の INSERT が成功した）か手元の期限でだけ開く。新しい hold 期限は DB へ書く前に手元の期限へ反映する。
 *
 * <p>{@code dataSource} の依存には入らない。{@code taskExecutor}・{@code MeterRegistry} は使う時に引く（D3-P2-1）。
 */
public class BackupQuiesceResponder implements SmartInitializingSingleton, DisposableBean, ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(BackupQuiesceResponder.class);

    static final Duration RETENTION_FIRST_DELAY = Duration.ofMinutes(1);
    static final Duration RETENTION_INTERVAL = Duration.ofDays(1);
    static final Duration RETENTION_RETRY = Duration.ofHours(1);
    /** 手元の期限で開いた後に RESUMED を書く試みの上限（provider の解除の確認は 15 秒で終わる）。 */
    static final int RESUME_ATTEMPTS_AFTER_DEADLINE = 30;
    private static final int HANDLED_MEMORY = 1000;
    private static final Duration THREAD_STOP_WAIT = Duration.ofSeconds(12);

    /** 状態機械の段階。 */
    enum Step {
        DRAINING, READ_HOLD, OBSERVE, INSERT_QUIESCED, RESOLVE_UNKNOWN, READ_ACK_STATE, QUIESCED, EXTEND_HOLD,
        OBSERVE_AGAIN, WRITE_OBSERVATION, RESUME, READ_ACK_FOR_RESUME, REFUSE, STOPPED_HOLD
    }

    /** 1つの要求の処理。 */
    static final class Work {
        final RequestView request;
        final long startedNanos;
        Step step;
        long drainDeadlineNanos;
        long drainedNanos = -1;
        long nextPollNanos;
        long nextObserveNanos;
        long nextExtendNanos;
        LocalDateTime holdUntil;
        LocalDateTime writtenHoldUntil;
        LocalDateTime pendingHoldUntil;
        boolean holdCapped;
        Observation observation;
        boolean observeDue;
        LocalDateTime lastVerifySeen;
        String refuseReason;
        String resumeReason;
        int resumeAttempts;
        boolean reopened;

        Work(RequestView request, long now) {
            this.request = request;
            this.startedNanos = now;
            this.nextPollNanos = now;
        }

        long id() {
            return request.id();
        }

        String jobKind() {
            return request.jobKind() == null ? "" : request.jobKind().toLowerCase(Locale.ROOT);
        }
    }

    private final BackupQuiesceLifecycleAnchor anchor;
    private final BackupWriteGate gate;
    private final BackupQuiesceProperties properties;
    private final BackupQuiesceMetrics metrics;
    private final Supplier<Executor> taskExecutor;
    private final boolean startThreads;
    private final BackupQuiesceTicker stateMachineTicker = new BackupQuiesceTicker("backup-quiesce-responder");
    private final AtomicBoolean flushInFlight = new AtomicBoolean();

    private volatile ApplicationContext applicationContext;
    private volatile BackupWritePermit startupPermit;

    // 状態機械（状態機械の thread だけが触る。起動の処理は thread を始める前にだけ触る）
    private final Set<Long> handled = new LinkedHashSet<>();
    private volatile Work work;
    private long nextPollNanos;
    private long nextRetentionNanos;
    private int retentionStep;
    private boolean lastPollFoundActive;

    /** Spring が使う。{@code taskExecutor} は使う時に引く（{@code dataSource} の依存の循環を作らない）。 */
    public BackupQuiesceResponder(BackupQuiesceLifecycleAnchor anchor, BackupQuiesceMetrics metrics,
                                  ObjectProvider<Executor> taskExecutor) {
        this(anchor, metrics, taskExecutor::getIfAvailable, true);
    }

    /** 試験用（thread を始めず、tick を試験が呼ぶ）。 */
    BackupQuiesceResponder(BackupQuiesceLifecycleAnchor anchor, BackupQuiesceMetrics metrics,
                           Supplier<Executor> taskExecutor, boolean startThreads) {
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        this.gate = anchor.gate();
        this.properties = anchor.properties();
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor");
        this.startThreads = startThreads;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    // ---------------------------------------------------------------- 起動（§6.8）

    /**
     * lifecycle の開始・Tomcat の port・{@code ApplicationRunner} より前に動く。instance を登録し、commit の後に有効な要求を
     * 読み、APP_STOPPED なら起動を中止し、無ければ startup permit を取って開き、ONLINE なら解除まで待つ。
     */
    @Override
    public void afterSingletonsInstantiated() {
        if (!anchor.isActive()) {
            return;
        }
        gate.setDispatcher(this::tryDispatch);
        gate.addOpenListener(this::scheduleDeferredFlush);
        if (gate.mode() == BackupWriteGate.Mode.DISABLED) {
            log.error("errorCode=BACKUP_QUIESCE_DISABLED backup の書込み静止を停止口（BACKUP_QUIESCE_ENABLED=false、ticket={}）で"
                    + "無効にして起動します。この間の backup は全て失敗します", properties.getDisabledTicket());
        }
        try {
            anchor.register();
        } catch (RuntimeException failure) {
            throw abort("最初の heartbeat（instance の登録）を書けません", failure);
        }
        Optional<RequestView> found;
        try {
            found = anchor.repository().findActiveRequest();
        } catch (RuntimeException failure) {
            throw abort("有効な静止要求を読めません", failure);
        }
        long now = gate.nanoTime();
        nextPollNanos = now;
        nextRetentionNanos = now + RETENTION_FIRST_DELAY.toNanos();
        RequestView request = found.filter(RequestView::active).orElse(null);
        found.filter(candidate -> !candidate.active()).ifPresent(this::ignoreExpired);
        if (request != null && request.appStopped()) {
            throw abort("app の停止を確かめる backup（APP_STOPPED、要求の番号 " + request.id() + "）の保持中です", null);
        }
        if (gate.mode() == BackupWriteGate.Mode.DISABLED) {
            startupPermit = gate.tryAcquire(BackupWritePermit.Kind.STARTUP, "startup");
            startThreads();
            return;
        }
        if (request == null) {
            // 状態機械の thread を始める前なので gate を変える者は他にいない
            startupPermit = gate.acquireStartupPermitWhileUnarmed("startup");
            gate.armOpen();
            startThreads();
            return;
        }
        log.warn("errorCode=BACKUP_QUIESCE_STARTUP_WAIT backup の静止（要求の番号 {}、{}）の最中に起動したため、解除まで起動を"
                + "待ちます（最長 {} 秒）", request.id(), request.jobKind(), properties.getStartupWaitMax().getSeconds());
        metrics.deferred("startup");
        startThreads();
        startupPermit = awaitStartupPermit();
    }

    private BackupWritePermit awaitStartupPermit() {
        long deadline = gate.nanoTime() + properties.getStartupWaitMax().toNanos();
        while (true) {
            long remaining = deadline - gate.nanoTime();
            if (remaining <= 0) {
                throw abort("最長 " + properties.getStartupWaitMax().getSeconds() + " 秒待っても backup の静止が解除されません",
                        null);
            }
            try {
                if (gate.awaitWritable(Duration.ofNanos(Math.min(remaining, Duration.ofSeconds(1).toNanos())))) {
                    BackupWritePermit permit = gate.tryAcquire(BackupWritePermit.Kind.STARTUP, "startup");
                    if (permit != null) {
                        log.info("backup の静止が解除されたため、起動を続けます");
                        return permit;
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw abort("起動の待ちが割り込まれました", interrupted);
            }
        }
    }

    private IllegalStateException abort(String reason, Throwable cause) {
        log.error("errorCode=BACKUP_QUIESCE_STARTUP_ABORT backup の書込み静止の確認ができないため、起動を中止します: {}", reason);
        return new IllegalStateException("[backup quiesce] 起動を中止します: " + reason, cause);
    }

    private void startThreads() {
        if (!startThreads) {
            return;
        }
        anchor.startHeartbeat();
        stateMachineTicker.start(Duration.ZERO, BackupQuiesceProperties.TICK, this::tick);
    }

    /** 起動の完了: instance を RUNNING にし、startup permit を返す。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        if (applicationContext != null && event.getApplicationContext() != applicationContext) {
            return;
        }
        anchor.markRunning();
        releaseStartupPermit();
    }

    /** 通常の停止の入口（lifecycle の停止より前）: SHUTDOWN にし、呼んだ thread の permit を返す（§6.11）。 */
    @EventListener(ContextClosedEvent.class)
    public void onContextClosed(ContextClosedEvent event) {
        if (applicationContext != null && event.getApplicationContext() != applicationContext) {
            return;
        }
        gate.shutdown();
        gate.releaseCurrentThreadPermits();
        releaseStartupPermit();
    }

    private void releaseStartupPermit() {
        BackupWritePermit permit = startupPermit;
        if (permit != null) {
            startupPermit = null;
            permit.close();
        }
    }

    @Override
    public void destroy() {
        stateMachineTicker.stop(THREAD_STOP_WAIT);
    }

    // ---------------------------------------------------------------- 状態機械（§6.9）

    /** 1 tick（状態機械の thread）。短い文を高々1つ実行する。 */
    void tick() {
        if (gate.phase() == BackupWriteGate.State.SHUTDOWN) {
            return;
        }
        if (gate.allowsNewPermits()) {
            gate.dispatchPending();
            scheduleDeferredFlush();
        }
        long now = gate.nanoTime();
        if (work == null) {
            idle(now);
            return;
        }
        switch (work.step) {
            case DRAINING -> draining(now);
            case READ_HOLD -> readHold(now);
            case OBSERVE -> observe(now);
            case INSERT_QUIESCED -> insertQuiesced(now);
            case RESOLVE_UNKNOWN -> resolveUnknown(now);
            case READ_ACK_STATE -> readAckState(now);
            case QUIESCED -> quiesced(now);
            case EXTEND_HOLD -> extendHold(now);
            case OBSERVE_AGAIN -> observeAgain(now);
            case WRITE_OBSERVATION -> writeObservation(now);
            case RESUME -> resume(now);
            case READ_ACK_FOR_RESUME -> readAckForResume(now);
            case REFUSE -> refuse(now);
            case STOPPED_HOLD -> stoppedHold(now);
        }
    }

    Step currentStep() {
        Work current = work;
        return current == null ? null : current.step;
    }

    private BackupQuiesceSignalRepository repository() {
        return anchor.repository();
    }

    private void idle(long now) {
        if (now - nextPollNanos >= 0) {
            nextPollNanos = now + properties.getPollInterval().toNanos();
            poll(now);
            return;
        }
        if (!lastPollFoundActive && gate.phase() == BackupWriteGate.State.OPEN && now - nextRetentionNanos >= 0) {
            retention(now);
        }
    }

    private void poll(long now) {
        Optional<RequestView> found;
        try {
            found = repository().findActiveRequest();
        } catch (RuntimeException failure) {
            log.debug("backup の静止要求を読めませんでした", failure);
            return;
        }
        lastPollFoundActive = found.map(RequestView::active).orElse(false);
        if (found.isEmpty() || handled.contains(found.get().id())) {
            armIfUnarmed();
            return;
        }
        RequestView request = found.get();
        remember(request.id());
        if (!request.active()) {
            ignoreExpired(request);
            armIfUnarmed();
            return;
        }
        start(request, now);
    }

    private void ignoreExpired(RequestView request) {
        remember(request.id());
        log.info("lease の期限が切れた backup の静止要求（番号 {}）は無効として扱います", request.id());
        metrics.requestResult(request.jobKind(), "ignored_expired");
    }

    private void remember(long requestId) {
        handled.add(requestId);
        if (handled.size() > HANDLED_MEMORY) {
            Iterator<Long> oldest = handled.iterator();
            oldest.next();
            oldest.remove();
        }
    }

    /** 起動の時に読んだ ONLINE の要求がその後に消えた場合、UNARMED のまま残さない。 */
    private void armIfUnarmed() {
        if (gate.phase() == BackupWriteGate.State.UNARMED) {
            gate.armOpen();
        }
    }

    private void start(RequestView request, long now) {
        log.info("errorCode=BACKUP_QUIESCE_REQUESTED backup の静止要求を読みました（番号 {}、{}、{}）", request.id(),
                request.mode(), request.jobKind());
        work = new Work(request, now);
        if (gate.mode() == BackupWriteGate.Mode.DISABLED) {
            beginRefuse("DISABLED");
            return;
        }
        if (request.protocolVersion() != BackupQuiesceSignalRepository.PROTOCOL_VERSION) {
            beginRefuse("UNSUPPORTED_PROTOCOL");
            return;
        }
        if (request.appStopped()) {
            gate.closeForStoppedHold(request.id());
            log.error("errorCode=BACKUP_QUIESCE_STOPPED_HOLD_SEEN 動いている instance が app の停止を確かめる backup の保持"
                    + "（番号 {}）を読みました。更新を止めます（ack は書きません）", request.id());
            work.step = Step.STOPPED_HOLD;
            work.nextPollNanos = now + properties.getPollInterval().toNanos();
            return;
        }
        if (!gate.beginClosing(request.id())) {
            log.warn("backup の静止要求（番号 {}）を受けられない gate の状態です（{}）", request.id(), gate.state());
            work = null;
            return;
        }
        work.drainDeadlineNanos = now + properties.drainTimeout(request.full()).toNanos();
        work.nextPollNanos = now + properties.getPollInterval().toNanos();
        work.step = Step.DRAINING;
    }

    private void draining(long now) {
        if (gate.isDrained()) {
            work.drainedNanos = now;
            work.step = Step.READ_HOLD;
            readHold(now);
            return;
        }
        if (now - work.drainDeadlineNanos >= 0) {
            log.warn("errorCode=BACKUP_QUIESCE_REFUSED 排水が上限（{} 秒）までに終わらないため、backup の静止を拒否します"
                            + "（番号 {}、待っていた処理: {}、書込み可能な業務の接続: {}）",
                    properties.drainTimeout(work.request.full()).getSeconds(), work.id(), gate.describePermitHolders(),
                    gate.writableConnectionCount());
            beginRefuse("DRAIN_TIMEOUT");
            refuse(now);
            return;
        }
        if (now - work.nextPollNanos >= 0) {
            work.nextPollNanos = now + properties.getPollInterval().toNanos();
            RequestView current;
            try {
                current = repository().findRequest(work.id()).orElse(null);
            } catch (RuntimeException failure) {
                return;
            }
            if (current == null || !current.active()) {
                endedBeforeAck(current);
            }
        }
    }

    private void readHold(long now) {
        RequestView current;
        try {
            current = repository().findRequest(work.id()).orElse(null);
        } catch (RuntimeException failure) {
            failedBeforeAck(now, failure);
            return;
        }
        long after = gate.nanoTime();
        if (current == null || !current.active() || current.maxHoldReached() || current.holdRemainingMicros() <= 0
                || current.holdUntil() == null) {
            endedBeforeAck(current);
            return;
        }
        // 手元の期限 = 文が返った後の単調時計 + (H − 文の中の UTC_TIMESTAMP(6))（§3.3。遅い側にずれる）
        if (!gate.extendLocalDeadline(after + current.holdRemainingMicros() * 1_000L)) {
            endedBeforeAck(current);
            return;
        }
        work.holdUntil = current.holdUntil();
        work.holdCapped = current.holdCapped();
        work.step = Step.OBSERVE;
    }

    private void observe(long now) {
        try {
            work.observation = repository().observeAppHosts();
            work.step = Step.INSERT_QUIESCED;
        } catch (RuntimeException failure) {
            failedBeforeAck(now, failure);
        }
    }

    private void insertQuiesced(long now) {
        if (!gate.isDrained()) {
            work.step = Step.DRAINING;
            return;
        }
        if (!gate.markQuiescedAttempt(work.id())) {
            endedBeforeAck(null);
            return;
        }
        int drainMillis = drainMillis();
        try {
            repository().insertQuiescedAck(work.id(), anchor.instanceId(), anchor.schedulerState(), drainMillis,
                    work.holdUntil, work.observation);
        } catch (RuntimeException failure) {
            work.step = Step.RESOLVE_UNKNOWN;
            log.warn("errorCode=BACKUP_QUIESCE_ACK_UNKNOWN backup の静止の応答（QUIESCED）の書込みの結果が不明のため、閉じたまま"
                    + "確かめます（番号 {}、{}）", work.id(), failure.getClass().getSimpleName());
            metrics.requestResult(work.jobKind(), "ack_unknown");
            return;
        }
        acked(now, drainMillis);
    }

    private void acked(long now, int drainMillis) {
        work.writtenHoldUntil = work.holdUntil;
        work.step = Step.QUIESCED;
        work.nextPollNanos = now + properties.getPollInterval().toNanos();
        work.nextObserveNanos = now + properties.getHeartbeatInterval().toNanos();
        log.info("errorCode=BACKUP_QUIESCE_ACKED 排水を終えて backup の静止に応答しました（番号 {}、排水 {} ms、hold 期限 {} UTC）",
                work.id(), drainMillis, work.holdUntil);
        metrics.requestResult(work.jobKind(), "acked");
        metrics.drain(work.jobKind(), Duration.ofMillis(drainMillis));
    }

    private int drainMillis() {
        long drained = work.drainedNanos < 0 ? gate.nanoTime() : work.drainedNanos;
        return (int) Math.min(Integer.MAX_VALUE, Duration.ofNanos(drained - work.startedNanos).toMillis());
    }

    /** QUIESCED を試みる前の失敗: 排水の上限を過ぎていれば拒否し（試していないので開いてよい）、そうでなければ次の tick で繰り返す。 */
    private void failedBeforeAck(long now, RuntimeException failure) {
        log.debug("backup の静止の応答の準備で失敗しました（番号 {}）", work.id(), failure);
        if (now - work.drainDeadlineNanos >= 0) {
            beginRefuse("ERROR");
        }
    }

    /** QUIESCED を試みる前に要求が終わった・期限に達した: 開く（I-3 の対象外）。 */
    private void endedBeforeAck(RequestView current) {
        if (current != null && "NEW_INSTANCE".equals(current.releaseReason())) {
            log.warn("errorCode=BACKUP_QUIESCE_FULL_ABORTED full の最中に新しい instance が登録されたため、backup が静止を"
                    + "解きました（番号 {}）。その日の full は取り直します", work.id());
        }
        gate.reopen(work.id());
        log.info("backup の静止要求（番号 {}）が応答の前に終わったため、更新を再開しました", work.id());
        metrics.resumed("request_gone");
        finish();
    }

    private void resolveUnknown(long now) {
        if (gate.localDeadlineReached()) {
            reopenAtDeadline(work.holdCapped ? "MAX_HOLD" : "LEASE_EXPIRED");
            return;
        }
        try {
            repository().insertRefusedAck(work.id(), anchor.instanceId(), anchor.schedulerState(), drainMillis(),
                    "ERROR", work.observation);
        } catch (DuplicateKeyException duplicate) {
            work.step = Step.READ_ACK_STATE;
            return;
        } catch (RuntimeException failure) {
            // lock 待ちの timeout（5 秒）を含め、次の tick で繰り返す。手元の期限に達したら開く
            log.debug("REFUSED の書込みに失敗しました（番号 {}）", work.id(), failure);
            return;
        }
        // REFUSED の INSERT が成功した = QUIESCED の行は確定していない（I-3 (a)）
        gate.confirmNonQuiesced(work.id());
        log.warn("errorCode=BACKUP_QUIESCE_REFUSED backup の静止の応答の結果が不明だったため、拒否（REFUSED）を確定して更新を"
                + "再開しました（番号 {}）", work.id());
        metrics.requestResult(work.jobKind(), "refused_error");
        reopenAndFinish(null, false);
    }

    private void readAckState(long now) {
        if (gate.localDeadlineReached()) {
            reopenAtDeadline(work.holdCapped ? "MAX_HOLD" : "LEASE_EXPIRED");
            return;
        }
        Optional<AckState> state;
        try {
            state = repository().findAckState(work.id(), anchor.instanceId());
        } catch (RuntimeException failure) {
            return;
        }
        if (state.isEmpty()) {
            work.step = Step.RESOLVE_UNKNOWN;
        } else if (state.get() == AckState.QUIESCED) {
            log.info("結果が不明だった backup の静止の応答は QUIESCED で確定していました（番号 {}）", work.id());
            acked(now, drainMillis());
        } else {
            gate.confirmNonQuiesced(work.id());
            reopenAndFinish(null, false);
        }
    }

    private void quiesced(long now) {
        if (gate.localDeadlineReached()) {
            reopenAtDeadline(work.holdCapped ? "MAX_HOLD" : "LEASE_EXPIRED");
            return;
        }
        if (work.pendingHoldUntil != null && now - work.nextExtendNanos >= 0) {
            work.step = Step.EXTEND_HOLD;
            extendHold(now);
            return;
        }
        if (now - work.nextPollNanos >= 0) {
            work.nextPollNanos = now + properties.getPollInterval().toNanos();
            followRequest();
            return;
        }
        if (work.observeDue || now - work.nextObserveNanos >= 0) {
            work.step = Step.OBSERVE_AGAIN;
            observeAgain(now);
        }
    }

    private void followRequest() {
        RequestView current;
        try {
            current = repository().findRequest(work.id()).orElse(null);
        } catch (RuntimeException failure) {
            return; // 読めなくても手元の期限で開く（I-3 (b)）
        }
        long after = gate.nanoTime();
        if (current == null) {
            beginResume("REQUEST_GONE");
            return;
        }
        if ("RELEASED".equals(current.status())) {
            beginResume("RELEASED");
            return;
        }
        if (!"REQUESTED".equals(current.status())) {
            if ("NEW_INSTANCE".equals(current.releaseReason())) {
                log.warn("errorCode=BACKUP_QUIESCE_FULL_ABORTED full の最中に新しい instance が登録されたため、backup が静止を"
                        + "解きました（番号 {}）。その日の full は取り直します", work.id());
            }
            beginResume("REQUEST_GONE");
            return;
        }
        if (!current.leaseValid()) {
            beginResume("LEASE_EXPIRED");
            return;
        }
        if (current.maxHoldReached()) {
            beginResume("MAX_HOLD");
            return;
        }
        if (current.holdUntil() != null && current.holdUntil().isAfter(work.holdUntil)) {
            // 先に手元の期限を延ばし、その後で DB へ書く（§3.3）
            if (!gate.extendLocalDeadline(after + current.holdRemainingMicros() * 1_000L)) {
                beginResume("LEASE_EXPIRED");
                return;
            }
            work.holdUntil = current.holdUntil();
            work.pendingHoldUntil = current.holdUntil();
        }
        work.holdCapped = current.holdCapped();
        LocalDateTime verify = current.verifyRequestedAt();
        if (verify != null && (work.lastVerifySeen == null || verify.isAfter(work.lastVerifySeen))) {
            work.lastVerifySeen = verify;
            work.observeDue = true;
        }
    }

    private void extendHold(long now) {
        LocalDateTime target = work.pendingHoldUntil;
        try {
            repository().updateHoldUntil(work.id(), anchor.instanceId(), target);
            work.writtenHoldUntil = target;
            if (target.equals(work.pendingHoldUntil)) {
                work.pendingHoldUntil = null;
            }
        } catch (RuntimeException failure) {
            // 手元の期限は先に延びているため、DB に確定し得る hold_until はそれより前にある。次の読取りの後に書き直す
            work.nextExtendNanos = now + properties.getPollInterval().toNanos();
            log.debug("hold 期限の延長を書けませんでした（番号 {}）", work.id(), failure);
        }
        work.step = Step.QUIESCED;
    }

    private void observeAgain(long now) {
        try {
            work.observation = repository().observeAppHosts();
            work.step = Step.WRITE_OBSERVATION;
        } catch (RuntimeException failure) {
            work.step = Step.QUIESCED;
            work.nextObserveNanos = now + properties.getHeartbeatInterval().toNanos();
        }
    }

    private void writeObservation(long now) {
        try {
            repository().updateObservation(work.id(), anchor.instanceId(), work.observation);
            work.observeDue = false;
        } catch (RuntimeException failure) {
            log.debug("接続元の観測を書けませんでした（番号 {}）", work.id(), failure);
        }
        work.nextObserveNanos = now + properties.getHeartbeatInterval().toNanos();
        work.step = Step.QUIESCED;
    }

    private void beginResume(String reason) {
        work.resumeReason = reason;
        work.step = Step.RESUME;
    }

    /** 手元の期限に達した（I-3 (b)）: gate を開き、RESUMED は後の tick で書く。 */
    private void reopenAtDeadline(String reason) {
        work.resumeReason = reason;
        if (!work.reopened) {
            work.reopened = gate.reopen(work.id());
            if (work.reopened) {
                log.error("errorCode=BACKUP_QUIESCE_AUTO_RESUMED backup の解除を確かめられないまま手元の期限に達したため、更新を"
                        + "再開しました（番号 {}、理由 {}）", work.id(), reason);
                metrics.resumed(reason);
                metrics.hold(work.jobKind(), Duration.ofNanos(gate.nanoTime() - work.startedNanos));
            }
        }
        work.step = Step.RESUME;
    }

    private void resume(long now) {
        int rows;
        try {
            rows = repository().markResumed(work.id(), anchor.instanceId(), work.resumeReason);
        } catch (RuntimeException failure) {
            if (!work.reopened && gate.localDeadlineReached()) {
                reopenAtDeadline(work.resumeReason);
            }
            if (work.reopened && ++work.resumeAttempts >= RESUME_ATTEMPTS_AFTER_DEADLINE) {
                log.warn("backup の静止の ack を RESUMED にできないまま再開を終えます（番号 {}）", work.id());
                finish();
            }
            return;
        }
        if (rows == 1) {
            gate.confirmNonQuiesced(work.id());
            reopenAndFinish(work.resumeReason, true);
        } else {
            work.step = Step.READ_ACK_FOR_RESUME;
        }
    }

    private void readAckForResume(long now) {
        Optional<AckState> state;
        try {
            state = repository().findAckState(work.id(), anchor.instanceId());
        } catch (RuntimeException failure) {
            work.step = Step.RESUME;
            return;
        }
        if (state.isPresent() && state.get() == AckState.QUIESCED) {
            work.step = Step.RESUME;
            return;
        }
        // RESUMED・REFUSED・行が無い（要求の削除で消えた）: この instance の QUIESCED は残っていない
        gate.confirmNonQuiesced(work.id());
        reopenAndFinish(work.resumeReason, true);
    }

    private void reopenAndFinish(String reason, boolean resumedWritten) {
        boolean opened = work.reopened || gate.reopen(work.id());
        if (!opened) {
            log.warn("backup の静止の解除の条件（I-3）を満たさないため、まだ開けません（番号 {}）", work.id());
            return;
        }
        if (!work.reopened) {
            Duration held = Duration.ofNanos(gate.nanoTime() - work.startedNanos);
            metrics.hold(work.jobKind(), held);
            if (reason == null) {
                // 拒否（REFUSED）の確定で開いた。log は呼び出し側が出した
                log.info("backup の静止の応答を拒否で確定したため、更新を再開しました（番号 {}、保持 {} 秒）", work.id(),
                        held.getSeconds());
            } else if ("RELEASED".equals(reason)) {
                log.info("errorCode=BACKUP_QUIESCE_RESUMED backup の静止の解除で更新を再開しました（番号 {}、保持 {} 秒、"
                                + "保留した非同期の処理 {} 件、解除後の書込み {} 件）", work.id(), held.getSeconds(),
                        gate.pendingTaskCount(), gate.deferredWrites().pending());
                metrics.resumed(reason);
            } else {
                log.error("errorCode=BACKUP_QUIESCE_AUTO_RESUMED backup の解除を待たずに更新を再開しました（番号 {}、理由 {}、"
                        + "保持 {} 秒）", work.id(), reason, held.getSeconds());
                metrics.resumed(reason);
            }
        }
        if (!resumedWritten) {
            log.debug("ack を RESUMED にせずに再開しました（番号 {}）", work.id());
        }
        finish();
    }

    private void beginRefuse(String reason) {
        work.refuseReason = reason;
        work.step = Step.REFUSE;
    }

    /** QUIESCED を試していない要求の拒否: REFUSED を1回試み、成否によらず開く（§6.9 の2）。 */
    private void refuse(long now) {
        try {
            repository().insertRefusedAck(work.id(), anchor.instanceId(), anchor.schedulerState(),
                    "DRAIN_TIMEOUT".equals(work.refuseReason) || "ERROR".equals(work.refuseReason) ? drainMillis() : 0,
                    work.refuseReason,
                    null);
        } catch (RuntimeException failure) {
            log.warn("backup の静止の拒否（REFUSED）を書けませんでした（番号 {}、{}）", work.id(),
                    failure.getClass().getSimpleName());
        }
        if ("DISABLED".equals(work.refuseReason)) {
            log.warn("errorCode=BACKUP_QUIESCE_REFUSED 停止口のため backup の静止を拒否しました（番号 {}、REFUSED（DISABLED））",
                    work.id());
        } else if ("UNSUPPORTED_PROTOCOL".equals(work.refuseReason)) {
            log.warn("errorCode=BACKUP_QUIESCE_REFUSED protocol の版が違うため backup の静止を拒否しました（番号 {}、版 {}）",
                    work.id(), work.request.protocolVersion());
        }
        metrics.requestResult(work.jobKind(), "refused_" + work.refuseReason.toLowerCase(Locale.ROOT));
        if (gate.holdingRequestId() == work.id()) {
            gate.reopen(work.id());
        }
        finish();
    }

    private void stoppedHold(long now) {
        if (now - work.nextPollNanos < 0) {
            return;
        }
        work.nextPollNanos = now + properties.getPollInterval().toNanos();
        RequestView current;
        try {
            current = repository().findRequest(work.id()).orElse(null);
        } catch (RuntimeException failure) {
            return;
        }
        if (current == null || !current.active()) {
            gate.reopen(work.id());
            log.warn("app の停止を確かめる backup の保持（番号 {}）が終わったため、更新を再開しました", work.id());
            finish();
        }
    }

    private void finish() {
        work = null;
        nextPollNanos = gate.nanoTime();
    }

    // ---------------------------------------------------------------- 保存期間（1日1回。§3.9）

    private void retention(long now) {
        Duration retention = properties.getRetention();
        try {
            if (retentionStep == 0) {
                int requests = repository().purgeRequests(retention);
                retentionStep = 1;
                if (requests > 0) {
                    log.info("保存期間（{} 日）を過ぎた backup の静止要求を {} 件消しました", retention.toDays(), requests);
                }
                return;
            }
            int instances = repository().purgeInstances(retention, anchor.instanceId());
            if (instances > 0) {
                log.info("保存期間（{} 日）を過ぎた backup の静止応答の instance を {} 件消しました", retention.toDays(), instances);
            }
            retentionStep = 0;
            nextRetentionNanos = now + RETENTION_INTERVAL.toNanos();
        } catch (RuntimeException failure) {
            log.warn("backup の静止の信号表の保存期間の削除に失敗しました（{}）。1 時間後に繰り返します",
                    failure.getClass().getSimpleName());
            retentionStep = 0;
            nextRetentionNanos = now + RETENTION_RETRY.toNanos();
        }
    }

    // ---------------------------------------------------------------- 解除の後の投入（塞がない）

    /**
     * {@code taskExecutor} へ投入する。executor の queue が満杯なら投入しない（{@code CallerRunsPolicy} で状態機械の thread が
     * task を実行して塞がれないため）。受け付けなかった task は保留のまま次の tick で投入し直す。
     */
    boolean tryDispatch(Runnable task) {
        Executor executor = taskExecutor.get();
        if (executor == null) {
            return false;
        }
        if (executor instanceof ThreadPoolTaskExecutor pool) {
            ThreadPoolExecutor threads;
            try {
                threads = pool.getThreadPoolExecutor();
            } catch (IllegalStateException notInitialized) {
                return false;
            }
            if (threads.isShutdown() || threads.getQueue().remainingCapacity() == 0) {
                return false;
            }
        }
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException rejected) {
            return false;
        }
    }

    private void scheduleDeferredFlush() {
        BackupQuiesceDeferredWrites writes = gate.deferredWrites();
        if (writes.pending() == 0 || !flushInFlight.compareAndSet(false, true)) {
            return;
        }
        boolean dispatched = tryDispatch(() -> {
            try {
                writes.flush(gate);
            } finally {
                flushInFlight.set(false);
            }
        });
        if (!dispatched) {
            flushInFlight.set(false);
        }
    }
}
