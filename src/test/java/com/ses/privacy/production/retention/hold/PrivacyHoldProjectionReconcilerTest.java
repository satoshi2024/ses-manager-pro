package com.ses.privacy.production.retention.hold;

import com.ses.common.exception.BusinessException;
import com.ses.privacy.foundation.PrivacyJournalPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NF07 hold の reconciler（A1H-1、production-bridge-phased-plan.md §3.4.4）の fast test。実 MySQL での確認は
 * {@code PrivacyHoldProjectionReconcilerMySqlTest}（Runner の shard-1）が行い、ここでは判定の分岐を専用の H2 で確かめる。
 */
class PrivacyHoldProjectionReconcilerTest {

    private static final String TENANT = PrivacyHoldTestFixture.TENANT;

    private PrivacyHoldTestFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new PrivacyHoldTestFixture("nf07-hold-reconciler-" + UUID.randomUUID());
        fixture.defaultCatalog();
    }

    @AfterEach
    void tearDown() {
        fixture.dropSchema();
    }

    @Test
    void ACK済みのpendingはprojectionを書いて消しgateが復帰する() {
        fixture.journal.commitThenFail.set(2);
        assertThatThrownBy(() -> fixture.service.start(fixture.subjectRequest("ENGINEER:10", "ACCEPT_RISK", "ACCEPT_RISK")))
                .isInstanceOf(BusinessException.class);
        assertThat(fixture.gate.evaluate(TENANT).reason()).isEqualTo(PrivacyHoldGate.REASON_PENDING);

        List<PrivacyHoldProjectionReconciler.Outcome> outcomes = fixture.reconciler.reconcileTenant(TENANT);

        assertThat(outcomes).contains(PrivacyHoldProjectionReconciler.Outcome.PENDING_PROJECTED,
                PrivacyHoldProjectionReconciler.Outcome.CONSISTENT);
        assertThat(fixture.state().pendingCommandId()).isNull();
        assertThat(fixture.jdbc.queryForObject("SELECT reason_code FROM t_legal_hold", String.class))
                .isEqualTo(PrivacyHoldService.RECONCILED);
        // 復元した行の理由 hash は「値を持たない」0×64（request hash を理由 hash の列に入れない。Review P3-1）
        assertThat(fixture.jdbc.queryForObject("SELECT reason_hash FROM t_legal_hold", String.class))
                .isEqualTo(PrivacyHoldProjectionReconciler.NO_REASON_HASH);
        assertThat(fixture.jdbc.queryForObject("SELECT requested_by FROM t_legal_hold", Long.class)).isZero();
        PrivacyHoldGate.Decision decision = fixture.gate.evaluate(TENANT);
        assertThat(decision.state()).isEqualTo(PrivacyHoldGate.State.VERIFIED);
        assertThat(decision.heldSubjects()).containsExactly("ENGINEER:10");
        assertThat(fixture.auditUris).anyMatch(uri -> uri.contains(PrivacyHoldAudit.RECONCILE_PROJECTED));
        assertThat(fixture.auditUris).allSatisfy(uri -> assertThat(uri).doesNotContain("ENGINEER"));
    }

    @Test
    void Journalに無いpendingはtimeoutとmax_skewを過ぎるまで残しその後に消す() {
        fixture.store.ensureStateLocked(TENANT, PrivacyHoldTestFixture.JOURNAL_ID, fixture.clock.instant());
        String commandId = PrivacyHoldPartitions.newIds().commandId();
        fixture.store.recordPending(TENANT, PrivacyHoldTestFixture.JOURNAL_ID, commandId, fixture.clock.instant());

        fixture.clock.advance(Duration.ofMinutes(2));
        assertThat(fixture.reconciler.reconcileTenant(TENANT))
                .containsExactly(PrivacyHoldProjectionReconciler.Outcome.PENDING_KEPT);
        assertThat(fixture.state().pendingCommandId()).isEqualTo(commandId);

        fixture.clock.advance(Duration.ofSeconds(2));
        assertThat(fixture.reconciler.reconcileTenant(TENANT))
                .contains(PrivacyHoldProjectionReconciler.Outcome.PENDING_ABANDONED);
        assertThat(fixture.state().pendingCommandId()).isNull();
        assertThat(fixture.gate.evaluate(TENANT).state()).isEqualTo(PrivacyHoldGate.State.VERIFIED);
        assertThat(fixture.auditUris).anyMatch(uri -> uri.contains(PrivacyHoldAudit.RECONCILE_ABANDONED));
    }

    @Test
    void pending_timeoutが未設定かappendの在途上限以下ならpendingを消さない() {
        fixture.store.ensureStateLocked(TENANT, PrivacyHoldTestFixture.JOURNAL_ID, fixture.clock.instant());
        String commandId = PrivacyHoldPartitions.newIds().commandId();
        fixture.store.recordPending(TENANT, PrivacyHoldTestFixture.JOURNAL_ID, commandId,
                fixture.clock.instant().minus(Duration.ofHours(1)));

        fixture.pendingTimeout = null;
        assertThat(fixture.reconciler.reconcileTenant(TENANT))
                .containsExactly(PrivacyHoldProjectionReconciler.Outcome.PENDING_KEPT);
        fixture.pendingTimeout = Duration.ofSeconds(35);
        assertThat(fixture.reconciler.reconcileTenant(TENANT))
                .containsExactly(PrivacyHoldProjectionReconciler.Outcome.PENDING_KEPT);
        fixture.pendingTimeout = Duration.ofMinutes(2);
        fixture.clockProperties.setMaxSkew(null);
        assertThat(fixture.reconciler.reconcileTenant(TENANT))
                .containsExactly(PrivacyHoldProjectionReconciler.Outcome.PENDING_KEPT);
        assertThat(fixture.state().pendingCommandId()).isEqualTo(commandId);
    }

    @Test
    void projectionに無いJournalのholdを追い付き再実行で二重にしない() {
        fixture.service.start(fixture.subjectRequest("ENGINEER:10", "ACCEPT_RISK", "ACCEPT_RISK"));
        PrivacyHoldPartitions.Ids ids = PrivacyHoldPartitions.newIds();
        fixture.journal.append(PrivacyJournalPort.JournalCommand.create(ids.commandId(),
                new PrivacyJournalPort.JournalPartition(TENANT, "DATA_ELEMENT#DOCUMENT:IMPORT"), 0,
                PrivacyJournalPort.CommandType.HOLD_STARTED, ids.holdId(), null, "e".repeat(64), null, 0, null));

        assertThat(fixture.reconciler.reconcileTenant(TENANT))
                .contains(PrivacyHoldProjectionReconciler.Outcome.CAUGHT_UP);
        assertThat(fixture.reconciler.reconcileTenant(TENANT))
                .containsExactly(PrivacyHoldProjectionReconciler.Outcome.CONSISTENT);

        PrivacyHoldGate.Decision decision = fixture.gate.evaluate(TENANT);
        assertThat(decision.heldDataElements()).containsExactly("DOCUMENT:IMPORT");
        assertThat(fixture.count("SELECT COUNT(*) FROM t_legal_hold")).isEqualTo(2);
    }

    @Test
    void holdとして解釈できないJournal_eventはprojectionへ書かずgateはUNAVAILABLEのまま() {
        fixture.service.start(fixture.subjectRequest("ENGINEER:10", "ACCEPT_RISK", "ACCEPT_RISK"));
        PrivacyHoldPartitions.Ids ids = PrivacyHoldPartitions.newIds();
        fixture.journal.append(PrivacyJournalPort.JournalCommand.create(ids.commandId(),
                new PrivacyJournalPort.JournalPartition(TENANT, "ENGINEER:11"), 0,
                PrivacyJournalPort.CommandType.HOLD_STARTED, "not-a-hold-id", null, "e".repeat(64), null, 0, null));

        assertThat(fixture.reconciler.reconcileTenant(TENANT))
                .contains(PrivacyHoldProjectionReconciler.Outcome.UNRECOGNIZED);
        assertThat(fixture.count("SELECT COUNT(*) FROM t_legal_hold")).isEqualTo(1);
        assertThat(fixture.gate.evaluate(TENANT).reason()).isEqualTo(PrivacyHoldGate.REASON_PROJECTION_LAG);
        // 状態が続く間毎回出る判定なので監査には残さない（Review P3-3）
        int audits = fixture.auditUris.size();
        fixture.reconciler.reconcileTenant(TENANT);
        assertThat(fixture.auditUris).hasSize(audits);
    }

    @Test
    void 状態を変えない判定は監査に残さずreason付きのmetricに数える() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        PrivacyHoldProjectionReconciler counted = fixture.reconcilerWith(registry);
        fixture.store.ensureStateLocked(TENANT, PrivacyHoldTestFixture.JOURNAL_ID, fixture.clock.instant());
        fixture.store.recordPending(TENANT, PrivacyHoldTestFixture.JOURNAL_ID, PrivacyHoldPartitions.newIds().commandId(),
                fixture.clock.instant());
        fixture.store.ensureStateLocked("other-tenant", PrivacyHoldTestFixture.JOURNAL_ID, fixture.clock.instant());
        fixture.store.recordPending("other-tenant", PrivacyHoldTestFixture.JOURNAL_ID, "not-a-command-id",
                fixture.clock.instant());

        counted.reconcileAll();
        counted.reconcileAll();

        assertThat(registry.get(PrivacyHoldProjectionReconciler.OUTCOME_METRIC)
                .tags("outcome", "PENDING_KEPT", "reason", "pendingNotExpired").counter().count()).isEqualTo(2.0);
        assertThat(registry.get(PrivacyHoldProjectionReconciler.OUTCOME_METRIC)
                .tags("outcome", "PENDING_AMBIGUOUS", "reason", "commandIdUnparsable").counter().count()).isEqualTo(2.0);
        assertThat(fixture.auditUris).isEmpty();
    }

    /**
     * A1H-1 delta review P3-N2: tenant の処理が例外で中断したときの WARN は例外の class 名を出し（同じ tenant が失敗し続ける原因を
     * log から辿れる）、message は出さない（subject 参照・氏名を含み得る）。metric の tag は固定の reason のまま。
     */
    @Test
    void tenantの処理が例外で中断したら例外のclass名だけをWARNに出しmessageは出さない() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        PrivacyHoldProjectionReconciler counted = fixture.reconcilerWith(registry);
        fixture.store.ensureStateLocked(TENANT, PrivacyHoldTestFixture.JOURNAL_ID, fixture.clock.instant());
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(PrivacyHoldProjectionReconciler.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        // tenant の追随位置の読取り（reconcileTenant の最初の照会）だけを、PII を含む message の例外で失敗させる
        fixture.dataSource.beforePrepare = sql -> {
            if (sql.contains("FROM t_privacy_projection_state WHERE tenant_id = ?") && !sql.contains("FOR UPDATE")) {
                throw new IllegalStateException("ENGINEER:10 山田太郎 の行を読めません");
            }
        };
        try {
            assertThat(counted.reconcileAll())
                    .containsEntry(PrivacyHoldProjectionReconciler.Outcome.PENDING_KEPT, 1);
        } finally {
            fixture.dataSource.beforePrepare = sql -> { };
            logger.detachAppender(appender);
        }

        List<String> warnings = appender.list.stream()
                .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
        assertThat(warnings).anySatisfy(message -> assertThat(message)
                .contains("reason=tenantAborted").contains("exceptionClass=IllegalStateException"));
        assertThat(warnings).noneMatch(message -> message.contains("山田") || message.contains("ENGINEER:10"));
        assertThat(appender.list).allSatisfy(event -> assertThat(event.getThrowableProxy())
                .as("例外の本文（stack trace の message）も出さない").isNull());
        assertThat(registry.get(PrivacyHoldProjectionReconciler.OUTCOME_METRIC)
                .tags("outcome", "PENDING_KEPT", "reason", "tenantAborted").counter().count()).isEqualTo(1.0);
    }

    @Test
    void 判定の後にpending_sinceが進められていればtimeoutのpendingを消さない() {
        fixture.store.ensureStateLocked(TENANT, PrivacyHoldTestFixture.JOURNAL_ID, fixture.clock.instant());
        String commandId = PrivacyHoldPartitions.newIds().commandId();
        fixture.store.recordPending(TENANT, PrivacyHoldTestFixture.JOURNAL_ID, commandId, fixture.clock.instant());
        java.time.Instant observed = fixture.state().pendingSince();
        // reconciler の判定と消去の間に、開始側が再送の直前の CAS で時刻を進めた
        assertThat(fixture.store.refreshPending(TENANT, PrivacyHoldTestFixture.JOURNAL_ID, commandId,
                fixture.clock.instant().plusSeconds(1))).isEqualTo(1);

        assertThat(fixture.store.clearExpiredPending(TENANT, PrivacyHoldTestFixture.JOURNAL_ID, commandId, observed))
                .isZero();
        assertThat(fixture.state().pendingCommandId()).isEqualTo(commandId);
        assertThat(fixture.store.clearExpiredPending(TENANT, PrivacyHoldTestFixture.JOURNAL_ID, commandId,
                fixture.state().pendingSince())).isEqualTo(1);
    }

    @Test
    void Journalが使えなければapplication_DBにも触れずに何もしない() {
        fixture.startupResult = new PrivacyJournalStartupVerifier.Result(
                PrivacyJournalStartupVerifier.Availability.NOT_CONFIGURED, List.of());
        fixture.dataSource.statements.set(0);

        assertThat(fixture.reconciler.reconcileAll())
                .containsEntry(PrivacyHoldProjectionReconciler.Outcome.JOURNAL_UNAVAILABLE, 1);
        assertThat(fixture.dataSource.statements.get()).isZero();
    }

    @Test
    void 定期実行はShedLockで1つのprocessだけが行う() throws Exception {
        var method = PrivacyHoldProjectionReconciler.class.getMethod("scheduledReconcile");
        assertThat(method.getAnnotation(Scheduled.class)).isNotNull();
        var lock = method.getAnnotation(net.javacrumbs.shedlock.spring.annotation.SchedulerLock.class);
        assertThat(lock).isNotNull();
        assertThat(lock.name()).isEqualTo(PrivacyHoldProjectionReconciler.LOCK_NAME);
    }
}
