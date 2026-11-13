package com.ses.importcenter.metadata;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * performance_schemaのInnoDB record lock待機を、{@code LOCK_DATA}の値に依存せずに特定する実MySQL試験用helper。
 *
 * <p>背景（CI run 43337400941 shard 1 と seed 73854703259 の再現）：{@code data_locks.LOCK_DATA}は、InnoDBが対象pageを
 * latch待ちなしで参照できたときだけ鍵値を返し、そうでなければNULLになる（MySQL 8.0 reference manual の
 * {@code data_locks}表の説明では「pageがbuffer poolに無い場合はNULL」。実装はpageをtry-getで取得し、取得できなければNULL）。
 * 実際に待機WAITING・保持GRANTEDの両lockが{@code key=null}で観測された。鍵値の完全一致を待機確認の必須条件にすると、
 * 実際には待機しているのに確認できず期限切れになる非決定性が生じる。infimum/supremum疑似recordはpageを参照せず
 * 文字列が決まるため、この問題は無い。
 *
 * <p>本helperは次の組合せで「同一recordでの待機」を確定する。
 * <ol>
 *   <li>構造：OBJECT_SCHEMA=DATABASE()・OBJECT_NAME・INDEX_NAME・LOCK_TYPE=RECORD・LOCK_MODE・LOCK_STATUS。</li>
 *   <li>thread対：{@code data_lock_waits}のREQUESTING/BLOCKING threadのPROCESSLIST_IDが期待接続と一致し、互いに異なる。</li>
 *   <li>record同一性（blocker基準）：blockerが主キー等の一意条件で1行だけを{@code FOR UPDATE}し、当該indexの排他
 *       record lock（X / X,REC_NOT_GAP。gap専用は数えない）をちょうど1件だけ保持していることを毎回確認し、待機辺の
 *       blocking lockがそのlockであることを要求する。保持lockが1件なら、それは一意条件で選んだその行のlockである。</li>
 *   <li>record同一性（requester基準）：requesterが主キー一点のUPDATE/FK子insertである場合、InnoDBのtransactionが同時に
 *       待機するlockは1件だけなので、その待機lockは当該行である（呼出側の文の形で保証する）。</li>
 *   <li>鍵値：LOCK_DATAが見えている側は期待鍵（と相手側の見えている鍵）に一致しなければならない。誤った行の待機を
 *       証拠として受け入れない。NULLは「不可視」として扱い、1〜4で判定する。</li>
 * </ol>
 */
final class InnoDbRecordLockWaits {
    static final Set<String> EXCLUSIVE_RECORD_MODES = Set.of("X", "X,REC_NOT_GAP");
    static final Set<String> EXCLUSIVE_NON_GAP_MODE = Set.of("X,REC_NOT_GAP");

    private InnoDbRecordLockWaits() {
    }

    /** 接続が保持するRECORD lock（GRANTED）。 */
    record HeldLock(long connectionId, String lockId, String transactionId, String table, String index,
                    String mode, String lockData) { }

    /**
     * blocker基準の待機確認に使う基準lock。{@code expectedKey}は、見えている鍵値が満たすべき条件（null=条件なし。
     * その場合も見えている鍵どうしの一致は要求する）。
     */
    record PointLock(HeldLock lock, Predicate<String> expectedKey) {
        long connectionId() { return lock.connectionId(); }
        String table() { return lock.table(); }
        String index() { return lock.index(); }
        String lockData() { return lock.lockData(); }
    }

    /** {@code data_lock_waits}の一辺（requesterのWAITING lock → blockerのGRANTED lock）。 */
    record WaitEdge(long requesterConnectionId, long blockerConnectionId,
                    String requesterLockId, String requesterTable, String requesterIndex, String requesterType,
                    String requesterMode, String requesterStatus, String requesterData,
                    String blockerLockId, String blockerTable, String blockerIndex, String blockerType,
                    String blockerMode, String blockerStatus, String blockerData) {
        String describe() {
            return "requester=" + requesterConnectionId + " " + requesterTable + "/" + requesterIndex + ","
                    + requesterType + "," + requesterMode + "," + requesterStatus + ",key=" + requesterData
                    + "; blocker=" + blockerConnectionId + " " + blockerTable + "/" + blockerIndex + ","
                    + blockerType + "," + blockerMode + "," + blockerStatus + ",key=" + blockerData;
        }
    }

