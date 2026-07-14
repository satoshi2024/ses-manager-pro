package com.ses.service;

import com.ses.dto.salesperformance.CommissionRuleDto;
import com.ses.dto.salesperformance.SalesPerformanceDto;
import java.util.List;

public interface SalesPerformanceService {
    List<SalesPerformanceDto> calculateMonthlyPerformance(String yearMonth);
    CommissionRuleDto getCommissionRule();
}
