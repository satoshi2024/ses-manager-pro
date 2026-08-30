package com.ses.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** NF-05 B1 migrationのforward-only/secret-safe contract。 */
class IntegrationHubB1MigrationContractTest {
    @Test
    void V132はV129を上書きせずscopeとreplay監査だけを追加する() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream("/db/migration/V132__integration_hub_public_api_b1.sql")) {
            if (stream == null) throw new IOException("missing V132 migration");
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        assertTrue(sql.contains("alter table m_webhook_subscription"));
        assertTrue(sql.contains("signing_credential_version"));
        assertTrue(sql.contains("alter table t_api_delivery"));
        assertTrue(sql.contains("scope_digest"));
        assertTrue(sql.contains("create table t_api_delivery_replay_audit"));
        assertTrue(sql.contains("uk_api_delivery_replay_generation"));
        assertTrue(sql.contains("operator_ref"));
        assertTrue(sql.contains("payload_hash"));
        assertTrue(!sql.contains("raw_body") && !sql.contains("secret_plain")
                && !sql.contains("encrypted_signing_secret"));
        assertTrue(sql.contains("rollback evidence"));
    }
}
