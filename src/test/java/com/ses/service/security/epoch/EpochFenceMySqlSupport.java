package com.ses.service.security.epoch;

import com.ses.test.InnoDbRecordLockWaits;
import com.ses.test.InnoDbRecordLockWaits.HeldLock;
import com.ses.test.InnoDbRecordLockWaits.PointLock;
import com.ses.test.InnoDbRecordLockWaits.WaitEdge;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * M2・M3（NF01細粒度epoch計画 §6.1、W3-15）が共有する、anchor行での reader（{@code FOR SHARE}）× writer（{@code FOR UPDATE}）
 * の待機確認。待機の判定は {@code performance_schema.data_lock_waits} のrequester/blocker対とlock IDで行い、
 * {@code LOCK_DATA} を連結条件にしない（{@link InnoDbRecordLockWaits}）。
 *
 * <ul>
 *   <li>reader先行: readerが主キー一点で取った共有record lockを、writerの開始前に1件だけに固定し、待機辺のblocking lockが
 *       そのlockであることを要求する。</li>
 *   <li>writer先行: writerが当該表の主キーで保持する排他record lockをちょうど1件に固定し（主キー一点の {@code FOR UPDATE}
 *       で明示lockにしたもの）、readerの共有lock要求がそれを待つことを要求する。writerが同じ行の共有lockを持たないこと
 *       （S→X昇格が無いこと）も確かめる。</li>
 * </ul>
 */
final class EpochFenceMySqlSupport {

    static final Set<String> SHARED_RECORD_MODES = Set.of("S", "S,REC_NOT_GAP");
    static final long WAIT_SECONDS = 30;

    private EpochFenceMySqlSupport() {
    }

    static long connectionId(JdbcTemplate jdbc) {
        Long id = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
        return id == null ? 0L : id;
    }

