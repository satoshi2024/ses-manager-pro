package com.ses.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** B2 migrationのforward-only、metadata-only、retention独立性を固定する。 */
class IntegrationHubB2MigrationContractTest {
    @Test
    void V135はinboundReplayをpayloadと独立したmetadataledgerへする() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V135__integration_hub_public_api_b2_inbound_replay.sql")) {
            if (stream == null) throw new IOException("missing V135 migration");
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        assertTrue(sql.contains("create table if not exists t_inbound_event_replay"));
        assertTrue(sql.contains("inbound_event_id"));
        assertTrue(sql.contains("on delete set null"));
        assertTrue(sql.contains("replay_generation"));
        assertTrue(sql.contains("operator_ref"));
        assertTrue(sql.contains("retention_class = 'audit_metadata_1y'"));
        assertTrue(sql.contains("inbound_replay"));
        assertTrue(!sql.contains("parsed_fields_snapshot") && !sql.contains("raw_body "));
        assertTrue(sql.contains("rollback evidence"));
    }
}
