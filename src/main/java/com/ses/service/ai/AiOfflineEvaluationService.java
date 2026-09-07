package com.ses.service.ai;

import com.ses.entity.AiEvaluation;

public interface AiOfflineEvaluationService {

    AiEvaluation evaluate(Long candidateVersionId, Long baselineVersionId);

    AiEvaluation evaluateCopilot(Long candidateVersionId, Long baselineVersionId);
}
