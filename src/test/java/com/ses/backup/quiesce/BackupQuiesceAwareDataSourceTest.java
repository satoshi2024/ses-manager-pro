package com.ses.backup.quiesce;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zaxxer.hikari.HikariConfigMXBean;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.actuate.autoconfigure.health.HealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.jdbc.DataSourceHealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.jdbc.DataSourcePoolMetricsAutoConfiguration;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RB-QUIESCE BWQ-004: 業務の DataSource の最後の防線（{@link BackupQuiesceAwareDataSource}）と停止の時の STOPPED の書込み
 * （{@link BackupQuiesceDataSourcePostProcessor}）を H2 と mock で確かめる（design.md §6.5・§6.11）。
 */
class BackupQuiesceAwareDataSourceTest {

    private final AtomicLong now = new AtomicLong(1_000_000_000_000L);

    private BackupWriteGate gate() {
        BackupWriteGate gate = new BackupWriteGate(BackupWriteGate.Mode.ARMED, true, Duration.ofSeconds(20), 100,
                now::get, Clock.fixed(Instant.parse("2026-11-21T00:00:00Z"), ZoneOffset.UTC));
        gate.recordHeartbeatSuccess();
        gate.armOpen();
        return gate;
    }

    private static Connection connection(boolean autoCommit) throws SQLException {
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(autoCommit);
        return connection;
    }

    private static DataSource dataSource(Connection connection) throws SQLException {
        DataSource target = mock(DataSource.class);
        when(target.getConnection()).thenReturn(connection);
        return target;
    }

    // ---------------------------------------------------------------- 借りる時

    @Test
    void 閉じている時だけpermitの無いthreadの接続を読取り専用にする() throws Exception {
        BackupWriteGate gate = gate();
        Connection raw = connection(true);
        BackupQuiesceAwareDataSource dataSource = new BackupQuiesceAwareDataSource(dataSource(raw), gate);

        Connection open = dataSource.getConnection();
        verify(raw, never()).setReadOnly(anyBoolean());
        assertThat(gate.writableConnectionCount()).isEqualTo(1);
        open.close();
        open.close();
        assertThat(gate.writableConnectionCount()).as("返すと数を戻す（2回目の close は効かない）").isZero();

        gate.beginClosing(1);
        Connection closed = dataSource.getConnection();
        verify(raw).setReadOnly(true);
        assertThat(gate.writableConnectionCount()).isZero();
        closed.close();
        assertThat(gate.writableConnectionCount()).isZero();
    }

    @Test
    void permitを持つthreadの接続は閉じていても読取り専用にせず書込み可能に数える() throws Exception {
        BackupWriteGate gate = gate();
        Connection raw = connection(true);
        BackupQuiesceAwareDataSource dataSource = new BackupQuiesceAwareDataSource(dataSource(raw), gate);
        try (BackupWritePermit permit = gate.tryAcquire(BackupWritePermit.Kind.HTTP, "POST /api/x")) {
            gate.beginClosing(1);
            Connection connection = dataSource.getConnection();
            verify(raw, never()).setReadOnly(anyBoolean());
            assertThat(gate.writableConnectionCount()).isEqualTo(1);
            assertThat(gate.isDrained()).isFalse();
            connection.close();
        }
        assertThat(gate.isDrained()).isTrue();
    }

