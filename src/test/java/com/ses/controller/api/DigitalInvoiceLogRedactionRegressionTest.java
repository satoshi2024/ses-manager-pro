package com.ses.controller.api;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.ses.common.exception.BusinessException;
import com.ses.entity.AuditLog;
import com.ses.entity.Customer;
import com.ses.entity.DigitalInvoice;
import com.ses.entity.Invoice;
import com.ses.entity.PeppolParticipant;
import com.ses.service.CustomerService;
import com.ses.service.DigitalInvoiceService;
import com.ses.service.DocumentService;
import com.ses.service.InvoiceService;
import com.ses.service.PeppolParticipantService;
import com.ses.service.integration.IntegrationJobService;
import com.ses.service.accounting.AccountingIntegrationWorker;
import com.ses.service.invoice.InvoiceDeliveryDispatcher;
import com.ses.service.invoice.JpPintRenderer;
import com.ses.service.invoice.provider.DigitalInvoiceProvider;
import com.ses.service.invoice.provider.DigitalInvoiceProviderResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 電子インボイス機能における機密情報ログ秘匿化（Redaction）回帰テスト。
 * パスワード、Bearer Token、メールアドレス、SQL文、接続文字列が
 * アプリケーションログ、APIレスポンス、DBジョブテーブルへ出力されないことを検証する。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("電子インボイス機密ログ遮断・診断情報記録の回帰テスト")
class DigitalInvoiceLogRedactionRegressionTest {

    private static final String SECRET_DB_PASS = "db password=secretPassword123";
    private static final String SECRET_BEARER = "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.sensitive_payload";
    private static final String SECRET_EMAIL = "victim-invoice-leak@ses-corp.example.com";
    private static final String SECRET_SQL = "SELECT id, card_number, secret_key FROM t_payment_secret WHERE password = 'leak'";
    private static final String SECRET_JDBC = "jdbc:mysql://prod-db.internal:3306/ses_db?user=admin&password=masterSecret";
    private static final String SECRET_BASIC = "dXNlcjpwYXNz";
    private static final String SECRET_REFRESH = "refresh-secret-e2e";
    private static final String SECRET_BINDING = "sql-bind-secret-e2e";

    private static final List<String> ALL_SECRETS = List.of(
            "secretPassword123",
            "sensitive_payload",
            SECRET_EMAIL,
            "SELECT id, card_number",
            "masterSecret",
            "prod-db.internal:3306",
            SECRET_BASIC,
            SECRET_REFRESH,
            SECRET_BINDING,
            "access-secret-e2e"
    );

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private PeppolParticipantService peppolParticipantService;

    @Autowired
    private IntegrationJobService integrationJobService;

    @Autowired
    private com.ses.mapper.IntegrationJobEventMapper integrationJobEventMapper;

    @Autowired
    private com.ses.mapper.IntegrationJobMapper integrationJobMapper;

    @Autowired
    private com.ses.mapper.AuditLogMapper auditLogMapper;

    @Autowired
    private AccountingIntegrationWorker accountingIntegrationWorker;

    @SpyBean
    private DigitalInvoiceService digitalInvoiceService;

    @MockBean
    private InvoiceDeliveryDispatcher deliveryDispatcher;

    @MockBean
    private DigitalInvoiceProvider digitalInvoiceProvider;

    @MockBean
    private DocumentService documentService;

    @MockBean
    private com.ses.mapper.InvoiceItemMapper invoiceItemMapper;

    private ListAppender<ILoggingEvent> appender;
    private Logger apiControllerLogger;
    private Logger inboundControllerLogger;
    private Logger serviceLogger;
    private Logger rendererLogger;
    private Logger webhookLogger;
    private Logger rootLogger;
    private Logger globalHandlerLogger;
    private Logger auditFilterLogger;

