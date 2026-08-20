package com.ses.service.ai;

import com.ses.dto.ai.AiEvaluationDashboardDto;
import com.ses.entity.AiEvaluation;

import java.util.List;

public interface AiEvaluationQueryService {

    AiEvaluationDashboardDto dashboard();

    List<AiEvaluation> listEvaluations();
}