    @Test
    void setReadOnlyのtrueはautocommitの間に成功した時だけ数から外しfalseは閉じていて静止で読取り専用なら無視する() throws Exception {
        BackupWriteGate gate = gate();
        Connection autoCommit = connection(true);
        Connection inTransaction = connection(false);
        DataSource target = mock(DataSource.class);
        when(target.getConnection()).thenReturn(autoCommit, inTransaction);
        BackupQuiesceAwareDataSource dataSource = new BackupQuiesceAwareDataSource(target, gate);

        Connection first = dataSource.getConnection();
        Connection second = dataSource.getConnection();
        assertThat(gate.writableConnectionCount()).isEqualTo(2);
        first.setReadOnly(true);
        assertThat(gate.writableConnectionCount()).as("autocommit の間の setReadOnly(true) は数から外す").isEqualTo(1);
        second.setReadOnly(true);
        assertThat(gate.writableConnectionCount()).as("transaction の途中は数え続ける").isEqualTo(1);

        // 静止の前に始まった読取り専用の transaction の終わりの setReadOnly(false) で書込み可能に戻らない
        gate.beginClosing(1);
        first.setReadOnly(false);
        verify(autoCommit, never()).setReadOnly(false);
        assertThat(gate.writableConnectionCount()).isEqualTo(1);
        second.close();
        assertThat(gate.isDrained()).isTrue();
        gate.reopen(1);
        first.setReadOnly(false);
        verify(autoCommit, never()).setReadOnly(false);
        first.close();

        // 開いている時の setReadOnly(false) は数えてから委譲する
        Connection third = connection(true);
        when(target.getConnection()).thenReturn(third);
        Connection reopened = dataSource.getConnection();
        reopened.setReadOnly(true);
        assertThat(gate.writableConnectionCount()).isZero();
        reopened.setReadOnly(false);
        verify(third).setReadOnly(false);
        assertThat(gate.writableConnectionCount()).isEqualTo(1);
        reopened.close();
        assertThat(gate.writableConnectionCount()).isZero();
    }

    @Test
    void 包む前のHikariへunwrapとisWrapperForを委譲する() throws Exception {
        try (HikariDataSource hikari = new HikariDataSource()) {
            hikari.setJdbcUrl("jdbc:h2:mem:bq_unwrap;MODE=MySQL");
            BackupQuiesceAwareDataSource dataSource = new BackupQuiesceAwareDataSource(hikari, gate());
            assertThat(dataSource.unwrap(HikariDataSource.class)).isSameAs(hikari);
            assertThat(dataSource.isWrapperFor(HikariDataSource.class)).isTrue();
            assertThat(dataSource.unwrap(HikariConfigMXBean.class)).isSameAs(hikari);
            assertThat(dataSource.getTargetDataSource()).isSameAs(hikari);
        }
    }

    // ---------------------------------------------------------------- I-4 の交互の順（決定的）

    /**
     * 借りる側の手順（数を増やす → 状態と permit を読む → 閉じていれば数を戻す）と gate の側の手順（CLOSING にする → 数を読む →
     * もう一度読む）の交互の順を全て並べ（20 通り）、「gate が排水の完了を確定したのに、借りる側が書込み可能な接続を得た」順が
     * 1つも無いことを確かめる。
     *
     * <p><b>これは手順の模型の検証である</b>（2a review P3-6）。20 通りは試験の中の数と状態の変数で I-4 の手順を再現して並べた
     * もので、実の {@link BackupWriteGate} の code を各 step で止めて駆動してはいない（gate の操作はそれぞれ1つの呼出しで、間に
     * 割り込む口が無い）。実の gate は最後に1つの順だけを再現する。実の code の I-4 の実証は {@code BackupWriteGateTest} の
     * 100 thread の競合と、実 MySQL の負荷の試験（{@code BackupWriteQuiesceMySqlTest}。Runner 待ち）が担う。
     */
    @Test
    void 借りるthreadと閉じるthreadの交互の順を全て並べても排水の確定と書込み可能な接続が両立しない() {
        List<String> orders = new ArrayList<>();
        interleave("", 3, 3, orders);
        assertThat(orders).hasSize(20);
        int drainedOrders = 0;
        for (String order : orders) {
            AtomicLong counter = new AtomicLong();
            boolean[] closing = {false};
            boolean borrowerAllowed = false;
            boolean borrowerRead = false;
            long firstRead = -1;
            long secondRead = -1;
            int borrowerStep = 0;
            int gateStep = 0;
            for (char actor : order.toCharArray()) {
                if (actor == 'B') {
                    switch (borrowerStep++) {
                        case 0 -> counter.incrementAndGet();
                        case 1 -> {
                            borrowerAllowed = !closing[0];
                            borrowerRead = true;
                        }
                        default -> {
                            if (!borrowerAllowed) {
                                counter.decrementAndGet();
                            }
                        }
                    }
                } else {
                    switch (gateStep++) {
                        case 0 -> closing[0] = true;
                        case 1 -> firstRead = counter.get();
                        default -> secondRead = counter.get();
                    }
                }
            }
            boolean drained = firstRead == 0 && secondRead == 0;
            if (drained) {
                drainedOrders++;
            }
            assertThat(drained && borrowerRead && borrowerAllowed)
                    .as("順 %s で排水の確定と書込み可能な接続が両立した", order).isFalse();
        }
        assertThat(drainedOrders).as("排水が確定する順もある（試験の非空虚性）").isPositive();

        // 同じ順を実の gate で再現する（数を増やす・読む・戻すを gate の操作として並べる）
        BackupWriteGate gate = gate();
        assertThat(gate.tryRegisterWritableConnection()).isTrue();
        gate.beginClosing(1);
        assertThat(gate.isDrained()).as("閉じる前に数えた接続は排水を止める").isFalse();
        gate.releaseWritableConnection();
        assertThat(gate.tryRegisterWritableConnection()).as("閉じた後に借りた接続は書込み可能にならない").isFalse();
        assertThat(gate.isDrained()).isTrue();
    }

