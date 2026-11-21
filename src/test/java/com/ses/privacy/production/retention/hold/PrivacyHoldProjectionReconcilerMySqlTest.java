package com.ses.privacy.production.retention.hold;

import com.ses.common.exception.BusinessException;
import com.ses.migration.PrivacyJournalFlywayJobConfig;
import com.ses.privacy.foundation.PrivacyJournalPort;
import com.ses.privacy.foundation.ProductionPrivacyComponents;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.MySQLContainer;
import com.ses.test.MySqlTestImages;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NF07 hold の reconciler の実 MySQL（A1H-1、production-bridge-phased-plan.md §3.4.4、§3.4 Demo 6）。
 * pending 残り＋ACK 済み → projection して復旧、pending 残り＋未 append → timeout 前は残し timeout 後に解除、
 * Journal 不達 → pending を残す、projection 欠落の追い付き、を本番 adapter と本番の索引で確かめる。時刻は可変の時計で進める。
 */
@Tag("mysql")
@Testcontainers
class PrivacyHoldProjectionReconcilerMySqlTest {

    private static final String ROOT_PASSWORD = UUID.randomUUID().toString();
    private static final String JOURNAL_ID = "journal-a1h-reconcile";
    private static final java.time.Instant T0 = java.time.Instant.parse("2026-11-16T03:00:00Z");
    /** 本番 adapter の event 時刻の時計（進めない）。ケースの時刻はケースごとの {@link #clock} で進める（Review P3-10）。 */
    private static final PrivacyHoldTestFixture.MutableClock ADAPTER_CLOCK = new PrivacyHoldTestFixture.MutableClock(T0);

    private PrivacyHoldTestFixture.MutableClock clock;

    @Container
    static final MySQLContainer<?> JOURNAL = new MySQLContainer<>(MySqlTestImages.image())
            .withDatabaseName(PrivacyJournalTestPrincipals.SCHEMA)
            .withUsername("root")
            .withPassword(ROOT_PASSWORD)
            .withCommand("--log-bin=mysql-bin", "--sync-binlog=1", "--innodb-flush-log-at-trx-commit=1");

    @Container
    static final com.ses.test.MySQLContainer<?> APP = new com.ses.test.MySQLContainer<>(MySqlTestImages.image())
            .withDatabaseName("ses_manager_db")
            .withUsername("root")
            .withPassword(ROOT_PASSWORD);

    private static HikariDataSource appPool;
    private static HikariDataSource appenderPool;
    private static JdbcTemplate journalRoot;
    private static PrivacyJournalPort production;
    private static String appender;
    private static String appenderPassword;

    private PrivacyHoldMySqlComponents components;

    @BeforeAll
    static void startContainers() {
        Flyway.configure().dataSource(APP.getJdbcUrl(), "root", ROOT_PASSWORD)
                .locations("classpath:db/migration").load().migrate();
        PrivacyJournalFlywayJobConfig.configure(JOURNAL.getJdbcUrl(), "root", ROOT_PASSWORD).load().migrate();
        journalRoot = new JdbcTemplate(new DriverManagerDataSource(JOURNAL.getJdbcUrl(), "root", ROOT_PASSWORD));
        appender = PrivacyJournalTestPrincipals.uniqueName("nf07_rcn");
        appenderPassword = PrivacyJournalTestPrincipals.randomPassword();
        PrivacyJournalTestPrincipals.createAppender(journalRoot, appender, appenderPassword);
        appenderPool = PrivacyJournalTestPools.pool(JOURNAL.getJdbcUrl(), appender, appenderPassword);
        appPool = new HikariDataSource();
        appPool.setJdbcUrl(APP.getJdbcUrl());
        appPool.setUsername("root");
        appPool.setPassword(ROOT_PASSWORD);
        appPool.setMaximumPoolSize(6);
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        production = ProductionPrivacyComponents.productionPrivacyJournal(prod, appenderPool, ADAPTER_CLOCK);
    }

    @AfterAll
    static void closePools() {
        if (appPool != null) {
            appPool.close();
        }
        if (appenderPool != null) {
            appenderPool.close();
        }
    }

