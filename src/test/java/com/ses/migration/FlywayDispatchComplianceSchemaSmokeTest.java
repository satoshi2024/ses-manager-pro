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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F1-MYSQL-FRESH-01（design §6.2）:
 * 空DBをV1→V84したfresh shapeが、R5契約のtable/FK/index/trigger/entity契約を形成し、skip 0で収束することを確認する。
 * 旧shape列（snapshot_json / limitation_date / worker_limitation_date）の不存在、typed列の存在、
 * snapshot version一意・hash非一意、worker state FK/CAS、triggerによる直接UPDATE/DELETE拒否を検証する。
 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayDispatchComplianceSchemaSmokeTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_dispatch_v84")
            .withUsername("root").withPassword("ses");

    @Test
    void V84freshでR5shapeとsnapshot一意性trigger境界が成立する() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("84")
                .load()
                .migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            for (String table : new String[]{
                    "m_workplace", "t_contract_compliance_profile", "t_contract_compliance_snapshot",
                    "t_contract_compliance_worker_snapshot", "t_contract_compliance_worker_state",
                    "t_compliance_snapshot_operation", "t_compliance_work_calendar",
                    "t_compliance_complaint_history", "t_employment_stability_history",
                    "t_training_history", "t_career_consulting_history", "t_planned_introduction_terms",
                    "t_planned_introduction_history", "t_direct_hire_dispute_history",
                    "t_notification_difference_history", "t_ledger_work_snapshot",
                    "t_compliance_finding", "t_document_delivery"}) {
                assertTableExists(statement, table);
            }
            // 旧shape列の不存在
            for (String column : new String[]{"snapshot_json", "workplace_snapshot_json", "worker_snapshot_json",
                    "limitation_date", "worker_limitation_date"}) {
                assertColumnAbsent(statement, "t_contract_compliance_profile", column);
            }
            // R5 typed列の存在
            for (String column : new String[]{
                    "workplace_limitation_date", "organization_limitation_date",
                    "social_insurance_procedure_incomplete_reason",
                    "dispatch_fee_amount", "dispatch_fee_basis", "dispatch_fee_currency",
                    "work_start_minute", "work_end_minute",
                    "responsibility_level", "benefits_detail", "dispatch_headcount",
                    "agreement_target_flag", "current_snapshot_id", "current_snapshot_version",
                    "retention_due_date", "legal_hold_flag"}) {
                assertColumnExists(statement, "t_contract_compliance_profile", column);
            }
            assertColumnExists(statement, "t_contract_compliance_snapshot", "organization_limitation_date");
            assertColumnExists(statement, "t_contract_compliance_worker_state", "current_snapshot_id");
            assertColumnExists(statement, "t_contract_compliance_worker_state", "current_snapshot_version");
            assertColumnExists(statement, "t_document_delivery", "template_version");
            assertColumnExists(statement, "t_document_delivery", "snapshot_hash");

            assertIndexExists(statement, "t_contract_compliance_snapshot", "uk_compliance_snapshot_version");
            assertIndexExists(statement, "t_contract_compliance_snapshot", "idx_compliance_snapshot_hash");
            assertIndexExists(statement, "t_contract_compliance_worker_snapshot", "uk_worker_snapshot_version");
            assertIndexExists(statement, "t_contract_compliance_worker_state", "uk_worker_state_contract_worker");
            assertIndexExists(statement, "t_compliance_snapshot_operation", "uk_snapshot_operation");
            assertIndexExists(statement, "t_compliance_finding", "uk_compliance_finding");
            assertIndexExists(statement, "t_document_delivery", "uk_document_delivery_idempotency");
            assertForeignKeyExists(statement, "t_contract_compliance_profile", "fk_profile_contract");
            assertForeignKeyExists(statement, "t_contract_compliance_profile", "fk_profile_current_snapshot");
            assertForeignKeyExists(statement, "t_contract_compliance_worker_state", "fk_worker_state_snapshot");
            assertTriggerExists(statement, "trg_t_contract_compliance_snapshot_no_update");
            assertTriggerExists(statement, "trg_t_contract_compliance_snapshot_no_delete");
            assertTriggerExists(statement, "trg_t_compliance_complaint_history_no_update");

            // fixture
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

            // A(v1,hA) → B(v2,hB) → A(v3,hA)：同じhashの再登場を許容し、versionは昇順一意
            statement.executeUpdate("INSERT INTO t_contract_compliance_snapshot "
                    + "(contract_id, snapshot_version, snapshot_hash, workplace_limitation_date) VALUES ("
                    + contractId + ", 1, 'hA', '2026-12-31')");
            statement.executeUpdate("INSERT INTO t_contract_compliance_snapshot "
                    + "(contract_id, snapshot_version, snapshot_hash, workplace_limitation_date) VALUES ("
                    + contractId + ", 2, 'hB', '2027-03-31')");
            statement.executeUpdate("INSERT INTO t_contract_compliance_snapshot "
                    + "(contract_id, snapshot_version, snapshot_hash, workplace_limitation_date) VALUES ("
                    + contractId + ", 3, 'hA', '2026-12-31')");
            assertEquals(3, queryInt(statement,
                    "SELECT COUNT(*) FROM t_contract_compliance_snapshot WHERE contract_id=" + contractId),
                    "A/B/Aは3versionとして保持するはず");
            boolean duplicateVersionRejected = false;
            try {
                statement.executeUpdate("INSERT INTO t_contract_compliance_snapshot "
                        + "(contract_id, snapshot_version, snapshot_hash) VALUES (" + contractId + ", 2, 'hX')");
            } catch (SQLException expected) {
                duplicateVersionRejected = true;
            }
            assertTrue(duplicateVersionRejected, "同一versionの重複は拒否するはず");

            // profile current pointer + CAS
            statement.executeUpdate("INSERT INTO t_contract_compliance_profile "
                    + "(contract_id, workplace_id, workplace_limitation_date, organization_limitation_date, "
                    + "current_snapshot_id, current_snapshot_version) VALUES ("
                    + contractId + ", " + workplaceId + ", '2026-12-31', NULL, 1, 1)");
            assertEquals(1, statement.executeUpdate(
                    "UPDATE t_contract_compliance_profile SET current_snapshot_id=2, current_snapshot_version=2 "
                            + "WHERE contract_id=" + contractId + " AND current_snapshot_version=1"), "CASは1勝");
            // 明示NULL：current列の値→NULL
            statement.executeUpdate("UPDATE t_contract_compliance_profile SET organization_limitation_date = NULL "
                    + "WHERE contract_id=" + contractId);
            assertEquals(0, queryInt(statement,
                    "SELECT COUNT(*) FROM t_contract_compliance_profile WHERE organization_limitation_date IS NOT NULL"),
                    "current列の値→NULLが保存されるはず");

            // operation idempotency：同一operation_idの重複を拒否
            statement.executeUpdate("INSERT INTO t_compliance_snapshot_operation "
                    + "(operation_id, scope_type, contract_id, expected_version, resulting_snapshot_id, status) "
                    + "VALUES ('op-fresh-1', 'CONTRACT', " + contractId + ", 0, 1, 'SUCCEEDED')");
            boolean duplicateOperationRejected = false;
            try {
                statement.executeUpdate("INSERT INTO t_compliance_snapshot_operation "
                        + "(operation_id, scope_type, contract_id, expected_version, resulting_snapshot_id, status) "
                        + "VALUES ('op-fresh-1', 'CONTRACT', " + contractId + ", 0, 1, 'SUCCEEDED')");
            } catch (SQLException expected) {
                duplicateOperationRejected = true;
            }
            assertTrue(duplicateOperationRejected, "同じoperationのretryは2行目を作らないはず");

            // trigger：snapshotの直接UPDATE/DELETEを拒否
            boolean directUpdateRejected = false;
            try {
                statement.executeUpdate("UPDATE t_contract_compliance_snapshot SET snapshot_hash='tampered' "
                        + "WHERE snapshot_version=1");
            } catch (SQLException expected) {
                directUpdateRejected = true;
            }
            assertTrue(directUpdateRejected, "snapshotの直接UPDATEを拒否するはず");
            boolean directDeleteRejected = false;
            try {
                statement.executeUpdate("DELETE FROM t_contract_compliance_snapshot WHERE snapshot_version=1");
            } catch (SQLException expected) {
                directDeleteRejected = true;
            }
            assertTrue(directDeleteRejected, "snapshotの直接DELETEを拒否するはず");

            // finding：(contract_id, code, condition_fingerprint)の重複を拒否
            statement.executeUpdate("INSERT INTO t_compliance_finding "
                    + "(contract_id, code, condition_fingerprint) VALUES (" + contractId
                    + ", 'MISSING_ORGANIZATION_LIMITATION_DATE', 'fp-t061')");
            boolean duplicateFindingRejected = false;
            try {
                statement.executeUpdate("INSERT INTO t_compliance_finding "
                        + "(contract_id, code, condition_fingerprint) VALUES (" + contractId
                        + ", 'MISSING_ORGANIZATION_LIMITATION_DATE', 'fp-t061')");
            } catch (SQLException expected) {
                duplicateFindingRejected = true;
            }
            assertTrue(duplicateFindingRejected, "同一contract/code/fingerprintのfinding重複を拒否するはず");
        }
    }

    private void assertTableExists(Statement statement, String table) throws Exception {
        assertEquals(1, queryInt(statement, "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name='" + table + "'"), "tableが見つかりません: " + table);
    }

    private void assertColumnExists(Statement statement, String table, String column) throws Exception {
        assertEquals(1, queryInt(statement, "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='" + table + "' AND column_name='" + column + "'"),
                "columnが見つかりません: " + table + "." + column);
    }

    private void assertColumnAbsent(Statement statement, String table, String column) throws Exception {
        assertEquals(0, queryInt(statement, "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='" + table + "' AND column_name='" + column + "'"),
                "旧shape列が残っています: " + table + "." + column);
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

    private void assertTriggerExists(Statement statement, String trigger) throws Exception {
        assertEquals(1, queryInt(statement, "SELECT COUNT(*) FROM information_schema.triggers "
                + "WHERE trigger_schema=DATABASE() AND trigger_name='" + trigger + "'"),
                "triggerが見つかりません: " + trigger);
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
}
