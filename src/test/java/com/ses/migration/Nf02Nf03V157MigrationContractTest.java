package com.ses.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NF02/NF03 review-v2のmigration順序・tenant境界・最終一意制約を固定する契約テスト。 */
class Nf02Nf03V157MigrationContractTest {

    private static final Path ROOT = Path.of("src/main/resources/db/migration");

    @Test
    void V157は最新migrationの後に追加され既存履歴を上書きしない() throws Exception {
        Path migration = ROOT.resolve("V157__nf02_nf03_tenant_attachment_notification_boundary.sql");
        assertTrue(Files.exists(migration));
        assertFalse(Files.exists(ROOT.resolve("V150__nf02_nf03_tenant_attachment_notification_boundary.sql")));
        String sql = Files.readString(migration, StandardCharsets.UTF_8);
        assertTrue(sql.contains("uk_service_attachment_tenant_business_key"));
        assertTrue(sql.contains("ADD COLUMN business_key"));
        assertTrue(sql.contains("UPDATE t_service_attachment_link"));
        assertTrue(sql.contains("uk_notification_tenant_dedupe"));
        assertTrue(sql.contains("t_service_attachment_compensation"));
        assertTrue(sql.contains("ALTER TABLE t_notification"));
    }
}