    @BeforeEach
    void setUp() {
        clock = new PrivacyHoldTestFixture.MutableClock(T0);
        components = newComponents("nf07-rcn-" + UUID.randomUUID().toString().substring(0, 8), appenderPool);
        components.seedCatalog();
    }

    @Test
    void pendingが残りJournalでACK済みならprojectionを書いてpendingを消しgateが復帰する() {
        // append は確定したが ACK を2回とも失い（再送も ACK 不明）、projection を書けずに終わった開始
        components.port.loseAckAfterCommit.set(2);
        assertThatThrownBy(() -> components.service.start(components.subject("ENGINEER:601")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(503));
        assertThat(components.gate.evaluate(components.tenantId).reason()).isEqualTo(PrivacyHoldGate.REASON_PENDING);
        assertThat(components.count("SELECT COUNT(*) FROM t_legal_hold WHERE tenant_id = ?", components.tenantId)).isZero();

        List<PrivacyHoldProjectionReconciler.Outcome> outcomes = components.reconciler.reconcileTenant(components.tenantId);

        assertThat(outcomes).contains(PrivacyHoldProjectionReconciler.Outcome.PENDING_PROJECTED);
        assertThat(components.store.stateRows(components.tenantId).get(0).pendingCommandId()).isNull();
        assertThat(components.app.queryForObject("SELECT reason_code FROM t_legal_hold WHERE tenant_id = ?", String.class,
                components.tenantId)).isEqualTo(PrivacyHoldService.RECONCILED);
        PrivacyHoldGate.Decision decision = components.gate.evaluate(components.tenantId);
        assertThat(decision.state()).isEqualTo(PrivacyHoldGate.State.VERIFIED);
        assertThat(decision.heldSubjects()).containsExactly("ENGINEER:601");
        assertThat(components.auditUris).anyMatch(uri -> uri.contains(PrivacyHoldAudit.RECONCILE_PROJECTED));
    }

    @Test
    void pendingが残りJournalにcommandが無ければtimeout前は残しtimeout後に消す() {
        components.store.ensureStateLocked(components.tenantId, JOURNAL_ID, clock.instant());
        String commandId = PrivacyHoldPartitions.newIds().commandId();
        components.store.recordPending(components.tenantId, JOURNAL_ID, commandId, clock.instant());

        assertThat(components.reconciler.reconcileTenant(components.tenantId))
                .contains(PrivacyHoldProjectionReconciler.Outcome.PENDING_KEPT);
        assertThat(components.store.stateRows(components.tenantId).get(0).pendingCommandId()).isEqualTo(commandId);

        // pending-timeout（2分）+ max-skew（1秒）を過ぎてから、消す直前にもう一度照会して消す
        clock.advance(Duration.ofMinutes(2).plusSeconds(2));
        assertThat(components.reconciler.reconcileTenant(components.tenantId))
                .contains(PrivacyHoldProjectionReconciler.Outcome.PENDING_ABANDONED);
        assertThat(components.store.stateRows(components.tenantId).get(0).pendingCommandId()).isNull();
        assertThat(components.gate.evaluate(components.tenantId).state()).isEqualTo(PrivacyHoldGate.State.VERIFIED);
        assertThat(components.auditUris).anyMatch(uri -> uri.contains(PrivacyHoldAudit.RECONCILE_ABANDONED));
    }

    @Test
    void pending_timeoutが未設定ならJournalにcommandが無くてもpendingを消さない() {
        PrivacyHoldMySqlComponents unset = new PrivacyHoldMySqlComponents(components.tenantId, appPool, production,
                appenderPool, JOURNAL_ID, null, clock);
        unset.store.ensureStateLocked(components.tenantId, JOURNAL_ID, clock.instant());
        String commandId = PrivacyHoldPartitions.newIds().commandId();
        unset.store.recordPending(components.tenantId, JOURNAL_ID, commandId, clock.instant().minus(Duration.ofHours(1)));

        assertThat(unset.reconciler.reconcileTenant(components.tenantId))
                .contains(PrivacyHoldProjectionReconciler.Outcome.PENDING_KEPT);
        assertThat(unset.store.stateRows(components.tenantId).get(0).pendingCommandId()).isEqualTo(commandId);
    }

    @Test
    void Journalに届かなければpendingを残しgateはUNAVAILABLEのまま() {
        components.store.ensureStateLocked(components.tenantId, JOURNAL_ID, clock.instant());
        String commandId = PrivacyHoldPartitions.newIds().commandId();
        components.store.recordPending(components.tenantId, JOURNAL_ID, commandId, clock.instant().minus(Duration.ofHours(1)));
        HikariDataSource closed = PrivacyJournalTestPools.pool(JOURNAL.getJdbcUrl(), appender, appenderPassword);
        closed.close();
        PrivacyHoldMySqlComponents unreachable = newComponents(components.tenantId, closed);

        // 索引に届かない回は判定せずに中断する（reconcileAll はこの例外を捕えて次回に再試行する）
        assertThatThrownBy(() -> unreachable.reconciler.reconcileTenant(components.tenantId))
                .isInstanceOf(com.ses.privacy.foundation.JournalUnavailableException.class);
        assertThat(components.store.stateRows(components.tenantId).get(0).pendingCommandId()).isEqualTo(commandId);
        assertThat(unreachable.gate.evaluate(components.tenantId).state()).isEqualTo(PrivacyHoldGate.State.UNAVAILABLE);

        unreachable.startupResult = new PrivacyJournalStartupVerifier.Result(
                PrivacyJournalStartupVerifier.Availability.UNAVAILABLE, java.util.List.of("connection.failed"));
        assertThat(unreachable.reconciler.reconcileAll())
                .containsEntry(PrivacyHoldProjectionReconciler.Outcome.JOURNAL_UNAVAILABLE, 1);
        assertThat(components.store.stateRows(components.tenantId).get(0).pendingCommandId()).isEqualTo(commandId);
    }

    @Test
    void projectionに無いJournalのholdを追い付いて書きgateが復帰する() {
        components.service.start(components.subject("ENGINEER:602"));
        // pending を消した後に遅れて確定した append（または projection の欠落）の再現
        PrivacyHoldPartitions.Ids ids = PrivacyHoldPartitions.newIds();
        PrivacyJournalPort.JournalPartition partition =
                new PrivacyJournalPort.JournalPartition(components.tenantId, "ENGINEER:603");
        production.append(PrivacyJournalPort.JournalCommand.create(ids.commandId(), partition, 0,
                PrivacyJournalPort.CommandType.HOLD_STARTED, ids.holdId(), null, "d".repeat(64), null, 0, null));
        assertThat(components.gate.evaluate(components.tenantId).reason()).isEqualTo(PrivacyHoldGate.REASON_PROJECTION_LAG);

        assertThat(components.reconciler.reconcileTenant(components.tenantId))
                .contains(PrivacyHoldProjectionReconciler.Outcome.CAUGHT_UP);

        PrivacyHoldGate.Decision decision = components.gate.evaluate(components.tenantId);
        assertThat(decision.state()).isEqualTo(PrivacyHoldGate.State.VERIFIED);
        assertThat(decision.heldSubjects()).containsExactlyInAnyOrder("ENGINEER:602", "ENGINEER:603");
        assertThat(components.reconciler.reconcileTenant(components.tenantId)).as("再実行で二重に書かない")
                .doesNotContain(PrivacyHoldProjectionReconciler.Outcome.CAUGHT_UP)
                .contains(PrivacyHoldProjectionReconciler.Outcome.CONSISTENT);
        assertThat(components.count("SELECT COUNT(*) FROM t_legal_hold WHERE tenant_id = ?", components.tenantId))
                .isEqualTo(2);
    }

    private PrivacyHoldMySqlComponents newComponents(String tenantId, javax.sql.DataSource journalDataSource) {
        return new PrivacyHoldMySqlComponents(tenantId, appPool, production, journalDataSource, JOURNAL_ID,
                Duration.ofMinutes(2), clock);
    }
}