    /** 各pollの前に呼ぶ確認（worker futureが期待より早く終わっていないか等）。 */
    @FunctionalInterface
    interface PollCheck {
        void run() throws Exception;
    }

    static List<HeldLock> grantedRecordLocks(JdbcTemplate jdbc, long connectionId, String table, String index,
                                             Set<String> modes) {
        return jdbc.query("""
                        SELECT th.PROCESSLIST_ID, dl.ENGINE_LOCK_ID, dl.ENGINE_TRANSACTION_ID, dl.OBJECT_NAME,
                               dl.INDEX_NAME, dl.LOCK_MODE, dl.LOCK_DATA
                        FROM performance_schema.data_locks dl
                        JOIN performance_schema.threads th ON th.THREAD_ID=dl.THREAD_ID
                        WHERE th.PROCESSLIST_ID=? AND dl.OBJECT_SCHEMA=DATABASE() AND dl.OBJECT_NAME=?
                          AND dl.INDEX_NAME=? AND dl.LOCK_TYPE='RECORD' AND dl.LOCK_STATUS='GRANTED'
                        """, (rs, row) -> new HeldLock(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)),
                connectionId, table, index).stream().filter(lock -> modes.contains(lock.mode())).toList();
    }

    /** requester（null=任意）からblockerへの、指定table・indexのrecord lock待機辺。 */
    static List<WaitEdge> waitEdges(JdbcTemplate jdbc, Long requesterConnectionId, long blockerConnectionId,
                                    String table, String index) {
        long requesterFilter = requesterConnectionId == null ? 0L : requesterConnectionId;
        return jdbc.query("""
                        SELECT wt.PROCESSLIST_ID, bt.PROCESSLIST_ID,
                               req.ENGINE_LOCK_ID, req.OBJECT_NAME, req.INDEX_NAME, req.LOCK_TYPE, req.LOCK_MODE,
                               req.LOCK_STATUS, req.LOCK_DATA,
                               blk.ENGINE_LOCK_ID, blk.OBJECT_NAME, blk.INDEX_NAME, blk.LOCK_TYPE, blk.LOCK_MODE,
                               blk.LOCK_STATUS, blk.LOCK_DATA
                        FROM performance_schema.data_lock_waits w
                        JOIN performance_schema.threads wt ON wt.THREAD_ID=w.REQUESTING_THREAD_ID
                        JOIN performance_schema.threads bt ON bt.THREAD_ID=w.BLOCKING_THREAD_ID
                        JOIN performance_schema.data_locks req
                          ON req.ENGINE=w.ENGINE AND req.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                        JOIN performance_schema.data_locks blk
                          ON blk.ENGINE=w.ENGINE AND blk.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID
                        WHERE (?=0 OR wt.PROCESSLIST_ID=?) AND bt.PROCESSLIST_ID=?
                          AND req.OBJECT_SCHEMA=DATABASE() AND req.OBJECT_NAME=? AND req.INDEX_NAME=?
                          AND blk.OBJECT_SCHEMA=DATABASE() AND blk.OBJECT_NAME=? AND blk.INDEX_NAME=?
                        """, (rs, row) -> new WaitEdge(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13),
                        rs.getString(14), rs.getString(15), rs.getString(16)),
                requesterFilter, requesterFilter, blockerConnectionId, table, index, table, index);
    }

    /**
     * blockerが一意条件で1行だけを排他lockしたことを確認し、その保持lockを返す。保持lockが1件でない、または見えている
     * 鍵値が期待と違う場合は対象行を特定できないため失敗させる（lockは同期的に作られるが念のため短く待つ）。
     */
    static PointLock requireSinglePointLock(JdbcTemplate jdbc, long blockerConnectionId, String table, String index,
                                            Predicate<String> expectedKey, String label) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<HeldLock> held = List.of();
        while (System.nanoTime() < deadline) {
            held = grantedRecordLocks(jdbc, blockerConnectionId, table, index, EXCLUSIVE_RECORD_MODES);
            if (held.size() == 1) {
                HeldLock lock = held.get(0);
                if (!keyVisibleAndMatches(lock.lockData(), expectedKey)) {
                    throw new AssertionError(label + "のblockerが期待と異なる行をlockしています: key=" + lock.lockData());
                }
                return new PointLock(lock, expectedKey);
            }
            if (held.size() > 1) break;
            Thread.sleep(25);
        }
        throw new AssertionError(label + "のblockerが" + table + "/" + index
                + "の排他record lockをちょうど1件保持していることを確認できませんでした: count=" + held.size());
    }

    /**
     * blocker基準の待機を待つ。毎poll、blockerが当該indexの排他record lockを1件だけ保持し、待機辺のblocking lockが
     * そのlockであることを確認する（lock IDはpage再編成で変わり得るため、取得済みIDの固定比較はしない）。
     */
    static WaitEdge awaitBlockerAnchoredWait(JdbcTemplate jdbc, PointLock anchor, Supplier<Long> expectedRequester,
                                             Set<String> requestModes, Set<String> blockerModes, long timeoutMillis,
                                             PollCheck beforeEachPoll, String label) throws Exception {
        Predicate<String> expectedKey = anchor.expectedKey();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            beforeEachPoll.run();
            Long requester = expectedRequester == null ? null : expectedRequester.get();
            if (expectedRequester == null || requester != null) {
                List<HeldLock> held = grantedRecordLocks(jdbc, anchor.connectionId(), anchor.table(), anchor.index(),
                        EXCLUSIVE_RECORD_MODES);
                if (held.size() == 1 && keyVisibleAndMatches(held.get(0).lockData(), expectedKey)) {
                    HeldLock current = held.get(0);
                    for (WaitEdge edge : waitEdges(jdbc, requester, anchor.connectionId(), anchor.table(),
                            anchor.index())) {
                        if (Objects.equals(edge.blockerLockId(), current.lockId())
                                && structurallyMatches(edge, requestModes, blockerModes)
                                && keysConsistent(edge, expectedKey, anchor.lockData(), current.lockData())) {
                            return edge;
                        }
                    }
                }
            }
            Thread.sleep(25);
        }
        beforeEachPoll.run();
        throw new AssertionError(label + "の対象record lock待機を確認できませんでした: "
                + describeWaitsForBlocker(jdbc, anchor.connectionId()));
    }

    /**
     * requester基準の待機を待つ。requesterの文が主キー一点（または単一行FK）であることは呼出側が保証する。
     * requester・blockerの接続IDが揃うまでは問い合わせない。
     */
    static WaitEdge awaitRequesterAnchoredWait(JdbcTemplate jdbc, Supplier<Long> requester, Supplier<Long> blocker,
                                               String table, String index, Set<String> requestModes,
                                               Set<String> blockerModes, Predicate<String> expectedKey,
                                               long timeoutMillis, PollCheck beforeEachPoll, String label)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            beforeEachPoll.run();
            Long requesterId = requester.get();
            Long blockerId = blocker.get();
            if (requesterId != null && blockerId != null && !requesterId.equals(blockerId)) {
                for (WaitEdge edge : waitEdges(jdbc, requesterId, blockerId, table, index)) {
                    if (structurallyMatches(edge, requestModes, blockerModes)
                            && keysConsistent(edge, expectedKey, null, null)) {
                        return edge;
                    }
                }
            }
            Thread.sleep(25);
        }
        beforeEachPoll.run();
        Long blockerId = blocker.get();
        throw new AssertionError(label + "の対象record lock待機を確認できませんでした: requester=" + requester.get()
                + " blocker=" + blockerId + "; "
                + (blockerId == null ? "blocker connection id未取得" : describeWaitsForBlocker(jdbc, blockerId)));
    }

    /** 見えている鍵値が期待に合うか。NULL（不可視）と期待なしは合格とする。 */
    static boolean keyVisibleAndMatches(String lockData, Predicate<String> expectedKey) {
        return lockData == null || expectedKey == null || expectedKey.test(lockData);
    }

    /** LOCK_DATAの単純な数値主キー（例 {@code 123}）が期待値と一致するか。 */
    static Predicate<String> primaryKey(long id) {
        String expected = Long.toString(id);
        return lockData -> expected.equals(unquote(lockData.trim()));
    }

    static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        return value;
    }

    /** 失敗時の診断。blockerを待っている全辺を、鍵値（NULLを含む）とともに列挙する。 */
    static String describeWaitsForBlocker(JdbcTemplate jdbc, long blockerConnectionId) {
        try {
            List<String> details = new ArrayList<>();
            for (WaitEdge edge : jdbc.query("""
                            SELECT wt.PROCESSLIST_ID, bt.PROCESSLIST_ID,
                                   req.ENGINE_LOCK_ID, req.OBJECT_NAME, req.INDEX_NAME, req.LOCK_TYPE, req.LOCK_MODE,
                                   req.LOCK_STATUS, req.LOCK_DATA,
                                   blk.ENGINE_LOCK_ID, blk.OBJECT_NAME, blk.INDEX_NAME, blk.LOCK_TYPE, blk.LOCK_MODE,
                                   blk.LOCK_STATUS, blk.LOCK_DATA
                            FROM performance_schema.data_lock_waits w
                            JOIN performance_schema.threads wt ON wt.THREAD_ID=w.REQUESTING_THREAD_ID
                            JOIN performance_schema.threads bt ON bt.THREAD_ID=w.BLOCKING_THREAD_ID
                            JOIN performance_schema.data_locks req
                              ON req.ENGINE=w.ENGINE AND req.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                            JOIN performance_schema.data_locks blk
                              ON blk.ENGINE=w.ENGINE AND blk.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID
                            WHERE bt.PROCESSLIST_ID=?
                            """, (rs, row) -> new WaitEdge(rs.getLong(1), rs.getLong(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                            rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13),
                            rs.getString(14), rs.getString(15), rs.getString(16)),
                    blockerConnectionId)) {
                details.add(edge.describe());
            }
            List<HeldLock> held = jdbc.query("""
                            SELECT th.PROCESSLIST_ID, dl.ENGINE_LOCK_ID, dl.ENGINE_TRANSACTION_ID, dl.OBJECT_NAME,
                                   dl.INDEX_NAME, dl.LOCK_MODE, dl.LOCK_DATA
                            FROM performance_schema.data_locks dl
                            JOIN performance_schema.threads th ON th.THREAD_ID=dl.THREAD_ID
                            WHERE th.PROCESSLIST_ID=? AND dl.LOCK_TYPE='RECORD' AND dl.LOCK_STATUS='GRANTED'
                            """, (rs, row) -> new HeldLock(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)),
                    blockerConnectionId);
            for (HeldLock lock : held) {
                details.add("held " + lock.table() + "/" + lock.index() + "," + lock.mode() + ",key=" + lock.lockData());
            }
            return details.isEmpty() ? "blocker=" + blockerConnectionId + "の待機辺・保持lockなし" : details.toString();
        } catch (RuntimeException failure) {
            return "待機診断の取得に失敗しました: " + failure.getClass().getSimpleName();
        }
    }

    private static boolean structurallyMatches(WaitEdge edge, Set<String> requestModes, Set<String> blockerModes) {
        return edge.requesterConnectionId() != edge.blockerConnectionId()
                && "RECORD".equals(edge.requesterType()) && "WAITING".equals(edge.requesterStatus())
                && requestModes.contains(edge.requesterMode())
                && "RECORD".equals(edge.blockerType()) && "GRANTED".equals(edge.blockerStatus())
                && blockerModes.contains(edge.blockerMode())
                && Objects.equals(edge.requesterTable(), edge.blockerTable())
                && Objects.equals(edge.requesterIndex(), edge.blockerIndex());
    }

    /** 見えている鍵値どうし・期待鍵の間に矛盾が無いこと。NULLは比較から外す。 */
    private static boolean keysConsistent(WaitEdge edge, Predicate<String> expectedKey, String anchorKey,
                                          String currentKey) {
        List<String> visible = new ArrayList<>();
        for (String key : new String[]{edge.requesterData(), edge.blockerData(), anchorKey, currentKey}) {
            if (key == null) continue;
            if (expectedKey != null && !expectedKey.test(key)) return false;
            visible.add(key);
        }
        return visible.stream().distinct().count() <= 1;
    }
}
