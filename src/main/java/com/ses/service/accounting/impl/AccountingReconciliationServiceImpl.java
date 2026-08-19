package com.ses.service.accounting.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.accounting.AccountingReconciliationSummaryDto;
import com.ses.dto.accounting.AccountingReconciliationSummaryDto.ReconciliationItemDto;
import com.ses.dto.accounting.canonical.CanonicalPaymentSync;
import com.ses.entity.*;
import com.ses.mapper.BpPaymentMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ExpenseRequestMapper;
import com.ses.mapper.InvoiceMapper;
import com.ses.mapper.InvoicePaymentMapper;
import com.ses.mapper.SystemConfigMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.accounting.AccountingOrganizationResolver;
import com.ses.service.accounting.AccountingProvider;
import com.ses.service.accounting.AccountingProviderFactory;
import com.ses.service.accounting.AccountingReconciliationService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.integration.IntegrationConnectionService;
import com.ses.service.integration.IntegrationJobService;
import com.ses.service.security.OrganizationScopeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * 会計・支払月次照合サービス実装 (B3 / design §5, §6.1, §6.2, §6.3 / P1-09).
 * <p>
 * - 売上、BP仕入、要員経費、請求書入金 (t_invoice_payment) の 4 母集団完全照合。
 * - freee 取引一覧の実外部金額突合。
 * - 振込手数料込み総消込突合 (amount + fee) および曖昧入金 (PAYMENT_AMBIGUOUS) の fail-closed。
 * - 未接続・トークンなし・外部 API 取得失敗・50ページ到達時の厳格 fail-closed (readyForClosing = false)。
 * - 生例外・PII の完全遮断。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountingReconciliationServiceImpl implements AccountingReconciliationService {

    private final InvoiceMapper invoiceMapper;
    private final BpPaymentMapper bpPaymentMapper;
    private final CustomerMapper customerMapper;
    private final WorkRecordMapper workRecordMapper;
    private final ExpenseRequestMapper expenseRequestMapper;
    private final InvoicePaymentMapper invoicePaymentMapper;
    private final EngineerMapper engineerMapper;
    private final IntegrationJobService jobService;
    private final IntegrationConnectionService connectionService;
    private final AccountingProviderFactory providerFactory;
    private final AccountingOrganizationResolver organizationResolver;
    private final OrganizationScopeService organizationScopeService;
    private final SystemConfigMapper systemConfigMapper;
    private final ObjectMapper objectMapper;

    private static final String IGNORE_CONFIG_PREFIX = "accounting.reconciliation.ignore.";

    @Override
    public AccountingReconciliationSummaryDto reconcileMonth(String month) {
        // R4-T06: 月はテナントタイムゾーン基準の「今月」を既定とする (design §6.1)
        java.time.ZoneId zoneId = AccountingTenantContextHolder.getZoneId();
        if (month == null || month.isBlank()) {
            month = YearMonth.now(zoneId).toString();
        }

        YearMonth ymMonth = YearMonth.parse(month);
        LocalDate startOfMonth = ymMonth.atDay(1);
        LocalDate endOfMonth = ymMonth.atEndOfMonth();

        List<ReconciliationItemDto> items = new ArrayList<>();
        Map<String, String> ignoreMap = loadIgnoreMap(month);

        IntegrationConnection conn = resolveConnection("default", null, "freee", "accounting");
        boolean externalFetchFailed = false;
        List<CanonicalPaymentSync> extDeals = new ArrayList<>();    // deal単位 (売上/仕入/経費照合・EXTERNAL_ONLY)
        List<CanonicalPaymentSync> extPayments = new ArrayList<>(); // payment単位 (入金 1:1 消込)

        // 外部 API からのデータ取得 (P1-09: トークンなし、未接続、API 障害、50ページ上限、重複ID時は fail-closed)
        if (conn == null || conn.getEncryptedTokens() == null || conn.getEncryptedTokens().isBlank()) {
            externalFetchFailed = true;
            items.add(ReconciliationItemDto.builder()
                    .category("EXTERNAL_FETCH_ERROR")
                    .partnerName("外部サービス")
                    .status("AMOUNT_MISMATCH")
                    .discrepancyReason("外部連携接続マスタまたは認証トークンが未設定のため外部照合を実行できませんでした (エラーコード: UNAUTHORIZED)")
                    .build());
        } else {
            try {
                AccountingProvider provider = providerFactory.getProvider(conn);
                com.ses.dto.accounting.PaymentFetchResult fetchResult = provider.fetchPayments(conn, startOfMonth, endOfMonth);
                extDeals = fetchResult != null && fetchResult.getDeals() != null ? fetchResult.getDeals() : new ArrayList<>();
                extPayments = fetchResult != null && fetchResult.getPayments() != null ? fetchResult.getPayments() : new ArrayList<>();
                if (fetchResult != null && (fetchResult.isPageCapReached() || fetchResult.isDuplicateDealId()
                        || fetchResult.isDuplicatePaymentId() || fetchResult.isFetchFailed())) {
                    externalFetchFailed = true;
                    String reason;
                    if (fetchResult.isPageCapReached()) {
                        reason = "外部取引一覧の取得が50ページ(5,000件)上限に到達しました。絞り込みまたはfreee側の確認が必要です (エラーコード: PAGE_CAP_REACHED)";
                    } else if (fetchResult.isDuplicateDealId()) {
                        reason = "外部取引一覧に重複する取引IDが含まれており、照合を実行できません (エラーコード: DUPLICATE_DEAL_ID)";
                    } else if (fetchResult.isDuplicatePaymentId()) {
                        reason = "外部取引一覧に重複する決済IDが含まれており、照合を実行できません (エラーコード: DUPLICATE_PAYMENT_ID)";
                    } else {
                        reason = "外部システムからの取引実績取得に失敗しました (エラーコード: " + (fetchResult.getErrorCode() != null ? fetchResult.getErrorCode() : "EXTERNAL_API_ERROR") + ")";
                    }
                    items.add(ReconciliationItemDto.builder()
                            .category("EXTERNAL_FETCH_ERROR")
                            .partnerName("外部サービス")
                            .status("AMOUNT_MISMATCH")
                            .discrepancyReason(reason)
                            .build());
                }
            } catch (Exception e) {
                log.error("External payments fetch failed for month={}, error_code=EXTERNAL_API_ERROR", month);
                externalFetchFailed = true;
                items.add(ReconciliationItemDto.builder()
                        .category("EXTERNAL_FETCH_ERROR")
                        .partnerName("外部サービス")
                        .status("AMOUNT_MISMATCH")
                        .discrepancyReason("外部システムからの取引実績取得に失敗しました (エラーコード: EXTERNAL_API_ERROR)")
                        .build());
            }
        }

        // 組織スコープの事前判定 (P1-06)
        boolean isManager = (organizationScopeService != null && !organizationScopeService.hasFullAccess());
        Set<Long> allowedOrgIds = isManager ? organizationScopeService.allowedOrganizationIds(LocalDate.now(zoneId)) : Collections.emptySet();

        // 外部取引 (deal単位) のマッピング用インデックス (dealId -> CanonicalPaymentSync)
        Map<String, CanonicalPaymentSync> extDealMap = new HashMap<>();
        for (CanonicalPaymentSync ext : extDeals) {
            if (ext.getDealId() != null) {
                extDealMap.put(ext.getDealId(), ext);
            }
        }
        Set<String> matchedExternalDealIds = new HashSet<>();

        // ============================================================
        // 1. 母集団 1: 売上請求 (t_invoice)
        // ============================================================
        // R1-P1-06: マネージャーは組織条件を最初のSQLへ適用 (空集合時は DB レベルで 1=0)
        List<Invoice> invoices;
        if (isManager) {
            invoices = invoiceMapper.selectForReconciliationScoped(month, new java.util.ArrayList<>(allowedOrgIds));
        } else {
            invoices = invoiceMapper.selectList(new LambdaQueryWrapper<Invoice>()
                    .eq(Invoice::getBillingMonth, month));
        }

        for (Invoice inv : invoices) {
            Customer cust = inv.getCustomerId() != null ? customerMapper.selectById(inv.getCustomerId()) : null;
            String partnerName = cust != null ? cust.getCompanyName() : "顧客ID:" + inv.getCustomerId();

            IntegrationJob latestJob = jobService.getLatestJob("INVOICE", inv.getId(), "SALES_INVOICE_SYNC");
            String itemKey = "SALES:" + inv.getId();
            String ignoreReason = ignoreMap.get(itemKey);

            if (latestJob != null && "SUCCEEDED".equals(latestJob.getStatus()) && latestJob.getExternalId() != null) {
                String dealId = latestJob.getExternalId();
                CanonicalPaymentSync extDeal = extDealMap.get(dealId);

                if (extDeal == null) {
                    // P1-09: 外部取引が削除または未存在の場合は INTERNAL_ONLY (fail-closed)
                    items.add(ReconciliationItemDto.builder()
                            .category("SALES")
                            .internalId(inv.getId())
                            .internalNo(inv.getInvoiceNo())
                            .partnerName(partnerName)
                            .internalAmount(inv.getTotal())
                            .externalDealId(dealId)
                            .externalRefNo(inv.getInvoiceNo())
                            .externalAmount(BigDecimal.ZERO)
                            .status(ignoreReason != null ? "IGNORED" : "INTERNAL_ONLY")
                            .discrepancyReason("freee取引が削除または未存在 (dealId=" + dealId + ")")
                            .ignoreReason(ignoreReason)
                            .build());
                } else {
                    matchedExternalDealIds.add(dealId);
                    BigDecimal extAmount = extDeal.getAmount();
                    boolean amountMatches = inv.getTotal() != null && extAmount != null && inv.getTotal().compareTo(extAmount) == 0;

                    if (amountMatches) {
                        items.add(ReconciliationItemDto.builder()
                                .category("SALES")
                                .internalId(inv.getId())
                                .internalNo(inv.getInvoiceNo())
                                .partnerName(partnerName)
                                .internalAmount(inv.getTotal())
                                .externalDealId(dealId)
                                .externalRefNo(inv.getInvoiceNo())
                                .externalAmount(extAmount)
                                .status(ignoreReason != null ? "IGNORED" : "MATCHED")
                                .ignoreReason(ignoreReason)
                                .build());
                    } else {
                        items.add(ReconciliationItemDto.builder()
                                .category("SALES")
                                .internalId(inv.getId())
                                .internalNo(inv.getInvoiceNo())
                                .partnerName(partnerName)
                                .internalAmount(inv.getTotal())
                                .externalDealId(dealId)
                                .externalRefNo(inv.getInvoiceNo())
                                .externalAmount(extAmount)
                                .status(ignoreReason != null ? "IGNORED" : "AMOUNT_MISMATCH")
                                .discrepancyReason("外部取引金額不一致 (内部: " + inv.getTotal() + ", 外部: " + extAmount + ")")
                                .ignoreReason(ignoreReason)
                                .build());
                    }
                }
            } else if (latestJob != null && "FAILED".equals(latestJob.getStatus()) && "AMOUNT_MISMATCH".equals(latestJob.getErrorCode())) {
                items.add(ReconciliationItemDto.builder()
                        .category("SALES")
                        .internalId(inv.getId())
                        .internalNo(inv.getInvoiceNo())
                        .partnerName(partnerName)
                        .internalAmount(inv.getTotal())
                        .externalDealId(latestJob.getExternalId())
                        .externalRefNo(inv.getInvoiceNo())
                        .externalAmount(BigDecimal.ZERO)
                        .status(ignoreReason != null ? "IGNORED" : "AMOUNT_MISMATCH")
                        .discrepancyReason("外部取引との金額不一致")
                        .ignoreReason(ignoreReason)
                        .build());
            } else {
                items.add(ReconciliationItemDto.builder()
                        .category("SALES")
                        .internalId(inv.getId())
                        .internalNo(inv.getInvoiceNo())
                        .partnerName(partnerName)
                        .internalAmount(inv.getTotal())
                        .status(ignoreReason != null ? "IGNORED" : "INTERNAL_ONLY")
                        .discrepancyReason("freee売上未送信または連携未完了")
                        .ignoreReason(ignoreReason)
                        .build());
            }
        }

        // ============================================================
        // 2. 母集団 2: BP仕入 (t_bp_payment / t_work_record)
        // ============================================================
        // R1-P1-06: マネージャーは組織条件を最初のSQLへ適用
        List<BpPayment> bpPayments;
        if (isManager) {
            bpPayments = bpPaymentMapper.selectForReconciliationScoped(month, new java.util.ArrayList<>(allowedOrgIds));
        } else {
            List<WorkRecord> workRecords = workRecordMapper.selectList(new LambdaQueryWrapper<WorkRecord>()
                    .eq(WorkRecord::getWorkMonth, month));
            List<Long> wrIds = workRecords.stream().map(WorkRecord::getId).toList();
            bpPayments = wrIds.isEmpty() ? Collections.emptyList() :
                    bpPaymentMapper.selectList(new LambdaQueryWrapper<BpPayment>().in(BpPayment::getWorkRecordId, wrIds));
        }

        for (BpPayment bp : bpPayments) {
            IntegrationJob latestJob = jobService.getLatestJob("BP_PAYMENT", bp.getId(), "BP_PURCHASE_SYNC");
            String itemKey = "PURCHASE:" + bp.getId();
            String ignoreReason = ignoreMap.get(itemKey);

            if (latestJob != null && "SUCCEEDED".equals(latestJob.getStatus()) && latestJob.getExternalId() != null) {
                String dealId = latestJob.getExternalId();
                CanonicalPaymentSync extDeal = extDealMap.get(dealId);

                if (extDeal == null) {
                    // P1-09: 外部取引が削除または未存在の場合は INTERNAL_ONLY (fail-closed)
                    items.add(ReconciliationItemDto.builder()
                            .category("PURCHASE")
                            .internalId(bp.getId())
                            .internalNo("BP-" + bp.getId())
                            .partnerName(bp.getPayeeCompanyName())
                            .internalAmount(bp.getAmount())
                            .externalDealId(dealId)
                            .externalRefNo("BP-" + bp.getId())
                            .externalAmount(BigDecimal.ZERO)
                            .status(ignoreReason != null ? "IGNORED" : "INTERNAL_ONLY")
                            .discrepancyReason("freee仕入取引が削除または未存在 (dealId=" + dealId + ")")
                            .ignoreReason(ignoreReason)
                            .build());
                } else {
                    matchedExternalDealIds.add(dealId);
                    BigDecimal extAmount = extDeal.getAmount();
                    boolean amountMatches = bp.getAmount() != null && extAmount != null && bp.getAmount().compareTo(extAmount) == 0;

                    if (amountMatches) {
                        items.add(ReconciliationItemDto.builder()
                                .category("PURCHASE")
                                .internalId(bp.getId())
                                .internalNo("BP-" + bp.getId())
                                .partnerName(bp.getPayeeCompanyName())
                                .internalAmount(bp.getAmount())
                                .externalDealId(dealId)
                                .externalRefNo("BP-" + bp.getId())
                                .externalAmount(extAmount)
                                .status(ignoreReason != null ? "IGNORED" : "MATCHED")
                                .ignoreReason(ignoreReason)
                                .build());
                    } else {
                        items.add(ReconciliationItemDto.builder()
                                .category("PURCHASE")
                                .internalId(bp.getId())
                                .internalNo("BP-" + bp.getId())
                                .partnerName(bp.getPayeeCompanyName())
                                .internalAmount(bp.getAmount())
                                .externalDealId(dealId)
                                .externalRefNo("BP-" + bp.getId())
                                .externalAmount(extAmount)
                                .status(ignoreReason != null ? "IGNORED" : "AMOUNT_MISMATCH")
                                .discrepancyReason("外部取引金額不一致 (内部: " + bp.getAmount() + ", 外部: " + extAmount + ")")
                                .ignoreReason(ignoreReason)
                                .build());
                    }
                }
            } else {
                items.add(ReconciliationItemDto.builder()
                        .category("PURCHASE")
                        .internalId(bp.getId())
                        .internalNo("BP-" + bp.getId())
                        .partnerName(bp.getPayeeCompanyName())
                        .internalAmount(bp.getAmount())
                        .status(ignoreReason != null ? "IGNORED" : "INTERNAL_ONLY")
                        .discrepancyReason("freee仕入未送信")
                        .ignoreReason(ignoreReason)
                        .build());
            }
        }

        // ============================================================
        // 3. 母集団 3: 経費精算 (t_expense_request)
        // ============================================================
        // R1-P1-06: マネージャーは組織条件を最初のSQLへ適用 (UNKNOWN履歴はfail-closed)
        List<ExpenseRequest> expenses;
        if (isManager) {
            expenses = expenseRequestMapper.selectForReconciliationScoped(startOfMonth, endOfMonth, new java.util.ArrayList<>(allowedOrgIds));
        } else {
            expenses = expenseRequestMapper.selectList(new LambdaQueryWrapper<ExpenseRequest>()
                    .ge(ExpenseRequest::getExpenseDate, startOfMonth)
                    .le(ExpenseRequest::getExpenseDate, endOfMonth));
        }

        for (ExpenseRequest exp : expenses) {
            IntegrationJob latestJob = jobService.getLatestJob("EXPENSE_REQUEST", exp.getId(), "EXPENSE_DEAL_SYNC");
            String itemKey = "EXPENSE:" + exp.getId();
            String ignoreReason = ignoreMap.get(itemKey);

            Engineer eng = exp.getEngineerId() != null ? engineerMapper.selectById(exp.getEngineerId()) : null;
            String engineerName = eng != null ? eng.getFullName() : "要員ID:" + exp.getEngineerId();

            if (latestJob != null && "SUCCEEDED".equals(latestJob.getStatus()) && latestJob.getExternalId() != null) {
                String dealId = latestJob.getExternalId();
                CanonicalPaymentSync extDeal = extDealMap.get(dealId);

                if (extDeal == null) {
                    // P1-09: 外部経費取引が削除または未存在の場合は INTERNAL_ONLY (fail-closed)
                    items.add(ReconciliationItemDto.builder()
                            .category("EXPENSE")
                            .internalId(exp.getId())
                            .internalNo(exp.getExpenseNo() != null ? exp.getExpenseNo() : "EX-" + exp.getId())
                            .partnerName(engineerName)
                            .internalAmount(exp.getAmount())
                            .externalDealId(dealId)
                            .externalRefNo(exp.getExpenseNo())
                            .externalAmount(BigDecimal.ZERO)
                            .status(ignoreReason != null ? "IGNORED" : "INTERNAL_ONLY")
                            .discrepancyReason("freee経費取引が削除または未存在 (dealId=" + dealId + ")")
                            .ignoreReason(ignoreReason)
                            .build());
                } else {
                    matchedExternalDealIds.add(dealId);
                    BigDecimal extAmount = extDeal.getAmount();
                    boolean amountMatches = exp.getAmount() != null && extAmount != null && exp.getAmount().compareTo(extAmount) == 0;

                    if (amountMatches) {
                        items.add(ReconciliationItemDto.builder()
                                .category("EXPENSE")
                                .internalId(exp.getId())
                                .internalNo(exp.getExpenseNo() != null ? exp.getExpenseNo() : "EX-" + exp.getId())
                                .partnerName(engineerName)
                                .internalAmount(exp.getAmount())
                                .externalDealId(dealId)
                                .externalRefNo(exp.getExpenseNo())
                                .externalAmount(extAmount)
                                .status(ignoreReason != null ? "IGNORED" : "MATCHED")
                                .ignoreReason(ignoreReason)
                                .build());
                    } else {
                        items.add(ReconciliationItemDto.builder()
                                .category("EXPENSE")
                                .internalId(exp.getId())
                                .internalNo(exp.getExpenseNo() != null ? exp.getExpenseNo() : "EX-" + exp.getId())
                                .partnerName(engineerName)
                                .internalAmount(exp.getAmount())
                                .externalDealId(dealId)
                                .externalRefNo(exp.getExpenseNo())
                                .externalAmount(extAmount)
                                .status(ignoreReason != null ? "IGNORED" : "AMOUNT_MISMATCH")
                                .discrepancyReason("外部経費取引金額不一致 (内部: " + exp.getAmount() + ", 外部: " + extAmount + ")")
                                .ignoreReason(ignoreReason)
                                .build());
                    }
                }
            } else if ("承認済".equals(exp.getStatus()) || "会計連携済".equals(exp.getStatus())) {
                items.add(ReconciliationItemDto.builder()
                        .category("EXPENSE")
                        .internalId(exp.getId())
                        .internalNo(exp.getExpenseNo() != null ? exp.getExpenseNo() : "EX-" + exp.getId())
                        .partnerName(engineerName)
                        .internalAmount(exp.getAmount())
                        .status(ignoreReason != null ? "IGNORED" : "INTERNAL_ONLY")
                        .discrepancyReason("freee経費取引未送信")
                        .ignoreReason(ignoreReason)
                        .build());
            }
        }

        // ============================================================
        // 4. 母集団 4: 請求書入金消込 (t_invoice_payment / P1-09)
        // ============================================================
        // R1-P1-06: マネージャーは組織条件を最初のSQLへ適用
        List<InvoicePayment> payments;
        if (isManager) {
            payments = invoicePaymentMapper.selectForReconciliationScoped(startOfMonth, endOfMonth, new java.util.ArrayList<>(allowedOrgIds));
        } else {
            payments = invoicePaymentMapper.selectList(new LambdaQueryWrapper<InvoicePayment>()
                    .ge(InvoicePayment::getPaidDate, startOfMonth)
                    .le(InvoicePayment::getPaidDate, endOfMonth));
        }

        Set<String> consumedExternalPaymentKeys = new HashSet<>();

        for (InvoicePayment ip : payments) {
            Invoice inv = invoiceMapper.selectById(ip.getInvoiceId());
            if (isManager && inv != null) {
                Long orgId = organizationResolver.resolveInvoiceOrganizationId(inv);
                if (orgId == null || !allowedOrgIds.contains(orgId)) {
                    continue;
                }
            }

            Customer cust = (inv != null && inv.getCustomerId() != null) ? customerMapper.selectById(inv.getCustomerId()) : null;
            String partnerName = cust != null ? cust.getCompanyName() : "請求ID:" + ip.getInvoiceId();

            BigDecimal internalGross = ip.getAmount() != null ? ip.getAmount() : BigDecimal.ZERO;
            if (ip.getFee() != null) {
                internalGross = internalGross.add(ip.getFee()); // 振込手数料込み総消込金額 (P1-09)
            }

            String itemKey = "PAYMENT:" + ip.getId();
            String ignoreReason = ignoreMap.get(itemKey);

            BigDecimal targetAmount = internalGross;
            LocalDate paidDate = ip.getPaidDate();

            // P1-09: 未消費の外部決済から、金額・日付(非NULL必須)が一致する候補を {dealId}:{paymentId} で 1:1 抽出。
            // 決済日不明 (paymentDate NULL) の外部決済は照合対象にせず fail-closed 側へ倒す。
            List<CanonicalPaymentSync> candidateExtPayments = extPayments.stream()
                    .filter(ext -> ext.getAmount() != null && ext.getAmount().compareTo(targetAmount) == 0
                            && ext.getPaymentDate() != null
                            && (paidDate == null || paidDate.equals(ext.getPaymentDate()))
                            && ext.getDealId() != null && ext.getPaymentId() != null
                            && !consumedExternalPaymentKeys.contains(ext.getDealId() + ":" + ext.getPaymentId()))
                    .toList();

            if (candidateExtPayments.size() == 1) {
                CanonicalPaymentSync extMatch = candidateExtPayments.get(0);
                consumedExternalPaymentKeys.add(extMatch.getDealId() + ":" + extMatch.getPaymentId());
                matchedExternalDealIds.add(extMatch.getDealId());

                items.add(ReconciliationItemDto.builder()
                        .category("PAYMENT")
                        .internalId(ip.getId())
                        .internalNo("PAY-" + ip.getId())
                        .partnerName(partnerName)
                        .internalAmount(internalGross)
                        .externalDealId(extMatch.getDealId())
                        .externalRefNo(extMatch.getReferenceNo() != null ? extMatch.getReferenceNo() : extMatch.getPaymentId())
                        .externalAmount(extMatch.getAmount())
                        .status(ignoreReason != null ? "IGNORED" : "MATCHED")
                        .ignoreReason(ignoreReason)
                        .build());
            } else if (candidateExtPayments.size() > 1) {
                // 同日同額の複数決済で1:1特定不可 -> PAYMENT_AMBIGUOUS (P1-09: fail-closed)
                items.add(ReconciliationItemDto.builder()
                        .category("PAYMENT")
                        .internalId(ip.getId())
                        .internalNo("PAY-" + ip.getId())
                        .partnerName(partnerName)
                        .internalAmount(internalGross)
                        .status(ignoreReason != null ? "IGNORED" : "AMOUNT_MISMATCH")
                        .discrepancyReason("PAYMENT_AMBIGUOUS: 同額の外部決済レコードが複数件存在し、1:1の特定ができません")
                        .ignoreReason(ignoreReason)
                        .build());
            } else {
                items.add(ReconciliationItemDto.builder()
                        .category("PAYMENT")
                        .internalId(ip.getId())
                        .internalNo("PAY-" + ip.getId())
                        .partnerName(partnerName)
                        .internalAmount(internalGross)
                        .status(ignoreReason != null ? "IGNORED" : "INTERNAL_ONLY")
                        .discrepancyReason("外部決済レコードが見つかりません")
                        .ignoreReason(ignoreReason)
                        .build());
            }
        }

        // ============================================================
        // 5. 外部のみ存在する取引 (EXTERNAL_ONLY)  — deal単位で判定
        // ============================================================
        for (CanonicalPaymentSync ext : extDeals) {
            if (ext.getDealId() != null && !matchedExternalDealIds.contains(ext.getDealId())) {
                String extKey = "EXTERNAL:" + ext.getDealId();
                String ignoreReason = ignoreMap.get(extKey);
                items.add(ReconciliationItemDto.builder()
                        .category("EXTERNAL_DEAL")
                        .partnerName(ext.getPartnerName() != null ? ext.getPartnerName() : "外部取引先")
                        .externalDealId(ext.getDealId())
                        .externalRefNo(ext.getReferenceNo())
                        .externalAmount(ext.getAmount())
                        .status(ignoreReason != null ? "IGNORED" : "EXTERNAL_ONLY")
                        .discrepancyReason("外部システムにのみ存在する取引（自動内部登録は行われません）")
                        .ignoreReason(ignoreReason)
                        .build());
            }
        }

        // 集計
        int matched = 0, internalOnly = 0, externalOnly = 0, mismatch = 0, ignored = 0;
        for (ReconciliationItemDto item : items) {
            switch (item.getStatus()) {
                case "MATCHED" -> matched++;
                case "INTERNAL_ONLY" -> internalOnly++;
                case "EXTERNAL_ONLY" -> externalOnly++;
                case "AMOUNT_MISMATCH" -> mismatch++;
                case "IGNORED" -> ignored++;
            }
        }

        // 重大不一致が0件かつ外部取得失敗がない場合にのみ月次締め可能 (P1-09: fail-closed)
        boolean readyForClosing = !externalFetchFailed && (mismatch == 0 && internalOnly == 0 && externalOnly == 0);

        return AccountingReconciliationSummaryDto.builder()
                .month(month)
                .matchedCount(matched)
                .internalOnlyCount(internalOnly)
                .externalOnlyCount(externalOnly)
                .amountMismatchCount(mismatch)
                .ignoredCount(ignored)
                .readyForClosing(readyForClosing)
                .externalFetchFailed(externalFetchFailed)
                .items(items)
                .build();
    }

    @Override
    @Transactional
    public void ignoreDiscrepancy(String month, String category, String externalDealId, Long internalId, String reason, Long userId) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(400, "除外・無視理由を入力してください");
        }

        String itemKey = (internalId != null) ? category + ":" + internalId : "EXTERNAL:" + externalDealId;
        Map<String, String> ignoreMap = loadIgnoreMap(month);
        ignoreMap.put(itemKey, reason);

        saveIgnoreMap(month, ignoreMap);
        log.info("Recorded reconciliation ignore: month={}, key={}, reason={}, userId={}", month, itemKey, reason, userId);
    }

    @Override
    public void assertReconciledForClosing(String month) {
        AccountingReconciliationSummaryDto summary = reconcileMonth(month);
        if (!summary.isReadyForClosing()) {
            throw new BusinessException(400, String.format(
                    "対象月 [%s] の会計・支払照合に未解決の差異があるか、外部APIの取得に失敗しているため、月次締めを実行できません " +
                            "(完全一致: %d, 内部のみ: %d, 外部のみ: %d, 不一致: %d, 外部取得失敗: %b)。" +
                            "照合画面から差異を確認し、連携または理由付き除外設定を行ってください。",
                    month, summary.getMatchedCount(), summary.getInternalOnlyCount(),
                    summary.getExternalOnlyCount(), summary.getAmountMismatchCount(),
                    summary.isExternalFetchFailed()));
        }
    }

    private IntegrationConnection resolveConnection(String tenantId, Long legalEntityId, String provider, String product) {
        return connectionService.getOrCreateConnection(tenantId, legalEntityId, provider, product);
    }

    private Map<String, String> loadIgnoreMap(String month) {
        String key = IGNORE_CONFIG_PREFIX + month;
        SystemConfig config = systemConfigMapper.selectOne(new LambdaQueryWrapper<SystemConfig>()
                .eq(SystemConfig::getConfigKey, key));
        if (config == null || config.getConfigValue() == null || config.getConfigValue().isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(config.getConfigValue(), new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse ignore map for month={}: {}", month, e.getMessage());
            return new HashMap<>();
        }
    }

    private void saveIgnoreMap(String month, Map<String, String> map) {
        String key = IGNORE_CONFIG_PREFIX + month;
        String json;
        try {
            json = objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize ignore map", e);
        }

        SystemConfig existing = systemConfigMapper.selectOne(new LambdaQueryWrapper<SystemConfig>()
                .eq(SystemConfig::getConfigKey, key));
        if (existing != null) {
            existing.setConfigValue(json);
            systemConfigMapper.updateById(existing);
        } else {
            SystemConfig config = new SystemConfig();
            config.setConfigKey(key);
            config.setConfigValue(json);
            config.setDescription("月次照合差異除外リスト: " + month);
            systemConfigMapper.insert(config);
        }
    }
}
