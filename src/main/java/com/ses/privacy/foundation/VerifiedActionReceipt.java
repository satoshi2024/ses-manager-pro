package com.ses.privacy.foundation;

/** 将来provider receiptで検証する証明。SF-W1ではproviderも発行処理も実装しない。 */
public final class VerifiedActionReceipt {
    private final String tenantId;
    private final String caseId;
    private final String authorizationId;
    private final String resultHash;
    private final String bindingFingerprint;
    private final java.time.Instant expiresAt;

    VerifiedActionReceipt(String tenantId, String caseId, String authorizationId, String resultHash,
                          String bindingFingerprint, java.time.Instant expiresAt) {
        this.tenantId = tenantId;
        this.caseId = caseId;
        this.authorizationId = authorizationId;
        this.resultHash = resultHash;
        this.bindingFingerprint = bindingFingerprint;
        this.expiresAt = expiresAt;
    }

    public String tenantId() { return tenantId; }
    public String caseId() { return caseId; }
    public String authorizationId() { return authorizationId; }
    public String resultHash() { return resultHash; }
    public String bindingFingerprint() { return bindingFingerprint; }
    public java.time.Instant expiresAt() { return expiresAt; }

    public boolean matches(TransitionBinding binding, VerifiedDispatchFence fence, java.time.Instant now) {
        return tenantId.equals(binding.tenantId()) && caseId.equals(binding.caseId())
                && authorizationId.equals(fence.authorizationId())
                && bindingFingerprint.equals(binding.fingerprint())
                && now.isBefore(expiresAt);
    }
}
