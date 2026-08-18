package com.ses.service.accounting.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.accounting.canonical.CanonicalDealResult;
import com.ses.dto.accounting.canonical.CanonicalExpenseDeal;
import com.ses.dto.accounting.canonical.CanonicalPaymentSync;
import com.ses.dto.accounting.canonical.CanonicalPurchaseDeal;
import com.ses.entity.*;
import com.ses.mapper.BpBankAccountMapper;
import com.ses.mapper.BpPaymentMapper;
import com.ses.mapper.ExpenseRequestMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.BpCompanyService;
import com.ses.service.MonthlyClosingService;
import com.ses.service.accounting.AccountingProvider;
import com.ses.service.accounting.AccountingProviderFactory;
import com.ses.service.accounting.PurchaseExpensePaymentIntegrationService;
import com.ses.service.integration.ExternalMappingService;
import com.ses.service.integration.IntegrationConnectionService;
import com.ses.service.integration.IntegrationJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;

/**
 * BP仕入・経費・支払実績同期連携サービス実装 (B2 / design §4, §6.1, §6.3, G9)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseExpensePaymentIntegrationServiceImpl implements PurchaseExpensePaymentIntegrationService {

    private final BpPaymentMapper bpPaymentMapper;
    private final BpCompanyService bpCompanyService;
    private final BpBankAccountMapper bpBankAccountMapper;
    private final WorkRecordMapper workRecordMapper;
    private final ExpenseRequestMapper expenseRequestMapper;
    private final MonthlyClosingService monthlyClosingService;
    private final IntegrationConnectionService connectionService;
    private final ExternalMappingService mappingService;
    private final IntegrationJobService jobService;
    private final AccountingProviderFactory providerFactory;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public IntegrationJob triggerBpPurchaseSync(Long bpPaymentId, Long triggeredByUserId) {
        BpPayment bpPayment = bpPaymentMapper.selectById(bpPaymentId);
        if (bpPayment == null) {
            throw new BusinessException(404, "BP支払レコードが見つかりません (id=" + bpPaymentId + ")");
        }

        // ステータスガード (未払または承認済のみ)
        if (!"未払".equals(bpPayment.getStatus()) && !"承認済".equals(bpPayment.getStatus())) {
            throw new BusinessException(400, "未払または承認済のBP支払レコードのみ連携可能です (現在: " + bpPayment.getStatus() + ")");
        }

        // 月次締めチェック
        if (bpPayment.getWorkRecordId() != null) {
            WorkRecord wr = workRecordMapper.selectById(bpPayment.getWorkRecordId());
            if (wr != null && wr.getWorkMonth() != null) {
                monthlyClosingService.assertOpenForUpdate(wr.getWorkMonth());
            }
        }

        // 口座変更未承認ガード (R3.4)
        if (bpPayment.getBpCompanyId() != null) {
            List<BpBankAccount> pendingAccounts = bpBankAccountMapper.selectList(new LambdaQueryWrapper<BpBankAccount>()
                    .eq(BpBankAccount::getBpCompanyId, bpPayment.getBpCompanyId())
                    .eq(BpBankAccount::getApprovalStatus, "PENDING"));
            if (!pendingAccounts.isEmpty()) {
                throw new BusinessException(400, "BP会社の銀行口座変更が承認待ちのため、支払連携を実行できません。口座承認を完了させてください。");
            }
        }

        IntegrationConnection conn = resolveConnection("default", null, "freee", "accounting");
        String bpCompanyCode = bpPayment.getBpCompanyId() != null ? "BP-" + bpPayment.getBpCompanyId() : "BP-UNKNOWN";

        // マッピング検証ガード
        mappingService.assertMappingVerified(conn.getId(), "BP_PARTNER", bpCompanyCode);
        mappingService.assertMappingVerified(conn.getId(), "ACCOUNT_PURCHASE", "PURCHASE_DEFAULT");
        mappingService.assertMappingVerified(conn.getId(), "TAX_PURCHASE_10", "TAX_PURCHASE_10");

        ExternalMapping bpPartnerMapping = mappingService.getMapping(conn.getId(), "BP_PARTNER", bpCompanyCode);
        ExternalMapping purchaseAccount = mappingService.getMapping(conn.getId(), "ACCOUNT_PURCHASE", "PURCHASE_DEFAULT");
        ExternalMapping taxMapping = mappingService.getMapping(conn.getId(), "TAX_PURCHASE_10", "TAX_PURCHASE_10");

        LocalDate issueDate = LocalDate.now();
        LocalDate dueDate = issueDate.plusMonths(1);

        CanonicalPurchaseDeal canonical = CanonicalPurchaseDeal.builder()
                .bpPaymentId(bpPayment.getId())
                .bpCompanyId(bpPayment.getBpCompanyId())
                .bpCompanyCode(bpCompanyCode)
                .bpCompanyName(bpPayment.getPayeeCompanyName() != null ? bpPayment.getPayeeCompanyName() : "BP会社ID:" + bpPayment.getBpCompanyId())
                .issueDate(issueDate)
                .dueDate(dueDate)
                .amount(bpPayment.getAmount() != null ? bpPayment.getAmount() : BigDecimal.ZERO)
                .accountItemCode(purchaseAccount.getExternalId())
                .taxCode(taxMapping.getExternalId())
                .remarks(bpPayment.getRemarks() != null ? bpPayment.getRemarks() : "BP外注費: " + bpPayment.getId())
                .build();

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(canonical);
        } catch (Exception e) {
            throw new RuntimeException("Canonical JSON serialization failed", e);
        }

        String payloadHash = calculateSha256(payloadJson);
        String idempotencyKey = "BP_PURCHASE:" + bpPayment.getId();

        log.info("Enqueueing BP purchase sync job for bpPaymentId={}, idempotencyKey={}", bpPaymentId, idempotencyKey);
        return jobService.createJob(
                conn.getId(), "BP_PURCHASE_SYNC", "BP_PAYMENT", bpPayment.getId(), idempotencyKey, payloadHash);
    }

    @Override
    @Transactional
    public IntegrationJob triggerPaymentSync(Long bpPaymentId, Long triggeredByUserId) {
        BpPayment bpPayment = bpPaymentMapper.selectById(bpPaymentId);
        if (bpPayment == null) {
            throw new BusinessException(404, "BP支払レコードが見つかりません (id=" + bpPaymentId + ")");
        }

        IntegrationConnection conn = resolveConnection("default", null, "freee", "accounting");
        IntegrationJob latestPurchase = jobService.getLatestJob("BP_PAYMENT", bpPaymentId, "BP_PURCHASE_SYNC");
        if (latestPurchase == null || !"SUCCEEDED".equals(latestPurchase.getStatus()) || latestPurchase.getExternalId() == null) {
            throw new BusinessException(400, "連携済みの外部取引が存在しないため、支払実績同期を実行できません");
        }

        String idempotencyKey = "PAYMENT_SYNC:" + bpPayment.getId() + ":" + latestPurchase.getExternalId();
        String payload = "{\"bpPaymentId\":" + bpPaymentId + ",\"externalDealId\":\"" + latestPurchase.getExternalId() + "\"}";
        String payloadHash = calculateSha256(payload);

        log.info("Enqueueing payment sync job for bpPaymentId={}, externalDealId={}", bpPaymentId, latestPurchase.getExternalId());
        return jobService.createJob(
                conn.getId(), "PAYMENT_SYNC", "BP_PAYMENT", bpPayment.getId(), idempotencyKey, payloadHash);
    }

    @Override
    @Transactional
    public IntegrationJob triggerExpenseSync(Long expenseRequestId, Long triggeredByUserId) {
        ExpenseRequest expense = expenseRequestMapper.selectById(expenseRequestId);
        if (expense == null) {
            throw new BusinessException(404, "経費申請レコードが見つかりません (id=" + expenseRequestId + ")");
        }

        if (!"承認済".equals(expense.getStatus())) {
            throw new BusinessException(400, "承認済の経費申請のみ会計連携可能です (現在: " + expense.getStatus() + ")");
        }

        IntegrationConnection conn = resolveConnection("default", null, "freee", "accounting");
        String engineerCode = "ENG-" + expense.getEngineerId();

        mappingService.assertMappingVerified(conn.getId(), "ACCOUNT_EXPENSE", "EXPENSE_DEFAULT");
        mappingService.assertMappingVerified(conn.getId(), "TAX_PURCHASE_10", "TAX_PURCHASE_10");

        ExternalMapping expenseAccount = mappingService.getMapping(conn.getId(), "ACCOUNT_EXPENSE", "EXPENSE_DEFAULT");
        ExternalMapping taxMapping = mappingService.getMapping(conn.getId(), "TAX_PURCHASE_10", "TAX_PURCHASE_10");

        CanonicalExpenseDeal canonical = CanonicalExpenseDeal.builder()
                .expenseId(expense.getId())
                .expenseNo(expense.getExpenseNo() != null ? expense.getExpenseNo() : "EX-" + expense.getId())
                .engineerId(expense.getEngineerId())
                .engineerCode(engineerCode)
                .category(expense.getCategory())
                .amount(expense.getAmount())
                .expenseDate(expense.getExpenseDate() != null ? expense.getExpenseDate() : LocalDate.now())
                .accountItemCode(expenseAccount.getExternalId())
                .taxCode(taxMapping.getExternalId())
                .description(expense.getDescription())
                .build();

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(canonical);
        } catch (Exception e) {
            throw new RuntimeException("Canonical JSON serialization failed", e);
        }

        String payloadHash = calculateSha256(payloadJson);
        String idempotencyKey = "EXPENSE:" + expense.getId() + ":" + canonical.getExpenseNo();

        log.info("Enqueueing expense sync job for expenseId={}, idempotencyKey={}", expenseRequestId, idempotencyKey);
        return jobService.createJob(
                conn.getId(), "EXPENSE_DEAL_SYNC", "EXPENSE_REQUEST", expense.getId(), idempotencyKey, payloadHash);
    }

    @Override
    public void processBpPurchaseJob(Long jobId) {
        IntegrationJob job = jobService.claimJob(jobId);
        if (job == null) {
            return;
        }

        IntegrationConnection conn = connectionService.getById(job.getConnectionId());
        BpPayment bpPayment = bpPaymentMapper.selectById(job.getTargetId());
        if (bpPayment == null) {
            jobService.markFailed(jobId, "BP_PAYMENT_NOT_FOUND", "BP支払レコードが見つかりません");
            return;
        }

        try {
            String bpCompanyCode = bpPayment.getBpCompanyId() != null ? "BP-" + bpPayment.getBpCompanyId() : "BP-UNKNOWN";
            ExternalMapping bpPartnerMapping = mappingService.getMapping(conn.getId(), "BP_PARTNER", bpCompanyCode);
            ExternalMapping purchaseAccount = mappingService.getMapping(conn.getId(), "ACCOUNT_PURCHASE", "PURCHASE_DEFAULT");
            ExternalMapping taxMapping = mappingService.getMapping(conn.getId(), "TAX_PURCHASE_10", "TAX_PURCHASE_10");

            LocalDate issueDate = LocalDate.now();
            LocalDate dueDate = issueDate.plusMonths(1);

            CanonicalPurchaseDeal canonical = CanonicalPurchaseDeal.builder()
                    .bpPaymentId(bpPayment.getId())
                    .bpCompanyId(bpPayment.getBpCompanyId())
                    .bpCompanyCode(bpCompanyCode)
                    .bpCompanyName(bpPayment.getPayeeCompanyName() != null ? bpPayment.getPayeeCompanyName() : "BP会社ID:" + bpPayment.getBpCompanyId())
                    .issueDate(issueDate)
                    .dueDate(dueDate)
                    .amount(bpPayment.getAmount() != null ? bpPayment.getAmount() : BigDecimal.ZERO)
                    .accountItemCode(purchaseAccount != null ? purchaseAccount.getExternalId() : null)
                    .taxCode(taxMapping != null ? taxMapping.getExternalId() : null)
                    .remarks(bpPayment.getRemarks() != null ? bpPayment.getRemarks() : "BP外注費: " + bpPayment.getId())
                    .build();

            // ペイロードハッシュ確認 (P1-08)
            String payloadJson = objectMapper.writeValueAsString(canonical);
            String currentHash = calculateSha256(payloadJson);
            if (job.getPayloadHash() != null && !job.getPayloadHash().equals(currentHash)) {
                jobService.markFailed(jobId, "PAYLOAD_MUTATED", "ジョブ登録後にBP支払データが変更されています");
                return;
            }

            AccountingProvider provider = providerFactory.getProvider(conn);
            CanonicalDealResult result = provider.upsertPurchaseDeal(conn, canonical);

            if (result.isSuccess()) {
                jobService.markSucceeded(jobId, result.getExternalId(), result.getProviderRequestId(),
                        "freee仕入取引作成成功: dealId=" + result.getExternalId());
            } else {
                if (result.isRetryable()) {
                    jobService.markRetryable(jobId, result.getErrorCode(), result.getErrorMessageSafe(),
                            result.getRetryAfterSeconds());
                } else {
                    jobService.markFailed(jobId, result.getErrorCode(), result.getErrorMessageSafe());
                }
            }
        } catch (Exception e) {
            log.error("Error executing BP purchase job: jobId={}", jobId, e);
            jobService.markRetryable(jobId, "JOB_EXECUTION_EXCEPTION", e.getMessage(), 60);
        }
    }

    @Override
    public void processPaymentSyncJob(Long jobId) {
        IntegrationJob job = jobService.claimJob(jobId);
        if (job == null) {
            return;
        }

        IntegrationConnection conn = connectionService.getById(job.getConnectionId());
        BpPayment bpPayment = bpPaymentMapper.selectById(job.getTargetId());
        if (bpPayment == null) {
            jobService.markFailed(jobId, "BP_PAYMENT_NOT_FOUND", "BP支払レコードが見つかりません");
            return;
        }

        try {
            IntegrationJob latestPurchase = jobService.getLatestJob("BP_PAYMENT", bpPayment.getId(), "BP_PURCHASE_SYNC");
            if (latestPurchase == null || latestPurchase.getExternalId() == null) {
                jobService.markFailed(jobId, "PURCHASE_JOB_MISSING", "先行するBP仕入連携ジョブが見つかりません");
                return;
            }

            AccountingProvider provider = providerFactory.getProvider(conn);
            CanonicalPaymentSync syncResult = provider.fetchDealPayment(conn, latestPurchase.getExternalId());

            if (syncResult == null || !syncResult.isSettled()) {
                jobService.markRetryable(jobId, "PAYMENT_NOT_SETTLED", "外部取引の決済がまだ完了していません", 300);
                return;
            }

            // dealId 一致確認 (P1-08)
            if (!latestPurchase.getExternalId().equals(syncResult.getDealId())) {
                jobService.markFailed(jobId, "DEAL_ID_MISMATCH",
                        "外部取引IDが一致しません (期待: " + latestPurchase.getExternalId() + ", 実際: " + syncResult.getDealId() + ")");
                return;
            }

            // 金額一致確認 (P1-08)
            if (bpPayment.getAmount() != null && syncResult.getAmount() != null
                    && bpPayment.getAmount().compareTo(syncResult.getAmount()) != 0) {
                jobService.markFailed(jobId, "AMOUNT_MISMATCH",
                        "決済金額が内部BP支払金額と一致しません (内部: " + bpPayment.getAmount() + ", 外部: " + syncResult.getAmount() + ")");
                return;
            }

            // BP支払ステータス更新 (CAS 条件付き)
            int updated = bpPaymentMapper.update(null, new LambdaUpdateWrapper<BpPayment>()
                    .set(BpPayment::getStatus, "支払済")
                    .set(BpPayment::getPaidDate, syncResult.getPaymentDate() != null ? syncResult.getPaymentDate() : LocalDate.now())
                    .eq(BpPayment::getId, bpPayment.getId())
                    .eq(BpPayment::getStatus, "未払"));

            if (updated == 0 && !"支払済".equals(bpPayment.getStatus())) {
                jobService.markFailed(jobId, "CAS_CONFLICT", "BP支払ステータスの更新競合が発生しました");
                return;
            }

            jobService.markSucceeded(jobId, syncResult.getDealId(), null,
                    "決済同期完了: dealId=" + syncResult.getDealId() + ", paymentDate=" + syncResult.getPaymentDate());

        } catch (Exception e) {
            log.error("Error executing payment sync job: jobId={}", jobId, e);
            jobService.markRetryable(jobId, "PAYMENT_SYNC_EXCEPTION", e.getMessage(), 60);
        }
    }

    @Override
    public void processExpenseJob(Long jobId) {
        IntegrationJob job = jobService.claimJob(jobId);
        if (job == null) {
            return;
        }

        IntegrationConnection conn = connectionService.getById(job.getConnectionId());
        ExpenseRequest expense = expenseRequestMapper.selectById(job.getTargetId());
        if (expense == null) {
            jobService.markFailed(jobId, "EXPENSE_NOT_FOUND", "経費申請レコードが見つかりません");
            return;
        }

        try {
            String engineerCode = "ENG-" + expense.getEngineerId();
            ExternalMapping expenseAccount = mappingService.getMapping(conn.getId(), "ACCOUNT_EXPENSE", "EXPENSE_DEFAULT");
            ExternalMapping taxMapping = mappingService.getMapping(conn.getId(), "TAX_PURCHASE_10", "TAX_PURCHASE_10");

            CanonicalExpenseDeal canonical = CanonicalExpenseDeal.builder()
                    .expenseId(expense.getId())
                    .expenseNo(expense.getExpenseNo() != null ? expense.getExpenseNo() : "EX-" + expense.getId())
                    .engineerId(expense.getEngineerId())
                    .engineerCode(engineerCode)
                    .category(expense.getCategory())
                    .amount(expense.getAmount())
                    .expenseDate(expense.getExpenseDate() != null ? expense.getExpenseDate() : LocalDate.now())
                    .accountItemCode(expenseAccount != null ? expenseAccount.getExternalId() : null)
                    .taxCode(taxMapping != null ? taxMapping.getExternalId() : null)
                    .description(expense.getDescription())
                    .build();

            AccountingProvider provider = providerFactory.getProvider(conn);
            CanonicalDealResult result = provider.upsertExpenseDeal(conn, canonical);

            if (result.isSuccess()) {
                // 経費ステータス更新 (CAS: 承認済 -> 会計連携済)
                expenseRequestMapper.update(null, new LambdaUpdateWrapper<ExpenseRequest>()
                        .set(ExpenseRequest::getStatus, "会計連携済")
                        .eq(ExpenseRequest::getId, expense.getId())
                        .eq(ExpenseRequest::getStatus, "承認済"));

                jobService.markSucceeded(jobId, result.getExternalId(), result.getProviderRequestId(),
                        "freee経費取引作成成功: dealId=" + result.getExternalId());
            } else {
                if (result.isRetryable()) {
                    jobService.markRetryable(jobId, result.getErrorCode(), result.getErrorMessageSafe(),
                            result.getRetryAfterSeconds());
                } else {
                    jobService.markFailed(jobId, result.getErrorCode(), result.getErrorMessageSafe());
                }
            }
        } catch (Exception e) {
            log.error("Error executing expense job: jobId={}", jobId, e);
            jobService.markRetryable(jobId, "EXPENSE_JOB_EXCEPTION", e.getMessage(), 60);
        }
    }

    private IntegrationConnection resolveConnection(String tenantId, Long legalEntityId, String provider, String product) {
        return connectionService.getOrCreateConnection(tenantId, legalEntityId, provider, product);
    }

    private String calculateSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not supported", e);
        }
    }
}
