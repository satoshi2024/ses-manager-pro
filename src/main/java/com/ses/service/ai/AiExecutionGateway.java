package com.ses.service.ai;

import com.ses.service.ai.copilot.summary.PreflightCostEvidence;

public interface AiExecutionGateway {

    AiGatewayResult execute(AiGatewayRequest request);

    /** Copilotの読取専用preflight。provider I/Oやattempt永続化は行わない。 */
    default PreflightCostEvidence preflightCopilot(AiGatewayRequest request) {
        throw new UnsupportedOperationException("Copilot preflight is unavailable");
    }
}
