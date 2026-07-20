package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.WorkRecordGridDto;
import com.ses.entity.WorkRecord;
import com.ses.entity.WorkRecordDaily;
import com.ses.service.TimesheetPdfService;
import com.ses.service.WorkRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/work-records")
@RequiredArgsConstructor
public class WorkRecordApiController {

    private final WorkRecordService workRecordService;
    private final TimesheetPdfService timesheetPdfService;
    private final com.ses.service.security.DataScopeService dataScopeService;

    @GetMapping("/grid")
    public ApiResult<List<WorkRecordGridDto>> getGrid(@RequestParam String month) {
        return ApiResult.success(workRecordService.monthlyGrid(month));
    }

    @PutMapping
    public ApiResult<WorkRecord> saveHours(@Valid @RequestBody com.ses.dto.workrecord.WorkRecordSaveRequest request) {
        return ApiResult.success(workRecordService.saveHours(
                request.getContractId(),
                request.getWorkMonth(),
                request.getActualHours(),
                request.getRemarks()
        ));
    }

    @PostMapping("/confirm")
    public ApiResult<Void> confirmMonth(@RequestParam String month) {
        workRecordService.confirmMonth(month);
        return ApiResult.success(null);
    }

    /**
     * 月次確定の解除（管理者のみ）。
     * SecurityConfig の requestMatchers でも管理者に制限しており二重防御。
     */
    @PostMapping("/reopen")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<Void> reopenMonth(@RequestParam String month) {
        workRecordService.reopenMonth(month);
        return ApiResult.success(null);
    }

    // ===== 承認側（engineer-self-service-timesheet / P1） =====

    @PostMapping("/{id}/approve")
    public ApiResult<Void> approve(@PathVariable Long id) {
        workRecordService.approve(id);
        return ApiResult.success(null);
    }

    @PostMapping("/{id}/reject")
    public ApiResult<Void> reject(@PathVariable Long id, @RequestBody RejectRequest request) {
        workRecordService.reject(id, request.getComment());
        return ApiResult.success(null);
    }

    @GetMapping("/{id}/daily")
    public ApiResult<List<WorkRecordDaily>> daily(@PathVariable Long id) {
        return ApiResult.success(workRecordService.listDaily(id));
    }

    @GetMapping("/{id}/report.pdf")
    public ResponseEntity<byte[]> report(@PathVariable Long id) {
        byte[] bytes = timesheetPdfService.generate(id);
        String fileName = "作業報告書_" + id + ".pdf";
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
                .body(bytes);
    }

    public static class RejectRequest {
        private String comment;
        public String getComment() { return comment; }
        public void setComment(String comment) { this.comment = comment; }
    }
}
