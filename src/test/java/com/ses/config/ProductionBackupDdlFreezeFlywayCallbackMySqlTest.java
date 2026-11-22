package com.ses.config;

import com.ses.migration.ProductionFlywayJobConfig;
import com.ses.test.MySQLContainer;
import com.ses.test.MySqlTestImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RB-QUIESCE BWQ-008（BWQ-AC-006-01・U-5・design.md §8）: 実 MySQL 8.4 で、本番の Flyway job の設定（{@code ops/flyway/flyway-prod.conf}）
 * が backup の DDL 凍結の lock {@code ses_backup_ddl_freeze} を取ってから migration を適用し、終わりで返すこと。別の session
 * （backup の保持 process に当たる）が lock を持つ間は migration を1件も適用せず日本語の message で失敗する。
 *
 * <p>競合の場合は待ちを短くした callback（試験用の constructor）に差し替え、120 秒待たない。他の場合は job の設定のまま
 * （callback は class 名から作る）にする。
 */
@Tag("mysql")
@Testcontainers
class ProductionBackupDdlFreezeFlywayCallbackMySqlTest {

    private static final String LOCK = ProductionBackupDdlFreezeFlywayCallback.LOCK_NAME;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Container
    @SuppressWarnings("resource") // ライフサイクルは Testcontainers Extension が管理する。
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MySqlTestImages.image())
            .withDatabaseName("ses_ddl_freeze")
            .withUsername("root")
            .withPassword("ses")
            .withStartupTimeout(Duration.ofMinutes(10))
            .withStartupAttempts(3);

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void 別のsessionがlockを持つ間はmigrationを1件も適用せず日本語のmessageで失敗する() throws Exception {
        String url = createDatabase("ses_ddl_freeze_held");
        try (Connection holder = connect(url)) {
            assertThat(scalar(holder, "SELECT GET_LOCK('" + LOCK + "', 0)")).as("backup の保持 process に当たる session").isEqualTo(1L);
            long holderId = scalar(holder, "SELECT CONNECTION_ID()");
            ProductionBackupDdlFreezeFlywayCallback freeze = new ProductionBackupDdlFreezeFlywayCallback(2);
            Flyway flyway = job(url).callbacks(new ProductionAdminInitialPasswordFlywayCallback(), freeze,
                    new LegacyDatabaseFlywayCallback()).load();

            FlywayException failure = assertThrows(FlywayException.class, flyway::migrate);

            assertThat(failure.getMessage()).contains("backup が実行中のため migration を始めません（" + LOCK + "）")
                    .contains("backup の timer が止まっていることを確かめて再実行してください")
                    .contains("migration は1件も適用していません");
            assertThat(freeze.holdsLock()).isFalse();
            try (Connection check = connect(url)) {
                assertThat(scalar(check, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() "
                        + "AND table_name <> 'flyway_schema_history'")).as("migration が1件も適用されていない").isZero();
                // 履歴表は Flyway が BEFORE_MIGRATE の前に作る（空の DB の初回だけ）。行は1件も無い
                if (scalar(check, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() "
                        + "AND table_name = 'flyway_schema_history'") == 1L) {
                    assertThat(scalar(check, "SELECT COUNT(*) FROM flyway_schema_history")).isZero();
                }
                assertThat(scalar(check, "SELECT IS_USED_LOCK('" + LOCK + "')")).as("lock は保持の session のまま")
                        .isEqualTo(holderId);
            }
        }
        try (Connection check = connect(url)) {
            assertThat(scalar(check, "SELECT IS_FREE_LOCK('" + LOCK + "')")).isEqualTo(1L);
        }
    }

    @Test
    @Timeout(value = 600, unit = TimeUnit.SECONDS)
    void lockが空くまで待ってから取りmigrationの間は持ち終わりで返す() throws Exception {
        String url = createDatabase("ses_ddl_freeze_free");
        LockProbe probe = new LockProbe(url);
        Thread releaser;
        long started;
        try (Connection holder = connect(url)) {
            assertThat(scalar(holder, "SELECT GET_LOCK('" + LOCK + "', 0)")).isEqualTo(1L);
            // 保持 process が 3 秒後に lock を返す（job は最長 120 秒待つ）
            releaser = new Thread(() -> {
                try {
                    Thread.sleep(3_000);
                    scalar(holder, "SELECT RELEASE_LOCK('" + LOCK + "')");
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }, "ddl-freeze-holder");
            releaser.start();
            // job の設定の callback（class 名から作る）に、migration の間の lock を外から見る probe を足す
            Callback[] configured = job(url).getCallbacks();
            Callback[] callbacks = java.util.Arrays.copyOf(configured, configured.length + 1);
            callbacks[configured.length] = probe;
            Flyway flyway = job(url).callbacks(callbacks).load();
            assertThat(java.util.Arrays.stream(configured).map(callback -> callback.getClass().getName()).toList())
                    .contains(ProductionBackupDdlFreezeFlywayCallback.class.getName());

            started = System.nanoTime();
            MigrateResult result = flyway.migrate();

            assertThat(Duration.ofNanos(System.nanoTime() - started)).as("lock が空くまで待った")
                    .isGreaterThanOrEqualTo(Duration.ofSeconds(2));
            assertThat(result.success).isTrue();
            assertThat(result.migrationsExecuted).isPositive();
            releaser.join(10_000);
        }
        assertThat(probe.freeDuringMigration).as("migration の間は job が lock を持つ（他の session から空いていない）")
                .isEqualTo(0L);
        try (Connection check = connect(url)) {
            assertThat(scalar(check, "SELECT IS_FREE_LOCK('" + LOCK + "')")).as("終わりで返した").isEqualTo(1L);
        }
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void migrationが失敗してもlockを返す() throws Exception {
        // 履歴表の無い非空の schema は baseline（V9）の後 V10 で失敗する（FlywayProdFreshInstallMySqlTest の対照実験と同じ）
        String url = createDatabase("ses_ddl_freeze_failed");
        try (Connection connection = connect(url); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE bwq_stray_object (id INT PRIMARY KEY)");
        }

        assertThrows(FlywayException.class, () -> job(url).load().migrate());

        try (Connection check = connect(url)) {
            assertThat(scalar(check, "SELECT IS_FREE_LOCK('" + LOCK + "')")).as("AFTER_MIGRATE_ERROR で返した").isEqualTo(1L);
        }
    }

    /** migration の1件目の後に、別の接続で lock が空いているかを見る。 */
    static final class LockProbe implements Callback {
        private final String url;
        volatile Long freeDuringMigration;

        LockProbe(String url) {
            this.url = url;
        }

        @Override
        public boolean supports(Event event, Context context) {
            return event == Event.AFTER_EACH_MIGRATE;
        }

        @Override
        public boolean canHandleInTransaction(Event event, Context context) {
            return true;
        }

        @Override
        public void handle(Event event, Context context) {
            if (freeDuringMigration != null) {
                return;
            }
            try (Connection connection = connect(url)) {
                freeDuringMigration = scalar(connection, "SELECT IS_FREE_LOCK('" + LOCK + "')");
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public String getCallbackName() {
            return "ZzBackupDdlFreezeLockProbe";
        }
    }

    // ---------------------------------------------------------------- helper

    private static org.flywaydb.core.api.configuration.FluentConfiguration job(String url) {
        return ProductionFlywayJobConfig.configure(url, MYSQL.getUsername(), MYSQL.getPassword(),
                new BCryptPasswordEncoder().encode(randomPassword()));
    }

    private static String randomPassword() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return "Init1" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 同じ container に空の DB を作り、application.yml の既定の DB_URL と同じ接続 parameter を付けた URL を返す。 */
    private static String createDatabase(String database) throws SQLException {
        try (Connection root = connect(MYSQL.getJdbcUrl()); Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
            statement.execute("CREATE DATABASE `" + database + "`");
        }
        String base = MYSQL.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
        org.springframework.beans.factory.config.YamlPropertiesFactoryBean yaml =
                new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
        yaml.setResources(new org.springframework.core.io.ClassPathResource("application.yml"));
        String datasourceUrl = String.valueOf(java.util.Objects.requireNonNull(yaml.getObject())
                .get("spring.datasource.url"));
        int query = datasourceUrl.indexOf('?');
        String parameters = query < 0 ? "" : datasourceUrl.substring(query + 1).replaceAll("}$", "");
        return parameters.isEmpty() ? base : base + (base.contains("?") ? "&" : "?") + parameters;
    }

    private static Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url, MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }
}
