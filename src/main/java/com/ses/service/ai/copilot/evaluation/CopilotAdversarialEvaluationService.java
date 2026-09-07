package com.ses.service.ai.copilot.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.ses.entity.AiEvaluation;

public interface CopilotAdversarialEvaluationService {

    String FIXTURE = "ai/eval/anonymized-management-copilot-v1.json";

    AiEvaluation evaluate(Long candidateVersionId, Long baselineVersionId);

    AiEvaluation evaluate(Long candidateVersionId, Long baselineVersionId, JsonNode fixture);

    JsonNode loadFixture();
}
