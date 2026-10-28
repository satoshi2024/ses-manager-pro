package com.ses.service.ai.copilot.summary;

/** verifierが署名・実行束縛を確認した費用結果。 */
record VerifiedCostEvaluation(SummaryCostEvidence evidence, String cardId, String cardVersion,
                              String cardDigest, String executionBindingDigest) { }
