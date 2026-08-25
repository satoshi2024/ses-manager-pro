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
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ExpenseRequestMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.BpCompanyService;
import com.ses.service.MonthlyClosingService;
import com.ses.service.accounting.AccountingOrganizationResolver;
import com.ses.service.accounting.AccountingProvider;
import com.ses.service.accounting.AccountingProviderFactory;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.accounting.AccountingTimezoneResolver;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
    private final EngineerMapper engineerMapper;
    private final ExpenseRequestMapper expenseRequestMapper;
    private final WorkRecordMapper workRecordMapper;
    private final MonthlyClosingService monthlyClosingService;
    private final IntegrationConnectionService connectionService;
    private final ExternalMappingService mappingService;
    private final IntegrationJobService jobService;
    private final AccountingProviderFactory providerFactory;
    private final AccountingOrganizationResolver organizationResolver;
    private final AccountingTimezoneResolver timezoneResolver;
    private final ObjectMapper objectMapper;

    private LocalDate resolveBpIssueDate(BpPayment bpPayment) {
        if (bpPayment.getWorkRecordId() != null) {
            WorkRecord wr = workRecordMapper.selectById(bpPayment.getWorkRecordId());
            if (wr != null && wr.getWorkMonth() != null && !wr.getWorkMonth().isBlank()) {
                return java.time.YearMonth.parse(wr.getWorkMonth().trim()).atEndOfMonth();
            }
        }
        throw new BusinessException(400, "BP支払レコードの対象月(workMonth)が未設定です (id=" + bpPayment.getId() + ")");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IntegrationJob triggerBpPurchaseSync(Long bpPaymentId, Long triggeredByUserId) {
        BpPayment bpPayment = bpPaymentMapper.selectById(bpPaymentId);
        if (bpPayment == null) {
            throw new BusinessException(404, "BP支払レコードが見つかりません (id=" + bpPaymentId + ")");
        }

        // P1-08: 金額 NULL 厳格チェック
        if (bpPayment.getAmount() == null || bpPayment.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "BP支払金額は必須かつ正数である必要があります (id=" + bpPaymentId + ")");
        }

        // ステータス適格性チェック
        if (bpPayment.getStatus() != null &&
                !"未払".equals(bpPayment.getStatus()) && !"承認済".equals(bpPayment.getStatus()) &&
                !"CONFIRMED".equalsIgnoreCase(bpPayment.getStatus()) && !"APPROVED".equalsIgnoreCase(bpPayment.getStatus())) {
            throw new BusinessException(400, "未払または承認済のBP支払レコードのみ連携可能です (status=" + bpPayment.getStatus() + ")");
        }

        // 口座変更未承認ガード
        if (bpPayment.getBpCompanyId() != null) {
            Long pendingAccounts = bpBankAccountMapper.selectCount(
                    new LambdaQueryWrapper<com.ses.entity.BpBankAccount>()
                            .eq(com.ses.entity.BpBankAccount::getBpCompanyId, bpPayment.getBpCompanyId())
                            .eq(com.ses.entity.BpBankAccount::getApprovalStatus, "PENDING"));
            if (pendingAccounts != null && pendingAccounts > 0) {
                throw new BusinessException(400, "銀行口座変更が承認待ちのためBP支払連携を実行できません (bpCompanyId=" + bpPayment.getBpCompanyId() + ")");
            }
        }

        // 月次締めチェック (締め済み月への更新拒否)
        if (bpPayment.getWorkRecordId() != null) {
            WorkRecord wr = workRecordMapper.selectById(bpPayment.getWorkRecordId());
            if (wr != null && wr.getWorkMonth() != null && !wr.getWorkMonth().isBlank()) {
                monthlyClosingService.assertOpenForUpdate(wr.getWorkMonth().trim());
            }
        }

        IntegrationConnection conn = resolveConnection("default", null, "freee", "accounting");
        String bpCompanyCode = bpPayment.getBpCompanyId() != null ? "BP-" + bpPayment.getBpCompanyId() : "BP-UNKNOWN";

        // マッピング検証ガード
        mappingService.assertMappingVerified(conn.getId(), "BP_PARTNER", bpCompanyCode);
        mappingService.assertMappingVerified(conn.getId(), "ACCOUNT_PURCHASE", "PURCHASE_DEFAULT");
        mappingService.assertMappingVerified(conn.getId(), "TAX_PURCHASE_10", "TAX_PURCHASE_10");

        ExternalMapping purchaseAccount = mappingService.getMapping(conn.getId(), "ACCOUNT_PURCHASE", "PURCHASE_DEFAULT");
        ExternalMapping taxMapping = mappingService.getMapping(conn.getId(), "TAX_PURCHASE_10", "TAX_PURCHASE_10");

        // P1-08: 業務日付 (issueDate) を対象月 (work_month) 末日に固定 (翌日再試行でもハッシュ不変)
        LocalDate issueDate = resolveBpIssueDate(bpPayment);
        LocalDate dueDate = issueDate.plusMonths(1);

        CanonicalPurchaseDeal canonical = CanonicalPurchaseDeal.builder()
                .bpPaymentId(bpPayment.getId())
                .bpCompanyId(bpPayment.getBpCompanyId())
                .bpCompanyCode(bpCompanyCode)
                .bpCompanyName(bpPayment.getPayeeCompanyName() != null ? bpPayment.getPayeeCompanyName() : "BP会社ID:" + bpPayment.getBpCompanyId())
                .issueDate(issueDate)
                .dueDate(dueDate)
                .amount(bpPayment.getAmount())
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
        Long orgId = organizationResolver.resolveBpPaymentOrganizationId(bpPayment);

        log.info("Enqueueing BP purchase sync job for bpPaymentId={}, idempotencyKey={}, orgId={}", bpPaymentId, idempotencyKey, orgId);
        return jobService.createJob(
                conn.getId(), "BP_PURCHASE_SYNC", "BP_PAYMENT", bpPayment.getId(), idempotencyKey, payloadHash,
                payloadJson, conn.getTenantId(), conn.getLegalEntityId(), orgId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
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
        String payload = "{\"bpPaymentId\":" + bpPaymentId + ",\"externalDealId\":\"" + latestPurchase.getExternalId() + "\",\"expectedAmount\":" + bpPayment.getAmount() + "}";
        String payloadHash = calculateSha256(payload);
        Long orgId = organizationResolver.resolveBpPaymentOrganizationId(bpPayment);

        log.info("Enqueueing payment sync job for bpPaymentId={}, externalDealId={}, orgId={}", bpPaymentId, latestPurchase.getExternalId(), orgId);
        return jobService.createJob(
                conn.getId(), "PAYMENT_SYNC", "BP_PAYMENT", bpPayment.getId(), idempotencyKey, payloadHash,
                payload, conn.getTenantId(), conn.getLegalEntityId(), orgId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IntegrationJob triggerExpenseSync(Long expenseRequestId, Long triggeredByUserId) {
        ExpenseRequest expense = expenseRequestMapper.selectById(expenseRequestId);
        if (expense == null) {
            throw new BusinessException(404, "経費申請レコードが見つかりません (id=" + expenseRequestId + ")");
        }

        // P1-08: 金額・日付 NULL 厳格チェック
        if (expense.getAmount() == null || expense.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "経費金額は必須かつ正数である必要があります (id=" + expenseRequestId + ")");
        }
        if (expense.getExpenseDate() == null) {
            throw new BusinessException(400, "経費発生日は必須です (id=" + expenseRequestId + ")");
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
                .expenseDate(expense.getExpenseDate())
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
        Long orgId = organizationResolver.resolveExpenseOrganizationId(expense);

        log.info("Enqueueing expense sync job for expenseId={}, idempotencyKey={}, orgId={}", expenseRequestId, idempotencyKey, orgId);
        return jobService.createJob(
                conn.getId(), "EXPENSE_DEAL_SYNC", "EXPENSE_REQUEST", expense.getId(), idempotencyKey, payloadHash,
                payloadJson, conn.getTenantId(), conn.getLegalEntityId(), orgId);
    }

    @Override
    public void processBpPurchaseJob(Long jobId) {
        IntegrationJob job = jobService.claimJob(jobId);
        if (job == null) {
            return;
        }

        IntegrationConnection conn = connectionService.getById(job.getConnectionId());
        if (conn == null) {
            jobService.markFailed(jobId, "CONNECTION_NOT_FOUND", "外部連携接続情報が見つかりません");
            return;
        }

        // P1-07: Snapshot 必須・SHA-256 検証
        if (job.getPayloadSnapshot() == null || job.getPayloadSnapshot().isBlank()) {
            jobService.markFailed(jobId, "LEGACY_SNAPSHOT_MISSING", "Snapshotが存在しないジョブは実行できません");
            return;
        }

        String calculatedHash = calculateSha256(job.getPayloadSnapshot());
        if (job.getPayloadHash() != null && !job.getPayloadHash().equals(calculatedHash)) {
            jobService.markFailed(jobId, "PAYLOAD_HASH_MISMATCH", "SnapshotのSHA-256ハッシュが一致しません");
            return;
        }

        AccountingTenantContextHolder.runWithTenant(job.getTenantId(), timezoneResolver.resolve(job.getTenantId()), () -> {
            try {
                CanonicalPurchaseDeal canonical = objectMapper.readValue(job.getPayloadSnapshot(), CanonicalPurchaseDeal.class);

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
            } catch (com.ses.common.exception.TokenRefreshInProgressException e) {
                log.warn("Token refresh in progress during BP purchase job {}: rescheduling retry in 5s", jobId);
                jobService.markRetryable(jobId, "TOKEN_REFRESH_IN_PROGRESS", "他ノードでトークン更新中のため再試行待ち", 5);
            } catch (Exception e) {
                log.error("Error executing BP purchase job: error_code=JOB_EXECUTION_EXCEPTION, jobId={}, jobType=BP_PURCHASE_SYNC", jobId);
                jobService.markRetryable(jobId, "JOB_EXECUTION_EXCEPTION", "BP仕入取引作成中にシステムエラーが発生しました", 60);
            }
        });
    }

    @Override
    public void processPaymentSyncJob(Long jobId) {
        IntegrationJob job = jobService.claimJob(jobId);
        if (job == null) {
            return;
        }

        IntegrationConnection conn = connectionService.getById(job.getConnectionId());
        if (conn == null) {
            jobService.markFailed(jobId, "CONNECTION_NOT_FOUND", "外部連携接続情報が見つかりません");
            return;
        }

        // P1-07: Snapshot 必須・SHA-256 検証
        if (job.getPayloadSnapshot() == null || job.getPayloadSnapshot().isBlank()) {
            jobService.markFailed(jobId, "LEGACY_SNAPSHOT_MISSING", "Snapshotが存在しないジョブは実行できません");
            return;
        }

        String calculatedHash = calculateSha256(job.getPayloadSnapshot());
        if (job.getPayloadHash() != null && !job.getPayloadHash().equals(calculatedHash)) {
            jobService.markFailed(jobId, "PAYLOAD_HASH_MISMATCH", "SnapshotのSHA-256ハッシュが一致しません");
            return;
        }

        AccountingTenantContextHolder.runWithTenant(job.getTenantId(), timezoneResolver.resolve(job.getTenantId()), () -> {
            try {
                com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(job.getPayloadSnapshot());
                String externalDealId = node.has("externalDealId") ? node.get("externalDealId").asText() : null;
                BigDecimal expectedAmount = node.has("expectedAmount") ? new BigDecimal(node.get("expectedAmount").asText()) : null;

                if (externalDealId == null || externalDealId.isBlank()) {
                    jobService.markFailed(jobId, "DEAL_ID_MISSING_IN_SNAPSHOT", "Snapshotに外部取引IDが含まれていません");
                    return;
                }

                AccountingProvider provider = providerFactory.getProvider(conn);
                CanonicalPaymentSync syncResult = provider.fetchDealPayment(conn, externalDealId);

                if (syncResult == null || !syncResult.isSettled()) {
                    jobService.markRetryable(jobId, "PAYMENT_NOT_SETTLED", "外部取引的の決済がまだ完了していません", 300);
                    return;
                }

                // dealId 一致確認 (P1-08)
                if (!externalDealId.equals(syncResult.getDealId())) {
                    jobService.markFailed(jobId, "DEAL_ID_MISMATCH", "外部取引IDが一致しません");
                    return;
                }

                // 金額一致確認 (P1-08: NULL厳格チェック)
                if (expectedAmount == null || syncResult.getAmount() == null
                        || expectedAmount.compareTo(syncResult.getAmount()) != 0) {
                    jobService.markFailed(jobId, "AMOUNT_MISMATCH", "決済金額が内部BP支払金額と一致しません");
                    return;
                }

                // 支払日確認 (P1-08: NULL厳格チェック)
                if (syncResult.getPaymentDate() == null) {
                    jobService.markFailed(jobId, "PAYMENT_DATE_MISSING", "外部決済日が取得できませんでした");
                    return;
                }

                // BP支払ステータス更新 (CAS 条件付き)
                int updated = bpPaymentMapper.update(null, new LambdaUpdateWrapper<BpPayment>()
                        .set(BpPayment::getStatus, "支払済")
                        .set(BpPayment::getPaidDate, syncResult.getPaymentDate())
                        .eq(BpPayment::getId, job.getTargetId())
                        .eq(BpPayment::getStatus, "未払"));

                if (updated == 0) {
                    BpPayment current = bpPaymentMapper.selectById(job.getTargetId());
                    if (current != null && !"支払済".equals(current.getStatus())) {
                        jobService.markFailed(jobId, "CAS_CONFLICT", "BP支払ステータスの更新競合が発生しました");
                        return;
                    }
                }

                jobService.markSucceeded(jobId, syncResult.getDealId(), null,
                        "決済同期完了: dealId=" + syncResult.getDealId() + ", paymentDate=" + syncResult.getPaymentDate());

            } catch (com.ses.common.exception.TokenRefreshInProgressException e) {
                log.warn("Token refresh in progress during payment sync job {}: rescheduling retry in 5s", jobId);
                jobService.markRetryable(jobId, "TOKEN_REFRESH_IN_PROGRESS", "他ノードでトークン更新中のため再試行待ち", 5);
            } catch (Exception e) {
                log.error("Error executing payment sync job: error_code=JOB_EXECUTION_EXCEPTION, jobId={}, jobType=PAYMENT_SYNC", jobId);
                jobService.markRetryable(jobId, "PAYMENT_SYNC_EXCEPTION", "支払実績同期処理中にシステムエラーが発生しました", 60);
            }
        });
    }

    @Override
    public void processExpenseJob(Long jobId) {
        IntegrationJob job = jobService.claimJob(jobId);
        if (job == null) {
            return;
        }

        IntegrationConnection conn = connectionService.getById(job.getConnectionId());
        if (conn == null) {
            jobService.markFailed(jobId, "CONNECTION_NOT_FOUND", "外部連携接続情報が見つかりません");
            return;
        }

        // P1-07: Snapshot 必須・SHA-256 検証
        if (job.getPayloadSnapshot() == null || job.getPayloadSnapshot().isBlank()) {
            jobService.markFailed(jobId, "LEGACY_SNAPSHOT_MISSING", "Snapshotが存在しないジョブは実行できません");
            return;
        }

        String calculatedHash = calculateSha256(job.getPayloadSnapshot());
        if (job.getPayloadHash() != null && !job.getPayloadHash().equals(calculatedHash)) {
            jobService.markFailed(jobId, "PAYLOAD_HASH_MISMATCH", "SnapshotのSHA-256ハッシュが一致しません");
            return;
        }

        AccountingTenantContextHolder.runWithTenant(job.getTenantId(), timezoneResolver.resolve(job.getTenantId()), () -> {
            try {
                CanonicalExpenseDeal canonical = objectMapper.readValue(job.getPayloadSnapshot(), CanonicalExpenseDeal.class);

                AccountingProvider provider = providerFactory.getProvider(conn);
                CanonicalDealResult result = provider.upsertExpenseDeal(conn, canonical);

                if (result.isSuccess()) {
                    // 経費ステータス更新 (CAS: 承認済 -> 会計連携済) (P1-08)
                    int updated = expenseRequestMapper.update(null, new LambdaUpdateWrapper<ExpenseRequest>()
                            .set(ExpenseRequest::getStatus, "会計連携済")
                            .eq(ExpenseRequest::getId, job.getTargetId())
                            .eq(ExpenseRequest::getStatus, "承認済"));

                    if (updated != 1) {
                        jobService.markFailed(jobId, "CAS_CONFLICT",
                                "経費申請ステータスのCAS更新に失敗しました (承認済ではありません)");
                        return;
                    }

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
            } catch (com.ses.common.exception.TokenRefreshInProgressException e) {
                log.warn("Token refresh in progress during expense job {}: rescheduling retry in 5s", jobId);
                jobService.markRetryable(jobId, "TOKEN_REFRESH_IN_PROGRESS", "他ノードでトークン更新中のため再試行待ち", 5);
            } catch (Exception e) {
                log.error("Error executing expense job: error_code=JOB_EXECUTION_EXCEPTION, jobId={}, jobType=EXPENSE_DEAL_SYNC", jobId);
                jobService.markRetryable(jobId, "EXPENSE_JOB_EXCEPTION", "経費取引連携処理中にエラーが発生しました", 60);
            }
        });
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
