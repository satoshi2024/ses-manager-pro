package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.attendance.sync.AttendanceSyncResultDto;
import com.ses.service.attendance.AttendanceSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 雇用勤怠の外部provider同期API（S11 B1）。
 *
 * <p>scopeはdesign §5.3: 管理者=全件、HR=法人scope、マネージャー=組織scope。営業・要員は
 * SecurityConfigの`/api/work-records/attendance/**`制限で到達できない。同期実行（run）は
 * 管理者/HRのみ（scheduler principal相当、マネージャーはstatus/CSV閲覧のみ）。</p>
 */
@RestController
@RequestMapping("/api/work-records/attendance/sync")
@RequiredArgsConstructor
public class AttendanceSyncApiController {

    private static final Set<String> DIRECTIONS = Set.of("push", "pull", "all");

    private final AttendanceSyncService attendanceSyncService;

    @PostMapping("/run")
    public ApiResult<AttendanceSyncResultDto> run(@RequestParam String month,
                                                  @RequestParam(defaultValue = "all") String direction) {
        if (!DIRECTIONS.contains(direction)) {
            throw BusinessException.of(400, "error.attendance.sync.invalidDirection");
        }
        requireHrOrAdminRole();
        AttendanceSyncResultDto result = switch (direction) {
            case "push" -> attendanceSyncService.syncPush(month);
            case "pull" -> attendanceSyncService.syncPull(month);
            default -> attendanceSyncService.syncAll(month);
        };
        return ApiResult.success(result);
    }

    @GetMapping("/status")
    public ApiResult<AttendanceSyncStatusDto> status() {
        AttendanceSyncStatusDto dto = new AttendanceSyncStatusDto();
        dto.setProvider(attendanceSyncService.providerSource());
        dto.setProviderAvailable(attendanceSyncService.providerAvailable());
        dto.setLastResult(attendanceSyncService.lastResult());
        return ApiResult.success(dto);
    }

    @GetMapping("/export-csv")
    public void exportCsv(@RequestParam String month,
                          jakarta.servlet.http.HttpServletResponse response) {
        try {
            String encoded = java.net.URLEncoder.encode("勤怠同期_" + month + ".csv", StandardCharsets.UTF_8)
                    .replace("+", "%20");
            response.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8).toString());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename*=UTF-8''" + encoded);
            attendanceSyncService.exportCsv(month, new BufferedOutputStream(response.getOutputStream()));
            response.getOutputStream().flush();
        } catch (java.io.IOException e) {
            throw BusinessException.of(500, "error.attendance.sync.csvFailed");
        }
    }

    private void requireHrOrAdminRole() {
        String role = SecurityUtils.currentRole();
        if (!Set.of("管理者", "HR").contains(role)) {
            throw BusinessException.of(403, "error.attendance.roleDenied");
        }
    }

    /** status API用DTO（provider状態＋直近結果）。 */
    public static class AttendanceSyncStatusDto {
        private String provider;
        private boolean providerAvailable;
        private AttendanceSyncResultDto lastResult;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public boolean isProviderAvailable() { return providerAvailable; }
        public void setProviderAvailable(boolean providerAvailable) { this.providerAvailable = providerAvailable; }
        public AttendanceSyncResultDto getLastResult() { return lastResult; }
        public void setLastResult(AttendanceSyncResultDto lastResult) { this.lastResult = lastResult; }
    }
}
