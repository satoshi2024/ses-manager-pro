package com.ses.privacy.foundation;

import java.time.Instant;
import java.util.Optional;

/**
 * ポリシー照会結果 DTO。
 * 処分や外送の実行認可フラグは一切含まない（Explanation-only）。
 */
public record PolicyExplanationResult(
    PolicyDecisionStatus status,
    PolicyFailureReason failureReason,
    String policyVersion,
    String policyDigest,
    String dataElementId,
    String targetAction,
    String targetProvider,
    String scope,
    Optional<Instant> effectiveFrom,
    Optional<Instant> expiresAt,
    String explanationReason
) {}
