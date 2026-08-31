package com.ses.service.accounting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.entity.IntegrationConnection;
import com.ses.entity.IntegrationJob;
import com.ses.common.util.CorrelationContext;
import com.ses.common.util.LogRedaction;
import com.ses.service.integration.IntegrationConnectionService;
import com.ses.service.integration.IntegrationJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 会計・支払連携ジョブワーカー (P1-01 / design §6.2 scheduler/async)。
 * <p>
 * {@code app.scheduling.enabled=true} 時のみ有効化される。
 * テスト環境では {@code application-test.yml} が {@code app.scheduling.enabled=false} を設定するため
 * スケジューラは動かないが、本コンポーネント自体はテストから明示呼び出し可能。
 * </p>
 * <ul>
 *   <li>5秒間隔で PENDING/RETRYABLE の due job を最大10件 claim・dispatch する。</li>
 *   <li>1分間隔で lease timeout (15分) を超えた stale RUNNING を回収する。
 *       回収前に freee 上の ref_number を照合し、既存なら SUCCEEDED（再POST禁止）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountingIntegrationWorker {

    /** RUNNING state の lease 期限 (分)。この期間を超えると worker crash とみなして回収する。 */
    private static final int RUNNING_LEASE_MINUTES = 15;

    private static final Set<String> DEAL_CREATE_JOB_TYPES = Set.of(
            "SALES_INVOICE_SYNC", "BP_PURCHASE_SYNC", "PURCHASE_DEAL_SYNC", "EXPENSE_DEAL_SYNC");

    private final IntegrationJobService jobService;
    private final IntegrationConnectionService connectionService;
    private final AccountingProviderFactory providerFactory;
    private final SalesInvoiceIntegrationService salesInvoiceIntegrationService;
    private final PurchaseExpensePaymentIntegrationService purchaseIntegrationService;
    private final com.ses.service.DigitalInvoiceService digitalInvoiceService;
    private final ObjectMapper objectMapper;

    /**
     * due job (PENDING/RETRYABLE かつ next_retry_at <= now) を最大10件 claim して dispatch する。
     * fixedDelay=5000ms: 前回の完了から5秒後に次の実行を開始する（同時実行なし）。
     */
    @Scheduled(fixedDelay = 5000)
    @SchedulerLock(name = "accountingProcessDueJobs", lockAtLeastFor = "PT1S", lockAtMostFor = "PT4M")
    public void processDueJobs() {
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
     * 取引作成系は回収前に ref_number で freee を照合し、既存なら SUCCEEDED（再POSTしない）。
     */
    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "accountingRecoverStaleRunning", lockAtLeastFor = "PT1S", lockAtMostFor = "PT2M")
    public void recoverStaleRunning() {
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
        int succeeded = 0;
        int retryable = 0;
        for (IntegrationJob job : stale) {
            CorrelationContext.beginJob(job.getId(), job.getCorrelationId());
            try {
                Optional<String> existingDealId = findExistingDealIfCreateJob(job);
                if (existingDealId.isPresent()) {
                    jobService.markSucceeded(job.getId(), existingDealId.get(), null,
                            "外部取引の存在を照合して復旧しました。");
                    succeeded++;
                } else {
                    jobService.markRetryable(job.getId(), "STALE_LEASE",
                            "error.integration.maxAttemptsExceeded", 0);
                    retryable++;
                }
            } catch (Exception e) {
                log.warn("滞留連携ジョブの復旧照合に失敗: jobId={} category=SYSTEM errorCode=STALE_RECOVERY_ERROR exceptionClass={} detail={}",
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
        log.warn("滞留連携ジョブを復旧: 件数={} lease={}分 成功={} 再試行待ち={}",
                stale.size(), RUNNING_LEASE_MINUTES, succeeded, retryable);
    }

    /** ジョブ種別ごとに適切な process メソッドへ dispatch する (P1-01)。 */
    public void dispatchJob(IntegrationJob job) {
        if (job == null || job.getJobType() == null) return;

        switch (job.getJobType()) {
            case "DIGITAL_INVOICE_SEND" -> digitalInvoiceService.processSendJob(job.getId());
            case "DIGITAL_INVOICE_CREDIT_NOTE" -> digitalInvoiceService.processCreditNoteJob(job.getId());
            case "SALES_INVOICE_SYNC"   -> salesInvoiceIntegrationService.processSalesInvoiceJob(job.getId());
            case "SALES_INVOICE_CANCEL" -> salesInvoiceIntegrationService.processSalesCancelJob(job.getId());
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

    private Optional<String> findExistingDealIfCreateJob(IntegrationJob job) {
        if (job.getJobType() == null || !DEAL_CREATE_JOB_TYPES.contains(job.getJobType())) {
            return Optional.empty();
        }
        String refNumber = resolveRefNumber(job);
        if (refNumber == null || refNumber.isBlank()) {
            return Optional.empty();
        }
        IntegrationConnection conn = connectionService.getById(job.getConnectionId());
        if (conn == null) {
            return Optional.empty();
        }
        AccountingProvider provider = providerFactory.getProvider(conn);
        return provider.findDealIdByRefNumber(conn, refNumber);
    }

    private String resolveRefNumber(IntegrationJob job) {
        if ("BP_PURCHASE_SYNC".equals(job.getJobType()) || "PURCHASE_DEAL_SYNC".equals(job.getJobType())) {
            return job.getTargetId() == null ? null : "BP-" + job.getTargetId();
        }
        String snapshot = job.getPayloadSnapshot();
        if (snapshot == null || snapshot.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(snapshot);
            if (root.hasNonNull("invoiceNo")) {
                return root.get("invoiceNo").asText();
            }
            if (root.hasNonNull("refNumber")) {
                return root.get("refNumber").asText();
            }
            if (root.hasNonNull("expenseNo")) {
                return root.get("expenseNo").asText();
            }
        } catch (Exception e) {
            log.warn("連携ジョブの送信スナップショットを解釈できない: jobId={} category=BUSINESS errorCode=INVALID_PAYLOAD",
                    job.getId());
        }
        return null;
    }
}
