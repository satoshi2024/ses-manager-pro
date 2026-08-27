package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.report.ReportScheduleCreateRequest;
import com.ses.entity.ReportSchedule;
import com.ses.service.report.ReportScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 管理レポートschedule API。enabled変更はSecurityConfigで管理者のみに限定する。 */
@RestController
@RequestMapping("/api/management-reports/schedules")
@RequiredArgsConstructor
public class ManagementReportScheduleApiController {

    private final ReportScheduleService scheduleService;

    @GetMapping
    public ApiResult<List<ReportSchedule>> list() {
        return ApiResult.success(scheduleService.list());
    }

    @PostMapping
    public ApiResult<ReportSchedule> create(@RequestBody ReportScheduleCreateRequest request) {
        return ApiResult.success(scheduleService.create(request));
    }

    @PostMapping("/{scheduleId}/enable")
    public ApiResult<ReportSchedule> enable(@PathVariable Long scheduleId) {
        return ApiResult.success(scheduleService.setEnabled(scheduleId, true));
    }

    @PostMapping("/{scheduleId}/disable")
    public ApiResult<ReportSchedule> disable(@PathVariable Long scheduleId) {
        return ApiResult.success(scheduleService.setEnabled(scheduleId, false));
    }
}
