package com.ses.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R4-P1-01用のCI/Testcontainers read-only環境証跡。
 * V83まで適用した一時MySQLのFlyway履歴を変更せずに読み出し、V82欠番とV83適用を固定する。
 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayEnvironmentEvidenceTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_s10_evidence")
            .withUsername("ses")
            .withPassword("ses");

    @Test
    void ciTestcontainersのV82V83履歴をreadOnlyで記録する() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("83")
                .load()
                .migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            EvidenceRow v82 = find(statement, "82");
            EvidenceRow v83 = find(statement, "83");
            EvidenceRow latest = findLatestSuccessful(statement);

            assertFalse(v82.present(), "V82はこの証跡環境では実在してはならない");
            assertTrue(v83.present(), "V83の履歴行が必要");
            assertTrue(v83.success(), "V83は成功済みである必要がある");
            assertNotNull(v83.installedOn(), "V83 installed_onが必要");
            assertNotNull(v83.checksum(), "V83 checksumが必要");
            assertEquals("83", latest.version(), "CI/Testcontainersの成功済みlatestはV83である必要がある");

            // stdoutはCI run logへ保存される。秘密情報と接続URLは出力しない。
            System.out.printf(
                    "S10_ENV_EVIDENCE environment=CI/Testcontainers version=82 status=ABSENT "
                            + "version=83 success=%s installed_on=%s checksum=%s latest_successful=%s%n",
                    v83.success(), v83.installedOn(), v83.checksum(), latest.version());
        }
    }

    private EvidenceRow find(Statement statement, String version) throws Exception {
        String sql = "SELECT version, success, installed_on, checksum "
                + "FROM flyway_schema_history WHERE version='" + version + "' "
                + "ORDER BY installed_rank DESC LIMIT 1";
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                return new EvidenceRow(false, version, false, null, null);
            }
            return new EvidenceRow(true, resultSet.getString("version"),
                    resultSet.getBoolean("success"), resultSet.getTimestamp("installed_on"),
                    resultSet.getObject("checksum"));
        }
    }

    private EvidenceRow findLatestSuccessful(Statement statement) throws Exception {
        String sql = "SELECT version, success, installed_on, checksum "
                + "FROM flyway_schema_history WHERE success=true AND version IS NOT NULL "
                + "ORDER BY installed_rank DESC LIMIT 1";
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next(), "成功済みFlyway履歴が必要");
            return new EvidenceRow(true, resultSet.getString("version"),
                    resultSet.getBoolean("success"), resultSet.getTimestamp("installed_on"),
                    resultSet.getObject("checksum"));
        }
    }

    private record EvidenceRow(boolean present, String version, boolean success,
                               java.sql.Timestamp installedOn, Object checksum) {
    }
}
