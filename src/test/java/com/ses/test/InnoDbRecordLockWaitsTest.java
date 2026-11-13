package com.ses.test;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 実MySQL試験のlock待機確認helperのうち、DB無しで決まる判定（鍵値照合と待機graphの到達判定）を確認する。
 * 誤った行の待機を成功と判定しないことが、LOCK_DATAに依存しない確認方式の前提になる。
 */
class InnoDbRecordLockWaitsTest {

    @Test
    void compositeKeyは列値の並びが完全一致する場合だけ受け入れる() {
        Predicate<String> key = InnoDbRecordLockWaits.compositeKey("tenant-a", "subject-b");

        assertTrue(key.test("'tenant-a', 'subject-b'"));
        assertFalse(key.test("'tenant-a', 'subject-c'"), "別subjectの行を拒否する");
        assertFalse(key.test("'tenant-x', 'subject-b'"), "別tenantの行を拒否する");
        assertFalse(key.test("'subject-b', 'tenant-a'"), "列順の違いを拒否する");
        assertFalse(key.test("'tenant-a'"), "列数が少ない鍵を拒否する");
        assertFalse(key.test("'journal-z', 'tenant-a', 'subject-b'"), "列数が多い鍵を拒否する");
        assertFalse(key.test("supremum pseudo-record"), "疑似recordを拒否する");
    }

    @Test
    void compositeKeyは区切りと衝突する期待値を受け付けない() {
        assertThrows(IllegalArgumentException.class, () -> InnoDbRecordLockWaits.compositeKey("a,b", "c"));
        assertThrows(IllegalArgumentException.class, () -> InnoDbRecordLockWaits.compositeKey("a'b"));
        assertThrows(IllegalArgumentException.class, () -> InnoDbRecordLockWaits.compositeKey(""));
    }

    @Test
    void 見えている鍵値だけを照合しNULLは不可視として扱う() {
        Predicate<String> key = InnoDbRecordLockWaits.primaryKey(42);

        assertTrue(InnoDbRecordLockWaits.keyVisibleAndMatches(null, key), "NULLは不可視として判定から外す");
        assertTrue(InnoDbRecordLockWaits.keyVisibleAndMatches("42", key));
        assertFalse(InnoDbRecordLockWaits.keyVisibleAndMatches("43", key), "見えている別行の鍵は拒否する");
    }

    @Test
    void reachesHolderはholderの対象lockへの直接辺を到達とする() {
        List<InnoDbRecordLockWaits.WaitEdge> waits = List.of(edge(11, "req-11", 1, "holder-row", "GRANTED"));

        assertTrue(InnoDbRecordLockWaits.reachesHolder(waits, 11, 1, "holder-row"));
        assertFalse(InnoDbRecordLockWaits.reachesHolder(waits, 12, 1, "holder-row"), "辺の無いrequesterは到達しない");
    }

    @Test
    void reachesHolderはholderの別lockを指す辺を到達としない() {
        List<InnoDbRecordLockWaits.WaitEdge> waits = List.of(edge(11, "req-11", 1, "holder-other-row", "GRANTED"));

        assertFalse(InnoDbRecordLockWaits.reachesHolder(waits, 11, 1, "holder-row"));
    }

    @Test
    void reachesHolderは先行requesterのWAITING_lockを経由して到達する() {
        List<InnoDbRecordLockWaits.WaitEdge> waits = List.of(
                edge(11, "req-11", 1, "holder-row", "GRANTED"),
                edge(12, "req-12", 11, "req-11", "WAITING"));

        assertTrue(InnoDbRecordLockWaits.reachesHolder(waits, 12, 1, "holder-row"));
    }

    @Test
    void reachesHolderは他接続のGRANTED_lockで行き止まる経路を到達としない() {
        List<InnoDbRecordLockWaits.WaitEdge> waits = List.of(
                edge(11, "req-11", 1, "holder-row", "GRANTED"),
                edge(12, "req-12", 11, "granted-11-other-row", "GRANTED"));

        assertFalse(InnoDbRecordLockWaits.reachesHolder(waits, 12, 1, "holder-row"),
                "requester 11が対象行を待っていても、12が待つのは11の別lockなので対象行の待機とは言えない");
    }

    @Test
    void reachesHolderは循環する待機辺でも停止する() {
        List<InnoDbRecordLockWaits.WaitEdge> waits = List.of(
                edge(12, "req-12", 13, "req-13", "WAITING"),
                edge(13, "req-13", 12, "req-12", "WAITING"));

        assertFalse(InnoDbRecordLockWaits.reachesHolder(waits, 12, 1, "holder-row"));
    }

    private static InnoDbRecordLockWaits.WaitEdge edge(long requester, String requesterLockId, long blocker,
                                                       String blockerLockId, String blockerStatus) {
        return new InnoDbRecordLockWaits.WaitEdge(requester, blocker,
                requesterLockId, "t", "PRIMARY", "RECORD", "X,REC_NOT_GAP", "WAITING", null,
                blockerLockId, "t", "PRIMARY", "RECORD", "X,REC_NOT_GAP", blockerStatus, null);
    }
}
