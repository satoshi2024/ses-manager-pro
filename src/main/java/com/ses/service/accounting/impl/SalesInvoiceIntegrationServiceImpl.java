package com.ses.service.accounting.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.accounting.canonical.CanonicalDealResult;
import com.ses.dto.accounting.canonical.CanonicalSalesInvoice;
import com.ses.entity.*;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 売上請求・取消連携サービス実装 (B1 / design §4, §6.1, §6.3)。
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
                        .accountItemCode(salesAccount.getExternalId())
                        .taxCode(taxMapping.getExternalId())
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
        if (conn == null) {
            throw new BusinessException(400, "会計接続設定が存在しません");
        }

        IntegrationJob latestSyncJob = jobService.getLatestJob("INVOICE", invoiceId, "SALES_INVOICE_SYNC");
        if (latestSyncJob == null || !"SUCCEEDED".equals(latestSyncJob.getStatus()) || latestSyncJob.getExternalId() == null) {
            throw new BusinessException(400, "連携済みの外部取引が存在しないため、取消連携は不要です");
        }

        String payload = "{\"externalDealId\":\"" + latestSyncJob.getExternalId() + "\",\"reason\":\"" + cancelReason + "\"}";
        String payloadHash = calculateSha256(payload);
        String idempotencyKey = "SALES_CANCEL:" + invoice.getId() + ":" + latestSyncJob.getExternalId();

        log.info("Enqueueing sales cancel job for invoiceId={}, externalDealId={}", invoiceId, latestSyncJob.getExternalId());
        return jobService.createJob(
                conn.getId(), "SALES_INVOICE_CANCEL", "INVOICE", invoice.getId(), idempotencyKey, payloadHash);
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

    @Override
    public void processSalesInvoiceJob(Long jobId) {
        IntegrationJob job = jobService.claimJob(jobId);
        if (job == null) {
            log.info("Job {} is already claimed or not runnable", jobId);
            return;
        }

        IntegrationConnection conn = connectionService.getById(job.getConnectionId());
        if (conn == null) {
            jobService.markFailed(jobId, "CONNECTION_NOT_FOUND", "接続マスタが見つかりません");
            return;
        }

        Invoice invoice = invoiceService.getById(job.getTargetId());
        if (invoice == null) {
            jobService.markFailed(jobId, "INVOICE_NOT_FOUND", "請求書が見つかりません");
            return;
        }

        try {
            Customer customer = customerService.getById(invoice.getCustomerId());
            String customerCode = "CUST-" + invoice.getCustomerId();
            String customerName = customer != null ? customer.getCompanyName() : "顧客ID:" + invoice.getCustomerId();

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

            AccountingProvider provider = providerFactory.getProvider(conn);
            // DB transaction 外で外部API呼出 (platform-invariants §3.3)
            CanonicalDealResult result = provider.upsertSalesInvoice(conn, canonical);

            if (result.isSuccess()) {
                jobService.markSucceeded(jobId, result.getExternalId(), result.getProviderRequestId(), "freee取引登録成功");
            } else if (result.isRetryable()) {
                int backoff = result.getRetryAfterSeconds() != null ? result.getRetryAfterSeconds() : 30;
                jobService.markRetryable(jobId, result.getErrorCode(), result.getErrorMessageSafe(), backoff);
            } else {
                jobService.markFailed(jobId, result.getErrorCode(), result.getErrorMessageSafe());
            }

        } catch (Exception e) {
            log.error("Failed to process sales invoice job {}", jobId, e);
            jobService.markRetryable(jobId, "SYSTEM_ERROR", e.getMessage(), 30);
        }
    }

    @Override
    public void processSalesCancelJob(Long jobId) {
        IntegrationJob job = jobService.claimJob(jobId);
        if (job == null) {
            return;
        }

        IntegrationConnection conn = connectionService.getById(job.getConnectionId());
        IntegrationJob latestSync = jobService.getLatestJob("INVOICE", job.getTargetId(), "SALES_INVOICE_SYNC");
        if (latestSync == null || latestSync.getExternalId() == null) {
            jobService.markFailed(jobId, "EXTERNAL_DEAL_NOT_FOUND", "取消対象の外部取引IDが見つかりません");
            return;
        }

        try {
            AccountingProvider provider = providerFactory.getProvider(conn);
            CanonicalDealResult result = provider.cancelSalesInvoice(conn, latestSync.getExternalId(), "請求取消");

            if (result.isSuccess()) {
                jobService.markSucceeded(jobId, latestSync.getExternalId(), result.getProviderRequestId(), "freee取引取消成功");
            } else if (result.isRetryable()) {
                int backoff = result.getRetryAfterSeconds() != null ? result.getRetryAfterSeconds() : 30;
                jobService.markRetryable(jobId, result.getErrorCode(), result.getErrorMessageSafe(), backoff);
            } else {
                jobService.markFailed(jobId, result.getErrorCode(), result.getErrorMessageSafe());
            }
        } catch (Exception e) {
            log.error("Failed to process sales cancel job {}", jobId, e);
            jobService.markRetryable(jobId, "SYSTEM_ERROR", e.getMessage(), 30);
        }
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
