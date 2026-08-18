package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.PageUtils;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.accounting.canonical.CanonicalSalesInvoice;
import com.ses.entity.Customer;
import com.ses.entity.ExternalMapping;
import com.ses.entity.IntegrationConnection;
import com.ses.entity.IntegrationJob;
import com.ses.entity.IntegrationJobEvent;
import com.ses.entity.Invoice;
import com.ses.service.CustomerService;
import com.ses.service.InvoiceService;
import com.ses.service.accounting.AccountingProvider;
import com.ses.service.accounting.AccountingProviderFactory;
import com.ses.service.accounting.AccountingReconciliationService;
import com.ses.service.accounting.PurchaseExpensePaymentIntegrationService;
import com.ses.service.accounting.SalesInvoiceIntegrationService;
import com.ses.service.integration.ExternalMappingService;
import com.ses.service.integration.IntegrationConnectionService;
import com.ses.service.integration.IntegrationJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 会計・支払連携 REST API コントローラー (design §4, §6.1, §6.2, platform-invariants §6)。
 * <p>
 * - 管理者: 全操作可能 (接続設定、マッピング編集・検証、手動同期・再試行・取消、照合除外)。
 * - マネージャー: 参照専用 (接続一覧、マッピング一覧、ジョブ一覧/詳細、プレビュー、照合結果)。
 * - 営業 / HR / 要員: アクセス不可 (403)。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/accounting")
@PreAuthorize("hasAnyRole('管理者', 'マネージャー')")
@RequiredArgsConstructor
public class AccountingIntegrationApiController {

    private final IntegrationConnectionService connectionService;
    private final ExternalMappingService mappingService;
    private final IntegrationJobService jobService;
    private final InvoiceService invoiceService;
    private final CustomerService customerService;
    private final AccountingProviderFactory providerFactory;
    private final SalesInvoiceIntegrationService salesIntegrationService;
    private final PurchaseExpensePaymentIntegrationService purchaseIntegrationService;
    private final AccountingReconciliationService reconciliationService;

