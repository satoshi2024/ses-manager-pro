package com.ses.service.ai.copilot.summary;

import com.ses.service.ai.CopilotProviderUsage;

import java.time.Instant;

/** verifier境界内だけで扱う未信頼の実行・usage入力。 */
record RateCardExecutionInput(
        String attemptId,
        String idempotencyKey,
        String rateCardDigest,
        String tenantId,
        String traceId,
        String useCase,
        String attemptStatus,
        int statusVersion,
        String dispatchNonce,
        String provider,
        String modelRevision,
        String region,
        String tier,
        String configHash,
        String preflightSnapshotHash,
        Instant evaluatedAt,
        CopilotProviderUsage usage,
        DispatchOutcome dispatchOutcome
) { }
