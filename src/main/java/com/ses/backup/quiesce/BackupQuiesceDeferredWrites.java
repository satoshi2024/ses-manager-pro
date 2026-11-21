package com.ses.backup.quiesce;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 解除後の書込みの一覧（design.md §6.13。U-8 の静止中の logout の {@code t_user_session} の失効と監査）。
 *
 * <ul>
 *   <li>memory にだけ持つ（process が落ちると失われる。§17 L-14）。上限を超えたら最も古いものから WARN を出して捨てる。</li>
 *   <li>入れた順に書く。各書込みは「解除後の書込み」の permit（{@link BackupWritePermit.Kind#DEFERRED_WRITE}）を持って行う。
 *       permit を取れない（その間に gate がまた閉じた）ときは捨てずに一覧の先頭へ戻し、次に開いた時に書く（D3-P3-5）。</li>
 *   <li>DB の失敗は WARN を出して {@link #MAX_ATTEMPTS} 回まで繰り返し、それでも失敗したら内容つきの WARN を残して捨てる。</li>
 * </ul>
 * 後で書く値（時刻・接続元・correlation ID 等）は、入れる側が logout の時点で取っておき {@link DeferredWrite} に閉じ込める。
 */
public final class BackupQuiesceDeferredWrites {

    private static final Logger log = LoggerFactory.getLogger(BackupQuiesceDeferredWrites.class);

    /** 1件の書込みを試みる回数の上限。 */
    public static final int MAX_ATTEMPTS = 3;

    /** 解除の後に書く1件。{@link #summary()} は log に出すため個人データ・秘密を含めない（user ID・session hash の先頭8文字・時刻）。 */
    public interface DeferredWrite {
        String summary();

        void write();
    }

    private final int capacity;
    private final Deque<DeferredWrite> queue = new ArrayDeque<>();
    private final AtomicBoolean flushing = new AtomicBoolean();
    private final AtomicLong dropped = new AtomicLong();

    public BackupQuiesceDeferredWrites(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("解除後の書込みの上限は1以上にしてください");
        }
        this.capacity = capacity;
    }

    /** 一覧の末尾へ入れ、WARN {@code BACKUP_QUIESCE_DEFERRED_WRITE} を出す。上限を超えたら最も古いものを捨てる。 */
    public void add(DeferredWrite write) {
        Objects.requireNonNull(write, "write");
        DeferredWrite evicted = null;
        synchronized (queue) {
            if (queue.size() >= capacity) {
                evicted = queue.pollFirst();
            }
            queue.addLast(write);
        }
        log.warn("errorCode=BACKUP_QUIESCE_DEFERRED_WRITE backup の静止中のため、書込みを静止の解除の後へ回しました（{}）",
                write.summary());
        if (evicted != null) {
            dropped.incrementAndGet();
            log.warn("errorCode=BACKUP_QUIESCE_DEFERRED_WRITE_DROPPED 解除後の書込みが上限（{}件）を超えたため、"
                    + "最も古いものを書かずに捨てました（{}）", capacity, evicted.summary());
        }
    }

    /** 未処理の件数（metric {@code ses_backup_quiesce_deferred_writes_pending}）。 */
    public int pending() {
        synchronized (queue) {
            return queue.size();
        }
    }

    /** 上限で捨てた件数（試験と診断用）。 */
    public long droppedCount() {
        return dropped.get();
    }

    public int capacity() {
        return capacity;
    }

    /**
     * 一覧を入れた順に書く（gate が開いた時に executor の thread で呼ぶ）。同時に1つだけ動く。
     *
     * @return 書けた件数
     */
    public int flush(BackupWriteGate gate) {
        if (!flushing.compareAndSet(false, true)) {
            return 0;
        }
        int written = 0;
        try {
            while (true) {
                DeferredWrite next;
                synchronized (queue) {
                    next = queue.pollFirst();
                }
                if (next == null) {
                    return written;
                }
                BackupWritePermit permit = gate.tryAcquire(BackupWritePermit.Kind.DEFERRED_WRITE, "deferred-write");
                if (permit == null) {
                    synchronized (queue) {
                        queue.addFirst(next);
                    }
                    log.info("backup の静止がまた始まったため、残りの解除後の書込み（{}件）は次の解除の後に書きます", pending());
                    return written;
                }
                try {
                    if (writeWithRetry(next)) {
                        written++;
                    }
                } finally {
                    permit.close();
                }
            }
        } finally {
            flushing.set(false);
        }
    }

    private static boolean writeWithRetry(DeferredWrite write) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                write.write();
                return true;
            } catch (RuntimeException failure) {
                log.warn("解除後の書込みに失敗しました（{}回目/{}回、{}、{}）", attempt, MAX_ATTEMPTS, write.summary(),
                        failure.getClass().getSimpleName());
            }
        }
        log.warn("errorCode=BACKUP_QUIESCE_DEFERRED_WRITE_DROPPED 解除後の書込みが{}回失敗したため、書かずに捨てました（{}）",
                MAX_ATTEMPTS, write.summary());
        return false;
    }
}
