package com.ses.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import com.ses.test.MySQLContainer;
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
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class FlywayG2GateSchemaSmokeTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_g2_v102")
            .withUsername("root")
            .withPassword("ses");

@Test
void V102freshからV102_3までG2shapeがfreeze_append_only制約を維持する() throws Exception {
Flyway.configure()
.dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
.locations("classpath:db/migration")
.target("102_3")
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
"t_compliance_operation_ledger",
"t_compliance_external_reviewer_subject",
"t_compliance_external_reviewer_verification_event",
"t_compliance_external_review_adoption_event",
"m_compliance_verification_source",
"m_compliance_verification_method",
"t_compliance_reviewer_qualification"}) {
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
            assertTriggerExists(statement, "trg_g2_operation_claim_insert");
            assertTriggerExists(statement, "trg_g2_operation_no_update");
            assertTriggerExists(statement, "trg_g2_operation_no_delete");

            assertColumnType(statement, "t_compliance_responsible_assignment", "effective_from", "datetime", 6);
            assertColumnType(statement, "t_compliance_responsible_assignment", "effective_to", "datetime", 6);
            assertCompositeForeignKey(statement, "m_compliance_mapping_source", "fk_g2_source_mapping",
                    List.of("tenant_id", "mapping_id"), "m_compliance_mapping_version", List.of("tenant_id", "id"));
            assertCompositeForeignKey(statement, "m_compliance_mapping_review_requirement_group", "fk_g2_review_group_mapping",
                    List.of("tenant_id", "mapping_id"), "m_compliance_mapping_version", List.of("tenant_id", "id"));
            assertCompositeForeignKey(statement, "m_compliance_mapping_review_requirement_type", "fk_g2_review_type_group",
                    List.of("tenant_id", "requirement_group_id"), "m_compliance_mapping_review_requirement_group", List.of("tenant_id", "id"));
            assertCompositeForeignKey(statement, "m_compliance_mapping_review_requirement_type", "fk_g2_review_type_reviewer",
                    List.of("tenant_id", "reviewer_type_id"), "m_compliance_external_reviewer_type", List.of("tenant_id", "id"));
            assertCompositeForeignKey(statement, "t_compliance_responsible_assignment", "fk_g2_assignment_workplace",
                    List.of("tenant_id", "workplace_id"), "m_workplace", List.of("tenant_id", "id"));
            assertCompositeForeignKey(statement, "t_compliance_mapping_approval_event", "fk_g2_approval_mapping",
                    List.of("tenant_id", "mapping_id"), "m_compliance_mapping_version", List.of("tenant_id", "id"));
            assertCompositeForeignKey(statement, "t_compliance_mapping_approval_event", "fk_g2_approval_assignment",
                    List.of("tenant_id", "assignment_id"), "t_compliance_responsible_assignment", List.of("tenant_id", "id"));
            assertCompositeForeignKey(statement, "t_compliance_mapping_approval_event", "fk_g2_approval_workplace",
                    List.of("tenant_id", "workplace_id_snapshot"), "m_workplace", List.of("tenant_id", "id"));
            assertCompositeForeignKey(statement, "t_compliance_mapping_approval_event", "fk_g2_approval_target",
                    List.of("tenant_id", "target_event_id"), "t_compliance_mapping_approval_event", List.of("tenant_id", "id"));
            assertCompositeForeignKey(statement, "t_compliance_mapping_approval_event", "fk_g2_approval_supersedes",
                    List.of("tenant_id", "supersedes_event_id"), "t_compliance_mapping_approval_event", List.of("tenant_id", "id"));
            assertCompositeForeignKey(statement, "t_compliance_external_review_event", "fk_g2_external_mapping",
                    List.of("tenant_id", "mapping_id"), "m_compliance_mapping_version", List.of("tenant_id", "id"));
            assertCompositeForeignKey(statement, "t_compliance_external_review_event", "fk_g2_external_group",
                    List.of("tenant_id", "requirement_group_id"), "m_compliance_mapping_review_requirement_group", List.of("tenant_id", "id"));
            assertCompositeForeignKey(statement, "t_compliance_external_review_event", "fk_g2_external_reviewer_type",
                    List.of("tenant_id", "reviewer_type_id"), "m_compliance_external_reviewer_type", List.of("tenant_id", "id"));
            assertCompositeForeignKey(statement, "t_compliance_external_review_event", "fk_g2_external_target",
                    List.of("tenant_id", "target_event_id"), "t_compliance_external_review_event", List.of("tenant_id", "id"));
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
            long assignmentCountBeforeDuplicate = queryLong(statement,
                    "SELECT COUNT(*) FROM t_compliance_responsible_assignment WHERE tenant_id='default' AND workplace_id="
                            + workplaceId);
            assertThrows(SQLException.class, () -> insertOpenAssignment(statement, "default", workplaceId, userId,
                    "2026-08-02 00:00:01.000000"),
                    "同一workplaceのopen assignmentはactive_slot=1で一意であるはず");
            assertEquals(assignmentCountBeforeDuplicate, queryLong(statement,
                    "SELECT COUNT(*) FROM t_compliance_responsible_assignment WHERE tenant_id='default' AND workplace_id="
                            + workplaceId));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO t_compliance_responsible_assignment "
                            + "(tenant_id, workplace_id, user_id, effective_from, effective_to, active_slot, assigned_by) "
                            + "VALUES ('default', " + workplaceId + ", " + userId
                            + ", '2026-08-03 00:00:01.000000', '2026-08-04 00:00:01.000000', 1, " + userId + ")"),
                    "finite assignmentにactive_slot=1は許可しないはず");
            long mappingCountBeforeActiveNull = queryLong(statement,
                    "SELECT COUNT(*) FROM m_compliance_mapping_version");
            SQLException activeNullFailure = assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO m_compliance_mapping_version "
                            + "(tenant_id, mapping_code, mapping_version, mapping_hash, review_policy_hash, effective_from, status, active_slot) "
                            + "VALUES ('default', 'G2-ACTIVE-NULL', 'v1', REPEAT('a', 64), REPEAT('b', 64), '2026-08-01', 'ACTIVE', NULL)"),
                    "ACTIVE mappingのactive_slot=NULLは拒否するはず");
            assertTrue(activeNullFailure.getSQLState().equals("23000") || activeNullFailure.getSQLState().equals("45000"));
            assertEquals(mappingCountBeforeActiveNull, queryLong(statement,
                    "SELECT COUNT(*) FROM m_compliance_mapping_version"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO m_compliance_mapping_version "
                            + "(tenant_id, mapping_code, mapping_version, mapping_hash, review_policy_hash, effective_from, status, active_slot) "
                            + "VALUES ('default', 'G2-DRAFT-SLOT', 'v1', REPEAT('a', 64), REPEAT('b', 64), '2026-08-01', 'DRAFT', 1)"),
                    "非ACTIVE mappingのactive_slot=1は拒否するはず");

            statement.executeUpdate("INSERT INTO m_compliance_mapping_version "
                    + "(tenant_id, mapping_code, mapping_version, mapping_hash, review_policy_hash, effective_from, status, active_slot) "
                    + "VALUES ('default', 'G2-ACTIVE-DUP', 'v1-dup', REPEAT('a', 64), REPEAT('b', 64), '2026-08-01', 'ACTIVE', 1)");
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO m_compliance_mapping_version "
                            + "(tenant_id, mapping_code, mapping_version, mapping_hash, review_policy_hash, effective_from, status, active_slot) "
                            + "VALUES ('default', 'G2-ACTIVE-DUP', 'v2', REPEAT('c', 64), REPEAT('d', 64), '2026-08-02', 'ACTIVE', 1)"),
                    "同一mapping codeのACTIVE二重登録は拒否するはず");

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

            assertRejectedWithoutRowChange(statement,
                    "INSERT INTO t_compliance_operation_ledger "
                            + "(tenant_id, operation_id, operation_type, idempotency_key, request_hash, state, started_at, correlation_id) VALUES "
                            + "('default', 'g2-operation-invalid-failed', 'MAPPING_ACTIVE', 'g2-op-invalid-failed-key', REPEAT('i', 64), "
                            + "'FAILED', '2026-08-01 00:00:01.000000', 'g2-op-invalid-failed-correlation')",
                    "t_compliance_operation_ledger",
                    "tenant_id='default' AND operation_id='g2-operation-invalid-failed'",
                    "FAILED初期operation INSERTは拒否するはず");
            assertRejectedWithoutRowChange(statement,
                    "INSERT INTO t_compliance_operation_ledger "
                            + "(tenant_id, operation_id, operation_type, idempotency_key, request_hash, state, started_at, finished_at, failure_code, correlation_id) VALUES "
                            + "('default', 'g2-operation-invalid-processing', 'MAPPING_ACTIVE', 'g2-op-invalid-processing-key', REPEAT('j', 64), "
                            + "'PROCESSING', '2026-08-01 00:00:01.000000', '2026-08-01 00:00:02.000000', 'BROKEN', 'g2-op-invalid-processing-correlation')",
                    "t_compliance_operation_ledger",
                    "tenant_id='default' AND operation_id='g2-operation-invalid-processing'",
                    "finished/failure付きPROCESSING INSERTは拒否するはず");
            assertRejectedWithoutRowChange(statement,
                    "INSERT INTO t_compliance_operation_ledger "
                            + "(tenant_id, operation_id, operation_type, idempotency_key, request_hash, state, retryable_flag, attempt_count, started_at, correlation_id, version, deleted_flag) VALUES "
                            + "('default', 'g2-operation-invalid-retryable', 'MAPPING_ACTIVE', 'g2-op-invalid-retryable-key', REPEAT('k', 64), "
                            + "'PROCESSING', 1, 1, '2026-08-01 00:00:01.000000', 'g2-op-invalid-retryable-correlation', 0, 0)",
                    "t_compliance_operation_ledger",
                    "tenant_id='default' AND operation_id='g2-operation-invalid-retryable'",
                    "retryable_flag=1のclaim INSERTは拒否するはず");
            assertRejectedWithoutRowChange(statement,
                    "INSERT INTO t_compliance_operation_ledger "
                            + "(tenant_id, operation_id, operation_type, idempotency_key, request_hash, state, retryable_flag, attempt_count, started_at, correlation_id, version, deleted_flag) VALUES "
                            + "('default', 'g2-operation-invalid-attempt', 'MAPPING_ACTIVE', 'g2-op-invalid-attempt-key', REPEAT('l', 64), "
                            + "'PROCESSING', 0, 2, '2026-08-01 00:00:01.000000', 'g2-op-invalid-attempt-correlation', 0, 0)",
                    "t_compliance_operation_ledger",
                    "tenant_id='default' AND operation_id='g2-operation-invalid-attempt'",
                    "attempt_count!=1のclaim INSERTは拒否するはず");
            assertRejectedWithoutRowChange(statement,
                    "INSERT INTO t_compliance_operation_ledger "
                            + "(tenant_id, operation_id, operation_type, idempotency_key, request_hash, state, retryable_flag, attempt_count, started_at, correlation_id, version, deleted_flag) VALUES "
                            + "('default', 'g2-operation-invalid-version', 'MAPPING_ACTIVE', 'g2-op-invalid-version-key', REPEAT('m', 64), "
                            + "'PROCESSING', 0, 1, '2026-08-01 00:00:01.000000', 'g2-op-invalid-version-correlation', 1, 0)",
                    "t_compliance_operation_ledger",
                    "tenant_id='default' AND operation_id='g2-operation-invalid-version'",
                    "version!=0のclaim INSERTは拒否するはず");
            assertRejectedWithoutRowChange(statement,
                    "INSERT INTO t_compliance_operation_ledger "
                            + "(tenant_id, operation_id, operation_type, idempotency_key, request_hash, state, retryable_flag, attempt_count, started_at, correlation_id, version, deleted_flag) VALUES "
                            + "('default', 'g2-operation-invalid-deleted', 'MAPPING_ACTIVE', 'g2-op-invalid-deleted-key', REPEAT('n', 64), "
                            + "'PROCESSING', 0, 1, '2026-08-01 00:00:01.000000', 'g2-op-invalid-deleted-correlation', 0, 1)",
                    "t_compliance_operation_ledger",
                    "tenant_id='default' AND operation_id='g2-operation-invalid-deleted'",
                    "deleted_flag=1のclaim INSERTは拒否するはず");
            statement.executeUpdate("INSERT INTO t_compliance_operation_ledger "
                    + "(tenant_id, operation_id, operation_type, idempotency_key, request_hash, state, "
                    + "started_at, result_summary_canonical, result_http_status, correlation_id, version, deleted_flag) VALUES "
                    + "('default', 'g2-operation-1', 'MAPPING_ACTIVE', 'g2-op-key-1', REPEAT('d', 64), 'PROCESSING', "
                    + "'2026-08-01 00:00:01.000000', NULL, NULL, 'g2-op-correlation-1', 0, 0)");
            long operationId = queryLong(statement,
                    "SELECT id FROM t_compliance_operation_ledger WHERE operation_id='g2-operation-1'");
            statement.executeUpdate("UPDATE t_compliance_operation_ledger SET state='SUCCEEDED', finished_at='2026-08-01 00:00:02.000000', "
                    + "result_summary_canonical='{}', result_http_status=200, result_hash=REPEAT('f', 64), "
                    + "version=1 WHERE id=" + operationId);
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE t_compliance_operation_ledger SET result_summary_canonical='tampered', version=2 WHERE id=" + operationId),
                    "SUCCEEDED operationのresult改変は拒否するはず");
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "DELETE FROM t_compliance_operation_ledger WHERE id=" + operationId),
                    "operation ledgerのDELETEは拒否するはず");

            statement.executeUpdate("INSERT INTO t_compliance_operation_ledger "
                    + "(tenant_id, operation_id, operation_type, idempotency_key, request_hash, state, "
                    + "started_at, correlation_id, version, deleted_flag) VALUES "
                    + "('default', 'g2-operation-nohash', 'MAPPING_ACTIVE', 'g2-op-nohash-key', REPEAT('g', 64), "
                    + "'PROCESSING', '2026-08-01 00:00:01.000000', 'g2-op-nohash-correlation', 0, 0)");
            long noHashOperationId = queryLong(statement,
                    "SELECT id FROM t_compliance_operation_ledger WHERE operation_id='g2-operation-nohash'");
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE t_compliance_operation_ledger SET state='SUCCEEDED', result_summary_canonical='{}', "
                            + "result_http_status=200, version=1 WHERE id=" + noHashOperationId),
                    "SUCCEEDED operationはresult_hash必須のはず");

            statement.executeUpdate("INSERT INTO t_compliance_operation_ledger "
                    + "(tenant_id, operation_id, operation_type, idempotency_key, request_hash, state, "
                    + "started_at, correlation_id, version, deleted_flag) VALUES "
                    + "('default', 'g2-operation-retry', 'MAPPING_ACTIVE', 'g2-op-retry-key', REPEAT('e', 64), "
                    + "'PROCESSING', '2026-08-01 00:00:01.000000', 'g2-op-retry-correlation', 0, 0)");
            long retryOperationId = queryLong(statement,
                    "SELECT id FROM t_compliance_operation_ledger WHERE operation_id='g2-operation-retry'");
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE t_compliance_operation_ledger SET state='FAILED', retryable_flag=1, "
                            + "finished_at='2026-08-01 00:00:02.000000', failure_code='TEMPORARY', "
                            + "result_summary_canonical='forbidden', result_http_status=500, result_hash=REPEAT('h', 64), "
                            + "version=1 WHERE id=" + retryOperationId),
                    "FAILED operationはresult payloadを保持しないはず");
            statement.executeUpdate("UPDATE t_compliance_operation_ledger SET state='FAILED', retryable_flag=1, "
                    + "finished_at='2026-08-01 00:00:02.000000', failure_code='TEMPORARY', version=1 WHERE id="
                    + retryOperationId);
            assertEquals(0L, queryLong(statement,
                    "SELECT COUNT(*) FROM t_compliance_operation_ledger WHERE id=" + retryOperationId
                            + " AND (result_summary_canonical IS NOT NULL OR result_http_status IS NOT NULL OR result_hash IS NOT NULL)"));
            statement.executeUpdate("UPDATE t_compliance_operation_ledger SET state='PROCESSING', retryable_flag=1, "
                    + "attempt_count=attempt_count+1, lease_until='2026-08-01 00:05:00.000000', finished_at=NULL, "
                    + "failure_code=NULL, result_summary_canonical=NULL, result_http_status=NULL, result_hash=NULL, "
                    + "version=2 WHERE id=" + retryOperationId);
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE t_compliance_operation_ledger SET result_summary_canonical='tampered', version=3 "
                            + "WHERE id=" + retryOperationId),
                    "PROCESSING lease以外のresult改変は拒否するはず");
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE t_compliance_operation_ledger SET state='PROCESSING', retryable_flag=0, "
                            + "finished_at='2026-08-01 00:00:03.000000', failure_code=NULL, version=3 WHERE id="
                            + retryOperationId),
                    "PROCESSING中の不正transitionは拒否するはず");
            statement.executeUpdate("UPDATE t_compliance_operation_ledger SET state='FAILED', retryable_flag=0, "
                    + "finished_at='2026-08-01 00:00:03.000000', failure_code='PERMANENT', version=3 WHERE id="
                    + retryOperationId);
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE t_compliance_operation_ledger SET state='PROCESSING', retryable_flag=1, "
                            + "attempt_count=attempt_count+1, lease_until='2026-08-01 00:06:00.000000', "
                            + "finished_at=NULL, failure_code=NULL, version=4 WHERE id=" + retryOperationId),
                    "retryableでないFAILEDの再開は拒否するはず");

            long otherMappingId = insertMapping(statement, "other-tenant", "G2-OTHER", "v1", "DRAFT");
            long otherWorkplaceId = insertWorkplace(statement, "other-tenant", customerId, "G2-WORKPLACE-B");
            long otherAssignmentId = insertOpenAssignment(statement, "other-tenant", otherWorkplaceId, userId,
                    "2026-08-01 00:00:01.000000");
            long firstApprovalId = insertApproval(statement, "default", mappingId, assignmentId, workplaceId, userId,
                    null, "g2-approval-1");
            long secondApprovalId = insertApproval(statement, "default", mappingId, assignmentId, workplaceId, userId,
                    firstApprovalId, firstApprovalId, "g2-approval-supersedes");
            assertTrue(secondApprovalId > firstApprovalId);
            statement.executeUpdate("INSERT INTO m_compliance_mapping_review_requirement_type "
                    + "(tenant_id, requirement_group_id, reviewer_type_id, reviewer_type_code_snapshot, "
                    + "reviewer_type_name_snapshot, credential_label_snapshot, credential_required_snapshot) "
                    + "VALUES ('default', " + groupId + ", " + reviewerTypeId
                    + ", 'TYPE-1', 'Type 1', 'Credential', 0)");
            assertRejectedWithoutRowChange(statement,
                    "INSERT INTO m_compliance_mapping_review_requirement_group "
                            + "(tenant_id, mapping_id, requirement_group_code, display_name, minimum_distinct_reviewers) "
                            + "VALUES ('other-tenant', " + mappingId + ", 'GROUP-CROSS', 'Cross', 1)",
                    "m_compliance_mapping_review_requirement_group",
                    "tenant_id='other-tenant' AND requirement_group_code='GROUP-CROSS'",
                    "mapping→groupのcross-tenant参照は拒否するはず");
            long otherReviewerTypeId = insertReviewerType(statement, "other-tenant", "TYPE-CROSS");
            long otherGroupId = insertReviewGroup(statement, "other-tenant", otherMappingId, "GROUP-OTHER");
            long secondReviewId = insertExternalReview(statement, "default", mappingId, groupId, reviewerTypeId,
                    userId, reviewId, reviewId, "g2-review-supersedes");
            assertTrue(secondReviewId > reviewId);
            assertRejectedWithoutRowChange(statement,
                    externalReviewSql("default", otherMappingId, groupId, reviewerTypeId, userId,
                            null, null, "g2-review-map-cross"),
                    "t_compliance_external_review_event",
                    "tenant_id='default' AND idempotency_key='g2-review-map-cross'",
                    "external review→mappingのcross-tenant参照は拒否するはず");
            assertRejectedWithoutRowChange(statement,
                    externalReviewSql("default", mappingId, otherGroupId, reviewerTypeId, userId,
                            null, null, "g2-review-group-cross"),
                    "t_compliance_external_review_event",
                    "tenant_id='default' AND idempotency_key='g2-review-group-cross'",
                    "external review→groupのcross-tenant参照は拒否するはず");
            assertRejectedWithoutRowChange(statement,
                    externalReviewSql("default", mappingId, groupId, otherReviewerTypeId, userId,
                            null, null, "g2-review-reviewer-cross"),
                    "t_compliance_external_review_event",
                    "tenant_id='default' AND idempotency_key='g2-review-reviewer-cross'",
                    "external review→reviewer typeのcross-tenant参照は拒否するはず");
            assertRejectedWithoutRowChange(statement,
                    externalReviewSql("other-tenant", otherMappingId, otherGroupId, otherReviewerTypeId, userId,
                            reviewId, null, "g2-review-target-cross"),
                    "t_compliance_external_review_event",
                    "tenant_id='other-tenant' AND idempotency_key='g2-review-target-cross'",
                    "external review targetのcross-tenant参照は拒否するはず");
            assertRejectedWithoutRowChange(statement,
                    externalReviewSql("other-tenant", otherMappingId, otherGroupId, otherReviewerTypeId, userId,
                            null, reviewId, "g2-review-supersedes-cross"),
                    "t_compliance_external_review_event",
                    "tenant_id='other-tenant' AND idempotency_key='g2-review-supersedes-cross'",
                    "external review supersedesのcross-tenant参照は拒否するはず");
            assertRejectedWithoutRowChange(statement,
                    externalReviewSql("default", mappingId, groupId, reviewerTypeId, userId,
                            999999L, null, "g2-review-target-orphan"),
                    "t_compliance_external_review_event",
                    "tenant_id='default' AND idempotency_key='g2-review-target-orphan'",
                    "external review targetの孤立参照は拒否するはず");
            assertRejectedWithoutRowChange(statement,
                    externalReviewSql("default", mappingId, groupId, reviewerTypeId, userId,
                            null, 999999L, "g2-review-supersedes-orphan"),
                    "t_compliance_external_review_event",
                    "tenant_id='default' AND idempotency_key='g2-review-supersedes-orphan'",
                    "external review supersedesの孤立参照は拒否するはず");
            assertRejectedWithoutRowChange(statement,
                    "INSERT INTO m_compliance_mapping_review_requirement_type "
                            + "(tenant_id, requirement_group_id, reviewer_type_id, reviewer_type_code_snapshot, "
                            + "reviewer_type_name_snapshot, credential_label_snapshot, credential_required_snapshot) "
                            + "VALUES ('other-tenant', " + groupId + ", " + otherReviewerTypeId
                            + ", 'TYPE-CROSS', 'Cross', 'Credential', 0)",
                    "m_compliance_mapping_review_requirement_type",
                    "tenant_id='other-tenant' AND requirement_group_id=" + groupId,
                    "group→reviewer typeのcross-tenant参照は拒否するはず");
            assertRejectedWithoutRowChange(statement,
                    "INSERT INTO m_compliance_mapping_review_requirement_type "
                            + "(tenant_id, requirement_group_id, reviewer_type_id, reviewer_type_code_snapshot, "
                            + "reviewer_type_name_snapshot, credential_label_snapshot, credential_required_snapshot) "
                            + "VALUES ('default', " + groupId + ", " + otherReviewerTypeId
                            + ", 'TYPE-CROSS', 'Cross', 'Credential', 0)",
                    "m_compliance_mapping_review_requirement_type",
                    "tenant_id='default' AND reviewer_type_id=" + otherReviewerTypeId,
                    "group→reviewer typeのcross-tenant参照は拒否するはず");

            long sourceCrossMappingId = insertMapping(statement, "default", "G2-SOURCE-CROSS", "v1-sc", "DRAFT");
            assertRejectedWithoutRowChange(statement,
                    "INSERT INTO m_compliance_mapping_source "
                            + "(tenant_id, mapping_id, source_code, source_url, source_version, confirmed_on, effective_from) "
                            + "VALUES ('other-tenant', " + sourceCrossMappingId + ", 'SRC-CROSS', "
                            + "'https://example.invalid/cross', '1', '2026-08-01', '2026-08-01')",
                    "m_compliance_mapping_source",
                    "tenant_id='other-tenant' AND mapping_id=" + sourceCrossMappingId,
                    "source→mappingのcross-tenant参照は拒否するはず");
            assertRejectedWithoutRowChange(statement,
                    "INSERT INTO t_compliance_responsible_assignment "
                            + "(tenant_id, workplace_id, user_id, effective_from, active_slot, assigned_by) VALUES "
                            + "('default', " + otherWorkplaceId + ", " + userId
                            + ", '2026-08-03 00:00:01.000000', 1, " + userId + ")",
                    "t_compliance_responsible_assignment",
                    "tenant_id='default' AND workplace_id=" + otherWorkplaceId,
                    "assignment→workplaceのcross-tenant参照は拒否するはず");
            assertRejectedWithoutRowChange(statement,
                    "INSERT INTO t_compliance_responsible_assignment "
                            + "(tenant_id, workplace_id, user_id, effective_from, active_slot, assigned_by) VALUES "
                            + "('default', 999999, " + userId
                            + ", '2026-08-03 00:00:01.000000', 1, " + userId + ")",
                    "t_compliance_responsible_assignment",
                    "tenant_id='default' AND workplace_id=999999",
                    "assignment→workplaceの孤立参照は拒否するはず");
            assertRejectedWithoutRowChange(statement,
                    "INSERT INTO t_compliance_mapping_approval_event "
                            + "(tenant_id, mapping_id, mapping_version, mapping_hash, review_policy_hash, assignment_id, "
                            + "workplace_id_snapshot, actor_id, actor_display_name_snapshot, actor_role_snapshot, action, "
                            + "event_chain_id, occurred_at, operation_id, correlation_id, idempotency_key) VALUES ('other-tenant', "
                            + mappingId + ", 'v1', REPEAT('a', 64), REPEAT('b', 64), " + otherAssignmentId + ", "
                            + otherWorkplaceId + ", " + userId + ", 'Actor', 'COMPLIANCE_RESPONSIBLE', 'APPROVE', 'chain-map-cross', "
                            + "'2026-08-01 00:00:01.000000', UUID(), 'corr-map-cross', 'g2-approval-map-cross')",
                    "t_compliance_mapping_approval_event",
                    "tenant_id='other-tenant' AND idempotency_key='g2-approval-map-cross'",
                    "approval→mappingのcross-tenant参照は拒否するはず");
            assertRejectedWithoutRowChange(statement,
                    "INSERT INTO t_compliance_mapping_approval_event "
                            + "(tenant_id, mapping_id, mapping_version, mapping_hash, review_policy_hash, assignment_id, "
                            + "workplace_id_snapshot, actor_id, actor_display_name_snapshot, actor_role_snapshot, action, "
                            + "event_chain_id, occurred_at, operation_id, correlation_id, idempotency_key) VALUES ('default', "
                            + mappingId + ", 'v1', REPEAT('a', 64), REPEAT('b', 64), " + otherAssignmentId + ", "
                            + workplaceId + ", " + userId + ", 'Actor', 'COMPLIANCE_RESPONSIBLE', 'APPROVE', 'chain-asg-cross', "
                            + "'2026-08-01 00:00:01.000000', UUID(), 'corr-asg-cross', 'g2-approval-asg-cross')",
                    "t_compliance_mapping_approval_event",
                    "tenant_id='default' AND idempotency_key='g2-approval-asg-cross'",
                    "approval→assignmentのcross-tenant参照は拒否するはず");
            assertRejectedWithoutRowChange(statement,
                    "INSERT INTO t_compliance_mapping_approval_event "
                            + "(tenant_id, mapping_id, mapping_version, mapping_hash, review_policy_hash, assignment_id, "
                            + "workplace_id_snapshot, actor_id, actor_display_name_snapshot, actor_role_snapshot, action, "
                            + "event_chain_id, occurred_at, operation_id, correlation_id, idempotency_key) VALUES ('default', "
                            + mappingId + ", 'v1', REPEAT('a', 64), REPEAT('b', 64), " + assignmentId + ", "
                            + otherWorkplaceId + ", " + userId + ", 'Actor', 'COMPLIANCE_RESPONSIBLE', 'APPROVE', 'chain-wp-cross', "
                            + "'2026-08-01 00:00:01.000000', UUID(), 'corr-wp-cross', 'g2-approval-wp-cross')",
                    "t_compliance_mapping_approval_event",
                    "tenant_id='default' AND idempotency_key='g2-approval-wp-cross'",
                    "approval→workplaceのcross-tenant参照は拒否するはず");
            assertRejectedWithoutRowChange(statement,
                    "INSERT INTO t_compliance_mapping_approval_event "
                            + "(tenant_id, mapping_id, mapping_version, mapping_hash, review_policy_hash, assignment_id, "
                            + "workplace_id_snapshot, actor_id, actor_display_name_snapshot, actor_role_snapshot, action, "
                            + "event_chain_id, target_event_id, occurred_at, operation_id, correlation_id, idempotency_key) VALUES ('other-tenant', "
                            + otherMappingId + ", 'v1', REPEAT('a', 64), REPEAT('b', 64), " + otherAssignmentId + ", "
                            + otherWorkplaceId + ", " + userId + ", 'Actor', 'COMPLIANCE_RESPONSIBLE', 'APPROVE', 'chain-target-cross', "
                            + firstApprovalId + ", '2026-08-01 00:00:01.000000', UUID(), 'corr-target-cross', 'g2-approval-target-cross')",
                    "t_compliance_mapping_approval_event",
                    "tenant_id='other-tenant' AND idempotency_key='g2-approval-target-cross'",
                    "approval targetのcross-tenant参照は拒否するはず");
            assertRejectedWithoutRowChange(statement,
                    "INSERT INTO t_compliance_mapping_approval_event "
                            + "(tenant_id, mapping_id, mapping_version, mapping_hash, review_policy_hash, assignment_id, "
                            + "workplace_id_snapshot, actor_id, actor_display_name_snapshot, actor_role_snapshot, action, "
                            + "event_chain_id, target_event_id, occurred_at, operation_id, correlation_id, idempotency_key) VALUES ('default', "
                            + mappingId + ", 'v1', REPEAT('a', 64), REPEAT('b', 64), " + assignmentId + ", "
                            + workplaceId + ", " + userId + ", 'Actor', 'COMPLIANCE_RESPONSIBLE', 'APPROVE', 'chain-target-orphan', "
                            + "999999, '2026-08-01 00:00:01.000000', UUID(), 'corr-target-orphan', 'g2-approval-target-orphan')",
                    "t_compliance_mapping_approval_event",
                    "tenant_id='default' AND idempotency_key='g2-approval-target-orphan'",
                    "approval targetの孤立参照は拒否するはず");
            assertRejectedWithoutRowChange(statement,
                    "INSERT INTO t_compliance_mapping_approval_event "
                            + "(tenant_id, mapping_id, mapping_version, mapping_hash, review_policy_hash, assignment_id, "
                            + "workplace_id_snapshot, actor_id, actor_display_name_snapshot, actor_role_snapshot, action, "
                            + "event_chain_id, supersedes_event_id, occurred_at, operation_id, correlation_id, idempotency_key) VALUES ('other-tenant', "
                            + otherMappingId + ", 'v1', REPEAT('a', 64), REPEAT('b', 64), " + otherAssignmentId + ", "
                            + otherWorkplaceId + ", " + userId + ", 'Actor', 'COMPLIANCE_RESPONSIBLE', 'APPROVE', 'chain-supersedes-cross', "
                            + firstApprovalId + ", '2026-08-01 00:00:01.000000', UUID(), 'corr-supersedes-cross', 'g2-approval-supersedes-cross')",
                    "t_compliance_mapping_approval_event",
                    "tenant_id='other-tenant' AND idempotency_key='g2-approval-supersedes-cross'",
                    "approval supersedesのcross-tenant参照は拒否するはず");
            assertRejectedWithoutRowChange(statement,
                    "INSERT INTO t_compliance_mapping_approval_event "
                            + "(tenant_id, mapping_id, mapping_version, mapping_hash, review_policy_hash, assignment_id, "
                            + "workplace_id_snapshot, actor_id, actor_display_name_snapshot, actor_role_snapshot, action, "
                            + "event_chain_id, supersedes_event_id, occurred_at, operation_id, correlation_id, idempotency_key) VALUES ('default', "
                            + mappingId + ", 'v1', REPEAT('a', 64), REPEAT('b', 64), " + assignmentId + ", "
                            + workplaceId + ", " + userId + ", 'Actor', 'COMPLIANCE_RESPONSIBLE', 'APPROVE', 'chain-supersedes-orphan', "
                            + "999999, '2026-08-01 00:00:01.000000', UUID(), 'corr-supersedes-orphan', 'g2-approval-supersedes-orphan')",
                    "t_compliance_mapping_approval_event",
                    "tenant_id='default' AND idempotency_key='g2-approval-supersedes-orphan'",
                    "approval supersedesの孤立参照は拒否するはず");
            statement.executeUpdate("INSERT INTO t_compliance_mapping_status_event "
                    + "(tenant_id, mapping_id, mapping_version, mapping_hash, review_policy_hash, before_status, after_status, "
                    + "actor_id, actor_display_name_snapshot, actor_role_snapshot, occurred_at, expected_version, operation_id, correlation_id) VALUES "
                    + "('default', " + mappingId + ", 'v1', REPEAT('a', 64), REPEAT('b', 64), 'DRAFT', 'PROVISIONAL_REVIEWED', "
                    + userId + ", 'Actor', 'ADMIN', '2026-08-01 00:00:01.000000', 0, UUID(), 'corr-status-same')");
            assertEquals(1L, queryLong(statement,
                    "SELECT COUNT(*) FROM t_compliance_mapping_status_event WHERE tenant_id='default' AND correlation_id='corr-status-same'"));
            assertRejectedWithoutRowChange(statement,
                    "INSERT INTO t_compliance_mapping_status_event "
                            + "(tenant_id, mapping_id, mapping_version, mapping_hash, review_policy_hash, before_status, after_status, "
                            + "actor_id, actor_display_name_snapshot, actor_role_snapshot, occurred_at, expected_version, operation_id, correlation_id) VALUES "
                            + "('other-tenant', " + mappingId + ", 'v1', REPEAT('a', 64), REPEAT('b', 64), 'DRAFT', 'PROVISIONAL_REVIEWED', "
                            + userId + ", 'Actor', 'ADMIN', '2026-08-01 00:00:01.000000', 0, UUID(), 'corr-status-cross')",
                    "t_compliance_mapping_status_event",
                    "tenant_id='other-tenant' AND correlation_id='corr-status-cross'",
                    "status→mappingのcross-tenant参照は拒否するはず");

            long finiteWorkplaceId = insertWorkplace(statement, "default", customerId, "G2-WORKPLACE-FINITE");
            insertFiniteAssignment(statement, "default", finiteWorkplaceId, userId,
                    "2026-08-10 00:00:00.000000", "2026-08-10 00:00:01.000000", "finite-a");
            insertFiniteAssignment(statement, "default", finiteWorkplaceId, userId,
                    "2026-08-10 00:00:01.000000", "2026-08-10 00:00:02.000000", "finite-b");
            long beforeAdjacent = queryAssignmentAt(statement, finiteWorkplaceId, "2026-08-09 23:59:59.999999");
            long firstAdjacent = queryAssignmentAt(statement, finiteWorkplaceId, "2026-08-10 00:00:00.999999");
            long secondAdjacent = queryAssignmentAt(statement, finiteWorkplaceId, "2026-08-10 00:00:01.000000");
            long atEnd = queryAssignmentAt(statement, finiteWorkplaceId, "2026-08-10 00:00:02.000000");
            assertEquals(-1L, beforeAdjacent);
            assertTrue(firstAdjacent > 0L);
            assertTrue(secondAdjacent > 0L && secondAdjacent != firstAdjacent);
            assertEquals(-1L, atEnd);
            assertEquals(0L, queryLong(statement,
                    "SELECT COUNT(*) FROM t_compliance_responsible_assignment WHERE workplace_id=" + finiteWorkplaceId
                            + " AND effective_from < '2026-08-10 00:00:03.000000'"
                            + " AND (effective_to IS NULL OR effective_to > '2026-08-10 00:00:02.000000')"));
            assertTrue(queryLong(statement,
                    "SELECT COUNT(*) FROM t_compliance_responsible_assignment WHERE workplace_id=" + finiteWorkplaceId
                            + " AND effective_from < '2026-08-10 00:00:01.500000'"
                            + " AND (effective_to IS NULL OR effective_to > '2026-08-10 00:00:00.500000')") > 0);

            // ===== V102_3検証（R23-R2-P1-02・target 102_3で適用済み） =====
            // dynamic master・qualification association・frozen flags列・first_slot UNIQUE・subject UPDATE trigger
            for (String column : new String[]{
                    "qualification_verification_required", "active_status_verification_required",
                    "verification_source_id", "verification_method_id", "max_age_days",
                    "effective_from", "effective_to"}) {
                assertColumnExists(statement, "m_compliance_external_reviewer_type", column);
            }
            for (String column : new String[]{
                    "qualification_verification_required_snapshot",
                    "active_status_verification_required_snapshot"}) {
                assertColumnExists(statement, "m_compliance_mapping_review_requirement_type", column);
            }
            assertColumnExists(statement, "t_compliance_external_review_adoption_event", "first_slot");
            assertIndexExists(statement, "t_compliance_external_review_adoption_event", "uk_g2_adoption_first");
            assertColumnExists(statement, "t_compliance_mapping_approval_event", "evidence_scan_status");
            assertTriggerExists(statement, "trg_g2_subject_no_update");
            // reviewer typeへdynamic列を設定できる
            statement.executeUpdate("UPDATE m_compliance_external_reviewer_type "
                    + "SET qualification_verification_required=1, active_status_verification_required=1, max_age_days=365 "
                    + "WHERE tenant_id='default' AND type_code='LABOR_CONSULTANT'");
        }
    }

    /**
     * R23-R2-P1-01/02: V102まで適用したDBをV102_3へupgradeする。
     * fresh DBは新V1（consolidated baseline）でfirst_slot定義済みのため、
     * V102_3が既存列へUNIQUE(tenant_id, first_slot)を追加できることと、
     * 新規テーブル/列/triggerが適用されることを検証する。
     * （旧V1（first_slotなし）からのupgrade経路はローカルMySQLで別途検証済み・
     *   V102_3の情報スキーマガード付きADD COLUMNがUnknown columnを回避する）
     */
    @Test
    void V102適用済みDBをV102_3へupgradeするとfirst_slotのUNIQUEと新規オブジェクトが追加される() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("102")
                .load()
                .migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            // V102時点: 新V1（consolidated baseline）由来のfirst_slot列とUNIQUE indexが既に存在する
            // （旧V1からのupgrade経路はローカルMySQLで検証済み: 情報スキーマガード付きADD COLUMNが適用される）
            assertColumnExists(statement, "t_compliance_external_review_adoption_event", "first_slot");
            assertIndexExists(statement, "t_compliance_external_review_adoption_event", "uk_g2_adoption_first");
        }

        // V102_3までupgrade（V102_1/V102_2/V102_3を順次適用）
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("102_3")
                .load()
                .migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            assertColumnExists(statement, "t_compliance_external_review_adoption_event", "first_slot");
            assertIndexExists(statement, "t_compliance_external_review_adoption_event", "uk_g2_adoption_first");
            assertColumnExists(statement, "m_compliance_external_reviewer_type", "qualification_verification_required");
            assertColumnExists(statement, "t_compliance_mapping_approval_event", "evidence_scan_status");
            assertTableExists(statement, "m_compliance_verification_source");
            assertTableExists(statement, "t_compliance_reviewer_qualification");
            assertTriggerExists(statement, "trg_g2_subject_no_update");
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
        return insertApproval(statement, tenant, mappingId, assignmentId, workplaceId, userId,
                targetEventId, null, idempotencyKey);
    }

    private long insertApproval(Statement statement, String tenant, long mappingId, long assignmentId,
                                long workplaceId, long userId, Long targetEventId, Long supersedesEventId,
                                String idempotencyKey) throws SQLException {
        statement.executeUpdate("INSERT INTO t_compliance_mapping_approval_event "
                + "(tenant_id, mapping_id, mapping_version, mapping_hash, review_policy_hash, assignment_id, "
                + "workplace_id_snapshot, actor_id, actor_display_name_snapshot, actor_role_snapshot, action, "
                + "event_chain_id, target_event_id, supersedes_event_id, occurred_at, operation_id, correlation_id, idempotency_key) VALUES ('"
                + tenant + "', " + mappingId + ", 'v1', REPEAT('a', 64), REPEAT('b', 64), " + assignmentId + ", "
                + workplaceId + ", " + userId + ", 'Actor', 'COMPLIANCE_RESPONSIBLE', 'APPROVE', 'chain-"
                + idempotencyKey + "', " + (targetEventId == null ? "NULL" : targetEventId) + ", "
                + (supersedesEventId == null ? "NULL" : supersedesEventId) + ", "
                + "'2026-08-01 00:00:01.000000', UUID(), 'corr-" + idempotencyKey + "', '" + idempotencyKey + "')");
        return queryLong(statement, "SELECT id FROM t_compliance_mapping_approval_event WHERE tenant_id='"
                + tenant + "' AND idempotency_key='" + idempotencyKey + "'");
    }

    private void assertRejectedWithoutRowChange(Statement statement, String sql, String table,
                                                String predicate, String message) throws SQLException {
        long before = queryLong(statement, "SELECT COUNT(*) FROM " + table + " WHERE " + predicate);
        SQLException failure = assertThrows(SQLException.class, () -> statement.executeUpdate(sql), message);
        assertTrue("23000".equals(failure.getSQLState()) || "45000".equals(failure.getSQLState()),
                message + "のSQLStateが不正です: " + failure.getSQLState());
        assertEquals(before, queryLong(statement, "SELECT COUNT(*) FROM " + table + " WHERE " + predicate),
                message + "で行が残ってはいけません");
    }

    private long insertReviewerType(Statement statement) throws SQLException {
        return insertReviewerType(statement, "default", "TYPE-1");
    }

    private long insertReviewerType(Statement statement, String tenant, String typeCode) throws SQLException {
        statement.executeUpdate("INSERT INTO m_compliance_external_reviewer_type "
                + "(tenant_id, type_code, display_name, credential_label) VALUES ('" + tenant + "', '" + typeCode
                + "', 'Type 1', 'Credential')");
        return queryLong(statement,
                "SELECT id FROM m_compliance_external_reviewer_type WHERE tenant_id='" + tenant
                        + "' AND type_code='" + typeCode + "'");
    }

    private void insertFiniteAssignment(Statement statement, String tenant, long workplaceId, long userId,
                                        String effectiveFrom, String effectiveTo, String reason) throws SQLException {
        statement.executeUpdate("INSERT INTO t_compliance_responsible_assignment "
                + "(tenant_id, workplace_id, user_id, effective_from, effective_to, active_slot, assigned_by, ended_by, end_reason) VALUES ('"
                + tenant + "', " + workplaceId + ", " + userId + ", '" + effectiveFrom + "', '" + effectiveTo
                + "', NULL, " + userId + ", " + userId + ", '" + reason + "')");
    }

    private long queryAssignmentAt(Statement statement, long workplaceId, String asOf) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(
                "SELECT id FROM t_compliance_responsible_assignment WHERE workplace_id=" + workplaceId
                        + " AND effective_from <= '" + asOf + "'"
                        + " AND (effective_to IS NULL OR '" + asOf + "' < effective_to)"
                        + " ORDER BY effective_from DESC, id DESC LIMIT 1")) {
            return resultSet.next() ? resultSet.getLong(1) : -1L;
        }
    }

    private long insertReviewGroup(Statement statement, long mappingId) throws SQLException {
        return insertReviewGroup(statement, "default", mappingId, "GROUP-1");
    }

    private long insertReviewGroup(Statement statement, String tenant, long mappingId, String groupCode)
            throws SQLException {
        statement.executeUpdate("INSERT INTO m_compliance_mapping_review_requirement_group "
                + "(tenant_id, mapping_id, requirement_group_code, display_name, minimum_distinct_reviewers) "
                + "VALUES ('" + tenant + "', " + mappingId + ", '" + groupCode + "', 'Group 1', 1)");
        return queryLong(statement,
                "SELECT id FROM m_compliance_mapping_review_requirement_group WHERE tenant_id='" + tenant
                        + "' AND requirement_group_code='" + groupCode + "'");
    }

    private long insertExternalReview(Statement statement, String tenant, long mappingId, long groupId,
                                      long reviewerTypeId, long userId, Long targetEventId,
                                      Long supersedesEventId, String idempotencyKey) throws SQLException {
        statement.executeUpdate(externalReviewSql(tenant, mappingId, groupId, reviewerTypeId, userId,
                targetEventId, supersedesEventId, idempotencyKey));
        return queryLong(statement,
                "SELECT id FROM t_compliance_external_review_event WHERE tenant_id='" + tenant
                        + "' AND idempotency_key='" + idempotencyKey + "'");
    }

    private String externalReviewSql(String tenant, long mappingId, long groupId, long reviewerTypeId,
                                     long userId, Long targetEventId, Long supersedesEventId,
                                     String idempotencyKey) {
        return "INSERT INTO t_compliance_external_review_event "
                + "(tenant_id, mapping_id, mapping_version, mapping_hash, review_policy_hash, requirement_group_id, "
                + "requirement_group_code_snapshot, reviewer_type_id, reviewer_type_code_snapshot, "
                + "reviewer_type_name_snapshot, reviewer_name_snapshot, reviewer_identity_hash, action, review_chain_id, "
                + "target_event_id, supersedes_event_id, reviewed_at, recorded_at, recorded_by, operation_id, correlation_id, idempotency_key) VALUES ('"
                + tenant + "', " + mappingId + ", 'v1', REPEAT('a', 64), REPEAT('b', 64), " + groupId + ", 'GROUP-1', "
                + reviewerTypeId + ", 'TYPE-1', 'Type 1', 'Reviewer 1', REPEAT('c', 64), 'APPROVED', 'chain-"
                + idempotencyKey + "', " + (targetEventId == null ? "NULL" : targetEventId) + ", "
                + (supersedesEventId == null ? "NULL" : supersedesEventId)
                + ", '2026-08-01 00:00:00.000000', '2026-08-01 00:00:00.000000', " + userId
                + ", UUID(), 'corr-" + idempotencyKey + "', '" + idempotencyKey + "')";
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
