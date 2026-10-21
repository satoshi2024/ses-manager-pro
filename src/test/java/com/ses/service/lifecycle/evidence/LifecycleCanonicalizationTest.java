package com.ses.service.lifecycle.evidence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LifecycleCanonicalizationTest {

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Test
    @DisplayName("証跡版が安定順に並び、exact版の各値が証跡hashへ反映されること")
    void evidenceHashIsStableAndBindsExactVersionFacts() {
        var first = new LifecycleCanonicalization.EvidenceSnapshot("tenant-a", List.of(
                new LifecycleCanonicalization.DocumentVersionSnapshot(20, 202, 2, HASH_B, 4),
                new LifecycleCanonicalization.DocumentVersionSnapshot(10, 101, 1, HASH_A, 3)));
        var reordered = new LifecycleCanonicalization.EvidenceSnapshot("tenant-a", List.of(
                new LifecycleCanonicalization.DocumentVersionSnapshot(10, 101, 1, HASH_A.toUpperCase(), 3),
                new LifecycleCanonicalization.DocumentVersionSnapshot(20, 202, 2, HASH_B, 4)));
        var changedVersion = new LifecycleCanonicalization.EvidenceSnapshot("tenant-a", List.of(
                new LifecycleCanonicalization.DocumentVersionSnapshot(10, 102, 2, HASH_A, 3),
                new LifecycleCanonicalization.DocumentVersionSnapshot(20, 202, 2, HASH_B, 4)));
        var changedEpoch = new LifecycleCanonicalization.EvidenceSnapshot("tenant-a", List.of(
                new LifecycleCanonicalization.DocumentVersionSnapshot(10, 101, 1, HASH_A, 4),
                new LifecycleCanonicalization.DocumentVersionSnapshot(20, 202, 2, HASH_B, 4)));
        var changedContentHash = new LifecycleCanonicalization.EvidenceSnapshot("tenant-a", List.of(
                new LifecycleCanonicalization.DocumentVersionSnapshot(10, 101, 1, HASH_B, 3),
                new LifecycleCanonicalization.DocumentVersionSnapshot(20, 202, 2, HASH_B, 4)));

        assertEquals(first.canonicalJson(), reordered.canonicalJson());
        assertEquals(first.sha256(), reordered.sha256());
        assertNotEquals(first.sha256(), changedVersion.sha256());
        assertNotEquals(first.sha256(), changedEpoch.sha256());
        assertNotEquals(first.sha256(), changedContentHash.sha256());
        assertTrue(first.canonicalJson().indexOf("\"documentId\":10")
                < first.canonicalJson().indexOf("\"documentId\":20"));
    }

    @Test
    @DisplayName("重複した文書選択と検証済み複数版を拒否すること")
    void duplicateDocumentIsAmbiguousAndRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LifecycleCanonicalization.Request(
                "tenant-a", 1, 0, LifecycleCanonicalization.Operation.SUBMIT, 7, "key-1", "comment",
                List.of(new LifecycleCanonicalization.DocumentSelection(10, 101),
                        new LifecycleCanonicalization.DocumentSelection(10, 102))));

        assertThrows(IllegalArgumentException.class, () -> new LifecycleCanonicalization.EvidenceSnapshot("tenant-a", List.of(
                new LifecycleCanonicalization.DocumentVersionSnapshot(10, 101, 1, HASH_A, 0),
                new LifecycleCanonicalization.DocumentVersionSnapshot(10, 102, 2, HASH_B, 0))));
    }

    @Test
    @DisplayName("semantic request hashは選択順に依存せず、選択変更で変わること")
    void requestHashIsStableForReorderedSelections() {
        var first = new LifecycleCanonicalization.Request("tenant-a", 1, 2,
                LifecycleCanonicalization.Operation.SUBMIT, 7, "idem-1", "受領確認", List.of(
                new LifecycleCanonicalization.DocumentSelection(20, 202),
                new LifecycleCanonicalization.DocumentSelection(10, 101)));
        var reordered = new LifecycleCanonicalization.Request("tenant-a", 1, 2,
                LifecycleCanonicalization.Operation.SUBMIT, 7, "idem-1", "受領確認", List.of(
                new LifecycleCanonicalization.DocumentSelection(10, 101),
                new LifecycleCanonicalization.DocumentSelection(20, 202)));
        var changedSelection = new LifecycleCanonicalization.Request("tenant-a", 1, 2,
                LifecycleCanonicalization.Operation.SUBMIT, 7, "idem-1", "受領確認", List.of(
                new LifecycleCanonicalization.DocumentSelection(10, 102),
                new LifecycleCanonicalization.DocumentSelection(20, 202)));

        assertEquals(first.canonicalJson(), reordered.canonicalJson());
        assertEquals(first.sha256(), reordered.sha256());
        assertNotEquals(first.sha256(), changedSelection.sha256());
        assertFalse(first.canonicalJson().contains("submissionId"));
        assertFalse(first.canonicalJson().contains("submissionRevision"));
    }

    @Test
    @DisplayName("confirmation bindingは独立し、永続submission ID・revision・証跡hashへ結合すること")
    void confirmationHashBindsSubmissionRevisionAndEvidence() {
        var first = new LifecycleCanonicalization.ConfirmationBinding(31, 1, HASH_A);
        assertNotEquals(first.sha256(), new LifecycleCanonicalization.ConfirmationBinding(32, 1, HASH_A).sha256());
        assertNotEquals(first.sha256(), new LifecycleCanonicalization.ConfirmationBinding(31, 2, HASH_A).sha256());
        assertNotEquals(first.sha256(), new LifecycleCanonicalization.ConfirmationBinding(31, 1, HASH_B).sha256());
    }

    @Test
    @DisplayName("exact証跡の識別子欠落と不正content hashを拒否すること")
    void exactEvidenceRequiresVerifiedIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> new LifecycleCanonicalization.DocumentVersionSnapshot(10, 0, 1, HASH_A, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new LifecycleCanonicalization.DocumentVersionSnapshot(10, 101, 1, "client-hash", 0));
        assertThrows(IllegalArgumentException.class,
                () -> new LifecycleCanonicalization.EvidenceSnapshot(" ", List.of()));
    }

    @Test
    @DisplayName("孤立surrogateを拒否し、正しいsurrogate pairはcanonical hashで扱えること")
    void malformedSurrogatesAreRejectedAndEmojiRemainsValid() {
        assertThrows(IllegalArgumentException.class, () -> new LifecycleCanonicalization.Request(
                "tenant-a", 1, 0, LifecycleCanonicalization.Operation.SUBMIT, 7, "key-1", "\uD800", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new LifecycleCanonicalization.Request(
                "tenant-a", 1, 0, LifecycleCanonicalization.Operation.SUBMIT, 7, "key-1", "\uD801", List.of()));

        var emoji = new LifecycleCanonicalization.Request("tenant-a", 1, 0,
                LifecycleCanonicalization.Operation.SUBMIT, 7, "key-1", "確認\uD83D\uDE00", List.of());
        assertEquals(64, emoji.sha256().length());
        assertTrue(emoji.canonicalJson().contains("確認\uD83D\uDE00"));
    }

    @Test
    @DisplayName("認可epoch tupleのいずれかが変わればfail-closedになること")
    void authorizationEpochTupleDetectsRevocation() {
        var submission = new LifecycleAuthorizationEpochs(2, 5, 3);
        assertTrue(submission.submitterStillCurrent(new LifecycleAuthorizationEpochs(2, 5, 3)));
        assertFalse(submission.submitterStillCurrent(new LifecycleAuthorizationEpochs(3, 5, 3)));
        assertFalse(submission.submitterStillCurrent(new LifecycleAuthorizationEpochs(2, 6, 3)));
        assertFalse(submission.submitterStillCurrent(new LifecycleAuthorizationEpochs(2, 5, 4)));
    }

    @Test
    @DisplayName("依存lockが固定rank順に整列し同じparent行を重複排除すること")
    void lockPlanSortsAndDeduplicates() {
        var assetAssignment = new LifecycleLockPlan.LockKey(LifecycleLockPlan.Rank.ASSET_ASSIGNMENT, "tenant-a", 9);
        var engineer = new LifecycleLockPlan.LockKey(LifecycleLockPlan.Rank.ENGINEER, "tenant-a", 4);
        var plan = LifecycleLockPlan.from(List.of(
                new LifecycleLockPlan.LockKey(LifecycleLockPlan.Rank.DOCUMENT, "tenant-a", 12),
                assetAssignment,
                engineer,
                new LifecycleLockPlan.LockKey(LifecycleLockPlan.Rank.ASSET, "tenant-a", 2),
                engineer));

        assertEquals(List.of(
                new LifecycleLockPlan.LockKey(LifecycleLockPlan.Rank.ENGINEER, "tenant-a", 4),
                new LifecycleLockPlan.LockKey(LifecycleLockPlan.Rank.ASSET, "tenant-a", 2),
                assetAssignment,
                new LifecycleLockPlan.LockKey(LifecycleLockPlan.Rank.DOCUMENT, "tenant-a", 12)), plan.locks());
    }

    @Test
    @DisplayName("依存lock集合が同じなら順序と重複を許容し、追加・削除・tenant・ID変更を拒否すること")
    void dependencyFenceComparesCanonicalLockSet() {
        var engineer = new LifecycleLockPlan.LockKey(LifecycleLockPlan.Rank.ENGINEER, "tenant-a", 4);
        var document = new LifecycleLockPlan.LockKey(LifecycleLockPlan.Rank.DOCUMENT, "tenant-a", 12);
        var selected = LifecycleLockPlan.from(List.of(engineer, document));

        selected.requireSameDependencies(LifecycleLockPlan.from(List.of(document, engineer, document)));
        assertThrows(LifecycleLockPlan.DependencyChangedException.class,
                () -> selected.requireSameDependencies(LifecycleLockPlan.from(List.of(engineer))));
        assertThrows(LifecycleLockPlan.DependencyChangedException.class,
                () -> selected.requireSameDependencies(LifecycleLockPlan.from(List.of(engineer, document,
                        new LifecycleLockPlan.LockKey(LifecycleLockPlan.Rank.CUSTOMER, "tenant-a", 8)))));
        assertThrows(LifecycleLockPlan.DependencyChangedException.class,
                () -> selected.requireSameDependencies(LifecycleLockPlan.from(List.of(engineer,
                        new LifecycleLockPlan.LockKey(LifecycleLockPlan.Rank.DOCUMENT, "tenant-b", 12)))));
        assertThrows(LifecycleLockPlan.DependencyChangedException.class,
                () -> selected.requireSameDependencies(LifecycleLockPlan.from(List.of(engineer,
                        new LifecycleLockPlan.LockKey(LifecycleLockPlan.Rank.DOCUMENT, "tenant-a", 13)))));
    }
}
