package com.ses.service.ai.copilot.dispatch;

import java.time.LocalDateTime;

/**
 * READY化・claim CASのSQLへ渡す発行時bindingの列値（mapperの引数専用）。
 * <p>authorityではなく、{@link DispatchTicket} から作る写しである。DB値やこの値からticket・permitは作れない。
 * nonce・idempotency keyの生値は持たず、hashだけを持つ。時刻はUTCの {@link LocalDateTime}（DATETIME(6)）。
 * claim IDは小文字36文字表記で、binaryへの変換はmapperの {@code UUID_TO_BIN(?, 0)} だけが行う（計画 D-2）。</p>
 */
public final class DispatchColumns {

    private final Long runId;
    private final String tenantId;
    private final String traceId;
    private final String useCase;
    private final String claimId;
    private final byte[] nonceHash;
    private final byte[] idempotencyHash;
    private final byte[] bindingHash;
    private final byte[] preflightSnapshotHash;
    private final byte[] requestSha256;
    private final LocalDateTime issuedAt;
    private final LocalDateTime expiresAt;
    private final String provider;
    private final String modelRevision;
    private final String region;
    private final String tier;
    private final byte[] configHash;
    private final Long artifactVersionId;
    private final byte[] scopeHash;
    private final byte[] inputHash;
    private final byte[] approvalDigest;
    private final String cardId;
    private final String cardVersion;
    private final byte[] cardDigest;
    private final String policyId;
    private final String policyVersion;
    private final byte[] policyDigest;

    DispatchColumns(DispatchTicket ticket) {
        DispatchIssueRequest request = ticket.request();
        this.runId = ticket.runId();
        this.tenantId = ticket.tenantId();
        this.traceId = ticket.traceId();
        this.useCase = DispatchTicket.USE_CASE;
        this.claimId = ticket.claimId();
        this.nonceHash = ticket.nonceHash();
        this.idempotencyHash = ticket.idempotencyHash();
        this.bindingHash = ticket.bindingHash();
        this.preflightSnapshotHash = DispatchDigests.hex(request.preflightSnapshotHash());
        this.requestSha256 = DispatchDigests.hex(request.requestSha256());
        this.issuedAt = DispatchDigests.utc(ticket.issuedAt());
        this.expiresAt = DispatchDigests.utc(ticket.expiresAt());
        this.provider = request.provider();
        this.modelRevision = request.modelRevision();
        this.region = request.region();
        this.tier = request.tier();
        this.configHash = DispatchDigests.hex(request.configHash());
        this.artifactVersionId = request.artifactVersionId();
        this.scopeHash = DispatchDigests.hex(request.scopeHash());
        this.inputHash = DispatchDigests.hex(request.inputHash());
        this.approvalDigest = DispatchDigests.hex(request.approvalDigest());
        this.cardId = request.cardId();
        this.cardVersion = request.cardVersion();
        this.cardDigest = DispatchDigests.hex(request.cardDigest());
        this.policyId = request.policyId();
        this.policyVersion = request.policyVersion();
        this.policyDigest = DispatchDigests.hex(request.policyDigest());
    }

    public Long getRunId() { return runId; }
    public String getTenantId() { return tenantId; }
    public String getTraceId() { return traceId; }
    public String getUseCase() { return useCase; }
    public String getClaimId() { return claimId; }
    public byte[] getNonceHash() { return nonceHash.clone(); }
    public byte[] getIdempotencyHash() { return idempotencyHash.clone(); }
    public byte[] getBindingHash() { return bindingHash.clone(); }
    public byte[] getPreflightSnapshotHash() { return preflightSnapshotHash.clone(); }
    public byte[] getRequestSha256() { return requestSha256.clone(); }
    public LocalDateTime getIssuedAt() { return issuedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public String getProvider() { return provider; }
    public String getModelRevision() { return modelRevision; }
    public String getRegion() { return region; }
    public String getTier() { return tier; }
    public byte[] getConfigHash() { return configHash.clone(); }
    public Long getArtifactVersionId() { return artifactVersionId; }
    public byte[] getScopeHash() { return scopeHash.clone(); }
    public byte[] getInputHash() { return inputHash.clone(); }
    public byte[] getApprovalDigest() { return approvalDigest.clone(); }
    public String getCardId() { return cardId; }
    public String getCardVersion() { return cardVersion; }
    public byte[] getCardDigest() { return cardDigest.clone(); }
    public String getPolicyId() { return policyId; }
    public String getPolicyVersion() { return policyVersion; }
    public byte[] getPolicyDigest() { return policyDigest.clone(); }
}