    static TransactionTemplate readCommitted(PlatformTransactionManager manager) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout((int) (WAIT_SECONDS * 4));
        return template;
    }

    static TransactionTemplate required(PlatformTransactionManager manager) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setTimeout((int) (WAIT_SECONDS * 4));
        return template;
    }

    /**
     * reader先行: readerが {@code table} の主キーで共有lockを保持している間、writer（自身のtransactionでcommitする）が待ち、
     * readerの解放後に完了することを確かめる。
     *
     * @param threadSetup 各worker threadの最初に行う準備（SecurityContext・tenant context）
     * @return writerの戻り値
     */
    static <T> T assertWriterWaitsForReader(JdbcTemplate jdbc, PlatformTransactionManager manager, String table,
                                            Runnable threadSetup, Supplier<Long> readerCall, Supplier<T> writerCall,
                                            String label) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch readerLocked = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        AtomicLong readerConnection = new AtomicLong();
        AtomicLong writerConnection = new AtomicLong();
        try {
            Future<Long> reader = pool.submit(() -> {
                threadSetup.run();
                return readCommitted(manager).execute(status -> {
                    try {
                        readerConnection.set(connectionId(jdbc));
                        return readerCall.get();
                    } finally {
                        readerLocked.countDown();
                        awaitQuietly(releaseReader);
                        status.setRollbackOnly();
                    }
                });
            });
            if (!readerLocked.await(WAIT_SECONDS, TimeUnit.SECONDS) || reader.isDone()) {
                fail(label + ": readerがFOR SHAREのlockを保持できなかった: " + outcome(reader));
            }
            String sharedLockId = requireSingleSharedLock(jdbc, readerConnection.get(), table, label);
            Future<T> writer = pool.submit(call(threadSetup, () -> required(manager).execute(status -> {
                writerConnection.set(connectionId(jdbc));
                return writerCall.get();
            })));
            WaitEdge edge = awaitWriterWaitsOnSharedLock(jdbc, writer, writerConnection, readerConnection.get(), table,
                    sharedLockId, label);
            assertFalse(writer.isDone(), label + ": writerはreaderの解放まで待つこと。edge=" + edge.describe());
            releaseReader.countDown();
            reader.get(WAIT_SECONDS, TimeUnit.SECONDS);
            return writer.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            releaseReader.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * writer先行: writerが自身のtransactionで書込みを終えてcommit前に止まっている間、readerが {@code table} の主キーで
     * 共有lockを待ち、writerのcommit後に新しい値を読むことを確かめる。
     *
     * @return readerが読んだepoch
     */
    static long assertReaderWaitsForWriter(JdbcTemplate jdbc, PlatformTransactionManager manager, String table,
                                           Runnable threadSetup, Runnable writerCall, Supplier<Long> readerCall,
                                           String label) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch writerDone = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        AtomicLong writerConnection = new AtomicLong();
        AtomicLong readerConnection = new AtomicLong();
        try {
            Future<Object> writer = pool.submit(call(threadSetup, () -> required(manager).execute(status -> {
                writerConnection.set(connectionId(jdbc));
                writerCall.run();
                writerDone.countDown();
                awaitQuietly(releaseWriter);
                return null;
            })));
            if (!writerDone.await(WAIT_SECONDS, TimeUnit.SECONDS) || writer.isDone()) {
                fail(label + ": writerが書込みを終えてcommit前に止まらなかった: " + outcome(writer));
            }
            PointLock anchor = InnoDbRecordLockWaits.requireSinglePointLock(jdbc, writerConnection.get(), table,
                    "PRIMARY", key -> true, label);
            List<HeldLock> shared = InnoDbRecordLockWaits.grantedRecordLocks(jdbc, writerConnection.get(), table,
                    "PRIMARY", SHARED_RECORD_MODES);
            assertEquals(List.of(), shared, label + ": writerはanchor行を最初から排他lockで取る（S→X昇格なし）");
            Future<Long> reader = pool.submit(call(threadSetup, () -> readCommitted(manager).execute(status -> {
                readerConnection.set(connectionId(jdbc));
                Long value = readerCall.get();
                status.setRollbackOnly();
                return value;
            })));
            WaitEdge edge = InnoDbRecordLockWaits.awaitBlockerAnchoredWait(jdbc, anchor,
                    () -> readerConnection.get() > 0 ? readerConnection.get() : null, SHARED_RECORD_MODES,
                    InnoDbRecordLockWaits.EXCLUSIVE_RECORD_MODES, TimeUnit.SECONDS.toMillis(WAIT_SECONDS),
                    () -> {
                        if (reader.isDone()) {
                            fail(label + ": readerがwriterのcommitを待たずに終了した: " + outcome(reader));
                        }
                    }, label);
            assertFalse(reader.isDone(), label + ": readerはwriterのcommitまで待つこと。edge=" + edge.describe());
            releaseWriter.countDown();
            writer.get(WAIT_SECONDS, TimeUnit.SECONDS);
            return reader.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } finally {
            releaseWriter.countDown();
            pool.shutdownNow();
        }
    }

    /** readerが当該表の主キーで共有record lockをちょうど1件保持していることを確かめ、そのlock IDを返す。 */
    static String requireSingleSharedLock(JdbcTemplate jdbc, long readerConnection, String table, String label)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<HeldLock> held = List.of();
        while (System.nanoTime() < deadline) {
            held = InnoDbRecordLockWaits.grantedRecordLocks(jdbc, readerConnection, table, "PRIMARY", SHARED_RECORD_MODES);
            if (held.size() == 1) {
                return held.get(0).lockId();
            }
            if (held.size() > 1) {
                break;
            }
            Thread.sleep(20);
        }
        throw new AssertionError(label + ": readerが" + table + "の主キーで共有record lockをちょうど1件保持していることを"
                + "確認できなかった: count=" + held.size());
    }

    private static WaitEdge awaitWriterWaitsOnSharedLock(JdbcTemplate jdbc, Future<?> writer, AtomicLong writerConnection,
                                                         long readerConnection, String table, String sharedLockId,
                                                         String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (writer.isDone()) {
                fail(label + ": writerが待たずに終了した: " + outcome(writer));
            }
            List<HeldLock> held = InnoDbRecordLockWaits.grantedRecordLocks(jdbc, readerConnection, table, "PRIMARY",
                    SHARED_RECORD_MODES);
            if (held.size() != 1 || !held.get(0).lockId().equals(sharedLockId)) {
                fail(label + ": readerの共有lockが固定したものと変わった: anchored=" + sharedLockId + ", held=" + held);
            }
            long requester = writerConnection.get();
            if (requester > 0) {
                for (WaitEdge edge : InnoDbRecordLockWaits.waitEdges(jdbc, requester, readerConnection, table, "PRIMARY")) {
                    if (sharedLockId.equals(edge.blockerLockId())
                            && "RECORD".equals(edge.requesterType()) && "WAITING".equals(edge.requesterStatus())
                            && InnoDbRecordLockWaits.EXCLUSIVE_RECORD_MODES.contains(edge.requesterMode())
                            && "RECORD".equals(edge.blockerType()) && "GRANTED".equals(edge.blockerStatus())
                            && SHARED_RECORD_MODES.contains(edge.blockerMode())) {
                        return edge;
                    }
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError(label + ": writerが" + table + "でreaderの共有lockを待つことを確認できなかった: writer="
                + writerConnection.get() + "; " + InnoDbRecordLockWaits.describeWaitsForBlocker(jdbc, readerConnection));
    }

    private static <T> Callable<T> call(Runnable threadSetup, Supplier<T> body) {
        return () -> {
            threadSetup.run();
            return body.get();
        };
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_SECONDS * 3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("解放待ちが時間切れになりました");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String outcome(Future<?> future) {
        if (!future.isDone()) {
            return "running";
        }
        try {
            return "result=" + future.get();
        } catch (Exception e) {
            return "failure=" + e;
        }
    }
}
