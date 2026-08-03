package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.common.util.PageUtils;
import com.ses.dto.WorkRecordGridDto;
import com.ses.dto.workrecord.PendingApprovalItemDto;
import com.ses.dto.workrecord.PendingApprovalSummaryDto;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/work-records")
@RequiredArgsConstructor
public class WorkRecordApiController {

    private final WorkRecordService workRecordService;
    private final TimesheetPdfService timesheetPdfService;
    @GetMapping("/grid")
    public ApiResult<List<WorkRecordGridDto>> getGrid(@RequestParam String month) {
        return ApiResult.success(workRecordService.monthlyGrid(month));
    }

    @GetMapping("/grid/page")
    public ApiResult<Page<WorkRecordGridDto>> getGridPage(
            @RequestParam String month,
            @RequestParam(required = false) Long current,
            @RequestParam(required = false) Long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        return ApiResult.success(workRecordService.monthlyGridPage(month, current, size, keyword, status));
    }

    /**
     * 承認滞留の可視化（読み取り専用・トラックA3）。対象月の提出済（未承認）件数と滞留日数を返す。
     * 状態遷移は一切行わない。組織scopeは {@link WorkRecordService#monthlyGrid} と同一経路で絞り込む
     * （既に承認可能な範囲へ絞られた結果からIDを取り出すだけなので、scope判定を重複実装しない）。
     * 滞留日数は t_work_record.updated_at（提出済へ遷移した際にDBのON UPDATE CURRENT_TIMESTAMPで
     * 自動更新される）を基準にする。提出済のレコードは編集不可のため、承認・差戻しされるまで
     * updated_at は提出時刻のまま変わらない。
     */
    @GetMapping("/pending-approval-summary")
    public ApiResult<PendingApprovalSummaryDto> pendingApprovalSummary(
            @RequestParam String month,
            @RequestParam(required = false) Long current,
            @RequestParam(required = false) Long size) {
        List<WorkRecordGridDto> submitted = workRecordService.monthlyGrid(month).stream()
                .filter(g -> "提出済".equals(g.getStatus()) && g.getWorkRecordId() != null)
                .collect(Collectors.toList());

        if (submitted.isEmpty()) {
            return ApiResult.success(new PendingApprovalSummaryDto(0, null, List.of()));
        }

        List<Long> ids = submitted.stream().map(WorkRecordGridDto::getWorkRecordId).collect(Collectors.toList());
        Map<Long, LocalDateTime> updatedAtById = workRecordService.listByIds(ids).stream()
                .collect(Collectors.toMap(WorkRecord::getId, WorkRecord::getUpdatedAt));

        LocalDate today = LocalDate.now();
        List<PendingApprovalItemDto> items = submitted.stream()
                .map(g -> {
                    PendingApprovalItemDto dto = new PendingApprovalItemDto();
                    dto.setWorkRecordId(g.getWorkRecordId());
                    dto.setContractId(g.getContractId());
                    dto.setContractNo(g.getContractNo());
                    dto.setEngineerName(g.getEngineerName());
                    LocalDateTime updatedAt = updatedAtById.get(g.getWorkRecordId());
                    int daysPending = updatedAt == null ? 0
                            : (int) Math.max(0, ChronoUnit.DAYS.between(updatedAt.toLocalDate(), today));
                    dto.setDaysPending(daysPending);
                    return dto;
                })
                .sorted(Comparator.comparingInt(PendingApprovalItemDto::getDaysPending).reversed())
                .collect(Collectors.toList());

        int maxPendingDays = items.get(0).getDaysPending();

        // 一覧のページサイズは PageUtils.safePage で正規化する（MI-21と同じ上限に揃える）。
        Page<PendingApprovalItemDto> page = PageUtils.safePage(
                current == null ? 1L : current, size == null ? PageUtils.DEFAULT_PAGE_SIZE : size,
                PageUtils.DEFAULT_PAGE_SIZE);
        int from = (int) Math.min((page.getCurrent() - 1) * page.getSize(), items.size());
        int to = (int) Math.min(from + page.getSize(), items.size());

        return ApiResult.success(new PendingApprovalSummaryDto(items.size(), maxPendingDays, items.subList(from, to)));
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
    @PreAuthorize("hasRole('管理者')")
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
        workRecordService.assertAllowed(id);
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
