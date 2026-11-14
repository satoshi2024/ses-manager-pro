package com.ses.privacy.foundation;

import com.ses.privacy.foundation.PrivacyJournalPort.JournalPartition;
import com.ses.privacy.foundation.PrivacyProjectionPort.ProjectionResult;
import com.ses.privacy.foundation.PrivacyProjectionPort.ProjectionSnapshot;
import org.springframework.core.env.Environment;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.time.Duration;

/** partitionからlive authorityを読み直すtest-only recovery候補。成功後も隔離を解除しない。
 * D2bのmonotonic deadline/statement timeoutは実装したが、end-to-end上限や未検証matrixは別gateに残る。
 */
final class PrivacyRecoveryService {
    enum Status { RECOVERED_SYNTHETIC, QUARANTINED, ABORTED_UNKNOWN }
    /** applied/noOpはこのrecover呼出し中に実行されたapplyRecoveryの結果を試行間で合算する。 */
    record Result(Status status, String reason, long revision, String eventHash,
                  int appliedCount, int noOpCount, long generation) { }

    private static final int STABLE_READ_ATTEMPTS = 3;
    private static final int RECOVERY_ATTEMPTS = 3;
    private final LatestWitnessReader witness;
    private final SignedWatermarkVerifier verifier;
    private final PrivacyJournalSnapshotReader journal;
    private final PrivacyRecoveryProjectionPort projection;
    private final JdbcPrivacyPartitionFence fence;
    private final Environment environment;

    PrivacyRecoveryService(LatestWitnessReader witness, SignedWatermarkVerifier verifier,
            PrivacyJournalSnapshotReader journal, PrivacyRecoveryProjectionPort projection,
            JdbcPrivacyPartitionFence fence, Environment environment) {
        this.witness = Objects.requireNonNull(witness, "witness");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.projection = Objects.requireNonNull(projection, "projection");
        this.fence = Objects.requireNonNull(fence, "fence");
        this.environment = Objects.requireNonNull(environment, "environment");
        requireTestProfile();
    }

    /** 入力はpartitionだけ。trusted snapshot、ACK、履歴、latestは受け取らない。 */
    Result recover(LatestWitnessPort.Partition partition) {
        return recover(partition, RecoveryDeadline.startDefault());
    }

    Result recover(LatestWitnessPort.Partition partition, Duration workBudgetForTest) {
        return recover(partition, RecoveryDeadline.startForTest(workBudgetForTest));
    }

