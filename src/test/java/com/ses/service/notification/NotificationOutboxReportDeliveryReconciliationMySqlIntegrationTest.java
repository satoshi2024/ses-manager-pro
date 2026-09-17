package com.ses.service.notification;

import com.ses.entity.NotificationOutbox;
import com.ses.entity.ReportDelivery;
import com.ses.mapper.NotificationOutboxMapper;
import com.ses.mapper.ReportDeliveryMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.report.ReportDeliveryNotificationBridge;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.mockito.ArgumentMatchers;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/** report delivery同期失敗がoutboxの永続reconciliationへ収束することをMySQLで確認する。 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class NotificationOutboxReportDeliveryReconciliationMySqlIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_nf10_reconciliation_mysql")
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
    private NotificationOutboxDispatcher dispatcher;

    @Autowired
    private ReportDeliveryNotificationBridge notificationBridge;

    @Autowired
    private ReportDeliveryMapper reportDeliveryMapper;

    @SpyBean
    private ReportDeliveryMapper reportDeliveryMapperSpy;

    @Autowired
    private NotificationOutboxMapper notificationOutboxMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SqlSessionTemplate sqlSessionTemplate;

    @MockBean
    private WebhookNotifier webhookNotifier;

    private Long notificationId;
    private Long outboxId;
    private Long deliveryId;
    private String dedupeKey;
    private final AtomicBoolean failSynchronization = new AtomicBoolean(true);

    @BeforeEach
    void setUp() {
        when(webhookNotifier.notifyNow(ArgumentMatchers.any())).thenReturn(true);
        AccountingTenantContextHolder.setTenantId("default");
        doAnswer(invocation -> {
            if (failSynchronization.getAndSet(false)) {
                throw new IllegalStateException("テスト用の同期失敗");
            }
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("tenantId", invocation.getArgument(0));
            parameters.put("outboxId", invocation.getArgument(1));
            parameters.put("status", invocation.getArgument(2));
            parameters.put("errorCode", invocation.getArgument(3));
            parameters.put("errorMessage", invocation.getArgument(4));
            return sqlSessionTemplate.update(
                    ReportDeliveryMapper.class.getName() + ".syncOutboxStatus", parameters);
        }).when(reportDeliveryMapperSpy).syncOutboxStatus(
                ArgumentMatchers.anyString(), ArgumentMatchers.anyLong(), ArgumentMatchers.anyString(),
                ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @AfterEach
    void cleanupFixtures() {
        AccountingTenantContextHolder.clear();
        if (deliveryId != null) {
            jdbcTemplate.update("DELETE FROM t_report_delivery WHERE id = ?", deliveryId);
        }
        if (outboxId != null) {
            jdbcTemplate.update("DELETE FROM t_notification_outbox WHERE id = ?", outboxId);
        }
        if (notificationId != null) {
            jdbcTemplate.update("DELETE FROM t_notification WHERE id = ?", notificationId);
        }
    }

    @Test
    void 同期失敗はcommit後にreconciliationされ二重配布されない() {
        String fixtureKey = "nf10-reconciliation-" + UUID.randomUUID();
        dedupeKey = fixtureKey;

        ReportDelivery delivery = new ReportDelivery();
        delivery.setTenantId("default");
        delivery.setRunId(Math.abs(fixtureKey.hashCode()) + 1L);
        delivery.setRecipientUserId(1L);
        delivery.setRecipientScopeJson("{\"recipientUserId\":1,\"fixture\":\"" + fixtureKey + "\"}");
        delivery.setRecipientScopeHash("scope-" + fixtureKey);
        delivery.setPreviewStatus("ALLOWED");
        delivery.setPreviewedAt(LocalDateTime.now());
        delivery.setScopeDecision("ALLOW");
        delivery.setDeliveryChannel("IN_APP_LINK");
        delivery.setDeliveryStatus("ENQUEUED");
        delivery.setNotificationDedupeKey(dedupeKey);
        delivery.setReauthRequired(1);
        delivery.setAttemptCount(1);
        reportDeliveryMapper.insert(delivery);
        deliveryId = delivery.getId();

        String actionUrl = "/api/management-reports/deliveries/" + deliveryId + "/download";
        Long createdOutboxId = notificationBridge.publish(
                1L, "MANAGEMENT_REPORT", "月次管理レポート",
                "snapshotを確認できます（ダウンロード時に再認証が必要です）。",
                actionUrl, dedupeKey, "management-report");
        assertThat(createdOutboxId).isNotNull();
        outboxId = createdOutboxId;

        ReportDelivery linked = reportDeliveryMapper.selectById(deliveryId);
        linked.setNotificationOutboxId(outboxId);
        assertThat(reportDeliveryMapper.updateById(linked)).isEqualTo(1);
        notificationId = notificationOutboxMapper.selectById(outboxId).getNotificationId();

        // dispatchOneは独立transactionでclaim・SENT更新・reconciliation flag保存までcommitする。
        assertThat(dispatcher.dispatchOne(outboxId)).isFalse();

        assertCommittedState("ENQUEUED", "SENT", 1);

        // 同期依存は一回限りで復旧しており、reconcilePendingは別のREQUIRES_NEW transactionで実行される。
        assertThat(dispatcher.reconcilePending()).isEqualTo(1);
        assertCommittedState("SENT", "SENT", 0);

        // 同じdedupeでの再publishと再reconcileは通知・outbox・deliveryを増やさない。
        assertThat(notificationBridge.publish(
                1L, "MANAGEMENT_REPORT", "月次管理レポート",
                "snapshotを確認できます（ダウンロード時に再認証が必要です）。",
                actionUrl, dedupeKey, "management-report")).isNull();
        assertThat(dispatcher.reconcilePending()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_report_delivery WHERE id = ?", Integer.class, deliveryId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_notification WHERE dedupe_key = ?", Integer.class, dedupeKey + "#u1"))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_notification_outbox WHERE dedupe_key = ?", Integer.class, dedupeKey + "#u1"))
                .isEqualTo(1);
    }

    private void assertCommittedState(String expectedDeliveryStatus, String expectedOutboxStatus,
                                      int expectedReconciliationRequired) {
        ReportDelivery committedDelivery = reportDeliveryMapper.selectById(deliveryId);
        NotificationOutbox committedOutbox = notificationOutboxMapper.selectById(outboxId);
        assertThat(committedDelivery.getDeliveryStatus()).isEqualTo(expectedDeliveryStatus);
        assertThat(committedOutbox.getStatus()).isEqualTo(expectedOutboxStatus);
        assertThat(committedOutbox.getReconciliationRequired()).isEqualTo(expectedReconciliationRequired);

        String lastError = jdbcTemplate.queryForObject(
                "SELECT last_error FROM t_notification_outbox WHERE id = ?", String.class, outboxId);
        assertThat(lastError).doesNotContain("token=")
                .doesNotContain("delivery:")
                .doesNotContain("payload")
                .doesNotContain("response")
                .doesNotContain(fixturePiiMarker());

        String notificationLink = jdbcTemplate.queryForObject(
                "SELECT link_url FROM t_notification WHERE id = ?", String.class, notificationId);
        String outboxLink = jdbcTemplate.queryForObject(
                "SELECT link_url FROM t_notification_outbox WHERE id = ?", String.class, outboxId);
        assertThat(notificationLink).isEqualTo(outboxLink)
                .contains("/api/management-reports/deliveries/")
                .doesNotContain("token=")
                .doesNotContain("delivery:");
    }

    private String fixturePiiMarker() {
        return dedupeKey == null ? "" : dedupeKey;
    }
}
