package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.dashboard.ContractProfitDto;
import com.ses.dto.dashboard.DashboardSummaryDto;
import com.ses.dto.dashboard.UtilizationForecastDto;
import com.ses.service.DashboardService;
import com.ses.service.UtilizationForecastService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardApiController {

    private final DashboardService dashboardService;
    private final UtilizationForecastService utilizationForecastService;

    @GetMapping("/summary")
    public ApiResult<DashboardSummaryDto> getSummary(
            @RequestParam(required = false) Integer year) {
        return ApiResult.success(dashboardService.getSummary(year));
    }

    @GetMapping("/profit-analysis")
    public ApiResult<List<ContractProfitDto>> getProfitAnalysis() {
        return ApiResult.success(dashboardService.getProfitAnalysis());
    }

    @GetMapping("/utilization-forecast")
    public ApiResult<UtilizationForecastDto> getUtilizationForecast(
            @RequestParam(defaultValue = "3") int months) {
        return ApiResult.success(utilizationForecastService.getForecast(months));
    }
}
