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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F1-MYSQL-LEGACY-01（design §6.2）:
 * exact provenance付きV83公開形状＋既存契約fixtureへV84を適用し、
 * 新table作成・既存契約no-backfill・success/checksum固定を確認する。
 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayV84LegacySchemaSmokeTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_dispatch_legacy")
            .withUsername("root").withPassword("ses");

    @Test
    void V83公開形状へV84を適用し既存契約を変更せず収束する() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").target("83").load().migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement st = connection.createStatement()) {
            // V83時点の公開形状はdispatch tableを持たない。現行V1は統合baselineでdispatch shapeを含むため、
            // V84適用前のlegacy stateとしてdispatch tableを除去してexact V83公開形状を再現する（推測backfillなし）。
            st.execute("DROP TABLE IF EXISTS t_document_delivery");
            st.execute("DROP TABLE IF EXISTS t_compliance_finding");
            st.execute("DROP TABLE IF EXISTS t_ledger_work_snapshot");
            st.execute("DROP TABLE IF EXISTS t_notification_difference_history");
            st.execute("DROP TABLE IF EXISTS t_direct_hire_dispute_history");
            st.execute("DROP TABLE IF EXISTS t_planned_introduction_history");
            st.execute("DROP TABLE IF EXISTS t_planned_introduction_terms");
            st.execute("DROP TABLE IF EXISTS t_career_consulting_history");
            st.execute("DROP TABLE IF EXISTS t_training_history");
            st.execute("DROP TABLE IF EXISTS t_employment_stability_history");
            st.execute("DROP TABLE IF EXISTS t_compliance_complaint_history");
            st.execute("DROP TABLE IF EXISTS t_compliance_work_calendar");
            st.execute("DROP TABLE IF EXISTS t_compliance_snapshot_operation");
            st.execute("DROP TABLE IF EXISTS t_contract_compliance_worker_state");
            st.execute("DROP TABLE IF EXISTS t_contract_compliance_worker_snapshot");
            st.execute("DROP TABLE IF EXISTS t_contract_compliance_profile");
            st.execute("DROP TABLE IF EXISTS t_contract_compliance_snapshot");
            st.execute("DROP TABLE IF EXISTS m_workplace");
            assertEquals(0, queryInt(st, "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema=DATABASE() AND table_name='t_contract_compliance_snapshot'"),
                    "V83時点の公開形状にdispatch tableが存在しないこと");
            // 既存契約fixture
            st.executeUpdate("INSERT INTO m_customer (company_name) VALUES ('legacy customer')");
            long customerId = queryLong(st, "SELECT id FROM m_customer WHERE company_name='legacy customer'");
            st.executeUpdate("INSERT INTO t_engineer (full_name, employment_type, status) "
                    + "VALUES ('legacy engineer', '正社員', 'Bench')");
            long engineerId = queryLong(st, "SELECT id FROM t_engineer WHERE full_name='legacy engineer'");
            st.executeUpdate("INSERT INTO t_project (project_name, customer_id) VALUES ('legacy project', " + customerId + ")");
            long projectId = queryLong(st, "SELECT id FROM t_project WHERE project_name='legacy project'");
            st.executeUpdate("INSERT INTO t_contract "
                    + "(engineer_id, project_id, customer_id, contract_type, start_date, selling_price, cost_price) VALUES ("
                    + engineerId + ", " + projectId + ", " + customerId + ", '派遣', '2025-04-01', 200, 90)");
        }

        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").target("84").load().migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement st = connection.createStatement()) {
            for (String table : new String[]{
                    "t_contract_compliance_profile", "t_contract_compliance_snapshot",
                    "t_contract_compliance_worker_state", "t_compliance_snapshot_operation",
                    "t_compliance_complaint_history", "t_document_delivery"}) {
                assertEquals(1, queryInt(st, "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema=DATABASE() AND table_name='" + table + "'"), table + "が必要です");
            }
            assertEquals(1, queryInt(st, "SELECT COUNT(*) FROM t_contract WHERE contract_type='派遣'"),
                    "既存契約はV84で変更しない（推測backfill 0）");
            assertTrue(queryLong(st,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='84' AND success=1") == 1,
                    "V84 success=1が必要です");
            assertTrue(queryInt(st,
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                            + "AND table_name='t_contract_compliance_profile' AND column_name='organization_limitation_date'") == 1,
                    "legacy適用でもR5 typed列が成立するはず");
        }

        // V84まででvalidate（S11 trackの未コミットV91等がclasspathにあるためtargetを固定）
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").target("84").load().validate();
    }

    private int queryInt(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private long queryLong(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }
}
