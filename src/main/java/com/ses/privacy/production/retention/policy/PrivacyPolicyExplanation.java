package com.ses.privacy.production.retention.policy;

import com.ses.privacy.foundation.PolicyExplanationResult;

import java.time.Instant;

/**
 * 説明APIの照会結果。説明 metadata だけを返し、処分・外部送信の実行認可は含まない（{@code executionAuthorized} は常に false）。
 *
 * <p>policy が未知・未承認・検証不能の場合は {@code decisionStatus} が {@code APPROVED_ACTIVE} 以外になる（fail-closed）。
 */
public record PrivacyPolicyExplanation(String decisionStatus, String failureReason, String policyVersion,
                                       String policyDigest, String dataElementId, String targetAction,
                                       String targetProvider, String scope, Instant effectiveFrom,
                                       Instant expiresAt, String explanation, boolean executionAuthorized) {

    static PrivacyPolicyExplanation from(PolicyExplanationResult result) {
        return new PrivacyPolicyExplanation(
                result.status() == null ? null : result.status().name(),
                result.failureReason() == null ? null : result.failureReason().name(),
                result.policyVersion(), result.policyDigest(), result.dataElementId(), result.targetAction(),
                result.targetProvider(), result.scope(),
                result.effectiveFrom() == null ? null : result.effectiveFrom().orElse(null),
                result.expiresAt() == null ? null : result.expiresAt().orElse(null),
                result.explanationReason(), false);
    }
}