    private static void interleave(String prefix, int borrower, int closer, List<String> sink) {
        if (borrower == 0 && closer == 0) {
            sink.add(prefix);
            return;
        }
        if (borrower > 0) {
            interleave(prefix + "B", borrower - 1, closer, sink);
        }
        if (closer > 0) {
            interleave(prefix + "G", borrower, closer - 1, sink);
        }
    }

    // ---------------------------------------------------------------- 停止の時の STOPPED（§6.11）

    @Test
    void 停止の時はHikariを閉じてからSTOPPEDを求める() {
        List<String> order = new ArrayList<>();
        BackupWriteGate gate = gate();
        BackupQuiesceLifecycleAnchor anchor = registeredAnchor(gate, order);
        BackupQuiesceDataSourcePostProcessor processor = processor(anchor, Duration.ofSeconds(10));
        RecordingDataSource raw = new RecordingDataSource(order);

        Object wrapped = processor.postProcessAfterInitialization(raw, "dataSource");
        assertThat(wrapped).isInstanceOf(BackupQuiesceAwareDataSource.class);
        assertThat(processor.requiresDestruction(raw)).as("破棄の callback は包む前の bean に付く").isTrue();
        assertThat(processor.requiresDestruction(wrapped)).isFalse();
        assertThat(processor.postProcessAfterInitialization(raw, "otherDataSource")).as("dataSource だけを包む").isSameAs(raw);

        BackupWritePermit startup = gate.tryAcquire(BackupWritePermit.Kind.STARTUP, "startup");
        processor.postProcessBeforeDestruction(raw, "dataSource");
        assertThat(startup.isClosed()).as("呼んだ thread の permit を返す").isTrue();
        assertThat(gate.state()).isEqualTo(BackupWriteGate.State.SHUTDOWN);
        assertThat(order).containsExactly("close", "stopped");
    }

