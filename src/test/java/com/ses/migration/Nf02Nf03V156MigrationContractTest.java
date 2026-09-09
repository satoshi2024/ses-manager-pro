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
    void NF02_NF03の追加migrationがV156からV162まで順序通りに存在する() throws Exception {
        List<String> expected = List.of(
                "V156__nf02_nf03_boundary_repair.sql",
                "V157__nf02_nf03_tenant_attachment_notification_boundary.sql",
                "V158__certification_master_optimistic_lock.sql",
                "V159__approval_tenant_isolation.sql",
                "V160__task_notification_tenant_retry_state.sql",
                "V161__expense_accounting_job_tenant_scope.sql",
                "V162__notification_outbox_tenant_scope.sql");
        for (String name : expected) {
            assertTrue(Files.exists(MIGRATION_ROOT.resolve(name)), name);
        }
        assertFalse(Files.exists(MIGRATION_ROOT.resolve("V150__nf02_nf03_boundary_repair.sql")));
        try (var paths = Files.list(MIGRATION_ROOT)) {
            List<Integer> versions = paths.map(path -> path.getFileName().toString())
                    .map(Pattern.compile("^V(\\d+)(__|_).*")::matcher)
                    .filter(java.util.regex.Matcher::matches)
                    .map(m -> Integer.valueOf(m.group(1)))
                    .filter(v -> v >= 156 && v <= 162)
                    .sorted()
                    .toList();
            assertEquals(List.of(156, 157, 158, 159, 160, 161, 162), versions);
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
    }

    private String read(String name) throws Exception {
        return Files.readString(MIGRATION_ROOT.resolve(name), StandardCharsets.UTF_8);
    }
}
