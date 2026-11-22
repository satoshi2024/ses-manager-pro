package com.ses.backup.quiesce;

import com.ses.test.MySQLContainer;
import com.ses.test.MySqlTestImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RB-QUIESCE BWQ-003: 静止応答（{@link BackupQuiesceResponder}）と信号表の SQL（{@code JdbcBackupQuiesceSignalRepository}）を
 * 実 MySQL 8.4 で確かめる（design.md §5・§6.8・§6.9・§19）。
 *
 * <ul>
 *   <li>登録の {@code db_client_host}（{@code USER()} の host 部）が {@code PROCESSLIST.HOST} から port を外した値と一致する。</li>
 *   <li>lease を延ばさなければ手元の期限で再開し、ack を RESUMED（LEASE_EXPIRED）にする。app の session の time_zone が
 *       {@code +09:00}、provider の session が {@code +00:00} でも hold 期限は lease の期限と同じ UTC の値になる。</li>
 *   <li>APP_STOPPED の保持中は起動処理が例外で止まる（停止口の DISABLED でも）。</li>
 *   <li>ack の INSERT の commit の後に接続を {@code KILL} で切ると、閉じたまま REFUSED の INSERT が重複になり QUIESCED として続く。</li>
 *   <li>別の session が ack の行の lock を持つ間、REFUSED の INSERT が 5 秒で timeout し、heartbeat は続く。</li>
 * </ul>
 * app の接続は PROCESS 権限の無い専用の account（{@code bq_app}）で作り、provider の操作は root の接続で行う。
 */
@Tag("mysql")
@Testcontainers
class BackupQuiesceResponderMySqlTest {

