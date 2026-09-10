package com.ses.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.dto.report.ReportRecipientPreview;
import com.ses.dto.report.ReportRecipientPreviewResult;
import com.ses.entity.Document;
import com.ses.entity.DocumentVersion;
import com.ses.entity.ReportDelivery;
import com.ses.entity.ReportRun;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.mapper.ReportDeliveryMapper;
import com.ses.mapper.ReportRunMapper;
import com.ses.service.report.ReportDeliveryService;
import com.ses.service.report.ReportRecipientPreviewService;
import com.ses.service.notification.WebhookNotifier;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** MySQL上でlegacy deliveryのretry/manual replayと文書解決の実DB経路を確認する。 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class ReportDeliveryMySqlIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_nf10_delivery_mysql")
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
    private ReportDeliveryService reportDeliveryService;

    @Autowired
    private ReportRunMapper runMapper;

    @Autowired
    private ReportDeliveryMapper deliveryMapper;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private DocumentVersionMapper documentVersionMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private ReportRecipientPreviewService recipientPreviewService;

    @MockBean
    private WebhookNotifier webhookNotifier;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<Long> runIds = new ArrayList<>();
    private final List<Long> documentIds = new ArrayList<>();

    @BeforeEach
    void authenticateAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("1", "N/A",
                        List.of(new SimpleGrantedAuthority("ROLE_管理者"))));
    }

    @AfterEach
    void cleanupFixtures() {
        for (Long runId : runIds) {
            jdbcTemplate.update("DELETE FROM t_notification_outbox WHERE dedupe_key LIKE CONCAT('REPORT:', ?, ':%')", runId);
            jdbcTemplate.update("DELETE FROM t_notification WHERE dedupe_key LIKE CONCAT('REPORT:', ?, ':%')", runId);
            jdbcTemplate.update("DELETE FROM t_report_delivery WHERE run_id = ?", runId);
        }
        for (Long documentId : documentIds) {
            jdbcTemplate.update("DELETE FROM t_document_version WHERE document_id = ?", documentId);
            jdbcTemplate.update("DELETE FROM t_document WHERE id = ?", documentId);
        }
        for (Long runId : runIds) {
            jdbcTemplate.update("DELETE FROM t_report_run WHERE id = ?", runId);
        }
        SecurityContextHolder.clearContext();
    }

    @Test
    void retryはlegacy文書を再利用し再実行しても通知を重複発行しない() throws Exception {
        ReportRun run = insertRun("retry");
        DocumentVersion version = insertDocument(run.getRunKey());
        ReportDelivery delivery = insertDelivery(run, version, "RETRY");
        stubPreview(run);

        reportDeliveryService.retry(delivery.getId());
        reportDeliveryService.retry(delivery.getId());

        ReportDelivery loaded = deliveryMapper.selectById(delivery.getId());
        assertThat(loaded.getDeliveryStatus()).isEqualTo("ENQUEUED");
        assertThat(loaded.getNotificationOutboxId()).isNotNull();
        assertThat(loaded.getDocumentId()).isEqualTo(version.getDocumentId());
        assertThat(loaded.getDocumentVersionNo()).isEqualTo(1);
        assertThat(loaded.getLinkTokenHash()).hasSize(64);
        assertNotificationIsActionOnly(run.getId(), loaded.getNotificationDedupeKey());
    }

    @Test
    void manualReplayはlegacy文書を再利用し重複replayを無害化する() throws Exception {
        ReportRun run = insertRun("manual-replay");
        DocumentVersion version = insertDocument(run.getRunKey());
        ReportDelivery delivery = insertDelivery(run, version, "FAILED");
        stubPreview(run);

        reportDeliveryService.manualReplay(delivery.getId());
        reportDeliveryService.manualReplay(delivery.getId());

        ReportDelivery loaded = deliveryMapper.selectById(delivery.getId());
        assertThat(loaded.getDeliveryStatus()).isEqualTo("ENQUEUED");
        assertThat(loaded.getNotificationOutboxId()).isNotNull();
        assertThat(loaded.getDocumentId()).isEqualTo(version.getDocumentId());
        assertNotificationIsActionOnly(run.getId(), loaded.getNotificationDedupeKey());
    }

    private void assertNotificationIsActionOnly(Long runId, String dedupeKey) {
        Integer notificationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_notification WHERE dedupe_key = ?", Integer.class, dedupeKey);
        Integer outboxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_notification_outbox WHERE dedupe_key = ?", Integer.class, dedupeKey);
        String notificationLink = jdbcTemplate.queryForObject(
                "SELECT link_url FROM t_notification WHERE dedupe_key = ?", String.class, dedupeKey);
        String outboxLink = jdbcTemplate.queryForObject(
                "SELECT link_url FROM t_notification_outbox WHERE dedupe_key = ?", String.class, dedupeKey);
        assertThat(notificationCount).isEqualTo(1);
        assertThat(outboxCount).isEqualTo(1);
        assertThat(notificationLink).contains("/api/management-reports/deliveries/")
                .doesNotContain("token=").doesNotContain("delivery:");
        assertThat(outboxLink).contains("/api/management-reports/deliveries/")
                .doesNotContain("token=").doesNotContain("delivery:");
        assertThat(runId).isPositive();
    }

    private void stubPreview(ReportRun run) throws Exception {
        ReportRecipientPreview recipient = new ReportRecipientPreview(
                1L, "管理者", "ALLOW", "SCOPE-MYSQL", "scope-mysql");
        ReportRecipientPreviewResult preview = new ReportRecipientPreviewResult(
                "preview-mysql", "APPROVED_SCOPE_CHECKED", LocalDateTime.now(), List.of(recipient));
        run.setRecipientPreviewHash(preview.getPreviewHash());
        run.setRecipientSnapshotJson(objectMapper.writeValueAsString(List.of(recipient)));
        runMapper.updateById(run);
        when(recipientPreviewService.previewForRun(any(ReportRun.class))).thenReturn(preview);
    }

    private ReportRun insertRun(String suffix) {
        ReportRun run = new ReportRun();
        run.setTenantId("default");
        run.setRunKey("nf10-mysql-" + suffix + "-" + System.nanoTime());
        run.setTemplateId(1L);
        run.setTemplateVersionId(1L);
        run.setSnapshotVersion(1);
        run.setPrincipalType("SYSTEM_PRINCIPAL");
        run.setScopeOwnerType("COMPANY");
        run.setOrganizationScopeJson("{}");
        run.setScopePolicyVersion("mysql-test-policy");
        run.setScopeHash("mysql-run-scope");
        run.setPeriodFrom(LocalDate.of(2026, 8, 1));
        run.setPeriodTo(LocalDate.of(2026, 8, 31));
        run.setCutoffKind("GENERATED_AT");
        run.setAsOfAt(LocalDateTime.now());
        run.setTimezoneId("Asia/Tokyo");
        run.setStatus("SUCCEEDED");
        run.setSnapshotSchemaVersion("report-1.0");
        runMapper.insert(run);
        runIds.add(run.getId());
        return run;
    }

    private DocumentVersion insertDocument(String businessKey) {
        Document document = new Document();
        document.setTenantId("default");
        document.setDocumentType("MANAGEMENT_REPORT");
        document.setTitle("NF10 MySQL artifact");
        document.setCurrency("JPY");
        document.setDirection("INTERNAL");
        document.setStatus("CONFIRMED");
        document.setLegalHoldFlag(0);
        document.setCreatedBy(1L);
        documentMapper.insert(document);
        documentIds.add(document.getId());

        DocumentVersion version = new DocumentVersion();
        version.setTenantId("default");
        version.setDocumentId(document.getId());
        version.setVersionNo(1);
        version.setStorageKey("reports/nf10-mysql/" + businessKey + ".pdf");
        version.setOriginalName("nf10-mysql.pdf");
        version.setContentType("application/pdf");
        version.setSizeBytes(16L);
        version.setSha256("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        version.setSourceType("GENERATED");
        version.setBusinessKey("nf10-mysql-artifact-" + businessKey);
        version.setVersionDiscriminator("1");
        version.setScanStatus("CLEAN");
        version.setCreatedBy(1L);
        documentVersionMapper.insert(version);
        return version;
    }

    private ReportDelivery insertDelivery(ReportRun run, DocumentVersion version, String status) {
        ReportDelivery delivery = new ReportDelivery();
        delivery.setTenantId("default");
        delivery.setRunId(run.getId());
        delivery.setDocumentId(version.getDocumentId());
        delivery.setDocumentVersionNo(version.getVersionNo());
        delivery.setRecipientUserId(1L);
        delivery.setRecipientScopeJson("{\"recipientUserId\":1}");
        delivery.setRecipientScopeHash("scope-mysql");
        delivery.setPreviewStatus("ALLOWED");
        delivery.setPreviewedAt(LocalDateTime.now());
        delivery.setScopeDecision("ALLOW");
        delivery.setDeliveryChannel("IN_APP_LINK");
        delivery.setDeliveryStatus(status);
        delivery.setNotificationDedupeKey("NF10-MYSQL:" + run.getId() + ":" + status);
        delivery.setAttemptCount(1);
        deliveryMapper.insert(delivery);
        return delivery;
    }
}
