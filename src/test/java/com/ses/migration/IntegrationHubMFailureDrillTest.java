package com.ses.migration;

import com.ses.config.integrationhub.ExternalApiPrincipal;
import com.ses.config.integrationhub.ExternalApiPublicIdCodec;
import com.ses.entity.integrationhub.ApiDelivery;
import com.ses.service.integrationhub.ApiDeliveryService;
import com.ses.service.integrationhub.ApiRetentionPurgeService;
import com.ses.service.integrationhub.ExternalDtoSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M: worker crash/stale lease/restore epoch/recovery drillをH2実service経路で固定する。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IntegrationHubMFailureDrillTest {
    private static final String CLIENT_ID = "m-failure-client";
    private static final String TENANT = "tenant-m-failure";
    private static final long LEGAL_ENTITY = 72L;
    private static final long PROJECT_ID = 1L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 31, 15, 0);

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ApiDeliveryService deliveryService;
    @Autowired
    private ApiRetentionPurgeService retentionPurgeService;
    @Autowired
    private ExternalApiPublicIdCodec publicIdCodec;

    @Test
    void workerCrash後のstaleLeaseはrecoverExpiredLeasesでRETRYABLEへ復帰する() {
        long subscriptionId = insertSubscription();
        ApiDelivery enqueued = deliveryService.enqueue("m-failure-event", subscriptionId, 1, CLIENT_ID,
                "integration.webhook.deliver", TENANT, "project", PROJECT_ID, "resource.changed", "v1",
                "correlation-m-000001", snapshot("m-failure-event", "correlation-m-000001"), NOW);
        ApiDelivery claimed = deliveryService.claim(enqueued.getId(), "stale-lease", NOW, NOW.plusMinutes(5));
        assertEquals("CLAIMED", claimed.getStatus());

        assertEquals(0, deliveryService.recoverExpiredLeases(NOW.plusMinutes(4)));
        assertEquals(1, deliveryService.recoverExpiredLeases(NOW.plusMinutes(6)));

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM t_api_delivery WHERE id = ?", String.class, enqueued.getId());
        assertEquals("RETRYABLE", status);
    }

    @Test
    void restoreEpochはpurgeCheckpointをinvalid化し障害復旧後に再purgeできる() {
        assertEquals(1L, retentionPurgeService.advanceRestoreEpoch("DELIVERY", "FAILED_DLQ_PAYLOAD_90D", NOW));
        assertEquals(2L, retentionPurgeService.advanceRestoreEpoch("DELIVERY", "FAILED_DLQ_PAYLOAD_90D",
                NOW.plusMinutes(1)));
        assertEquals(2L, jdbcTemplate.queryForObject(
                "SELECT restore_epoch FROM t_api_purge_checkpoint WHERE record_kind = 'DELIVERY' "
                        + "AND retention_class = 'FAILED_DLQ_PAYLOAD_90D'", Long.class));
    }

    @Test
    void providerTimeout相当のRETRYABLEはattempt上限到達でDLQへ収束する() {
        long subscriptionId = insertSubscription();
        ApiDelivery enqueued = deliveryService.enqueue("m-dlq-event", subscriptionId, 1, CLIENT_ID,
                "integration.webhook.deliver", TENANT, "project", PROJECT_ID, "resource.changed", "v1",
                "correlation-m-000002", snapshot("m-dlq-event", "correlation-m-000002"), NOW);
        ApiDelivery claimed = deliveryService.claim(enqueued.getId(), "dlq-lease", NOW, NOW.plusMinutes(5));
        LocalDateTime terminalAt = NOW.plusMinutes(1);
        for (int attempt = 1; attempt <= 7; attempt++) {
            assertTrue(deliveryService.markRetryable(claimed.getId(), claimed.getVersion(),
                    claimed.getDeliveryGeneration(), claimed.getLeaseToken(),
                    claimed.getProviderIdempotencyKey(), claimed.getPayloadHash(),
                    "TRANSPORT_TIMEOUT", terminalAt, terminalAt));
            claimed = deliveryService.claim(claimed.getId(), "dlq-lease-" + attempt, terminalAt,
                    terminalAt.plusMinutes(5));
            assertTrue(claimed != null, "claim after retry must succeed at attempt " + attempt);
        }
        assertTrue(deliveryService.markRetryable(claimed.getId(), claimed.getVersion(),
                claimed.getDeliveryGeneration(), claimed.getLeaseToken(),
                claimed.getProviderIdempotencyKey(), claimed.getPayloadHash(),
                "TRANSPORT_TIMEOUT", terminalAt, terminalAt));
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM t_api_delivery WHERE id = ?", String.class, enqueued.getId());
        assertEquals("DLQ", status);
    }

    private long insertSubscription() {
        jdbcTemplate.update("DELETE FROM t_api_delivery WHERE client_id = ?", CLIENT_ID);
        jdbcTemplate.update("DELETE FROM m_webhook_subscription WHERE client_id = ?", CLIENT_ID);
        jdbcTemplate.update("DELETE FROM m_api_client WHERE client_id = ?", CLIENT_ID);
        String scope = "{\"tenantIds\":[\"" + TENANT + "\"],\"legalEntityIds\":[\"" + LEGAL_ENTITY + "\"],"
                + "\"projectIds\":[\"" + PROJECT_ID + "\"]}";
        jdbcTemplate.update("""
                INSERT INTO m_api_client (id, client_id, owner_ref, tenant_id, legal_entity_id,
                                          data_scope_json, allowed_cidrs, client_tier, status, version)
                VALUES (9910100, ?, 'PROJECT_OWNER', ?, ?, ?, '127.0.0.1/32', 'INTERNAL_TEST', 'ACTIVE', 0)
                """, CLIENT_ID, TENANT, LEGAL_ENTITY, scope);
        jdbcTemplate.update("""
                INSERT INTO m_webhook_subscription (client_id, provider_name, direction, event_type,
                                                      endpoint_url, key_id, encrypted_signing_secret,
                                                      crypto_key_version, data_scope_json, status, version)
                VALUES (?, 'provider-m', 'OUTBOUND', 'resource.changed', 'https://example.invalid/hook',
                        'm-key', 'IHG1:v1:iv:cipher', 'v1', ?, 'ACTIVE', 0)
                """, CLIENT_ID, scope);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM m_webhook_subscription WHERE client_id = ? LIMIT 1", Long.class, CLIENT_ID);
    }

    private ExternalDtoSnapshot snapshot(String eventId, String correlationId) {
        String publicProjectId = publicIdCodec.encode(principal(), "project", PROJECT_ID);
        return ExternalDtoSnapshot.of("""
                {"eventId":"%s","eventType":"resource.changed","schemaVersion":"v1",
                "createdAt":"2026-08-31T15:00:00Z","publicResourceId":"%s",
                "correlationId":"%s","payload":{"publicProjectId":"%s","status":"ACTIVE"}}
                """.formatted(eventId, publicProjectId, correlationId, publicProjectId));
    }

    private ExternalApiPrincipal principal() {
        return new ExternalApiPrincipal(CLIENT_ID, 9910100L, TENANT, LEGAL_ENTITY,
                "{\"tenantIds\":[\"" + TENANT + "\"],\"legalEntityIds\":[\"" + LEGAL_ENTITY + "\"],"
                        + "\"projectIds\":[\"" + PROJECT_ID + "\"]}",
                1, "delivery-binding", "INTERNAL_TEST");
    }
}
