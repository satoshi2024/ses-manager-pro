package com.ses.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NF-02/NF-03の最終migration番号・FK・index・境界DDLを固定する。 */
class Nf02Nf03V156MigrationContractTest {

    private static final Path MIGRATION_ROOT = Path.of("src/main/resources/db/migration");

    @Test
    void NF02_NF03の追加migrationがV156からV172まで順序通りに存在する() throws Exception {
        List<String> expected = List.of(
                "V156__nf02_nf03_boundary_repair.sql",
                "V157__nf02_nf03_tenant_attachment_notification_boundary.sql",
                "V158__certification_master_optimistic_lock.sql",
                "V159__approval_tenant_isolation.sql",
                "V160__task_notification_tenant_retry_state.sql",
                "V161__expense_accounting_job_tenant_scope.sql",
                "V162__notification_outbox_tenant_scope.sql",
                "V163__nf02_nf03_explicit_customer_engineer_ownership.sql",
                "V164__nf02_nf03_ownership_repair_operations.sql",
                "V165__nf03_bp_availability_tenant_scope.sql",
                "V166__nf02_nf03_engineer_account_link_tenant_repair.sql",
                "V167__nf03_ai_recommendation_tenant_records.sql",
                "V168__nf02_nf03_ownership_repair_authority_cas.sql",
                "V169__nf02_nf03_resume_candidate_skill_cas.sql",
                "V170__nf02_nf03_project_candidate_tenant_evidence.sql",
                "V171__contract_tenant_sla_notification_boundary.sql",
                "V172__nf02_nf03_contract_reference_ownership_repair.sql");
        for (String name : expected) {
            assertTrue(Files.exists(MIGRATION_ROOT.resolve(name)), name);
        }
        assertFalse(Files.exists(MIGRATION_ROOT.resolve("V150__nf02_nf03_boundary_repair.sql")));
        try (var paths = Files.list(MIGRATION_ROOT)) {
            List<Integer> versions = paths.map(path -> path.getFileName().toString())
                    .map(Pattern.compile("^V(\\d+)(__|_).*")::matcher)
                    .filter(java.util.regex.Matcher::matches)
                    .map(m -> Integer.valueOf(m.group(1)))
                    .filter(v -> v >= 156 && v <= 172)
                    .sorted()
                    .toList();
            assertEquals(List.of(156, 157, 158, 159, 160, 161, 162, 163, 164, 165, 166, 167, 168, 169, 170, 171, 172), versions);
        }
    }

    @Test
    void 採番証憑候補の境界DDLを持つ() throws Exception {
        String migration = Files.readString(MIGRATION_ROOT.resolve("V156__nf02_nf03_boundary_repair.sql"),
                StandardCharsets.UTF_8);

        assertTrue(migration.contains("CREATE TABLE t_service_request_sequence"));
        assertTrue(migration.contains("PRIMARY KEY (tenant_id, request_month)"));
        assertTrue(migration.contains("last_number BETWEEN 0 AND 9999"));
        assertTrue(migration.contains("uk_service_request_tenant_no"));
        assertTrue(migration.contains("idx_document_link_tenant_target"));
        assertTrue(migration.contains("response_breach_time_unknown"));
        assertTrue(migration.contains("resolve_breach_time_unknown"));
        assertTrue(migration.contains("MODIFY COLUMN continuity_group_id BIGINT NOT NULL AUTO_INCREMENT"));
        assertTrue(migration.contains("fk_learning_candidate_run"));
        assertTrue(migration.contains("fk_learning_candidate_snapshot"));
        assertTrue(migration.contains("uk_learning_decision_tenant_idempotency"));
        assertFalse(migration.contains("V150"));
    }

