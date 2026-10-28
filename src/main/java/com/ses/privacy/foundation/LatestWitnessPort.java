package com.ses.privacy.foundation;

import java.util.List;

/** 独立witnessのappend-only履歴と単調latest境界。 */
interface LatestWitnessPort {
    List<SignedWatermark> history(Partition partition);
    Latest latest(Partition partition);
    AppendResult append(SignedWatermark checkpoint, long expectedEpoch, String expectedCheckpointHash);

    enum AppendResult { APPENDED, REPLAYED, QUARANTINED }
    record Partition(String journalId, String tenantId, String subjectRef) {
        public Partition {
            if (journalId == null || journalId.isBlank() || tenantId == null || tenantId.isBlank()
                    || subjectRef == null || subjectRef.isBlank()) throw new IllegalArgumentException("witness partitionが必要です");
        }
    }
    record Latest(Partition partition, long epoch, long eventRevision, String eventHash, String checkpointHash) { }
}
