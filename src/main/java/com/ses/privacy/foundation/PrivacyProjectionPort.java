package com.ses.privacy.foundation;

/** journalから検証済みのeventだけを受け取る再構築可能projection境界。 */
interface PrivacyProjectionPort {
    ProjectionResult apply(JournalProjectionService.VerifiedJournalEvent event);
    default ProjectionResult applyRecovery(JournalProjectionService.VerifiedJournalEvent event,
            JdbcPrivacyPartitionFence.Lease lease) {
        return ProjectionResult.quarantine(event == null || event.event() == null ? 0 : event.event().revision(),
                event == null || event.event() == null ? null : event.event().eventHash(),
                QuarantineCode.PARTITION_FENCED);
    }
    ProjectionSnapshot snapshot(PrivacyJournalPort.JournalPartition partition);
    default ProjectionSnapshot snapshotForNormalProjection(PrivacyJournalPort.JournalPartition partition) {
        return snapshot(partition);
    }

    enum Status { APPLIED, NO_OP, QUARANTINED }
    enum QuarantineCode {
        NONE, ACK_INVALID, ACK_NOT_FOUND, JOURNAL_UNAVAILABLE, JOURNAL_CHAIN_INVALID,
        PROJECTION_MISMATCH, REVISION_GAP, REVISION_BACKWARD, HASH_CONFLICT, CAS_CONFLICT,
        PROJECTION_UNAVAILABLE, PARTITION_FENCED, DEADLINE_EXCEEDED,
        COMMIT_CONFIRMED_QUARANTINED, COMMIT_OUTCOME_UNKNOWN
    }
    record ProjectionResult(Status status, long revision, String eventHash, QuarantineCode reason) {
        static ProjectionResult applied(long revision, String hash) { return new ProjectionResult(Status.APPLIED, revision, hash, QuarantineCode.NONE); }
        static ProjectionResult noOp(long revision, String hash) { return new ProjectionResult(Status.NO_OP, revision, hash, QuarantineCode.NONE); }
        static ProjectionResult quarantine(long revision, String hash, QuarantineCode reason) {
            return new ProjectionResult(Status.QUARANTINED, revision, hash, reason);
        }
    }
    record ProjectionSnapshot(PrivacyJournalPort.JournalPartition partition, long revision, String eventHash,
                              boolean held, String lastActionId, String lastActionState) { }
}
