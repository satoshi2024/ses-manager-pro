package com.ses.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** NF-05 F2専用audit migrationの静的契約。MySQL適用はFlyway smokeで検証する。 */
class IntegrationHubF2MigrationContractTest {
    @Test
    void V130はboundedDecisionAuditを作りraw情報を作らない() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream("/db/migration/V130__integration_hub_external_api_audit.sql")) {
            if (stream == null) throw new IOException("missing V130 migration");
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        assertTrue(sql.contains("create table if not exists t_external_api_audit"));
        for (String column : new String[]{"correlation_id", "credential_version", "key_id",
                "authentication_decision", "scope_decision", "data_scope_decision",
                "command_decision", "rate_decision", "route_template", "result_code"}) {
            assertTrue(sql.contains(column), "missing audit column: " + column);
        }
        assertTrue(!sql.contains("raw_target") && !sql.contains("raw_body")
                && !sql.contains("secret_plain") && !sql.contains("source_ip"));
        assertTrue(sql.contains("pre_auth_principal = 'unauthenticated'"));
        assertTrue(sql.contains("rollback evidence"));
    }
}
