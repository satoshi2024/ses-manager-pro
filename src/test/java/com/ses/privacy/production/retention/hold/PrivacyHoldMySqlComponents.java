package com.ses.privacy.production.retention.hold;

import com.ses.config.OidcSecurityProperties;
import com.ses.privacy.foundation.JournalUnavailableException;
import com.ses.privacy.foundation.PrivacyJournalPort;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.PrivacyApprovalProperties;
import com.ses.privacy.production.ProductionPrivacyProviderRegistry;
import com.ses.service.AuditLogService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * hold の MySQL test の組立て（Testcontainers の MySQL test だけが使う）。application DB は Flyway で全 migration を当てた
 * 実 MySQL、Journal は本番 adapter（prod の {@code MockEnvironment} で {@code ProductionPrivacyComponents} から生成した
 * もの。dev profile には実行時の経路を開かないため、A1H の Demo は Testcontainers の結果で示す）と本番の索引
 * （{@link JdbcPrivacyHoldJournalIndex}）。port は ACK の喪失と append の一時停止を注入できる decorator で包む。
 */
final class PrivacyHoldMySqlComponents {

    final String tenantId;
    final JdbcTemplate app;
    final FaultyPort port;
    final PrivacyHoldProjectionStore store;
    final PrivacyHoldJournal journal;
    final PrivacyHoldGate gate;
    final PrivacyHoldService service;
    final PrivacyHoldProjectionReconciler reconciler;
    final PrivacyHoldProjector projector;
    final PrivacyApprovalProperties approval = new PrivacyApprovalProperties();
    final PrivacyHoldProperties holdProperties = new PrivacyHoldProperties();
    final PrivacyClockProperties clockProperties = new PrivacyClockProperties();
    final List<String> auditUris = new CopyOnWriteArrayList<>();
    volatile PrivacyJournalStartupVerifier.Result startupResult =
            new PrivacyJournalStartupVerifier.Result(PrivacyJournalStartupVerifier.Availability.AVAILABLE, List.of());

    PrivacyHoldMySqlComponents(String tenantId, DataSource appDataSource, PrivacyJournalPort productionPort,
                               DataSource journalDataSource, String journalId, Duration pendingTimeout, Clock clock) {
        this.tenantId = tenantId;
        this.app = new JdbcTemplate(appDataSource);
        this.port = new FaultyPort(productionPort);
        approval.getD05().setDecisionId("D05-NF07-MYSQL-001");
        approval.getD05().setSignedOn("2026-11-01");
        approval.getD05().setExpiresOn("2027-06-01");
        holdProperties.setAllowedTargetTypes(new ArrayList<>(List.of("SUBJECT", "DATA_ELEMENT")));
        clockProperties.setMaxSkew(Duration.ofSeconds(1));
        AuditLogService auditLog = mock(AuditLogService.class);
        doAnswer(invocation -> {
            auditUris.add(invocation.getArgument(2));
            return null;
        }).when(auditLog).recordRequired(any(), any(), any(), anyInt(), any(), anyBoolean());
        OidcSecurityProperties oidc = new OidcSecurityProperties();
        oidc.setTenantId(tenantId);

        this.store = new PrivacyHoldProjectionStore(app);
        // application DB と Journal は別 container（@@server_uuid が異なる）。本番と同じく application DB を実際に照会する
        this.journal = new PrivacyHoldJournal(() -> startupResult, () -> port,
                new JdbcPrivacyHoldJournalIndex(journalDataSource), journalId, pendingTimeout, Duration.ofSeconds(35),
                () -> app.queryForObject("SELECT @@server_uuid", String.class), clock);
        HoldSubjectResolver resolver = new HoldSubjectResolver(app, new ProductionPrivacyProviderRegistry(List.of()));
        PrivacyHoldDecisions decisions = new PrivacyHoldDecisions(approval, holdProperties, clockProperties, clock);
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> noMetrics = emptyProvider();
        this.gate = new PrivacyHoldGate(store, journal, resolver, noMetrics);
        TransactionTemplate independent = PrivacyHoldConfiguration.requiresNew(new DataSourceTransactionManager(appDataSource));
        PrivacyHoldAudit audit = new PrivacyHoldAudit(auditLog);
        this.projector = new PrivacyHoldProjector(store, journal, independent, audit, clock);
        this.service = new PrivacyHoldService(mock(PrivacyAccessGuard.class), oidc, decisions, journal, store, resolver,
                gate, projector, audit, independent, clock,
                () -> new PrivacyHoldService.Actor(tenantId, 1L, "privacy-admin-mysql"));
        this.reconciler = new PrivacyHoldProjectionReconciler(journal, store, projector, decisions, audit, independent,
                clock, noMetrics);
    }

    /** tenant の catalog（帰属できない element 1件）。 */
    void seedCatalog() {
        app.update("INSERT INTO m_pii_data_element (tenant_id, data_element_id, storage_kind, storage_ref, provider_id, "
                + "subject_attribution) VALUES (?, 'INTEGRATION_HUB_AUDIT', 'DB_COLUMN', 't_api_delivery_replay_audit', "
                + "NULL, 'NOT_ATTRIBUTABLE')", tenantId);
    }

    PrivacyHoldStartRequest subject(String subjectRef) {
        return new PrivacyHoldStartRequest("SUBJECT", subjectRef, null, "LITIGATION", "訴訟対応のため保全する",
                "TICKET-MYSQL-1", null,
                List.of(new PrivacyHoldStartRequest.ElementChoice("INTEGRATION_HUB_AUDIT", "ACCEPT_RISK")));
    }

    int count(String sql, Object... args) {
        Integer value = app.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        return mock(ObjectProvider.class);
    }

    /** 本番 adapter を包み、append の後の ACK 喪失と append 前の一時停止を注入する。 */
    static final class FaultyPort implements PrivacyJournalPort {
        private final PrivacyJournalPort delegate;
        final AtomicInteger appendCalls = new AtomicInteger();
        final AtomicInteger loseAckAfterCommit = new AtomicInteger();
        volatile boolean unreachable;
        volatile CountDownLatch entered;
        volatile CountDownLatch release;

        FaultyPort(PrivacyJournalPort delegate) {
            this.delegate = delegate;
        }

        @Override
        public JournalAck append(JournalCommand command) {
            appendCalls.incrementAndGet();
            CountDownLatch gate = release;
            if (gate != null) {
                entered.countDown();
                try {
                    if (!gate.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test latch timeout");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            if (unreachable) {
                throw new JournalUnavailableException("test: unreachable", null);
            }
            JournalAck ack = delegate.append(command);
            if (loseAckAfterCommit.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new JournalUnavailableException("test: ACK lost after commit", null);
            }
            return ack;
        }

        @Override
        public JournalHead head(JournalPartition partition) {
            if (unreachable) {
                throw new JournalUnavailableException("test: unreachable", null);
            }
            return delegate.head(partition);
        }

        @Override
        public List<JournalEvent> events(JournalPartition partition) {
            if (unreachable) {
                throw new JournalUnavailableException("test: unreachable", null);
            }
            return delegate.events(partition);
        }
    }
}
