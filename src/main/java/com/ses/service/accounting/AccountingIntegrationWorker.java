package com.ses.service.accounting;

import com.ses.entity.IntegrationJob;
import com.ses.service.integration.IntegrationJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 会計・支払連携ジョブワーカー (P1-01 / design §6.2 scheduler/async)。
 * <p>
 * {@code app.scheduling.enabled=true} 時のみ有効化される。
 * テスト環境では {@code application-test.yml} が {@code app.scheduling.enabled=false} を設定するため
 * このコンポーネントはロードされない。
 * </p>
 * <ul>
 *   <li>5秒間隔で PENDING/RETRYABLE の due job を最大10件 claim・dispatch する。</li>
 *   <li>1分間隔で lease timeout (15分) を超えた stale RUNNING を RETRYABLE に戻す。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountingIntegrationWorker {

    /** RUNNING state の lease 期限 (分)。この期間を超えると worker crash とみなして回収する。 */
    private static final int RUNNING_LEASE_MINUTES = 15;

    private final IntegrationJobService jobService;
    private final SalesInvoiceIntegrationService salesInvoiceIntegrationService;
    private final PurchaseExpensePaymentIntegrationService purchaseIntegrationService;

    /**
     * due job (PENDING/RETRYABLE かつ next_retry_at <= now) を最大10件 claim して dispatch する。
     * fixedDelay=5000ms: 前回の完了から5秒後に次の実行を開始する（同時実行なし）。
     */
    @Scheduled(fixedDelay = 5000)
    public void processDueJobs() {
        List<IntegrationJob> dueJobs = jobService.listDueJobs(10);
        if (dueJobs.isEmpty()) {
            return;
        }
        log.debug("Processing {} due accounting integration job(s)", dueJobs.size());
        for (IntegrationJob job : dueJobs) {
            try {
                dispatchJob(job);
            } catch (Exception e) {
                log.error("Unexpected error dispatching jobId={}, jobType={}", job.getId(), job.getJobType(), e);
            }
        }
    }

    /**
     * 15分以上 RUNNING のまま残った stale job を RETRYABLE に戻す（worker crash 回収）。
     */
    @Scheduled(fixedDelay = 60_000)
    public void recoverStaleRunning() {
        int recovered = jobService.recoverStaleRunningJobs(RUNNING_LEASE_MINUTES);
        if (recovered > 0) {
            log.warn("Recovered {} stale RUNNING job(s) (lease > {} minutes)", recovered, RUNNING_LEASE_MINUTES);
        }
    }

    /** ジョブ種別ごとに適切な process メソッドへ dispatch する (P1-01)。 */
    public void dispatchJob(IntegrationJob job) {
        if (job == null || job.getJobType() == null) return;

        switch (job.getJobType()) {
            case "SALES_INVOICE_SYNC"   -> salesInvoiceIntegrationService.processSalesInvoiceJob(job.getId());
            case "SALES_INVOICE_CANCEL" -> salesInvoiceIntegrationService.processSalesCancelJob(job.getId());
            case "BP_PURCHASE_SYNC",
                 "PURCHASE_DEAL_SYNC"   -> purchaseIntegrationService.processBpPurchaseJob(job.getId());
            case "EXPENSE_DEAL_SYNC"    -> purchaseIntegrationService.processExpenseJob(job.getId());
            case "PAYMENT_SYNC"         -> purchaseIntegrationService.processPaymentSyncJob(job.getId());
            default -> {
                log.warn("Unknown job type: {}, marking FAILED (jobId={})", job.getJobType(), job.getId());
                IntegrationJob claimed = jobService.claimJob(job.getId());
                if (claimed != null) {
                    jobService.markFailed(job.getId(), "UNKNOWN_JOB_TYPE",
                            "未知のジョブ種別: " + job.getJobType());
                }
            }
        }
    }
}
