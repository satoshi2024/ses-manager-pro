package com.ses.service.ai.copilot.dispatch;

import com.ses.service.ai.copilot.CopilotRunService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** NF08 B2c-1 claim試験の共通fixture（synthetic値だけ。実provider・実鍵・PIIを含まない）。 */
final class DispatchClaimFixtures {

    static final String CONFIG_HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    static final String APPROVAL_DIGEST = "a".repeat(64);
    static final String PROVIDER = "gemini";
    static final String COST_SCOPE = "gemini";

    private DispatchClaimFixtures() {
    }

    /** 有効な入力。deadlineは十分先、card期間は {@code around} の前後1時間。 */
    static DispatchIssueRequest request(long artifactVersionId, Instant around, long nowNanos) {
        return request(artifactVersionId, around, nowNanos, 30, 10_000, 100_000);
    }

    static DispatchIssueRequest request(long artifactVersionId, Instant around, long nowNanos, int upperBoundYen,
                                        long dailyLimitYen, long monthlyLimitYen) {
        return request(artifactVersionId, around, nowNanos, upperBoundYen, dailyLimitYen, monthlyLimitYen, COST_SCOPE);
    }

    /** 実DB試験用。cost_scopeを試験ごとに分け、bucketの合計を他の試験と共有しない。 */
    static DispatchIssueRequest request(long artifactVersionId, Instant around, long nowNanos, int upperBoundYen,
                                        long dailyLimitYen, long monthlyLimitYen, String costScope) {
        return new DispatchIssueRequest(PROVIDER, "gemini-2.5-flash-001", "asia-northeast1", "standard",
                CONFIG_HASH, artifactVersionId, "b".repeat(64), "c".repeat(64), "d".repeat(64), "e".repeat(64),
                APPROVAL_DIGEST, "NF08-EXT-2026-0001", "card-nf08", "v1", "f".repeat(64),
                around.minus(Duration.ofHours(1)), around.plus(Duration.ofHours(1)),
                "policy-nf08", "v1", "9".repeat(64), "generativelanguage.googleapis.com", "Q-UTIL-001", 9L, 512,
                costScope, upperBoundYen, dailyLimitYen, monthlyLimitYen, Duration.ofMinutes(2),
                nowNanos + TimeUnit.MINUTES.toNanos(5),
                businessDay(around).minusDays(30), businessDay(around).plusDays(30));
    }

    /** 承認の有効期間（署名日・失効日）だけを差し替えた写し。 */
    static DispatchIssueRequest withApproval(DispatchIssueRequest base, LocalDate signedOn, LocalDate expiresOn) {
        return new DispatchIssueRequest(base.provider(), base.modelRevision(), base.region(), base.tier(),
                base.configHash(), base.artifactVersionId(), base.scopeHash(), base.inputHash(),
                base.preflightSnapshotHash(), base.requestSha256(), base.approvalDigest(), base.decisionId(),
                base.cardId(), base.cardVersion(), base.cardDigest(), base.cardEffectiveFrom(), base.cardEffectiveUntil(),
                base.policyId(), base.policyVersion(), base.policyDigest(), base.destinationHost(), base.queryId(),
                base.legalEntityId(), base.promptBytes(), base.costScope(), base.upperBoundYen(), base.dailyLimitYen(),
                base.monthlyLimitYen(), base.attemptTtl(), base.deadlineNanos(), signedOn, expiresOn);
    }

    static LocalDate businessDay(Instant instant) {
        return LocalDate.ofInstant(instant, com.ses.service.ai.AiExternalSendApproval.BUSINESS_ZONE);
    }

    static CopilotRunService.CopilotAttemptRecord summaryAttempt(long runId, String traceId, String tenantId,
                                                                 long artifactVersionId) {
        CopilotRunService.CopilotArtifactSnapshot artifact = new CopilotRunService.CopilotArtifactSnapshot(
                artifactVersionId, PROVIDER, "gemini-2.5-flash", "nf08-f1", CONFIG_HASH, "catalog-v1", 0);
        return new CopilotRunService.CopilotAttemptRecord(runId, traceId, null, "SUMMARY", tenantId, artifact,
                Map.of("attemptKind", "SUMMARY", "traceId", traceId));
    }

    /** 同じ値を持つ別のticket（別プロセス・再起動後に同じbindingを知る者が作った写しを模擬する）。 */
    static DispatchTicket copyOf(DispatchTicket ticket) {
        return new DispatchTicket(ticket.runId(), ticket.tenantId(), ticket.traceId(), ticket.claimId(),
                ticket.request(), ticket.nonce(), ticket.idempotencyKey(), ticket.issuedAt(), ticket.issuedNanos());
    }

    /** ticketの発行時snapshotと全列が一致するREADY行。 */
    static DispatchRunRow readyRow(DispatchTicket ticket) {
        DispatchColumns c = ticket.columns();
        DispatchRunRow row = new DispatchRunRow();
        row.setId(c.getRunId());
        row.setTenantId(c.getTenantId());
        row.setTraceId(c.getTraceId());
        row.setUseCase(c.getUseCase());
        row.setStatus("PENDING");
        row.setStatusVersion(0);
        row.setDispatchState("READY");
        row.setDispatchClaimIdText(c.getClaimId());
        row.setDispatchNonceHash(c.getNonceHash());
        row.setDispatchIdempotencyHash(c.getIdempotencyHash());
        row.setDispatchBindingHash(c.getBindingHash());
        row.setPreflightSnapshotHash(c.getPreflightSnapshotHash());
        row.setDispatchRequestSha256(c.getRequestSha256());
        row.setDispatchIssuedAt(c.getIssuedAt());
        row.setDispatchExpiresAt(c.getExpiresAt());
        row.setDispatchProvider(c.getProvider());
        row.setDispatchModelRevision(c.getModelRevision());
        row.setDispatchRegion(c.getRegion());
        row.setDispatchTier(c.getTier());
        row.setDispatchConfigHash(c.getConfigHash());
        row.setDispatchArtifactVersionId(c.getArtifactVersionId());
        row.setDispatchScopeHash(c.getScopeHash());
        row.setDispatchInputHash(c.getInputHash());
        row.setDispatchApprovalDigest(c.getApprovalDigest());
        row.setDispatchCardId(c.getCardId());
        row.setDispatchCardVersion(c.getCardVersion());
        row.setDispatchCardDigest(c.getCardDigest());
        row.setDispatchPolicyId(c.getPolicyId());
        row.setDispatchPolicyVersion(c.getPolicyVersion());
        row.setDispatchPolicyDigest(c.getPolicyDigest());
        return row;
    }

    /** 読むたびに予定した時刻を順に返し、最後の値を繰り返すClock（固定Clockの延長）。 */
    static final class SequenceClock extends Clock {
        private final Deque<Instant> pending = new ArrayDeque<>();
        private Instant last;

        SequenceClock(Instant first, Instant... rest) {
            last = first;
            pending.add(first);
            for (Instant next : rest) {
                pending.add(next);
            }
        }

        synchronized void then(Instant next) {
            pending.add(next);
        }

        synchronized void set(Instant fixed) {
            pending.clear();
            last = fixed;
        }

        @Override
        public synchronized Instant instant() {
            if (!pending.isEmpty()) {
                last = pending.removeFirst();
            }
            return last;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
