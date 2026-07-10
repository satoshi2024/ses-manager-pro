package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.dashboard.DashboardSummaryDto;
import com.ses.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardApiController {

    private final DashboardService dashboardService;

    @GetMapping("/summary")
    public ApiResult<DashboardSummaryDto> getSummary() {
        return ApiResult.success(dashboardService.getSummary());
    }
}