    private Result recover(LatestWitnessPort.Partition partition, RecoveryDeadline deadline) {
        requireTestProfile();
        Objects.requireNonNull(partition, "partition");
        JdbcPrivacyPartitionFence.Lease acquired;
        try {
            acquired = fence.quarantine(new JournalPartition(partition.tenantId(), partition.subjectRef()), deadline);
        } catch (JdbcPrivacyPartitionFence.AcquisitionUnconfirmedException | RecoveryDeadline.ExpiredException unknown) {
            return new Result(Status.ABORTED_UNKNOWN, "FENCE_ACQUIRE_UNCONFIRMED", 0, null, 0, 0, 0);
        }
        LeaseHolder holder = new LeaseHolder(acquired);
        String lastReason = "RETRY_EXHAUSTED";
        int applied = 0;
        int noOp = 0;
        try {
        for (int attempt = 0; attempt < RECOVERY_ATTEMPTS; attempt++) {
            deadline.check();
            StableWitness firstWitness = readStableWitness(partition, deadline);
            deadline.check();
            if (firstWitness == null) return quarantined(lastReason = "WITNESS_UNSTABLE", 0, null, applied, noOp, holder.lease);
            SignedWatermarkVerifier.Result witnessResult = verifier.verify(partition,
                    firstWitness.history(), firstWitness.latest());
            if (witnessResult.status() != SignedWatermarkVerifier.Status.VALID) {
                return quarantined("WITNESS_" + witnessResult.reason().name(),
                        firstWitness.latest() == null ? 0 : firstWitness.latest().eventRevision(),
                        firstWitness.latest() == null ? null : firstWitness.latest().eventHash(), applied, noOp, holder.lease);
            }

            PrivacyJournalSnapshotReader.Snapshot journalSnapshot;
            try {
                journalSnapshot = journal.read(new JournalPartition(partition.tenantId(), partition.subjectRef()), deadline);
                deadline.check();
            } catch (RuntimeException unavailable) {
                if (unavailable instanceof RecoveryDeadline.ExpiredException) throw unavailable;
                if (causedByTimeout(unavailable)) return quarantined("JOURNAL_READ_TIMEOUT", 0, null, applied, noOp, holder.lease);
                lastReason = "JOURNAL_UNAVAILABLE";
                continue;
            }
            if (journalSnapshot == null || !JournalProjectionService.verifyChain(
                    new JournalPartition(partition.tenantId(), partition.subjectRef()),
                    journalSnapshot.head(), journalSnapshot.events())
                    || !journalHeadMatchesEvents(journalSnapshot.head(), journalSnapshot.events())) {
                return quarantined("JOURNAL_CHAIN_INVALID", 0, null, applied, noOp, holder.lease);
            }
            if (journalSnapshot.head().revision() != witnessResult.latest().checkpoint().eventRevision()
                    || !journalSnapshot.head().eventHash().equals(witnessResult.latest().checkpoint().eventHash())) {
                lastReason = "WITNESS_JOURNAL_HEAD_MISMATCH";
                continue;
            }

            ProjectionSnapshot current;
            try {
                current = projection.snapshot(new JournalPartition(partition.tenantId(), partition.subjectRef()), holder.lease);
            } catch (RuntimeException unavailable) {
                if (unavailable instanceof JdbcPrivacyPartitionFence.StaleOwnerException) {
                    return quarantined("OWNER_GENERATION_STALE", journalSnapshot.head().revision(),
                            journalSnapshot.head().eventHash(), applied, noOp, holder.lease);
                }
                if (unavailable instanceof RecoveryDeadline.ExpiredException) throw unavailable;
                if (causedByTimeout(unavailable)) return quarantined("PROJECTION_READ_TIMEOUT",
                        journalSnapshot.head().revision(), journalSnapshot.head().eventHash(), applied, noOp, holder.lease);
                return quarantined("PROJECTION_UNAVAILABLE", journalSnapshot.head().revision(),
                        journalSnapshot.head().eventHash(), applied, noOp, holder.lease);
            }
            if (!JournalProjectionService.snapshotMatchesJournal(new JournalPartition(partition.tenantId(), partition.subjectRef()),
                    current, journalSnapshot.events())) {
                return quarantined("PROJECTION_MISMATCH", journalSnapshot.head().revision(),
                        journalSnapshot.head().eventHash(), applied, noOp, holder.lease);
            }

            List<JournalProjectionService.VerifiedJournalEvent> verifiedEvents;
            try {
                verifiedEvents = JournalProjectionService.verifiedEvents(new JournalPartition(partition.tenantId(), partition.subjectRef()),
                        journalSnapshot.head(), journalSnapshot.events());
            } catch (RuntimeException invalid) {
                return quarantined("JOURNAL_CHAIN_INVALID", 0, null, applied, noOp, holder.lease);
            }
            for (int index = Math.toIntExact(current.revision()); index < verifiedEvents.size(); index++) {
                deadline.check();
                ProjectionResult result;
                try {
                    result = projection.applyRecovery(verifiedEvents.get(index), holder.lease);
                } catch (RuntimeException unavailable) {
                    if (unavailable instanceof RecoveryDeadline.ExpiredException) throw unavailable;
                    if (causedByTimeout(unavailable)) return quarantined("REPLAY_READ_TIMEOUT",
                            journalSnapshot.head().revision(), journalSnapshot.head().eventHash(), applied, noOp, holder.lease);
                    return quarantined("PROJECTION_UNAVAILABLE", journalSnapshot.head().revision(),
                            journalSnapshot.head().eventHash(), applied, noOp, holder.lease);
                }
                if (result.status() == PrivacyProjectionPort.Status.QUARANTINED) {
                    if (result.reason() == PrivacyProjectionPort.QuarantineCode.PARTITION_FENCED) {
                        return quarantined("OWNER_GENERATION_STALE", journalSnapshot.head().revision(),
                                journalSnapshot.head().eventHash(), applied, noOp, holder.lease);
                    }
                    String reason = "REPLAY_" + result.reason().name();
                    return quarantined(reason, journalSnapshot.head().revision(),
                            journalSnapshot.head().eventHash(), applied, noOp, holder.lease);
                }
                if (result.status() == PrivacyProjectionPort.Status.APPLIED) applied++;
                else if (result.status() == PrivacyProjectionPort.Status.NO_OP) noOp++;
                else return quarantined("REPLAY_STATUS_INVALID", journalSnapshot.head().revision(),
                            journalSnapshot.head().eventHash(), applied, noOp, holder.lease);
                ProjectionSnapshot observed;
                try {
                    observed = projection.snapshot(new JournalPartition(partition.tenantId(), partition.subjectRef()), holder.lease);
                } catch (RuntimeException unavailable) {
                    if (unavailable instanceof JdbcPrivacyPartitionFence.StaleOwnerException) {
                        return quarantined("OWNER_GENERATION_STALE", journalSnapshot.head().revision(),
                                journalSnapshot.head().eventHash(), applied, noOp, holder.lease);
                    }
                    if (unavailable instanceof RecoveryDeadline.ExpiredException) throw unavailable;
                    if (causedByTimeout(unavailable)) return quarantined("REPLAY_READ_TIMEOUT",
                            journalSnapshot.head().revision(), journalSnapshot.head().eventHash(), applied, noOp, holder.lease);
                    return quarantined("PROJECTION_UNAVAILABLE", journalSnapshot.head().revision(),
                            journalSnapshot.head().eventHash(), applied, noOp, holder.lease);
                }
                if (!JournalProjectionService.snapshotMatchesJournal(new JournalPartition(partition.tenantId(), partition.subjectRef()),
                        observed, journalSnapshot.events()) || observed.revision() < index + 1L) {
                    return quarantined("REPLAY_READBACK_MISMATCH", journalSnapshot.head().revision(),
                            journalSnapshot.head().eventHash(), applied, noOp, holder.lease);
                }
            }

            StableWitness finalWitness = readStableWitness(partition, deadline);
            deadline.check();
            PrivacyJournalSnapshotReader.Snapshot finalJournal;
            ProjectionSnapshot finalProjection;
            try {
                finalJournal = journal.read(new JournalPartition(partition.tenantId(), partition.subjectRef()), deadline);
                deadline.check();
                finalProjection = projection.snapshot(new JournalPartition(partition.tenantId(), partition.subjectRef()), holder.lease);
            } catch (JdbcPrivacyPartitionFence.StaleOwnerException staleOwner) {
                return quarantined("OWNER_GENERATION_STALE", 0, null, applied, noOp, holder.lease);
            } catch (RuntimeException unavailable) {
                if (unavailable instanceof RecoveryDeadline.ExpiredException) throw unavailable;
                if (causedByTimeout(unavailable)) return quarantined("FINAL_READ_TIMEOUT", 0, null, applied, noOp, holder.lease);
                lastReason = "FINAL_READ_UNAVAILABLE";
                continue;
            }
            if (finalWitness == null || finalJournal == null ||
                    !sameWitness(firstWitness, finalWitness) ||
                    !JournalProjectionService.verifyChain(new JournalPartition(partition.tenantId(), partition.subjectRef()),
                            finalJournal.head(), finalJournal.events()) ||
                    !journalHeadMatchesEvents(finalJournal.head(), finalJournal.events()) ||
                    finalJournal.head().revision() != finalWitness.latest().eventRevision() ||
                    !finalJournal.head().eventHash().equals(finalWitness.latest().eventHash()) ||
                    !JournalProjectionService.snapshotMatchesJournal(new JournalPartition(partition.tenantId(), partition.subjectRef()),
                            finalProjection, finalJournal.events()) ||
                    finalProjection.revision() != finalJournal.head().revision() ||
                    !finalProjection.eventHash().equals(finalJournal.head().eventHash())) {
                lastReason = "FINAL_STATE_ADVANCED_OR_MISMATCHED";
                continue;
            }
            SignedWatermarkVerifier.Result finalVerified = verifier.verify(partition,
                    finalWitness.history(), finalWitness.latest());
            if (finalVerified.status() != SignedWatermarkVerifier.Status.VALID) {
                return quarantined("FINAL_WITNESS_" + finalVerified.reason().name(), finalJournal.head().revision(),
                        finalJournal.head().eventHash(), applied, noOp, holder.lease);
            }
            deadline.check();
            return new Result(Status.RECOVERED_SYNTHETIC, "VERIFIED_LIVE_HEAD", finalJournal.head().revision(),
                    finalJournal.head().eventHash(), applied, noOp, holder.lease.generation());
        }
        return quarantined(lastReason, 0, null, applied, noOp, holder.lease);
        } catch (RecoveryDeadline.ExpiredException expired) {
            return quarantined("RECOVERY_DEADLINE_EXCEEDED", 0, null, applied, noOp, holder.lease);
        } catch (RecoveryDeadline.PhaseTimeoutException phaseTimeout) {
            return quarantined(phaseTimeout.phase(), 0, null, applied, noOp, holder.lease);
        }
    }