    @Test
    void V158以降のtenantと再試行境界DDLを固定する() throws Exception {
        String v158 = read("V158__certification_master_optimistic_lock.sql");
        String v159 = read("V159__approval_tenant_isolation.sql");
        String v160 = read("V160__task_notification_tenant_retry_state.sql");
        String v161 = read("V161__expense_accounting_job_tenant_scope.sql");
        String v162 = read("V162__notification_outbox_tenant_scope.sql");
        String v163 = read("V163__nf02_nf03_explicit_customer_engineer_ownership.sql");
        String v164 = read("V164__nf02_nf03_ownership_repair_operations.sql");
        String v165 = read("V165__nf03_bp_availability_tenant_scope.sql");
        String v166 = read("V166__nf02_nf03_engineer_account_link_tenant_repair.sql");
        String v167 = read("V167__nf03_ai_recommendation_tenant_records.sql");
        String v168 = read("V168__nf02_nf03_ownership_repair_authority_cas.sql");
        String v169 = read("V169__nf02_nf03_resume_candidate_skill_cas.sql");
        String v170 = read("V170__nf02_nf03_project_candidate_tenant_evidence.sql");
        String v171 = read("V171__contract_tenant_sla_notification_boundary.sql");
        String v172 = read("V172__nf02_nf03_contract_reference_ownership_repair.sql");

        assertTrue(v158.contains("ALTER TABLE m_certification"));
        assertTrue(v158.contains("version INT NOT NULL DEFAULT 0"));
        assertTrue(v159.contains("ALTER TABLE t_approval_request"));
        assertTrue(v159.contains("uk_approval_request_tenant_idempotency"));
        assertTrue(v159.contains("FOREIGN KEY (tenant_id, request_id)"));
        assertTrue(v160.contains("status = 'RETRY'"));
        assertTrue(v160.contains("attempt_count"));
        assertTrue(v160.contains("next_retry_at"));
        assertTrue(v160.contains("uk_task_notify_tenant_date"));
        assertFalse(v160.contains("UPDATE t_task_notification_log SET status = 'SENT' WHERE status IS NULL"));
        assertTrue(v161.contains("ALTER TABLE t_expense_accounting_job"));
        assertTrue(v161.contains("uk_expense_job_tenant_request"));
        assertTrue(v162.contains("ALTER TABLE t_notification_outbox"));
        assertTrue(v162.contains("uk_notification_outbox_tenant_dedupe"));
        assertTrue(v163.contains("ALTER TABLE m_customer"));
        assertTrue(v163.contains("ALTER TABLE t_engineer"));
        assertTrue(v163.contains("__ses_v163_check_customer_ownership"));
        assertTrue(v163.contains("__ses_v163_check_engineer_ownership"));
        assertTrue(v163.contains("nf02_nf03_ownership_repair_queue"));
        assertTrue(v163.contains("TENANT_UNRESOLVED"));
        assertTrue(v163.contains("idx_customer_tenant_population"));
        assertTrue(v163.contains("idx_engineer_tenant_population"));
        assertFalse(v163.contains("SET c.tenant_id = 'default'"));
        assertFalse(v163.contains("SET e.tenant_id = 'default'"));
        assertTrue(v164.contains("status VARCHAR(32) NOT NULL DEFAULT 'PENDING'"));
        assertTrue(v164.contains("repair_tenant_id"));
        assertTrue(v164.contains("resolution_reason"));
        assertTrue(v164.contains("evidence"));
        assertTrue(v164.contains("resolved_at"));
        assertTrue(v164.contains("resolved_by"));
        assertTrue(v164.contains("idx_nf02_nf03_repair_status_age"));
        assertTrue(v165.contains("ALTER TABLE t_bp_availability"));
        assertTrue(v165.contains("ADD COLUMN tenant_id VARCHAR(100) NULL"));
        assertTrue(v165.contains("BP_AVAILABILITY"));
        assertTrue(v165.contains("idx_bp_availability_tenant_population"));
        assertTrue(v166.contains("ENGINEER_ACCOUNT_LINK"));
        assertTrue(v166.contains("TENANT_UNRESOLVED"));
        assertTrue(v166.contains("idx_engineer_account_link_tenant_owner"));
        assertTrue(v167.contains("t_ai_recommendation_item"));
        assertTrue(v167.contains("t_ai_feedback"));
        assertTrue(v167.contains("t_ai_outcome"));
        assertTrue(v167.contains("__ses_check_v167_ai_tenant_records"));
        assertTrue(v167.contains("fk_ai_item_run_tenant"));
        assertTrue(v167.contains("fk_ai_feedback_item_tenant"));
        assertTrue(v167.contains("fk_ai_outcome_item_tenant"));
        assertFalse(v167.contains("tenant_id = 'default'"));
        assertTrue(v168.contains("claim_token"));
        assertTrue(v168.contains("evidence_hash"));
        assertTrue(v168.contains("incident_id"));
        assertTrue(v168.contains("idx_nf02_nf03_repair_claim"));
        assertTrue(v169.contains("ALTER TABLE t_resume_ingestion"));
        assertTrue(v169.contains("tenant_id VARCHAR(100) NULL"));
        assertTrue(v169.contains("MODIFY COLUMN stored_file_name VARCHAR(120) NULL"));
        assertTrue(v169.contains("idx_resume_ingestion_tenant_status_file"));
        assertTrue(v169.contains("ALTER TABLE t_candidate"));
        assertTrue(v169.contains("ALTER TABLE t_project"));
        assertTrue(v169.contains("version INT NOT NULL DEFAULT 0"));
        assertTrue(v169.contains("UPDATE t_resume_ingestion r"));
        assertFalse(v169.contains("tenant_id = 'default'"));
        assertTrue(v170.contains("ALTER TABLE t_project_ingestion"));
        assertTrue(v170.contains("tenant_id VARCHAR(100) NULL"));
        assertTrue(v170.contains("version INT NOT NULL DEFAULT 0"));
        assertTrue(v170.contains("idx_project_ingestion_tenant_status"));
        assertTrue(v170.contains("TENANT_EVIDENCE_CONFLICT"));
        assertTrue(v170.contains("conflicting_tenant_id"));
        assertTrue(v170.contains("evidence_hash"));
        assertTrue(v170.contains("SHA2"));
        assertTrue(v170.contains("t_candidate c"));
        assertTrue(v170.contains("t_resume_ingestion r"));
        assertFalse(v170.contains("tenant_id = 'default'"));
        assertTrue(v171.contains("ALTER TABLE t_contract"));
        assertTrue(v171.contains("tenant_id VARCHAR(100) NULL"));
        assertTrue(v171.contains("JOIN m_customer mc"));
        assertTrue(v171.contains("TENANT_UNRESOLVED"));
        assertTrue(v171.contains("idx_contract_tenant_customer_status_sales"));
        assertFalse(v171.contains("SET c.tenant_id = 'default'"));
        assertTrue(v172.contains("t_contract"));
        assertTrue(v172.contains("m_customer"));
        assertTrue(v172.contains("t_engineer"));
        assertTrue(v172.contains("t_project"));
        assertTrue(v172.contains("sys_user"));
        assertTrue(v172.contains("t_engineer_account_link"));
        assertTrue(v172.contains("t_user_organization"));
        assertTrue(v172.contains("nf02_nf03_ownership_repair_queue"));
        assertTrue(v172.contains("evidence_hash"));
        assertTrue(v172.contains("SHA2"));
        assertTrue(v172.contains("TENANT_REFERENCE_MISMATCH"));
        assertFalse(v172.contains("'default'"));
    }

    private String read(String name) throws Exception {
        return Files.readString(MIGRATION_ROOT.resolve(name), StandardCharsets.UTF_8);
    }
}
