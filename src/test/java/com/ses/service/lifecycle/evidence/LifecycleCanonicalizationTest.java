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
    @DisplayName("認可fenceのいずれかの成分が変わればfail-closedになること")
    void authorizationFenceDetectsRevocation() {
        var submission = new LifecycleAuthorizationFence(2, 5, 3, organizations(10L, 1L));
        assertTrue(submission.submitterStillCurrent(new LifecycleAuthorizationFence(2, 5, 3, organizations(10L, 1L))));
        assertEquals(LifecycleAuthorizationFence.Component.GLOBAL,
                submission.firstChange(new LifecycleAuthorizationFence(3, 5, 3, organizations(10L, 1L))));
        assertEquals(LifecycleAuthorizationFence.Component.ACTOR,
                submission.firstChange(new LifecycleAuthorizationFence(2, 6, 3, organizations(10L, 1L))));
        assertEquals(LifecycleAuthorizationFence.Component.ENGINEER,
                submission.firstChange(new LifecycleAuthorizationFence(2, 5, 4, organizations(10L, 1L))));
        assertEquals(LifecycleAuthorizationFence.Component.ORGANIZATION,
                submission.firstChange(new LifecycleAuthorizationFence(2, 5, 3, organizations(10L, 2L))));
        assertEquals(LifecycleAuthorizationFence.Component.ORGANIZATION,
                submission.firstChange(new LifecycleAuthorizationFence(2, 5, 3, organizations(11L, 1L))));
        assertFalse(submission.submitterStillCurrent(LifecycleAuthorizationFence.withoutOrganizations(2, 5, 3)));
        assertFalse(submission.submitterStillCurrent(null));
        assertThrows(IllegalArgumentException.class,
                () -> new LifecycleAuthorizationFence(-1, 0, 0, organizations(10L, 1L)));
        assertThrows(IllegalArgumentException.class,
                () -> new LifecycleAuthorizationFence(0, 0, 0, organizations(10L, -1L)));
    }

    @Test
    @DisplayName("依存lockが固定rank順に整列し同じparent行を重複排除すること")
    void lockPlanSortsAndDeduplicates() {
        var assetAssignment = LifecycleLockPlan.LockKey.exclusive(LifecycleLockPlan.Rank.ASSET_ASSIGNMENT, "", 9);
        var engineer = LifecycleLockPlan.LockKey.exclusive(LifecycleLockPlan.Rank.ENGINEER, "tenant-a", 4);
        var plan = LifecycleLockPlan.from(List.of(
                LifecycleLockPlan.LockKey.exclusive(LifecycleLockPlan.Rank.DOCUMENT, "tenant-a", 12),
                assetAssignment,
                engineer,
                LifecycleLockPlan.LockKey.exclusive(LifecycleLockPlan.Rank.ASSET, "", 2),
                engineer));

        assertEquals(List.of(
                LifecycleLockPlan.LockKey.exclusive(LifecycleLockPlan.Rank.ENGINEER, "tenant-a", 4),
                LifecycleLockPlan.LockKey.exclusive(LifecycleLockPlan.Rank.ASSET, "", 2),
                assetAssignment,
                LifecycleLockPlan.LockKey.exclusive(LifecycleLockPlan.Rank.DOCUMENT, "tenant-a", 12)), plan.locks());
    }

    @Test
    @DisplayName("依存lock集合が同じなら順序と重複を許容し、追加・削除・tenant・ID・mode変更を拒否すること")
    void dependencyFenceComparesCanonicalLockSet() {
        var engineer = LifecycleLockPlan.LockKey.shared(LifecycleLockPlan.Rank.ENGINEER, "tenant-a", 4);
        var document = LifecycleLockPlan.LockKey.shared(LifecycleLockPlan.Rank.DOCUMENT, "tenant-a", 12);
        var selected = LifecycleLockPlan.from(List.of(engineer, document));

        selected.requireSameDependencies(LifecycleLockPlan.from(List.of(document, engineer, document)));
        assertThrows(LifecycleLockPlan.DependencyChangedException.class,
                () -> selected.requireSameDependencies(LifecycleLockPlan.from(List.of(engineer))));
        assertThrows(LifecycleLockPlan.DependencyChangedException.class,
                () -> selected.requireSameDependencies(LifecycleLockPlan.from(List.of(engineer, document,
                        LifecycleLockPlan.LockKey.exclusive(LifecycleLockPlan.Rank.CUSTOMER, "tenant-a", 8)))));
        assertThrows(LifecycleLockPlan.DependencyChangedException.class,
                () -> selected.requireSameDependencies(LifecycleLockPlan.from(List.of(engineer,
                        LifecycleLockPlan.LockKey.shared(LifecycleLockPlan.Rank.DOCUMENT, "tenant-b", 12)))));
        assertThrows(LifecycleLockPlan.DependencyChangedException.class,
                () -> selected.requireSameDependencies(LifecycleLockPlan.from(List.of(engineer,
                        LifecycleLockPlan.LockKey.shared(LifecycleLockPlan.Rank.DOCUMENT, "tenant-a", 13)))));
        assertThrows(LifecycleLockPlan.DependencyChangedException.class,
                () -> selected.requireSameDependencies(LifecycleLockPlan.from(List.of(engineer,
                        LifecycleLockPlan.LockKey.exclusive(LifecycleLockPlan.Rank.DOCUMENT, "tenant-a", 12)))));
    }

    private static java.util.SortedMap<Long, Long> organizations(long organizationId, long epoch) {
        return new java.util.TreeMap<>(java.util.Map.of(organizationId, epoch));
    }

    @Test
    @DisplayName("CONFIRMのrequest hashは確認の束縛と判断を含みSUBMITのcanonical JSONは変わらないこと（W3-21a）")
    void confirmRequestHashBindsSubmissionAndDecision() {
        var binding = new LifecycleCanonicalization.ConfirmationBinding(31, 1, HASH_A.toUpperCase());
        var confirm = LifecycleCanonicalization.Request.confirm("tenant-a", 1, 2, 9, "idem-1", "確認しました",
                binding, LifecycleCanonicalization.Decision.CONFIRM);
        var reject = LifecycleCanonicalization.Request.confirm("tenant-a", 1, 2, 9, "idem-1", "確認しました",
                binding, LifecycleCanonicalization.Decision.REJECT);
        var otherRevision = LifecycleCanonicalization.Request.confirm("tenant-a", 1, 2, 9, "idem-1", "確認しました",
                new LifecycleCanonicalization.ConfirmationBinding(31, 2, HASH_A), LifecycleCanonicalization.Decision.CONFIRM);
        var otherEvidence = LifecycleCanonicalization.Request.confirm("tenant-a", 1, 2, 9, "idem-1", "確認しました",
                new LifecycleCanonicalization.ConfirmationBinding(31, 1, HASH_B), LifecycleCanonicalization.Decision.CONFIRM);
        var lowerCase = LifecycleCanonicalization.Request.confirm("tenant-a", 1, 2, 9, "idem-1", "確認しました",
                new LifecycleCanonicalization.ConfirmationBinding(31, 1, HASH_A), LifecycleCanonicalization.Decision.CONFIRM);

        assertNotEquals(confirm.sha256(), reject.sha256(), "同じkeyで判断だけを変えた再送は別のhash（409）");
        assertNotEquals(confirm.sha256(), otherRevision.sha256());
        assertNotEquals(confirm.sha256(), otherEvidence.sha256());
        assertEquals(confirm.sha256(), lowerCase.sha256(), "証跡hashは小文字hexへ正規化して束縛する");
        assertTrue(confirm.sha256().matches("[0-9a-f]{64}"));
        assertTrue(confirm.canonicalJson().contains("\"decision\":\"CONFIRM\""));
        assertTrue(confirm.canonicalJson().contains("\"submissionId\":31"));

        var submit = new LifecycleCanonicalization.Request("tenant-a", 1, 2,
                LifecycleCanonicalization.Operation.SUBMIT, 7, "idem-1", "受領確認",
                List.of(new LifecycleCanonicalization.DocumentSelection(10, 101)));
        assertFalse(submit.canonicalJson().contains("decision"));
        assertFalse(submit.canonicalJson().contains("confirmation"));
        assertThrows(IllegalArgumentException.class, () -> new LifecycleCanonicalization.Request("tenant-a", 1, 2,
                LifecycleCanonicalization.Operation.SUBMIT, 7, "idem-1", "", List.of(), binding,
                LifecycleCanonicalization.Decision.CONFIRM));
        assertThrows(NullPointerException.class, () -> new LifecycleCanonicalization.Request("tenant-a", 1, 2,
                LifecycleCanonicalization.Operation.CONFIRM, 7, "idem-1", "", List.of()));
    }

    @Test
    @DisplayName("保存する証跡canonical JSONはtenantId・文書ID・版ID・版番号・sha256・文書epochだけを持ち title・氏名・commentを含まないこと（§12 O-8）")
    void persistedEvidenceJsonCarriesOnlyIdentifiersAndHashes() throws Exception {
        var evidence = new LifecycleCanonicalization.EvidenceSnapshot("tenant-a", List.of(
                new LifecycleCanonicalization.DocumentVersionSnapshot(10, 101, 3, HASH_A, 7)));
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(evidence.canonicalJson());

        assertEquals(java.util.Set.of("tenantId", "documents"), fieldNames(json));
        assertEquals(1, json.get("documents").size());
        assertEquals(java.util.Set.of("documentId", "documentVersionId", "versionNo", "sha256",
                "evidenceEligibilityEpoch"), fieldNames(json.get("documents").get(0)));
        String text = evidence.canonicalJson().toLowerCase(java.util.Locale.ROOT);
        for (String forbidden : List.of("title", "name", "comment", "storage", "key", "path")) {
            assertFalse(text.contains(forbidden), forbidden);
        }
        // EvidenceSnapshotは文書titleや氏名・commentを受け取る引数自体を持たない
        assertEquals(java.util.Set.of("tenantId", "documents"), java.util.Arrays.stream(
                LifecycleCanonicalization.EvidenceSnapshot.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).collect(java.util.stream.Collectors.toSet()));
        assertEquals(java.util.Set.of("documentId", "documentVersionId", "versionNo", "sha256",
                "evidenceEligibilityEpoch"), java.util.Arrays.stream(
                LifecycleCanonicalization.DocumentVersionSnapshot.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).collect(java.util.stream.Collectors.toSet()));
    }

    private static java.util.Set<String> fieldNames(com.fasterxml.jackson.databind.JsonNode node) {
        java.util.Set<String> names = new java.util.TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
