package com.ses.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T061 F1のV84 fresh MySQL schema、FK/unique/snapshot境界を確認する。 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayDispatchComplianceSchemaSmokeTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_dispatch_v84")
            .withUsername("ses")
            .withPassword("ses");

    @Test
    void V84のdispatchコンプライアンスshapeとsnapshot一意性が成立する() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("84")
                .load()
                .migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            for (String table : new String[]{
                    "m_workplace", "t_contract_compliance_profile", "t_compliance_finding", "t_document_delivery"}) {
                assertTableExists(statement, table);
            }
            for (String column : new String[]{
                    "snapshot_json", "limitation_date", "dispatch_responsible_user_id",
                    "workplace_limitation_date", "worker_limitation_date", "workplace_snapshot_json",
                    "worker_snapshot_json",
                    "complaint_processing_history", "health_insurance_missing_reason",
                    "pension_insurance_expected_date", "employment_insurance_expected_date"}) {
                assertColumnExists(statement, "t_contract_compliance_profile", column);
            }
            assertIndexExists(statement, "t_contract_compliance_profile",
                    "uk_contract_compliance_profile_contract");
            assertIndexExists(statement, "t_compliance_finding", "uk_compliance_finding");
            assertIndexExists(statement, "t_document_delivery", "uk_document_delivery_idempotency");
            assertForeignKeyExists(statement, "t_contract_compliance_profile", "fk_profile_contract");
            assertForeignKeyExists(statement, "t_contract_compliance_profile", "fk_profile_client_contact");
            assertForeignKeyExists(statement, "t_document_delivery", "fk_delivery_document");

            statement.executeUpdate("INSERT INTO m_customer (company_name) VALUES ('T061 customer')");
            long customerId = queryLong(statement,
                    "SELECT id FROM m_customer WHERE company_name='T061 customer'");
            statement.executeUpdate("INSERT INTO t_engineer (full_name, employment_type, status) "
                    + "VALUES ('T061 engineer', '正社員', 'Bench')");
            long engineerId = queryLong(statement,
                    "SELECT id FROM t_engineer WHERE full_name='T061 engineer'");
            statement.executeUpdate("INSERT INTO t_project (project_name, customer_id) VALUES "
                    + "('T061 project', " + customerId + ")");
            long projectId = queryLong(statement,
                    "SELECT id FROM t_project WHERE project_name='T061 project'");
            statement.executeUpdate("INSERT INTO t_contract "
                    + "(engineer_id, project_id, customer_id, contract_type, start_date, selling_price, cost_price) VALUES ("
                    + engineerId + ", " + projectId + ", " + customerId + ", '派遣', '2026-08-01', 100, 50)");
            long contractId = queryLong(statement,
                    "SELECT id FROM t_contract WHERE engineer_id=" + engineerId);
            statement.executeUpdate("INSERT INTO m_workplace (customer_id, name, address) VALUES ("
                    + customerId + ", 'T061 workplace', '旧住所')");
            long workplaceId = queryLong(statement,
                    "SELECT id FROM m_workplace WHERE name='T061 workplace'");
            statement.executeUpdate("INSERT INTO t_contract_compliance_profile "
                    + "(contract_id, workplace_id, limitation_date, snapshot_json, snapshot_at) VALUES ("
                    + contractId + ", " + workplaceId + ", NULL, '{\"address\":\"旧住所\"}', CURRENT_TIMESTAMP)");
            statement.executeUpdate("UPDATE m_workplace SET address='新住所' WHERE id=" + workplaceId);
            assertEquals("{\"address\":\"旧住所\"}", queryString(statement,
                    "SELECT snapshot_json FROM t_contract_compliance_profile WHERE contract_id=" + contractId)
                    .replace(" ", ""));
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM t_contract_compliance_profile WHERE limitation_date IS NULL"));

            statement.executeUpdate("INSERT INTO t_compliance_finding "
                    + "(contract_id, code, condition_fingerprint) VALUES (" + contractId
                    + ", 'MISSING_LIMITATION_DATE', 'fp-t061')");
            boolean duplicateRejected = false;
            try {
                statement.executeUpdate("INSERT INTO t_compliance_finding "
                        + "(contract_id, code, condition_fingerprint) VALUES (" + contractId
                        + ", 'MISSING_LIMITATION_DATE', 'fp-t061')");
            } catch (SQLException expected) {
                duplicateRejected = true;
            }
            assertTrue(duplicateRejected, "同一contract/code/fingerprintのfinding重複を拒否するはず");
        }
    }

    private void assertTableExists(Statement statement, String table) throws Exception {
        assertEquals(1, queryInt(statement, "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name='" + table + "'"));
    }

    private void assertColumnExists(Statement statement, String table, String column) throws Exception {
        assertEquals(1, queryInt(statement, "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='" + table + "' AND column_name='" + column + "'"));
    }

    private void assertIndexExists(Statement statement, String table, String index) throws Exception {
        assertTrue(queryInt(statement, "SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name='" + table + "' AND index_name='" + index + "'") > 0,
                table + "." + index + "が存在するはず");
    }

    private void assertForeignKeyExists(Statement statement, String table, String constraint) throws Exception {
        assertEquals(1, queryInt(statement, "SELECT COUNT(*) FROM information_schema.table_constraints "
                + "WHERE constraint_schema=DATABASE() AND table_name='" + table
                + "' AND constraint_name='" + constraint + "' AND constraint_type='FOREIGN KEY'"));
    }

    private int queryInt(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private long queryLong(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }

    private String queryString(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }
}
