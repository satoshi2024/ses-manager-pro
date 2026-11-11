package com.ses.privacy.foundation;

/** 将来journalで検証する証明。SF-W1では発行処理を実装しない。 */
public final class VerifiedDispatchFence {
    private final String tenantId;
    private final String caseId;
    private final String authorizationId;
    private final String bindingFingerprint;
    private final java.time.Instant expiresAt;

    VerifiedDispatchFence(String tenantId, String caseId, String authorizationId,
                          String bindingFingerprint,
                          java.time.Instant expiresAt) {
        this.tenantId = tenantId;
        this.caseId = caseId;
        this.authorizationId = authorizationId;
        this.bindingFingerprint = bindingFingerprint;
        this.expiresAt = expiresAt;
    }

    public String tenantId() { return tenantId; }
    public String caseId() { return caseId; }
    public String authorizationId() { return authorizationId; }
    public String bindingFingerprint() { return bindingFingerprint; }

    public boolean matches(TransitionBinding binding, java.time.Instant now) {
        return tenantId.equals(binding.tenantId()) && caseId.equals(binding.caseId())
                && bindingFingerprint.equals(binding.fingerprint()) && now.isBefore(expiresAt);
    }
}
