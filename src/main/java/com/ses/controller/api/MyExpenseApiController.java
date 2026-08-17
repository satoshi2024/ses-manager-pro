package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.expense.ExpenseRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * 要員ポータル（経費申請）API。
 * 冒頭で本人のengineerIdをリンクから解決し、以降すべての読み書きをそのengineerId配下に限定する
 * （パスにengineerIdを受けない=越権の余地を消す。engineer-self-service-portal-v2 §6.2）。
 */
@RestController
@RequestMapping("/api/my/expenses")
@RequiredArgsConstructor
public class MyExpenseApiController {

    private final EngineerAccountLinkService linkService;
    private final ExpenseRequestService expenseRequestService;

    private Long currentEngineerId() {
        Long engineerId = linkService.findEngineerIdByUserId(SecurityUtils.currentUserId());
        if (engineerId == null) {
            // 認可違反（未紐付け）は403とし、system障害(500)へ混入させない。
            throw BusinessException.of(403, "error.my.notLinked");
        }
        return engineerId;
    }

    @GetMapping
    public ApiResult<Page<ExpenseRequestService.ExpenseRequestDto>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size) {
        return ApiResult.success(expenseRequestService.pageForEngineer(currentEngineerId(), status, current, size));
    }

    @PostMapping
    public ApiResult<ExpenseRequestService.ExpenseRequestDto> create(@RequestBody DraftRequest request) {
        ExpenseRequestService.ExpenseDraftCommand command = toCommand(request);
        return ApiResult.success(expenseRequestService.createDraft(currentEngineerId(), command));
    }

    @PutMapping("/{id}")
    public ApiResult<ExpenseRequestService.ExpenseRequestDto> update(@PathVariable Long id,
                                                                     @RequestBody DraftRequest request) {
        ExpenseRequestService.ExpenseDraftCommand command = toCommand(request);
        return ApiResult.success(expenseRequestService.updateDraft(currentEngineerId(), id, command));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        expenseRequestService.deleteDraft(currentEngineerId(), id);
        return ApiResult.success(null);
    }

    @PostMapping("/{id}/submit")
    public ApiResult<ExpenseRequestService.ExpenseRequestDto> submit(@PathVariable Long id) {
        return ApiResult.success(expenseRequestService.submit(currentEngineerId(), id));
    }

    @PostMapping("/{id}/resubmit")
    public ApiResult<ExpenseRequestService.ExpenseRequestDto> resubmit(@PathVariable Long id) {
        return ApiResult.success(expenseRequestService.resubmit(currentEngineerId(), id));
    }

    @PostMapping("/{id}/receipt")
    public ApiResult<ExpenseRequestService.ExpenseRequestDto> uploadReceipt(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BusinessException.of(400, "error.file.empty");
        }
        try (java.io.InputStream stream = file.getInputStream()) {
            return ApiResult.success(expenseRequestService.attachReceipt(currentEngineerId(), id,
                    file.getOriginalFilename(), file.getContentType(), stream));
        } catch (java.io.IOException e) {
            throw BusinessException.of(400, "error.file.readFailed");
        }
    }

    @GetMapping("/{id}/receipt")
    public ResponseEntity<InputStreamResource> downloadReceipt(@PathVariable Long id) {
        ExpenseRequestService.ReceiptDownload download =
                expenseRequestService.downloadReceipt(currentEngineerId(), id);
        String encodedName = URLEncoder.encode(download.originalName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(parseMediaType(download.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encodedName)
                .body(new InputStreamResource(download.stream()));
    }

    private ExpenseRequestService.ExpenseDraftCommand toCommand(DraftRequest request) {
        return new ExpenseRequestService.ExpenseDraftCommand(
                request.getExpenseDate(), request.getCategory(), request.getAmount(),
                request.getCustomerId(), request.getProjectId(), request.getDescription());
    }

    private MediaType parseMediaType(String contentType) {
        try {
            return MediaType.parseMediaType(contentType);
        } catch (RuntimeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    /** 下書き作成/更新リクエスト。 */
    public static class DraftRequest {
        private LocalDate expenseDate;
        private String category;
        private BigDecimal amount;
        private Long customerId;
        private Long projectId;
        private String description;

        public LocalDate getExpenseDate() { return expenseDate; }
        public void setExpenseDate(LocalDate expenseDate) { this.expenseDate = expenseDate; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
        public Long getCustomerId() { return customerId; }
        public void setCustomerId(Long customerId) { this.customerId = customerId; }
        public Long getProjectId() { return projectId; }
        public void setProjectId(Long projectId) { this.projectId = projectId; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }
}
