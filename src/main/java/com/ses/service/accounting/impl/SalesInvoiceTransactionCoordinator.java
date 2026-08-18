package com.ses.service.accounting.impl;

import com.ses.dto.accounting.canonical.CanonicalDealResult;
import com.ses.entity.IntegrationConnection;
import com.ses.entity.IntegrationJob;
import com.ses.service.integration.IntegrationJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 売上請求ジョブの外部呼出結果をDBトランザクション内で原子反映するコーディネーター。
 * in-flight 取消時の CANCELLED_EXTERNALLY_CREATED イベント記録と補償ジョブ作成を同一 Tx で実行し、
 * 補償ジョブ登録例外時には全ロールバックを保証する (R1-P1-02)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SalesInvoiceTransactionCoordinator {

    private final IntegrationJobService jobService;

    @Transactional
    public void handleSalesInvoiceResult(Long jobId, IntegrationJob job, IntegrationConnection conn, CanonicalDealResult result) {
        IntegrationJob currentJob = jobService.getById(jobId);
        if (currentJob == null) return;

        if (result.isSuccess()) {
            if ("CANCELLED".equals(currentJob.getStatus())) {
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

    private String calculateSha256(String input) {
        if (input == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 calculation failed", e);
        }
    }
}
