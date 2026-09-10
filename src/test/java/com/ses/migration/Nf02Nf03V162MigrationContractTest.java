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
class Nf02Nf03V162MigrationContractTest {

    private static final Path MIGRATION_ROOT = Path.of("src/main/resources/db/migration");

    @Test
    void NF02_NF03の追加migrationがV162からV178まで順序通りに存在する() throws Exception {
        List<String> expected = List.of(
                "V162__nf02_nf03_boundary_repair.sql",
                "V163__nf02_nf03_tenant_attachment_notification_boundary.sql",
                "V164__certification_master_optimistic_lock.sql",
                "V165__approval_tenant_isolation.sql",
                "V166__task_notification_tenant_retry_state.sql",
                "V167__expense_accounting_job_tenant_scope.sql",
                "V168__notification_outbox_tenant_scope.sql",
                "V169__nf02_nf03_explicit_customer_engineer_ownership.sql",
                "V170__nf02_nf03_ownership_repair_operations.sql",
                "V171__nf03_bp_availability_tenant_scope.sql",
                "V172__nf02_nf03_engineer_account_link_tenant_repair.sql",
                "V173__nf03_ai_recommendation_tenant_records.sql",
                "V174__nf02_nf03_ownership_repair_authority_cas.sql",
                "V175__nf02_nf03_resume_candidate_skill_cas.sql",
                "V176__nf02_nf03_project_candidate_tenant_evidence.sql",
                "V177__contract_tenant_sla_notification_boundary.sql",
                "V178__nf02_nf03_contract_reference_ownership_repair.sql");
        for (String name : expected) {
            assertTrue(Files.exists(MIGRATION_ROOT.resolve(name)), name);
        }
        assertFalse(Files.exists(MIGRATION_ROOT.resolve("V150__nf02_nf03_boundary_repair.sql")));
        try (var paths = Files.list(MIGRATION_ROOT)) {
            List<Integer> versions = paths.map(path -> path.getFileName().toString())
                    .map(Pattern.compile("^V(\\d+)(__|_).*")::matcher)
                    .filter(java.util.regex.Matcher::matches)
                    .map(m -> Integer.valueOf(m.group(1)))
                    .filter(v -> v >= 162 && v <= 178)
                    .sorted()
                    .toList();
            assertEquals(List.of(162, 163, 164, 165, 166, 167, 168, 169, 170, 171, 172, 173, 174, 175, 176, 177, 178), versions);
        }
    }

