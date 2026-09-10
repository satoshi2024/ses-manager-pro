package com.ses.service;

import com.ses.dto.dashboard.ContractProfitDto;
import com.ses.dto.dashboard.DashboardSummaryDto;
import java.util.List;
import com.ses.service.ai.copilot.CopilotExecutionContext;

public interface DashboardService {
    DashboardSummaryDto getSummary(Integer year);
    List<ContractProfitDto> getProfitAnalysis();

    default DashboardSummaryDto getSummary(Integer year, CopilotExecutionContext context) {
        throw com.ses.common.exception.BusinessException.of(403, "EXECUTION_CONTEXT_REQUIRED");
    }

    default List<ContractProfitDto> getProfitAnalysis(CopilotExecutionContext context) {
        throw com.ses.common.exception.BusinessException.of(403, "EXECUTION_CONTEXT_REQUIRED");
    }

}
