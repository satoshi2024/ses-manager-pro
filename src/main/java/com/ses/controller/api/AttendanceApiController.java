package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.attendance.AttendanceDayRequest;
import com.ses.dto.attendance.AttendanceOverviewDto;
import com.ses.dto.attendance.AttendanceReopenRequest;
import com.ses.service.AttendanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 雇用勤怠API。レスポンスはentityではなくDTOだけを返す。 */
@RestController
@RequiredArgsConstructor
public class AttendanceApiController {

    private final AttendanceService attendanceService;

    @GetMapping("/api/my/attendance")
    public ApiResult<AttendanceOverviewDto> mine(@RequestParam String month) {
        return ApiResult.success(attendanceService.mine(month));
    }

    @PostMapping("/api/my/attendance/daily")
    public ApiResult<Void> saveMyDay(@RequestBody AttendanceDayRequest request) {
        attendanceService.saveMyDay(request);
        return ApiResult.success(null);
    }

    @DeleteMapping("/api/my/attendance/daily")
    public ApiResult<Void> deleteMyDay(@RequestParam String month, @RequestParam String workDate) {
        attendanceService.deleteMyDay(month, workDate);
        return ApiResult.success(null);
    }

    @PostMapping("/api/my/attendance/submit")
    public ApiResult<Void> submitMyMonth(@RequestParam String month) {
        attendanceService.submitMyMonth(month);
        return ApiResult.success(null);
    }

    @GetMapping("/api/work-records/attendance")
    public ApiResult<AttendanceOverviewDto> management(@RequestParam String month) {
        return ApiResult.success(attendanceService.management(month));
    }

    @PostMapping("/api/work-records/attendance/{engineerId}/reject")
    public ApiResult<Void> reject(@PathVariable Long engineerId, @RequestParam String month) {
        attendanceService.reject(engineerId, month);
        return ApiResult.success(null);
    }

    @PostMapping("/api/work-records/attendance/{engineerId}/approve")
    public ApiResult<Void> approve(@PathVariable Long engineerId, @RequestParam String month) {
        attendanceService.approve(engineerId, month);
        return ApiResult.success(null);
    }

    @PostMapping("/api/work-records/attendance/{engineerId}/close")
    public ApiResult<Void> close(@PathVariable Long engineerId, @RequestParam String month) {
        attendanceService.close(engineerId, month);
        return ApiResult.success(null);
    }

    @PostMapping("/api/work-records/attendance/{engineerId}/reopen")
    public ApiResult<Void> reopen(@PathVariable Long engineerId,
                                  @RequestBody AttendanceReopenRequest request) {
        attendanceService.reopen(engineerId, request == null ? null : request.getMonth(),
                request == null ? null : request.getReason());
        return ApiResult.success(null);
    }
}
