package com.ses.privacy.foundation;

import com.ses.migration.PrivacyJournalFlywayJobConfig;
import com.ses.privacy.foundation.PrivacyJournalPort.CommandType;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalAck;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalCommand;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalEvent;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalHead;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalPartition;
import com.ses.privacy.production.retention.hold.PrivacyJournalTestPools;
import com.ses.privacy.production.retention.hold.PrivacyJournalTestPrincipals;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.ses.privacy.foundation.PrivacyJournalPort.CommandType.DISPATCH_AUTHORIZED;
import static com.ses.privacy.foundation.PrivacyJournalPort.CommandType.HOLD_RELEASED;
import static com.ses.privacy.foundation.PrivacyJournalPort.CommandType.HOLD_STARTED;
import static com.ses.privacy.foundation.PrivacyJournalPort.CommandType.LEASE_CLAIMED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NF07-A1J: 本番 Journal adapter（{@link ProductionPrivacyJournalAdapter}）を実 MySQL で検証する
 * （production-bridge-phased-plan.md §3.3）。
 *
 * <p>Journal schema は専用 Flyway job の設定（{@code ops/flyway/flyway-privacy-journal.conf}）そのもので作り、
 * principal は journal-witness-grant-design.md §3.2 の appender / reader と同じ GRANT にする。接続は本番 holder と
 * 同じ pool 設定（session time zone の固定等）を使う。container は2つ: Journal と、合成 adapter との同値性を
 * 確かめる予備（合成 schema {@code privacy-journal/schema-test.sql}）。application DB は本 adapter が触らないため使わない。
 */
@Tag("mysql")
@Testcontainers
class ProductionPrivacyJournalAdapterMySqlTest {
    private static final String ROOT_PASSWORD = UUID.randomUUID().toString();
    private static final Instant T0 = Instant.parse("2026-11-15T00:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(T0);

    @Container
    static final MySQLContainer<?> JOURNAL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName(PrivacyJournalTestPrincipals.SCHEMA)
            .withUsername("root")
            .withPassword(ROOT_PASSWORD)
            .withCommand("--log-bin=mysql-bin", "--sync-binlog=1", "--innodb-flush-log-at-trx-commit=1");

    @Container
    static final MySQLContainer<?> SYNTHETIC = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName(JournalTestConnectionCapability.randomDatabase())
            .withUsername("root")
            .withPassword(ROOT_PASSWORD)
            .withCommand("--log-bin=mysql-bin", "--sync-binlog=1", "--innodb-flush-log-at-trx-commit=1");

    private static AnnotationConfigApplicationContext testContext;
    private static JdbcTemplate journalRoot;
    private static JdbcTemplate syntheticRoot;
    private static HikariDataSource appenderPool;
    private static HikariDataSource readerPool;
    private static MockEnvironment prod;
    private static PrivacyJournalPort production;
    private static JdbcPrivacyJournalAdapter synthetic;

    private final JournalPartition partition = new JournalPartition("tenant-a1j", "subject-opaque-a1j");

    @BeforeAll
    static void startJournal() {
        PrivacyJournalFlywayJobConfig.configure(JOURNAL.getJdbcUrl(), "root", ROOT_PASSWORD).load().migrate();
        journalRoot = new JdbcTemplate(new DriverManagerDataSource(JOURNAL.getJdbcUrl(), "root", ROOT_PASSWORD));
        String appender = PrivacyJournalTestPrincipals.uniqueName("nf07_app");
        String appenderPassword = PrivacyJournalTestPrincipals.randomPassword();
        PrivacyJournalTestPrincipals.createAppender(journalRoot, appender, appenderPassword);
        String reader = PrivacyJournalTestPrincipals.uniqueName("nf07_rdr");
        String readerPassword = PrivacyJournalTestPrincipals.randomPassword();
        PrivacyJournalTestPrincipals.createReader(journalRoot, reader, readerPassword);
        appenderPool = PrivacyJournalTestPools.pool(JOURNAL.getJdbcUrl(), appender, appenderPassword);
        readerPool = PrivacyJournalTestPools.pool(JOURNAL.getJdbcUrl(), reader, readerPassword);
        prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        production = ProductionPrivacyComponents.productionPrivacyJournal(prod, appenderPool, CLOCK);

        testContext = new AnnotationConfigApplicationContext();
        testContext.getEnvironment().setActiveProfiles("test");
        testContext.refresh();
        JournalTestConnectionCapability capability = JournalTestConnectionCapability.fromOwnedContainer(SYNTHETIC, testContext);
        new ResourceDatabasePopulator(new ClassPathResource("privacy-journal/schema-test.sql"))
                .execute(capability.adminDataSource());
        syntheticRoot = new JdbcTemplate(capability.adminDataSource());
        synthetic = new JdbcPrivacyJournalAdapter(capability.createJournalWriterDataSource(), capability.environment(), CLOCK);
    }

    @AfterAll
    static void close() {
        if (appenderPool != null) appenderPool.close();
        if (readerPool != null) readerPool.close();
        if (testContext != null) testContext.close();
    }

    @BeforeEach
    void clearOwnedRows() {
        CLOCK.set(T0);
        for (String table : List.of("pj_lease", "pj_action", "pj_command", "pj_event", "pj_head")) {
            journalRoot.update("DELETE FROM " + table);
        }
        for (String table : List.of("sf_journal_lease", "sf_journal_action", "sf_journal_command", "sf_journal_event",
                "sf_journal_head")) {
            syntheticRoot.update("DELETE FROM " + table);
        }
    }

    @Test
    void Journalの表はFlyway_jobの設定で作られ合成schemaと名前以外同じ列定義を持つ() {
        String columns = "SELECT table_name, column_name, ordinal_position, column_type, is_nullable, "
                + "IFNULL(character_set_name, ''), IFNULL(collation_name, '') FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name LIKE ? ORDER BY table_name, ordinal_position";
        List<String> journalColumns = journalRoot.query(columns, (rs, row) -> String.join("|",
                rs.getString(1).replaceFirst("^pj_", ""), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7)), "pj\\_%");
        List<String> syntheticColumns = syntheticRoot.query(columns, (rs, row) -> String.join("|",
                rs.getString(1).replaceFirst("^sf_journal_", ""), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7)), "sf\\_journal\\_%");

