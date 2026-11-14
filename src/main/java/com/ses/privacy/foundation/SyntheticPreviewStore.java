package com.ses.privacy.foundation;

import java.time.Instant;
import java.util.List;

/** test harnessから一回だけ供給される、合成preview persistenceの限定port。 */
interface SyntheticPreviewStore {
    PreviewSnapshot find(String tenantId, String requestId, String idempotencyKey);
    CaseHead readCaseHead(String tenantId, String caseRef);
    PersistOutcome persist(PreviewDraft draft);

    enum PersistOutcome { COMMITTED, CAS_FAILED, DUPLICATE, ACK_UNKNOWN, FAILED }
    record CaseHead(long revision, String state) { }
    record PreviewSnapshot(String tenantId, String requestId, String idempotencyKey, String requestHash,
                           String caseRef, long baseCaseRevision, long caseRevision, String identityHash,
                           String witnessHash, String snapshotHash, String witnessJson, String itemsJson,
                           Instant observedAt, Instant observedTo) { }
    record PreviewDraft(String tenantId, String requestId, String idempotencyKey, String requestHash,
                        String caseRef, long baseCaseRevision, long caseRevision, String identityHash,
                        String witnessHash, String snapshotHash, String witnessJson, String itemsJson,
                        Instant observedAt, Instant observedTo, String targetId, String targetVersion,
                        String targetHash, String policyHash, String policyVersion, String providerId) { }
}
