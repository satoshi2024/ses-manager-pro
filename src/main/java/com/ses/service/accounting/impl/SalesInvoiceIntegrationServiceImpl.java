package com.ses.service.accounting.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.accounting.canonical.CanonicalDealResult;
import com.ses.dto.accounting.canonical.CanonicalSalesInvoice;
import com.ses.entity.Customer;
import com.ses.entity.ExternalMapping;
import com.ses.entity.IntegrationConnection;
import com.ses.entity.IntegrationJob;
import com.ses.entity.Invoice;
import com.ses.service.CustomerService;
import com.ses.service.InvoiceService;
import com.ses.service.MonthlyClosingService;
import com.ses.service.accounting.AccountingProvider;
import com.ses.service.accounting.AccountingProviderFactory;
import com.ses.service.accounting.SalesInvoiceIntegrationService;
import com.ses.service.integration.ExternalMappingService;
import com.ses.service.integration.IntegrationConnectionService;
import com.ses.service.integration.IntegrationJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 売上請求書・取引連携サービス実装 (B1 / design §4, §6.1, §6.3)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalesInvoiceIntegrationServiceImpl implements SalesInvoiceIntegrationService {

    private final InvoiceService invoiceService;
    private final CustomerService customerService;
    private final MonthlyClosingService monthlyClosingService;
    private final IntegrationConnectionService connectionService;
    private final ExternalMappingService mappingService;
    private final IntegrationJobService jobService;
    private final AccountingProviderFactory providerFactory;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public IntegrationJob triggerSalesSync(Long invoiceId, Long triggeredByUserId) {
        Invoice invoice = invoiceService.getById(invoiceId);
        if (invoice == null) {
            throw new BusinessException(404, "請求書が見つかりません (id=" + invoiceId + ")");
        }

        // ステータス適格性ガード (送付済 / 入金済 / 一部入金 のみ許可) (P1-07)
        if (!"送付済".equals(invoice.getStatus()) && !"入金済".equals(invoice.getStatus()) && !"一部入金".equals(invoice.getStatus())) {
            throw new BusinessException(400, "送付済または入金済の請求書のみ会計連携可能です (現在: " + invoice.getStatus() + ")");
        }

        // 月次締めチェック (締め済み月への更新拒否)
        if (invoice.getBillingMonth() != null) {
            monthlyClosingService.assertOpenForUpdate(invoice.getBillingMonth());
        }

        IntegrationConnection conn = resolveConnection("default", null, "freee", "accounting");

        Customer customer = customerService.getById(invoice.getCustomerId());
        String customerCode = "CUST-" + invoice.getCustomerId();
        String customerName = customer != null ? customer.getCompanyName() : "顧客ID:" + invoice.getCustomerId();

        // マッピング検証ガード (未登録・未検証は送信不可)
        mappingService.assertMappingVerified(conn.getId(), "CUSTOMER_PARTNER", customerCode);
        mappingService.assertMappingVerified(conn.getId(), "ACCOUNT_SALES", "SALES_DEFAULT");
        mappingService.assertMappingVerified(conn.getId(), "TAX_SALES_10", "TAX_10");

        ExternalMapping partnerMapping = mappingService.getMapping(conn.getId(), "CUSTOMER_PARTNER", customerCode);
        ExternalMapping salesAccount = mappingService.getMapping(conn.getId(), "ACCOUNT_SALES", "SALES_DEFAULT");
        ExternalMapping taxMapping = mappingService.getMapping(conn.getId(), "TAX_SALES_10", "TAX_10");

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
                .details(List.of(CanonicalSalesInvoice.Detail.builder()
                        .description(invoice.getRemarks() != null ? invoice.getRemarks() : "SES請求: " + invoice.getInvoiceNo())
                        .amount(invoice.getTotal())
                        .accountItemCode(salesAccount != null ? salesAccount.getExternalId() : null)
                        .taxCode(taxMapping != null ? taxMapping.getExternalId() : null)
                        .build()))
                .build();

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(canonical);
        } catch (Exception e) {
            throw new RuntimeException("Canonical JSON serialization failed", e);
        }

        String payloadHash = calculateSha256(payloadJson);
        String idempotencyKey = "SALES_INVOICE:" + invoice.getId() + ":" + invoice.getInvoiceNo();

        log.info("Enqueueing sales sync job for invoiceId={}, idempotencyKey={}", invoiceId, idempotencyKey);
        return jobService.createJob(
                conn.getId(), "SALES_INVOICE_SYNC", "INVOICE", invoice.getId(), idempotencyKey, payloadHash);
    }

    @Override
    @Transactional
    public IntegrationJob triggerSalesCancel(Long invoiceId, String cancelReason, Long triggeredByUserId) {
        Invoice invoice = invoiceService.getById(invoiceId);
        if (invoice == null) {
            throw new BusinessException(404, "請求書が見つかりません (id=" + invoiceId + ")");
        }

        if (invoice.getBillingMonth() != null) {
            monthlyClosingService.assertOpenForUpdate(invoice.getBillingMonth());
        }

        IntegrationConnection conn = resolveConnection("default", null, "freee", "accounting");
        IntegrationJob latestSync = jobService.getLatestJob("INVOICE", invoiceId, "SALES_INVOICE_SYNC");
        if (latestSync == null || !"SUCCEEDED".equals(latestSync.getStatus()) || latestSync.getExternalId() == null) {
            throw new BusinessException(400, "連携済みの外部取引が存在しないため、取消処理を実行できません");
        }

        String reason = (cancelReason != null && !cancelReason.isBlank()) ? cancelReason : "請求書取消";
        String idempotencyKey = "SALES_CANCEL:" + invoice.getId() + ":" + latestSync.getExternalId();
        String payload = "{\"invoiceId\":" + invoiceId + ",\"externalDealId\":\"" + latestSync.getExternalId() + "\",\"reason\":\"" + reason + "\"}";
        String payloadHash = calculateSha256(payload);

        log.info("Enqueueing sales cancel job for invoiceId={}, externalDealId={}", invoiceId, latestSync.getExternalId());
        return jobService.createJob(
                conn.getId(), "SALES_INVOICE_CANCEL", "INVOICE", invoice.getId(), idempotencyKey, payloadHash);
    }

    @Override
    public void processSalesInvoiceJob(Long jobId) {
        IntegrationJob job = jobService.claimJob(jobId);
        if (job == null) {
            return;
        }

        IntegrationConnection conn = connectionService.getById(job.getConnectionId());
        Invoice invoice = invoiceService.getById(job.getTargetId());
        if (invoice == null) {
            jobService.markFailed(jobId, "INVOICE_NOT_FOUND", "請求書レコードが見つかりません (id=" + job.getTargetId() + ")");
            return;
        }

        try {
            Customer customer = customerService.getById(invoice.getCustomerId());
            String customerCode = "CUST-" + invoice.getCustomerId();
            String customerName = customer != null ? customer.getCompanyName() : "顧客ID:" + invoice.getCustomerId();

            ExternalMapping partnerMapping = mappingService.getMapping(conn.getId(), "CUSTOMER_PARTNER", customerCode);
            ExternalMapping salesAccount = mappingService.getMapping(conn.getId(), "ACCOUNT_SALES", "SALES_DEFAULT");
            ExternalMapping taxMapping = mappingService.getMapping(conn.getId(), "TAX_SALES_10", "TAX_10");

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
                    .details(List.of(CanonicalSalesInvoice.Detail.builder()
                            .description(invoice.getRemarks() != null ? invoice.getRemarks() : "SES請求: " + invoice.getInvoiceNo())
                            .amount(invoice.getTotal())
                            .accountItemCode(salesAccount != null ? salesAccount.getExternalId() : null)
                            .taxCode(taxMapping != null ? taxMapping.getExternalId() : null)
                            .build()))
                    .build();

            // ペイロードハッシュ確認 (P1-07: immutable snapshot 整合)
            String payloadJson = objectMapper.writeValueAsString(canonical);
            String currentHash = calculateSha256(payloadJson);
            if (job.getPayloadHash() != null && !job.getPayloadHash().equals(currentHash)) {
                jobService.markFailed(jobId, "PAYLOAD_MUTATED",
                        "ジョブ登録後に請求書データが変更されています。手動でジョブを再作成してください。");
                return;
            }

            AccountingProvider provider = providerFactory.getProvider(conn);
            CanonicalDealResult result = provider.upsertSalesInvoice(conn, canonical);

            if (result.isSuccess()) {
                jobService.markSucceeded(jobId, result.getExternalId(), result.getProviderRequestId(),
                        "freee取引登録成功: dealId=" + result.getExternalId());
            } else {
                if (result.isRetryable()) {
                    jobService.markRetryable(jobId, result.getErrorCode(), result.getErrorMessageSafe(),
                            result.getRetryAfterSeconds());
                } else {
                    jobService.markFailed(jobId, result.getErrorCode(), result.getErrorMessageSafe());
                }
            }
        } catch (Exception e) {
            log.error("Error executing sales invoice job: jobId={}", jobId, e);
            jobService.markRetryable(jobId, "JOB_EXECUTION_EXCEPTION", e.getMessage(), 60);
        }
    }

    @Override
    public void processSalesCancelJob(Long jobId) {
        IntegrationJob job = jobService.claimJob(jobId);
        if (job == null) {
            return;
        }

        IntegrationConnection conn = connectionService.getById(job.getConnectionId());
        try {
            IntegrationJob latestSync = jobService.getLatestJob("INVOICE", job.getTargetId(), "SALES_INVOICE_SYNC");
            if (latestSync == null || latestSync.getExternalId() == null) {
                jobService.markFailed(jobId, "SYNC_JOB_MISSING", "先行する売上連携ジョブが見つかりません");
                return;
            }

            AccountingProvider provider = providerFactory.getProvider(conn);
            CanonicalDealResult result = provider.cancelSalesInvoice(conn, latestSync.getExternalId(), "請求取消");

            if (result.isSuccess()) {
                jobService.markSucceeded(jobId, result.getExternalId(), result.getProviderRequestId(),
                        "freee取引取消完了: dealId=" + result.getExternalId());
            } else {
                if (result.isRetryable()) {
                    jobService.markRetryable(jobId, result.getErrorCode(), result.getErrorMessageSafe(),
                            result.getRetryAfterSeconds());
                } else {
                    jobService.markFailed(jobId, result.getErrorCode(), result.getErrorMessageSafe());
                }
            }
        } catch (Exception e) {
            log.error("Error executing sales cancel job: jobId={}", jobId, e);
            jobService.markRetryable(jobId, "CANCEL_JOB_EXCEPTION", e.getMessage(), 60);
        }
    }

    @Override
    public CanonicalSalesInvoice buildCanonicalInvoice(Long invoiceId) {
        Invoice invoice = invoiceService.getById(invoiceId);
        if (invoice == null) {
            throw new BusinessException(404, "請求書が見つかりません (id=" + invoiceId + ")");
        }
        Customer customer = customerService.getById(invoice.getCustomerId());
        String customerCode = "CUST-" + invoice.getCustomerId();
        String customerName = customer != null ? customer.getCompanyName() : "顧客ID:" + invoice.getCustomerId();

        return CanonicalSalesInvoice.builder()
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
                        .build()))
                .build();
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
