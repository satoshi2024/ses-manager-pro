package com.ses.migration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * V63 identity/security H2 schemaの直接回帰。
 * 本番MySQL方言とFK適用はFlywayMigrationSmokeTestで検証し、ここでは
 * tenant境界付きunique、recovery hash、scan metadataの永続契約を確認する。
 */
@SpringBootTest
@ActiveProfiles("test")
class EnterpriseIdentitySchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String suffix = "t015-" + System.nanoTime();

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM t_file_security_metadata WHERE stored_name LIKE ?", suffix + "%");
        jdbcTemplate.update("DELETE FROM t_mfa_recovery_code WHERE code_hash LIKE ?", suffix + "%");
        jdbcTemplate.update("DELETE FROM m_identity_provider WHERE client_id LIKE ?", suffix + "%");
    }

    @Test
    void V63の全テーブルとscan項目がH2へ投入される() {
        assertEquals(1, tableCount("m_identity_provider"));
        assertEquals(1, tableCount("t_user_external_identity"));
        assertEquals(1, tableCount("t_user_mfa"));
        assertEquals(1, tableCount("t_mfa_recovery_code"));
        assertEquals(1, tableCount("t_user_session"));
        assertEquals(1, tableCount("m_permission_group"));
        assertEquals(1, tableCount("t_user_permission_group"));
        assertEquals(1, tableCount("t_permission_group_action"));
        assertEquals(1, tableCount("t_file_security_metadata"));
        assertEquals(1, columnCount("t_file_security_metadata", "scan_status"));
        assertEquals(1, columnCount("t_file_security_metadata", "scanner_version"));
        assertEquals(1, columnCount("t_mfa_recovery_code", "code_hash"));
    }

    @Test
    void identityとfileはtenant単位で同じ外部値を許可し同一tenant重複を拒否する() {
        String issuer = "https://login.example.test/" + suffix;
        String clientA = suffix + "-a";
        String clientB = suffix + "-b";
        jdbcTemplate.update("INSERT INTO m_identity_provider "
                        + "(tenant_id, provider_type, issuer_uri, client_id) VALUES (?, 'OIDC', ?, ?)",
                "tenant-a", issuer, clientA);
        assertDoesNotThrow(() -> jdbcTemplate.update("INSERT INTO m_identity_provider "
                        + "(tenant_id, provider_type, issuer_uri, client_id) VALUES (?, 'OIDC', ?, ?)",
                "tenant-b", issuer, clientB));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("INSERT INTO m_identity_provider "
                        + "(tenant_id, provider_type, issuer_uri, client_id) VALUES (?, 'OIDC', ?, ?)",
                "tenant-a", issuer, suffix + "-duplicate"));

        String stored = suffix + "-resume.pdf";
        jdbcTemplate.update("INSERT INTO t_file_security_metadata "
                        + "(tenant_id, stored_name, file_kind) VALUES (?, ?, 'SKILL_SHEET')", "tenant-a", stored);
        assertDoesNotThrow(() -> jdbcTemplate.update("INSERT INTO t_file_security_metadata "
                        + "(tenant_id, stored_name, file_kind) VALUES (?, ?, 'SKILL_SHEET')", "tenant-b", stored));
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("INSERT INTO t_file_security_metadata "
                        + "(tenant_id, stored_name, file_kind) VALUES (?, ?, 'SKILL_SHEET')", "tenant-a", stored));
    }

    @Test
    void recoveryCodeはhashを保存し同一userの同一hashを一度しか登録できない() {
        String hash = suffix + "-" + "a".repeat(40);
        jdbcTemplate.update("INSERT INTO t_mfa_recovery_code "
                        + "(tenant_id, user_id, code_hash) VALUES ('tenant-a', 900001, ?)", hash);
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("INSERT INTO t_mfa_recovery_code "
                        + "(tenant_id, user_id, code_hash) VALUES ('tenant-a', 900001, ?)", hash));
        assertEquals(hash, jdbcTemplate.queryForObject(
                "SELECT code_hash FROM t_mfa_recovery_code WHERE code_hash = ?", String.class, hash));
    }

    private int tableCount(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                + "WHERE TABLE_SCHEMA = SCHEMA() AND TABLE_NAME = ?", Integer.class, table.toUpperCase());
    }

    private int columnCount(String table, String column) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA = SCHEMA() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, table.toUpperCase(), column.toUpperCase());
    }
}
