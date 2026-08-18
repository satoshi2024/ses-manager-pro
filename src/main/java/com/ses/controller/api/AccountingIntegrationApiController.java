package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.common.util.PageUtils;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.accounting.SalesPreviewDto;
import com.ses.dto.accounting.canonical.CanonicalSalesInvoice;
import com.ses.entity.*;
import com.ses.service.CustomerService;
import com.ses.service.InvoiceService;
import com.ses.service.accounting.AccountingProvider;
import com.ses.service.accounting.AccountingProviderFactory;
import com.ses.service.integration.ExternalMappingService;
import com.ses.service.integration.IntegrationConnectionService;
import com.ses.service.integration.IntegrationJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 会計・支払連携 API コントローラー (A1 / design §6.2)。
 * 管理者のみ connection/mapping 更新が可能。マネージャーはジョブ状態の読み取り専用。
 * トークンなどの秘密情報は API レスポンスに一切含めない (design §6.2)。
 * actorID は SecurityContext から取得し、hardcoded 値を使用しない (P1-06)。
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
    private final com.ses.service.accounting.SalesInvoiceIntegrationService salesIntegrationService;
    private final com.ses.service.accounting.PurchaseExpensePaymentIntegrationService purchaseIntegrationService;

    /** SecurityContext からユーザーIDを取得する（P1-06: hardcoded 1L を排除）。 */
    private Long resolveActorId() {
        Long userId = SecurityUtils.currentUserId();
        return userId != null ? userId : -1L;
    }

    // === 1. 接続マスタ (Connection) API ===

    /** 接続一覧（管理者・マネージャー参照可能）。トークン情報はレスポンスから除外。 */
    @GetMapping("/connections")
    public ApiResult<List<IntegrationConnection>> listConnections(
            @RequestParam(value = "tenantId", defaultValue = "default") String tenantId) {
        List<IntegrationConnection> list = connectionService.listConnections(tenantId);
        // encryptedTokens が確実に null であることを保証
        for (IntegrationConnection c : list) {
            c.setEncryptedTokens(null);
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
            if (!valid && "CONNECTED".equals(conn.getStatus())) {
                connectionService.updateStatus(connectionId, "REAUTH_REQUIRED");
            }
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

    /** 管理者のみマッピングの変更・検証が可能。マネージャーは参照のみ。 */
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
    public ApiResult<Void> verifyMapping(@PathVariable("id") Long mappingId,
                                         @RequestBody(required = false) String snapshot) {
        mappingService.verifyMapping(mappingId, snapshot != null ? snapshot : "{\"verified\": true}");
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

        Page<IntegrationJob> result = jobService.page(page, wrapper);
        return ApiResult.success(result);
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

    // === 4. 送信前プレビュー (Preview) API ===

    @GetMapping("/preview/sales/{invoiceId}")
    public ApiResult<SalesPreviewDto> previewSalesInvoice(@PathVariable("invoiceId") Long invoiceId) {
        Invoice invoice = invoiceService.getById(invoiceId);
        if (invoice == null) {
            return ApiResult.error(404, "請求書が見つかりません (id=" + invoiceId + ")");
        }

        Customer customer = customerService.getById(invoice.getCustomerId());
        String customerCode = "CUST-" + invoice.getCustomerId();
        String customerName = customer != null ? customer.getCompanyName() : "顧客ID:" + invoice.getCustomerId();

        IntegrationConnection conn = connectionService.getConnection("default", null, "freee", "accounting");
        if (conn == null) {
            conn = connectionService.getOrCreateConnection("default", null, "freee", "accounting");
        }

        List<String> validationErrors = new ArrayList<>();
        if (!"CONNECTED".equals(conn.getStatus())) {
            validationErrors.add("会計接続状態が無効または未接続です (ステータス: " + conn.getStatus() + ")");
        }

        // 取引先マッピング確認
        ExternalMapping partnerMapping = mappingService.getMapping(conn.getId(), "CUSTOMER_PARTNER", customerCode);
        if (partnerMapping == null) {
            validationErrors.add("顧客取引先マッピングが未登録です [顧客コード: " + customerCode + "]");
        } else if (partnerMapping.getVerifiedAt() == null) {
            validationErrors.add("顧客取引先マッピングが未検証です [顧客コード: " + customerCode + ", 外部ID: " + partnerMapping.getExternalId() + "]");
        }

        // 勘定科目マッピング確認
        ExternalMapping salesAccountMapping = mappingService.getMapping(conn.getId(), "ACCOUNT_SALES", "SALES_DEFAULT");
        if (salesAccountMapping == null || salesAccountMapping.getVerifiedAt() == null) {
            validationErrors.add("売上高勘定科目マッピングが未登録または未検証です");
        }

        // 税区分マッピング確認
        ExternalMapping taxMapping = mappingService.getMapping(conn.getId(), "TAX_SALES_10", "TAX_10");
        if (taxMapping == null || taxMapping.getVerifiedAt() == null) {
            validationErrors.add("消費税10%税区分マッピングが未登録または未検証です");
        }

        CanonicalSalesInvoice canonical = CanonicalSalesInvoice.builder()
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
                .build();

        SalesPreviewDto preview = SalesPreviewDto.builder()
                .readyToSend(validationErrors.isEmpty())
                .validationErrors(validationErrors)
                .canonicalInvoice(canonical)
                .providerName(conn.getProvider())
                .externalCompanyName(conn.getCompanyName())
                .build();

        return ApiResult.success(preview);
    }

    // === 5. トリガー (Trigger) API ===

    @PostMapping("/sync/sales/{invoiceId}")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<IntegrationJob> triggerSalesSync(@PathVariable("invoiceId") Long invoiceId) {
        Long actorId = resolveActorId();
        IntegrationJob job = salesIntegrationService.triggerSalesSync(invoiceId, actorId);
        return ApiResult.success(job);
    }

    @PostMapping("/cancel/sales/{invoiceId}")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<IntegrationJob> triggerSalesCancel(@PathVariable("invoiceId") Long invoiceId,
                                                         @RequestParam(value = "reason", defaultValue = "請求取消") String reason) {
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

    // === 6. 月次照合 (Reconciliation) API ===

    private final com.ses.service.accounting.AccountingReconciliationService reconciliationService;

    @GetMapping("/reconciliation")
    public ApiResult<com.ses.dto.accounting.AccountingReconciliationSummaryDto> getReconciliation(
            @RequestParam(value = "month", required = false) String month) {
        return ApiResult.success(reconciliationService.reconcileMonth(month));
    }

    @PostMapping("/reconciliation/ignore")
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

    public record IntegrationJobDetailDto(IntegrationJob job, List<IntegrationJobEvent> events) {
    }
}
