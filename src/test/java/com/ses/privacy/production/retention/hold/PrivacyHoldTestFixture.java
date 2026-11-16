package com.ses.privacy.production.retention.hold;

import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.PrivacyApprovalProperties;
import com.ses.privacy.production.ProductionPrivacyProviderRegistry;
import com.ses.service.AuditLogService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * hold の fast test の共通部。test class ごとに専用の H2（{@code jdbc:h2:mem:}、MySQL mode）へ hold が読む表だけを作り、
 * 本番の store・gate・projector・service・reconciler を組み立てる。Journal は {@link InMemoryHoldJournal}、権限の guard と
 * 監査は mock（監査の本文は {@link #auditUris} に残す）。application の test DB（testdb）は使わない。
 */
final class PrivacyHoldTestFixture {

    static final String TENANT = "default";
    static final String JOURNAL_ID = "journal-test";
    static final Instant T0 = Instant.parse("2026-11-16T03:00:00Z");

    final MutableClock clock = new MutableClock(T0);
    final CountingDataSource dataSource;
    final JdbcTemplate jdbc;
    final InMemoryHoldJournal journal = new InMemoryHoldJournal(clock);
    final PrivacyApprovalProperties approval = new PrivacyApprovalProperties();
    final PrivacyHoldProperties holdProperties = new PrivacyHoldProperties();
    final PrivacyClockProperties clockProperties = new PrivacyClockProperties();
    final PrivacyAccessGuard guard = mock(PrivacyAccessGuard.class);
    final AuditLogService auditLog = mock(AuditLogService.class);
    final List<String> auditUris = new CopyOnWriteArrayList<>();
    volatile PrivacyJournalStartupVerifier.Result startupResult =
            new PrivacyJournalStartupVerifier.Result(PrivacyJournalStartupVerifier.Availability.AVAILABLE, List.of());
    volatile Duration pendingTimeout = Duration.ofMinutes(2);
    volatile PrivacyHoldService.Actor actor = new PrivacyHoldService.Actor(TENANT, 1L, "privacy-admin-a");
    /** application DB の {@code @@server_uuid}（H2 には無いため差し替える。Journal と同じ値なら同じ instance の扱い）。 */
    volatile java.util.function.Supplier<String> applicationServerUuid = () -> "application-server-uuid";

    final PrivacyHoldProjectionStore store;
    final PrivacyHoldJournal holdJournal;
    final HoldSubjectResolver resolver;
    final PrivacyHoldDecisions decisions;
    final PrivacyHoldGate gate;
    final PrivacyHoldService service;
    final PrivacyHoldProjectionReconciler reconciler;

    PrivacyHoldTestFixture(String databaseName) {
        DriverManagerDataSource h2 = new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseName + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        this.dataSource = new CountingDataSource(h2);
        this.jdbc = new JdbcTemplate(dataSource);
        createSchema();
        approval.getD05().setDecisionId("D05-NF07-TEST-001");
        approval.getD05().setSignedOn("2026-11-01");
        approval.getD05().setExpiresOn("2027-06-01");
        holdProperties.setAllowedTargetTypes(new java.util.ArrayList<>(List.of("SUBJECT", "DATA_ELEMENT")));
        clockProperties.setMaxSkew(Duration.ofSeconds(1));
        doAnswer(invocation -> {
            auditUris.add(invocation.getArgument(2));
            return null;
        }).when(auditLog).recordRequired(any(), any(), any(), anyInt(), any(), anyBoolean());

        this.store = new PrivacyHoldProjectionStore(jdbc);
        this.holdJournal = new PrivacyHoldJournal(() -> startupResult, () -> journal, journal, JOURNAL_ID,
                null, Duration.ofSeconds(35), () -> applicationServerUuid.get(), clock) {
            @Override
            Duration pendingTimeout() {
                return PrivacyHoldTestFixture.this.pendingTimeout;
            }
        };
        this.resolver = new HoldSubjectResolver(jdbc, new ProductionPrivacyProviderRegistry(List.of()));
        this.decisions = new PrivacyHoldDecisions(approval, holdProperties, clockProperties, clock);
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> noMetrics = emptyProvider();
        this.gate = new PrivacyHoldGate(store, holdJournal, resolver, noMetrics);
        this.independent = PrivacyHoldConfiguration.requiresNew(new DataSourceTransactionManager(dataSource));
        this.audit = new PrivacyHoldAudit(auditLog);
        this.projector = new PrivacyHoldProjector(store, holdJournal, independent, audit, clock);
        this.service = new PrivacyHoldService(guard, null, decisions, holdJournal, store, resolver, gate, projector, audit,
                independent, clock, () -> actor);
        this.reconciler = new PrivacyHoldProjectionReconciler(holdJournal, store, projector, decisions, audit,
                independent, clock, noMetrics);
    }

    private final TransactionTemplate independent;
    private final PrivacyHoldAudit audit;
    private final PrivacyHoldProjector projector;

    /** metric を数える gate（同じ store・Journal・resolver）。 */
    PrivacyHoldGate gateWith(io.micrometer.core.instrument.MeterRegistry registry) {
        return new PrivacyHoldGate(store, holdJournal, resolver, providerOf(registry));
    }

    /** metric を数える reconciler（同じ store・Journal・projector）。 */
    PrivacyHoldProjectionReconciler reconcilerWith(io.micrometer.core.instrument.MeterRegistry registry) {
        return new PrivacyHoldProjectionReconciler(holdJournal, store, projector, decisions, audit, independent, clock,
                providerOf(registry));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<io.micrometer.core.instrument.MeterRegistry> providerOf(
            io.micrometer.core.instrument.MeterRegistry registry) {
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }

    /** catalog の行を足す（provider は登録しないため、ATTRIBUTABLE の行は UNKNOWN として扱われる）。 */
    void catalog(String dataElementId, String attribution) {
        jdbc.update("INSERT INTO m_pii_data_element (tenant_id, data_element_id, provider_id, subject_attribution) "
                + "VALUES (?, ?, NULL, ?)", TENANT, dataElementId, attribution);
    }

    /** 既定の catalog（帰属できない element 2件、個人データなし1件、未分類1件）。 */
    void defaultCatalog() {
        catalog("DOCUMENT:IMPORT", "NOT_ATTRIBUTABLE");
        catalog("INTEGRATION_HUB_AUDIT", "NOT_ATTRIBUTABLE");
        catalog("API_NONCE", "NO_PERSONAL_DATA");
        catalog("AUDIT_LOG", "UNKNOWN");
    }

    PrivacyHoldStartRequest subjectRequest(String subjectRef, String importChoice, String hubChoice) {
        return new PrivacyHoldStartRequest("SUBJECT", subjectRef, null, "LITIGATION", "訴訟対応のため保全する",
                "TICKET-1001", null, List.of(
                new PrivacyHoldStartRequest.ElementChoice("DOCUMENT:IMPORT", importChoice),
                new PrivacyHoldStartRequest.ElementChoice("INTEGRATION_HUB_AUDIT", hubChoice)));
    }

    PrivacyHoldStartRequest elementRequest(String dataElementId) {
        return new PrivacyHoldStartRequest("DATA_ELEMENT", null, dataElementId, "LITIGATION", "訴訟対応のため保全する",
                "TICKET-1002", true, null);
    }

    int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    PrivacyHoldProjectionStore.StateRow state() {
        return store.stateRows(TENANT).stream().findFirst().orElse(null);
    }

    void dropSchema() {
        jdbc.execute("DROP ALL OBJECTS");
    }

    private void createSchema() {
        jdbc.execute("CREATE TABLE t_privacy_projection_state (tenant_id VARCHAR(100) NOT NULL, "
                + "journal_id VARCHAR(64) NOT NULL, engaged_at TIMESTAMP(6) NOT NULL, "
                + "last_applied_seq BIGINT NOT NULL DEFAULT 0, last_applied_hash CHAR(64) NULL, "
                + "pending_command_id VARCHAR(64) NULL, pending_since TIMESTAMP(6) NULL, verified_at TIMESTAMP(6) NULL, "
                + "row_version BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (tenant_id, journal_id), "
                + "CONSTRAINT chk_projection_pending CHECK ((pending_command_id IS NULL) = (pending_since IS NULL)))");
        jdbc.execute("CREATE TABLE t_legal_hold (id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL, "
                + "hold_id VARCHAR(64) NOT NULL, target_type VARCHAR(20) NOT NULL, subject_ref VARCHAR(128) NULL, "
                + "data_element_id VARCHAR(128) NULL, hold_kind VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN', "
                + "status VARCHAR(20) NOT NULL, reason_code VARCHAR(50) NOT NULL, reason_hash CHAR(64) NOT NULL, "
                + "ticket_ref VARCHAR(100) NOT NULL, requested_by BIGINT NOT NULL, requested_at TIMESTAMP(6) NOT NULL, "
                + "journal_revision BIGINT NOT NULL, journal_event_hash CHAR(64) NOT NULL, released_at TIMESTAMP(6) NULL, "
                + "CONSTRAINT uk_legal_hold UNIQUE (tenant_id, hold_id))");
        jdbc.execute("CREATE TABLE m_pii_data_element (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "tenant_id VARCHAR(100) NOT NULL, data_element_id VARCHAR(128) NOT NULL, provider_id VARCHAR(100) NULL, "
                + "subject_attribution VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN', deleted_flag TINYINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE t_document (id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL, "
                + "document_type VARCHAR(50) NOT NULL)");
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        return provider;
    }

    /** 進められる時計。 */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** 発行した statement を数える DataSource（未使用 tenant の gate が照会1回で終わることの確認用）。 */
    static final class CountingDataSource implements DataSource {
        private final DataSource delegate;
        final AtomicInteger statements = new AtomicInteger();
        /** statement を準備する直前に SQL を受け取る（lock 待ちの模擬に使う。A1H-1 delta review P2-N1）。 */
        volatile java.util.function.Consumer<String> beforePrepare = sql -> { };

        CountingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return wrap(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return wrap(delegate.getConnection(username, password));
        }

        private Connection wrap(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.equals("prepareStatement") || name.equals("createStatement") || name.equals("prepareCall")) {
                            statements.incrementAndGet();
                            if (args != null && args.length > 0 && args[0] instanceof String sql) {
                                beforePrepare.accept(sql);
                            }
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }
}
