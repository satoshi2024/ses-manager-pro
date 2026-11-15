package com.ses.service.ai.copilot.dispatch;

import com.ses.service.ai.AiGatewayRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * READY化したsummary dispatch attemptの発行時snapshot（プロセス内だけの値）。
 * <p>{@link CopilotDispatchClaimService#prepareReady} だけが作り、claimに一度だけ使える（{@link #markClaimStarted}）。
 * DB行・JSON・public DTOからは作れない（constructorはpackage内専用。{@code Serializable}・{@code Cloneable}・
 * setter・Jackson creatorを持たない）。プロセスが再起動するとticketは失われ、同じattemptを再claimする手段は無い。
 * DB上のfenceは別に {@code READY → CLAIMED} の完全一致CASが担う。</p>
 * <p>nonce・idempotency keyの生値はこのobjectの中だけに置き、DB・log・例外・監査へは出さない（hashだけを保存する）。</p>
 */
public final class DispatchTicket {

    static final String USE_CASE = AiGatewayRequest.USE_COPILOT;
    private static final String BINDING_VERSION = "nf08-dispatch-binding-v1";

    private final Long runId;
    private final String tenantId;
    private final String traceId;
    private final String claimId;
    private final DispatchIssueRequest request;
    private final byte[] nonce;
    private final byte[] idempotencyKey;
    private final byte[] nonceHash;
    private final byte[] idempotencyHash;
    private final Instant issuedAt;
    private final Instant expiresAt;
    private final long issuedNanos;
    private final byte[] bindingHash;
    private final AtomicBoolean claimStarted = new AtomicBoolean(false);

    DispatchTicket(Long runId, String tenantId, String traceId, String claimId, DispatchIssueRequest request,
                   byte[] nonce, byte[] idempotencyKey, Instant issuedAt, long issuedNanos) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.traceId = DispatchDigests.requireCanonicalUuid(traceId, "trace ID");
        this.claimId = DispatchDigests.requireCanonicalUuid(claimId, "claim ID");
        this.request = Objects.requireNonNull(request, "request");
        if (nonce == null || nonce.length < 16 || idempotencyKey == null || idempotencyKey.length < 16) {
            throw new IllegalArgumentException("nonceとidempotency keyは128bit以上である必要があります");
        }
        this.nonce = nonce.clone();
        this.idempotencyKey = idempotencyKey.clone();
        this.nonceHash = DispatchDigests.sha256(this.nonce);
        this.idempotencyHash = DispatchDigests.sha256(this.idempotencyKey);
        this.issuedAt = DispatchDigests.micros(Objects.requireNonNull(issuedAt, "issuedAt"));
        this.expiresAt = this.issuedAt.plus(request.attemptTtl());
        this.issuedNanos = issuedNanos;
        this.bindingHash = bindingDigest();
    }

    public Long runId() { return runId; }
    public String tenantId() { return tenantId; }
    public String traceId() { return traceId; }
    /** claim ID（小文字36文字表記）。ATTEMPT/RESULT監査のidempotency_key・費用entryの予約IDと同じ値。 */
    public String claimId() { return claimId; }
    public Instant issuedAt() { return issuedAt; }
    public Instant expiresAt() { return expiresAt; }

    DispatchIssueRequest request() { return request; }
    byte[] nonce() { return nonce.clone(); }
    byte[] idempotencyKey() { return idempotencyKey.clone(); }
    byte[] nonceHash() { return nonceHash.clone(); }
    byte[] idempotencyHash() { return idempotencyHash.clone(); }
    byte[] bindingHash() { return bindingHash.clone(); }
    long issuedNanos() { return issuedNanos; }

    DispatchColumns columns() {
        return new DispatchColumns(this);
    }

    /** このticketでのclaim開始を一度だけ記録する。二度目以降はfalse（同じattemptを再claimしない）。 */
    boolean markClaimStarted() {
        return claimStarted.compareAndSet(false, true);
    }

    /**
     * lock済みのDB行と発行時snapshotを列ごとに照合し、一致しない列名を返す（空なら全列一致）。
     * aggregate binding hashの一致だけで代替しない。値そのものは返さない（logへ出さない）。
     */
    List<String> mismatches(DispatchRunRow row) {
        List<String> differences = new ArrayList<>();
        if (row == null) {
            differences.add("row");
            return differences;
        }
        DispatchColumns expected = columns();
        check(differences, "id", Objects.equals(runId, row.getId()));
        check(differences, "tenant_id", Objects.equals(tenantId, row.getTenantId()));
        check(differences, "trace_id", Objects.equals(traceId, row.getTraceId()));
        check(differences, "use_case", Objects.equals(USE_CASE, row.getUseCase()));
        check(differences, "status", "PENDING".equals(row.getStatus()));
        check(differences, "status_version", Integer.valueOf(0).equals(row.getStatusVersion()));
        check(differences, "dispatch_state", DispatchState.READY.name().equals(row.getDispatchState()));
        check(differences, "dispatch_claim_id", Objects.equals(claimId, row.getDispatchClaimIdText()));
        check(differences, "dispatch_nonce_hash", Arrays.equals(nonceHash, row.getDispatchNonceHash()));
        check(differences, "dispatch_idempotency_hash",
                Arrays.equals(idempotencyHash, row.getDispatchIdempotencyHash()));
        check(differences, "dispatch_binding_hash", Arrays.equals(bindingHash, row.getDispatchBindingHash()));
        check(differences, "preflight_snapshot_hash",
                Arrays.equals(expected.getPreflightSnapshotHash(), row.getPreflightSnapshotHash()));
        check(differences, "dispatch_request_sha256",
                Arrays.equals(expected.getRequestSha256(), row.getDispatchRequestSha256()));
        check(differences, "dispatch_issued_at", Objects.equals(expected.getIssuedAt(), row.getDispatchIssuedAt()));
        check(differences, "dispatch_expires_at",
                Objects.equals(expected.getExpiresAt(), row.getDispatchExpiresAt()));
        check(differences, "dispatch_claimed_at", row.getDispatchClaimedAt() == null);
        check(differences, "dispatch_started_at", row.getDispatchStartedAt() == null);
        check(differences, "dispatch_provider", Objects.equals(request.provider(), row.getDispatchProvider()));
        check(differences, "dispatch_model_revision",
                Objects.equals(request.modelRevision(), row.getDispatchModelRevision()));
        check(differences, "dispatch_region", Objects.equals(request.region(), row.getDispatchRegion()));
        check(differences, "dispatch_tier", Objects.equals(request.tier(), row.getDispatchTier()));
        check(differences, "dispatch_config_hash",
                Arrays.equals(expected.getConfigHash(), row.getDispatchConfigHash()));
        check(differences, "dispatch_artifact_version_id",
                Objects.equals(request.artifactVersionId(), row.getDispatchArtifactVersionId()));
        check(differences, "dispatch_scope_hash", Arrays.equals(expected.getScopeHash(), row.getDispatchScopeHash()));
        check(differences, "dispatch_input_hash", Arrays.equals(expected.getInputHash(), row.getDispatchInputHash()));
        check(differences, "dispatch_approval_digest",
                Arrays.equals(expected.getApprovalDigest(), row.getDispatchApprovalDigest()));
        check(differences, "dispatch_card_id", Objects.equals(request.cardId(), row.getDispatchCardId()));
        check(differences, "dispatch_card_version",
                Objects.equals(request.cardVersion(), row.getDispatchCardVersion()));
        check(differences, "dispatch_card_digest",
                Arrays.equals(expected.getCardDigest(), row.getDispatchCardDigest()));
        check(differences, "dispatch_policy_id", Objects.equals(request.policyId(), row.getDispatchPolicyId()));
        check(differences, "dispatch_policy_version",
                Objects.equals(request.policyVersion(), row.getDispatchPolicyVersion()));
        check(differences, "dispatch_policy_digest",
                Arrays.equals(expected.getPolicyDigest(), row.getDispatchPolicyDigest()));
        return differences;
    }

    /** claim IDだけを示す（nonce・hash・承認情報は出さない）。 */
    @Override
    public String toString() {
        return "DispatchTicket[claimId=" + claimId + "]";
    }

    private static void check(List<String> differences, String column, boolean matches) {
        if (!matches) {
            differences.add(column);
        }
    }

    /**
     * 発行時bindingのcanonical SHA-256。各値は {@code key=長さ:値} の行で表し、順序・version prefixを固定する。
     * 値は {@link DispatchIssueRequest} で識別子・16進表記へ制限済みであり、改行や区切りの曖昧さを持たない。
     */
    private byte[] bindingDigest() {
        StringBuilder canonical = new StringBuilder(BINDING_VERSION).append('\n');
        line(canonical, "tenantId", tenantId);
        line(canonical, "runId", String.valueOf(runId));
        line(canonical, "traceId", traceId);
        line(canonical, "useCase", USE_CASE);
        line(canonical, "claimId", claimId);
        line(canonical, "nonceHash", DispatchDigests.hexOf(nonceHash));
        line(canonical, "idempotencyHash", DispatchDigests.hexOf(idempotencyHash));
        line(canonical, "provider", request.provider());
        line(canonical, "modelRevision", request.modelRevision());
        line(canonical, "region", request.region());
        line(canonical, "tier", request.tier());
        line(canonical, "configHash", request.configHash());
        line(canonical, "artifactVersionId", String.valueOf(request.artifactVersionId()));
        line(canonical, "scopeHash", request.scopeHash());
        line(canonical, "inputHash", request.inputHash());
        line(canonical, "preflightSnapshotHash", request.preflightSnapshotHash());
        line(canonical, "requestSha256", request.requestSha256());
        line(canonical, "approvalDigest", request.approvalDigest());
        line(canonical, "decisionId", request.decisionId());
        line(canonical, "cardId", request.cardId());
        line(canonical, "cardVersion", request.cardVersion());
        line(canonical, "cardDigest", request.cardDigest());
        line(canonical, "cardEffectiveFrom", request.cardEffectiveFrom().toString());
        line(canonical, "cardEffectiveUntil", request.cardEffectiveUntil().toString());
        line(canonical, "policyId", request.policyId());
        line(canonical, "policyVersion", request.policyVersion());
        line(canonical, "policyDigest", request.policyDigest());
        line(canonical, "destinationHost", request.destinationHost());
        line(canonical, "costScope", request.costScope());
        line(canonical, "upperBoundYen", String.valueOf(request.upperBoundYen()));
        line(canonical, "dailyLimitYen", String.valueOf(request.dailyLimitYen()));
        line(canonical, "monthlyLimitYen", String.valueOf(request.monthlyLimitYen()));
        line(canonical, "issuedAt", issuedAt.toString());
        line(canonical, "expiresAt", expiresAt.toString());
        line(canonical, "approvalSignedOn", request.approvalSignedOn().toString());
        line(canonical, "approvalExpiresOn", request.approvalExpiresOn().toString());
        return DispatchDigests.sha256(canonical.toString());
    }

    private static void line(StringBuilder canonical, String key, String value) {
        canonical.append(key).append('=').append(value.length()).append(':').append(value).append('\n');
    }
}
