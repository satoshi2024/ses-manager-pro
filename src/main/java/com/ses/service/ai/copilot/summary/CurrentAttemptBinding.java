package com.ses.service.ai.copilot.summary;

/** verifierが実行入力と照合する現在のattempt/context。 */
record CurrentAttemptBinding(String attemptId, String tenantId, String traceId, String useCase,
                             String attemptStatus, int statusVersion, String dispatchNonce, String provider,
                             String modelRevision, String region, String tier,
                             String configHash, String preflightSnapshotHash) { }