    /** SecurityContext からユーザーIDを取得する (fail-closed)。 */
    private Long resolveActorId() {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) {
            throw new BusinessException(401, "ユーザー認証が必要です");
        }
        return userId;
    }

    // === 1. 接続マスタ (Connection) API ===

    @GetMapping("/connections")
    public ApiResult<List<IntegrationConnection>> listConnections(
            @RequestParam(value = "tenantId", defaultValue = "default") String tenantId) {
        List<IntegrationConnection> list = connectionService.listConnections(tenantId);
        for (IntegrationConnection c : list) {
            c.setEncryptedTokens(null); // セキュリティのため確実にマスク
        }
        return ApiResult.success(list);
    }

    @GetMapping("/connections/{id}/health")
    public ApiResult<Boolean> checkHealth(@PathVariable("id") Long connectionId) {
        IntegrationConnection conn = connectionService.getById(connectionId);
        if (conn == null) {
            return ApiResult.error(404, "接続マスタが見つかりません");
        }
        try {
            AccountingProvider provider = providerFactory.getProvider(conn);
            boolean valid = provider.validateConnection(conn);
            return ApiResult.success(valid);
        } catch (Exception e) {
            log.warn("Health check failed for connectionId={}", connectionId, e);
            return ApiResult.success(false);
        }
    }

    @PostMapping("/connections/{id}/status")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<Void> updateStatus(@PathVariable("id") Long connectionId,
                                        @RequestParam("status") String status) {
        connectionService.updateStatus(connectionId, status);
        return ApiResult.success(null);
    }

    // === 2. マッピング (Mapping) API ===

    @GetMapping("/mappings")
    public ApiResult<List<ExternalMapping>> listMappings(
            @RequestParam("connectionId") Long connectionId,
            @RequestParam(value = "objectType", required = false) String objectType) {
        List<ExternalMapping> list = mappingService.listByConnection(connectionId, objectType);
        return ApiResult.success(list);
    }

    @PostMapping("/mappings")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<Void> saveMapping(@RequestBody ExternalMapping mapping) {
        mappingService.saveOrUpdateMapping(mapping);
        return ApiResult.success(null);
    }

    @PostMapping("/mappings/{id}/verify")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<Void> verifyMapping(@PathVariable("id") Long mappingId) {
        mappingService.verifyAndSnapshotMapping(mappingId);
        return ApiResult.success(null);
    }

    // === 3. ジョブ (Job) API ===

    @GetMapping("/jobs")
    public ApiResult<Page<IntegrationJob>> listJobs(
            @RequestParam(value = "current", defaultValue = "1") long current,
            @RequestParam(value = "size", defaultValue = "20") long size,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "jobType", required = false) String jobType,
            @RequestParam(value = "targetType", required = false) String targetType) {

        Page<IntegrationJob> page = PageUtils.safePage(current, size);
        LambdaQueryWrapper<IntegrationJob> wrapper = new LambdaQueryWrapper<IntegrationJob>()
                .orderByDesc(IntegrationJob::getId);

        if (status != null && !status.isBlank()) {
            wrapper.eq(IntegrationJob::getStatus, status);
        }
        if (jobType != null && !jobType.isBlank()) {
            wrapper.eq(IntegrationJob::getJobType, jobType);
        }
        if (targetType != null && !targetType.isBlank()) {
            wrapper.eq(IntegrationJob::getTargetType, targetType);
        }

        return ApiResult.success(jobService.page(page, wrapper));
    }

    @GetMapping("/jobs/{id}")
    public ApiResult<IntegrationJobDetailDto> getJobDetail(@PathVariable("id") Long jobId) {
        IntegrationJob job = jobService.getById(jobId);
        if (job == null) {
            return ApiResult.error(404, "ジョブが見つかりません");
        }
        List<IntegrationJobEvent> events = jobService.listEvents(jobId);
        return ApiResult.success(new IntegrationJobDetailDto(job, events));
    }

    @PostMapping("/jobs/{id}/retry")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<Void> retryJob(@PathVariable("id") Long jobId) {
        jobService.resetForManualRetry(jobId);
        return ApiResult.success(null);
    }

    @PostMapping("/jobs/{id}/cancel")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<Void> cancelJob(@PathVariable("id") Long jobId,
                                     @RequestParam(value = "reason", defaultValue = "手動キャンセル") String reason) {
        jobService.cancelJob(jobId, reason);
        return ApiResult.success(null);
    }

    // === 4. 送信プレビュー API ===

    @GetMapping("/preview/sales-invoice/{invoiceId}")
    public ApiResult<CanonicalSalesInvoice> previewSalesInvoice(@PathVariable("invoiceId") Long invoiceId) {
        Invoice invoice = invoiceService.getById(invoiceId);
        if (invoice == null) {
            return ApiResult.error(404, "請求書が見つかりません");
        }

        Customer customer = customerService.getById(invoice.getCustomerId());
        String customerCode = "CUST-" + invoice.getCustomerId();
        String customerName = customer != null ? customer.getCompanyName() : "顧客ID:" + invoice.getCustomerId();

        CanonicalSalesInvoice preview = CanonicalSalesInvoice.builder()
                .invoiceId(invoice.getId())
                .invoiceNo(invoice.getInvoiceNo())
                .customerId(invoice.getCustomerId())
                .customerCode(customerCode)
                .customerName(customerName)
                .issueDate(invoice.getIssuedDate())
                .dueDate(invoice.getDueDate())
                .subtotal(invoice.getSubtotal())
                .tax(invoice.getTax())
                .total(invoice.getTotal())
                .taxRate(invoice.getTaxRate())
                .remarks(invoice.getRemarks())
                .details(List.of(CanonicalSalesInvoice.Detail.builder()
                        .description(invoice.getRemarks() != null ? invoice.getRemarks() : "SES請求: " + invoice.getInvoiceNo())
                        .amount(invoice.getTotal())
                        .accountItemCode("2101")
                        .taxCode("21")
                        .build()))
                .build();

        return ApiResult.success(preview);
    }

    // === 5. 手動連携トリガー API (管理者のみ) ===

    @PostMapping("/sync/sales-invoice/{invoiceId}")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<IntegrationJob> triggerSalesSync(@PathVariable("invoiceId") Long invoiceId) {
        Long actorId = resolveActorId();
        IntegrationJob job = salesIntegrationService.triggerSalesSync(invoiceId, actorId);
        return ApiResult.success(job);
    }

    @PostMapping("/cancel/sales-invoice/{invoiceId}")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<IntegrationJob> triggerSalesCancel(@PathVariable("invoiceId") Long invoiceId,
                                                        @RequestParam(value = "reason", required = false) String reason) {
        Long actorId = resolveActorId();
        IntegrationJob job = salesIntegrationService.triggerSalesCancel(invoiceId, reason, actorId);
        return ApiResult.success(job);
    }

    @PostMapping("/sync/bp-purchase/{bpPaymentId}")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<IntegrationJob> triggerBpPurchaseSync(@PathVariable("bpPaymentId") Long bpPaymentId) {
        Long actorId = resolveActorId();
        IntegrationJob job = purchaseIntegrationService.triggerBpPurchaseSync(bpPaymentId, actorId);
        return ApiResult.success(job);
    }

    @PostMapping("/sync/payment/{bpPaymentId}")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<IntegrationJob> triggerPaymentSync(@PathVariable("bpPaymentId") Long bpPaymentId) {
        Long actorId = resolveActorId();
        IntegrationJob job = purchaseIntegrationService.triggerPaymentSync(bpPaymentId, actorId);
        return ApiResult.success(job);
    }

    @PostMapping("/sync/expense/{expenseRequestId}")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<IntegrationJob> triggerExpenseSync(@PathVariable("expenseRequestId") Long expenseRequestId) {
        Long actorId = resolveActorId();
        IntegrationJob job = purchaseIntegrationService.triggerExpenseSync(expenseRequestId, actorId);
        return ApiResult.success(job);
    }

    // === 6. 月次照合 (Reconciliation) API ===

    @GetMapping("/reconciliation")
    public ApiResult<com.ses.dto.accounting.AccountingReconciliationSummaryDto> getReconciliation(
            @RequestParam(value = "month", required = false) String month) {
        return ApiResult.success(reconciliationService.reconcileMonth(month));
    }

    @PostMapping("/reconciliation/ignore")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<Void> ignoreDiscrepancy(
            @RequestBody IgnoreRequest request) {
        Long actorId = resolveActorId();
        reconciliationService.ignoreDiscrepancy(
                request.month(),
                request.category(),
                request.externalDealId(),
                request.internalId(),
                request.reason(),
                actorId
        );
        return ApiResult.success(null);
    }

    public record IgnoreRequest(String month, String category, String externalDealId, Long internalId, String reason) {}

    public record IntegrationJobDetailDto(IntegrationJob job, List<IntegrationJobEvent> events) {}
}
