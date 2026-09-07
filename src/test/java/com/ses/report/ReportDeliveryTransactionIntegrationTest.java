package com.ses.report;

import com.ses.dto.report.ReportRecipientPreview;
import com.ses.entity.ReportDelivery;
import com.ses.entity.ReportRun;
import com.ses.mapper.ReportDeliveryMapper;
import com.ses.mapper.ReportRunMapper;
import com.ses.service.report.ReportDeliveryIssueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * ReportDeliveryIssueServiceのSpring proxy経由で、outbox成功後のdelivery最終更新失敗時に
 * delivery/outboxが半端に残らないことを実H2トランザクションで検証する。
 */
@SpringBootTest
@ActiveProfiles("test")
class ReportDeliveryTransactionIntegrationTest {

    @Autowired
    private ReportDeliveryIssueService deliveryIssueService;
    @Autowired
    private ReportRunMapper runMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @SpyBean
    private ReportDeliveryMapper deliveryMapper;

    private Long runId;
    private Long recipientUserId;

    @BeforeEach
    void setUp() {
        Mockito.reset(deliveryMapper);
        recipientUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = 'admin' AND deleted_flag = 0", Long.class);
        runId = insertSucceededRun();
        doAnswer(invocation -> {
            ReportDelivery delivery = invocation.getArgument(0);
            if ("ENQUEUED".equals(delivery.getDeliveryStatus()) && delivery.getNotificationOutboxId() != null) {
                throw new RuntimeException("delivery最終更新の意図的失敗");
            }
            return invocation.callRealMethod();
        }).when(deliveryMapper).updateById(any(ReportDelivery.class));
    }

    @Test
    void outbox成功後のdelivery最終更新失敗はdeliveryとoutboxを同一TXでrollbackする() {
        int outboxBefore = countOutbox();
        ReportRun run = runMapper.selectById(runId);
        ReportRecipientPreview recipient = new ReportRecipientPreview(
                recipientUserId, "管理者", "ALLOW", "SCOPE_MATCH", "scope-hash");

        assertThatThrownBy(() -> deliveryIssueService.issue(run, null, recipient, null))
                .hasMessageContaining("delivery最終更新の意図的失敗");

        assertThat(countDeliveries(runId)).isZero();
        assertThat(countOutbox()).isEqualTo(outboxBefore);
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

    private int countOutbox() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_notification_outbox", Integer.class);
        return count == null ? 0 : count;
    }
}
