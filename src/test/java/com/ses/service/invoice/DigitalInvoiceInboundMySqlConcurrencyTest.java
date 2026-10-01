package com.ses.service.invoice;

import com.ses.common.exception.BusinessException;
import com.ses.service.DigitalInvoiceService;
import com.ses.service.security.FileScanResult;
import com.ses.service.security.FileScanner;
import com.ses.service.storage.DocumentStorage;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/** S-NF09: 受信電文の一意制約・原子性・archive補償を実MySQLで検証する。 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class DigitalInvoiceInboundMySqlConcurrencyTest {

    private static final String TENANT = "nf09-tenant-a";
    private static final long LEGAL_ENTITY = 1L;
    private static final String RECEIVER_ID = "nf09-receiver-a";
    private static final String SUPPLIER_ID = "nf09-supplier-a";
    private static final String RECEIVER_ID_B = "nf09-receiver-b";
    private static final String SUPPLIER_ID_B = "nf09-supplier-b";

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_nf09_inbound")
            .withUsername("root")
            .withPassword("ses");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Autowired
    private DigitalInvoiceService digitalInvoiceService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private DocumentStorage documentStorage;
    @MockBean
    private FileScanner fileScanner;

    @BeforeEach
    void setUp() throws Exception {
        dropFailureTriggers();
        jdbcTemplate.update("DELETE FROM t_peppol_participant WHERE participant_id IN (?, ?, ?, ?)",
                RECEIVER_ID, SUPPLIER_ID, RECEIVER_ID_B, SUPPLIER_ID_B);
        insertParticipant(TENANT, LEGAL_ENTITY, "ORGANIZATION", 910001L, RECEIVER_ID);
        insertParticipant(TENANT, LEGAL_ENTITY, "BP_COMPANY", 910002L, SUPPLIER_ID);
        insertParticipant("nf09-tenant-b", 2L, "ORGANIZATION", 920001L, RECEIVER_ID_B);
        insertParticipant("nf09-tenant-b", 2L, "BP_COMPANY", 920002L, SUPPLIER_ID_B);
        when(fileScanner.scan(any(Path.class), any())).thenReturn(FileScanResult.clean("nf09-test"));
        doNothing().when(documentStorage).put(any(String.class), any(InputStream.class), anyBoolean());
        doNothing().when(documentStorage).promote(any(String.class));
        doNothing().when(documentStorage).delete(any(String.class));
    }

    @AfterEach
    void tearDown() {
        dropFailureTriggers();
        jdbcTemplate.update("DELETE e FROM t_digital_invoice_event e "
                + "JOIN t_digital_invoice d ON d.id = e.digital_invoice_id "
                + "WHERE d.provider_message_id LIKE 'nf09-mysql-%'");
        jdbcTemplate.update("DELETE l FROM t_document_access_log l "
                + "JOIN t_document_version v ON v.document_id = l.document_id "
                + "WHERE v.business_key LIKE 'DIGITAL_INVOICE:nf09-mysql-%'");
        jdbcTemplate.update("DELETE v FROM t_document_version v "
                + "WHERE v.business_key LIKE 'DIGITAL_INVOICE:nf09-mysql-%'");
        jdbcTemplate.update("DELETE d FROM t_document d "
                + "WHERE d.document_type = 'INVOICE_IN' AND NOT EXISTS "
                + "(SELECT 1 FROM t_document_version v WHERE v.document_id = d.id)");
        jdbcTemplate.update("DELETE FROM t_digital_invoice WHERE provider_message_id LIKE 'nf09-mysql-%'");
        jdbcTemplate.update("DELETE FROM t_peppol_participant WHERE participant_id IN (?, ?, ?, ?)",
                RECEIVER_ID, SUPPLIER_ID, RECEIVER_ID_B, SUPPLIER_ID_B);
    }

    @Test
    void 同一providerMessageIdの並行受信は一つのinvoice_event_documentへ収束する() throws Exception {
        String xml = xml("NF09-SAME-MESSAGE");
        List<Throwable> errors = runConcurrently(
                () -> processInboundAsProvider(
                        "nf09-mysql-same-message", "nf09-mysql-event-same", xml, "hash-same", LocalDateTime.now()),
                () -> processInboundAsProvider(
                        "nf09-mysql-same-message", "nf09-mysql-event-same", xml, "hash-same", LocalDateTime.now()));

        assertTrue(errors.isEmpty(), () -> "予期しない並行受信エラー: " + errors
                + " db=" + jdbcTemplate.queryForList(
                "SELECT d.id, d.provider_message_id, e.provider_event_id, e.payload_hash, "
                        + "e.canonical_payload_hash FROM t_digital_invoice d "
                        + "LEFT JOIN t_digital_invoice_event e ON e.digital_invoice_id = d.id "
                        + "WHERE d.provider_message_id = 'nf09-mysql-same-message'"));
        assertCounts("nf09-mysql-same-message", 1, 1, 1);
    }

    @Test
    void 同一providerMessageIdでeventIdが異なる場合はfailClosedする() {
        String xml = xml("NF09-EVENT-ID-CONFLICT");
        processInboundAsProvider(
                "nf09-mysql-event-id-conflict", "nf09-mysql-event-one", xml, "hash-same", LocalDateTime.now());

        BusinessException error = assertThrows(BusinessException.class,
                () -> processInboundAsProvider(
                        "nf09-mysql-event-id-conflict", "nf09-mysql-event-two", xml, "hash-same", LocalDateTime.now()));

        assertEquals(409, error.getCode());
        assertCounts("nf09-mysql-event-id-conflict", 1, 1, 1);
    }

    @Test
    void 不正XMLは実シリアライズ例外をcauseに保持して何も登録しない() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> processInboundAsProvider(
                        "nf09-mysql-malformed", "nf09-mysql-malformed-event",
                        "<Invoice><ID>broken", "hash-malformed", LocalDateTime.now()));

        assertEquals(400, error.getCode());
        assertNotNull(error.getCause());
        assertCounts("nf09-mysql-malformed", 0, 0, 0);
    }

    @Test
    void 同一providerMessageIdでcanonicalXMLが異なる場合は既存archiveを変更せず拒否する() {
        processInboundAsProvider(
                "nf09-mysql-payload-conflict", "nf09-mysql-payload-event",
                xml("NF09-PAYLOAD-ONE"), "hash-one", LocalDateTime.now());

        BusinessException error = assertThrows(BusinessException.class,
                () -> processInboundAsProvider(
                        "nf09-mysql-payload-conflict", "nf09-mysql-payload-event",
                        xml("NF09-PAYLOAD-TWO"), "hash-two", LocalDateTime.now()));

        assertEquals(409, error.getCode());
        assertCounts("nf09-mysql-payload-conflict", 1, 1, 1);
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_document_version WHERE business_key = ?",
                Long.class, "DIGITAL_INVOICE:nf09-mysql-payload-conflict"));
    }

    @Test
    void 既存送信電文と同じproviderMessageIdの受信は方向不一致で拒否する() {
        // 本テストの前提データは受信service境界を通さず、V182のactor pairを満たす形で固定する。
        jdbcTemplate.update("""
                INSERT INTO t_digital_invoice
                    (tenant_id, legal_entity_id, direction, profile, specification_version,
                     message_id, provider_message_id, status, actor_type, confirmation_source,
                     created_at, updated_at)
                VALUES (?, ?, 'SEND', 'Standard', '1.1.3', ?, ?, 'SENT',
                        'LEGACY_UNRESOLVED', 'LEGACY_UNRESOLVED', NOW(), NOW())
                """, TENANT, LEGAL_ENTITY, "NF09-DIRECTION-CONFLICT",
                "nf09-mysql-direction-conflict");

        BusinessException error = assertThrows(BusinessException.class,
                () -> processInboundAsProvider(
                        "nf09-mysql-direction-conflict", "nf09-mysql-direction-event",
                        xml("NF09-DIRECTION-CONFLICT-IN"), "hash-direction", LocalDateTime.now()));

        assertEquals(409, error.getCode());
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_digital_invoice WHERE provider_message_id = ? AND direction = 'SEND'",
                Long.class, "nf09-mysql-direction-conflict"));
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_digital_invoice WHERE provider_message_id = ? AND direction = 'RECEIVE'",
                Long.class, "nf09-mysql-direction-conflict"));
    }

    @Test
    void 異なるproviderMessageIdでも同一XMLmessageIdなら一つへ収束する() {
        String xml = xml("NF09-SAME-XML-ID");
        processInboundAsProvider(
                "nf09-mysql-message-a", "nf09-mysql-event-c", xml, "hash-xml", LocalDateTime.now());
        processInboundAsProvider(
                "nf09-mysql-message-b", "nf09-mysql-event-d", xml, "hash-xml", LocalDateTime.now());

        assertCounts("nf09-mysql-message-", 1, 1, 1);
    }

    @Test
    void 同一businessIdでもreceiverScopeが異なれば別invoiceとして保存する() {
        String invoiceNo = "NF09-SAME-BUSINESS-ID";
        String xmlA = xml(invoiceNo, RECEIVER_ID, SUPPLIER_ID);
        String xmlB = xml(invoiceNo, RECEIVER_ID_B, SUPPLIER_ID_B);

        processInboundAsProvider(
                "nf09-mysql-cross-scope-a", "nf09-mysql-cross-event-a",
                xmlA, "hash-cross-a", LocalDateTime.now());
        processInboundAsProvider(
                "nf09-mysql-cross-scope-b", "nf09-mysql-cross-event-b",
                xmlB, "hash-cross-b", LocalDateTime.now());

        assertEquals(2L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_digital_invoice WHERE message_id = ? AND direction = 'RECEIVE'",
                Long.class, invoiceNo));
        assertEquals(2L, jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT CONCAT(tenant_id, ':', legal_entity_id)) "
                        + "FROM t_digital_invoice WHERE message_id = ? AND direction = 'RECEIVE'",
                Long.class, invoiceNo));
    }

    @Test
    void 同一providerEventIdでpayloadHashが異なる場合は409で既存eventを保持する() {
        String xml = xml("NF09-EVENT-CONFLICT");
        processInboundAsProvider(
                "nf09-mysql-event-message", "nf09-mysql-event-conflict", xml, "hash-original", LocalDateTime.now());

        BusinessException error = assertThrows(BusinessException.class,
                () -> processInboundAsProvider(
                        "nf09-mysql-event-message", "nf09-mysql-event-conflict",
                        xml, "hash-tampered", LocalDateTime.now()));
        assertEquals(409, error.getCode());
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_digital_invoice_event WHERE provider_event_id = ?",
                Long.class, "nf09-mysql-event-conflict"));
    }

    @Test
    void archive失敗時はinvoiceとdocumentを残さない() {
        doThrow(new IllegalStateException("archive-failure-secret"))
                .when(documentStorage).put(any(String.class), any(InputStream.class), anyBoolean());

        BusinessException error = assertThrows(BusinessException.class, () -> processInboundAsProvider(
                "nf09-mysql-archive-failure", "nf09-mysql-event-failure-a",
                xml("NF09-ARCHIVE-FAILURE"), "hash-failure", LocalDateTime.now()));

        assertNotNull(error.getCause());

        assertCounts("nf09-mysql-archive-failure", 0, 0, 0);
    }

    @Test
    void business行insert失敗時はdocumentを残さない() {
        createBusinessInsertFailureTrigger();

        assertThrows(RuntimeException.class, () -> processInboundAsProvider(
                "nf09-mysql-business-failure", "nf09-mysql-event-failure-b",
                xml("NF09-BUSINESS-FAILURE"), "hash-business", LocalDateTime.now()));

        assertCounts("nf09-mysql-business-failure", 0, 0, 0);
    }

    @Test
    void eventinsert失敗時はinvoiceとdocumentを同時rollbackする() {
        createEventInsertFailureTrigger();

        assertThrows(RuntimeException.class, () -> processInboundAsProvider(
                "nf09-mysql-event-failure", "nf09-mysql-event-failure-c",
                xml("NF09-EVENT-FAILURE"), "hash-event", LocalDateTime.now()));

        assertCounts("nf09-mysql-event-failure", 0, 0, 0);
    }

    private void createBusinessInsertFailureTrigger() {
        jdbcTemplate.execute("CREATE TRIGGER nf09_business_insert_failure BEFORE INSERT ON t_digital_invoice "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'nf09 business insert failure'");
    }

    private void createEventInsertFailureTrigger() {
        jdbcTemplate.execute("CREATE TRIGGER nf09_event_insert_failure BEFORE INSERT ON t_digital_invoice_event "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'nf09 event insert failure'");
    }

    private void dropFailureTriggers() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS nf09_business_insert_failure");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS nf09_event_insert_failure");
    }

    private void processInboundAsProvider(String providerMessageId, String eventId, String xml,
                                          String payloadHash, LocalDateTime eventAt) {
        com.ses.common.audit.ExecutionActorContext.runAsProviderCallback(
                "test-provider-callback", eventId,
                () -> digitalInvoiceService.processInboundInvoice(
                        providerMessageId, eventId, xml, payloadHash, eventAt));
    }

    private String xml(String invoiceNo) {
        return xml(invoiceNo, RECEIVER_ID, SUPPLIER_ID);
    }

    private String xml(String invoiceNo, String receiverId, String supplierId) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Invoice>"
                + "<ID>" + invoiceNo + "</ID><IssueDate>2026-08-01</IssueDate>"
                + "<AccountingSupplierParty><Party><EndpointID schemeID=\"0188\">" + supplierId
                + "</EndpointID></Party></AccountingSupplierParty>"
                + "<AccountingCustomerParty><Party><EndpointID schemeID=\"0188\">" + receiverId
                + "</EndpointID></Party></AccountingCustomerParty>"
                + "<LegalMonetaryTotal><TaxInclusiveAmount>100</TaxInclusiveAmount></LegalMonetaryTotal>"
                + "</Invoice>";
    }

    private void insertParticipant(String tenantId, Long legalEntityId,
                                   String ownerType, Long ownerId, String participantId) {
        jdbcTemplate.update("""
                INSERT INTO t_peppol_participant
                    (tenant_id, legal_entity_id, owner_type, owner_id, scheme_id,
                     participant_id, provider, status, verified_at, deleted_flag)
                VALUES (?, ?, ?, ?, '0188', ?, 'FAST_ACCOUNTING', 'VERIFIED', NOW(), 0)
                """, tenantId, legalEntityId, ownerType, ownerId, participantId);
    }

    private List<Throwable> runConcurrently(CheckedAction... actions) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(actions.length);
        CountDownLatch ready = new CountDownLatch(actions.length);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        try {
            for (CheckedAction action : actions) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(20, TimeUnit.SECONDS);
                    action.run();
                    return null;
                }));
            }
            assertTrue(ready.await(20, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> future : futures) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException ex) {
                    errors.add(ex.getCause());
                }
            }
        } finally {
            executor.shutdownNow();
        }
        return errors;
    }

    private void assertCounts(String providerMessagePrefix, long invoiceCount,
                              long eventCount, long documentCount) {
        assertEquals(invoiceCount, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_digital_invoice WHERE provider_message_id LIKE ?",
                Long.class, providerMessagePrefix + "%"));
        assertEquals(eventCount, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_digital_invoice_event WHERE provider_event_id LIKE 'nf09-mysql-%' "
                        + "AND digital_invoice_id IN (SELECT id FROM t_digital_invoice WHERE provider_message_id LIKE ?)",
                Long.class, providerMessagePrefix + "%"));
        assertEquals(documentCount, jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT d.id) FROM t_document d JOIN t_document_version v ON v.document_id = d.id "
                        + "WHERE v.business_key LIKE ?", Long.class,
                "DIGITAL_INVOICE:" + providerMessagePrefix + "%"));
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }
}
