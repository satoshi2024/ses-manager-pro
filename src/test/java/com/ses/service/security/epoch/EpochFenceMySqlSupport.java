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
 *       で明示lockにしたもの）、readerの共有lock要求がそれを待つことを要求する。writerがanchor記録そのものに共有lockを
 *       持たないこと（S→X昇格が無いこと）も確かめる。</li>
 * </ul>
 *
 * <p>S→X昇格の判定はanchor記録だけに限る（W3-15 delta review P1-D1）。writerが子表へinsertすると、InnoDBはFK検査で
 * 親表の別の行（例: {@code t_user_permission_group.assigned_by} → 操作者の {@code sys_user} 行）に {@code S,REC_NOT_GAP}
 * を付ける。これは同じ表・同じindexでも別の記録へのlockで、anchorの昇格ではないため数えない。</p>
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
            assertNoSharedLockOnAnchorRecord(jdbc, anchor, label);
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

    /**
     * reader先行で、writerを外側transactionなしに直接呼ぶ（serviceが自分で宣言した分離レベルのtransactionを始める）。
     * writerがreaderの共有lockを待っている間に、待機側接続の {@code transaction_isolation} を
     * {@code performance_schema.variables_by_thread} から読んで返す（E-3の宣言が実行時に効いていることの確認）。
     */
    static String assertDirectWriterWaitsForReaderAndReadIsolation(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                                                   String table, Runnable threadSetup,
                                                                   Supplier<Long> readerCall, Runnable writerCall,
                                                                   String label) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch readerLocked = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        AtomicLong readerConnection = new AtomicLong();
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
            Future<Object> writer = pool.submit(call(threadSetup, () -> {
                writerCall.run();
                return null;
            }));
            long requester = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (requester == 0 && System.nanoTime() < deadline) {
                if (writer.isDone()) {
                    fail(label + ": writerが待たずに終了した: " + outcome(writer));
                }
                for (WaitEdge edge : InnoDbRecordLockWaits.waitEdges(jdbc, null, readerConnection.get(), table, "PRIMARY")) {
                    if (sharedLockId.equals(edge.blockerLockId()) && "WAITING".equals(edge.requesterStatus())
                            && InnoDbRecordLockWaits.EXCLUSIVE_RECORD_MODES.contains(edge.requesterMode())) {
                        requester = edge.requesterConnectionId();
                    }
                }
                if (requester == 0) {
                    Thread.sleep(20);
                }
            }
            if (requester == 0) {
                throw new AssertionError(label + ": writerが" + table + "でreaderの共有lockを待つことを確認できなかった; "
                        + InnoDbRecordLockWaits.describeWaitsForBlocker(jdbc, readerConnection.get()));
            }
            String isolation = jdbc.queryForObject("""
                    SELECT v.VARIABLE_VALUE
                    FROM performance_schema.variables_by_thread v
                    JOIN performance_schema.threads t ON t.THREAD_ID = v.THREAD_ID
                    WHERE t.PROCESSLIST_ID = ? AND v.VARIABLE_NAME = 'transaction_isolation'
                    """, String.class, requester);
            assertFalse(writer.isDone(), label + ": writerはreaderの解放まで待つこと");
            releaseReader.countDown();
            reader.get(WAIT_SECONDS, TimeUnit.SECONDS);
            writer.get(WAIT_SECONDS, TimeUnit.SECONDS);
            return isolation;
        } finally {
            releaseReader.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * writerがanchor記録そのものに共有record lockを持たないことを確かめる（S→X昇格が無いこと）。
     *
     * <p>同じ表・indexでも、FK検査が親表の別の行に付ける {@code S,REC_NOT_GAP}（例: 権限group割当のinsertが
     * {@code assigned_by} の操作者行に付けるもの）はanchorではないので数えない。記録の同一性は、InnoDBの record lockの
     * {@code ENGINE_LOCK_ID}（{@code trx:space:page:heap_no:lock}）の {@code space:page:heap_no} で判定し、
     * {@code LOCK_DATA} は連結条件にしない（両方見えている場合に位置の判定と矛盾しないことだけを確かめる）。
     * 判定時点のanchorの排他lockを読み直し、writerが止まっている間の同じ時点の位置どうしで比べる。</p>
     */
    static void assertNoSharedLockOnAnchorRecord(JdbcTemplate jdbc, PointLock anchor, String label) {
        List<HeldLock> exclusive = InnoDbRecordLockWaits.grantedRecordLocks(jdbc, anchor.connectionId(), anchor.table(),
                anchor.index(), InnoDbRecordLockWaits.EXCLUSIVE_RECORD_MODES);
        if (exclusive.size() != 1) {
            fail(label + ": writerのanchor排他lockが1件ではなくなった: " + exclusive);
        }
        List<String> problems = sharedLocksOnAnchorRecord(exclusive.get(0), InnoDbRecordLockWaits.grantedRecordLocks(
                jdbc, anchor.connectionId(), anchor.table(), anchor.index(), SHARED_RECORD_MODES));
        assertEquals(List.of(), problems, label + ": writerはanchor記録を最初から排他lockで取る（S→X昇格なし）");
    }

    /**
     * anchorの排他lockと同じ記録上にある共有lock、または同じ記録上かを判定できない共有lockを返す（DB無しで検証できる純関数）。
     * 別の記録（FK検査が付ける親行のS lock等）は返さない。
     */
    static List<String> sharedLocksOnAnchorRecord(HeldLock anchorLock, List<HeldLock> sharedLocks) {
        String anchorPosition = recordPosition(anchorLock.lockId());
        List<String> problems = new java.util.ArrayList<>();
        for (HeldLock shared : sharedLocks) {
            String position = recordPosition(shared.lockId());
            boolean bothKeysVisible = anchorLock.lockData() != null && shared.lockData() != null;
            boolean sameRecord;
            if (anchorPosition != null && position != null) {
                sameRecord = anchorPosition.equals(position);
                if (bothKeysVisible && sameRecord != anchorLock.lockData().equals(shared.lockData())) {
                    problems.add("lockの位置と鍵値の判定が矛盾する: anchor=" + anchorLock + ", shared=" + shared);
                    continue;
                }
            } else if (bothKeysVisible) {
                sameRecord = anchorLock.lockData().equals(shared.lockData());
            } else {
                problems.add("ENGINE_LOCK_IDの形式が想定外で、共有lockがanchor記録上かを判定できない: anchor="
                        + anchorLock + ", shared=" + shared);
                continue;
            }
            if (sameRecord) {
                problems.add("anchor記録上の共有lock（S→X昇格の兆候）: anchor=" + anchorLock + ", shared=" + shared);
            }
        }
        return problems;
    }

    /**
     * InnoDBの record lockの {@code ENGINE_LOCK_ID}（MySQL 8.0: {@code trx_id:space_id:page_no:heap_no:lock_id}）から
     * 記録の位置 {@code space:page:heap_no} を取り出す。形式が違えば {@code null}（呼出側は鍵値で代替するか失敗させる）。
     */
    static String recordPosition(String engineLockId) {
        if (engineLockId == null) {
            return null;
        }
        String[] parts = engineLockId.split(":");
        if (parts.length != 5) {
            return null;
        }
        for (int i = 1; i <= 3; i++) {
            if (!parts[i].matches("\\d+")) {
                return null;
            }
        }
        return parts[1] + ":" + parts[2] + ":" + parts[3];
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