    private static final String DATABASE = "ses_bq_responder";
    private static final String APP_USER = "bq_app";
    private static final String APP_PASSWORD = "bq_app_pw_0001";

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MySqlTestImages.image())
            .withDatabaseName(DATABASE).withUsername("root").withPassword("ses")
            .withStartupTimeout(Duration.ofMinutes(10));

    private final List<BackupQuiesceResponder> responders = new ArrayList<>();
    private final List<BackupQuiesceLifecycleAnchor> anchors = new ArrayList<>();

    @BeforeAll
    static void schema() throws Exception {
        try (Connection root = root()) {
            ScriptUtils.executeSqlScript(root,
                    new FileSystemResource("src/main/resources/db/migration/V202__backup_write_quiesce_signal.sql"));
            try (Statement st = root.createStatement()) {
                st.execute("CREATE USER '" + APP_USER + "'@'%' IDENTIFIED BY '" + APP_PASSWORD + "'");
                st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON " + DATABASE + ".* TO '" + APP_USER + "'@'%'");
            }
        }
    }

    @BeforeEach
    void clean() throws Exception {
        try (Connection root = root(); Statement st = root.createStatement()) {
            st.executeUpdate("DELETE FROM sys_backup_quiesce_ack");
            st.executeUpdate("DELETE FROM sys_backup_quiesce_instance");
            st.executeUpdate("DELETE FROM sys_backup_quiesce_request");
        }
    }

    @AfterEach
    void stop() {
        for (BackupQuiesceResponder responder : responders) {
            responder.destroy();
        }
        // 差し替えた repository を使う anchor を、pool を持つ anchor より先に止める
        for (int i = anchors.size() - 1; i >= 0; i--) {
            anchors.get(i).destroy();
        }
    }

    // ---------------------------------------------------------------- 登録と接続元

    @Test
    void 登録のdb_client_hostはPROCESSLISTのhostからportを外した値と一致し観測もその接続元だけ() throws Exception {
        BackupQuiesceLifecycleAnchor anchor = anchor(new BackupQuiesceProperties(), true);
        start(anchor);
        String clientHost;
        long connectionId;
        try (Connection root = root(); Statement st = root.createStatement();
             ResultSet rs = st.executeQuery("SELECT db_client_host, db_connection_id FROM sys_backup_quiesce_instance "
                     + "WHERE instance_id = '" + anchor.instanceId() + "'")) {
            assertThat(rs.next()).isTrue();
            clientHost = rs.getString(1);
            connectionId = rs.getLong(2);
        }
        String processlistHost = scalarString("SELECT HOST FROM information_schema.PROCESSLIST WHERE ID = " + connectionId);
        assertThat(processlistHost).as("TCP の接続は port を持つ").contains(":");
        assertThat(BackupQuiesceHosts.fromProcesslist(processlistHost)).isEqualTo(clientHost);

        BackupQuiesceSignalRepository.Observation observation = anchor.repository().observeAppHosts();
        assertThat(observation.hosts()).as("PROCESS 権限の無い account は同じ account の接続だけを見る")
                .startsWith(clientHost + "=").doesNotContain(",");
        assertThat(observation.observedAt()).isCloseTo(utcNow(), within(Duration.ofSeconds(5)));
    }

    // ---------------------------------------------------------------- lease・time_zone

    @Test
    void leaseを延ばさないとLEASE_EXPIREDで再開しsessionのtime_zoneが違ってもhold期限はUTCで一致する() throws Exception {
        BackupQuiesceLifecycleAnchor anchor = anchor(new BackupQuiesceProperties(), true);
        BackupQuiesceResponder responder = start(anchor);
        long id = insertRequest("ONLINE", "CHECKPOINT", 8);

        awaitTrue(() -> "QUIESCED".equals(ackState(id, anchor)), Duration.ofSeconds(10), "QUIESCED の ack");
        assertThat(anchor.gate().state()).isEqualTo(BackupWriteGate.State.CLOSED);
        assertThat(scalarLong("SELECT COUNT(*) FROM sys_backup_quiesce_ack a JOIN sys_backup_quiesce_request r "
                + "ON r.id = a.request_id WHERE a.request_id = " + id + " AND a.hold_until = r.lease_expires_at"))
                .as("app（+09:00）と provider（+00:00）で hold 期限が同じ UTC の値").isEqualTo(1L);
        assertThat(scalarLong("SELECT COUNT(*) FROM sys_backup_quiesce_ack WHERE request_id = " + id
                + " AND acked_at BETWEEN TIMESTAMPADD(SECOND, -10, UTC_TIMESTAMP(6)) AND UTC_TIMESTAMP(6)"))
                .as("acked_at は UTC").isEqualTo(1L);

        awaitTrue(() -> "RESUMED".equals(ackState(id, anchor)), Duration.ofSeconds(20), "lease の期限切れで RESUMED");
        assertThat(scalarString("SELECT resume_reason FROM sys_backup_quiesce_ack WHERE request_id = " + id))
                .isEqualTo("LEASE_EXPIRED");
        assertThat(anchor.gate().state()).isEqualTo(BackupWriteGate.State.OPEN);
        assertThat(responder.currentStep()).isNull();
    }

    // ---------------------------------------------------------------- APP_STOPPED の保持

    @Test
    void APP_STOPPEDの保持中は起動処理が例外で止まり停止口のDISABLEDでも止まる() throws Exception {
        insertRequest("APP_STOPPED", "FULL", 60);
        BackupQuiesceLifecycleAnchor armed = anchor(new BackupQuiesceProperties(), true);
        assertThatThrownBy(() -> responder(armed).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_STOPPED");

        BackupQuiesceProperties disabled = new BackupQuiesceProperties();
        disabled.setEnabled(false);
        disabled.setDisabledTicket("CHG-1234");
        BackupQuiesceLifecycleAnchor stopped = anchor(disabled, true);
        assertThat(stopped.gate().mode()).isEqualTo(BackupWriteGate.Mode.DISABLED);
        assertThatThrownBy(() -> responder(stopped).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class);
        assertThat(scalarLong("SELECT COUNT(*) FROM sys_backup_quiesce_instance WHERE instance_id = '"
                + stopped.instanceId() + "' AND gate_state = 'DISABLED'")).as("停止口も登録する").isEqualTo(1L);
    }

    // ---------------------------------------------------------------- 結果不明の ack

    @Test
    void ackのINSERTのcommitの後に接続をKILLで切っても閉じたままREFUSEDが重複になりQUIESCEDとして続く() throws Exception {
        BackupQuiesceProperties properties = new BackupQuiesceProperties();
        BackupQuiesceLifecycleAnchor real = anchor(properties, true);
        FailingRepository failing = new FailingRepository(real.repository());
        failing.afterQuiescedInsert = () -> {
            killAppConnections();
            throw new QueryTimeoutException("ack の commit の後に接続が切れた（結果不明）");
        };
        BackupQuiesceLifecycleAnchor anchor = wrap(real, failing, properties);
        BackupQuiesceResponder responder = start(anchor);
        long id = insertRequest("ONLINE", "CHECKPOINT", 60);

        awaitTrue(() -> responder.currentStep() == BackupQuiesceResponder.Step.QUIESCED, Duration.ofSeconds(15),
                "重複を読んで QUIESCED として続く");
        assertThat(failing.quiescedInserts).isEqualTo(1);
        assertThat(failing.refusedDuplicates).as("REFUSED の INSERT は重複").isPositive();
        assertThat(ackState(id, anchor)).isEqualTo("QUIESCED");
        assertThat(anchor.gate().state()).as("hold 期限の前に開かない").isEqualTo(BackupWriteGate.State.CLOSED);

        releaseRequest(id);
        awaitTrue(() -> anchor.gate().state() == BackupWriteGate.State.OPEN, Duration.ofSeconds(10), "解除で開く");
        assertThat(ackState(id, anchor)).isEqualTo("RESUMED");
    }

    @Test
    void 別のsessionがackの行のlockを持つ間REFUSEDのINSERTは5秒でtimeoutしheartbeatは続く() throws Exception {
        BackupQuiesceProperties properties = new BackupQuiesceProperties();
        BackupQuiesceLifecycleAnchor real = anchor(properties, true);
        FailingRepository failing = new FailingRepository(real.repository());
        failing.skipQuiescedInsert = true;
        Connection blocker = root();
        blocker.setAutoCommit(false);
        failing.afterQuiescedInsert = () -> {
            // 結果不明の INSERT と同じ PK の行を、別の session が commit せずに持つ（REFUSED の INSERT はその lock を待つ）
            try (Statement st = blocker.createStatement()) {
                st.executeUpdate("INSERT INTO sys_backup_quiesce_ack (request_id, instance_id, state, scheduler_state, "
                        + "drain_millis, hold_until, observed_app_hosts, observed_at, acked_at) SELECT id, '"
                        + failing.instanceId + "', 'QUIESCED', 'PAUSED', 0, lease_expires_at, '', UTC_TIMESTAMP(6), "
                        + "UTC_TIMESTAMP(6) FROM sys_backup_quiesce_request WHERE status = 'REQUESTED'");
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
            throw new QueryTimeoutException("結果不明");
        };
        BackupQuiesceLifecycleAnchor anchor = wrap(real, failing, properties);
        failing.instanceId = anchor.instanceId();
        BackupQuiesceResponder responder = start(anchor);
        try {
            long id = insertRequest("ONLINE", "CHECKPOINT", 60);
            awaitTrue(() -> failing.refusedTimeouts >= 2, Duration.ofSeconds(20), "REFUSED の INSERT の lock 待ちの timeout");
            LocalDateTime beatBefore = lastBeat(anchor);
            Thread.sleep(11_000);
            assertThat(lastBeat(anchor)).as("lock 待ちの間も heartbeat は別の thread と接続で続く").isAfter(beatBefore);
            assertThat(anchor.gate().isFenced()).isFalse();
            assertThat(anchor.gate().state()).as("結果不明の間は閉じたまま").isEqualTo(BackupWriteGate.State.CLOSED);
            assertThat(failing.maxRefusedMillis).as("1回の待ちは lock 待ちの timeout（5 秒）の程度")
                    .isBetween(4_000L, 9_000L);

            blocker.rollback();
            awaitTrue(() -> anchor.gate().state() == BackupWriteGate.State.OPEN, Duration.ofSeconds(15),
                    "lock が外れると REFUSED が成功して開く");
            assertThat(ackState(id, anchor)).isEqualTo("REFUSED");
            assertThat(responder.currentStep()).isNull();
        } finally {
            blocker.rollback();
            blocker.close();
        }
    }

    // ---------------------------------------------------------------- 停止の順（BWQ-004。§6.11）

    @Test
    void 通常のcontextのcloseではSTOPPEDを書いた時点で業務のHikariが閉じていてtaskExecutorのtaskが終わっている() throws Exception {
        ObservedHikariDataSource.reset();
        stopRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("dataSource")).isInstanceOf(BackupQuiesceAwareDataSource.class);
            assertThat(scalarLong("SELECT COUNT(*) FROM sys_backup_quiesce_instance WHERE status <> 'STOPPED'"))
                    .isEqualTo(1L);
            context.getBean("taskExecutor", Executor.class).execute(() -> {
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                ObservedHikariDataSource.TASK_DONE.set(true);
            });
        });
        assertThat(ObservedHikariDataSource.stoppedAtClose).as("Hikari を閉じた時点ではまだ STOPPED でない").isZero();
        assertThat(ObservedHikariDataSource.taskDoneAtClose).as("taskExecutor の task は Hikari より先に終わっている")
                .isTrue();
        assertThat(scalarLong("SELECT COUNT(*) FROM sys_backup_quiesce_instance WHERE status = 'STOPPED' "
                + "AND stopped_at IS NOT NULL")).as("Hikari を閉じた後に STOPPED を書いた").isEqualTo(1L);
    }

    @Test
    void refreshの失敗でもHikariを閉じた後にSTOPPEDを書きstartup_permitを返して10秒待たない() throws Exception {
        ObservedHikariDataSource.reset();
        long start = System.nanoTime();
        stopRunner().withUserConfiguration(FailAfterRegistration.class)
                .run(context -> assertThat(context).hasFailed());
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
        assertThat(ObservedHikariDataSource.closedCount.get()).isPositive();
        assertThat(ObservedHikariDataSource.stoppedAtClose).isZero();
        assertThat(ObservedHikariDataSource.notStoppedAtClose).as("登録の後に失敗した").isEqualTo(1L);
        assertThat(scalarLong("SELECT COUNT(*) FROM sys_backup_quiesce_instance WHERE status = 'STOPPED'"))
                .isEqualTo(1L);
    }

    @Test
    void permitを持つ処理が10秒より長く続く停止ではSTOPPEDを書かずWARNを出す() throws Exception {
        ObservedHikariDataSource.reset();
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(BackupQuiesceDataSourcePostProcessor.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        java.util.concurrent.CountDownLatch acquired = new java.util.concurrent.CountDownLatch(1);
        Thread[] holder = new Thread[1];
        long[] closingStarted = new long[1];
        try {
            stopRunner().run(context -> {
                BackupWriteGate gate = context.getBean(BackupQuiesceLifecycleAnchor.class).gate();
                holder[0] = new Thread(() -> {
                    BackupWritePermit permit = gate.tryAcquire(BackupWritePermit.Kind.HTTP, "SlowRequest");
                    acquired.countDown();
                    try {
                        Thread.sleep(15_000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        if (permit != null) {
                            permit.close();
                        }
                    }
                }, "slow-request");
                holder[0].start();
                assertThat(acquired.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                closingStarted[0] = System.nanoTime();
            });
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - closingStarted[0])).isGreaterThanOrEqualTo(Duration.ofSeconds(10));
        assertThat(appender.list).anyMatch(event -> event.getFormattedMessage()
                .startsWith("errorCode=BACKUP_QUIESCE_STOP_UNCONFIRMED") && event.getFormattedMessage().contains("SlowRequest"));
        assertThat(scalarLong("SELECT COUNT(*) FROM sys_backup_quiesce_instance WHERE status = 'STOPPED'"))
                .as("書込み中かもしれない instance を STOPPED にしない").isZero();
        holder[0].join(20_000);
    }

    @Test
    void ApplicationRunnerの失敗ではmainのthreadのstartup_permitが返され停止が10秒待たない() throws Exception {
        ObservedHikariDataSource.reset();
        long start = System.nanoTime();
        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() ->
                new org.springframework.boot.builder.SpringApplicationBuilder(RunnerFailureApplication.class)
                        .web(org.springframework.boot.WebApplicationType.NONE)
                        .profiles("prod")
                        .properties(stopProperties())
                        .run());
        assertThat(failure).isNotNull();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
        assertThat(scalarLong("SELECT COUNT(*) FROM sys_backup_quiesce_instance WHERE status = 'STOPPED'"))
                .isEqualTo(1L);
        assertThat(ObservedHikariDataSource.stoppedAtClose).isZero();
    }

    private static String[] stopProperties() {
        DataSourceProperties app = appDataSource();
        return new String[]{"spring.config.name=bq-responder-mysql-none", "spring.main.banner-mode=off",
                "spring.datasource.url=" + app.getUrl(), "spring.datasource.username=" + APP_USER,
                "spring.datasource.password=" + APP_PASSWORD,
                "spring.datasource.type=" + ObservedHikariDataSource.class.getName(),
                "app.scheduling.enabled=false", "app.backup-quiesce.enabled=true"};
    }

    private static org.springframework.boot.test.context.runner.ApplicationContextRunner stopRunner() {
        return new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration.class,
                        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class))
                .withUserConfiguration(com.ses.config.AsyncConfig.class, BackupQuiesceConfiguration.class)
                .withPropertyValues(stopProperties());
    }

    /** 業務の DataSource（Hikari）が閉じた時点の信号表と taskExecutor の task の状態を記録する。 */
    public static class ObservedHikariDataSource extends com.zaxxer.hikari.HikariDataSource {
        static final java.util.concurrent.atomic.AtomicBoolean TASK_DONE = new java.util.concurrent.atomic.AtomicBoolean();
        static final java.util.concurrent.atomic.AtomicInteger closedCount = new java.util.concurrent.atomic.AtomicInteger();
        static volatile long stoppedAtClose = -1;
        static volatile long notStoppedAtClose = -1;
        static volatile boolean taskDoneAtClose;

        static void reset() {
            TASK_DONE.set(false);
            closedCount.set(0);
            stoppedAtClose = -1;
            notStoppedAtClose = -1;
            taskDoneAtClose = false;
        }

        @Override
        public void close() {
            if (closedCount.getAndIncrement() == 0) {
                try {
                    stoppedAtClose = scalarLong("SELECT COUNT(*) FROM sys_backup_quiesce_instance WHERE status = 'STOPPED'");
                    notStoppedAtClose = scalarLong(
                            "SELECT COUNT(*) FROM sys_backup_quiesce_instance WHERE status <> 'STOPPED'");
                } catch (SQLException failure) {
                    throw new IllegalStateException(failure);
                }
                taskDoneAtClose = TASK_DONE.get();
            }
            super.close();
        }
    }

    /** 登録（静止応答の afterSingletonsInstantiated）の後に初期化で例外を投げる（refresh の失敗）。 */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class FailAfterRegistration {
        @org.springframework.context.annotation.Bean
        org.springframework.beans.factory.SmartInitializingSingleton failingAfterRegistration() {
            return () -> {
                throw new IllegalStateException("登録の後の初期化の失敗（試験）");
            };
        }
    }

    /** {@code ApplicationRunner} が失敗する起動（SpringApplication が main の thread で context を閉じる）。 */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.ImportAutoConfiguration({
            org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration.class,
            org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class})
    @org.springframework.context.annotation.Import({com.ses.config.AsyncConfig.class, BackupQuiesceConfiguration.class})
    static class RunnerFailureApplication {
        @org.springframework.context.annotation.Bean
        org.springframework.boot.ApplicationRunner failingRunner() {
            return arguments -> {
                throw new IllegalStateException("起動時処理の失敗（試験）");
            };
        }
    }

    // ---------------------------------------------------------------- helper

    private static Connection root() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
    }

    /** app 側の接続: PROCESS 権限の無い専用 account、session の time_zone は +09:00（provider と違える）。 */
    private static DataSourceProperties appDataSource() {
        DataSourceProperties properties = new DataSourceProperties();
        String url = MYSQL.getJdbcUrl();
        properties.setUrl(url + (url.contains("?") ? "&" : "?") + "sessionVariables=time_zone='%2B09:00'");
        properties.setUsername(APP_USER);
        properties.setPassword(APP_PASSWORD);
        return properties;
    }

    private BackupQuiesceLifecycleAnchor anchor(BackupQuiesceProperties properties, boolean prod) {
        StandardEnvironment environment = new StandardEnvironment();
        if (prod) {
            environment.setActiveProfiles("prod");
        }
        BackupQuiesceLifecycleAnchor anchor = new BackupQuiesceLifecycleAnchor(appDataSource(), properties, environment);
        anchors.add(anchor);
        return anchor;
    }

    /** 実の pool と repository を持つ anchor の repository だけを差し替える（gate は prod の扱い）。 */
    private BackupQuiesceLifecycleAnchor wrap(BackupQuiesceLifecycleAnchor real, BackupQuiesceSignalRepository repository,
                                              BackupQuiesceProperties properties) {
        BackupWriteGate gate = new BackupWriteGate(BackupWriteGate.Mode.ARMED, true, properties.getFenceAfter(),
                properties.getDeferredWritesMax());
        BackupQuiesceLifecycleAnchor anchor = new BackupQuiesceLifecycleAnchor(properties, gate, repository, true);
        anchors.add(anchor);
        return anchor;
    }

    private BackupQuiesceResponder responder(BackupQuiesceLifecycleAnchor anchor) {
        Executor direct = Runnable::run;
        BackupQuiesceResponder responder = new BackupQuiesceResponder(anchor, new BackupQuiesceMetrics(anchor.gate()),
                () -> direct, true);
        responders.add(responder);
        return responder;
    }

    private BackupQuiesceResponder start(BackupQuiesceLifecycleAnchor anchor) {
        BackupQuiesceResponder responder = responder(anchor);
        responder.afterSingletonsInstantiated();
        responder.onApplicationReady(null);
        return responder;
    }

    /** provider と同じく session の time_zone を +00:00 にし、UTC_TIMESTAMP(6) で要求を登録する。 */
    private static long insertRequest(String mode, String jobKind, int leaseSeconds) throws SQLException {
        try (Connection root = root(); Statement st = root.createStatement()) {
            st.execute("SET SESSION time_zone = '+00:00'");
            st.executeUpdate("INSERT INTO sys_backup_quiesce_request (request_token, mode, job_kind, status, lease_seconds, "
                    + "requested_by, protocol_version, requested_at, lease_expires_at) VALUES (REPLACE(UUID(), '-', ''), '"
                    + mode + "', '" + jobKind + "', 'REQUESTED', " + leaseSeconds + ", 'mysql-test:host', 1, "
                    + "UTC_TIMESTAMP(6), TIMESTAMPADD(SECOND, " + leaseSeconds + ", UTC_TIMESTAMP(6)))",
                    Statement.RETURN_GENERATED_KEYS);
            try (ResultSet keys = st.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return keys.getLong(1);
            }
        }
    }

    private static void releaseRequest(long id) throws SQLException {
        try (Connection root = root(); Statement st = root.createStatement()) {
            st.executeUpdate("UPDATE sys_backup_quiesce_request SET status = 'RELEASED', released_at = UTC_TIMESTAMP(6), "
                    + "release_reason = 'COMPLETED' WHERE id = " + id);
        }
    }

    private static void killAppConnections() {
        try (Connection root = root(); Statement st = root.createStatement()) {
            List<Long> ids = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT ID FROM information_schema.PROCESSLIST WHERE USER = '"
                    + APP_USER + "'")) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
            for (long id : ids) {
                try {
                    st.execute("KILL " + id);
                } catch (SQLException alreadyGone) {
                    // 既に切れた接続は無視する
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String ackState(long requestId, BackupQuiesceLifecycleAnchor anchor) {
        try {
            return scalarString("SELECT state FROM sys_backup_quiesce_ack WHERE request_id = " + requestId);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static LocalDateTime lastBeat(BackupQuiesceLifecycleAnchor anchor) throws SQLException {
        try (Connection root = root(); Statement st = root.createStatement();
             ResultSet rs = st.executeQuery("SELECT last_beat_at FROM sys_backup_quiesce_instance WHERE instance_id = '"
                     + anchor.instanceId() + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getObject(1, LocalDateTime.class);
        }
    }

    private static LocalDateTime utcNow() throws SQLException {
        try (Connection root = root(); Statement st = root.createStatement();
             ResultSet rs = st.executeQuery("SELECT UTC_TIMESTAMP(6)")) {
            rs.next();
            return rs.getObject(1, LocalDateTime.class);
        }
    }

    private static org.assertj.core.data.TemporalUnitOffset within(Duration duration) {
        return new org.assertj.core.data.TemporalUnitWithinOffset(duration.toMillis(), java.time.temporal.ChronoUnit.MILLIS);
    }

    private static String scalarString(String sql) throws SQLException {
        try (Connection root = root(); Statement st = root.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static long scalarLong(String sql) throws SQLException {
        try (Connection root = root(); Statement st = root.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }

    private static void awaitTrue(BooleanSupplier condition, Duration max, String what) throws InterruptedException {
        long giveUp = System.nanoTime() + max.toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime() - giveUp).as("待っても満たされない: " + what).isNegative();
            Thread.sleep(100);
        }
    }

    /** 実の repository に失敗を差し込む（結果不明の ack と lock 待ちを作る）。 */
    static final class FailingRepository implements BackupQuiesceSignalRepository {
        private final BackupQuiesceSignalRepository delegate;
        volatile Runnable afterQuiescedInsert = () -> { };
        volatile boolean skipQuiescedInsert;
        volatile String instanceId;
        volatile int quiescedInserts;
        volatile int refusedDuplicates;
        volatile int refusedTimeouts;
        volatile long maxRefusedMillis;

        FailingRepository(BackupQuiesceSignalRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public Registered registerInstance(InstanceRegistration registration) {
            return delegate.registerInstance(registration);
        }

        @Override
        public boolean heartbeat(String instanceId, String status, String gateState) {
            return delegate.heartbeat(instanceId, status, gateState);
        }

        @Override
        public boolean markStopped(String instanceId) {
            return delegate.markStopped(instanceId);
        }

        @Override
        public Optional<RequestView> findActiveRequest() {
            return delegate.findActiveRequest();
        }

        @Override
        public Optional<RequestView> findRequest(long requestId) {
            return delegate.findRequest(requestId);
        }

        @Override
        public Observation observeAppHosts() {
            return delegate.observeAppHosts();
        }

        @Override
        public void insertQuiescedAck(long requestId, String instanceId, String schedulerState, int drainMillis,
                                      LocalDateTime holdUntil, Observation observation) {
            quiescedInserts++;
            if (!skipQuiescedInsert) {
                delegate.insertQuiescedAck(requestId, instanceId, schedulerState, drainMillis, holdUntil, observation);
            }
            Runnable failure = afterQuiescedInsert;
            afterQuiescedInsert = () -> { };
            failure.run();
        }

        @Override
        public void insertRefusedAck(long requestId, String instanceId, String schedulerState, int drainMillis,
                                     String refuseReason, Observation observation) {
            long start = System.nanoTime();
            try {
                delegate.insertRefusedAck(requestId, instanceId, schedulerState, drainMillis, refuseReason, observation);
            } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                refusedDuplicates++;
                throw duplicate;
            } catch (RuntimeException timeout) {
                refusedTimeouts++;
                throw timeout;
            } finally {
                maxRefusedMillis = Math.max(maxRefusedMillis, Duration.ofNanos(System.nanoTime() - start).toMillis());
            }
        }

        @Override
        public Optional<AckState> findAckState(long requestId, String instanceId) {
            return delegate.findAckState(requestId, instanceId);
        }

        @Override
        public int updateHoldUntil(long requestId, String instanceId, LocalDateTime holdUntil) {
            return delegate.updateHoldUntil(requestId, instanceId, holdUntil);
        }

        @Override
        public int updateObservation(long requestId, String instanceId, Observation observation) {
            return delegate.updateObservation(requestId, instanceId, observation);
        }

        @Override
        public int markResumed(long requestId, String instanceId, String resumeReason) {
            return delegate.markResumed(requestId, instanceId, resumeReason);
        }

        @Override
        public int purgeRequests(Duration retention) {
            return delegate.purgeRequests(retention);
        }

        @Override
        public int purgeInstances(Duration retention, String ownInstanceId) {
            return delegate.purgeInstances(retention, ownInstanceId);
        }
    }
}
