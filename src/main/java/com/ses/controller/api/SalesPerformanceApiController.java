package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.salesperformance.CommissionRuleDto;
import com.ses.dto.salesperformance.SalesPerformanceDto;
import com.ses.service.SalesPerformanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;

@RestController
@RequestMapping("/api/sales-performance")
@RequiredArgsConstructor
public class SalesPerformanceApiController {

    private final SalesPerformanceService salesPerformanceService;

    @GetMapping
    public ApiResult<List<SalesPerformanceDto>> getMonthlyPerformance(
            @RequestParam(required = false) String month) {
        if (month == null || month.isEmpty()) {
            month = YearMonth.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        }
        return ApiResult.success(salesPerformanceService.calculateMonthlyPerformance(month));
    }

    @GetMapping("/commission-rule")
    public ApiResult<CommissionRuleDto> getCommissionRule() {
        return ApiResult.success(salesPerformanceService.getCommissionRule());
    }
}
