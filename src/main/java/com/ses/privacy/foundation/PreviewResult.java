package com.ses.privacy.foundation;

import java.time.Instant;
import java.util.List;

/** Candidate detailを返すのは後验再検証成功時だけ。 */
record PreviewResult(String requestId, long caseRevision, String status, String reasonCode,
                     String snapshotRef, List<PreviewItem> items, Instant observedFrom,
                     Instant observedTo, Instant validatedAt, String dispositionLabel) {
    PreviewResult { items = List.copyOf(items); }
    record PreviewItem(String syntheticTargetId, String targetVersion, String contentHash, Instant observedAt, String status) { }
    static PreviewResult closed(String request, long revision, String status, String reason, String ref) {
        return new PreviewResult(request, revision, status, reason, ref, List.of(), null, null, null, "PREVIEW_ONLY");
    }
}
