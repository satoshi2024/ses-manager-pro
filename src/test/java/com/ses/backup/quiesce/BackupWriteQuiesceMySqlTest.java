package com.ses.backup.quiesce;

import com.ses.test.MySQLContainer;
import com.ses.test.MySqlTestImages;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * RB-QUIESCE BWQ-004（BWQ-AC-002-02・AC-002-07）: 業務の DataSource の最後の防線を実 MySQL 8.4 で確かめる
 * （design.md §6.5・§19）。
 *
 * <ul>
 *   <li>静止中に permit の無い thread が業務の pool から試みた INSERT・UPDATE は ERROR 1792 で失敗する。permit の thread は書ける。</li>
 *   <li>locking read（{@code FOR UPDATE}・{@code FOR SHARE}）の読取り専用の transaction での挙動を記録する（§19 の未実測の前提）。</li>
 *   <li>閉じる前に借りた書込み可能な接続が返されるまで排水は終わらない。</li>
 *   <li>静止の前に始まった読取り専用の transaction の終わりの {@code setReadOnly(false)} で書込み可能に戻らない。</li>
 *   <li>制御用 pool（包まない）は静止中も書ける。</li>
 *   <li>32 thread × 200 回の開閉の負荷で、各回の排水の確定の後・開く前に commit された permit の無い行が 0 件。</li>
 *   <li>静止の前に書いた marker は残り、静止中の marker は無く、解除の後の marker は書ける。</li>
 *   <li>Hikari は接続を返すと読取り専用を既定へ戻す（Connector/J は {@code SET SESSION TRANSACTION READ ONLY} を送る）。</li>
 * </ul>
 * app の接続は PROCESS 権限の無い専用の account（{@code bq_app}）で作る。
 */
@Tag("mysql")
@Testcontainers
class BackupWriteQuiesceMySqlTest {

