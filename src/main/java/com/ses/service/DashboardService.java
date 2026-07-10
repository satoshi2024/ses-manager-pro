package com.ses.service;

import com.ses.dto.dashboard.ContractProfitDto;
import com.ses.dto.dashboard.DashboardSummaryDto;
import java.util.List;

public interface DashboardService {
    DashboardSummaryDto getSummary(Integer year);
    List<ContractProfitDto> getProfitAnalysis();
}
