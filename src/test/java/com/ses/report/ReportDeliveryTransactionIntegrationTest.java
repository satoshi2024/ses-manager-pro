package com.ses.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.dto.report.ReportDocumentArtifact;
import com.ses.dto.report.ReportRecipientPreview;
import com.ses.dto.report.ReportRecipientPreviewResult;
import com.ses.entity.Document;
import com.ses.entity.DocumentVersion;
import com.ses.entity.ReportDelivery;
import com.ses.entity.ReportRun;
import com.ses.mapper.ReportDeliveryMapper;
import com.ses.mapper.ReportRunMapper;
import com.ses.service.DocumentService;
import com.ses.service.NotificationService;
import com.ses.service.report.ReportDeliveryDocumentRegistrar;
import com.ses.service.report.ReportDeliveryIssueService;
import com.ses.service.report.ReportDeliveryService;
import com.ses.service.report.ReportRecipientPreviewService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * ReportDeliveryIssueService / ReportDeliveryServiceのトランザクション境界検証。
 * 1. outbox成功後のdelivery最終更新失敗時にdelivery/outboxが同一TXでrollbackされること。
 * 2. 通知失敗時にUnexpectedRollbackなく配布行をRETRYへ確定しPROCESSINGを残さないこと。
 * クラスTXは被測のrollback境界を壊すため付けず、@AfterEachで本テスト専用行のみ明示削除する。
 */
@SpringBootTest
@ActiveProfiles("test")
class ReportDeliveryTransactionIntegrationTest {

    @Autowired
    private ReportDeliveryIssueService deliveryIssueService;
    @Autowired
    private ReportDeliveryService reportDeliveryService;
    @Autowired
    private ReportRunMapper runMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @SpyBean
    private ReportDeliveryMapper deliveryMapper;

    @MockBean
    private NotificationService notificationService;
    @MockBean
    private ReportRecipientPreviewService recipientPreviewService;
    @MockBean
    private ReportDeliveryDocumentRegistrar documentRegistrar;
    @MockBean
    private DocumentService documentService;

    private Long runId;
    private Long recipientUserId;

