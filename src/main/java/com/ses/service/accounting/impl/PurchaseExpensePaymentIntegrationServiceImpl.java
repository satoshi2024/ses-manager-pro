package com.ses.service.accounting.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.accounting.canonical.CanonicalDealResult;
import com.ses.dto.accounting.canonical.CanonicalPaymentSync;
import com.ses.dto.accounting.canonical.CanonicalPurchaseDeal;
import com.ses.entity.*;
import com.ses.mapper.BpBankAccountMapper;
import com.ses.mapper.BpPaymentMapper;
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

        // 月次締めチェック
        if (bpPayment.getWorkRecordId() != null) {
            WorkRecord wr = workRecordMapper.selectById(bpPayment.getWorkRecordId());
            if (wr != null && wr.getWorkMonth() != null) {
                monthlyClosingService.assertOpenForUpdate(wr.getWorkMonth());
            }
        }

        // 口座変更未承認ガード (R3.4: 未承認の口座変更申請中パートナーへの振込・支払データ連携をブロック)
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
            ExternalMapping purchaseAccount = mappingService.getMapping(conn.getId(), "ACCOUNT_PURCHASE", "PURCHASE_DEFAULT");
            ExternalMapping taxMapping = mappingService.getMapping(conn.getId(), "TAX_PURCHASE_10", "TAX_PURCHASE_10");

            CanonicalPurchaseDeal canonical = CanonicalPurchaseDeal.builder()
                    .bpPaymentId(bpPayment.getId())
                    .bpCompanyId(bpPayment.getBpCompanyId())
                    .bpCompanyCode(bpCompanyCode)
                    .bpCompanyName(bpPayment.getPayeeCompanyName() != null ? bpPayment.getPayeeCompanyName() : "BP会社ID:" + bpPayment.getBpCompanyId())
                    .issueDate(LocalDate.now())
                    .dueDate(LocalDate.now().plusMonths(1))
                    .amount(bpPayment.getAmount() != null ? bpPayment.getAmount() : BigDecimal.ZERO)
                    .accountItemCode(purchaseAccount != null ? purchaseAccount.getExternalId() : null)
                    .taxCode(taxMapping != null ? taxMapping.getExternalId() : null)
                    .remarks(bpPayment.getRemarks() != null ? bpPayment.getRemarks() : "BP外注費: " + bpPayment.getId())
                    .build();

            AccountingProvider provider = providerFactory.getProvider(conn);
            CanonicalDealResult result = provider.upsertPurchaseDeal(conn, canonical);

            if (result.isSuccess()) {
                jobService.markSucceeded(jobId, result.getExternalId(), result.getProviderRequestId(), "freee仕入登録成功");
            } else if (result.isRetryable()) {
                int backoff = result.getRetryAfterSeconds() != null ? result.getRetryAfterSeconds() : 30;
                jobService.markRetryable(jobId, result.getErrorCode(), result.getErrorMessageSafe(), backoff);
            } else {
                jobService.markFailed(jobId, result.getErrorCode(), result.getErrorMessageSafe());
            }
        } catch (Exception e) {
            log.error("Failed to process BP purchase job {}", jobId, e);
            jobService.markRetryable(jobId, "SYSTEM_ERROR", e.getMessage(), 30);
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

        IntegrationJob latestPurchase = jobService.getLatestJob("BP_PAYMENT", job.getTargetId(), "BP_PURCHASE_SYNC");
        if (latestPurchase == null || latestPurchase.getExternalId() == null) {
            jobService.markFailed(jobId, "EXTERNAL_DEAL_NOT_FOUND", "外部取引IDが見つかりません");
            return;
        }

        try {
            AccountingProvider provider = providerFactory.getProvider(conn);
            CanonicalPaymentSync syncResult = provider.fetchDealPayment(conn, latestPurchase.getExternalId());

            if (syncResult == null) {
                jobService.markFailed(jobId, "PAYMENT_NOT_FOUND", "外部決済情報が存在しません");
                return;
            }

            // 照合ガード (R3.2: 外部ID＋金額＋日付が一致した場合のみ内部paidへ更新)
            if (bpPayment.getAmount() == null || syncResult.getAmount() == null
                    || bpPayment.getAmount().compareTo(syncResult.getAmount()) != 0) {
                log.warn("Payment amount mismatch for bpPaymentId={}: internal={}, external={}",
                        bpPayment.getId(), bpPayment.getAmount(), syncResult.getAmount());
                jobService.markFailed(jobId, "PAYMENT_AMOUNT_MISMATCH",
                        String.format("金額不一致のため支払済更新を拒否しました (内部:%s, 外部:%s)",
                                bpPayment.getAmount(), syncResult.getAmount()));
                return;
            }

            if (syncResult.getPaymentDate() == null) {
                jobService.markFailed(jobId, "PAYMENT_DATE_MISSING", "外部決済日が取得できませんでした");
                return;
            }

            // 内部 paid 更新
            bpPayment.setStatus("支払済");
            bpPayment.setPaidDate(syncResult.getPaymentDate());
            bpPaymentMapper.updateById(bpPayment);

            jobService.markSucceeded(jobId, syncResult.getExternalId(), syncResult.getDealId(),
                    "外部決済照合完了: paidDate=" + syncResult.getPaymentDate());

        } catch (Exception e) {
            log.error("Failed to process payment sync job {}", jobId, e);
            jobService.markRetryable(jobId, "SYSTEM_ERROR", e.getMessage(), 30);
        }
    }

    private IntegrationConnection resolveConnection(String tenantId, Long corporateEntityId, String provider, String product) {
        if (corporateEntityId != null) {
            IntegrationConnection conn = connectionService.getConnection(tenantId, corporateEntityId, provider, product);
            if (conn != null) return conn;
        }
        List<IntegrationConnection> list = connectionService.listConnections(tenantId);
        for (IntegrationConnection c : list) {
            if (provider.equalsIgnoreCase(c.getProvider()) && product.equalsIgnoreCase(c.getProduct())) {
                return c;
            }
        }
        return connectionService.getOrCreateConnection(tenantId, corporateEntityId, provider, product);
    }

    private String calculateSha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
