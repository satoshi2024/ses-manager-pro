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
            assertTriggerExists(statement, "trg_g2_external_review_no_update");
            assertTriggerExists(statement, "trg_g2_external_review_no_delete");

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
            long userId = queryLong(statement, "SELECT MIN(id) FROM sys_user");
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
        }
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
