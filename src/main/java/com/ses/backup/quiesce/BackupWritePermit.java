package com.ses.backup.quiesce;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 閉じる前に始まった書込み（HTTP の更新・job・async・起動時処理・解除後の書込み）を表す印（design.md §6.5.3）。
 *
 * <p>{@link BackupWriteGate} が発行する。thread に結び付け（入れ子を許す）、取得の時刻と持ち主を記録する。
 * 数えた permit（{@link #isCounted()}）が1つでも残っている間、gate の排水は終わらない。入れ子の permit は数えず、
 * 外側の permit が返されるまで排水を止め続ける。{@link #close()} は何度呼んでも1回だけ効く。
 */
public final class BackupWritePermit implements AutoCloseable {

    /** permit の種類（§6.5.3。metric の label にも使う）。 */
    public enum Kind {
        HTTP, SCHEDULER, ASYNC, STARTUP, DEFERRED_WRITE;

        /** metric の label の値（小文字）。 */
        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final BackupWriteGate gate;
    private final long id;
    private final Kind kind;
    private final String owner;
    private final long acquiredAtNanos;
    private final boolean counted;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Thread boundThread;

    BackupWritePermit(BackupWriteGate gate, long id, Kind kind, String owner, long acquiredAtNanos, boolean counted) {
        this.gate = gate;
        this.id = id;
        this.kind = kind;
        this.owner = owner;
        this.acquiredAtNanos = acquiredAtNanos;
        this.counted = counted;
    }

    long id() {
        return id;
    }

    public Kind kind() {
        return kind;
    }

    /** 持ち主（request の method と URI・job 名・task の型など。個人データを含めない）。 */
    public String owner() {
        return owner;
    }

    /** 取得の時刻（gate の単調時計）。 */
    public long acquiredAtNanos() {
        return acquiredAtNanos;
    }

    /** 排水の対象として数えた permit か（入れ子の permit は false）。 */
    public boolean isCounted() {
        return counted;
    }

    public boolean isClosed() {
        return closed.get();
    }

    Thread boundThread() {
        return boundThread;
    }

    void bind(Thread thread) {
        this.boundThread = thread;
    }

    /**
     * 投入した thread で数えた permit（{@link BackupWriteGate#tryAcquireDetached}）を、task を実行する thread に結び付ける。
     * 以後、この thread の入れ子の要求は待たずに permit を得る。task の終わりに {@link #close()} を呼ぶ。
     */
    public BackupWritePermit attachToCurrentThread() {
        gate.attach(this);
        return this;
    }

    /** permit を返す。結び付けた thread 以外から呼んでもよい（数は戻り、その thread の結び付きは次の参照で外れる）。 */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            gate.release(this);
        }
    }

    @Override
    public String toString() {
        return "permit[" + kind.label() + ", " + owner + "]";
    }
}
