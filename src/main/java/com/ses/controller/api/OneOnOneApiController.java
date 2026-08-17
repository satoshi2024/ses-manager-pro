package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.service.oneonone.OneOnOneRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 1on1管理API（HR/管理者=全件+confidential可視、マネージャー=組織scope配下、営業=担当要員の公開部分）。
 * design §6.2決定表。menu付与に加えて@PreAuthorizeで二重に境界を張る。
 */
@RestController
@RequestMapping("/api/one-on-ones")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('管理者','HR','マネージャー','営業')")
public class OneOnOneApiController {

    private final OneOnOneRequestService oneOnOneService;

    @GetMapping
    public ApiResult<Page<OneOnOneRequestService.OneOnOneDto>> list(
            @RequestParam(required = false) String engineerName,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size) {
        return ApiResult.success(oneOnOneService.pageManagement(engineerName, status, current, size));
    }

    @GetMapping("/{id}")
    public ApiResult<OneOnOneRequestService.OneOnOneDto> detail(@PathVariable Long id) {
        return ApiResult.success(oneOnOneService.detailManagement(id));
    }

    @PostMapping("/{id}/schedule")
    public ApiResult<OneOnOneRequestService.OneOnOneDto> schedule(@PathVariable Long id,
                                                                  @RequestBody ScheduleRequest request) {
        return ApiResult.success(oneOnOneService.schedule(id,
                request == null ? null : request.getScheduledAt()));
    }

    @PostMapping("/{id}/complete")
    public ApiResult<OneOnOneRequestService.OneOnOneDto> complete(@PathVariable Long id,
                                                                  @RequestBody(required = false) CompleteRequest request) {
        return ApiResult.success(oneOnOneService.complete(id,
                request == null ? null : request.getEmployeeVisibleNote()));
    }

    @PostMapping("/{id}/cancel")
    public ApiResult<OneOnOneRequestService.OneOnOneDto> cancel(@PathVariable Long id,
                                                                @RequestBody(required = false) CancelRequest request) {
        return ApiResult.success(oneOnOneService.cancel(id,
                request == null ? null : request.getReason()));
    }

    /** confidential相談（HR/管理者のみ。営業・マネージャーには一切出さない。design §6.2）。 */
    @PostMapping("/{id}/private-note")
    public ApiResult<OneOnOneRequestService.OneOnOneDto> savePrivateNote(@PathVariable Long id,
                                                                        @RequestBody PrivateNoteRequest request) {
        return ApiResult.success(oneOnOneService.savePrivateNote(id,
                request == null ? null : request.getNote()));
    }

    public static class ScheduleRequest {
        private LocalDate scheduledAt;

        public LocalDate getScheduledAt() {
            return scheduledAt;
        }

        public void setScheduledAt(LocalDate scheduledAt) {
            this.scheduledAt = scheduledAt;
        }
    }

    public static class CompleteRequest {
        private String employeeVisibleNote;

        public String getEmployeeVisibleNote() {
            return employeeVisibleNote;
        }

        public void setEmployeeVisibleNote(String employeeVisibleNote) {
            this.employeeVisibleNote = employeeVisibleNote;
        }
    }

    public static class CancelRequest {
        private String reason;

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }

    public static class PrivateNoteRequest {
        private String note;

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }
}
