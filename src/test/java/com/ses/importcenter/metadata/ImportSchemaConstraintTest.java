package com.ses.importcenter.metadata;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ImportSchemaConstraintTest {
    @Autowired private JdbcTemplate jdbc;

    @Test
    void directSqlCannotClearAnActiveSlotOrPublishOutsideTheUniqueSlot() {
        String sourceCode = "schema-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long sourceId = insert("t_import_source_system", Map.of(
                "tenant_id", "default", "source_code", sourceCode, "display_name", "schema test",
                "lookup_key_version", "test-v1", "created_by", 1L, "updated_by", 1L));
        byte[] digest = new byte[32];
        long mappingId = insert("t_import_mapping", Map.of(
                "tenant_id", "default", "source_system_id", sourceId, "entity_type", "CUSTOMER",
                "schema_version", "ses-import-v1", "mapping_version", 1, "canonical_bytes", new byte[]{1},
                "mapping_sha256", digest, "created_by", 1L));
        long documentId = insert("t_document", Map.of(
                "tenant_id", "default", "document_type", "SYNTHETIC_IMPORT", "direction", "INCOMING",
                "status", "CONFIRMED", "legal_hold_flag", 0, "version", 1L));
        long documentVersionId = insert("t_document_version", Map.of(
                "tenant_id", "default", "document_id", documentId, "version_no", 1, "storage_key", "schema-test-key",
                "original_name", "synthetic.csv", "sha256", "00".repeat(32), "source_type", "RECEIVED",
                "business_key", "schema-test", "version_discriminator", "1", "scan_status", "CLEAN"));
        long jobId = insert("t_import_job", Map.ofEntries(
                Map.entry("tenant_id", "default"), Map.entry("source_system_id", sourceId), Map.entry("source_version", 1L),
                Map.entry("entity_type", "CUSTOMER"), Map.entry("schema_version", "ses-import-v1"),
                Map.entry("source_encoding", "UTF8"), Map.entry("source_sha256", digest), Map.entry("legal_entity_id", "1"),
                Map.entry("document_id", documentId), Map.entry("document_version_id", documentVersionId),
                Map.entry("document_version_no", 1), Map.entry("document_sha256", digest),
                Map.entry("mapping_id", mappingId), Map.entry("mapping_version", 1), Map.entry("mapping_sha256", digest),
                Map.entry("created_by", 1L)));
        long runId = insert("t_import_validation_run", runValues(sourceId, mappingId, jobId, documentId, documentVersionId, digest));

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE t_import_validation_run SET active_slot = NULL WHERE tenant_id = 'default' AND id = ?", runId));

        completeRun(runId);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE t_import_validation_run SET published_slot = 2 WHERE tenant_id = 'default' AND id = ?", runId));
    }

    @Test
    void directSqlCannotPublishACompletedRunWithoutReconciliationEvidence() {
        String sourceCode = "summary-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long sourceId = insert("t_import_source_system", Map.of(
                "tenant_id", "default", "source_code", sourceCode, "display_name", "summary test",
                "lookup_key_version", "test-v1", "created_by", 1L, "updated_by", 1L));
        byte[] digest = new byte[32];
        long mappingId = insert("t_import_mapping", Map.of("tenant_id", "default", "source_system_id", sourceId,
                "entity_type", "CUSTOMER", "schema_version", "ses-import-v1", "mapping_version", 1,
                "canonical_bytes", new byte[]{1}, "mapping_sha256", digest, "created_by", 1L));
        long documentId = insert("t_document", Map.of("tenant_id", "default", "document_type", "SYNTHETIC_IMPORT",
                "direction", "INCOMING", "status", "CONFIRMED", "legal_hold_flag", 0, "version", 1L));
        long documentVersionId = insert("t_document_version", Map.of("tenant_id", "default", "document_id", documentId,
                "version_no", 1, "storage_key", "summary-test-key", "original_name", "synthetic.csv",
                "sha256", "00".repeat(32), "source_type", "RECEIVED", "business_key", "summary-test",
                "version_discriminator", "1", "scan_status", "CLEAN"));
        long jobId = insert("t_import_job", Map.ofEntries(Map.entry("tenant_id", "default"), Map.entry("source_system_id", sourceId),
                Map.entry("source_version", 1L), Map.entry("entity_type", "CUSTOMER"), Map.entry("schema_version", "ses-import-v1"),
                Map.entry("source_encoding", "UTF8"), Map.entry("source_sha256", digest), Map.entry("legal_entity_id", "1"),
                Map.entry("document_id", documentId), Map.entry("document_version_id", documentVersionId), Map.entry("document_version_no", 1),
                Map.entry("document_sha256", digest), Map.entry("mapping_id", mappingId), Map.entry("mapping_version", 1),
                Map.entry("mapping_sha256", digest), Map.entry("created_by", 1L)));
        long runId = insert("t_import_validation_run", runValues(sourceId, mappingId, jobId, documentId, documentVersionId, digest));

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE t_import_validation_run SET state='COMPLETED', active_slot=NULL, published_slot=1 WHERE tenant_id='default' AND id=?", runId));
    }

    private Map<String, Object> runValues(long sourceId, long mappingId, long jobId, long documentId,
                                          long versionId, byte[] digest) {
        return Map.ofEntries(Map.entry("tenant_id", "default"), Map.entry("job_id", jobId), Map.entry("run_no", 1),
                Map.entry("state", "BUILDING"), Map.entry("created_lease_generation", 1L),
                Map.entry("last_resumed_lease_generation", 1L), Map.entry("active_slot", 1),
                Map.entry("actor_user_id", 1L), Map.entry("source_system_id", sourceId), Map.entry("source_version", 1L),
                Map.entry("source_code_snapshot", "schema-test"), Map.entry("entity_type", "CUSTOMER"),
                Map.entry("schema_version", "ses-import-v1"), Map.entry("source_encoding", "UTF8"), Map.entry("source_sha256", digest),
                Map.entry("document_id", documentId), Map.entry("document_version_id", versionId), Map.entry("document_version_no", 1),
                Map.entry("document_sha256", digest), Map.entry("mapping_id", mappingId), Map.entry("mapping_version", 1),
                Map.entry("mapping_sha256", digest), Map.entry("validation_fingerprint", digest));
    }

    private void completeRun(long runId) {
        StringBuilder sql = new StringBuilder("UPDATE t_import_validation_run SET state='COMPLETED', active_slot=NULL, published_slot=1,")
                .append("source_row_count=0, physical_row_count=0, empty_row_count=0, accepted_row_count=0, rejected_row_count=0,")
                .append("finding_summary_bytes=?, finding_summary_sha256=?, amount_excluded_reason_counts=?, amount_excluded_reason_sha256=?, row_set_sha256=?, summary_sha256=?");
        for (String field : new String[]{"expected_unit_price", "unit_price_min", "unit_price_max", "selling_price", "cost_price"}) {
            sql.append(',').append(field).append("_accepted_known_count=0,").append(field).append("_accepted_sum_yen=NULL,")
                    .append(field).append("_rejected_known_count=0,").append(field).append("_rejected_sum_yen=NULL,")
                    .append(field).append("_excluded_count=0");
        }
        sql.append(" WHERE tenant_id='default' AND id=?");
        jdbc.update(sql.toString(), new byte[]{0}, new byte[32], new byte[]{0}, new byte[32], new byte[32], new byte[32], runId);
    }

    private long insert(String table, Map<String, Object> values) {
        Number key = new SimpleJdbcInsert(jdbc.getDataSource()).withTableName(table).usingGeneratedKeyColumns("id").executeAndReturnKey(values);
        return key.longValue();
    }
}