    private static boolean causedByTimeout(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String name = current.getClass().getSimpleName();
            if (name.contains("Timeout") || name.contains("TimedOut")) return true;
        }
        return false;
    }

    private StableWitness readStableWitness(LatestWitnessPort.Partition partition, RecoveryDeadline deadline) {
        for (int readAttempt = 0; readAttempt < STABLE_READ_ATTEMPTS; readAttempt++) {
            try {
                deadline.check();
                List<SignedWatermark> historyA = List.copyOf(witness.history(partition, deadline));
                deadline.check();
                LatestWitnessPort.Latest latestA = witness.latest(partition, deadline);
                deadline.check();
                List<SignedWatermark> historyB = List.copyOf(witness.history(partition, deadline));
                deadline.check();
                LatestWitnessPort.Latest latestB = witness.latest(partition, deadline);
                deadline.check();
                if (sameHistory(historyA, historyB) && sameLatest(latestA, latestB)) {
                    return new StableWitness(historyB, latestB);
                }
            } catch (RuntimeException unavailable) {
                if (unavailable instanceof RecoveryDeadline.ExpiredException) throw unavailable;
                if (causedByTimeout(unavailable)) throw new RecoveryDeadline.PhaseTimeoutException("WITNESS_READ_TIMEOUT", unavailable);
                // 読取失敗を値として扱わず、回数上限後に隔離結果へ倒す。
            }
        }
        return null;
    }

    private static boolean sameWitness(StableWitness left, StableWitness right) {
        return sameHistory(left.history(), right.history()) && sameLatest(left.latest(), right.latest());
    }

    private static boolean sameHistory(List<SignedWatermark> left, List<SignedWatermark> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            SignedWatermark a = left.get(i);
            SignedWatermark b = right.get(i);
            if (a == null || b == null || !a.checkpoint().equals(b.checkpoint())
                    || !MessageDigest.isEqual(a.canonicalPayload(), b.canonicalPayload())
                    || !MessageDigest.isEqual(a.signature(), b.signature())
                    || !a.checkpointHash().equals(b.checkpointHash())
                    || !a.signatureDigest().equals(b.signatureDigest())) return false;
        }
        return true;
    }

    private static boolean sameLatest(LatestWitnessPort.Latest left, LatestWitnessPort.Latest right) {
        if (left == null || right == null) return left == right;
        try { return MessageDigest.isEqual(latestBytes(left), latestBytes(right)); }
        catch (RuntimeException malformed) { return false; }
    }

    private static boolean journalHeadMatchesEvents(PrivacyJournalPort.JournalHead head,
            List<PrivacyJournalPort.JournalEvent> events) {
        boolean held = false;
        long generation = 0;
        String owner = null;
        java.time.Instant leaseUntil = null;
        for (PrivacyJournalPort.JournalEvent event : events) {
            switch (event.type()) {
                case HOLD_STARTED -> {
                    if (held) return false;
                    held = true;
                }
                case HOLD_RELEASED -> { return false; }
                case LEASE_CLAIMED, LEASE_TAKEN_OVER -> {
                    if (event.expectedLeaseGeneration() != generation || event.workerId() == null
                            || event.leaseUntil() == null || !event.createdAt().isBefore(event.leaseUntil())) return false;
                    if (event.type() == PrivacyJournalPort.CommandType.LEASE_CLAIMED && owner != null
                            && event.createdAt().isBefore(leaseUntil)) return false;
                    if (event.type() == PrivacyJournalPort.CommandType.LEASE_TAKEN_OVER
                            && (owner == null || leaseUntil == null || event.createdAt().isBefore(leaseUntil))) return false;
                    generation++;
                    owner = event.workerId();
                    leaseUntil = event.leaseUntil();
                }
                case DISPATCH_AUTHORIZED -> {
                    if (held || event.expectedLeaseGeneration() != generation || owner == null
                            || !owner.equals(event.workerId()) || leaseUntil == null
                            || !event.createdAt().isBefore(leaseUntil)) return false;
                }
            }
        }
        return head != null && head.held() == held && head.leaseGeneration() == generation
                && Objects.equals(head.leaseOwner(), owner) && Objects.equals(head.leaseUntil(), leaseUntil);
    }

    private static byte[] latestBytes(LatestWitnessPort.Latest value) {
        byte[] journalId = value.partition().journalId().getBytes(StandardCharsets.UTF_8);
        byte[] tenantId = value.partition().tenantId().getBytes(StandardCharsets.UTF_8);
        byte[] subject = value.partition().subjectRef().getBytes(StandardCharsets.UTF_8);
        byte[] eventHash = value.eventHash().getBytes(StandardCharsets.US_ASCII);
        byte[] checkpointHash = value.checkpointHash().getBytes(StandardCharsets.US_ASCII);
        ByteBuffer bytes = ByteBuffer.allocate(8 * 2 + 4 * 5 + journalId.length + tenantId.length + subject.length
                + eventHash.length + checkpointHash.length);
        bytes.putLong(value.epoch()).putLong(value.eventRevision());
        for (byte[] field : List.of(journalId, tenantId, subject, eventHash, checkpointHash)) bytes.putInt(field.length).put(field);
        return bytes.array();
    }

    private Result quarantined(String reason, long revision, String hash, int applied, int noOp,
            JdbcPrivacyPartitionFence.Lease lease) {
        return new Result(Status.QUARANTINED, reason, revision, hash, applied, noOp, lease.generation());
    }

    private void requireTestProfile() {
        String[] active = environment.getActiveProfiles();
        String[] defaults = environment.getDefaultProfiles();
        if (!Arrays.asList(active).contains("test") || Arrays.stream(active).anyMatch("prod"::equals)
                || Arrays.stream(defaults).anyMatch("prod"::equals)) {
            throw new SecurityException("synthetic recoveryはprodを含まないactive test profile専用です");
        }
    }

    private record StableWitness(List<SignedWatermark> history, LatestWitnessPort.Latest latest) { }
    private record LeaseHolder(JdbcPrivacyPartitionFence.Lease lease) { }
}
