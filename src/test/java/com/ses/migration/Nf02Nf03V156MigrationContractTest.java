package com.ses.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NF-02/NF-03の最終migration番号・FK・index・境界DDLを固定する。 */
class Nf02Nf03V156MigrationContractTest {

    private static final Path MIGRATION_ROOT = Path.of("src/main/resources/db/migration");

    @Test
    void V156が最新migrationの後ろにあり旧番号へ追記していない() throws Exception {
        assertTrue(Files.exists(MIGRATION_ROOT.resolve("V156__nf02_nf03_boundary_repair.sql")));
        assertFalse(Files.exists(MIGRATION_ROOT.resolve("V150__nf02_nf03_boundary_repair.sql")));
        try (var paths = Files.list(MIGRATION_ROOT)) {
            assertTrue(paths.map(path -> path.getFileName().toString())
                    .noneMatch(name -> name.startsWith("V157") || name.startsWith("V158")));
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
}