    @Test
    void 採番証憑候補の境界DDLを持つ() throws Exception {
        String migration = Files.readString(MIGRATION_ROOT.resolve("V162__nf02_nf03_boundary_repair.sql"),
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
    void V164以降のtenantと再試行境界DDLを固定する() throws Exception {
        String v164 = read("V164__certification_master_optimistic_lock.sql");
        String v165 = read("V165__approval_tenant_isolation.sql");
        String v166 = read("V166__task_notification_tenant_retry_state.sql");
        String v167 = read("V167__expense_accounting_job_tenant_scope.sql");
        String v168 = read("V168__notification_outbox_tenant_scope.sql");
        String v169 = read("V169__nf02_nf03_explicit_customer_engineer_ownership.sql");
        String v170 = read("V170__nf02_nf03_ownership_repair_operations.sql");
        String v171 = read("V171__nf03_bp_availability_tenant_scope.sql");
        String v172 = read("V172__nf02_nf03_engineer_account_link_tenant_repair.sql");
        String v173 = read("V173__nf03_ai_recommendation_tenant_records.sql");
        String v174 = read("V174__nf02_nf03_ownership_repair_authority_cas.sql");
        String v175 = read("V175__nf02_nf03_resume_candidate_skill_cas.sql");
        String v176 = read("V176__nf02_nf03_project_candidate_tenant_evidence.sql");
        String v177 = read("V177__contract_tenant_sla_notification_boundary.sql");
        String v178 = read("V178__nf02_nf03_contract_reference_ownership_repair.sql");

        assertTrue(v164.contains("ALTER TABLE m_certification"));
        assertTrue(v164.contains("version INT NOT NULL DEFAULT 0"));
        assertTrue(v165.contains("ALTER TABLE t_approval_request"));
        assertTrue(v165.contains("uk_approval_request_tenant_idempotency"));
        assertTrue(v165.contains("FOREIGN KEY (tenant_id, request_id)"));
        assertTrue(v166.contains("status = 'RETRY'"));
        assertTrue(v166.contains("attempt_count"));
        assertTrue(v166.contains("next_retry_at"));
        assertTrue(v166.contains("uk_task_notify_tenant_date"));
        assertFalse(v166.contains("UPDATE t_task_notification_log SET status = 'SENT' WHERE status IS NULL"));
        assertTrue(v167.contains("ALTER TABLE t_expense_accounting_job"));
        assertTrue(v167.contains("uk_expense_job_tenant_request"));
        assertTrue(v168.contains("ALTER TABLE t_notification_outbox"));
        assertTrue(v168.contains("uk_notification_outbox_tenant_dedupe"));
        assertTrue(v169.contains("ALTER TABLE m_customer"));
        assertTrue(v169.contains("ALTER TABLE t_engineer"));
        assertTrue(v169.contains("__ses_v169_check_customer_ownership"));
        assertTrue(v169.contains("__ses_v169_check_engineer_ownership"));
        assertTrue(v169.contains("nf02_nf03_ownership_repair_queue"));
        assertTrue(v169.contains("TENANT_UNRESOLVED"));
        assertTrue(v169.contains("idx_customer_tenant_population"));
        assertTrue(v169.contains("idx_engineer_tenant_population"));
        assertFalse(v169.contains("SET c.tenant_id = 'default'"));
        assertFalse(v169.contains("SET e.tenant_id = 'default'"));
        assertTrue(v170.contains("status VARCHAR(32) NOT NULL DEFAULT 'PENDING'"));
        assertTrue(v170.contains("repair_tenant_id"));
        assertTrue(v170.contains("resolution_reason"));
        assertTrue(v170.contains("evidence"));
        assertTrue(v170.contains("resolved_at"));
        assertTrue(v170.contains("resolved_by"));
        assertTrue(v170.contains("idx_nf02_nf03_repair_status_age"));
        assertTrue(v171.contains("ALTER TABLE t_bp_availability"));
        assertTrue(v171.contains("ADD COLUMN tenant_id VARCHAR(100) NULL"));
        assertTrue(v171.contains("BP_AVAILABILITY"));
        assertTrue(v171.contains("idx_bp_availability_tenant_population"));
        assertTrue(v172.contains("ENGINEER_ACCOUNT_LINK"));
        assertTrue(v172.contains("TENANT_UNRESOLVED"));
        assertTrue(v172.contains("idx_engineer_account_link_tenant_owner"));
        assertTrue(v173.contains("t_ai_recommendation_item"));
        assertTrue(v173.contains("t_ai_feedback"));
        assertTrue(v173.contains("t_ai_outcome"));
        assertTrue(v173.contains("__ses_check_v173_ai_tenant_records"));
        assertTrue(v173.contains("fk_ai_item_run_tenant"));
        assertTrue(v173.contains("fk_ai_feedback_item_tenant"));
        assertTrue(v173.contains("fk_ai_outcome_item_tenant"));
        assertFalse(v173.contains("tenant_id = 'default'"));
        assertTrue(v174.contains("claim_token"));
        assertTrue(v174.contains("evidence_hash"));
        assertTrue(v174.contains("incident_id"));
        assertTrue(v174.contains("idx_nf02_nf03_repair_claim"));
        assertTrue(v175.contains("ALTER TABLE t_resume_ingestion"));
        assertTrue(v175.contains("tenant_id VARCHAR(100) NULL"));
        assertTrue(v175.contains("MODIFY COLUMN stored_file_name VARCHAR(120) NULL"));
        assertTrue(v175.contains("idx_resume_ingestion_tenant_status_file"));
        assertTrue(v175.contains("ALTER TABLE t_candidate"));
        assertTrue(v175.contains("ALTER TABLE t_project"));
        assertTrue(v175.contains("version INT NOT NULL DEFAULT 0"));
        assertTrue(v175.contains("UPDATE t_resume_ingestion r"));
        assertFalse(v175.contains("tenant_id = 'default'"));
        assertTrue(v176.contains("ALTER TABLE t_project_ingestion"));
        assertTrue(v176.contains("tenant_id VARCHAR(100) NULL"));
        assertTrue(v176.contains("version INT NOT NULL DEFAULT 0"));
        assertTrue(v176.contains("idx_project_ingestion_tenant_status"));
        assertTrue(v176.contains("TENANT_EVIDENCE_CONFLICT"));
        assertTrue(v176.contains("conflicting_tenant_id"));
        assertTrue(v176.contains("evidence_hash"));
        assertTrue(v176.contains("SHA2"));
        assertTrue(v176.contains("t_candidate c"));
        assertTrue(v176.contains("t_resume_ingestion r"));
        assertFalse(v176.contains("tenant_id = 'default'"));
        assertTrue(v177.contains("ALTER TABLE t_contract"));
        assertTrue(v177.contains("tenant_id VARCHAR(100) NULL"));
        assertTrue(v177.contains("JOIN m_customer mc"));
        assertTrue(v177.contains("TENANT_UNRESOLVED"));
        assertTrue(v177.contains("idx_contract_tenant_customer_status_sales"));
        assertFalse(v177.contains("SET c.tenant_id = 'default'"));
        assertTrue(v178.contains("t_contract"));
        assertTrue(v178.contains("m_customer"));
        assertTrue(v178.contains("t_engineer"));
        assertTrue(v178.contains("t_project"));
        assertTrue(v178.contains("sys_user"));
        assertTrue(v178.contains("t_engineer_account_link"));
        assertTrue(v178.contains("t_user_organization"));
        assertTrue(v178.contains("nf02_nf03_ownership_repair_queue"));
        assertTrue(v178.contains("evidence_hash"));
        assertTrue(v178.contains("SHA2"));
        assertTrue(v178.contains("TENANT_REFERENCE_MISMATCH"));
        assertFalse(v178.contains("'default'"));
    }

    private String read(String name) throws Exception {
        return Files.readString(MIGRATION_ROOT.resolve(name), StandardCharsets.UTF_8);
    }
}
