package com.ses.service.accounting;

import com.ses.entity.IntegrationJob;
import com.ses.common.audit.ExecutionActorContext;
import com.ses.common.util.CorrelationContext;
import com.ses.common.util.LogRedaction;
import com.ses.service.integration.IntegrationJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 会計・支払連携ジョブワーカー (P1-01 / design §6.2 scheduler/async)。
 * <p>
 * {@code app.scheduling.enabled=true} 時のみ有効化される。
 * テスト環境では {@code application-test.yml} が {@code app.scheduling.enabled=false} を設定するため
 * スケジューラは動かないが、本コンポーネント自体はテストから明示呼び出し可能。
 * </p>
 * <ul>
 *   <li>5秒間隔で PENDING/RETRYABLE の due job を最大10件 claim・dispatch する。</li>
 *   <li>1分間隔で lease timeout (15分) を超えた stale RUNNING を再試行待ちへ戻す。
 *       取引作成系の既存取引の確認は、再試行（試行2回目以降）の事前確認（{@link AccountingProvider.DealCreateGuard}）が
 *       作成時と同じ範囲・同じ規則で行う（ここで別の規則で採用すると、取消後の再送信でも採用してしまう）。</li>
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
    private final com.ses.service.DigitalInvoiceService digitalInvoiceService;
    private final AccountingTimezoneResolver timezoneResolver;

    /**
     * due job (PENDING/RETRYABLE かつ next_retry_at <= now) を最大10件 claim して dispatch する。
     * fixedDelay=5000ms: 前回の完了から5秒後に次の実行を開始する（同時実行なし）。
     */
    @Scheduled(fixedDelay = 5000)
    @SchedulerLock(name = "accountingProcessDueJobs", lockAtLeastFor = "PT1S", lockAtMostFor = "PT4M")
    public void processDueJobs() {
        ExecutionActorContext.runAsSystem("accounting-integration-worker", "SCHEDULER_POLL",
                this::processDueJobsInternal);
    }

    private void processDueJobsInternal() {
        List<IntegrationJob> dueJobs;
        try {
            dueJobs = jobService.listDueJobs(10);
        } catch (Exception e) {
            log.error("連携ジョブ一覧の取得に失敗: category=SYSTEM errorCode=JOB_LIST_ERROR exceptionClass={} detail={}",
                    LogRedaction.exceptionType(e), LogRedaction.safeThrowableSummary(e));
            return;
        }
        if (dueJobs.isEmpty()) {
            return;
        }
        log.debug("処理対象の会計連携ジョブ: 件数={}", dueJobs.size());
        for (IntegrationJob job : dueJobs) {
            String correlationId = CorrelationContext.begin(job.getCorrelationId());
            CorrelationContext.put(CorrelationContext.JOB_ID, job.getId());
            try {
                dispatchJob(job);
            } catch (Exception e) {
                log.error("連携ジョブのディスパッチに失敗: category=SYSTEM error_code=JOB_DISPATCH_ERROR jobId={} jobType={} correlationId={} exceptionClass={} detail={}",
                        job.getId(), job.getJobType(), correlationId, LogRedaction.exceptionType(e),
                        LogRedaction.safeThrowableSummary(e));
            } finally {
                CorrelationContext.clear();
            }
        }
    }

    /**
     * 15分以上 RUNNING のまま残った stale job を回収する。
     * 再試行待ちへ戻すだけで、取引作成系の既存取引の確認は再試行の事前確認に任せる（再POSTで二重に作らない）。
     */
    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "accountingRecoverStaleRunning", lockAtLeastFor = "PT1S", lockAtMostFor = "PT2M")
    public void recoverStaleRunning() {
        ExecutionActorContext.runAsSystem("accounting-integration-worker", "SCHEDULER_POLL",
                this::recoverStaleRunningInternal);
    }

    private void recoverStaleRunningInternal() {
        List<IntegrationJob> stale;
        try {
            stale = jobService.listStaleRunningJobs(RUNNING_LEASE_MINUTES);
        } catch (Exception e) {
            log.error("滞留連携ジョブ一覧の取得に失敗: category=SYSTEM errorCode=STALE_LIST_ERROR exceptionClass={} detail={}",
                    LogRedaction.exceptionType(e), LogRedaction.safeThrowableSummary(e));
            return;
        }
        if (stale.isEmpty()) {
            return;
        }
        int retryable = 0;
        for (IntegrationJob job : stale) {
            CorrelationContext.beginJob(job.getId(), job.getCorrelationId());
            try {
                if (job.getTenantId() == null || job.getTenantId().isBlank() || job.getLegalEntityId() == null) {
                    jobService.markFailed(job.getId(), "SCOPE_MISSING", "error.tenant.contextRequired");
                    continue;
                }
                AccountingTenantContextHolder.runWithTenant(
                        job.getTenantId(), timezoneResolver.resolve(job.getTenantId()), () -> {
                            jobService.markRetryable(job.getId(), "STALE_LEASE",
                                    "error.integration.maxAttemptsExceeded", 0);
                            return true;
                        });
                retryable++;
            } catch (Exception e) {
                log.warn("滞留連携ジョブを再試行待ちへ戻す処理に失敗: jobId={} category=SYSTEM errorCode=STALE_RECOVERY_ERROR exceptionClass={} detail={}",
                        job.getId(), LogRedaction.exceptionType(e), LogRedaction.safeThrowableSummary(e));
                try {
                    jobService.markRetryable(job.getId(), "STALE_LEASE",
                            "error.integration.maxAttemptsExceeded", 0);
                    retryable++;
                } catch (Exception retryException) {
                    log.warn("滞留連携ジョブの再試行待ち遷移に失敗: jobId={} category=SYSTEM errorCode=STALE_RECOVERY_ERROR exceptionClass={} detail={}",
                            job.getId(), LogRedaction.exceptionType(retryException),
                            LogRedaction.safeThrowableSummary(retryException));
                }
            } finally {
                CorrelationContext.clear();
            }
        }
        log.warn("滞留連携ジョブを再試行待ちへ戻しました: 件数={} lease={}分 再試行待ち={}",
                stale.size(), RUNNING_LEASE_MINUTES, retryable);
    }

    /** ジョブ種別ごとに適切な process メソッドへ dispatch する (P1-01)。 */
    public void dispatchJob(IntegrationJob job) {
        if (job == null || job.getTenantId() == null || job.getTenantId().isBlank()
                || job.getLegalEntityId() == null) {
            if (job != null && job.getId() != null) {
                jobService.markFailed(job.getId(), "SCOPE_MISSING", "error.tenant.contextRequired");
            }
            return;
        }
        AccountingTenantContextHolder.runWithTenant(
                job.getTenantId(), timezoneResolver.resolve(job.getTenantId()),
                () -> ExecutionActorContext.runAsSystem(
                        job.getCorrelationId(), "job:" + job.getId(),
                        () -> dispatchJobInternal(job)));
    }

    private void dispatchJobInternal(IntegrationJob job) {
        if (job == null || job.getJobType() == null) return;

        switch (job.getJobType()) {
            case "DIGITAL_INVOICE_SEND" -> digitalInvoiceService.processSendJob(job.getId());
            case "DIGITAL_INVOICE_CREDIT_NOTE" -> digitalInvoiceService.processCreditNoteJob(job.getId());
            case "SALES_INVOICE_SYNC"   -> salesInvoiceIntegrationService.processSalesInvoiceJob(job.getId());
            case "SALES_INVOICE_CANCEL" -> salesInvoiceIntegrationService.processSalesCancelJob(job.getId());
            case "DEPOSIT_RECONCILE_SYNC" -> salesInvoiceIntegrationService.processDepositReconcileJob(job.getId());
            case "BP_PURCHASE_SYNC",
                 "PURCHASE_DEAL_SYNC"   -> purchaseIntegrationService.processBpPurchaseJob(job.getId());
            case "EXPENSE_DEAL_SYNC"    -> purchaseIntegrationService.processExpenseJob(job.getId());
            case "PAYMENT_SYNC"         -> purchaseIntegrationService.processPaymentSyncJob(job.getId());
            default -> {
                log.warn("未知の連携ジョブ種別を失敗扱いに変更: jobType={} jobId={}", job.getJobType(), job.getId());
                IntegrationJob claimed = jobService.claimJob(job.getId());
                if (claimed != null) {
                    jobService.markFailed(job.getId(), "UNKNOWN_JOB_TYPE",
                            "error.system.unexpected");
                }
            }
        }
    }
}