        assertFalse(journalColumns.isEmpty());
        assertEquals(syntheticColumns, journalColumns);
        assertEquals(List.of("1|V1__privacy_journal_core.sql|true"), journalRoot.query(
                "SELECT version, script, success FROM " + PrivacyJournalFlywayJobConfig.HISTORY_TABLE,
                (rs, row) -> rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getBoolean(3)));
    }

    @Test
    void HOLD_STARTEDのappendと同じcommand_IDの再送は元のACKを返す() {
        JournalCommand hold = hold("hold-a1j-1", 0);

        JournalAck first = production.append(hold);
        JournalAck again = production.append(hold);

        assertEquals(1, first.revision());
        assertFalse(first.replayed());
        assertTrue(again.replayed());
        assertEquals(first.revision(), again.revision());
        assertEquals(first.eventHash(), again.eventHash());
        JournalHead head = production.head(partition);
        assertTrue(head.held());
        assertEquals(1, head.revision());
        assertEquals(1, production.events(partition).size());
        assertEquals(1, count("pj_event"));
        assertThrows(JournalConflictException.class, () -> production.append(hold("hold-a1j-1", 1)),
                "同じ command ID で内容が違う再送は競合");
    }

    @Test
    void revision競合とhold中の新規認可と二重holdを拒否する() {
        production.append(leaseClaim("lease-1"));
        assertThrows(JournalConflictException.class, () -> production.append(hold("hold-stale", 0)));
        production.append(hold("hold-1", 1));

        assertThrows(JournalConflictException.class, () -> production.append(dispatch("auth-1", 2)));
        assertThrows(JournalConflictException.class, () -> production.append(hold("hold-2", 2)));
        assertEquals(2, production.head(partition).revision());
        assertEquals(2, production.events(partition).size());
    }

    @Test
    void HOLD_RELEASEDは本番adapterでも拒否しheadを変えない() {
        production.append(hold("hold-1", 0));

        assertThrows(SecurityException.class, () -> production.append(JournalCommand.create("release-1", partition, 1,
                HOLD_RELEASED, null, null, null, null, 0, null)));

        JournalHead head = production.head(partition);
        assertTrue(head.held());
        assertEquals(1, head.revision());
    }

    @Test
    void ACK不明の接続断は成功と推定せず同じcommand_IDの再送で元のACKを返す() {
        FaultInjectingDataSource faulty = new FaultInjectingDataSource(appenderPool);
        PrivacyJournalPort port = ProductionPrivacyComponents.productionPrivacyJournal(prod, faulty.proxy(), CLOCK);
        JournalCommand hold = hold("hold-ack-lost", 0);

        faulty.loseNextCommitAck();
        assertThrows(JournalUnavailableException.class, () -> port.append(hold));
        assertEquals(1, count("pj_event"), "commit は完了している（ACK だけが失われた）");

        JournalAck replay = port.append(hold);
        assertTrue(replay.replayed());
        assertEquals(1, replay.revision());
        assertEquals(replay, production.append(hold));
        assertEquals(1, production.events(partition).size());
    }

    @Test
    void transaction中断ではheadとevent正本が変わらず同じcommandを後で適用できる() {
        production.append(leaseClaim("lease-1"));
        JournalHead before = production.head(partition);
        List<JournalEvent> eventsBefore = production.events(partition);
        FaultInjectingDataSource faulty = new FaultInjectingDataSource(appenderPool);
        PrivacyJournalPort port = ProductionPrivacyComponents.productionPrivacyJournal(prod, faulty.proxy(), CLOCK);

        faulty.failNextStatement("INSERT INTO pj_command");
        assertThrows(JournalUnavailableException.class, () -> port.append(hold("hold-interrupted", 1)));

        assertEquals(before, production.head(partition));
        assertEquals(eventsBefore, production.events(partition));
        assertEquals(0, journalRoot.queryForObject("SELECT COUNT(*) FROM pj_event WHERE command_id = ?",
                Integer.class, "hold-interrupted"));
        JournalAck applied = port.append(hold("hold-interrupted", 1));
        assertFalse(applied.replayed());
        assertEquals(2, applied.revision());
    }

    @Test
    void ambient_transaction内からの呼出しは拒否する() {
        TransactionTemplate ambient = new TransactionTemplate(new DataSourceTransactionManager(
                new DriverManagerDataSource(SYNTHETIC.getJdbcUrl(), "root", ROOT_PASSWORD)));

        assertThrows(IllegalStateException.class, () -> ambient.executeWithoutResult(status -> production.head(partition)));
        assertThrows(IllegalStateException.class,
                () -> ambient.executeWithoutResult(status -> production.append(hold("hold-ambient", 0))));
        assertEquals(0, count("pj_event"));
    }

    @Test
    void 耐久設定が不適合なprimaryではappendを拒否する() {
        journalRoot.execute("SET GLOBAL sync_binlog = 0");
        try {
            assertThrows(JournalUnavailableException.class, () -> production.append(hold("hold-nondurable", 0)));
        } finally {
            journalRoot.execute("SET GLOBAL sync_binlog = 1");
        }
        assertEquals(0, production.head(partition).revision());
        assertEquals(0, count("pj_head"));
    }

    @Test
    void reader_principalは照会だけができ追記できない() {
        production.append(hold("hold-1", 0));
        PrivacyJournalPort readerPort = ProductionPrivacyComponents.productionPrivacyJournal(prod, readerPool, CLOCK);

        assertEquals(production.head(partition), readerPort.head(partition));
        assertEquals(production.events(partition), readerPort.events(partition));
        assertThrows(JournalUnavailableException.class, () -> readerPort.append(leaseClaim("lease-by-reader")));
        assertEquals(1, production.head(partition).revision());
    }

    @Test
    void 合成adapterと同一入力ではACKとhash_chainが一致する() {
        List<JournalCommand> commands = List.of(leaseClaim("lease-eq"), dispatch("auth-eq", 1), hold("hold-eq", 2));
        for (int i = 0; i < commands.size(); i++) {
            // nanosecond を含む時刻でもマイクロ秒へ正規化した同じ event 時刻になる
            CLOCK.set(T0.plusMillis(250L * i).plusNanos(123_456_789L));
            JournalAck syntheticAck = synthetic.append(commands.get(i));
            JournalAck productionAck = production.append(commands.get(i));
            assertEquals(syntheticAck, productionAck, "command " + commands.get(i).commandId());
        }

        assertEquals(synthetic.events(partition), production.events(partition));
        assertEquals(synthetic.head(partition), production.head(partition));
        assertEquals(List.of(LEASE_CLAIMED, DISPATCH_AUTHORIZED, HOLD_STARTED),
                production.events(partition).stream().map(JournalEvent::type).toList());
        // A1H の projection が通る foundation 既存の chain 検証とも一致する（Review P3-7）
        assertTrue(JournalProjectionService.verifyChain(partition, production.head(partition), production.events(partition)));
    }

    // ---------------------------------------------------------------- helper

    private JournalCommand hold(String id, long expected) {
        return JournalCommand.create(id, partition, expected, HOLD_STARTED, null, null, null, null, 0, null);
    }

    private JournalCommand leaseClaim(String id) {
        return JournalCommand.create(id, partition, 0, LEASE_CLAIMED, null, null, null, "worker-1", 0,
                T0.plusSeconds(60));
    }

    private JournalCommand dispatch(String id, long expected) {
        return JournalCommand.create(id, partition, expected, CommandType.DISPATCH_AUTHORIZED, "case-1", "action-1",
                "b".repeat(64), "worker-1", 1, null);
    }

    private static int count(String table) {
        Integer count = journalRoot.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? -1 : count;
    }

    /** commit 後の ACK 喪失と、特定 statement での transaction 中断を注入する DataSource。 */
    private static final class FaultInjectingDataSource {
        private final DataSource delegate;
        private final AtomicBoolean loseCommitAck = new AtomicBoolean();
        private final AtomicReference<String> failStatementPrefix = new AtomicReference<>();

        private FaultInjectingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        void loseNextCommitAck() {
            loseCommitAck.set(true);
        }

        void failNextStatement(String sqlPrefix) {
            failStatementPrefix.set(sqlPrefix);
        }

        DataSource proxy() {
            return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                    (proxy, method, args) -> {
                        if ("equals".equals(method.getName())) return proxy == args[0];
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("toString".equals(method.getName())) return "fault-injecting-journal";
                        Object result = invoke(method, delegate, args);
                        return "getConnection".equals(method.getName()) ? connection((Connection) result) : result;
                    });
        }

        private Connection connection(Connection real) {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if ("equals".equals(method.getName())) return proxy == args[0];
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("prepareStatement".equals(method.getName()) && args != null && args.length > 0
                                && args[0] instanceof String sql) {
                            String prefix = failStatementPrefix.get();
                            if (prefix != null && sql.startsWith(prefix) && failStatementPrefix.compareAndSet(prefix, null)) {
                                throw new SQLException("synthetic statement failure");
                            }
                        }
                        if ("commit".equals(method.getName()) && loseCommitAck.compareAndSet(true, false)) {
                            invoke(method, real, args);
                            throw new SQLException("synthetic lost commit acknowledgement", "08S01");
                        }
                        return invoke(method, real, args);
                    });
        }

        private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private MutableClock(Instant initial) {
            instant = new AtomicReference<>(initial);
        }

        private void set(Instant value) {
            instant.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
