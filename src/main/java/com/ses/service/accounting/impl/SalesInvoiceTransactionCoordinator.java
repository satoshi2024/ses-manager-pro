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
    /**
     * 送信成功後に、既に消込済みの入金明細をfreeeへ反映するジョブを登録する（先に消込してから送信した場合）。
     * 売上連携サービスはこのコーディネーターに依存するため、循環しないよう遅延取得する。
     */
    private final org.springframework.beans.factory.ObjectProvider<com.ses.service.accounting.SalesInvoiceIntegrationService>
            salesInvoiceIntegration;

    @Transactional(rollbackFor = Exception.class)
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
                        // NF09: 補償取消は元ジョブと同じ法人で実行する（接続の法人は全社共通ではNULL）
                        compPayload, conn.getTenantId(), job.getLegalEntityId(), job.getOrganizationId());
            } else {
                if (result.isAdoptedExisting()) {
                    // 新しく作成せずfreeeに既にあった取引を採用した（再試行・タイムアウト後の照合）。新規作成と区別して残す
                    jobService.recordJobEvent(jobId, "ADOPTED_EXISTING_DEAL", "accounting.event.adoptedExistingDeal", null);
                }
                jobService.markSucceeded(jobId, result.getExternalId(), result.getProviderRequestId(),
                        "freee取引登録成功: dealId=" + result.getExternalId());
                reflectReconciledDeposits(job);
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

    /**
     * 請求書の取引が作成できたら、その請求書へ既に消込済みの入金明細をfreeeの消込へ反映する（消込が送信より先だと、
     * 消込の時点では反映先の取引が無く登録されないため）。登録は savepoint（NESTED）で行い、失敗しても送信成功の記録は残す。
     */
    private void reflectReconciledDeposits(IntegrationJob job) {
        if (job == null || job.getTargetId() == null || !"SALES_INVOICE_SYNC".equals(job.getJobType())
                || salesInvoiceIntegration == null) {
            return;
        }
        com.ses.service.accounting.SalesInvoiceIntegrationService sync = salesInvoiceIntegration.getIfAvailable();
        if (sync == null) {
            return;
        }
        try {
            sync.enqueueDepositReconcilesForInvoice(job.getTargetId());
        } catch (RuntimeException e) {
            log.warn("送信後の入金消込の反映ジョブを登録できませんでした（送信成功は記録済み）: invoiceId={} error_code=DEPOSIT_RECONCILE_ENQUEUE_FAILED exceptionClass={}",
                    job.getTargetId(), e.getClass().getSimpleName());
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
