package com.ses.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V181/V182 の月次締めtenant契約と所有権修復監査経路を静的検証する。 */
class Nf02V181V182MigrationContractTest {

    @Test
    void V181はdefaultのみ写経し全tenantへbroadcastしない() throws Exception {
        String sql = Files.readString(
                Path.of("src/main/resources/db/migration/V181__nf02_monthly_closing_tenant_scope.sql"),
                StandardCharsets.UTF_8);
        assertTrue(sql.contains("t_monthly_closing"));
        assertTrue(sql.contains("UNIQUE KEY uk_monthly_closing_tenant_month"));
        assertTrue(sql.contains("'default' AS tenant_id") || sql.contains("'default'"));
        // 他tenantへの一括分配がないこと（禁止コメント以外に SET tenant_id = 他値 がない）
        assertFalse(sql.matches("(?is).*UPDATE\\s+t_monthly_closing\\s+SET\\s+tenant_id\\s*=\\s*'(?!default)[^']+'.*"));
        assertTrue(sql.contains("default tenant のみ") || sql.contains("default") && sql.contains("写経"));
    }

    @Test
    void V182はmonthlyClosingのtenantをNOT_NULL化し修復監査ビューを追加する() throws Exception {
        String sql = Files.readString(
                Path.of("src/main/resources/db/migration/V182__nf02_ownership_repair_audit_and_closing_not_null.sql"),
                StandardCharsets.UTF_8);
        assertTrue(sql.contains("MODIFY COLUMN tenant_id VARCHAR(100) NOT NULL"));
        assertTrue(sql.contains("v_nf02_nf03_ownership_repair_unresolved_audit"));
        assertTrue(sql.contains("AUDIT_ONLY_NO_CLAIM"));
        // conflicting_tenant_id を推測で埋めない
        assertFalse(sql.matches("(?s).*UPDATE\\s+nf02_nf03_ownership_repair_queue\\s+SET\\s+conflicting_tenant_id\\s*=.*"));
    }

    @Test
    void V180は1と1_0をdefaultへ正規化しV171本文を変更しない() throws Exception {
        String v180 = Files.readString(
                Path.of("src/main/resources/db/migration/V180__nf03_bp_availability_tenant_one_to_default.sql"),
                StandardCharsets.UTF_8);
        String v171 = Files.readString(
                Path.of("src/main/resources/db/migration/V171__nf03_bp_availability_tenant_scope.sql"),
                StandardCharsets.UTF_8);
        assertTrue(v180.contains("tenant_id = 'default'"));
        assertTrue(v180.contains("'1'") && v180.contains("'1.0'"));
        assertTrue(v171.contains("CAST(c.tenant_id AS CHAR)"));
    }
}
