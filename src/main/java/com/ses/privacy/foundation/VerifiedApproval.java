package com.ses.privacy.foundation;

/** 両envelopeの信頼検証成功後にのみ生成される、process内の検証証明。 */
public final class VerifiedApproval {
    private final String tenantId;
    private final String caseId;
    private final String policyDigest;
    private final String action;
    private final String providerId;
    private final String targetId;
    private final String targetVersion;
    private final String targetHash;
    private final String caseRevision;
    private final String policyRevision;
    private final java.time.Instant expiresAt;

    VerifiedApproval(String tenantId, String caseId, String policyDigest, String action, String providerId,
                     String targetId, String targetVersion, String targetHash,
                     String caseRevision, String policyRevision, java.time.Instant expiresAt) {
        this.tenantId = tenantId;
        this.caseId = caseId;
        this.policyDigest = policyDigest;
        this.action = action;
        this.providerId = providerId;
        this.targetId = targetId;
        this.targetVersion = targetVersion;
        this.targetHash = targetHash;
        this.caseRevision = caseRevision;
        this.policyRevision = policyRevision;
        this.expiresAt = expiresAt;
    }

    public String tenantId() { return tenantId; }
    public String caseId() { return caseId; }
    public String policyDigest() { return policyDigest; }
    public String action() { return action; }
    public String providerId() { return providerId; }
    public String targetId() { return targetId; }
    public String targetVersion() { return targetVersion; }
    public String targetHash() { return targetHash; }
    public String caseRevision() { return caseRevision; }
    public String policyRevision() { return policyRevision; }
    public java.time.Instant expiresAt() { return expiresAt; }

    public boolean matches(TransitionBinding binding, java.time.Instant now) {
        return tenantId.equals(binding.tenantId()) && caseId.equals(binding.caseId())
                && targetId.equals(binding.targetId()) && targetVersion.equals(binding.targetVersion())
                && targetHash.equals(binding.targetHash()) && policyDigest.equals(binding.policyDigest())
                && caseRevision.equals(binding.caseRevision()) && policyRevision.equals(binding.policyRevision())
                && action.equals(binding.action()) && providerId.equals(binding.providerId())
                && now.isBefore(expiresAt);
    }
}
