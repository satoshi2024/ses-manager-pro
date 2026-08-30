package com.ses.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NF-05 F1 H2 schema/init route and exact unique boundaries. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IntegrationHubF1SchemaH2Test {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void F1の全テーブルがH2初期化経路へ投入される() {
        for (String table : new String[]{
                "M_API_CLIENT", "M_API_CLIENT_SCOPE", "T_CREDENTIAL_VERSION", "T_API_IDEMPOTENCY_RECORD",
                "M_WEBHOOK_SUBSCRIPTION", "T_API_DELIVERY", "T_INBOUND_EVENT", "T_API_USAGE_BUCKET",
                "T_API_NONCE_REPLAY", "T_API_RETENTION_HOLD", "T_API_PURGE_CHECKPOINT",
                "T_API_DELIVERY_REPLAY_AUDIT"}) {
            Boolean exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) > 0 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = ?", Boolean.class, table);
            assertTrue(Boolean.TRUE.equals(exists), "missing H2 table: " + table);
        }
    }

    @Test
    void usage_keyはclient_scope_tenant_routeの四列だけで一意になる() {
        jdbcTemplate.update("INSERT INTO m_api_client (client_id, owner_ref, tenant_id, data_scope_json, allowed_cidrs) "
                + "VALUES ('h2-client', 'PROJECT_OWNER', 'tenant-a', '{}', '10.0.0.0/8')");
        jdbcTemplate.update("INSERT INTO t_api_usage_bucket (client_id, scope_code, tenant_id, route_template, "
                + "minute_window_start, day_window_start, burst_last_refill_at) VALUES "
                + "('h2-client', 'scope-a', 'tenant-a', '/route', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        assertThrows(DuplicateKeyException.class, () -> jdbcTemplate.update("INSERT INTO t_api_usage_bucket "
                + "(client_id, scope_code, tenant_id, route_template, minute_window_start, day_window_start, burst_last_refill_at) VALUES "
                + "('h2-client', 'scope-a', 'tenant-a', '/route', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"));
    }
}
