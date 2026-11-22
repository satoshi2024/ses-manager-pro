package com.ses.backup.quiesce;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Locale;
import java.util.Objects;

/**
 * 信号表だけに使う制御用・heartbeat 用の小さな接続 pool の holder（design.md §5.2・§6.5.4）。
 *
 * <ul>
 *   <li>業務の DataSource と同じ接続先・account（{@link DataSourceProperties}）から、状態機械用
 *       {@value #CONTROL_POOL_NAME}（最大2）と heartbeat 用 {@value #HEARTBEAT_POOL_NAME}（最大1）を作る。業務の pool の設定には
 *       影響しない。</li>
 *   <li><b>{@code DataSource} 型の bean にしない</b>（{@code DataSourceAutoConfiguration} が業務の DataSource を作らなくなり、
 *       単一候補を前提にする {@code JdbcTemplate}・MyBatis-Plus の自動構成が崩れるため。前例は {@code PrivacyJournalConnections}）。
 *       pool は同じ package の {@code JdbcBackupQuiesceSignalRepository} にだけ渡す。</li>
 *   <li>短い timeout: {@code innodb_lock_wait_timeout = 5}、Connector/J の {@code connectTimeout=5000}・{@code socketTimeout=10000}、
 *       {@code JdbcTemplate} の query timeout 5 秒、Hikari の {@code connectionTimeout=3000}・{@code validationTimeout=2000}
 *       （D3-P3-3）。どれも {@code fence-after − heartbeat-interval} より短い（{@link BackupQuiesceProperties} が検査する）。</li>
 *   <li>起動時に接続しない（{@code minimumIdle=0}・初期化時の接続確認なし）。autocommit で、transaction を跨がない。</li>
 * </ul>
 */
final class BackupQuiesceControlConnectionPool implements AutoCloseable {

    static final String CONTROL_POOL_NAME = "ses-backup-quiesce-control";
    static final String HEARTBEAT_POOL_NAME = "ses-backup-quiesce-heartbeat";

    private final HikariDataSource control;
    private final HikariDataSource heartbeat;

    BackupQuiesceControlConnectionPool(DataSourceProperties properties) {
        Objects.requireNonNull(properties, "properties");
        this.control = new HikariDataSource(config(properties, CONTROL_POOL_NAME, 2));
        this.heartbeat = new HikariDataSource(config(properties, HEARTBEAT_POOL_NAME, 1));
    }

    static HikariConfig config(DataSourceProperties properties, String poolName, int maximumPoolSize) {
        String url = properties.determineUrl();
        HikariConfig config = new HikariConfig();
        config.setPoolName(poolName);
        config.setJdbcUrl(url);
        config.setUsername(properties.determineUsername());
        config.setPassword(properties.determinePassword());
        String driver = properties.determineDriverClassName();
        if (driver != null && !driver.isBlank()) {
            config.setDriverClassName(driver);
        }
        config.setMaximumPoolSize(maximumPoolSize);
        config.setMinimumIdle(0);
        config.setInitializationFailTimeout(-1);
        config.setAutoCommit(true);
        config.setRegisterMbeans(false);
        config.setConnectionTimeout(BackupQuiesceProperties.CONTROL_POOL_CONNECTION_TIMEOUT.toMillis());
        config.setValidationTimeout(BackupQuiesceProperties.CONTROL_POOL_VALIDATION_TIMEOUT.toMillis());
        if (isMySql(url)) {
            config.setConnectionInitSql("SET SESSION innodb_lock_wait_timeout = "
                    + BackupQuiesceProperties.CONTROL_LOCK_WAIT_TIMEOUT.getSeconds());
            // URL に同じ key があっても、ここで渡す値が優先する（Connector/J は接続の Properties を URL の後に重ねる）
            config.addDataSourceProperty("connectTimeout", Long.toString(BackupQuiesceProperties.CONTROL_CONNECT_TIMEOUT.toMillis()));
            config.addDataSourceProperty("socketTimeout", Long.toString(BackupQuiesceProperties.CONTROL_SOCKET_TIMEOUT.toMillis()));
        }
        return config;
    }

    private static boolean isMySql(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).startsWith("jdbc:mysql:");
    }

    /** 状態機械用の {@code JdbcTemplate}（query timeout 5 秒）。 */
    JdbcTemplate controlTemplate() {
        return template(control);
    }

    /** heartbeat 用の {@code JdbcTemplate}（query timeout 5 秒）。 */
    JdbcTemplate heartbeatTemplate() {
        return template(heartbeat);
    }

    private static JdbcTemplate template(HikariDataSource dataSource) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setQueryTimeout((int) BackupQuiesceProperties.CONTROL_QUERY_TIMEOUT.getSeconds());
        return template;
    }

    /** 試験と診断用（同じ package だけ）。 */
    HikariDataSource controlDataSource() {
        return control;
    }

    HikariDataSource heartbeatDataSource() {
        return heartbeat;
    }

    @Override
    public void close() {
        control.close();
        heartbeat.close();
    }
}
