package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.attendance.discrepancy.AttendanceDiscrepancyDto;
import com.ses.service.attendance.AttendanceDiscrepancyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 客先工数差異API（S11 B2 / R4.1・R4.2）。
 *
 * <p>一覧はread-only比較DTO。確認理由の保存も請求金額を変更しない。
 * scopeはdesign §5.3（管理者=全件、HR=法人、マネージャー=組織）。営業・要員は
 * SecurityConfigの`/api/work-records/attendance/**`制限で到達できない。</p>
 */
@RestController
@RequestMapping("/api/work-records/attendance/discrepancy")
@RequiredArgsConstructor
public class AttendanceDiscrepancyApiController {

    private final AttendanceDiscrepancyService attendanceDiscrepancyService;

    @GetMapping
    public ApiResult<AttendanceDiscrepancyDto> list(@RequestParam String month) {
        return ApiResult.success(attendanceDiscrepancyService.list(month));
    }

    @PostMapping("/confirm")
    public ApiResult<Void> confirm(@RequestBody Map<String, String> request) {
        attendanceDiscrepancyService.confirm(
                request == null ? null : parseLong(request.get("engineerId")),
                request == null ? null : request.get("month"),
                request == null ? null : request.get("reason"));
        return ApiResult.success(null);
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
