package com.ses.migration;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T061 F1 R5のH2 replay shapeを確認する。
 * typed column（2種制限日・SRC-E⑱・料金等）、snapshot version一意（hashは非一意）、
 * worker current pointer、finding一意、期間CHECKを再現できることを検証する。
 */
class DispatchComplianceSchemaH2Test {

    @Test
    void H2でR5shapeのtyped列とsnapshot一意性を再現できる() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:t061_dispatch_r5;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE t_contract (id BIGINT PRIMARY KEY)");
            }
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("sql/schema-dispatch-compliance-h2.sql"));

            try (Statement statement = connection.createStatement()) {
                assertTrue(hasColumn(statement, "t_contract_compliance_profile", "organization_limitation_date"),
                        "組織単位の抵触日列が必要です");
                assertTrue(hasColumn(statement, "t_contract_compliance_profile",
                        "social_insurance_procedure_incomplete_reason"), "SRC-E⑱理由列が必要です");
                assertTrue(hasColumn(statement, "t_contract_compliance_profile", "dispatch_fee_amount"),
                        "派遣料金列が必要です");
                assertTrue(hasColumn(statement, "t_contract_compliance_profile", "work_start_minute"),
                        "始業分整数列が必要です");
                assertTrue(hasColumn(statement, "t_contract_compliance_snapshot", "workplace_limitation_date"));
                assertTrue(hasColumn(statement, "t_contract_compliance_snapshot", "organization_limitation_date"));
                assertTrue(hasColumn(statement, "t_contract_compliance_worker_state", "current_snapshot_id"));
                assertTrue(hasColumn(statement, "t_contract_compliance_worker_state", "current_snapshot_version"));
                assertTrue(hasColumn(statement, "t_compliance_complaint_history", "event_id"));
                assertTrue(hasColumn(statement, "t_compliance_complaint_history", "supersedes_event_id"));
                assertTrue(hasColumn(statement, "t_document_delivery", "template_version"));
                assertTrue(hasColumn(statement, "t_document_delivery", "snapshot_hash"));

                statement.execute("INSERT INTO t_contract (id) VALUES (1)");
                // A(v1,hA) → B(v2,hB) → A(v3,hA)：同じhashの再登場を許容し、versionは昇順一意
                statement.execute("INSERT INTO t_contract_compliance_snapshot "
                        + "(contract_id, snapshot_version, snapshot_hash, workplace_limitation_date) "
                        + "VALUES (1, 1, 'hash-A', '2026-12-31')");
                statement.execute("INSERT INTO t_contract_compliance_snapshot "
                        + "(contract_id, snapshot_version, snapshot_hash, workplace_limitation_date) "
                        + "VALUES (1, 2, 'hash-B', '2027-03-31')");
                statement.execute("INSERT INTO t_contract_compliance_snapshot "
                        + "(contract_id, snapshot_version, snapshot_hash, workplace_limitation_date) "
                        + "VALUES (1, 3, 'hash-A', '2026-12-31')");
                assertEquals(3, queryInt(statement,
                        "SELECT COUNT(*) FROM t_contract_compliance_snapshot WHERE contract_id=1"),
                        "A/B/Aは3versionとして保持するはず");
                assertEquals(2, queryInt(statement,
                        "SELECT COUNT(*) FROM t_contract_compliance_snapshot WHERE snapshot_hash='hash-A'"),
                        "同じcontent hashのversion重複を許容するはず");
                boolean duplicateVersionRejected = false;
                try {
                    statement.execute("INSERT INTO t_contract_compliance_snapshot "
                            + "(contract_id, snapshot_version, snapshot_hash) VALUES (1, 2, 'hash-X')");
                } catch (SQLException expected) {
                    duplicateVersionRejected = true;
                }
                assertTrue(duplicateVersionRejected, "同一versionの重複は拒否するはず");

                // worker current pointerはworker別に独立
                statement.execute("INSERT INTO t_contract_compliance_worker_snapshot "
                        + "(contract_id, worker_id, snapshot_version, snapshot_hash, worker_name) "
                        + "VALUES (1, 10, 1, 'w-h1', 'workerA')");
                statement.execute("INSERT INTO t_contract_compliance_worker_snapshot "
                        + "(contract_id, worker_id, snapshot_version, snapshot_hash, worker_name) "
                        + "VALUES (1, 20, 1, 'w-h1', 'workerB')");
                statement.execute("INSERT INTO t_contract_compliance_worker_state "
                        + "(contract_id, worker_id, current_snapshot_id, current_snapshot_version) "
                        + "VALUES (1, 10, 1, 1)");
                statement.execute("INSERT INTO t_contract_compliance_worker_state "
                        + "(contract_id, worker_id, current_snapshot_id, current_snapshot_version) "
                        + "VALUES (1, 20, 2, 1)");
                assertEquals(2, queryInt(statement,
                        "SELECT COUNT(*) FROM t_contract_compliance_worker_state WHERE contract_id=1"),
                        "2 workerのcurrent pointerは独立行");

                // operation idempotency：同一operation_idの重複を拒否
                statement.execute("INSERT INTO t_compliance_snapshot_operation "
                        + "(operation_id, scope_type, contract_id, expected_version, resulting_snapshot_id, status) "
                        + "VALUES ('op-1', 'CONTRACT', 1, 0, 1, 'SUCCEEDED')");
                boolean duplicateOperationRejected = false;
                try {
                    statement.execute("INSERT INTO t_compliance_snapshot_operation "
                            + "(operation_id, scope_type, contract_id, expected_version, resulting_snapshot_id, status) "
                            + "VALUES ('op-1', 'CONTRACT', 1, 0, 1, 'SUCCEEDED')");
                } catch (SQLException expected) {
                    duplicateOperationRejected = true;
                }
                assertTrue(duplicateOperationRejected, "同一operationのretryは重複insertせず1行であるはず");

                // finding：(contract_id, code, condition_fingerprint)の重複を拒否
                statement.execute("INSERT INTO t_compliance_finding "
                        + "(contract_id, code, condition_fingerprint) VALUES (1, 'MISSING_WORKPLACE_LIMITATION_DATE', 'h2-fp')");
                boolean duplicateFindingRejected = false;
                try {
                    statement.execute("INSERT INTO t_compliance_finding "
                            + "(contract_id, code, condition_fingerprint) VALUES (1, 'MISSING_WORKPLACE_LIMITATION_DATE', 'h2-fp')");
                } catch (SQLException expected) {
                    duplicateFindingRejected = true;
                }
                assertTrue(duplicateFindingRejected, "H2でもfindingの重複を拒否するはず");

                // 事業所期間の逆転を拒否
                boolean invalidPeriodRejected = false;
                try {
                    statement.execute("INSERT INTO m_workplace "
                            + "(customer_id, name, valid_from, valid_to) VALUES (1, 'invalid', '2026-12-31', '2026-01-01')");
                } catch (SQLException expected) {
                    invalidPeriodRejected = true;
                }
                assertTrue(invalidPeriodRejected, "事業所期間の逆転を拒否するはず");

                // profileの明示NULL clear：full DTO相当のUPDATEで値→NULLが保存される
                statement.execute("INSERT INTO t_contract_compliance_profile "
                        + "(contract_id, workplace_limitation_date, organization_limitation_date, dispatch_fee_amount) "
                        + "VALUES (1, '2026-12-31', '2027-03-31', 100.00)");
                statement.execute("UPDATE t_contract_compliance_profile "
                        + "SET workplace_limitation_date = NULL, dispatch_fee_amount = NULL WHERE contract_id = 1");
                assertEquals(0, queryInt(statement,
                        "SELECT COUNT(*) FROM t_contract_compliance_profile WHERE workplace_limitation_date IS NOT NULL"),
                        "current列の値→NULLが保存されるはず");
                assertFalse(queryInt(statement,
                        "SELECT COUNT(*) FROM t_contract_compliance_profile WHERE dispatch_fee_amount IS NOT NULL") > 0,
                        "料金も値→NULLできるはず");
            }
        }
    }

    @Test
    void H2でG2assignmentのactive_slotと秒精度境界を再現できる() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:t061_dispatch_g2;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE t_contract (id BIGINT PRIMARY KEY)");
            }
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("sql/schema-dispatch-compliance-h2.sql"));

            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO m_compliance_mapping_version "
                        + "(tenant_id, mapping_code, mapping_version, mapping_hash, review_policy_hash, effective_from, status) "
                        + "VALUES ('default', 'H2-G2', 'v1', REPEAT('a', 64), REPEAT('b', 64), '2026-08-01', 'DRAFT')");
                statement.execute("INSERT INTO m_workplace (tenant_id, customer_id, name, valid_from) "
                        + "VALUES ('default', 1, 'H2-G2-W', '2026-01-01')");
                long workplaceId = queryLong(statement,
                        "SELECT id FROM m_workplace WHERE tenant_id='default' AND name='H2-G2-W'");
                statement.execute("INSERT INTO m_workplace (tenant_id, customer_id, name, valid_from) "
                        + "VALUES ('default', 1, 'H2-G2-FINITE', '2026-01-01')");
                long finiteWorkplaceId = queryLong(statement,
                        "SELECT id FROM m_workplace WHERE tenant_id='default' AND name='H2-G2-FINITE'");
                statement.execute("INSERT INTO t_compliance_responsible_assignment "
                        + "(tenant_id, workplace_id, user_id, effective_from, active_slot, assigned_by) VALUES "
                        + "('default', " + workplaceId + ", 1, '2026-08-01 00:00:01.000000', 1, 1)");
                assertEquals(6, queryInt(statement, "SELECT DATETIME_PRECISION FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME='T_COMPLIANCE_RESPONSIBLE_ASSIGNMENT' AND COLUMN_NAME='EFFECTIVE_FROM'"));
                assertThrows(SQLException.class, () -> statement.execute(
                        "INSERT INTO t_compliance_responsible_assignment "
                                + "(tenant_id, workplace_id, user_id, effective_from, active_slot, assigned_by) VALUES "
                                + "('default', " + workplaceId + ", 2, '2026-08-01 00:00:02.000000', 1, 2)"));
                assertThrows(SQLException.class, () -> statement.execute(
                        "INSERT INTO t_compliance_responsible_assignment "
                                + "(tenant_id, workplace_id, user_id, effective_from, effective_to, active_slot, assigned_by, ended_by, end_reason) VALUES "
                                + "('default', " + workplaceId + ", 2, '2026-08-02 00:00:01.000000', "
                                + "'2026-08-03 00:00:01.000000', 1, 2, 2, 'invalid')"));
                assertThrows(SQLException.class, () -> statement.execute(
                        "INSERT INTO m_compliance_mapping_version "
                                + "(tenant_id, mapping_code, mapping_version, mapping_hash, review_policy_hash, effective_from, status, active_slot) "
                                + "VALUES ('default', 'H2-G2-ACTIVE-NULL', 'v1', REPEAT('a', 64), REPEAT('b', 64), '2026-08-01', 'ACTIVE', NULL)"));
                assertThrows(SQLException.class, () -> statement.execute(
                        "INSERT INTO m_compliance_mapping_version "
                                + "(tenant_id, mapping_code, mapping_version, mapping_hash, review_policy_hash, effective_from, status, active_slot) "
                                + "VALUES ('default', 'H2-G2-DRAFT-SLOT', 'v1', REPEAT('a', 64), REPEAT('b', 64), '2026-08-01', 'DRAFT', 1)"));

                statement.execute("INSERT INTO t_compliance_responsible_assignment "
                        + "(tenant_id, workplace_id, user_id, effective_from, effective_to, active_slot, assigned_by, ended_by, end_reason) VALUES "
                        + "('default', " + finiteWorkplaceId + ", 3, '2026-08-10 00:00:00.000000', "
                        + "'2026-08-10 00:00:01.000000', NULL, 3, 3, 'adjacent-a')");
                statement.execute("INSERT INTO t_compliance_responsible_assignment "
                        + "(tenant_id, workplace_id, user_id, effective_from, effective_to, active_slot, assigned_by, ended_by, end_reason) VALUES "
                        + "('default', " + finiteWorkplaceId + ", 4, '2026-08-10 00:00:01.000000', "
                        + "'2026-08-10 00:00:02.000000', NULL, 4, 4, 'adjacent-b')");
                assertEquals(-1L, queryAssignmentAt(statement, finiteWorkplaceId, "2026-08-09 23:59:59.999999"));
                long first = queryAssignmentAt(statement, finiteWorkplaceId, "2026-08-10 00:00:00.999999");
                long second = queryAssignmentAt(statement, finiteWorkplaceId, "2026-08-10 00:00:01.000000");
                assertTrue(first > 0L);
                assertTrue(second > 0L && second != first);
                assertEquals(-1L, queryAssignmentAt(statement, finiteWorkplaceId, "2026-08-10 00:00:02.000000"));
                assertEquals(0, queryInt(statement,
                        "SELECT COUNT(*) FROM t_compliance_responsible_assignment WHERE workplace_id=" + finiteWorkplaceId
                                + " AND effective_from < '2026-08-10 00:00:03.000000'"
                                + " AND (effective_to IS NULL OR effective_to > '2026-08-10 00:00:02.000000')"));
                assertTrue(queryInt(statement,
                        "SELECT COUNT(*) FROM t_compliance_responsible_assignment WHERE workplace_id=" + finiteWorkplaceId
                                + " AND effective_from < '2026-08-10 00:00:01.500000'"
                                + " AND (effective_to IS NULL OR effective_to > '2026-08-10 00:00:00.500000')") > 0);
            }
        }
    }

    @Test
    void H2でoperationResultのstate別NULLとhash契約を再現できる() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:t061_dispatch_g2_operation;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE t_contract (id BIGINT PRIMARY KEY)");
            }
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("sql/schema-dispatch-compliance-h2.sql"));

            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO t_compliance_operation_ledger "
                        + "(tenant_id, operation_id, operation_type, idempotency_key, request_hash, state, started_at, correlation_id) VALUES "
                        + "('default', 'h2-op-1', 'MAPPING_ACTIVE', 'h2-op-key-1', REPEAT('a', 64), "
                        + "'PROCESSING', '2026-08-01 00:00:01.000000', 'h2-op-correlation-1')");
                assertEquals(1, queryInt(statement,
                        "SELECT COUNT(*) FROM t_compliance_operation_ledger WHERE operation_id='h2-op-1' "
                                + "AND state='PROCESSING' AND retryable_flag=0 AND attempt_count=1 AND version=0 "
                                + "AND finished_at IS NULL AND failure_code IS NULL"));
                assertThrows(SQLException.class, () -> statement.execute(
                        "INSERT INTO t_compliance_operation_ledger "
                                + "(tenant_id, operation_id, operation_type, idempotency_key, request_hash, state, started_at, correlation_id) VALUES "
                                + "('default', 'h2-op-invalid-failed', 'MAPPING_ACTIVE', 'h2-op-invalid-failed-key', REPEAT('d', 64), "
                                + "'FAILED', '2026-08-01 00:00:01.000000', 'h2-op-invalid-failed-correlation')"));
                assertThrows(SQLException.class, () -> statement.execute(
                        "INSERT INTO t_compliance_operation_ledger "
                                + "(tenant_id, operation_id, operation_type, idempotency_key, request_hash, state, finished_at, failure_code, correlation_id) VALUES "
                                + "('default', 'h2-op-invalid-processing', 'MAPPING_ACTIVE', 'h2-op-invalid-processing-key', REPEAT('e', 64), "
                                + "'PROCESSING', '2026-08-01 00:00:02.000000', 'BROKEN', 'h2-op-invalid-processing-correlation')"));
                assertThrows(SQLException.class, () -> statement.execute(
                        "UPDATE t_compliance_operation_ledger SET state='FAILED', retryable_flag=1, "
                                + "finished_at='2026-08-01 00:00:02.000000', failure_code='TEMPORARY', "
                                + "result_summary_canonical='forbidden', result_http_status=500, result_hash=REPEAT('b', 64), version=1 "
                                + "WHERE operation_id='h2-op-1'"));
                assertThrows(SQLException.class, () -> statement.execute(
                        "UPDATE t_compliance_operation_ledger SET state='SUCCEEDED', result_summary_canonical='{}', "
                                + "result_http_status=200, version=1 WHERE operation_id='h2-op-1'"));
                statement.execute("UPDATE t_compliance_operation_ledger SET state='SUCCEEDED', finished_at='2026-08-01 00:00:02.000000', "
                        + "result_summary_canonical='{}', result_http_status=200, result_hash=REPEAT('c', 64), version=1 WHERE operation_id='h2-op-1'");
                assertEquals(1, queryInt(statement,
                        "SELECT COUNT(*) FROM t_compliance_operation_ledger WHERE operation_id='h2-op-1' "
                                + "AND result_hash=REPEAT('c', 64)"));
                statement.execute("INSERT INTO t_compliance_operation_ledger "
                        + "(tenant_id, operation_id, operation_type, idempotency_key, request_hash, state, started_at, correlation_id) VALUES "
                        + "('default', 'h2-op-failed', 'MAPPING_ACTIVE', 'h2-op-failed-key', REPEAT('f', 64), "
                        + "'PROCESSING', '2026-08-01 00:00:01.000000', 'h2-op-failed-correlation')");
                statement.execute("UPDATE t_compliance_operation_ledger SET state='FAILED', retryable_flag=1, "
                        + "finished_at='2026-08-01 00:00:03.000000', failure_code='TEMPORARY', version=1 "
                        + "WHERE operation_id='h2-op-failed'");
                assertEquals(1, queryInt(statement,
                        "SELECT COUNT(*) FROM t_compliance_operation_ledger WHERE operation_id='h2-op-failed' "
                                + "AND state='FAILED' AND finished_at IS NOT NULL AND failure_code='TEMPORARY' "
                                + "AND result_summary_canonical IS NULL AND result_hash IS NULL"));
            }
        }
    }

    private long queryAssignmentAt(Statement statement, long workplaceId, String asOf) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(
                "SELECT id FROM t_compliance_responsible_assignment WHERE workplace_id=" + workplaceId
                        + " AND effective_from <= '" + asOf + "'"
                        + " AND (effective_to IS NULL OR '" + asOf + "' < effective_to)"
                        + " ORDER BY effective_from DESC, id DESC LIMIT 1")) {
            return resultSet.next() ? resultSet.getLong(1) : -1L;
        }
    }

    private boolean hasColumn(Statement statement, String table, String column) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE UPPER(table_name)=UPPER('" + table + "') AND UPPER(column_name)=UPPER('" + column + "')")) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1) == 1;
        }
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