    private static final Logger log = LoggerFactory.getLogger(BackupWriteQuiesceMySqlTest.class);
    private static final String DATABASE = "ses_bq_write";
    private static final String APP_USER = "bq_app";
    private static final String APP_PASSWORD = "bq_app_pw_0002";

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MySqlTestImages.image())
            .withDatabaseName(DATABASE).withUsername("root").withPassword("ses")
            .withStartupTimeout(Duration.ofMinutes(10));

    private final List<AutoCloseable> closeables = new ArrayList<>();
    private BackupWriteGate gate;
    private BackupQuiesceAwareDataSource business;

    @BeforeAll
    static void schema() throws Exception {
        try (Connection root = root(); Statement st = root.createStatement()) {
            st.execute("CREATE TABLE bq_marker (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, round_no INT NOT NULL, "
                    + "writer VARCHAR(32) NOT NULL) ENGINE=InnoDB");
            st.execute("CREATE USER '" + APP_USER + "'@'%' IDENTIFIED BY '" + APP_PASSWORD + "'");
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON " + DATABASE + ".* TO '" + APP_USER + "'@'%'");
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Connection root = root(); Statement st = root.createStatement()) {
            st.executeUpdate("DELETE FROM bq_marker");
        }
        gate = new BackupWriteGate(BackupWriteGate.Mode.ARMED, true, Duration.ofDays(1), 100);
        gate.recordHeartbeatSuccess();
        gate.armOpen();
        business = new BackupQuiesceAwareDataSource(pool(40), gate);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable closeable : closeables) {
            closeable.close();
        }
    }

    @Test
    void 静止中にpermitの無いthreadのINSERTとUPDATEは1792で失敗しpermitのthreadは書ける() throws Exception {
        insert(business, 0, "before");
        gate.beginClosing(1);
        assertThat(gate.isDrained()).isTrue();
        try (Connection connection = business.getConnection(); Statement st = connection.createStatement()) {
            assertThat(scalar(st, "SELECT @@session.transaction_read_only")).isEqualTo(1L);
            Throwable insert = catchThrowable(() -> st.executeUpdate(
                    "INSERT INTO bq_marker (round_no, writer) VALUES (1, 'no-permit')"));
            assertThat(BackupQuiesceReadOnlyViolation.matches(insert)).as(String.valueOf(insert)).isTrue();
            assertThat(((SQLException) insert).getErrorCode()).isEqualTo(1792);
            Throwable update = catchThrowable(() -> st.executeUpdate("UPDATE bq_marker SET writer = 'x'"));
            assertThat(BackupQuiesceReadOnlyViolation.matches(update)).isTrue();
        }
        try (BackupWritePermit permit = gate.tryAcquireDetached(BackupWritePermit.Kind.SCHEDULER, "job")) {
            permit.attachToCurrentThread();
            insert(business, 1, "permit");
        }
        assertThat(count("writer = 'no-permit'")).isZero();
        assertThat(count("writer IN ('before', 'permit')")).isEqualTo(2L);
    }

    @Test
    void 読取り専用のtransactionでのlocking_readの挙動を記録する() throws Exception {
        insert(business, 0, "lock-target");
        gate.beginClosing(1);
        List<String> outcomes = new ArrayList<>();
        for (String lock : List.of("FOR UPDATE", "FOR SHARE")) {
            try (Connection connection = business.getConnection()) {
                connection.setAutoCommit(false);
                try (Statement st = connection.createStatement()) {
                    Throwable failure = catchThrowable(() -> st.executeQuery("SELECT id FROM bq_marker " + lock).close());
                    String outcome = failure == null ? "許可" : BackupQuiesceReadOnlyViolation.matches(failure) ? "1792"
                            : "その他: " + failure;
                    outcomes.add(lock + "=" + outcome);
                } finally {
                    connection.rollback();
                    connection.setAutoCommit(true);
                }
            }
        }
        log.info("[RB-QUIESCE §19] 読取り専用の transaction の locking read（MySQL {}）: {}",
                MYSQL.getDockerImageName(), outcomes);
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome).matches(".*=(許可|1792)"));
    }

    @Test
    void 閉じる前に借りた書込み可能な接続が返されるまで排水は終わらない() throws Exception {
        Connection early = business.getConnection();
        gate.beginClosing(1);
        assertThat(gate.isDrained()).isFalse();
        try (Statement st = early.createStatement()) {
            st.executeUpdate("INSERT INTO bq_marker (round_no, writer) VALUES (1, 'early')");
        }
        assertThat(gate.isDrained()).as("返すまで排水は終わらない").isFalse();
        early.close();
        assertThat(gate.isDrained()).isTrue();
        assertThat(count("writer = 'early'")).isEqualTo(1L);
    }

    @Test
    void 静止の前に始まった読取り専用のtransactionの終わりのsetReadOnly_falseで書込み可能に戻らない() throws Exception {
        try (Connection connection = business.getConnection()) {
            // Spring の @Transactional(readOnly = true) と同じ順: autocommit の間に読取り専用にしてから transaction を始める
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (Statement st = connection.createStatement()) {
                st.executeQuery("SELECT COUNT(*) FROM bq_marker").close();
            }
            gate.beginClosing(1);
            assertThat(gate.isDrained()).as("読取り専用にした接続は数えない").isTrue();
            connection.commit();
            connection.setAutoCommit(true);
            connection.setReadOnly(false);
            assertThat(connection.isReadOnly()).isTrue();
            try (Statement st = connection.createStatement()) {
                Throwable insert = catchThrowable(() -> st.executeUpdate(
                        "INSERT INTO bq_marker (round_no, writer) VALUES (1, 'after-readonly-tx')"));
                assertThat(BackupQuiesceReadOnlyViolation.matches(insert)).as(String.valueOf(insert)).isTrue();
            }
            assertThat(gate.writableConnectionCount()).isZero();
        }
        assertThat(count("writer = 'after-readonly-tx'")).isZero();
    }

    @Test
    void 制御用poolは静止中も書ける() throws Exception {
        DataSourceProperties properties = new DataSourceProperties();
        properties.setUrl(MYSQL.getJdbcUrl());
        properties.setUsername(APP_USER);
        properties.setPassword(APP_PASSWORD);
        BackupQuiesceControlConnectionPool control = new BackupQuiesceControlConnectionPool(properties);
        closeables.add(control);
        gate.beginClosing(1);
        assertThat(control.controlTemplate().update("INSERT INTO bq_marker (round_no, writer) VALUES (1, 'control')"))
                .isEqualTo(1);
        assertThat(control.controlTemplate().queryForObject("SELECT @@session.innodb_lock_wait_timeout", Long.class))
                .isEqualTo(5L);
    }

    @Test
    void 三十二threadで二百回開閉しても各回の排水の確定の後に開く前にcommitされたpermitの無い行は0件() throws Exception {
        int workers = 32;
        int rounds = 200;
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger round = new AtomicInteger();
        AtomicLong written = new AtomicLong();
        AtomicLong refused = new AtomicLong();
        AtomicLong unexpected = new AtomicLong();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            Thread worker = new Thread(() -> {
                while (!stop.get()) {
                    try (Connection connection = business.getConnection(); Statement st = connection.createStatement()) {
                        st.executeUpdate("INSERT INTO bq_marker (round_no, writer) VALUES (" + round.get() + ", 'worker')");
                        written.incrementAndGet();
                    } catch (SQLException failure) {
                        if (BackupQuiesceReadOnlyViolation.matches(failure)) {
                            refused.incrementAndGet();
                        } else {
                            unexpected.incrementAndGet();
                        }
                    }
                }
            }, "bq-load-" + i);
            worker.setDaemon(true);
            threads.add(worker);
            worker.start();
        }
        List<String> violations = new ArrayList<>();
        try (Connection root = root(); Statement st = root.createStatement()) {
            for (int r = 1; r <= rounds; r++) {
                Thread.sleep(3);
                round.set(r);
                assertThat(gate.beginClosing(r)).isTrue();
                long giveUp = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                while (!gate.isDrained()) {
                    assertThat(System.nanoTime() - giveUp).as("排水が終わらない（%d 回目）", r).isNegative();
                    Thread.onSpinWait();
                }
                // ack の時点（排水の確定）の最大の行。開く前までに増えた行は permit の無い書込みが通ったことを意味する
                long watermark = scalar(st, "SELECT COALESCE(MAX(id), 0) FROM bq_marker");
                Thread.sleep(5);
                long leaked = scalar(st, "SELECT COUNT(*) FROM bq_marker WHERE id > " + watermark);
                if (leaked > 0) {
                    violations.add(r + "回目=" + leaked);
                }
                assertThat(gate.reopen(r)).isTrue();
            }
        } finally {
            stop.set(true);
            for (Thread thread : threads) {
                thread.join(30_000);
            }
        }
        assertThat(violations).as("ack の後・開く前に commit された permit の無い行").isEmpty();
        assertThat(written.get()).as("開いている間は書ける（試験の非空虚性）").isPositive();
        assertThat(refused.get()).as("閉じている間の書込みは 1792 で拒否された").isPositive();
        assertThat(unexpected.get()).isZero();
        assertThat(gate.writableConnectionCount()).isZero();
    }

    @Test
    void 静止の前のmarkerは残り静止中のmarkerは無く解除の後のmarkerは書けHikariは返す時に読取り専用を戻す() throws Exception {
        BackupQuiesceAwareDataSource single = new BackupQuiesceAwareDataSource(pool(1), gate);
        insert(single, 0, "before");
        gate.beginClosing(7);
        try (Connection connection = single.getConnection(); Statement st = connection.createStatement()) {
            assertThat(scalar(st, "SELECT @@session.transaction_read_only")).isEqualTo(1L);
            assertThat(catchThrowable(() -> st.executeUpdate(
                    "INSERT INTO bq_marker (round_no, writer) VALUES (7, 'during')"))).isNotNull();
        }
        assertThat(gate.reopen(7)).isTrue();
        try (Connection connection = single.getConnection(); Statement st = connection.createStatement()) {
            assertThat(scalar(st, "SELECT @@session.transaction_read_only"))
                    .as("同じ物理接続（pool の大きさ 1）でも返す時に Hikari が既定（書込み可能）へ戻した").isZero();
            st.executeUpdate("INSERT INTO bq_marker (round_no, writer) VALUES (8, 'after')");
        }
        assertThat(count("writer = 'before'")).isEqualTo(1L);
        assertThat(count("writer = 'during'")).isZero();
        assertThat(count("writer = 'after'")).isEqualTo(1L);
    }

    // ---------------------------------------------------------------- helper

    private HikariDataSource pool(int size) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(MYSQL.getJdbcUrl());
        config.setUsername(APP_USER);
        config.setPassword(APP_PASSWORD);
        config.setMaximumPoolSize(size);
        config.setPoolName("bq-business-" + size);
        HikariDataSource dataSource = new HikariDataSource(config);
        closeables.add(dataSource);
        return dataSource;
    }

    private static Connection root() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
    }

    private static void insert(BackupQuiesceAwareDataSource dataSource, int round, String writer) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement st = connection.createStatement()) {
            st.executeUpdate("INSERT INTO bq_marker (round_no, writer) VALUES (" + round + ", '" + writer + "')");
        }
    }

    private static long count(String where) throws SQLException {
        try (Connection root = root(); Statement st = root.createStatement()) {
            return scalar(st, "SELECT COUNT(*) FROM bq_marker WHERE " + where);
        }
    }

    private static long scalar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }

}