    @BeforeEach
    void setUp() {
        appender = new ListAppender<>();
        appender.start();

        apiControllerLogger = (Logger) LoggerFactory.getLogger(DigitalInvoiceApiController.class);
        inboundControllerLogger = (Logger) LoggerFactory.getLogger(InboundDigitalInvoiceApiController.class);
        serviceLogger = (Logger) LoggerFactory.getLogger(com.ses.service.impl.DigitalInvoiceServiceImpl.class);
        rendererLogger = (Logger) LoggerFactory.getLogger(JpPintRenderer.class);
        webhookLogger = (Logger) LoggerFactory.getLogger(DigitalInvoiceWebhookApiController.class);
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        globalHandlerLogger = (Logger) LoggerFactory.getLogger(com.ses.common.exception.GlobalExceptionHandler.class);
        auditFilterLogger = (Logger) LoggerFactory.getLogger(com.ses.config.ApiAuditFilter.class);

        apiControllerLogger.addAppender(appender);
        inboundControllerLogger.addAppender(appender);
        serviceLogger.addAppender(appender);
        rendererLogger.addAppender(appender);
        webhookLogger.addAppender(appender);
        rootLogger.addAppender(appender);
        globalHandlerLogger.addAppender(appender);
        auditFilterLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        apiControllerLogger.detachAppender(appender);
        inboundControllerLogger.detachAppender(appender);
        serviceLogger.detachAppender(appender);
        rendererLogger.detachAppender(appender);
        webhookLogger.detachAppender(appender);
        rootLogger.detachAppender(appender);
        globalHandlerLogger.detachAppender(appender);
        auditFilterLogger.detachAppender(appender);
        appender.stop();
    }

    private String compositeSecretExceptionMessage() {
        return String.join(" | ",
                SECRET_DB_PASS,
                SECRET_BEARER,
                "Authorization: Basic " + SECRET_BASIC,
                "accessToken=access-secret-e2e",
                "{\"refreshToken\":\"" + SECRET_REFRESH + "\"}",
                "email=" + SECRET_EMAIL,
                SECRET_SQL,
                "SQL Parameters: [" + SECRET_BINDING + "]",
                SECRET_JDBC);
    }

    private void assertNoSecretsInLogsAndResponses(String apiResponseContent) {
        if (apiResponseContent != null && !apiResponseContent.isBlank()) {
            for (String secret : ALL_SECRETS) {
                assertThat(apiResponseContent)
                        .as("APIレスポンスに機密が含まれていないこと: %s", secret)
                        .doesNotContain(secret);
            }
        }

        for (ILoggingEvent event : appender.list) {
            String formattedMessage = event.getFormattedMessage();
            for (String secret : ALL_SECRETS) {
                assertThat(formattedMessage)
                        .as("ログ本文に機密が含まれていないこと: %s (logger=%s)", secret, event.getLoggerName())
                        .doesNotContain(secret);
            }

            IThrowableProxy throwableProxy = event.getThrowableProxy();
            if (throwableProxy != null) {
                assertThrowableProxyNoSecrets(throwableProxy);
            }
        }
        assertNoSecretsInPersistedDiagnostics();
    }

    private void assertThrowableProxyNoSecrets(IThrowableProxy proxy) {
        if (proxy == null) return;
        String message = proxy.getMessage();
        if (message != null) {
            for (String secret : ALL_SECRETS) {
                assertThat(message)
                        .as("例外プロキシメッセージに機密が含まれていないこと: %s", secret)
                        .doesNotContain(secret);
            }
        }
        assertThrowableProxyNoSecrets(proxy.getCause());
        if (proxy.getSuppressed() != null) {
            for (IThrowableProxy suppressed : proxy.getSuppressed()) {
                assertThrowableProxyNoSecrets(suppressed);
            }
        }
    }

    private void assertNoSecretsInPersistedDiagnostics() {
        for (com.ses.entity.IntegrationJob job : integrationJobMapper.selectList(null)) {
            assertPersistedValueIsSafe("ジョブのペイロードスナップショット", job.getPayloadSnapshot());
            assertPersistedValueIsSafe("ジョブの外部ID", job.getExternalId());
            assertPersistedValueIsSafe("ジョブのプロバイダリクエストID", job.getProviderRequestId());
            assertPersistedValueIsSafe("ジョブのプロバイダ操作ID", job.getProviderOperationId());
            assertPersistedValueIsSafe("ジョブの安全なエラー文言", job.getErrorMessageSafe());
        }
        for (com.ses.entity.IntegrationJobEvent event : integrationJobEventMapper.selectList(null)) {
            assertPersistedValueIsSafe("ジョブイベントの安全な詳細", event.getSafeDetail());
        }
        for (AuditLog auditLog : auditLogMapper.selectList(null)) {
            assertPersistedValueIsSafe("監査ログURI", auditLog.getUri());
            assertPersistedValueIsSafe("監査ログのエラーコード", auditLog.getErrorCode());
            assertPersistedValueIsSafe("監査ログのエラー分類", auditLog.getErrorCategory());
        }
    }

