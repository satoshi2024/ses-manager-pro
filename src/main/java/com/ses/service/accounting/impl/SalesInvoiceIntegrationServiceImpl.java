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
import com.ses.service.accounting.AccountingOrganizationResolver;
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
    private final AccountingOrganizationResolver organizationResolver;
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
        Long orgId = organizationResolver.resolveInvoiceOrganizationId(invoice);

        log.info("Enqueueing sales sync job for invoiceId={}, idempotencyKey={}, orgId={}", invoiceId, idempotencyKey, orgId);
        return jobService.createJob(
                conn.getId(), "SALES_INVOICE_SYNC", "INVOICE", invoice.getId(), idempotencyKey, payloadHash,
                payloadJson, conn.getTenantId(), conn.getLegalEntityId(), orgId);
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
        Long orgId = organizationResolver.resolveInvoiceOrganizationId(invoice);

        log.info("Enqueueing sales cancel job for invoiceId={}, externalDealId={}, orgId={}", invoiceId, latestSync.getExternalId(), orgId);
        return jobService.createJob(
                conn.getId(), "SALES_INVOICE_CANCEL", "INVOICE", invoice.getId(), idempotencyKey, payloadHash,
                payload, conn.getTenantId(), conn.getLegalEntityId(), orgId);
    }

    @Override
    public void processSalesInvoiceJob(Long jobId) {
        IntegrationJob job = jobService.claimJob(jobId);
        if (job == null) {
            return;
        }

        IntegrationConnection conn = connectionService.getById(job.getConnectionId());
        if (conn == null) {
            jobService.markFailed(jobId, "CONNECTION_NOT_FOUND", "外部連携接続情報が見つかりません");
            return;
        }

        try {
            CanonicalSalesInvoice canonical;
            if (job.getPayloadSnapshot() != null && !job.getPayloadSnapshot().isBlank()) {
                // P1-07: Worker は保存済み payload_snapshot を直接デシリアライズして送信
                canonical = objectMapper.readValue(job.getPayloadSnapshot(), CanonicalSalesInvoice.class);
            } else {
                Invoice invoice = invoiceService.getById(job.getTargetId());
                if (invoice == null) {
                    jobService.markFailed(jobId, "INVOICE_NOT_FOUND", "請求書レコードが見つかりません");
                    return;
                }
                Customer customer = customerService.getById(invoice.getCustomerId());
                String customerCode = "CUST-" + invoice.getCustomerId();
                String customerName = customer != null ? customer.getCompanyName() : "顧客ID:" + invoice.getCustomerId();

                ExternalMapping salesAccount = mappingService.getMapping(conn.getId(), "ACCOUNT_SALES", "SALES_DEFAULT");
                ExternalMapping taxMapping = mappingService.getMapping(conn.getId(), "TAX_SALES_10", "TAX_10");

                canonical = CanonicalSalesInvoice.builder()
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
            }

            AccountingProvider provider = providerFactory.getProvider(conn);
            CanonicalDealResult result = provider.upsertSalesInvoice(conn, canonical);

            handleSalesInvoiceResult(jobId, job, conn, result);

        } catch (com.ses.common.exception.TokenRefreshInProgressException e) {
            log.warn("Token refresh in progress during sales invoice job {}: rescheduling retry in 5s", jobId);
            jobService.markRetryable(jobId, "TOKEN_REFRESH_IN_PROGRESS", "他ノードでトークン更新中のため再試行待ち", 5);
        } catch (Exception e) {
            log.error("Error executing sales invoice job: jobId={}", jobId, e);
            jobService.markRetryable(jobId, "JOB_EXECUTION_EXCEPTION", "売上連携処理中にシステムエラーが発生しました", 60);
        }
    }

    @Transactional
    public void handleSalesInvoiceResult(Long jobId, IntegrationJob job, IntegrationConnection conn, CanonicalDealResult result) {
        IntegrationJob currentJob = jobService.getById(jobId);
        if (currentJob == null) return;

        if (result.isSuccess()) {
            if ("CANCELLED".equals(currentJob.getStatus())) {
                // P1-02: HTTP in-flight 中に取消された場合の検知 (CANCELLED_EXTERNALLY_CREATED) と原子補償ジョブ enqueue
                log.warn("Sales invoice job {} was cancelled in-flight but deal {} was created externally. Enqueueing compensation cancel job.",
                        jobId, result.getExternalId());
                jobService.recordJobEvent(jobId, "CANCELLED_EXTERNALLY_CREATED",
                        "外部取引(dealId=" + result.getExternalId() + ")が作成されましたがジョブは通信中にキャンセルされました", "CANCELLED_EXTERNALLY_CREATED");

                String compIdempotencyKey = "COMPENSATE_CANCEL:" + job.getTargetId() + ":" + result.getExternalId();
                String compPayload = "{\"invoiceId\":" + job.getTargetId() + ",\"externalDealId\":\"" + result.getExternalId() + "\",\"reason\":\"IN_FLIGHT_CANCEL_COMPENSATION\"}";
                String compHash = calculateSha256(compPayload);

                jobService.createJob(
                        conn.getId(), "SALES_INVOICE_CANCEL", "INVOICE", job.getTargetId(), compIdempotencyKey, compHash,
                        compPayload, conn.getTenantId(), conn.getLegalEntityId(), job.getOrganizationId());
            } else {
                jobService.markSucceeded(jobId, result.getExternalId(), result.getProviderRequestId(),
                        "freee取引登録成功: dealId=" + result.getExternalId());
            }
        } else {
            if (result.isRetryable()) {
                jobService.markRetryable(jobId, result.getErrorCode(), result.getErrorMessageSafe(),
                        result.getRetryAfterSeconds());
            } else {
                jobService.markFailed(jobId, result.getErrorCode(), result.getErrorMessageSafe());
            }
        }
    }

    @Override
    public void processSalesCancelJob(Long jobId) {
        IntegrationJob job = jobService.claimJob(jobId);
        if (job == null) {
            return;
        }

        IntegrationConnection conn = connectionService.getById(job.getConnectionId());
        if (conn == null) {
            jobService.markFailed(jobId, "CONNECTION_NOT_FOUND", "外部連携接続情報が見つかりません");
            return;
        }

        try {
            String externalDealId = null;
            String reason = "請求取消";

            // P1-07: 取消 Worker は snapshot から externalDealId と reason を直接読み込み（最新sync job検索の廃止）
            if (job.getPayloadSnapshot() != null && !job.getPayloadSnapshot().isBlank()) {
                com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(job.getPayloadSnapshot());
                if (node.has("externalDealId") && !node.get("externalDealId").isNull()) {
                    externalDealId = node.get("externalDealId").asText();
                }
                if (node.has("reason") && !node.get("reason").isNull()) {
                    reason = node.get("reason").asText();
                }
            }

            if (externalDealId == null || externalDealId.isBlank()) {
                IntegrationJob latestSync = jobService.getLatestJob("INVOICE", job.getTargetId(), "SALES_INVOICE_SYNC");
                if (latestSync != null && latestSync.getExternalId() != null) {
                    externalDealId = latestSync.getExternalId();
                }
            }

            if (externalDealId == null || externalDealId.isBlank()) {
                jobService.markFailed(jobId, "SYNC_JOB_MISSING", "取消対象の外部取引IDが見つかりません");
                return;
            }

            AccountingProvider provider = providerFactory.getProvider(conn);
            CanonicalDealResult result = provider.cancelSalesInvoice(conn, externalDealId, reason);

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
        } catch (com.ses.common.exception.TokenRefreshInProgressException e) {
            log.warn("Token refresh in progress during sales cancel job {}: rescheduling retry in 5s", jobId);
            jobService.markRetryable(jobId, "TOKEN_REFRESH_IN_PROGRESS", "他ノードでトークン更新中のため再試行待ち", 5);
        } catch (Exception e) {
            log.error("Error executing sales cancel job: jobId={}", jobId, e);
            jobService.markRetryable(jobId, "JOB_EXECUTION_EXCEPTION", "売上取引取消処理中にシステムエラーが発生しました", 60);
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
