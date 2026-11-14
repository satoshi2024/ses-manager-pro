package com.ses.privacy.foundation;

/** FSMへ渡す、現在も期待されるcase/target版。clientの承認flagは含めない。 */
public record TransitionBinding(String tenantId, String caseId, String targetId, String targetVersion,
                                String targetHash, String policyDigest, String caseRevision,
                                String policyRevision, String action, String providerId) {
    public TransitionBinding {
        if (tenantId == null || caseId == null || targetId == null || targetVersion == null
                || targetHash == null || policyDigest == null || caseRevision == null
                || policyRevision == null || action == null || providerId == null) {
            throw new IllegalArgumentException("FSM bindingの全fieldが必要です");
        }
    }

    public String fingerprint() {
        return ApprovalEnvelopeCanonicalizer.digest(ApprovalEnvelopeCanonicalizer.canonicalBindingBytes(this));
    }
}
