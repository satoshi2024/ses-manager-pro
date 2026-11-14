package com.ses.privacy.foundation;

import java.time.Instant;
import java.util.List;

/** candidate detailは現在状態の再検証成功時だけ返す。 */
record PreviewResult(String requestId, long caseRevision, String status, String reasonCode,
                     String snapshotRef, List<PreviewItem> items, Instant observedFrom,
                     Instant observedTo, Instant validatedAt, String dispositionLabel) {
    PreviewResult { items = List.copyOf(items); observedFrom = SyntheticCanonicalizer.toMicros(observedFrom); observedTo = SyntheticCanonicalizer.toMicros(observedTo); }
    record PreviewItem(String syntheticTargetId, String targetVersion, String contentHash, Instant observedAt,
                       Instant metadataObservedAt, String status) {
        PreviewItem { observedAt = SyntheticCanonicalizer.toMicros(observedAt); metadataObservedAt = SyntheticCanonicalizer.toMicros(metadataObservedAt); }
    }
    static PreviewResult closed(String request, long revision, String status, String reason, String ref) {
        return new PreviewResult(request, revision, status, reason, ref, List.of(), null, null, null, "PREVIEW_ONLY");
    }
}
