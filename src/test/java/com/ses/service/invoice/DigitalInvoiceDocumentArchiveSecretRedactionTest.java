package com.ses.service.invoice;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.ses.common.enums.FileKind;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Customer;
import com.ses.entity.DigitalInvoice;
import com.ses.entity.Invoice;
import com.ses.entity.PeppolParticipant;
import com.ses.service.CustomerService;
import com.ses.service.DigitalInvoiceService;
import com.ses.service.DocumentService;
import com.ses.service.FileStorageService;
import com.ses.service.InvoiceService;
import com.ses.service.PeppolParticipantService;
import com.ses.service.impl.DigitalInvoiceServiceImpl;
import com.ses.service.impl.DocumentServiceImpl;
import com.ses.service.impl.FileStorageServiceImpl;
import com.ses.service.integration.IntegrationJobService;
import com.ses.service.invoice.provider.DigitalInvoiceProvider;
import com.ses.service.security.FileScanResult;
import com.ses.service.security.FileScanner;
import com.ses.entity.FileSecurityMetadata;
import com.ses.service.storage.DocumentStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * 送信・受信 XML アーカイブ時の DocumentServiceImpl / FileStorageServiceImpl 実装経路における
 * 機密情報ログ秘匿化（Redaction）回帰テスト。
 * DocumentService を MockBean にせず、実クラスを経由した例外処理での安全な診断ログ出力を検証する。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("電子請求書XMLアーカイブ時のDocumentService/FileStorageService機密秘匿化テスト")
class DigitalInvoiceDocumentArchiveSecretRedactionTest {

    private static final List<String> ALL_INJECTED_SECRETS = List.of(
            "password=secret",
            "jdbc:mysql://user:password@host/db",
            "test-secret-token",
            "test-refresh-secret",
            "customer@example.com",
            "sql-secret",
            "bind-secret-val",
            "provider-json-secret-body",
            "json-token-secret"
    );

    @Autowired
    private DigitalInvoiceService digitalInvoiceService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private PeppolParticipantService peppolParticipantService;

    @Autowired
    private IntegrationJobService integrationJobService;

    @MockBean
    private DigitalInvoiceProvider digitalInvoiceProvider;

    @MockBean
    private DocumentStorage documentStorage;

    @MockBean
    private FileScanner fileScanner;

    @MockBean
    private com.ses.mapper.FileSecurityMetadataMapper fileSecurityMetadataMapper;

    @MockBean
    private com.ses.mapper.InvoiceItemMapper invoiceItemMapper;

    private ListAppender<ILoggingEvent> appender;
    private Logger documentServiceLogger;
    private Logger fileStorageLogger;
    private Logger digitalInvoiceLogger;
    private Logger rootLogger;

    @BeforeEach
    void setUp() {
        appender = new ListAppender<>();
        appender.start();

        documentServiceLogger = (Logger) LoggerFactory.getLogger(DocumentServiceImpl.class);
        fileStorageLogger = (Logger) LoggerFactory.getLogger(FileStorageServiceImpl.class);
        digitalInvoiceLogger = (Logger) LoggerFactory.getLogger(DigitalInvoiceServiceImpl.class);
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

        documentServiceLogger.addAppender(appender);
        fileStorageLogger.addAppender(appender);
        digitalInvoiceLogger.addAppender(appender);
        rootLogger.addAppender(appender);

        when(fileScanner.scan(any(Path.class), any(FileKind.class)))
                .thenReturn(FileScanResult.clean("1.0"));
    }

