package com.ses.integration;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ses.dto.accounting.IntegrationTokensDto;
import com.ses.entity.IntegrationConnection;
import com.ses.entity.IntegrationJob;
import com.ses.service.accounting.PurchaseExpensePaymentIntegrationService;
import com.ses.service.accounting.SalesInvoiceIntegrationService;
import com.ses.service.integration.IntegrationConnectionService;
import com.ses.service.integration.IntegrationJobService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R1-P1-10: Worker が生例外 (throwable / stack trace / 例外メッセージ) をログへ出力しないことを検証する。
 * <p>
 * SHA-256 が一致する不正 snapshot (機密文字列を含む壊れた JSON) を各 Worker へ渡し、
 * Worker catch が固定 error code のみを出力すること、および job.errorMessageSafe へ
 * 生例外メッセージや機密文字列が保存されないことを全5 Worker で確認する。
 */
@SpringBootTest
@ActiveProfiles("test")
class AccountingWorkerRawExceptionLogTest {

    private static final String SECRET = "secret@example.com";

    @Autowired
    private IntegrationConnectionService connectionService;

    @Autowired
    private IntegrationJobService jobService;

    @Autowired
    private SalesInvoiceIntegrationService salesIntegrationService;

    @Autowired
    private PurchaseExpensePaymentIntegrationService purchaseIntegrationService;

    private final Logger salesLogger = (Logger) LoggerFactory.getLogger(
            com.ses.service.accounting.impl.SalesInvoiceIntegrationServiceImpl.class);
    private final Logger purchaseLogger = (Logger) LoggerFactory.getLogger(
            com.ses.service.accounting.impl.PurchaseExpensePaymentIntegrationServiceImpl.class);
    private final Logger providerLogger = (Logger) LoggerFactory.getLogger(
            com.ses.service.accounting.provider.FreeeAccountingProvider.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private IntegrationConnection connection;

    @BeforeEach
    void setUp() {
        appender.start();
        salesLogger.addAppender(appender);
        purchaseLogger.addAppender(appender);
        providerLogger.addAppender(appender);

        connection = connectionService.getOrCreateConnection("default", null, "freee", "accounting");
        connectionService.saveTokens(connection.getId(), IntegrationTokensDto.builder()
                .accessToken("raw-log-test-token").refreshToken("raw-log-test-refresh").tokenType("Bearer").expiresIn(3600L).build(),
                99001L, "ログ遮断テスト事業所", 1L);
    }

    @AfterEach
    void tearDown() {
        salesLogger.detachAppender(appender);
        purchaseLogger.detachAppender(appender);
        providerLogger.detachAppender(appender);
        appender.stop();
    }

    private IntegrationJob malformedJob(String jobType, String targetType, Long targetId, String key) throws Exception {
        String malformed = "{\"email\":\"" + SECRET + "\",\"invoiceId\":" + targetId + ",}";
        String hash = java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(malformed.getBytes(StandardCharsets.UTF_8)));
        return jobService.createJob(connection.getId(), jobType, targetType, targetId, key, hash, malformed,
                connection.getTenantId(), connection.getLegalEntityId(), null);
    }

    private void assertNoRawExceptionLeak(IntegrationJob job) {
        // job の errorMessageSafe に機密・生例外メッセージが含まれない (error code は Worker 固有の定型コード)
        assertThat(job.getErrorCode()).isIn("JOB_EXECUTION_EXCEPTION", "PAYMENT_SYNC_EXCEPTION", "EXPENSE_JOB_EXCEPTION");
        assertThat(job.getErrorMessageSafe()).doesNotContain(SECRET);

        // 全 capture logger に機密・生メッセージが含まれない (throwable / stack trace 非出力)
        for (ILoggingEvent event : appender.list) {
            String msg = event.getFormattedMessage();
            assertThat(msg).as("logger=%s message=%s", event.getLoggerName(), msg)
                    .doesNotContain(SECRET)
                    .doesNotContain("RawExceptionLogTest")
                    .doesNotContain("at com.ses.");
        }
        appender.list.clear();
    }

    @Test
    @DisplayName("売上 sync Worker: 不正snapshotの例外をログへ出力しない (R1-P1-10)")
    void salesSyncWorker_noRawExceptionLog() throws Exception {
        IntegrationJob job = malformedJob("SALES_INVOICE_SYNC", "INVOICE", 101L, "RAW-LOG-SALES-SYNC");
        salesIntegrationService.processSalesInvoiceJob(job.getId());
        assertNoRawExceptionLeak(jobService.getById(job.getId()));
    }

    @Test
    @DisplayName("売上 cancel Worker: 不正snapshotの例外をログへ出力しない (R1-P1-10)")
    void salesCancelWorker_noRawExceptionLog() throws Exception {
        IntegrationJob job = malformedJob("SALES_INVOICE_CANCEL", "INVOICE", 102L, "RAW-LOG-SALES-CANCEL");
        salesIntegrationService.processSalesCancelJob(job.getId());
        assertNoRawExceptionLeak(jobService.getById(job.getId()));
    }

    @Test
    @DisplayName("BP仕入 Worker: 不正snapshotの例外をログへ出力しない (R1-P1-10)")
    void bpWorker_noRawExceptionLog() throws Exception {
        IntegrationJob job = malformedJob("BP_PURCHASE_SYNC", "BP_PAYMENT", 201L, "RAW-LOG-BP");
        purchaseIntegrationService.processBpPurchaseJob(job.getId());
        assertNoRawExceptionLeak(jobService.getById(job.getId()));
    }

    @Test
    @DisplayName("支払 Worker: 不正snapshotの例外をログへ出力しない (R1-P1-10)")
    void paymentWorker_noRawExceptionLog() throws Exception {
        IntegrationJob job = malformedJob("PAYMENT_SYNC", "BP_PAYMENT", 202L, "RAW-LOG-PAYMENT");
        purchaseIntegrationService.processPaymentSyncJob(job.getId());
        assertNoRawExceptionLeak(jobService.getById(job.getId()));
    }

    @Test
    @DisplayName("経費 Worker: 不正snapshotの例外をログへ出力しない (R1-P1-10)")
    void expenseWorker_noRawExceptionLog() throws Exception {
        IntegrationJob job = malformedJob("EXPENSE_DEAL_SYNC", "EXPENSE_REQUEST", 301L, "RAW-LOG-EXPENSE");
        purchaseIntegrationService.processExpenseJob(job.getId());
        assertNoRawExceptionLeak(jobService.getById(job.getId()));
    }
}
