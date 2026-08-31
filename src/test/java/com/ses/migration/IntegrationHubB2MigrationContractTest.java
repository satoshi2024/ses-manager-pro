package com.ses.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** B2 migrationのforward-only、metadata-only、retention独立性を固定する。 */
class IntegrationHubB2MigrationContractTest {
    @Test
    void V140はinboundReplayをpayloadと独立したmetadataledgerへする() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V140__integration_hub_public_api_b2_inbound_replay.sql")) {
            if (stream == null) throw new IOException("missing V140 migration");
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

    @Test
    void V141はproviderとresourceとopaque管理referenceを固定する() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V141__integration_hub_public_api_b2_binding_refs.sql")) {
            if (stream == null) throw new IOException("missing V141 migration");
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        assertTrue(sql.contains("provider_name"));
        assertTrue(sql.contains("admin_reference"));
        assertTrue(sql.contains("primary_resource_type"));
        assertTrue(sql.contains("primary_resource_id"));
        assertTrue(sql.contains("replay_reference"));
        assertTrue(sql.contains("uk_inbound_admin_reference"));
        assertTrue(sql.contains("uk_inbound_replay_reference"));
        assertTrue(sql.contains("chk_inbound_primary_resource"));
    }

    @Test
    void V142はinboundProcessingLease列とstaleRecovery索引を追加する() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V142__integration_hub_inbound_processing_lease.sql")) {
            if (stream == null) throw new IOException("missing V142 migration");
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        assertTrue(sql.contains("lease_token"));
        assertTrue(sql.contains("lease_expires_at"));
        assertTrue(sql.contains("idx_inbound_processing_lease"));
        assertTrue(sql.contains("t_inbound_event"));
        assertTrue(sql.contains("rollback evidence"));
    }
}