    @AfterEach
    void tearDown() {
        documentServiceLogger.detachAppender(appender);
        fileStorageLogger.detachAppender(appender);
        digitalInvoiceLogger.detachAppender(appender);
        rootLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("送信XMLアーカイブ例外パス: DocumentServiceImpl実体経由で機密が漏洩せずARCHIVE_FAILED診断情報が残る")
    void 送信XMLアーカイブで実DocumentService経由の機密漏洩を防ぎ診断情報を記録する() {
        // DocumentStorage が複合機密例外を投げるようモック（DocumentServiceは本物のSpring Bean）
        doThrow(new RuntimeException(createCompositeSecretException()))
                .when(documentStorage).put(anyString(), any(InputStream.class), anyBoolean());

        Customer customer = createCustomer("Real Doc Send Co");
        verifiedParticipant(customer, "part-real-doc-send");
        Invoice invoice = createInvoice(customer, "INV-REAL-DOC-SEND-1");

        DigitalInvoice di = digitalInvoiceService.enqueueInvoiceForSend(invoice.getId(), "1.1.3", customer.getId());
        com.ses.entity.IntegrationJob job = integrationJobService.getLatestJob("t_digital_invoice", di.getId(), "DIGITAL_INVOICE_SEND");
        assertThat(job).isNotNull();

        // 送信ジョブ実行（実DocumentService.registerGenerated 内で storage.put が失敗しログ記録）
        digitalInvoiceService.processSendJob(job.getId());

        // ログの検証
        assertNoSecretsInAppenderLogs();

        // DigitalInvoiceServiceImpl に ARCHIVE_FAILED, digitalInvoiceId, invoiceId, exceptionClass が記録されていること
        boolean foundDiag = appender.list.stream().anyMatch(e ->
                e.getLoggerName().equals(DigitalInvoiceServiceImpl.class.getName())
                        && e.getFormattedMessage().contains("送信XMLのアーカイブに失敗")
                        && e.getFormattedMessage().contains("digitalInvoiceId=" + di.getId())
                        && e.getFormattedMessage().contains("invoiceId=" + invoice.getId())
                        && e.getFormattedMessage().contains("errorCode=ARCHIVE_FAILED")
                        && e.getFormattedMessage().contains("exceptionClass="));
        assertThat(foundDiag).as("DigitalInvoiceServiceImplに安全な診断ログが記録されていること").isTrue();
    }

    @Test
    @DisplayName("受信XMLアーカイブ例外パス: DocumentServiceImpl実体経由で機密が漏洩せずARCHIVE_FAILED診断情報が残る")
    void 受信XMLアーカイブで実DocumentService経由の機密漏洩を防ぎ診断情報を記録する() {
        // DocumentStorage が複合機密例外を投げるようモック
        doThrow(new RuntimeException(createCompositeSecretException()))
                .when(documentStorage).put(anyString(), any(InputStream.class), anyBoolean());

        assertThrows(BusinessException.class, () ->
                digitalInvoiceService.processInboundInvoice("prov-inbound-secret-msg-1", "evt-inbound-secret-1",
                        "<Invoice/>", "hash123", LocalDateTime.now()));

        // ログの検証
        assertNoSecretsInAppenderLogs();

        // DigitalInvoiceServiceImpl に ARCHIVE_FAILED, providerMessageId, eventId, exceptionClass が記録されていること
        boolean foundDiag = appender.list.stream().anyMatch(e ->
                e.getLoggerName().equals(DigitalInvoiceServiceImpl.class.getName())
                        && e.getFormattedMessage().contains("受信XMLのアーカイブに失敗")
                        && e.getFormattedMessage().contains("providerMessageId=prov-inbound-secret-msg-1")
                        && e.getFormattedMessage().contains("eventId=evt-inbound-secret-1")
                        && e.getFormattedMessage().contains("errorCode=ARCHIVE_FAILED")
                        && e.getFormattedMessage().contains("exceptionClass="));
        assertThat(foundDiag).as("DigitalInvoiceServiceImplに受信アーカイブ失敗の診断ログが記録されていること").isTrue();
    }

    @Test
    @DisplayName("FileStorageServiceImpl保存/scan例外パス: 機密が漏洩せず安全なログが記録される")
    void FileStorageServiceの例外時に機密が漏洩せず診断情報が記録される() {
        when(fileSecurityMetadataMapper.insert(any(FileSecurityMetadata.class)))
                .thenThrow(new RuntimeException(createCompositeSecretException()));

        byte[] validPdf = "%PDF-1.7\n1 0 obj<<>>endobj\n%%EOF\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertThrows(BusinessException.class, () ->
                fileStorageService.store(validPdf, "secret-test.pdf", FileKind.CONTRACT_PDF));

        assertNoSecretsInAppenderLogs();

        boolean foundDiag = appender.list.stream().anyMatch(e ->
                e.getLoggerName().equals(FileStorageServiceImpl.class.getName())
                        && e.getFormattedMessage().contains("ファイル保存またはscanに失敗しました")
                        && e.getFormattedMessage().contains("storedName=")
                        && e.getFormattedMessage().contains("exceptionClass=")
                        && e.getFormattedMessage().contains("detail="));
        assertThat(foundDiag).as("FileStorageServiceImplに安全な診断ログが記録されていること").isTrue();
    }

    private Throwable createCompositeSecretException() {
        String providerJsonError = "{\"error\":\"invalid_request\",\"error_description\":\"provider-json-secret-body\",\"token\":\"json-token-secret\"}";
        String rootMsg = "Root DB failure with db password=secret and jdbc:mysql://user:password@host/db "
                + "while processing SQL: UPDATE t_digital_invoice SET status = 'SENT' WHERE secret = 'sql-secret' "
                + "SQL Parameters: [bind-secret-val] "
                + "provider error response: " + providerJsonError;

        SQLException rootSqlEx = new SQLException(rootMsg, "42000");
        IllegalStateException midEx = new IllegalStateException(
                "Mid-tier error with Authorization: Bearer test-secret-token and refresh_token=test-refresh-secret", rootSqlEx);
        return new RuntimeException("Top-tier failure contacting customer@example.com", midEx);
    }

    private void assertNoSecretsInAppenderLogs() {
        assertThat(appender.list).isNotEmpty();
        for (ILoggingEvent event : appender.list) {
            String formatted = event.getFormattedMessage();
            for (String secret : ALL_INJECTED_SECRETS) {
                assertThat(formatted).as("formattedMessageに機密が含まれないこと: %s", secret).doesNotContain(secret);
            }
            if (event.getArgumentArray() != null) {
                for (Object arg : event.getArgumentArray()) {
                    if (arg != null) {
                        for (String secret : ALL_INJECTED_SECRETS) {
                            assertThat(arg.toString()).as("argumentArrayに機密が含まれないこと: %s", secret).doesNotContain(secret);
                        }
                    }
                }
            }
            assertThrowableProxyNoSecrets(event.getThrowableProxy());
        }
    }

    private void assertThrowableProxyNoSecrets(IThrowableProxy proxy) {
        if (proxy == null) {
            return;
        }
        String msg = proxy.getMessage();
        if (msg != null) {
            for (String secret : ALL_INJECTED_SECRETS) {
                assertThat(msg).as("IThrowableProxy messageに機密が含まれないこと: %s", secret).doesNotContain(secret);
            }
        }
        assertThrowableProxyNoSecrets(proxy.getCause());
        if (proxy.getSuppressed() != null) {
            for (IThrowableProxy supp : proxy.getSuppressed()) {
                assertThrowableProxyNoSecrets(supp);
            }
        }
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
