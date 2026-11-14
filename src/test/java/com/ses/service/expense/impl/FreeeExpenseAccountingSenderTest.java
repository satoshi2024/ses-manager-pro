package com.ses.service.expense.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.ExpenseAccountingJob;
import com.ses.entity.ExpenseRequest;
import com.ses.entity.IntegrationJob;
import com.ses.service.accounting.PurchaseExpensePaymentIntegrationService;
import com.ses.service.expense.ExpenseAccountingSender;
import com.ses.service.integration.IntegrationJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 経費の自動freee送信sender。freeeへの送信は会計・支払連携の送信ジョブ（EXPENSE_DEAL_SYNC）に任せ、
 * 取引の作成を確認できたときだけ成功を返すこと（未送信のまま会計連携済にしない）を固定する。
 */
class FreeeExpenseAccountingSenderTest {

    private IntegrationJobService jobService;
    private PurchaseExpensePaymentIntegrationService integrationService;
    private FreeeExpenseAccountingSender sender;
    private final ExpenseRequest expense = new ExpenseRequest();
    private final ExpenseAccountingJob outboxJob = new ExpenseAccountingJob();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jobService = mock(IntegrationJobService.class);
        integrationService = mock(PurchaseExpensePaymentIntegrationService.class);
        ObjectProvider<PurchaseExpensePaymentIntegrationService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(integrationService);
        sender = new FreeeExpenseAccountingSender(jobService, provider);
        expense.setId(501L);
    }

    @Test
    void noSendJob_createsOneAndWaitsForTheDeal() {
        when(integrationService.triggerExpenseSync(501L, null)).thenReturn(job("PENDING", null));

        ExpenseAccountingSender.SendResult result = sender.send(expense, outboxJob);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ExpenseAccountingSender.PENDING);
        verify(integrationService).triggerExpenseSync(501L, null);
    }

    @Test
    void runningOrRetryableSendJob_isNotDuplicated() {
        for (String status : new String[]{"PENDING", "RUNNING", "RETRYABLE"}) {
            when(jobService.getLatestJob("EXPENSE_REQUEST", 501L, "EXPENSE_DEAL_SYNC")).thenReturn(job(status, null));
            ExpenseAccountingSender.SendResult result = sender.send(expense, outboxJob);
            assertThat(result.errorCode()).as(status).isEqualTo(ExpenseAccountingSender.PENDING);
        }
        verify(integrationService, never()).triggerExpenseSync(anyLong(), any());
    }

    @Test
    void succeededSendJob_returnsTheFreeeDealId_evenWhenSentManually() {
        when(jobService.getLatestJob("EXPENSE_REQUEST", 501L, "EXPENSE_DEAL_SYNC")).thenReturn(job("SUCCEEDED", "9000001"));

        ExpenseAccountingSender.SendResult result = sender.send(expense, outboxJob);

        assertThat(result.success()).isTrue();
        assertThat(result.correlationId()).isEqualTo("9000001");
        verify(integrationService, never()).triggerExpenseSync(anyLong(), any());
    }

    @Test
    void succeededWithoutDealId_isNotTreatedAsSent() {
        when(jobService.getLatestJob("EXPENSE_REQUEST", 501L, "EXPENSE_DEAL_SYNC")).thenReturn(job("SUCCEEDED", " "));

        ExpenseAccountingSender.SendResult result = sender.send(expense, outboxJob);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(FreeeExpenseAccountingSender.JOB_FAILED);
    }

    @Test
    void failedSendJob_isAFailure() {
        when(jobService.getLatestJob("EXPENSE_REQUEST", 501L, "EXPENSE_DEAL_SYNC")).thenReturn(job("FAILED", null));

        ExpenseAccountingSender.SendResult result = sender.send(expense, outboxJob);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(FreeeExpenseAccountingSender.JOB_FAILED);
    }

    @Test
    void cancelledSendJob_isTerminal_andIsNotSentAgain() {
        // 管理者の取消を自動送信が1分後に作り直すと、取消が無効になる（結果不明のジョブなら二重作成になる）
        when(jobService.getLatestJob("EXPENSE_REQUEST", 501L, "EXPENSE_DEAL_SYNC")).thenReturn(job("CANCELLED", null));

        ExpenseAccountingSender.SendResult result = sender.send(expense, outboxJob);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ExpenseAccountingSender.CANCELLED);
        assertThat(ExpenseAccountingSender.CANCELLED.length()).isLessThanOrEqualTo(40);
        verify(integrationService, never()).triggerExpenseSync(anyLong(), any());
    }

    @Test
    void sendRejectedBeforeQueueing_isNotReady() {
        when(integrationService.triggerExpenseSync(501L, null))
                .thenThrow(BusinessException.of(400, "error.accounting.mappingNotVerified"));

        ExpenseAccountingSender.SendResult result = sender.send(expense, outboxJob);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(FreeeExpenseAccountingSender.NOT_READY);
        // last_error_code（VARCHAR(40)）へ入る分類codeであること
        assertThat(result.errorCode().length()).isLessThanOrEqualTo(40);
        assertThat(ExpenseAccountingSender.PENDING.length()).isLessThanOrEqualTo(40);
    }

    private static IntegrationJob job(String status, String externalId) {
        IntegrationJob job = new IntegrationJob();
        job.setId(9000L);
        job.setStatus(status);
        job.setExternalId(externalId);
        return job;
    }
}
