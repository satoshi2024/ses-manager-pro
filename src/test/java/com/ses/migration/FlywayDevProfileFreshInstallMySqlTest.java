package com.ses.migration;

import com.ses.config.LegacyDatabaseFlywayCallback;
import com.ses.test.MySQLContainer;
import com.ses.test.MySqlTestImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RB-PRERUN 4: dev profile（{@code application-dev.yml} の {@code db/migration} + {@code db/migration-dev}）で、
 * 実MySQL 8の空DBへV1からLATESTまで適用できることを確かめる。
 *
 * <p>以前はdevのseed（{@code migration-dev/V145}）が入れたAI recommendation sampleのrunにtenantが無く、
 * {@code V173} の検査（tenantを特定できないため移行を停止しました）で必ず止まっていた。
 * {@code migration-dev/V172_1} がV173より前にseedのrunへ 'default' を補い、V173がitem・feedback・outcomeへ写す。</p>
 *
 * <p>Flywayの設定はdev起動と同じにする: locationsは {@code application-dev.yml} から読み、
 * out-of-order・baselineは {@code application.yml} の値、callbackはSpringが登録する
 * {@link LegacyDatabaseFlywayCallback}。prod（{@link FlywayProdFreshInstallMySqlTest}）・test profileは
 * {@code db/migration-dev} を読まないため、本testの対象外。</p>
 */
@Tag("mysql")
@Testcontainers
class FlywayDevProfileFreshInstallMySqlTest {

    @Container
    @SuppressWarnings("resource") // ライフサイクルは Testcontainers Extension が管理する。
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MySqlTestImages.image())
            .withDatabaseName("ses_dev_profile_fresh_install")
            .withUsername("root")
            .withPassword("ses")
            .withStartupTimeout(Duration.ofMinutes(10))
            .withStartupAttempts(3);

    @Test
    void dev_profileのlocationsで空DBへV1からLATESTまで適用できV173の前にseedのtenantが補われる() throws Exception {
        Properties base = yaml("application.yml");
        Properties dev = yaml("application-dev.yml");
        String[] locations = Arrays.stream(dev.getProperty("spring.flyway.locations").split(","))
                .map(String::trim).toArray(String[]::new);
        assertEquals(List.of("classpath:db/migration", "classpath:db/migration-dev"), List.of(locations),
                "dev profileのlocationsがdb/migrationとdb/migration-devであること");

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"),
                    "空DBから始めること");
        }

        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations(locations)
                .outOfOrder(Boolean.parseBoolean(base.getProperty("spring.flyway.out-of-order")))
                .baselineOnMigrate(Boolean.parseBoolean(base.getProperty("spring.flyway.baseline-on-migrate")))
                .baselineVersion(base.getProperty("spring.flyway.baseline-version"))
                .callbacks(new LegacyDatabaseFlywayCallback())
                .load();
        MigrateResult result = flyway.migrate();

        assertTrue(result.success, "dev profileのmigrateが成功すること");
        List<String> failed = new ArrayList<>();
        for (MigrationInfo info : flyway.info().all()) {
            if (info.getState() != MigrationState.SUCCESS) {
                failed.add(info.getVersion() + ":" + info.getState());
            }
        }
        assertEquals(List.of(), failed, "解決した全migrationがSUCCESSであること（pending・failed・ignoredなし）");
        assertEquals(LatestMigrationVersion.LATEST, flyway.info().current().getVersion().getVersion(),
                "dev seedを含めてもcurrent versionはLatestMigrationVersion.LATESTであること");

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            long seedFix = scalar(st, "SELECT installed_rank FROM flyway_schema_history WHERE version = '172.1' AND success = 1");
            long v173 = scalar(st, "SELECT installed_rank FROM flyway_schema_history WHERE version = '173' AND success = 1");
            assertTrue(seedFix < v173, "migration-dev/V172_1はV173より前に適用されること");
            for (String devVersion : List.of("100", "145", "146", "172.1")) {
                assertEquals(1L, scalar(st, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '"
                        + devVersion + "' AND success = 1"), "dev seed V" + devVersion + "が適用されること");
            }
            assertEquals("default", string(st, "SELECT tenant_id FROM t_ai_recommendation_run WHERE id = 85071"));
            assertEquals("default", string(st, "SELECT tenant_id FROM t_ai_recommendation_item WHERE id = 85081"));
            assertEquals("default", string(st, "SELECT tenant_id FROM t_ai_feedback WHERE id = 85091"));
            assertEquals("default", string(st, "SELECT tenant_id FROM t_ai_outcome WHERE id = 85092"));
        }

        MigrateResult again = flyway.migrate();
        assertTrue(again.success);
        assertEquals(0, again.migrationsExecuted, "再起動時のmigrateはno-opであること");
        assertTrue(flyway.validateWithResult().validationSuccessful, "再起動時のvalidateが通ること");
    }

    private static Properties yaml(String resource) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(resource));
        Properties properties = factory.getObject();
        assertTrue(properties != null && !properties.isEmpty(), resource + "を読めること");
        return properties;
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static long scalar(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "結果が1行あること: " + sql);
            return rs.getLong(1);
        }
    }

    private static String string(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "結果が1行あること: " + sql);
            return rs.getString(1);
        }
    }
}
