package com.ses.integration;

import com.ses.common.exception.BusinessException;
import com.ses.entity.IntegrationConnection;
import com.ses.entity.IntegrationJob;
import com.ses.entity.IntegrationJobEvent;
import com.ses.service.integration.IntegrationConnectionService;
import com.ses.service.integration.IntegrationJobService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IntegrationJobStateMachineTest {

    @Autowired
    private IntegrationJobService jobService;

    @Autowired
    private IntegrationConnectionService connectionService;

    private Long connId;

    @BeforeEach
    void setUp() {
        IntegrationConnection conn = connectionService.getOrCreateConnection("default", null, "freee", "accounting");
        connId = conn.getId();
    }

    @AfterEach
    void tearDown() {
        jobService.remove(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>());
        connectionService.remove(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>());
    }

    @Test
    @DisplayName("状態遷移: PENDING -> claim -> RUNNING -> markSucceeded -> SUCCEEDED (終端上書き不可)")
    void stateMachine_pendingToSucceeded() {
        IntegrationJob job = jobService.createJob(connId, "SALES_INVOICE_SYNC", "INVOICE", 1L, "IDEM-1", "hash1");
        assertThat(job.getStatus()).isEqualTo("PENDING");
        assertThat(job.getVersion()).isEqualTo(0);

        // Claim
        IntegrationJob claimed = jobService.claimJob(job.getId());
        assertThat(claimed).isNotNull();
        assertThat(claimed.getStatus()).isEqualTo("RUNNING");
        assertThat(claimed.getVersion()).isEqualTo(1);

        // Success
        jobService.markSucceeded(job.getId(), "EXT-100", "REQ-100", "連携成功");
        IntegrationJob succeeded = jobService.getById(job.getId());
        assertThat(succeeded.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(succeeded.getExternalId()).isEqualTo("EXT-100");
        assertThat(succeeded.getVersion()).isEqualTo(2);

        // 終端状態の上書き拒否: SUCCEEDED に対して markFailed や markRetryable を呼んでも上書きされない
        jobService.markFailed(job.getId(), "ERR", "エラー");
        IntegrationJob stillSucceeded = jobService.getById(job.getId());
        assertThat(stillSucceeded.getStatus()).isEqualTo("SUCCEEDED");

        // イベント履歴の確認
        List<IntegrationJobEvent> events = jobService.listEvents(job.getId());
        assertThat(events).hasSize(3); // PENDING, RUNNING, SUCCEEDED
    }

    @Test
    @DisplayName("状態遷移: RUNNING -> markRetryable (backoff) -> 未来時刻は claim 拒否 -> 手動リトライで PENDING")
    void stateMachine_retryableAndManualRetry() {
        IntegrationJob job = jobService.createJob(connId, "SALES_INVOICE_SYNC", "INVOICE", 2L, "IDEM-2", "hash2");
        IntegrationJob claimed = jobService.claimJob(job.getId());

        // Backoff 300秒で RETRYABLE
        jobService.markRetryable(job.getId(), "RATE_LIMITED", "レート制限", 300);
        IntegrationJob retryable = jobService.getById(job.getId());
        assertThat(retryable.getStatus()).isEqualTo("RETRYABLE");
        assertThat(retryable.getNextRetryAt()).isNotNull();

        // 未来の next_retry_at は claim できない
        IntegrationJob claimFuture = jobService.claimJob(job.getId());
        assertThat(claimFuture).isNull();

        // 手動リセットで PENDING に戻る
        jobService.resetForManualRetry(job.getId());
        IntegrationJob reset = jobService.getById(job.getId());
        assertThat(reset.getStatus()).isEqualTo("PENDING");
        assertThat(reset.getNextRetryAt()).isNull();

        // PENDING に戻れば再度 claim 可能
        IntegrationJob reclaimed = jobService.claimJob(job.getId());
        assertThat(reclaimed).isNotNull();
        assertThat(reclaimed.getStatus()).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("状態ガード: RUNNING や SUCCEEDED に対する手動リトライやキャンセルは例外をスロー")
    void stateMachine_illegalTransitionsRejected() {
        // 仕入ジョブ (BP_PURCHASE_SYNC) は RUNNING 中のキャンセル拒否
        IntegrationJob bpJob = jobService.createJob(connId, "BP_PURCHASE_SYNC", "BP_PAYMENT", 3L, "IDEM-3", "hash3");
        IntegrationJob bpClaimed = jobService.claimJob(bpJob.getId());

        // RUNNING 中のキャンセル拒否
        assertThatThrownBy(() -> jobService.cancelJob(bpJob.getId(), "キャンセル理由"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CANNOT_CANCEL_RUNNING_JOB");

        // RUNNING 中の手動リトライ拒否
        assertThatThrownBy(() -> jobService.resetForManualRetry(bpJob.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("再試行可能なステータス");

        // FAILED に遷移後は手動リトライ可能
        jobService.markFailed(bpJob.getId(), "FATAL_ERROR", "致命的エラー");
        IntegrationJob failed = jobService.getById(bpJob.getId());
        assertThat(failed.getStatus()).isEqualTo("FAILED");

        jobService.resetForManualRetry(bpJob.getId());
        IntegrationJob reset = jobService.getById(bpJob.getId());
        assertThat(reset.getStatus()).isEqualTo("PENDING");

        // PENDING 中のキャンセルは成功
        jobService.cancelJob(bpJob.getId(), "利用中止");
        IntegrationJob cancelled = jobService.getById(bpJob.getId());
        assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("取消マトリクス: FAILEDの取引作成系の送信ジョブは取消せる（内容を直して再送信するため）。それ以外のFAILEDは取消不可")
    void cancelJob_failedCreateJobCanBeCancelled_otherFailedJobsCannot() {
        // freeeの入力エラーで失敗した送信ジョブ（再試行は固定スナップショットのまま同じ失敗になる）
        IntegrationJob bpJob = jobService.createJob(connId, "BP_PURCHASE_SYNC", "BP_PAYMENT", 9101L, "IDEM-FAILED-BP", "hashFb");
        jobService.claimJob(bpJob.getId());
        jobService.markFailed(bpJob.getId(), "VALIDATION_ERROR", "error.accounting.freeeBeforeFiscalYear");
        assertThat(jobService.getById(bpJob.getId()).getStatus()).isEqualTo("FAILED");

        jobService.cancelJob(bpJob.getId(), "内容を直して再送信");
        assertThat(jobService.getById(bpJob.getId()).getStatus()).isEqualTo("CANCELLED");

        // 取引作成系でないジョブ（支払実績同期など）はキーが取引IDで固定のため、FAILEDの取消は認めない
        IntegrationJob paymentJob = jobService.createJob(connId, "PAYMENT_SYNC", "BP_PAYMENT", 9102L, "IDEM-FAILED-PAY", "hashFp");
        jobService.claimJob(paymentJob.getId());
        jobService.markFailed(paymentJob.getId(), "VALIDATION_ERROR", "error.accounting.freeeValidation");
        assertThatThrownBy(() -> jobService.cancelJob(paymentJob.getId(), "取消"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CANNOT_CANCEL_TERMINAL_JOB");
        assertThat(jobService.getById(paymentJob.getId()).getStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("入金消込の反映ジョブ: FAILEDは利用者が取消せる。実行中（RUNNING）はfreeeで消込が成立しても補償できないため取消せない")
    void cancelJob_depositReconcileJob_failedCanBeCancelledButRunningCannot() {
        IntegrationJob failed = jobService.createJob(connId, "DEPOSIT_RECONCILE_SYNC", "BANK_DEPOSIT", 9201L,
                "IDEM-DEPOSIT-FAILED", "hashDf");
        jobService.claimJob(failed.getId());
        jobService.markFailed(failed.getId(), "DEPOSIT_ALREADY_RECONCILED", "error.accounting.freeeDepositAlreadyReconciled");
        jobService.cancelJob(failed.getId(), "freeeで確認済み");
        assertThat(jobService.getById(failed.getId()).getStatus()).isEqualTo("CANCELLED");

        IntegrationJob running = jobService.createJob(connId, "DEPOSIT_RECONCILE_SYNC", "BANK_DEPOSIT", 9202L,
                "IDEM-DEPOSIT-RUNNING", "hashDr");
        jobService.claimJob(running.getId());
        assertThatThrownBy(() -> jobService.cancelJob(running.getId(), "取消"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CANNOT_CANCEL_RUNNING_JOB");
        assertThat(jobService.getById(running.getId()).getStatus()).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("システム取消は読んだ状態・バージョンで照合し、変わっていれば取消さない。要対応の印は成功済みジョブにだけ付く")
    void cancelBySystem_andMarkActionRequired_followTheReadState() {
        IntegrationJob pending = jobService.createJob(connId, "DEPOSIT_RECONCILE_SYNC", "BANK_DEPOSIT", 9301L,
                "IDEM-DEPOSIT-SYSTEM", "hashDs");
        IntegrationJob readBeforeClaim = jobService.getById(pending.getId());
        jobService.claimJob(pending.getId());
        // 読んだ後にWorkerが実行を始めた（PENDING → RUNNING）: 取消さない
        assertThat(jobService.cancelBySystem(readBeforeClaim, "DEPOSIT_RECONCILE_STALE", "error.accounting.depositReconcileStale"))
                .isFalse();
        assertThat(jobService.getById(pending.getId()).getStatus()).isEqualTo("RUNNING");
        // 今の状態で読み直せば取消せる（理由コードは利用者の取消と区別して残る）
        assertThat(jobService.cancelBySystem(jobService.getById(pending.getId()), "DEPOSIT_RECONCILE_STALE",
                "error.accounting.depositReconcileStale")).isTrue();
        IntegrationJob cancelled = jobService.getById(pending.getId());
        assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");
        assertThat(cancelled.getErrorCode()).isEqualTo("DEPOSIT_RECONCILE_STALE");
        assertThat(cancelled.getErrorMessageSafe()).isEqualTo("error.accounting.depositReconcileStale");
        assertThat(jobService.markActionRequired(pending.getId(), "DEPOSIT_RECONCILE_ACTION_REQUIRED",
                "accounting.depositReconcile.releasedAfterSync")).isFalse();

        IntegrationJob succeeded = jobService.createJob(connId, "DEPOSIT_RECONCILE_SYNC", "BANK_DEPOSIT", 9302L,
                "IDEM-DEPOSIT-DONE", "hashDd");
        jobService.claimJob(succeeded.getId());
        jobService.markSucceeded(succeeded.getId(), "668401", "req-done", "連携成功");
        assertThat(jobService.markActionRequired(succeeded.getId(), "DEPOSIT_RECONCILE_ACTION_REQUIRED",
                "accounting.depositReconcile.releasedAfterSync")).isTrue();
        IntegrationJob flagged = jobService.getById(succeeded.getId());
        assertThat(flagged.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(flagged.getErrorCode()).isEqualTo("DEPOSIT_RECONCILE_ACTION_REQUIRED");
        assertThat(jobService.listEvents(succeeded.getId()))
                .extracting(IntegrationJobEvent::getToStatus)
                .contains("ACTION_REQUIRED");
    }
}
