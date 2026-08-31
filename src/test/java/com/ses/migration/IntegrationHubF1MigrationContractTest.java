package com.ses.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** NF-05 F1 migrationの静的contract。実MySQL適用はFlywayMigrationSmokeTestで検証する。 */
class IntegrationHubF1MigrationContractTest {
    private static final String RESOURCE = "/db/migration/V134__integration_hub_public_api_f1.sql";
    private static final List<String> TABLES = List.of(
            "m_api_client", "m_api_client_scope", "t_credential_version", "t_api_idempotency_record",
            "m_webhook_subscription", "t_api_delivery", "t_inbound_event", "t_api_usage_bucket",
            "t_api_nonce_replay", "t_api_retention_hold", "t_api_purge_checkpoint");

    @Test
    void V129は全F1テーブルと承認済み自然キーcanonical状態rollback証跡を含む() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IOException("missing migration resource: " + RESOURCE);
            }
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String table : TABLES) {
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS " + table), "missing table: " + table);
        }
        assertTrue(sql.contains("UNIQUE (client_id, scope_code, tenant_id, route_template)"));
        assertTrue(sql.contains("UNIQUE (client_id, nonce_hash)"));
        assertTrue(sql.contains("PENDING', 'CLAIMED', 'RETRYABLE', 'SUCCEEDED', 'FAILED', 'DLQ"));
        assertTrue(sql.contains("IN_PROGRESS', 'SUCCEEDED', 'FAILED', 'CONFLICT"));
        assertTrue(sql.contains("-- ROLLBACK EVIDENCE"));
        assertTrue(sql.contains("backup/restore"));
    }

    @Test
    void V129はraw_secretやraw_body列を作らない() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IOException("missing migration resource: " + RESOURCE);
            }
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        assertTrue(!sql.contains("raw_body ") && !sql.contains("raw_nonce ") && !sql.contains("secret_plain"));
        assertTrue(sql.contains("encrypted_secret"));
        assertTrue(sql.contains("external_dto_snapshot"));
        assertTrue(sql.contains("provider_idempotency_key"));
    }
}
