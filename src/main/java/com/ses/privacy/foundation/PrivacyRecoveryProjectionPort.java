package com.ses.privacy.foundation;

/** normal投影・clear権限を含めないrecovery replay/readback専用境界。 */
interface PrivacyRecoveryProjectionPort {
    PrivacyProjectionPort.ProjectionSnapshot snapshot(PrivacyJournalPort.JournalPartition partition,
            JdbcPrivacyPartitionFence.Lease lease);
    PrivacyProjectionPort.ProjectionResult applyRecovery(JournalProjectionService.VerifiedJournalEvent event,
            JdbcPrivacyPartitionFence.Lease lease);
}