    @BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId("default");
        Mockito.reset(deliveryMapper);
        recipientUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = 'admin' AND deleted_flag = 0", Long.class);
        runId = insertSucceededRun();
    }

    @AfterEach
    void cleanupTestData() {
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
        if (runId != null) {
            String notificationDedupeKey = notificationDedupeKey(runId, recipientUserId);
            jdbcTemplate.update("DELETE FROM t_notification_outbox WHERE dedupe_key = ?", notificationDedupeKey);
            jdbcTemplate.update("DELETE FROM t_notification WHERE dedupe_key = ?", notificationDedupeKey);
            jdbcTemplate.update("DELETE FROM t_report_delivery WHERE run_id = ?", runId);
            jdbcTemplate.update("DELETE FROM t_report_run WHERE id = ?", runId);
        }
    }

    @Test
    void outbox成功後のdelivery最終更新失敗はdeliveryとoutboxを同一TXでrollbackする() {
        doAnswer(invocation -> {
            ReportDelivery delivery = invocation.getArgument(0);
            if ("ENQUEUED".equals(delivery.getDeliveryStatus()) && delivery.getNotificationOutboxId() != null) {
                throw new RuntimeException("delivery最終更新の意図的失敗");
            }
            return invocation.callRealMethod();
        }).when(deliveryMapper).update(any(ReportDelivery.class), any());

        String notificationDedupeKey = notificationDedupeKey(runId, recipientUserId);
        ReportRun run = runMapper.selectById(runId);
        ReportRecipientPreview recipient = new ReportRecipientPreview(
                recipientUserId, "管理者", "ALLOW", "SCOPE_MATCH", "scope-hash");

        assertThatThrownBy(() -> deliveryIssueService.issue(run, null, recipient, null))
                .hasMessageContaining("delivery最終更新の意図的失敗");

        assertThat(countDeliveries(runId)).isZero();
        assertThat(countNotifications(notificationDedupeKey)).isZero();
        assertThat(countOutbox(notificationDedupeKey)).isZero();
    }

    @Test
    void 通知失敗でもUnexpectedRollbackなく配布をRETRYへ確定しPROCESSINGを残さない() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(recipientUserId), "N/A",
                        List.of(new SimpleGrantedAuthority("ROLE_管理者"))));
        ReportRecipientPreview recipient = new ReportRecipientPreview(
                recipientUserId, "管理者", "ALLOW", "SCOPE_MATCH", "scope-hash");
        ReportRecipientPreviewResult preview = new ReportRecipientPreviewResult(
                "preview-hash", "APPROVED_SCOPE_CHECKED", LocalDateTime.now(), List.of(recipient));

        ReportRun run = runMapper.selectById(runId);
        run.setRecipientPreviewHash("preview-hash");
        run.setRecipientSnapshotJson(new ObjectMapper().writeValueAsString(List.of(recipient)));
        runMapper.updateById(run);

        Document document = new Document();
        document.setId(9901L);
        document.setTenantId("default");
        DocumentVersion version = new DocumentVersion();
        version.setVersionNo(1);
        version.setTenantId("default");
        when(recipientPreviewService.previewForRun(any(ReportRun.class))).thenReturn(preview);
        when(documentRegistrar.registerArtifact(run.getId(), "PDF"))
                .thenReturn(new ReportDocumentArtifact(run.getId(), "PDF", "artifact-hash", document, version));
        when(documentService.getVersionStorageKey(anyLong(), anyInt())).thenReturn("published/report.pdf");
        doThrow(new IllegalStateException("notification database failure"))
                .when(notificationService).publishToUserAndGetOutboxIdWithoutDispatch(
                        anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        assertThatCode(() -> reportDeliveryService.deliverUser(run.getId(), "preview-hash"))
                .doesNotThrowAnyException();

        ReportDelivery delivery = deliveryMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ReportDelivery>()
                .eq("run_id", run.getId()));
        assertThat(delivery).isNotNull();
        assertThat(delivery.getDeliveryStatus()).isEqualTo("RETRY");
        assertThat(delivery.getLastErrorCode()).isEqualTo("DELIVERY_FAILED");
        assertThat(delivery.getDocumentId()).isEqualTo(9901L);
        assertThat(delivery.getLinkTokenHash()).isNotBlank();
    }

    @Test
    void issueは通知linkへrawTokenを載せない() {
        when(notificationService.publishToUserAndGetOutboxIdWithoutDispatch(
                anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String link = invocation.getArgument(4);
                    assertThat(link).contains("/api/management-reports/deliveries/")
                            .doesNotContain("token=")
                            .doesNotContain("?");
                    return 555L;
                });

        ReportRun run = runMapper.selectById(runId);
        ReportRecipientPreview recipient = new ReportRecipientPreview(
                recipientUserId, "管理者", "ALLOW", "SCOPE_MATCH", "scope-hash");

        ReportDelivery delivery = deliveryIssueService.issue(run, null, recipient, null);

        assertThat(delivery.getDeliveryStatus()).isEqualTo("ENQUEUED");
        assertThat(delivery.getLinkTokenHash()).hasSize(64);
        assertThat(delivery.getNotificationOutboxId()).isEqualTo(555L);
    }

    private static String deliveryDedupeKey(Long runId, Long recipientUserId) {
        return "REPORT:" + runId + ":" + recipientUserId + ":a1";
    }

    private static String notificationDedupeKey(Long runId, Long recipientUserId) {
        return deliveryDedupeKey(runId, recipientUserId) + "#u" + recipientUserId;
    }

    private long insertSucceededRun() {
        jdbcTemplate.update("""
                INSERT INTO t_report_run (
                    tenant_id, run_key, template_id, template_version_id, schedule_id,
                    snapshot_version, principal_type, principal_user_id,
                    scope_owner_type, scope_owner_id, organization_scope_json,
                    scope_policy_version, scope_hash, period_from, period_to,
                    cutoff_kind, as_of_at, timezone_id, data_as_of_at, status,
                    snapshot_schema_version, source_policy_hash, generated_at
                ) VALUES (
                    'default', ?, 1, 1, 5,
                    1, 'SYSTEM_PRINCIPAL', ?,
                    'COMPANY', 1, '{"companyWide":true,"organizationIds":[],"directUserIds":[]}',
                    'v1', 'scope-hash', '2026-08-01', '2026-08-31',
                    '速報', CURRENT_TIMESTAMP, 'Asia/Tokyo', CURRENT_TIMESTAMP, 'SUCCEEDED',
                    'report-1.0', 'policy-hash', CURRENT_TIMESTAMP
                )
                """,
                "tx-test-" + System.nanoTime(), recipientUserId);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM t_report_run", Long.class);
    }

    private int countDeliveries(Long runId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_report_delivery WHERE run_id = ?", Integer.class, runId);
        return count == null ? 0 : count;
    }

    private int countNotifications(String dedupeKey) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_notification WHERE dedupe_key = ?", Integer.class, dedupeKey);
        return count == null ? 0 : count;
    }

    private int countOutbox(String dedupeKey) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_notification_outbox WHERE dedupe_key = ?", Integer.class, dedupeKey);
        return count == null ? 0 : count;
    }
}
