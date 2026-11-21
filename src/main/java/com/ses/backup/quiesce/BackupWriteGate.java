package com.ses.backup.quiesce;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * backup の書込み静止の gate（design.md §6.1・§6.2・§6.5.2・§6.5.3・§6.7）。Spring にも DB にも依らない。
 *
 * <p>状態は1つの object が持ち、静止応答の状態機械の thread（{@code BackupQuiesceResponder}）だけが遷移させる。
 * FENCED（heartbeat を {@code fence-after} の間書けない）と手元の期限の到達は、gate を参照する側が毎回評価する（遅延評価）。
 * FENCED は他の判定より優先する。
 *
 * <p>不変条件:
 * <ul>
 *   <li><b>I-4（数え方）</b>: permit と書込み可能な業務の接続の数は、増やす側が「先に数を増やしてから状態と permit を読み、
 *       閉じていれば戻す」。gate の側は「先に状態を CLOSING にしてから数を読み、CLOSED にする前にもう一度読む」。状態は
 *       volatile、数は atomic で持ち、順序が一貫する（Java の volatile・atomic の操作は全体で1つの順序を持つ）。</li>
 *   <li><b>I-3（開け直しの規則）</b>: 一度 QUIESCED の ack を書こうとした要求は、この instance が非 QUIESCED を確定した
 *       （{@link #confirmNonQuiesced}）か、手元の期限に達した場合だけ開く。手元の期限は延ばすだけで縮めない
 *       （{@link #extendLocalDeadline}）。期限に達したと一度でも判定した後は延ばせない（参照する側が開いたと見なした後に
 *       閉じ直さないため）。</li>
 * </ul>
 * UNARMED（登録の前）は prod の profile では閉じた状態と同じに扱い、prod 以外では書ける（D-P3-3）。
 */
public final class BackupWriteGate {

    private static final Logger log = LoggerFactory.getLogger(BackupWriteGate.class);

    /** gate の状態（instance の行の {@code gate_state}、metric {@code ses_backup_quiesce_gate_state} の値）。 */
    public enum State {
        UNARMED(0), OPEN(1), CLOSING(2), CLOSED(3), FENCED(4), SHUTDOWN(5), DISABLED(6);

        private final int code;

        State(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    /** gate の働き方（§6.2・§12.2）。 */
    public enum Mode {
        /** prod 以外の {@code enabled=false}（test profile 等）。登録も heartbeat もせず、gate は常に OPEN。 */
        INERT,
        /** {@code enabled=true}。UNARMED から始まり、静止応答が開閉する。 */
        ARMED,
        /** prod の {@code enabled=false}（停止口。U-7）。登録と heartbeat は続け、gate は閉じない。 */
        DISABLED
    }

    private static final long UNSET = Long.MIN_VALUE;
    private static final long EXPIRED = Long.MIN_VALUE + 1;
    private static final int HOLDER_LOG_LIMIT = 20;

    private final Mode mode;
    private final boolean prodProfile;
    private final LongSupplier nanoClock;
    private final Clock wallClock;
    private final long fenceAfterNanos;
    private final BackupQuiesceDeferredWrites deferredWrites;

    private final Object monitor = new Object();
    private volatile State phase;
    private volatile Hold hold;
    private volatile Instant closedAt;
    private volatile long lastBeatNanos;
    private final AtomicInteger permits = new AtomicInteger();
    private final AtomicInteger writableConnections = new AtomicInteger();
    private final AtomicLong localDeadline = new AtomicLong(UNSET);
    private final AtomicLong permitSequence = new AtomicLong();
    private final AtomicLong heartbeatFailures = new AtomicLong();
    private final ConcurrentHashMap<Long, BackupWritePermit> holders = new ConcurrentHashMap<>();
    private final ThreadLocal<ArrayDeque<BackupWritePermit>> bound = ThreadLocal.withInitial(ArrayDeque::new);
    private final Deque<Runnable> pendingTasks = new ArrayDeque<>();
    private final CopyOnWriteArrayList<Runnable> openListeners = new CopyOnWriteArrayList<>();
    private volatile Predicate<Runnable> dispatcher;

    /** 静止の対象の1つの要求について、gate が覚えること（I-3）。 */
    private static final class Hold {
        final long requestId;
        final boolean stoppedHold;
        volatile boolean quiescedAttempted;
        volatile boolean nonQuiescedConfirmed;

        Hold(long requestId, boolean stoppedHold) {
            this.requestId = requestId;
            this.stoppedHold = stoppedHold;
        }
    }

    public BackupWriteGate(Mode mode, boolean prodProfile, Duration fenceAfter, int deferredWritesMax) {
        this(mode, prodProfile, fenceAfter, deferredWritesMax, System::nanoTime, Clock.systemUTC());
    }

    BackupWriteGate(Mode mode, boolean prodProfile, Duration fenceAfter, int deferredWritesMax,
                    LongSupplier nanoClock, Clock wallClock) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.prodProfile = prodProfile;
        this.fenceAfterNanos = fenceAfter.toNanos();
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
        this.deferredWrites = new BackupQuiesceDeferredWrites(deferredWritesMax);
        this.lastBeatNanos = nanoClock.getAsLong();
        this.phase = switch (mode) {
            case INERT -> State.OPEN;
            case ARMED -> State.UNARMED;
            case DISABLED -> State.DISABLED;
        };
    }

    // ---------------------------------------------------------------- 状態（遅延評価）

    public Mode mode() {
        return mode;
    }

    public boolean isProdProfile() {
        return prodProfile;
    }

    /** 参照する側から見た今の状態。FENCED と手元の期限の到達をこの時点で評価する。 */
    public State state() {
        State current = phase;
        if (current == State.SHUTDOWN || current == State.DISABLED || current == State.UNARMED) {
            return current;
        }
        if (fencedNow()) {
            return State.FENCED;
        }
        if (current == State.CLOSED && localDeadlineReached()) {
            return State.OPEN;
        }
        return current;
    }

    /** 遷移の上の状態（遅延評価を含まない。状態機械と診断用）。 */
    State phase() {
        return phase;
    }

    /** permit の無い thread に書込みを許すか（OPEN・DISABLED、または prod 以外の UNARMED）。 */
    public boolean allowsNewPermits() {
        State current = state();
        return current == State.OPEN || current == State.DISABLED || (current == State.UNARMED && !prodProfile);
    }

    private boolean fencedNow() {
        return mode == Mode.ARMED && nanoClock.getAsLong() - lastBeatNanos > fenceAfterNanos;
    }

    /** heartbeat を書けていない（{@code fence-after} を超えた）か。 */
    public boolean isFenced() {
        State current = phase;
        return current != State.UNARMED && current != State.SHUTDOWN && fencedNow();
    }

    /** gate が最後に OPEN から閉じた時刻（CLOSING・CLOSED・FENCED の間だけ。開いていれば null）。 */
    public Instant closedSince() {
        State current = state();
        if (current == State.CLOSING || current == State.CLOSED) {
            return closedAt;
        }
        if (current == State.FENCED) {
            Instant fencedAt = wallClock.instant().minusNanos(Math.max(0, heartbeatAgeNanos() - fenceAfterNanos));
            Instant closed = closedAt;
            return closed != null && closed.isBefore(fencedAt) ? closed : fencedAt;
        }
        return null;
    }

    // ---------------------------------------------------------------- permit（I-4）

    /**
     * thread に結び付けた permit を取る。この thread が既に permit を持てば、入れ子の permit を待たずに返す。
     * 持たなければ先に数を増やしてから状態を読み、閉じていれば数を戻して null を返す。
     */
    public BackupWritePermit tryAcquire(BackupWritePermit.Kind kind, String owner) {
        ArrayDeque<BackupWritePermit> stack = boundStack();
        if (!stack.isEmpty()) {
            BackupWritePermit nested = new BackupWritePermit(this, permitSequence.incrementAndGet(), kind,
                    owner, nanoClock.getAsLong(), false);
            nested.bind(Thread.currentThread());
            stack.push(nested);
            return nested;
        }
        BackupWritePermit permit = count(kind, owner);
        if (!allowsNewPermits()) {
            uncount(permit);
            return null;
        }
        permit.bind(Thread.currentThread());
        stack.push(permit);
        return permit;
    }

    /**
     * 投入する thread で数え、実行する thread が引き継ぐ permit（async。§6.7）。投入する thread が permit を持てば閉じていても
     * 数える（その thread の permit が数えられている間は排水が終わらないため）。持たなければ I-4 の順で判定する。
     */
    public BackupWritePermit tryAcquireDetached(BackupWritePermit.Kind kind, String owner) {
        boolean holder = currentThreadHoldsPermit();
        BackupWritePermit permit = count(kind, owner);
        if (holder || allowsNewPermits()) {
            return permit;
        }
        uncount(permit);
        return null;
    }

    /**
     * 登録の前（UNARMED）に静止応答が取る startup permit（§6.8 の「有効な要求が無い」場合）。状態機械の thread を始める前で、
     * gate を変える者が他にいない時だけ呼ぶ。
     */
    BackupWritePermit acquireStartupPermitWhileUnarmed(String owner) {
        if (phase != State.UNARMED) {
            throw new IllegalStateException("startup permit は登録の前にだけ無条件に取れます: " + phase);
        }
        BackupWritePermit permit = count(BackupWritePermit.Kind.STARTUP, owner);
        permit.bind(Thread.currentThread());
        boundStack().push(permit);
        return permit;
    }

    public boolean currentThreadHoldsPermit() {
        return !boundStack().isEmpty();
    }

    /** 呼んだ thread が持つ permit を全て返す（停止の入口。§6.11）。 */
    public int releaseCurrentThreadPermits() {
        List<BackupWritePermit> held = new ArrayList<>(bound.get());
        for (BackupWritePermit permit : held) {
            permit.close();
        }
        bound.remove();
        return held.size();
    }

    void attach(BackupWritePermit permit) {
        if (permit.isClosed()) {
            throw new IllegalStateException("返した permit は引き継げません: " + permit);
        }
        permit.bind(Thread.currentThread());
        boundStack().push(permit);
    }

    void release(BackupWritePermit permit) {
        if (permit.boundThread() == Thread.currentThread()) {
            bound.get().remove(permit);
        }
        if (permit.isCounted()) {
            holders.remove(permit.id());
            permits.decrementAndGet();
        }
    }

    private BackupWritePermit count(BackupWritePermit.Kind kind, String owner) {
        // I-4: 先に数を増やす。状態はこの後で読む
        permits.incrementAndGet();
        BackupWritePermit permit = new BackupWritePermit(this, permitSequence.incrementAndGet(), kind,
                owner == null ? "unknown" : owner, nanoClock.getAsLong(), true);
        holders.put(permit.id(), permit);
        return permit;
    }

    private void uncount(BackupWritePermit permit) {
        holders.remove(permit.id());
        permits.decrementAndGet();
    }

    private ArrayDeque<BackupWritePermit> boundStack() {
        ArrayDeque<BackupWritePermit> stack = bound.get();
        if (!stack.isEmpty()) {
            stack.removeIf(BackupWritePermit::isClosed);
        }
        return stack;
    }

    // ---------------------------------------------------------------- 書込み可能な業務の接続（§6.5.2）

    /**
     * 業務の接続を書込み可能として数える（借りる時・{@code setReadOnly(false)} の時）。先に数を増やしてから状態と thread の
     * permit を読み、閉じていて permit も無ければ数を戻して false を返す（呼び出し側は接続を読取り専用にする）。
     */
    public boolean tryRegisterWritableConnection() {
        writableConnections.incrementAndGet();
        if (allowsNewPermits() || currentThreadHoldsPermit()) {
            return true;
        }
        writableConnections.decrementAndGet();
        return false;
    }

    /** 書込み可能として数えた接続を数から外す（返す時、または autocommit の間の {@code setReadOnly(true)} の成功）。 */
    public void releaseWritableConnection() {
        writableConnections.decrementAndGet();
    }

    public int permitCount() {
        return permits.get();
    }

    public int writableConnectionCount() {
        return writableConnections.get();
    }

    /** 排水が終わったか（permit 0 かつ書込み可能な接続 0 を2回読む。I-4 の gate の側）。 */
    public boolean isDrained() {
        if (permits.get() != 0 || writableConnections.get() != 0) {
            return false;
        }
        return permits.get() == 0 && writableConnections.get() == 0;
    }

    /** 最も古い permit の保持時間（kind ごと。metric {@code ses_backup_quiesce_oldest_permit_seconds}）。 */
    public OptionalLong oldestPermitAgeNanos(BackupWritePermit.Kind kind) {
        long now = nanoClock.getAsLong();
        return holders.values().stream().filter(permit -> permit.kind() == kind)
                .mapToLong(permit -> now - permit.acquiredAtNanos()).max();
    }

    /** 保持中の permit の持ち主と保持の時間（REFUSED の log 用。古い順に最大20件）。 */
    public List<String> describePermitHolders() {
        long now = nanoClock.getAsLong();
        List<String> lines = new ArrayList<>();
        holders.values().stream().sorted(Comparator.comparingLong(BackupWritePermit::acquiredAtNanos))
                .limit(HOLDER_LOG_LIMIT).forEach(permit -> {
                    Thread thread = permit.boundThread();
                    lines.add(permit.kind().label() + ":" + permit.owner() + "@"
                            + (thread == null ? "(未実行)" : thread.getName()) + "="
                            + Duration.ofNanos(now - permit.acquiredAtNanos()).toMillis() + "ms");
                });
        return lines;
    }

    // ---------------------------------------------------------------- 手元の期限（I-3 (b)）

    /**
     * 手元の期限を {@code deadlineNanos}（gate の単調時計）まで延ばす。縮めない。既に期限に達した後は延ばさず false を返す。
     */
    public boolean extendLocalDeadline(long deadlineNanos) {
        while (true) {
            long current = localDeadline.get();
            if (current == EXPIRED) {
                return false;
            }
            if (current != UNSET) {
                if (nanoClock.getAsLong() - current >= 0) {
                    if (localDeadline.compareAndSet(current, EXPIRED)) {
                        return false;
                    }
                    continue;
                }
                if (deadlineNanos - current <= 0) {
                    return true;
                }
            }
            if (localDeadline.compareAndSet(current, deadlineNanos)) {
                return true;
            }
        }
    }

    /** 手元の期限に達したか。達したと判定したら以後は延ばせない印を付ける。期限が未設定なら false。 */
    public boolean localDeadlineReached() {
        while (true) {
            long current = localDeadline.get();
            if (current == EXPIRED) {
                return true;
            }
            if (current == UNSET || nanoClock.getAsLong() - current < 0) {
                return false;
            }
            if (localDeadline.compareAndSet(current, EXPIRED)) {
                return true;
            }
        }
    }

    /** 手元の期限までの残り（未設定・到達済みなら空）。503 の {@code Retry-After} に使う。 */
    public OptionalLong remainingLocalDeadlineNanos() {
        long current = localDeadline.get();
        if (current == UNSET || current == EXPIRED) {
            return OptionalLong.empty();
        }
        long remaining = current - nanoClock.getAsLong();
        return remaining > 0 ? OptionalLong.of(remaining) : OptionalLong.empty();
    }

    // ---------------------------------------------------------------- 遷移（状態機械の thread だけが呼ぶ）

    /** 登録の後、有効な要求が無い: UNARMED → OPEN。 */
    void armOpen() {
        synchronized (monitor) {
            if (phase != State.UNARMED) {
                return;
            }
            phase = State.OPEN;
            monitor.notifyAll();
        }
        afterOpened();
    }

    /**
     * 要求を読んだ: OPEN（または UNARMED）→ CLOSING。この後、新しい permit は出ず、permit の無い thread の業務の接続は
     * 読取り専用になる。
     */
    boolean beginClosing(long requestId) {
        synchronized (monitor) {
            if (mode != Mode.ARMED || (phase != State.OPEN && phase != State.UNARMED)) {
                return false;
            }
            hold = new Hold(requestId, false);
            localDeadline.set(UNSET);
            closedAt = wallClock.instant();
            // I-4: 先に状態を CLOSING にする。数はこの後で読む（isDrained）
            phase = State.CLOSING;
            monitor.notifyAll();
            return true;
        }
    }

    /**
     * 動いている instance が APP_STOPPED の保持を読んだ: gate を閉じる（FENCED と同じ扱い。ack は書かない。§9.3）。
     * 手元の期限を持たず、要求の消失を読んで状態機械が開ける。
     */
    boolean closeForStoppedHold(long requestId) {
        synchronized (monitor) {
            if (mode != Mode.ARMED || (phase != State.OPEN && phase != State.UNARMED)) {
                return false;
            }
            hold = new Hold(requestId, true);
            localDeadline.set(UNSET);
            closedAt = wallClock.instant();
            phase = State.CLOSED;
            monitor.notifyAll();
            return true;
        }
    }

    /**
     * QUIESCED の INSERT を試みる直前に呼ぶ: CLOSING → CLOSED。手元の期限が設定されていなければ（または到達していれば）
     * 試みてはならず false を返す。この時点から I-3 が効く。
     */
    boolean markQuiescedAttempt(long requestId) {
        synchronized (monitor) {
            Hold current = hold;
            if (current == null || current.requestId != requestId || phase != State.CLOSING) {
                return false;
            }
            long deadline = localDeadline.get();
            if (deadline == UNSET || deadline == EXPIRED || localDeadlineReached()) {
                return false;
            }
            current.quiescedAttempted = true;
            phase = State.CLOSED;
            return true;
        }
    }

    /** この instance がその要求について非 QUIESCED の状態を確定した（RESUMED が1行に効いた・REFUSED が成功した）。 */
    void confirmNonQuiesced(long requestId) {
        Hold current = hold;
        if (current != null && current.requestId == requestId) {
            current.nonQuiescedConfirmed = true;
        }
    }

    /** その要求について QUIESCED を試みたか。 */
    boolean quiescedAttempted(long requestId) {
        Hold current = hold;
        return current != null && current.requestId == requestId && current.quiescedAttempted;
    }

    /** 今閉じている要求の番号（開いていれば -1）。 */
    long holdingRequestId() {
        Hold current = hold;
        return current == null ? -1 : current.requestId;
    }

    /**
     * 開け直す（I-3）。QUIESCED を試した要求は、非 QUIESCED の確定か手元の期限の到達がなければ開かず false を返す。
     * SHUTDOWN では開かない。
     */
    boolean reopen(long requestId) {
        synchronized (monitor) {
            Hold current = hold;
            if (current == null || current.requestId != requestId) {
                return phase == State.OPEN;
            }
            if (phase == State.SHUTDOWN || phase == State.DISABLED) {
                return false;
            }
            if (current.quiescedAttempted && !current.nonQuiescedConfirmed && !localDeadlineReached()) {
                return false;
            }
            hold = null;
            localDeadline.set(UNSET);
            closedAt = null;
            phase = State.OPEN;
            monitor.notifyAll();
        }
        afterOpened();
        return true;
    }

    /** 停止の開始（ContextClosedEvent、または業務の DataSource の破棄の開始）: → SHUTDOWN（戻らない）。INERT では変えない。 */
    public void shutdown() {
        int dropped;
        synchronized (monitor) {
            if (mode == Mode.INERT || phase == State.SHUTDOWN) {
                return;
            }
            phase = State.SHUTDOWN;
            synchronized (pendingTasks) {
                dropped = pendingTasks.size();
                pendingTasks.clear();
            }
            monitor.notifyAll();
        }
        if (dropped > 0) {
            log.warn("停止のため、backup の静止中に保留した非同期の処理{}件を実行せずに捨てました", dropped);
        }
    }

    /** gate が書込みを許す（OPEN 等）まで最長 {@code max} 待つ。SHUTDOWN・割込みでは直ちに戻る。 */
    public boolean awaitWritable(Duration max) throws InterruptedException {
        long deadline = nanoClock.getAsLong() + max.toNanos();
        synchronized (monitor) {
            while (true) {
                if (allowsNewPermits()) {
                    return true;
                }
                if (phase == State.SHUTDOWN) {
                    return false;
                }
                long remaining = deadline - nanoClock.getAsLong();
                if (remaining <= 0) {
                    return false;
                }
                // 手元の期限・heartbeat の回復は通知されないため、短い間隔で見直す
                long waitMillis = Math.max(1, Math.min(Duration.ofNanos(remaining).toMillis(),
                        BackupQuiesceProperties.TICK.toMillis()));
                monitor.wait(waitMillis);
            }
        }
    }

    // ---------------------------------------------------------------- heartbeat・self-fence

    /** heartbeat を書けた（または登録できた）。FENCED から戻ったら true。 */
    public boolean recordHeartbeatSuccess() {
        boolean wasFenced = isFenced();
        lastBeatNanos = nanoClock.getAsLong();
        return wasFenced;
    }

    /** heartbeat を書けなかった。今 FENCED なら true。 */
    public boolean recordHeartbeatFailure() {
        heartbeatFailures.incrementAndGet();
        return isFenced();
    }

    public long heartbeatFailureCount() {
        return heartbeatFailures.get();
    }

    /** 最後に heartbeat を書けてからの経過（metric {@code ses_backup_quiesce_heartbeat_age_seconds}）。 */
    public long heartbeatAgeNanos() {
        return nanoClock.getAsLong() - lastBeatNanos;
    }

    long nanoTime() {
        return nanoClock.getAsLong();
    }

    // ---------------------------------------------------------------- 閉じた後の async の保留一覧・解除後の書込み

    public BackupQuiesceDeferredWrites deferredWrites() {
        return deferredWrites;
    }

    /**
     * 閉じた後に permit の無い thread が投入した task を保留する（§6.7。待たずに戻る）。gate が開いた時に
     * {@link #setDispatcher} の dispatcher で executor へ投入し直す。保留の直後に開いていれば直ちに投入する。
     */
    public void deferUntilOpen(Runnable task) {
        Objects.requireNonNull(task, "task");
        synchronized (pendingTasks) {
            if (phase == State.SHUTDOWN) {
                log.warn("停止中のため、backup の静止で保留する非同期の処理を実行せずに捨てました");
                return;
            }
            pendingTasks.addLast(task);
        }
        if (allowsNewPermits()) {
            dispatchPending();
        }
    }

    public int pendingTaskCount() {
        synchronized (pendingTasks) {
            return pendingTasks.size();
        }
    }

    /**
     * 保留した task を executor へ投入し直す dispatcher。task を受け付けたら true、受け付けられない（executor が満杯等）なら
     * false を返し、その task と残りは保留のまま次の機会に回す。状態機械の thread を塞がないこと。
     */
    public void setDispatcher(Predicate<Runnable> dispatcher) {
        this.dispatcher = dispatcher;
        if (allowsNewPermits()) {
            dispatchPending();
        }
    }

    /** 開いていれば保留した task を投入し直す。投入した件数を返す。 */
    public int dispatchPending() {
        Predicate<Runnable> current = dispatcher;
        if (current == null) {
            return 0;
        }
        int dispatched = 0;
        while (allowsNewPermits()) {
            Runnable next;
            synchronized (pendingTasks) {
                next = pendingTasks.pollFirst();
            }
            if (next == null) {
                break;
            }
            boolean accepted;
            try {
                accepted = current.test(next);
            } catch (RuntimeException failure) {
                log.warn("保留した非同期の処理を投入し直せませんでした（{}）。次の機会に投入します", failure.getClass().getSimpleName());
                accepted = false;
            }
            if (!accepted) {
                synchronized (pendingTasks) {
                    pendingTasks.addFirst(next);
                }
                break;
            }
            dispatched++;
        }
        return dispatched;
    }

    /** gate が開いた時（UNARMED → OPEN、閉じた後の OPEN）に呼ぶ callback。塞がない処理だけを登録する。 */
    public void addOpenListener(Runnable listener) {
        openListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    private void afterOpened() {
        dispatchPending();
        for (Runnable listener : openListeners) {
            try {
                listener.run();
            } catch (RuntimeException failure) {
                log.warn("backup の静止の解除の後処理に失敗しました（{}）", failure.getClass().getSimpleName());
            }
        }
    }

    @Override
    public String toString() {
        return "BackupWriteGate[" + mode + ", " + state() + ", permits=" + permits.get() + ", writable="
                + writableConnections.get() + "]";
    }
}
