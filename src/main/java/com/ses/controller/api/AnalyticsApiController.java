package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.analytics.BenchEngineerDto;
import com.ses.dto.analytics.UtilizationPointDto;
import com.ses.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsApiController {

    private final AnalyticsService analyticsService;

    /** 一度に集計を許容する最大月数(過大な値によるリソース消費を防ぐ上限) */
    private static final int MAX_MONTHS = 60;

    @GetMapping("/utilization-trend")
    public ApiResult<List<UtilizationPointDto>> utilizationTrend(
            @RequestParam(defaultValue = "12") int months) {
        int safeMonths = Math.max(1, Math.min(months, MAX_MONTHS));
        return ApiResult.success(analyticsService.utilizationTrend(safeMonths));
    }

    @GetMapping("/bench")
    public ApiResult<List<BenchEngineerDto>> bench() {
        return ApiResult.success(analyticsService.benchList());
    }

    @GetMapping("/availability-timeline")
    public ApiResult<com.ses.dto.analytics.AvailabilityTimelineDto> availabilityTimeline(
            @RequestParam(required = true) String from,
            @RequestParam(required = true) String to,
            @RequestParam(required = false) Long skillId,
            @RequestParam(required = false) Long salesUserId) {
        return ApiResult.success(analyticsService.getAvailabilityTimeline(from, to, skillId, salesUserId));
    }
}
