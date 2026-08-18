package com.ses.integration;

import com.ses.entity.IntegrationConnection;
import com.ses.entity.IntegrationJob;
import com.ses.service.accounting.AccountingIntegrationWorker;
import com.ses.service.accounting.PurchaseExpensePaymentIntegrationService;
import com.ses.service.accounting.SalesInvoiceIntegrationService;
import com.ses.service.integration.IntegrationConnectionService;
import com.ses.service.integration.IntegrationJobService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class AccountingIntegrationWorkerTest {

    @Autowired
    private AccountingIntegrationWorker worker;

    @Autowired
    private IntegrationJobService jobService;

    @Autowired
    private IntegrationConnectionService connectionService;

    @MockBean
    private SalesInvoiceIntegrationService salesService;

    @MockBean
    private PurchaseExpensePaymentIntegrationService purchaseService;

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
    @DisplayName("Worker Dispatch: 5種類のジョブ種別が正しく対応サービスへdispatchされること")
    void worker_dispatchesAllJobTypes() {
        IntegrationJob j1 = jobService.createJob(connId, "SALES_INVOICE_SYNC", "INVOICE", 101L, "KEY-1", "hash1");
        IntegrationJob j2 = jobService.createJob(connId, "SALES_INVOICE_CANCEL", "INVOICE", 102L, "KEY-2", "hash2");
        IntegrationJob j3 = jobService.createJob(connId, "BP_PURCHASE_SYNC", "BP_PAYMENT", 103L, "KEY-3", "hash3");
        IntegrationJob j4 = jobService.createJob(connId, "EXPENSE_DEAL_SYNC", "EXPENSE_REQUEST", 104L, "KEY-4", "hash4");
        IntegrationJob j5 = jobService.createJob(connId, "PAYMENT_SYNC", "BP_PAYMENT", 105L, "KEY-5", "hash5");

        worker.processDueJobs();

        verify(salesService, times(1)).processSalesInvoiceJob(j1.getId());
        verify(salesService, times(1)).processSalesCancelJob(j2.getId());
        verify(purchaseService, times(1)).processBpPurchaseJob(j3.getId());
        verify(purchaseService, times(1)).processExpenseJob(j4.getId());
        verify(purchaseService, times(1)).processPaymentSyncJob(j5.getId());
    }

    @Test
    @DisplayName("Worker Dispatch: 未知のジョブ種別は FAILED / UNKNOWN_JOB_TYPE としてマークされること")
    void worker_handlesUnknownJobType() {
        IntegrationJob j = jobService.createJob(connId, "UNKNOWN_SPECIAL_TYPE", "OTHER", 999L, "KEY-UNK", "hashU");

        worker.dispatchJob(j);

        IntegrationJob updated = jobService.getById(j.getId());
        assertThat(updated.getStatus()).isEqualTo("FAILED");
        assertThat(updated.getErrorCode()).isEqualTo("UNKNOWN_JOB_TYPE");
    }

    @Test
    @DisplayName("Stale Running Recovery: 期限切れの RUNNING ジョブが RETRYABLE へ復旧されること")
    void worker_recoversStaleRunningJobs() {
        IntegrationJob j = jobService.createJob(connId, "SALES_INVOICE_SYNC", "INVOICE", 201L, "KEY-STALE", "hashS");
        IntegrationJob claimed = jobService.claimJob(j.getId());
        assertThat(claimed.getStatus()).isEqualTo("RUNNING");

        // 過去時刻に updated_at を設定して stale をシミュレート
        claimed.setUpdatedAt(java.time.LocalDateTime.now().minusMinutes(20));
        jobService.updateById(claimed);

        worker.recoverStaleRunning();

        IntegrationJob recovered = jobService.getById(j.getId());
        assertThat(recovered.getStatus()).isEqualTo("RETRYABLE");
    }
}