    @Test
    void 十秒で排水が終わらなければHikariを閉じずSTOPPEDを求めずWARNを出す() throws Exception {
        List<String> order = new ArrayList<>();
        BackupWriteGate gate = gate();
        BackupQuiesceLifecycleAnchor anchor = registeredAnchor(gate, order);
        BackupQuiesceDataSourcePostProcessor processor = processor(anchor, Duration.ofSeconds(10));
        RecordingDataSource raw = new RecordingDataSource(order);
        processor.postProcessAfterInitialization(raw, "dataSource");
        BackupWritePermit running = gate.tryAcquireDetached(BackupWritePermit.Kind.ASYNC, "LongRunningTask");

        Logger logger = (Logger) LoggerFactory.getLogger(BackupQuiesceDataSourcePostProcessor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            long before = now.get();
            processor.postProcessBeforeDestruction(raw, "dataSource");
            assertThat(now.get() - before).as("10 秒待つ").isGreaterThanOrEqualTo(Duration.ofSeconds(10).toNanos());
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(order).as("Hikari を閉じず STOPPED も書かない").isEmpty();
        assertThat(appender.list).filteredOn(event -> event.getLevel() == Level.WARN)
                .anyMatch(event -> event.getFormattedMessage().startsWith("errorCode=BACKUP_QUIESCE_STOP_UNCONFIRMED")
                        && event.getFormattedMessage().contains("async:LongRunningTask"));
        running.close();
    }

    @Test
    void INERTのgateでは包まず破棄の時に何もしない() {
        BackupQuiesceProperties properties = new BackupQuiesceProperties();
        BackupWriteGate inert = new BackupWriteGate(BackupWriteGate.Mode.INERT, false, properties.getFenceAfter(), 10);
        BackupQuiesceLifecycleAnchor anchor = new BackupQuiesceLifecycleAnchor(properties, inert, null, false);
        BackupQuiesceDataSourcePostProcessor processor = processor(anchor, Duration.ofSeconds(10));
        RecordingDataSource raw = new RecordingDataSource(new ArrayList<>());
        assertThat(processor.postProcessAfterInitialization(raw, "dataSource")).isSameAs(raw);
        assertThat(processor.requiresDestruction(raw)).isFalse();
    }

    // ---------------------------------------------------------------- Hikari の metric と health

    @Test
    void 包んでもHikariのmetricとdbのhealthを保つ() {
        String url = "jdbc:h2:mem:bq_metrics_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;INIT=RUNSCRIPT FROM 'classpath:sql/schema-backup-quiesce-h2.sql'";
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class,
                        DataSourceAutoConfiguration.class, MetricsAutoConfiguration.class,
                        SimpleMetricsExportAutoConfiguration.class, DataSourcePoolMetricsAutoConfiguration.class,
                        HealthContributorAutoConfiguration.class, DataSourceHealthContributorAutoConfiguration.class))
                .withUserConfiguration(BackupQuiesceConfiguration.class)
                .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.username=sa",
                        "spring.datasource.password=", "app.backup-quiesce.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    DataSource dataSource = context.getBean("dataSource", DataSource.class);
                    assertThat(dataSource).isInstanceOf(BackupQuiesceAwareDataSource.class);
                    try (Connection connection = dataSource.getConnection()) {
                        assertThat(connection.isValid(1)).isTrue();
                    }
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(registry.find("hikaricp.connections").gauge()).as("Hikari の metric").isNotNull();
                    assertThat(registry.find("ses.backup.quiesce.gate.state").gauge()).isNotNull();
                    assertThat(context.getBean("dbHealthContributor", HealthIndicator.class).health().getStatus())
                            .isEqualTo(Status.UP);
                });
    }

    // ---------------------------------------------------------------- helper

    private BackupQuiesceDataSourcePostProcessor processor(BackupQuiesceLifecycleAnchor anchor, Duration drainWait) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean(BackupQuiesceLifecycleAnchor.BEAN_NAME, anchor);
        ObjectProvider<BackupQuiesceLifecycleAnchor> provider = beans.getBeanProvider(BackupQuiesceLifecycleAnchor.class);
        return new BackupQuiesceDataSourcePostProcessor(provider, drainWait,
                duration -> now.addAndGet(duration.toNanos()));
    }

    private static BackupQuiesceLifecycleAnchor registeredAnchor(BackupWriteGate gate, List<String> order) {
        BackupQuiesceResponderTest.FakeRepository repository =
                new BackupQuiesceResponderTest.FakeRepository(new AtomicLong()) {
                    @Override
                    public boolean markStopped(String instanceId) {
                        order.add("stopped");
                        return true;
                    }
                };
        BackupQuiesceLifecycleAnchor anchor = new BackupQuiesceLifecycleAnchor(new BackupQuiesceProperties(), gate,
                repository, true);
        anchor.register();
        return anchor;
    }

    /** 閉じた順を記録する包む前の DataSource（Hikari の代わり）。 */
    static final class RecordingDataSource extends AbstractDataSource implements AutoCloseable {
        private final List<String> order;

        RecordingDataSource(List<String> order) {
            this.order = order;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = mock(Connection.class);
            when(connection.getAutoCommit()).thenReturn(true);
            return connection;
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override
        public void close() {
            order.add("close");
        }
    }
}