    private void assertPersistedValueIsSafe(String label, String value) {
        if (value == null) {
            return;
        }
        for (String secret : ALL_SECRETS) {
            assertThat(value).as("%sに機密が含まれていないこと: %s", label, secret).doesNotContain(secret);
        }
    }

    @Test
    @DisplayName("テスト実行報告に注入した精密な機密値を出力しない")
    void テスト実行報告に精密な機密値を出力しない() throws Exception {
        Path reportDirectory = Path.of("target", "surefire-reports");
        if (!Files.isDirectory(reportDirectory)) {
            return;
        }
        try (var paths = Files.walk(reportDirectory)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".xml")
                            || path.getFileName().toString().endsWith(".txt"))
                    .forEach(path -> {
                        try {
                            String report = Files.readString(path, StandardCharsets.UTF_8);
                            for (String secret : ALL_SECRETS) {
                                assertThat(report).as("テスト実行報告に機密がないこと: %s", path)
                                        .doesNotContain(secret);
                            }
                        } catch (java.io.IOException e) {
                            throw new IllegalStateException("テスト実行報告を読み取れません", e);
                        }
                    });
        }
    }

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("送信ディスパッチ例外パス: ログ・レスポンスに機密を出さず診断情報を記録")
    void 送信ディスパッチ例外で機密を隠し診断情報を残す() throws Exception {
        Customer customer = createCustomer("Dispatch Redaction Co");
        Invoice invoice = createInvoice(customer, "INV-DISP-RED-1");

        doThrow(new RuntimeException(compositeSecretExceptionMessage()))
                .when(deliveryDispatcher).dispatch(anyLong(), anyLong(), anyString());

        String correlationId = "corr-dispatch-e2e";
        MvcResult result = mockMvc.perform(post("/api/digital-invoices/dispatch/" + invoice.getId())
                        .header("X-Correlation-ID", correlationId).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-ID", correlationId))
                .andReturn();

        String responseJson = result.getResponse().getContentAsString();
        assertNoSecretsInLogsAndResponses(responseJson);

        // 診断情報の存在検証
        boolean foundDiag = appender.list.stream().anyMatch(e ->
                e.getFormattedMessage().contains("invoiceId=" + invoice.getId())
                        && e.getFormattedMessage().contains("exceptionClass=java.lang.RuntimeException")
                        && e.getFormattedMessage().contains("errorCode=DISPATCH_FAILED"));
        assertThat(foundDiag).as("送信ディスパッチの安全な診断ログが記録されていること").isTrue();

        List<AuditLog> auditLogs = auditLogMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AuditLog>()
                .eq(AuditLog::getUri, "/api/digital-invoices/dispatch/" + invoice.getId()));
        assertThat(auditLogs).isNotEmpty();
        assertThat(auditLogs.get(auditLogs.size() - 1).getCorrelationId()).isEqualTo(correlationId);
        assertThat(auditLogs.get(auditLogs.size() - 1).getInvoiceId()).isEqualTo(String.valueOf(invoice.getId()));
    }

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("インボイス取消例外パス: ログ・レスポンスに機密を出さず診断情報を記録")
    void 取消例外で機密を隠し診断情報を残す() throws Exception {
        Customer customer = createCustomer("Cancel Redaction Co");
        Invoice invoice = createInvoice(customer, "INV-CANCEL-RED-1");

        DigitalInvoice di = new DigitalInvoice();
        di.setInvoiceId(invoice.getId());
        di.setDirection("SEND");
        di.setProfile("Standard");
        di.setSpecificationVersion("1.1.3");
        di.setMessageId("MSG-CANCEL-" + invoice.getId());
        di.setStatus("QUEUED");
        digitalInvoiceService.save(di);

        doThrow(new RuntimeException(compositeSecretExceptionMessage()))
                .when(digitalInvoiceService).cancelInvoice(di.getId());

        MvcResult result = mockMvc.perform(post("/api/digital-invoices/" + di.getId() + "/cancel").with(csrf()))
                .andExpect(status().isOk())
                .andReturn();

        String responseJson = result.getResponse().getContentAsString();
        assertNoSecretsInLogsAndResponses(responseJson);

        boolean foundDiag = appender.list.stream().anyMatch(e ->
                e.getFormattedMessage().contains("digitalInvoiceId=" + di.getId())
                        && e.getFormattedMessage().contains("invoiceId=" + invoice.getId())
                        && e.getFormattedMessage().contains("exceptionClass=java.lang.RuntimeException")
                        && e.getFormattedMessage().contains("errorCode=CANCEL_FAILED"));
        assertThat(foundDiag).as("インボイス取消の安全な診断ログが記録されていること").isTrue();
    }

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("受信ACCEPT例外パス: ログ・レスポンスに機密を出さず診断情報を記録")
    void 受入例外で機密を隠し診断情報を残す() throws Exception {
        doThrow(new RuntimeException(compositeSecretExceptionMessage()))
                .when(digitalInvoiceService).acceptInboundReview(999L);

        MvcResult result = mockMvc.perform(post("/api/inbound-invoices/999/review")
                        .param("action", "ACCEPT")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn();

        String responseJson = result.getResponse().getContentAsString();
        assertNoSecretsInLogsAndResponses(responseJson);

        boolean foundDiag = appender.list.stream().anyMatch(e ->
                e.getFormattedMessage().contains("digitalInvoiceId=999")
                        && e.getFormattedMessage().contains("exceptionClass=java.lang.RuntimeException")
                        && e.getFormattedMessage().contains("errorCode=ACCEPT_FAILED"));
        assertThat(foundDiag).as("受信ACCEPTの安全な診断ログが記録されていること").isTrue();
    }

    @Test
    @DisplayName("送信ジョブ例外パス: プロバイダ例外の機密をログ・DBジョブ状態に残さない")
    void 送信ジョブのシステム例外をログと状態へ残さない() {
        Customer customer = createCustomer("Job Secret Co");
        verifiedParticipant(customer, "part-secret-1");
        Invoice invoice = createInvoice(customer, "INV-JOB-SECRET-1");

        when(digitalInvoiceProvider.sendInvoice(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException(compositeSecretExceptionMessage()));
        when(documentService.registerGenerated(any(), any())).thenAnswer(inv -> {
            com.ses.entity.Document doc = new com.ses.entity.Document();
            doc.setId(8801L);
            return doc;
        });

        DigitalInvoice di = digitalInvoiceService.enqueueInvoiceForSend(invoice.getId(), "1.1.3", customer.getId());
        com.ses.entity.IntegrationJob job = integrationJobService.getLatestJob("t_digital_invoice", di.getId(), "DIGITAL_INVOICE_SEND");
        assertThat(job).isNotNull();

        digitalInvoiceService.processSendJob(job.getId());

        assertNoSecretsInLogsAndResponses(null);

        com.ses.entity.IntegrationJob updatedJob = integrationJobService.getById(job.getId());
        assertThat(updatedJob.getStatus()).isEqualTo("RETRYABLE");
        assertThat(updatedJob.getErrorCode()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(updatedJob.getErrorMessageSafe()).isEqualTo("error.invoice.dispatchFailed");
        assertThat(updatedJob.getCorrelationId()).isNotBlank();
        assertThat(integrationJobEventMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.ses.entity.IntegrationJobEvent>()
                .eq(com.ses.entity.IntegrationJobEvent::getJobId, job.getId())))
                .allMatch(event -> event.getCorrelationId() != null && event.getSafeDetail() != null);
        for (String secret : ALL_SECRETS) {
            assertThat(updatedJob.getErrorMessageSafe()).doesNotContain(secret);
        }

        boolean foundDiag = appender.list.stream().anyMatch(e ->
                e.getFormattedMessage().contains("jobId=" + job.getId())
                        && e.getFormattedMessage().contains("digitalInvoiceId=" + di.getId())
                        && e.getFormattedMessage().contains("exceptionClass=com.ses.service.invoice.provider.DigitalInvoiceProviderException")
                        && e.getFormattedMessage().contains("errorCode=PROVIDER_UNAVAILABLE"));
        assertThat(foundDiag).as("送信ジョブの安全な診断ログが記録されていること").isTrue();
    }

    @Test
    @DisplayName("CreditNoteジョブ例外パス: プロバイダ例外の機密をログ・DBジョブ状態に残さない")
    void 打消しジョブのシステム例外をログと状態へ残さない() {
        Customer customer = createCustomer("CN Secret Co");
        verifiedParticipant(customer, "part-cn-secret");
        Invoice invoice = createInvoice(customer, "INV-CN-SECRET-1");

        when(digitalInvoiceProvider.sendInvoice(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException(compositeSecretExceptionMessage()));
        when(documentService.registerGenerated(any(), any())).thenAnswer(inv -> {
            com.ses.entity.Document doc = new com.ses.entity.Document();
            doc.setId(8802L);
            return doc;
        });

        DigitalInvoice di = digitalInvoiceService.enqueueInvoiceForSend(invoice.getId(), "1.1.3", customer.getId());
        di.setStatus("SENT");
        digitalInvoiceService.updateById(di);

        digitalInvoiceService.cancelInvoice(di.getId());

        DigitalInvoice cn = digitalInvoiceService.lambdaQuery()
                .eq(DigitalInvoice::getInvoiceId, invoice.getId())
                .eq(DigitalInvoice::getProfile, "CreditNote")
                .one();
        assertThat(cn).isNotNull();

        com.ses.entity.IntegrationJob cnJob = integrationJobService.getLatestJob("t_digital_invoice", cn.getId(), "DIGITAL_INVOICE_CREDIT_NOTE");
        assertThat(cnJob).isNotNull();

        digitalInvoiceService.processCreditNoteJob(cnJob.getId());

        assertNoSecretsInLogsAndResponses(null);

        com.ses.entity.IntegrationJob updatedJob = integrationJobService.getById(cnJob.getId());
        assertThat(updatedJob.getStatus()).isEqualTo("RETRYABLE");
        assertThat(updatedJob.getErrorCode()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(updatedJob.getErrorMessageSafe()).isEqualTo("error.invoice.dispatchFailed");
        assertThat(updatedJob.getCorrelationId()).isNotBlank();
        assertThat(integrationJobEventMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.ses.entity.IntegrationJobEvent>()
                .eq(com.ses.entity.IntegrationJobEvent::getJobId, cnJob.getId())))
                .allMatch(event -> event.getCorrelationId() != null && event.getSafeDetail() != null);
        for (String secret : ALL_SECRETS) {
            assertThat(updatedJob.getErrorMessageSafe()).doesNotContain(secret);
        }
    }

    @Test
    @DisplayName("safeJobErrorMessage: BusinessExceptionの原文ではなく固定安全文言を返すこと")
    void safeJobErrorMessageは固定安全文言を返す() {
        BusinessException beWithSecrets = new BusinessException("Validation failure with " + compositeSecretExceptionMessage());
        String safeMessage = com.ses.service.impl.DigitalInvoiceServiceImpl.safeJobErrorMessage(beWithSecrets);

        for (String secret : ALL_SECRETS) {
            assertThat(safeMessage).as("safeJobErrorMessageに機密が含まれないこと: %s", secret).doesNotContain(secret);
        }
        assertThat(safeMessage).isEqualTo("連携処理の結果を記録しました。");
    }

    @Test
    @DisplayName("受信ACCEPT時のXML再読取例外パス: 機密がログへ漏洩せず診断情報が記録されること")
    void 受入時XML再読取例外で機密を隠す() {
        DigitalInvoice di = new DigitalInvoice();
        di.setDirection("RECEIVE");
        di.setProfile("Standard");
        di.setStatus("PENDING_REVIEW");
        di.setSpecificationVersion("1.1.3");
        di.setMessageId("MSG-INBOUND-ACCEPT-1");
        di.setXmlDocumentId(9991L);
        digitalInvoiceService.save(di);

        when(documentService.download(eq(9991L), any()))
                .thenThrow(new RuntimeException(compositeSecretExceptionMessage()));

        org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> digitalInvoiceService.acceptInboundReview(di.getId()));

        assertNoSecretsInLogsAndResponses(null);

        boolean foundDiag = appender.list.stream().anyMatch(e ->
                e.getFormattedMessage().contains("digitalInvoiceId=" + di.getId())
                        && e.getFormattedMessage().contains("xmlDocumentId=9991")
                        && e.getFormattedMessage().contains("exceptionClass=java.lang.RuntimeException"));
        assertThat(foundDiag).as("XML再読取失敗の安全な診断ログが記録されていること").isTrue();
    }

    @Test
    @DisplayName("受信XMLアーカイブ例外パス: 機密がログへ漏洩せず診断情報が記録されること")
    void 受信XMLアーカイブ例外で機密を隠す() {
        when(documentService.registerReceived(any(), any()))
                .thenThrow(new RuntimeException(compositeSecretExceptionMessage()));

        org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class, () ->
                digitalInvoiceService.processInboundInvoice("prov-msg-leak-1", "evt-leak-1",
                        "<Invoice/>", "hash123", LocalDateTime.now()));

        assertNoSecretsInLogsAndResponses(null);

        boolean foundDiag = appender.list.stream().anyMatch(e ->
                e.getFormattedMessage().contains("providerMessageId=prov-msg-leak-1")
                        && e.getFormattedMessage().contains("eventId=evt-leak-1")
                        && e.getFormattedMessage().contains("exceptionClass=java.lang.RuntimeException"));
        assertThat(foundDiag).as("受信XMLアーカイブ失敗の安全な診断ログが記録されていること").isTrue();
    }

    @Test
    @DisplayName("送信XMLアーカイブ例外パス: 機密がログへ漏洩せず診断情報が記録されること")
    void 送信XMLアーカイブ例外で機密を隠す() {
        Customer customer = createCustomer("Archive Secret Co");
        verifiedParticipant(customer, "part-arch-secret");
        Invoice invoice = createInvoice(customer, "INV-ARCH-SECRET-1");

        when(documentService.registerGenerated(any(), any()))
                .thenThrow(new RuntimeException(compositeSecretExceptionMessage()));

        DigitalInvoice di = digitalInvoiceService.enqueueInvoiceForSend(invoice.getId(), "1.1.3", customer.getId());
        com.ses.entity.IntegrationJob job = integrationJobService.getLatestJob("t_digital_invoice", di.getId(), "DIGITAL_INVOICE_SEND");
        assertThat(job).isNotNull();

        digitalInvoiceService.processSendJob(job.getId());

        assertNoSecretsInLogsAndResponses(null);

        boolean foundDiag = appender.list.stream().anyMatch(e ->
                e.getFormattedMessage().contains("digitalInvoiceId=" + di.getId())
                        && e.getFormattedMessage().contains("invoiceId=" + invoice.getId())
                        && e.getFormattedMessage().contains("exceptionClass=java.lang.RuntimeException"));
        assertThat(foundDiag).as("送信XMLアーカイブ失敗の安全な診断ログが記録されていること").isTrue();
    }

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("プレビュー・ステータス・一覧・却下・XML取得の境界で機密を出力しない")
    void 電子請求書API全境界で機密を出力しない() throws Exception {
        Customer customer = createCustomer("API境界検証会社");
        verifiedParticipant(customer, "part-api-boundary");
        Invoice invoice = createInvoice(customer, "INV-API-BOUNDARY-1");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/digital-invoices/preview/" + invoice.getId()))
                .andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/digital-invoices/" + invoice.getId() + "/status-history"))
                .andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/inbound-invoices"))
                .andExpect(status().isOk());

        DigitalInvoice inbound = new DigitalInvoice();
        inbound.setDirection("RECEIVE");
        inbound.setProfile("Standard");
        inbound.setSpecificationVersion("1.1.3");
        inbound.setMessageId("MSG-API-REJECT-1");
        inbound.setStatus("PENDING_REVIEW");
        digitalInvoiceService.save(inbound);
        mockMvc.perform(post("/api/inbound-invoices/" + inbound.getId() + "/review")
                        .param("action", "REJECT").with(csrf()))
                .andExpect(status().isOk());

        DigitalInvoice downloadable = new DigitalInvoice();
        downloadable.setInvoiceId(invoice.getId());
        downloadable.setDirection("SEND");
        downloadable.setProfile("Standard");
        downloadable.setSpecificationVersion("1.1.3");
        downloadable.setMessageId("MSG-API-DOWNLOAD-1");
        downloadable.setStatus("SENT");
        downloadable.setXmlDocumentId(9911L);
        digitalInvoiceService.save(downloadable);
        when(documentService.download(eq(9911L), any()))
                .thenThrow(new RuntimeException(compositeSecretExceptionMessage()));

        MvcResult downloadResult = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/digital-invoices/" + downloadable.getId() + "/xml"))
                .andExpect(status().isInternalServerError()).andReturn();
        assertNoSecretsInLogsAndResponses(downloadResult.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("バックグラウンドワーカーの再試行と最終失敗で安全な状態と診断IDを残す")
    void ワーカーの再試行と最終失敗を安全に記録する() {
        Customer customer = createCustomer("ワーカー境界検証会社");
        verifiedParticipant(customer, "part-worker-boundary");
        Invoice invoice = createInvoice(customer, "INV-WORKER-BOUNDARY-1");
        when(digitalInvoiceProvider.sendInvoice(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException(compositeSecretExceptionMessage()));
        when(documentService.registerGenerated(any(), any())).thenAnswer(inv -> {
            com.ses.entity.Document doc = new com.ses.entity.Document();
            doc.setId(9921L);
            return doc;
        });

        DigitalInvoice di = digitalInvoiceService.enqueueInvoiceForSend(invoice.getId(), "1.1.3", customer.getId());
        com.ses.entity.IntegrationJob job = integrationJobService.getLatestJob("t_digital_invoice", di.getId(), "DIGITAL_INVOICE_SEND");
        job.setMaxAttempts(1);
        integrationJobService.updateById(job);

        accountingIntegrationWorker.dispatchJob(job);

        com.ses.entity.IntegrationJob failed = integrationJobService.getById(job.getId());
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getErrorCode()).isEqualTo("MAX_ATTEMPTS_EXCEEDED");
        assertThat(failed.getErrorMessageSafe()).isEqualTo("error.integration.maxAttemptsExceeded");
        assertThat(failed.getCorrelationId()).isNotBlank();
        assertThat(integrationJobEventMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.ses.entity.IntegrationJobEvent>()
                .eq(com.ses.entity.IntegrationJobEvent::getJobId, job.getId())))
                .allMatch(event -> event.getCorrelationId() != null && event.getSafeDetail() != null);
        assertNoSecretsInLogsAndResponses(null);
    }

    @Test
    @DisplayName("プロバイダ操作IDをローカル状態更新失敗後も照合可能に保持する")
    void プロバイダ応答後の状態更新失敗でも操作IDを保持する() {
        Customer customer = createCustomer("プロバイダ照合検証会社");
        verifiedParticipant(customer, "part-provider-operation");
        Invoice invoice = createInvoice(customer, "INV-PROVIDER-OPERATION-1");
        when(digitalInvoiceProvider.sendInvoiceWithMetadata(anyString(), anyString(), anyString()))
                .thenReturn(new DigitalInvoiceProviderResponse("provider-message-safe", "provider-operation-safe",
                        "provider-request-safe", 202, "ACCEPTED"));
        when(documentService.registerGenerated(any(), any())).thenAnswer(inv -> {
            com.ses.entity.Document doc = new com.ses.entity.Document();
            doc.setId(9931L);
            return doc;
        });

        DigitalInvoice di = digitalInvoiceService.enqueueInvoiceForSend(invoice.getId(), "1.1.3", customer.getId());
        di.setXmlDocumentId(9931L);
        digitalInvoiceService.updateById(di);
        doReturn(false).when(digitalInvoiceService).updateById(org.mockito.ArgumentMatchers.argThat(
                candidate -> candidate != null && di.getId().equals(candidate.getId())));
        com.ses.entity.IntegrationJob job = integrationJobService.getLatestJob("t_digital_invoice", di.getId(), "DIGITAL_INVOICE_SEND");

        digitalInvoiceService.processSendJob(job.getId());

        com.ses.entity.IntegrationJob updated = integrationJobService.getById(job.getId());
        assertThat(updated.getProviderOperationId()).isEqualTo("provider-operation-safe");
        assertThat(updated.getProviderRequestId()).isEqualTo("provider-request-safe");
        assertThat(updated.getErrorCode()).isEqualTo("LOCAL_STATE_UPDATE_FAILED");
        assertThat(updated.getErrorCategory()).isEqualTo("SYSTEM");
        assertThat(updated.getErrorMessageSafe()).isEqualTo("error.invoice.localStateUpdateFailed");
        assertNoSecretsInLogsAndResponses(null);
    }

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("任意のBusinessException原文をAPI応答と全ログへ出力しない")
    void 任意の業務例外原文をAPIと全ログへ出力しない() throws Exception {
        Customer customer = createCustomer("業務例外境界検証会社");
        Invoice invoice = createInvoice(customer, "INV-BUSINESS-SECRET-1");
        doThrow(new BusinessException(400, compositeSecretExceptionMessage()))
                .when(deliveryDispatcher).dispatch(anyLong(), anyLong(), anyString());

        MvcResult result = mockMvc.perform(post("/api/digital-invoices/dispatch/" + invoice.getId())
                        .header("X-Correlation-ID", "corr-business-exception").with(csrf()))
                .andExpect(status().isBadRequest()).andReturn();

        assertNoSecretsInLogsAndResponses(result.getResponse().getContentAsString());
        assertThat(result.getResponse().getContentAsString()).contains("入力内容を確認してください");
    }

    @Test
    @DisplayName("Webhook JSONエラー本文を保存せず安全な分類と相関情報だけを残す")
    void WebhookのJSONエラー本文を保存しない() throws Exception {
        when(digitalInvoiceProvider.verifyWebhookSignature(anyString(), anyString())).thenReturn(true);
        String body = "{\"status\":\"DELIVERED\",\"messageId\":\"msg-webhook-safe\","
                + "\"eventId\":\"op-webhook-safe\",\"error_description\":\""
                + SECRET_DB_PASS + " " + SECRET_BEARER + "\",\"token\":\""
                + SECRET_REFRESH + "\"";

        MvcResult result = mockMvc.perform(post("/api/webhooks/digital-invoice/fastaccounting")
                        .header("X-Correlation-ID", "corr-webhook-error")
                        .header("X-Signature", "signature-safe")
                        .contentType("application/json").content(body).with(csrf()))
                .andExpect(status().isInternalServerError()).andReturn();

        assertNoSecretsInLogsAndResponses(result.getResponse().getContentAsString());
        assertThat(appender.list).anyMatch(event -> event.getFormattedMessage().contains("correlationId=corr-webhook-error"));
    }

    private Customer createCustomer(String name) {
        Customer c = new Customer();
        c.setCompanyName(name);
        c.setDeliveryPreference("PEPPOL");
        customerService.save(c);
        return c;
    }

    private PeppolParticipant verifiedParticipant(Customer c, String participantId) {
        PeppolParticipant pp = new PeppolParticipant();
        pp.setOwnerType("CUSTOMER");
        pp.setOwnerId(c.getId());
        pp.setSchemeId("0192");
        pp.setProvider("FASTACCOUNTING");
        pp.setStatus("ACTIVE");
        pp.setVerifiedAt(LocalDateTime.now());
        pp.setParticipantId(participantId);
        peppolParticipantService.save(pp);
        return pp;
    }

    private Invoice createInvoice(Customer c, String invoiceNo) {
        Invoice inv = new Invoice();
        inv.setInvoiceNo(invoiceNo);
        inv.setCustomerId(c.getId());
        inv.setBillingMonth("2026-08");
        inv.setSubtotal(new BigDecimal("1000"));
        inv.setTax(new BigDecimal("100"));
        inv.setTotal(new BigDecimal("1100"));
        inv.setTaxRate(new BigDecimal("0.10"));
        inv.setStatus("未送付");
        inv.setIssuedDate(LocalDate.now());
        invoiceService.save(inv);
        stubInvoiceItem(inv.getId(), "1000");
        return inv;
    }

    @SuppressWarnings("unchecked")
    private void stubInvoiceItem(Long invoiceId, String amount) {
        com.ses.entity.InvoiceItem item = new com.ses.entity.InvoiceItem();
        item.setInvoiceId(invoiceId);
        item.setWorkRecordId(1L);
        item.setDescription("SES Service");
        item.setAmount(new BigDecimal(amount));
        when(invoiceItemMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(item));
    }
}
