package com.ses.service.ai.copilot.dispatch;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * claim transactionで読むsummary dispatch attempt行の写し（mapperの結果専用）。
 * <p>DBの値であり、ここからticket・permit・費用capabilityを作らない。{@link DispatchTicket#mismatches} が
 * 発行時snapshotと列ごとに照合するためだけに使う。claim IDは {@code BIN_TO_UUID(dispatch_claim_id, 0)} の36文字表記。</p>
 */
@Data
public class DispatchRunRow {
    private Long id;
    private String tenantId;
    private String traceId;
    private String useCase;
    private String status;
    private Integer statusVersion;
    private String dispatchState;
    private String dispatchClaimIdText;
    private byte[] dispatchNonceHash;
    private byte[] dispatchIdempotencyHash;
    private byte[] dispatchBindingHash;
    private byte[] preflightSnapshotHash;
    private byte[] dispatchRequestSha256;
    private LocalDateTime dispatchIssuedAt;
    private LocalDateTime dispatchExpiresAt;
    private LocalDateTime dispatchClaimedAt;
    private LocalDateTime dispatchStartedAt;
    private String dispatchProvider;
    private String dispatchModelRevision;
    private String dispatchRegion;
    private String dispatchTier;
    private byte[] dispatchConfigHash;
    private Long dispatchArtifactVersionId;
    private byte[] dispatchScopeHash;
    private byte[] dispatchInputHash;
    private byte[] dispatchApprovalDigest;
    private String dispatchCardId;
    private String dispatchCardVersion;
    private byte[] dispatchCardDigest;
    private String dispatchPolicyId;
    private String dispatchPolicyVersion;
    private byte[] dispatchPolicyDigest;
}
