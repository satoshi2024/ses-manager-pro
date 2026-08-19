package com.ses.migration;

import com.ses.test.MySQLContainer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V105.3相当のS15未適用DBから、V106前preflightを経由してcompany単位へ移行できることを実MySQLで検証する。
 * consolidated V1のS15表を一度除去して、V106到達前の実legacy表形状を再現する。
 */
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class FlywayV106CompanyBoundaryHistoricalUpgradeSmokeTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_v106_historical")
            .withUsername("root")
            .withPassword("ses")
            .withStartupTimeout(Duration.ofMinutes(10))
            .withStartupAttempts(3);

    @Test
    void historicalV105_3MultiCompanyUpgradeUsesPreflightAndRestoresBothCompanies() throws Exception {
        Flyway historical = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("105.3")
                .load();
        historical.migrate();

        try (Connection conn = MYSQL.createConnection(""); Statement st = conn.createStatement()) {
            // consolidated V1に折り込まれたS15表を除去し、V105.3時点の未作成形状へ戻す。
            st.execute("DROP TABLE IF EXISTS t_integration_job_event");
            st.execute("DROP TABLE IF EXISTS t_integration_job");
            st.execute("DROP TABLE IF EXISTS m_external_mapping");
            st.execute("DROP TABLE IF EXISTS m_integration_connection");

            st.executeUpdate("INSERT INTO t_freee_connection "
                    + "(company_id, company_name, access_token_encrypted, refresh_token_encrypted, token_expires_at, "
                    + "connected_by, connection_status, deleted_flag) VALUES "
                    + "(123, 'Company A', 'access-a', 'refresh-a', DATE_ADD(NOW(), INTERVAL 1 DAY), 1, 'CONNECTED', 0)");
            st.executeUpdate("INSERT INTO t_freee_connection "
                    + "(company_id, company_name, access_token_encrypted, refresh_token_encrypted, token_expires_at, "
                    + "connected_by, connection_status, deleted_flag) VALUES "
                    + "(456, 'Company B', 'access-b', 'refresh-b', DATE_ADD(NOW(), INTERVAL 2 DAY), 1, 'CONNECTED', 0)");
        }

        Flyway latest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load();
        latest.migrate();
        latest.validate();

        try (Connection conn = MYSQL.createConnection(""); Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM m_integration_connection "
                    + "WHERE provider='freee' AND product='payroll' AND deleted_flag=0")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1), "異なるcompany_idのlegacy connectionが2件ともactiveで復元されること");
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM m_integration_connection "
                    + "WHERE provider='freee' AND product='payroll' AND deleted_flag=0 "
                    + "AND external_company_id IN (123,456)")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1), "company_id単位のidentityが保持されること");
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.statistics "
                    + "WHERE table_schema=DATABASE() AND table_name='m_integration_connection' "
                    + "AND index_name='uk_int_conn' AND column_name='external_company_key'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "最終UNIQUEがexternal_company_keyを含むこと");
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema=DATABASE() AND table_name='m_accounting_legacy_freee_preflight_v105_4'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "preflight退避表が成功後に削除されること");
            }
            try (ResultSet rs = st.executeQuery("SELECT version, success FROM flyway_schema_history "
                    + "WHERE version IN ('105.4','106','106.1','106.2') ORDER BY version")) {
                assertTrue(rs.next());
                assertEquals("105.4", rs.getString("version"));
                assertEquals(1, rs.getInt("success"));
                assertTrue(rs.next());
                assertEquals("106", rs.getString("version"));
                assertEquals(1, rs.getInt("success"));
                assertTrue(rs.next());
                assertEquals("106.1", rs.getString("version"));
                assertEquals(1, rs.getInt("success"));
                assertTrue(rs.next());
                assertEquals("106.2", rs.getString("version"));
                assertEquals(1, rs.getInt("success"));
            }
        }
    }
}
