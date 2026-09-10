package com.ses.service.ai;

import com.ses.common.exception.BusinessException;

import java.util.Map;

/** AI用途ごとのprovider policy。legacyのlocal-only条件をingestion/learningへ暗黙適用しない。 */
public final class AiUseCasePolicy {
    private static final Map<String, Mode> POLICIES = Map.of(
            AiGatewayRequest.USE_CHAT, Mode.LEGACY_LOCAL,
            AiGatewayRequest.USE_MATCHING, Mode.LEGACY_LOCAL,
            AiGatewayRequest.USE_PROPOSAL_DRAFT, Mode.LEGACY_LOCAL,
            AiGatewayRequest.USE_INGEST_RESUME, Mode.OFFLINE_LOCAL,
            AiGatewayRequest.USE_INGEST_PROJECT, Mode.OFFLINE_LOCAL,
            AiGatewayRequest.USE_INGEST_BP, Mode.OFFLINE_LOCAL,
            AiGatewayRequest.USE_LEARNING_CANDIDATE, Mode.OFFLINE_LOCAL,
            AiGatewayRequest.USE_COPILOT, Mode.MANAGEMENT_COPILOT);

    private AiUseCasePolicy() {
    }

    public static Mode require(String useCase) {
        Mode mode = POLICIES.get(useCase);
        if (mode == null) {
            throw BusinessException.of(503, "AI_USE_CASE_NOT_REGISTERED");
        }
        return mode;
    }

    public enum Mode {
        LEGACY_LOCAL,
        OFFLINE_LOCAL,
        MANAGEMENT_COPILOT
    }
}
