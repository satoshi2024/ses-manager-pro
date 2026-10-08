package com.ses.service.ai.copilot;

import com.ses.service.ai.copilot.summary.PreflightCostEvidence;

/** 1回のCopilot summary呼出しに必要な秘密値を含まない不可変入力。 */
public record CopilotInvocation(
        CopilotConfigSnapshot configSnapshot,
        Long expectedArtifactVersionId,
        String expectedProvider,
        String expectedModel,
        String expectedPromptVersion,
        String expectedConfigHash,
        long deadlineNanos,
        PreflightCostEvidence preflightCostEvidence
) {
    public CopilotInvocation {
        if (configSnapshot == null || expectedArtifactVersionId == null
                || expectedProvider == null || expectedProvider.isBlank()
                || expectedModel == null || expectedModel.isBlank()
                || expectedPromptVersion == null || expectedPromptVersion.isBlank()
                || expectedConfigHash == null || expectedConfigHash.isBlank()
                || deadlineNanos <= 0 || preflightCostEvidence == null) {
            throw new IllegalArgumentException("Copilot invocationの必須項目が不足しています");
        }
        if (!preflightCostEvidence.configIdentity().equals(configSnapshot.configHash())) {
            throw new IllegalArgumentException("費用preflightとconfig snapshotが一致しません");
        }
    }

    public boolean identityMatchesSnapshot() {
        return configSnapshot.hasBoundedIdentity()
                && expectedProvider.equals(configSnapshot.configuredProvider())
                && expectedProvider.equals(configSnapshot.adapterProvider())
                && expectedModel.equals(configSnapshot.modelId())
                && expectedPromptVersion.equals(configSnapshot.promptVersion())
                && expectedConfigHash.equals(configSnapshot.configHash());
    }
}
