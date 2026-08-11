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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G2-MIG-01/07/10/11: V102の実MySQL shapeとfreeze/append-only境界を検証する。
 * Docker不在時は既存のTestcontainers方針に従ってskipし、CIでは空DBからV102まで適用する。
 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayG2GateSchemaSmokeTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_g2_v102")
            .withUsername("root")
            .withPassword("ses");

    @Test
    void V102freshでG2shapeとfreeze_append_only境界が成立する() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("102")
                .load()
                .migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            for (String table : new String[]{
                    "m_compliance_mapping_version",
                    "m_compliance_mapping_source",
                    "m_compliance_external_reviewer_type",
                    "m_compliance_mapping_review_requirement_group",
                    "m_compliance_mapping_review_requirement_type",
                    "t_compliance_responsible_assignment",
                    "t_compliance_mapping_approval_event",
                    "t_compliance_external_review_event",
                    "t_compliance_mapping_status_event",
                    "t_compliance_operation_ledger"}) {
                assertTableExists(statement, table);
            }
            for (String column : new String[]{
                    "mapping_version_id", "mapping_hash", "review_policy_hash", "gate_evaluated_at",
                    "gate_snapshot_hash", "worker_snapshot_id", "worker_snapshot_hash",
                    "recipient_display_snapshot_hash", "company_config_snapshot_hash",
                    "delivery_business_key", "generation_state", "full_document_version_id",
                    "mask_document_version_id", "limited_document_version_id"}) {
                assertColumnExists(statement, "t_document_delivery", column);
            }
            assertIndexExists(statement, "t_document_delivery", "uk_delivery_business_key");
            assertForeignKeyExists(statement, "t_document_delivery", "fk_delivery_g2_mapping_version");
            assertForeignKeyExists(statement, "t_document_delivery", "fk_delivery_g2_profile_snapshot");
            assertForeignKeyExists(statement, "t_document_delivery", "fk_delivery_g2_worker_snapshot");
            assertForeignKeyExists(statement, "t_document_delivery", "fk_delivery_g2_full_document_version");
            assertTriggerExists(statement, "trg_g2_mapping_source_freeze_insert");
            assertTriggerExists(statement, "trg_g2_mapping_source_freeze_update");
            assertTriggerExists(statement, "trg_g2_mapping_source_freeze_delete");
            assertTriggerExists(statement, "trg_g2_mapping_slot_check");
            assertTriggerExists(statement, "trg_g2_assignment_slot_check");
            assertTriggerExists(statement, "trg_g2_assignment_slot_check_update");
            assertTriggerExists(statement, "trg_g2_external_review_no_update");
            assertTriggerExists(statement, "trg_g2_external_review_no_delete");
            assertTriggerExists(statement, "trg_g2_operation_no_update");
            assertTriggerExists(statement, "trg_g2_operation_no_delete");

            assertColumnType(statement, "t_compliance_responsible_assignment", "effective_from", "datetime", 6);
            assertColumnType(statement, "t_compliance_responsible_assignment", "effective_to", "datetime", 6);
            assertCompositeForeignKey(statement, "m_compliance_mapping_source", "fk_g2_source_mapping",
                    List.of("tenant_id", "mapping_id"), "m_compliance_mapping_version", List.of("tenant_id", "id"));
            assertCompositeForeignKey(statement, "t_compliance_mapping_review_requirement_type", "fk_g2_review_type_group",
                    List.of("tenant_id", "requirement_group_id"), "m_compliance_mapping_review_requirement_group", List.of("tenant_id", "id"));
            assertCompositeForeignKey(statement, "t_compliance_mapping_approval_event", "fk_g2_approval_target",
                    List.of("tenant_id", "target_event_id"), "t_compliance_mapping_approval_event", List.of("tenant_id", "id"));
            assertCompositeForeignKey(statement, "t_compliance_external_review_event", "fk_g2_external_supersedes",
                    List.of("tenant_id", "supersedes_event_id"), "t_compliance_external_review_event", List.of("tenant_id", "id"));
            assertCompositeForeignKey(statement, "t_compliance_mapping_status_event", "fk_g2_status_mapping",
                    List.of("tenant_id", "mapping_id"), "m_compliance_mapping_version", List.of("tenant_id", "id"));

            statement.executeUpdate("INSERT INTO m_compliance_mapping_version "
                    + "(tenant_id, mapping_code, mapping_version, mapping_hash, review_policy_hash, effective_from, status) "
                    + "VALUES ('default', 'G2-TEST', 'v1', REPEAT('a', 64), REPEAT('b', 64), '2026-08-01', 'DRAFT')");
            long mappingId = queryLong(statement,
                    "SELECT id FROM m_compliance_mapping_version WHERE mapping_code='G2-TEST'");
            statement.executeUpdate("INSERT INTO m_compliance_mapping_source "
                    + "(tenant_id, mapping_id, source_code, source_url, source_version, confirmed_on, effective_from) "
                    + "VALUES ('default', " + mappingId + ", 'SRC-1', 'https://example.invalid/source', '1', '2026-08-01', '2026-08-01')");
            statement.executeUpdate("UPDATE m_compliance_mapping_version SET status='PROVISIONAL_REVIEWED' "
                    + "WHERE id=" + mappingId);

            long userId = queryLong(statement, "SELECT MIN(id) FROM sys_user");
            long customerId = queryLong(statement, "SELECT MIN(id) FROM m_customer");
            long workplaceId = insertWorkplace(statement, "default", customerId, "G2-WORKPLACE-A");
            long assignmentId = insertOpenAssignment(statement, "default", workplaceId, userId,
                    "2026-08-01 00:00:01.000000");
            assertThrows(SQLException.class, () -> insertOpenAssignment(statement, "default", workplaceId, userId,
                    "2026-08-02 00:00:01.000000"),
                    "同一workplaceのopen assignmentはactive_slot=1で一意であるはず");
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO t_compliance_responsible_assignment "
                            + "(tenant_id, workplace_id, user_id, effective_from, effective_to, active_slot, assigned_by) "
                            + "VALUES ('default', " + workplaceId + ", " + userId
                            + ", '2026-08-03 00:00:01.000000', '2026-08-04 00:00:01.000000', 1, " + userId + ")"),
                    "finite assignmentにactive_slot=1は許可しないはず");
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO t_compliance_mapping_version "
                            + "(tenant_id, mapping_code, mapping_version, mapping_hash, review_policy_hash, effective_from, status, active_slot) "
                            + "VALUES ('default', 'G2-ACTIVE-NULL', 'v1', REPEAT('a', 64), REPEAT('b', 64), '2026-08-01', 'ACTIVE', NULL)"),
                    "ACTIVE mappingのactive_slot=NULLは拒否するはず");
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO m_compliance_mapping_version "
                            + "(tenant_id, mapping_code, mapping_version, mapping_hash, review_policy_hash, effective_from, status, active_slot) "
                            + "VALUES ('default', 'G2-DRAFT-SLOT', 'v1', REPEAT('a', 64), REPEAT('b', 64), '2026-08-01', 'DRAFT', 1)"),
                    "非ACTIVE mappingのactive_slot=1は拒否するはず");

            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE m_compliance_mapping_source SET source_version='tampered' WHERE mapping_id=" + mappingId));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO m_compliance_mapping_source "
                            + "(tenant_id, mapping_id, source_code, source_url, source_version, confirmed_on, effective_from) "
                            + "VALUES ('default', " + mappingId + ", 'SRC-2', 'https://example.invalid/source-2', '1', '2026-08-01', '2026-08-01')"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "DELETE FROM m_compliance_mapping_source WHERE mapping_id=" + mappingId));

            long reviewerTypeId = insertReviewerType(statement);
            long groupId = insertReviewGroup(statement, mappingId);
            statement.executeUpdate("INSERT INTO t_compliance_external_review_event "
                    + "(tenant_id, mapping_id, mapping_version, mapping_hash, review_policy_hash, requirement_group_id, "
                    + "requirement_group_code_snapshot, reviewer_type_id, reviewer_type_code_snapshot, "
                    + "reviewer_type_name_snapshot, reviewer_name_snapshot, reviewer_identity_hash, action, review_chain_id, "
                    + "reviewed_at, recorded_at, recorded_by, operation_id, correlation_id, idempotency_key) VALUES "
                    + "('default', " + mappingId + ", 'v1', REPEAT('a', 64), REPEAT('b', 64), " + groupId + ", 'GROUP-1', "
                    + reviewerTypeId + ", 'TYPE-1', 'Type 1', 'Reviewer 1', REPEAT('c', 64), 'APPROVED', 'chain-1', "
                    + "'2026-08-01 00:00:00.000000', '2026-08-01 00:00:00.000000', " + userId + ", UUID(), 'corr-1', 'g2-review-1')");
            long reviewId = queryLong(statement,
                    "SELECT id FROM t_compliance_external_review_event WHERE idempotency_key='g2-review-1'");
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE t_compliance_external_review_event SET reviewer_name_snapshot='tampered' WHERE id=" + reviewId));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "DELETE FROM t_compliance_external_review_event WHERE id=" + reviewId));

            statement.executeUpdate("INSERT INTO t_compliance_operation_ledger "
                    + "(tenant_id, operation_id, operation_type, idempotency_key, request_hash, state, "
                    + "started_at, result_summary_canonical, result_http_status, correlation_id, version, deleted_flag) VALUES "
                    + "('default', 'g2-operation-1', 'MAPPING_ACTIVE', 'g2-op-key-1', REPEAT('d', 64), 'PROCESSING', "
                    + "'2026-08-01 00:00:01.000000', NULL, NULL, 'g2-op-correlation-1', 0, 0)");
            long operationId = queryLong(statement,
                    "SELECT id FROM t_compliance_operation_ledger WHERE operation_id='g2-operation-1'");
            statement.executeUpdate("UPDATE t_compliance_operation_ledger SET state='SUCCEEDED', "
                    + "result_summary_canonical='{}', result_http_status=200, version=1 WHERE id=" + operationId);
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE t_compliance_operation_ledger SET result_summary_canonical='tampered', version=2 WHERE id=" + operationId),
                    "SUCCEEDED operationのresult改変は拒否するはず");
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "DELETE FROM t_compliance_operation_ledger WHERE id=" + operationId),
                    "operation ledgerのDELETEは拒否するはず");

            long otherMappingId = insertMapping(statement, "other-tenant", "G2-OTHER", "v1", "DRAFT");
            long otherWorkplaceId = insertWorkplace(statement, "other-tenant", customerId, "G2-WORKPLACE-B");
            long otherAssignmentId = insertOpenAssignment(statement, "other-tenant", otherWorkplaceId, userId,
                    "2026-08-01 00:00:01.000000");
            long firstApprovalId = insertApproval(statement, "default", mappingId, assignmentId, workplaceId, userId,
                    null, "g2-approval-1");
            assertThrows(SQLException.class, () -> insertApproval(statement, "other-tenant", otherMappingId,
                    otherAssignmentId, otherWorkplaceId, userId, firstApprovalId, "g2-approval-cross-tenant"),
                    "別tenantのapproval self targetは拒否するはず");
        }
    }

    private long insertMapping(Statement statement, String tenant, String code, String version, String status)
            throws SQLException {
        statement.executeUpdate("INSERT INTO m_compliance_mapping_version "
                + "(tenant_id, mapping_code, mapping_version, mapping_hash, review_policy_hash, effective_from, status) VALUES "
                + "('" + tenant + "', '" + code + "', '" + version
                + "', REPEAT('a', 64), REPEAT('b', 64), '2026-08-01', '" + status + "')");
        return queryLong(statement, "SELECT id FROM m_compliance_mapping_version WHERE tenant_id='" + tenant
                + "' AND mapping_code='" + code + "' AND mapping_version='" + version + "'");
    }

    private long insertWorkplace(Statement statement, String tenant, long customerId, String name) throws SQLException {
        statement.executeUpdate("INSERT INTO m_workplace (tenant_id, customer_id, name, valid_from) VALUES ('"
                + tenant + "', " + customerId + ", '" + name + "', '2026-01-01')");
        return queryLong(statement, "SELECT id FROM m_workplace WHERE tenant_id='" + tenant + "' AND name='" + name + "'");
    }

    private long insertOpenAssignment(Statement statement, String tenant, long workplaceId, long userId,
                                      String effectiveFrom) throws SQLException {
        statement.executeUpdate("INSERT INTO t_compliance_responsible_assignment "
                + "(tenant_id, workplace_id, user_id, effective_from, active_slot, assigned_by) VALUES ('"
                + tenant + "', " + workplaceId + ", " + userId + ", '" + effectiveFrom + "', 1, " + userId + ")");
        return queryLong(statement, "SELECT id FROM t_compliance_responsible_assignment WHERE tenant_id='" + tenant
                + "' AND workplace_id=" + workplaceId + " AND active_slot=1");
    }

    private long insertApproval(Statement statement, String tenant, long mappingId, long assignmentId,
                                long workplaceId, long userId, Long targetEventId, String idempotencyKey)
            throws SQLException {
        statement.executeUpdate("INSERT INTO t_compliance_mapping_approval_event "
                + "(tenant_id, mapping_id, mapping_version, mapping_hash, review_policy_hash, assignment_id, "
                + "workplace_id_snapshot, actor_id, actor_display_name_snapshot, actor_role_snapshot, action, "
                + "event_chain_id, target_event_id, occurred_at, operation_id, correlation_id, idempotency_key) VALUES ('"
                + tenant + "', " + mappingId + ", 'v1', REPEAT('a', 64), REPEAT('b', 64), " + assignmentId + ", "
                + workplaceId + ", " + userId + ", 'Actor', 'COMPLIANCE_RESPONSIBLE', 'APPROVE', 'chain-"
                + idempotencyKey + "', " + (targetEventId == null ? "NULL" : targetEventId) + ", "
                + "'2026-08-01 00:00:01.000000', UUID(), 'corr-" + idempotencyKey + "', '" + idempotencyKey + "')");
        return queryLong(statement, "SELECT id FROM t_compliance_mapping_approval_event WHERE tenant_id='"
                + tenant + "' AND idempotency_key='" + idempotencyKey + "'");
    }

    private long insertReviewerType(Statement statement) throws SQLException {
        statement.executeUpdate("INSERT INTO m_compliance_external_reviewer_type "
                + "(tenant_id, type_code, display_name, credential_label) VALUES ('default', 'TYPE-1', 'Type 1', 'Credential')");
        return queryLong(statement,
                "SELECT id FROM m_compliance_external_reviewer_type WHERE type_code='TYPE-1'");
    }

    private long insertReviewGroup(Statement statement, long mappingId) throws SQLException {
        statement.executeUpdate("INSERT INTO m_compliance_mapping_review_requirement_group "
                + "(tenant_id, mapping_id, requirement_group_code, display_name, minimum_distinct_reviewers) "
                + "VALUES ('default', " + mappingId + ", 'GROUP-1', 'Group 1', 1)");
        return queryLong(statement,
                "SELECT id FROM m_compliance_mapping_review_requirement_group WHERE requirement_group_code='GROUP-1'");
    }

    private void assertTableExists(Statement statement, String table) throws SQLException {
        assertEquals(1, queryInt(statement, "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name='" + table + "'"), table + "が存在するはず");
    }

    private void assertColumnExists(Statement statement, String table, String column) throws SQLException {
        assertEquals(1, queryInt(statement, "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='" + table + "' AND column_name='" + column + "'"),
                table + "." + column + "が存在するはず");
    }

    private void assertIndexExists(Statement statement, String table, String index) throws SQLException {
        assertTrue(queryInt(statement, "SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name='" + table + "' AND index_name='" + index + "'") > 0,
                table + "." + index + "が存在するはず");
    }

    private void assertTriggerExists(Statement statement, String trigger) throws SQLException {
        assertEquals(1, queryInt(statement, "SELECT COUNT(*) FROM information_schema.triggers "
                + "WHERE trigger_schema=DATABASE() AND trigger_name='" + trigger + "'"),
                trigger + "が存在するはず");
    }

    private void assertForeignKeyExists(Statement statement, String table, String constraint) throws SQLException {
        assertEquals(1, queryInt(statement, "SELECT COUNT(*) FROM information_schema.table_constraints "
                + "WHERE constraint_schema=DATABASE() AND table_name='" + table + "' "
                + "AND constraint_name='" + constraint + "' AND constraint_type='FOREIGN KEY'"),
                constraint + "が存在するはず");
    }

    private void assertCompositeForeignKey(Statement statement, String table, String constraint,
                                           List<String> childColumns, String referencedTable,
                                           List<String> referencedColumns) throws SQLException {
        String sql = "SELECT COLUMN_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME "
                + "FROM information_schema.KEY_COLUMN_USAGE WHERE CONSTRAINT_SCHEMA=DATABASE() "
                + "AND TABLE_NAME='" + table + "' AND CONSTRAINT_NAME='" + constraint + "' "
                + "ORDER BY ORDINAL_POSITION";
        List<String> actualChild = new ArrayList<>();
        List<String> actualReferenced = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                actualChild.add(resultSet.getString(1));
                assertEquals(referencedTable, resultSet.getString(2));
                actualReferenced.add(resultSet.getString(3));
            }
        }
        assertEquals(childColumns, actualChild, table + "." + constraint + "のchild列順が不正です");
        assertEquals(referencedColumns, actualReferenced, table + "." + constraint + "のparent列順が不正です");
    }

    private void assertColumnType(Statement statement, String table, String column,
                                  String expectedType, int expectedPrecision) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery("SELECT DATA_TYPE, DATETIME_PRECISION "
                + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='" + table
                + "' AND COLUMN_NAME='" + column + "'")) {
            assertTrue(resultSet.next(), table + "." + column + "が存在するはず");
            assertEquals(expectedType, resultSet.getString(1));
            assertEquals(expectedPrecision, resultSet.getInt(2));
        }
    }

    private int queryInt(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private long queryLong(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }
}
