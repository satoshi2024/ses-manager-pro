package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.PageUtils;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.accounting.AccountingSendPreviewDto;
import com.ses.dto.accounting.canonical.CanonicalExpenseDeal;
import com.ses.dto.accounting.canonical.CanonicalPurchaseDeal;
import com.ses.dto.accounting.canonical.CanonicalSalesInvoice;
import com.ses.entity.BpPayment;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.ExpenseRequest;
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
import com.ses.service.accounting.AccountingTenantContextHolder;
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
    private final com.ses.service.security.OrganizationScopeService organizationScopeService;
    private final com.ses.service.accounting.AccountingOrganizationResolver organizationResolver;
    private final com.ses.mapper.BpPaymentMapper bpPaymentMapper;
    private final com.ses.mapper.ExpenseRequestMapper expenseRequestMapper;
    private final com.ses.mapper.EngineerMapper engineerMapper;
    private final com.ses.mapper.OrganizationUnitMapper organizationUnitMapper;
    private final com.ses.mapper.InvoiceMapper invoiceMapper;

    /** SecurityContext からユーザーIDを取得する (fail-closed)。 */
    private Long resolveActorId() {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) {
            throw new BusinessException(401, "ユーザー認証が必要です");
        }
        return userId;
    }

    /** マネージャーの許可組織ID集合。フルアクセス時は null。 */
    private java.util.Set<Long> allowedOrgIdsOrNull() {
        if (organizationScopeService == null || organizationScopeService.hasFullAccess()) {
            return null;
        }
        return organizationScopeService.allowedOrganizationIds(java.time.LocalDate.now());
    }

    /** 許可組織に紐づく法人ID集合 (design §5.2: 接続一覧の legal_entity スコープ)。 */
    private java.util.Set<Long> allowedLegalEntityIds(java.util.Set<Long> allowedOrgIds) {
        if (allowedOrgIds == null) {
            return null;
        }
        if (allowedOrgIds.isEmpty()) {
            return java.util.Set.of();
        }
        return organizationUnitMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.ses.entity.OrganizationUnit>()
                        .in(com.ses.entity.OrganizationUnit::getId, allowedOrgIds))
                .stream()
                .map(com.ses.entity.OrganizationUnit::getLegalEntityId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
    }

    // === 1. 接続マスタ (Connection) API ===

    @GetMapping("/connections")
    public ApiResult<List<IntegrationConnection>> listConnections() {
        // R1-P1-06: tenant は認証済みコンテキストから解決し、利用者指定パラメータを信用しない
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        java.util.Set<Long> allowedOrgIds = allowedOrgIdsOrNull();
        java.util.Set<Long> allowedLegalEntities = allowedLegalEntityIds(allowedOrgIds);

        List<IntegrationConnection> list;
        if (allowedLegalEntities != null) {
            if (allowedLegalEntities.isEmpty()) {
                return ApiResult.success(java.util.Collections.emptyList());
            }
            list = connectionService.listConnectionsByLegalEntities(tenantId, allowedLegalEntities);
        } else {
            list = connectionService.listConnections(tenantId);
        }
        for (IntegrationConnection c : list) {
            c.setEncryptedTokens(null); // セキュリティのため確実にマスク
        }
        return ApiResult.success(list);
    }

    @GetMapping("/connections/{id}/health")
    public ApiResult<Boolean> checkHealth(@PathVariable("id") Long connectionId) {
        // R1-P1-06: tenant + 法人条件を SQL で適用 (権限外・他tenantは 404)
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        java.util.Set<Long> allowedLegalEntities = allowedLegalEntityIds(allowedOrgIdsOrNull());
        IntegrationConnection conn = connectionService.getByIdScoped(connectionId, tenantId, allowedLegalEntities);
        if (conn == null) {
            return ApiResult.error(404, "接続マスタが見つかりません");
        }
        try {
            AccountingProvider provider = providerFactory.getProvider(conn);
            boolean valid = provider.validateConnection(conn);
            return ApiResult.success(valid);
        } catch (Exception e) {
            log.warn("Health check failed for connectionId={}", connectionId);
            return ApiResult.success(false);
        }
    }

    @PostMapping("/connections/{id}/status")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<Void> updateStatus(@PathVariable("id") Long connectionId,
                                        @RequestParam("status") String status) {
        // R1-P1-06: 管理者も tenant 境界は SQL で適用 (他tenantの状態変更を防止)
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        IntegrationConnection conn = connectionService.getByIdScoped(connectionId, tenantId, null);
        if (conn == null) {
            return ApiResult.error(404, "接続マスタが見つかりません");
        }
        connectionService.updateStatus(connectionId, status);
        return ApiResult.success(null);
    }

    // === 2. マッピング (Mapping) API ===

    @GetMapping("/mappings")
    public ApiResult<List<ExternalMapping>> listMappings(
            @RequestParam("connectionId") Long connectionId,
            @RequestParam(value = "objectType", required = false) String objectType) {
        // R1-P1-06: マネージャーは許可接続 (許可法人) のマッピングのみ (SQL境界)。管理者も current tenant に属する接続のみ。
        String currentTenantId = AccountingTenantContextHolder.requireTenantContext();
        java.util.Set<Long> allowedOrgIds = allowedOrgIdsOrNull();
        java.util.Set<Long> allowedLegalEntities = allowedLegalEntityIds(allowedOrgIds);
        java.util.Set<Long> allowedConnectionIds = null;
        if (allowedLegalEntities != null) {
            if (allowedLegalEntities.isEmpty()) {
                return ApiResult.success(java.util.Collections.emptyList());
            }
            allowedConnectionIds = connectionService.listConnectionsByLegalEntities(
                            currentTenantId, allowedLegalEntities)
                    .stream().map(IntegrationConnection::getId).collect(java.util.stream.Collectors.toSet());
        } else {
            // 管理者: 接続が current tenant に属することを SQL で確認 (他tenantは0件)
            if (connectionService.getByIdScoped(connectionId, currentTenantId, null) == null) {
                return ApiResult.success(java.util.Collections.emptyList());
            }
        }
        List<ExternalMapping> list = mappingService.listByConnectionsScoped(connectionId, objectType, allowedConnectionIds);
        return ApiResult.success(list);
    }

    @PostMapping("/mappings")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<Void> saveMapping(@RequestBody ExternalMapping mapping) {
        // R1-P1-06: 管理者も current tenant に属する接続のマッピングのみ変更可 (他tenantは404)
        if (mapping.getConnectionId() == null) {
            return ApiResult.error(400, "接続IDが必要です");
        }
        IntegrationConnection conn = connectionService.getByIdScoped(
                mapping.getConnectionId(), AccountingTenantContextHolder.requireTenantContext(), null);
        if (conn == null) {
            return ApiResult.error(404, "接続マスタが見つかりません");
        }
        mappingService.saveOrUpdateMapping(mapping);
        return ApiResult.success(null);
    }

    @PostMapping("/mappings/{id}/verify")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<Void> verifyMapping(@PathVariable("id") Long mappingId) {
        // R1-P1-06: 他tenantのマッピングは verify も不可 (404)
        // R4-R3: mappingId + current tenant をconnection JOIN付きの最初のSQLで解決する。
        // 照合失敗は未検証への差し戻しをcommitした後で例外にする（service内で投げるとロールバックされる）。
        ExternalMappingService.VerifyOutcome outcome = mappingService.verifyAndSnapshotMappingScoped(
                mappingId, AccountingTenantContextHolder.requireTenantContext());
        return switch (outcome) {
            case VERIFIED -> ApiResult.success(null);
            // 他tenant・不存在は従来どおり HTTP 200 + code 404（tenant境界テストの契約）
            case NOT_FOUND -> ApiResult.error(404, "マッピングが見つかりません");
            case UNSUPPORTED_TYPE -> throw BusinessException.of(400, "error.accounting.mappingTypeUnsupported");
            case REJECTED -> {
                ExternalMapping mapping = mappingService.getById(mappingId);
                Object typeArg = mapping == null ? "-" : com.ses.service.accounting.AccountingMappingType
                        .parse(mapping.getObjectType()).<Object>map(t -> t).orElse("-");
                throw BusinessException.of(400, "error.accounting.mappingVerifyFailed", typeArg);
            }
        };
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

        String currentTenantId = AccountingTenantContextHolder.requireTenantContext();
        if (currentTenantId != null && !currentTenantId.isBlank()) {
            wrapper.eq(IntegrationJob::getTenantId, currentTenantId);
        }

        if (status != null && !status.isBlank()) {
            wrapper.eq(IntegrationJob::getStatus, status);
        }
        if (jobType != null && !jobType.isBlank()) {
            wrapper.eq(IntegrationJob::getJobType, jobType);
        }
        if (targetType != null && !targetType.isBlank()) {
            wrapper.eq(IntegrationJob::getTargetType, targetType);
        }

        // マネージャーの組織スコープ境界適用 (design §5.2, R1-P1-06)
        if (organizationScopeService != null && !organizationScopeService.hasFullAccess()) {
            java.util.Set<Long> allowedOrgIds = organizationScopeService.allowedOrganizationIds(java.time.LocalDate.now());
            if (allowedOrgIds.isEmpty()) {
                wrapper.apply("1 = 0"); // 空集合ガード
            } else {
                wrapper.in(IntegrationJob::getOrganizationId, allowedOrgIds);
            }
        }

        return ApiResult.success(jobService.page(page, wrapper));
    }

    @GetMapping("/jobs/{id}")
    public ApiResult<IntegrationJobDetailDto> getJobDetail(@PathVariable("id") Long jobId) {
        LambdaQueryWrapper<IntegrationJob> query = new LambdaQueryWrapper<IntegrationJob>()
                .eq(IntegrationJob::getId, jobId);

        String currentTenantId = AccountingTenantContextHolder.requireTenantContext();
        if (currentTenantId != null && !currentTenantId.isBlank()) {
            query.eq(IntegrationJob::getTenantId, currentTenantId);
        }

        if (organizationScopeService != null && !organizationScopeService.hasFullAccess()) {
            java.util.Set<Long> allowedOrgIds = organizationScopeService.allowedOrganizationIds(java.time.LocalDate.now());
            if (allowedOrgIds.isEmpty()) {
                query.apply("1 = 0");
            } else {
                query.in(IntegrationJob::getOrganizationId, allowedOrgIds);
            }
        }
        IntegrationJob job = jobService.getOne(query);
        if (job == null) {
            return ApiResult.error(404, "ジョブが見つかりません");
        }
        List<IntegrationJobEvent> events = jobService.listEvents(jobId);
        return ApiResult.success(new IntegrationJobDetailDto(job, events));
    }

    @PostMapping("/jobs/{id}/retry")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<Void> retryJob(@PathVariable("id") Long jobId) {
        LambdaQueryWrapper<IntegrationJob> query = new LambdaQueryWrapper<IntegrationJob>()
                .eq(IntegrationJob::getId, jobId);
        String currentTenantId = AccountingTenantContextHolder.requireTenantContext();
        if (currentTenantId != null && !currentTenantId.isBlank()) {
            query.eq(IntegrationJob::getTenantId, currentTenantId);
        }
        IntegrationJob job = jobService.getOne(query);
        if (job == null) {
            return ApiResult.error(404, "ジョブが見つかりません");
        }

        jobService.resetForManualRetry(jobId);
        return ApiResult.success(null);
    }

    @PostMapping("/jobs/{id}/cancel")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<Void> cancelJob(@PathVariable("id") Long jobId,
                                     @RequestParam(value = "reason", defaultValue = "REASON_CLIENT_CANCEL") String reason) {
        LambdaQueryWrapper<IntegrationJob> query = new LambdaQueryWrapper<IntegrationJob>()
                .eq(IntegrationJob::getId, jobId);
        String currentTenantId = AccountingTenantContextHolder.requireTenantContext();
        if (currentTenantId != null && !currentTenantId.isBlank()) {
            query.eq(IntegrationJob::getTenantId, currentTenantId);
        }
        IntegrationJob job = jobService.getOne(query);
        if (job == null) {
            return ApiResult.error(404, "ジョブが見つかりません");
        }

        // P1-11: 取消理由は機械可読コード (design §8.3)。未知・日本語入力は REASON_OTHER へ正規化
        jobService.cancelJob(jobId, normalizeCancelReason(reason));
        return ApiResult.success(null);
    }

    /** 取消理由コードの完全 allow-list (design §8.3)。これ以外は REASON_OTHER へ正規化。 */
    private static final java.util.Set<String> CANCEL_REASON_CODES = java.util.Set.of(
            "REASON_CLIENT_CANCEL", "REASON_AMOUNT_CORRECTION", "REASON_DUPLICATE", "REASON_DISPUTE", "REASON_OTHER");

    private static String normalizeCancelReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "REASON_CLIENT_CANCEL";
        }
        String trimmed = reason.trim();
        return CANCEL_REASON_CODES.contains(trimmed) ? trimmed : "REASON_OTHER";
    }

    // === 4. 送信プレビュー API ===

    /*
     * プレビューは送信ジョブと同じ標準DTO（照合済みマッピングの外部IDを反映）と、必要マッピングの状態を返す。
     * 以前は勘定科目・税区分を固定値で組み立てていたため、実際に送る内容と一致しなかった。
     */

    @GetMapping("/preview/sales-invoice/{invoiceId}")
    public ApiResult<AccountingSendPreviewDto<CanonicalSalesInvoice>> previewSalesInvoice(@PathVariable("invoiceId") Long invoiceId) {
        // R1-P1-06: 組織条件を最初のSQLへ適用 (権限外IDは 404)
        java.util.Set<Long> allowedOrgIds = allowedOrgIdsOrNull();
        Invoice invoice = allowedOrgIds != null
                ? invoiceMapper.selectForPreviewScoped(invoiceId, new java.util.ArrayList<>(allowedOrgIds))
                : invoiceService.getById(invoiceId);
        if (invoice == null) {
            return ApiResult.error(404, "請求書が見つかりません");
        }
        return ApiResult.success(salesIntegrationService.previewSalesSync(invoice.getId()));
    }

    @GetMapping("/preview/bp-purchase/{bpPaymentId}")
    public ApiResult<AccountingSendPreviewDto<CanonicalPurchaseDeal>> previewBpPurchase(@PathVariable("bpPaymentId") Long bpPaymentId) {
        // R1-P1-06: 組織条件を最初のSQLへ適用 (権限外IDは 404)
        java.util.Set<Long> allowedOrgIds = allowedOrgIdsOrNull();
        BpPayment bp = allowedOrgIds != null
                ? bpPaymentMapper.selectForPreviewScoped(bpPaymentId, new java.util.ArrayList<>(allowedOrgIds))
                : bpPaymentMapper.selectByIdForTenant(bpPaymentId, AccountingTenantContextHolder.requireTenantContext());
        if (bp == null) {
            return ApiResult.error(404, "BP支払レコードが見つかりません");
        }
        return ApiResult.success(purchaseIntegrationService.previewBpPurchaseSync(bp.getId()));
    }

    @GetMapping("/preview/expense/{expenseRequestId}")
    public ApiResult<AccountingSendPreviewDto<CanonicalExpenseDeal>> previewExpense(@PathVariable("expenseRequestId") Long expenseRequestId) {
        // R1-P1-06: 組織条件を最初のSQLへ適用 (UNKNOWN履歴はfail-closed、権限外IDは 404)
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        java.util.Set<Long> allowedOrgIds = allowedOrgIdsOrNull();
        ExpenseRequest exp = allowedOrgIds != null
                ? expenseRequestMapper.selectForPreviewScoped(expenseRequestId,
                new java.util.ArrayList<>(allowedOrgIds), tenantId)
                : expenseRequestMapper.selectByIdForTenant(expenseRequestId, tenantId);
        if (exp == null) {
            return ApiResult.error(404, "経費申請レコードが見つかりません");
        }
        Engineer eng = exp.getEngineerId() != null
                ? engineerMapper.selectByIdForTenant(exp.getEngineerId(), tenantId) : null;
        if (exp.getEngineerId() != null && eng == null) {
            return ApiResult.error(404, "経費申請レコードが見つかりません");
        }
        return ApiResult.success(purchaseIntegrationService.previewExpenseSync(exp.getId()));
    }

    /** 送信プレビューの対象候補。 */
    public record SendCandidate(Long id, String no, String name, java.math.BigDecimal amount,
                                String date, String status) {}

    private static final int SEND_CANDIDATE_LIMIT = 200;

    /**
     * BP仕入・要員経費の送信候補（送信可能ステータスのもの、新しい順に最大200件）。
     * 送信操作と同じく管理者のみ。売上請求の候補は既存の請求書一覧APIを使う。
     */
    @GetMapping("/send-candidates")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<List<SendCandidate>> listSendCandidates(@RequestParam("kind") String kind) {
        if ("purchase".equals(kind)) {
            // BP支払はtenant列を持たないため、工数実績→契約→顧客のownershipで現在tenantへ限定する
            String tenantId = AccountingTenantContextHolder.requireTenantContext();
            List<BpPayment> payments = bpPaymentMapper.selectSendCandidatesForTenant(tenantId, SEND_CANDIDATE_LIMIT);
            return ApiResult.success(payments.stream()
                    .map(p -> new SendCandidate(p.getId(), "BP-PAY-" + p.getId(),
                            p.getPayeeCompanyName() != null ? p.getPayeeCompanyName() : p.getBpCompanyNameSnapshot(),
                            p.getAmount(), null, p.getStatus()))
                    .toList());
        }
        if ("expense".equals(kind)) {
            String tenantId = AccountingTenantContextHolder.requireTenantContext();
            return ApiResult.success(expenseRequestMapper.selectApprovedByTenant(tenantId, SEND_CANDIDATE_LIMIT).stream()
                    .map(e -> new SendCandidate(e.getId(),
                            e.getExpenseNo() != null ? e.getExpenseNo() : "EX-" + e.getId(),
                            e.getCategory(), e.getAmount(),
                            e.getExpenseDate() != null ? e.getExpenseDate().toString() : null, e.getStatus()))
                    .toList());
        }
        throw BusinessException.of(400, "error.accounting.unknownSendKind");
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

    /**
     * 連携ジョブ一覧から、送信済みのfreee売上取引を取消する。
     * 取消対象は「その請求書の最新の成功した送信ジョブ」に限る。取消→再送信の後に古い行から操作すると、
     * 請求書単位の取消APIは最新の取引を消してしまうため、行と取引の対応をここで確かめる。
     */
    @PostMapping("/jobs/{id}/cancel-deal")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<IntegrationJob> cancelSyncedSalesDeal(@PathVariable("id") Long jobId,
                                                           @RequestParam(value = "reason", required = false) String reason) {
        IntegrationJob job = jobService.getOne(new LambdaQueryWrapper<IntegrationJob>()
                .eq(IntegrationJob::getId, jobId)
                .eq(IntegrationJob::getTenantId, AccountingTenantContextHolder.requireTenantContext()));
        if (job == null) {
            return ApiResult.error(404, "ジョブが見つかりません");
        }
        if (!"SALES_INVOICE_SYNC".equals(job.getJobType()) || !"SUCCEEDED".equals(job.getStatus())
                || job.getExternalId() == null) {
            throw BusinessException.of(400, "error.accounting.dealNotCancellable");
        }
        IntegrationJob latest = jobService.getLatestJob("INVOICE", job.getTargetId(), "SALES_INVOICE_SYNC");
        if (latest == null || !latest.getId().equals(job.getId())) {
            throw BusinessException.of(409, "error.accounting.notLatestDeal");
        }
        return ApiResult.success(salesIntegrationService.triggerSalesCancel(job.getTargetId(), reason, resolveActorId()));
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
