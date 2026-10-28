package com.ses.service.ai.copilot.summary;

/**
 * B2c-0の不変preflight照合値。dispatch capabilityではなく、attempt IDやTLS検証結果も保持しない。
 */
final class CopilotSummaryPreflightCandidate {
    private final String tenantId;
    private final Long legalEntityId;
    private final String queryId;
    private final String existingQueryTraceId;
    private final Long artifactVersionId;
    private final String provider;
    private final String model;
    private final String promptVersion;
    private final String configHash;
    private final String scopeHash;
    private final String parameterHash;
    private final PreflightCostEvidence evidence;

    CopilotSummaryPreflightCandidate(String tenantId, Long legalEntityId, String queryId,
                                     String existingQueryTraceId,
                                     Long artifactVersionId, String provider, String model,
                                     String promptVersion, String configHash, String scopeHash,
                                     String parameterHash, PreflightCostEvidence evidence) {
        this.tenantId = tenantId;
        this.legalEntityId = legalEntityId;
        this.queryId = queryId;
        this.existingQueryTraceId = existingQueryTraceId;
        this.artifactVersionId = artifactVersionId;
        this.provider = provider;
        this.model = model;
        this.promptVersion = promptVersion;
        this.configHash = configHash;
        this.scopeHash = scopeHash;
        this.parameterHash = parameterHash;
        this.evidence = evidence;
    }

    boolean matches(String currentTenantId, Long currentLegalEntityId, String currentQueryId,
                    String currentExistingQueryTraceId,
                    Long currentArtifactVersionId, String currentProvider, String currentModel,
                    String currentPromptVersion, String currentConfigHash, String currentScopeHash,
                    String currentParameterHash) {
        return matches(currentTenantId, currentLegalEntityId, currentQueryId, currentExistingQueryTraceId,
                currentArtifactVersionId,
                currentProvider, currentModel, currentPromptVersion, currentConfigHash,
                currentScopeHash, currentParameterHash, evidence);
    }

    boolean matches(String currentTenantId, Long currentLegalEntityId, String currentQueryId,
                    String currentExistingQueryTraceId,
                    Long currentArtifactVersionId, String currentProvider, String currentModel,
                    String currentPromptVersion, String currentConfigHash, String currentScopeHash,
                    String currentParameterHash, PreflightCostEvidence currentEvidence) {
        return tenantId != null && tenantId.equals(currentTenantId)
                && legalEntityId != null && legalEntityId.equals(currentLegalEntityId)
                && queryId != null && queryId.equals(currentQueryId)
                && existingQueryTraceId != null && existingQueryTraceId.equals(currentExistingQueryTraceId)
                && artifactVersionId != null && artifactVersionId.equals(currentArtifactVersionId)
                && provider != null && provider.equals(currentProvider)
                && model != null && model.equals(currentModel)
                && promptVersion != null && promptVersion.equals(currentPromptVersion)
                && configHash != null && configHash.equals(currentConfigHash)
                && scopeHash != null && scopeHash.equals(currentScopeHash)
                && parameterHash != null && parameterHash.equals(currentParameterHash)
                && evidence != null && evidence.equals(currentEvidence);
    }

    boolean permitsLocalSummaryAttempt() {
        return evidence != null && evidence.dispatchAllowed()
                && "local-no-egress-v1".equals(evidence.policyId())
                && configHash.equals(evidence.configIdentity())
                && evidence.costState() == CopilotCostState.NOT_SENT
                && Integer.valueOf(0).equals(evidence.upperBoundYen())
                && Integer.valueOf(0).equals(evidence.budgetYen())
                && evidence.dispatchOutcome().state() == DispatchOutcome.State.NOT_STARTED
                && "LOCAL_PROVIDER_NO_EGRESS".equals(evidence.reasonCode())
                && "LOCAL_PROVIDER_NO_EGRESS".equals(evidence.dispatchOutcome().reasonCode())
                && "local-no-egress-proof-v1".equals(evidence.proofIdentity());
    }
}
