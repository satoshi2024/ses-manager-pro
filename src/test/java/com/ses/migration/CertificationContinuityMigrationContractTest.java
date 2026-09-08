package com.ses.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NF-03 continuity group migrationの衝突検知・複合FK・不変条件を静的に固定する。 */
class CertificationContinuityMigrationContractTest {

    @Test
    void migrationは履歴をINSERT_IGNOREで捨てずcollisionをSIGNALする() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V154__certification_continuity_group.sql"),
                StandardCharsets.UTF_8);

        assertFalse(migration.toUpperCase(java.util.Locale.ROOT).contains("INSERT IGNORE"));
        assertTrue(migration.contains("SIGNAL SQLSTATE '45000'"));
        assertTrue(migration.contains("COUNT(DISTINCT tenant_id, engineer_id, certification_id) > 1"));
        assertTrue(migration.contains("FOREIGN KEY (tenant_id, engineer_id, certification_id, continuity_group_id)"));
        assertTrue(migration.contains("current_flag = 1 AND current_holder_key = continuity_group_id"));
        assertTrue(migration.contains("current_flag = 0 AND current_holder_key IS NULL"));
    }

    @Test
    void H2schemaも複合FKとcurrentHolderCheckを持つ() throws Exception {
        String schema = Files.readString(Path.of("src/test/resources/sql/schema-certification-learning-skill-gap-h2.sql"),
                StandardCharsets.UTF_8);

        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS t_certification_continuity_group"));
        assertTrue(schema.contains("fk_eng_cert_continuity_group"));
        assertTrue(schema.contains("chk_eng_cert_current_holder"));
    }
}
