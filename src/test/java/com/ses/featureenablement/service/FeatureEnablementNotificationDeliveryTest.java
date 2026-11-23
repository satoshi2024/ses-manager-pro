package com.ses.featureenablement.service;

import com.ses.config.feature.Feature;
import com.ses.entity.FeatureSignOff;
import com.ses.featureenablement.FeatureEnablementH2Fixture;
import com.ses.featureenablement.FeatureEnablementTestConfiguration;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.signoff.FeatureSignOffPurgeScheduler;
import com.ses.featureenablement.state.FeatureEnablementSnapshotHolder;
import com.ses.service.notification.NotificationOutboxService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * review rb-fe-a1 P2-2: commit の後の通知と、消去の後の文書の実体・metadata の削除は、REQUIRES_NEW の新しい transaction で書く
 * （afterCommit の中の REQUIRED は完了済みの transaction に参加し、通知の outbox の即時配信の afterCommit が呼ばれない）。本当に
 * commit する経路を確かめるため、本 class は試験の transaction を持たず専用の H2 DB を使い、入れた行を {@code @AfterEach} で消す
 * （{@code TestIsolationAuditTest} の allowlist）。
 */
@SpringBootTest(properties = "app.feature-enablement.source=database")
@ActiveProfiles("test")
@Import(FeatureEnablementTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FeatureEnablementNotificationDeliveryTest {

    private static final String TENANT = FeatureEnablementH2Fixture.TENANT;
    private static final LocalDate SIGNED = LocalDate.of(2026, 11, 1);
    private static final LocalDate EXPIRES = LocalDate.of(2027, 10, 1);

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        String database = "fe_delivery_" + UUID.randomUUID().toString().replace("-", "");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + database
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL");
    }

    @SpyBean
    private NotificationOutboxService outbox;
    @Autowired
    private FeatureEnablementService service;
    @Autowired
    private FeatureSignOffService signOffs;
    @Autowired
    private FeatureSignOffPurgeScheduler purgeScheduler;
    @Autowired
    private FeatureEnablementSnapshotHolder holder;
    @Autowired
    private FeatureEnablementCatalog catalog;
    @Autowired
    private FeatureEnablementTestConfiguration.MutableTestClock clock;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> users = new ArrayList<>();
    private FeatureEnablementH2Fixture fixture;

    @BeforeEach
    void setUp() {
        clock.reset();
        fixture = new FeatureEnablementH2Fixture(jdbc, catalog);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM t_notification_outbox WHERE notification_id IN (SELECT id FROM t_notification "
                + "WHERE type = 'FEATURE_ENABLEMENT')");
        jdbc.update("DELETE FROM t_notification WHERE type = 'FEATURE_ENABLEMENT'");
        jdbc.update("DELETE FROM t_feature_enablement_state WHERE tenant_id = ?", TENANT);
        jdbc.update("DELETE FROM t_feature_enablement_request WHERE tenant_id = ?", TENANT);
        jdbc.update("DELETE FROM t_feature_sign_off_bundle_member WHERE tenant_id = ?", TENANT);
        jdbc.update("DELETE FROM t_feature_sign_off_bundle WHERE tenant_id = ?", TENANT);
        jdbc.update("DELETE FROM t_feature_sign_off WHERE tenant_id = ?", TENANT);
        jdbc.update("DELETE FROM t_feature_enablement_event WHERE tenant_id = ?", TENANT);
        jdbc.update("DELETE FROM t_feature_enablement_generation WHERE tenant_id = ?", TENANT);
        jdbc.update("DELETE FROM t_audit_log WHERE method = 'FEAT_EN'");
        jdbc.update("DELETE FROM t_file_security_metadata WHERE file_kind = 'FEATURE_SIGN_OFF'");
        for (Long id : users) {
            jdbc.update("DELETE FROM sys_user WHERE id = ?", id);
        }
        users.clear();
        clock.reset();
        holder.reload();
    }

    @Test
    void commitの後の通知は新しいtransactionで書きoutboxの即時配信が動く() {
        FeatureEnablementActor requester = admin();
        FeatureEnablementActor approver = admin();
        fixture.confirmedSignOffs(Feature.NF03, SIGNED, EXPIRES);

        service.requestOpen(Feature.NF03, null, requester);

        assertThat(count("SELECT COUNT(*) FROM t_notification WHERE type = 'FEATURE_ENABLEMENT' AND recipient_user_id = ?",
                approver.userId())).as("承認の依頼は申請者以外の管理者へ commit される").isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM t_notification WHERE type = 'FEATURE_ENABLEMENT' AND recipient_user_id = ?",
                requester.userId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM t_notification_outbox WHERE notification_id IN (SELECT id FROM "
                + "t_notification WHERE type = 'FEATURE_ENABLEMENT' AND recipient_user_id = ?)", approver.userId()))
                .as("outbox に積まれる").isEqualTo(1);
        verify(outbox, atLeastOnce()).dispatchOne(anyLong());
    }

    @Test
    void 消去の後の文書の実体とmetadataの削除は新しいtransactionでcommitされる() {
        FeatureEnablementActor registrant = admin();
        FeatureEnablementActor reviewer = admin();
        FeatureSignOff signOff = signOffs.register(new FeatureSignOffService.Registration("NF04_SRE", "SRE-2026-1101",
                "SRE", null, SIGNED, LocalDate.of(2027, 11, 1), Map.of()),
                "%PDF-1.7\n1 0 obj<<>>endobj\n% delivery\n%%EOF\n".getBytes(StandardCharsets.US_ASCII), "sre.pdf",
                registrant);
        String stored = signOff.getStoredName();
        signOffs.reject(signOff.getId(), 0, "CONTENT_MISMATCH", reviewer);
        assertThat(count("SELECT COUNT(*) FROM t_file_security_metadata WHERE stored_name = ? AND deleted_flag = 0",
                stored)).isEqualTo(1);

        clock.set(Instant.parse("2029-11-23T01:00:00Z"));
        assertThat(purgeScheduler.purgeDue()).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT status FROM t_feature_sign_off WHERE id = ?", String.class,
                signOff.getId())).isEqualTo("PURGED");
        assertThat(count("SELECT COUNT(*) FROM t_file_security_metadata WHERE stored_name = ? AND deleted_flag = 0",
                stored)).as("commit の後に metadata を論理削除した").isZero();
    }

    private FeatureEnablementActor admin() {
        FeatureEnablementActor actor = fixture.admin();
        users.add(actor.userId());
        return actor;
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
