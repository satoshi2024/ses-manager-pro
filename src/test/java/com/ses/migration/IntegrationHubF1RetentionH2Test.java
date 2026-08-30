package com.ses.migration;

import com.ses.entity.integrationhub.ApiDelivery;
import com.ses.service.integrationhub.ApiDeliveryService;
import com.ses.service.integrationhub.ApiNonceReplayService;
import com.ses.service.integrationhub.ApiRetentionPurgeService;
import com.ses.service.integrationhub.ExternalDtoSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NF-05 F1: retention expiry/legal hold/restore epoch/nonce purgeのH2境界。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IntegrationHubF1RetentionH2Test {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApiRetentionPurgeService retentionPurgeService;

    @Autowired
    private ApiNonceReplayService nonceReplayService;

    @Autowired
    private ApiDeliveryService deliveryService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void expiredDeliveryはpurgeされactiveHold中は残り解除後に再実行できる() {
        long subscriptionId = insertSubscription("retention-client", "https://example.invalid/hook");
        long deliveryId = insertDelivery(subscriptionId, "delivery-held", "FAILED_DLQ_PAYLOAD_90D");
        long freeDeliveryId = insertDelivery(subscriptionId, "delivery-free", "FAILED_DLQ_PAYLOAD_90D");
        LocalDateTime now = LocalDateTime.of(2026, 8, 30, 12, 0);

        assertTrue(retentionPurgeService.acquireHold("DELIVERY", deliveryId, "LEGAL_HOLD", now));
        ApiRetentionPurgeService.PurgeReport held = retentionPurgeService.purgeExpired(
                "DELIVERY", "FAILED_DLQ_PAYLOAD_90D", now, 1);
        assertEquals(1, held.purged());
        assertEquals(1, jdbcTemplate.queryForObject(
                 "SELECT COUNT(*) FROM t_api_delivery WHERE id = ?", Integer.class, deliveryId));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_api_delivery WHERE id = ?", Integer.class, freeDeliveryId));

        assertTrue(retentionPurgeService.releaseHold("DELIVERY", deliveryId, now));
        ApiRetentionPurgeService.PurgeReport purged = retentionPurgeService.purgeExpired(
                "DELIVERY", "FAILED_DLQ_PAYLOAD_90D", now, 10);
        assertEquals(1, purged.purged());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_api_delivery WHERE id = ?", Integer.class, deliveryId));
        assertEquals("COMPLETE", jdbcTemplate.queryForObject(
                "SELECT run_status FROM t_api_purge_checkpoint WHERE record_kind = 'DELIVERY' "
                        + "AND retention_class = 'FAILED_DLQ_PAYLOAD_90D'", String.class));
        assertNull(jdbcTemplate.queryForObject(
                "SELECT last_record_id FROM t_api_purge_checkpoint WHERE record_kind = 'DELIVERY' "
                        + "AND retention_class = 'FAILED_DLQ_PAYLOAD_90D'", Long.class));
    }

    @Test
    void restoreEpochはcheckpointをinvalid化し同じpurgeを再実行可能にする() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 30, 12, 0);
        assertEquals(1L, retentionPurgeService.advanceRestoreEpoch("INBOUND", "SUCCEEDED_PAYLOAD_30D", now));
        assertEquals(2L, retentionPurgeService.advanceRestoreEpoch("INBOUND", "SUCCEEDED_PAYLOAD_30D", now.plusMinutes(1)));
        assertEquals(2L, jdbcTemplate.queryForObject(
                "SELECT restore_epoch FROM t_api_purge_checkpoint WHERE record_kind = 'INBOUND' "
                        + "AND retention_class = 'SUCCEEDED_PAYLOAD_30D'", Long.class));
    }

    @Test
    void nonceの期限境界をboundedPurgeできる() {
        LocalDateTime accepted = LocalDateTime.of(2026, 8, 30, 12, 0);
        assertTrue(nonceReplayService.accept("nonce-client", 1,
                "nonce-value-which-is-not-persisted".getBytes(StandardCharsets.UTF_8), accepted, accepted));
        assertEquals(1, nonceReplayService.purgeExpired(accepted.plusMinutes(5), 100));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_api_nonce_replay WHERE client_id = 'nonce-client'", Integer.class));
    }

    @Test
    void activeLease中は削除せずlease期限後にversion条件付きで削除する() {
        long subscriptionId = insertSubscription("lease-client", "https://example.invalid/lease");
        long deliveryId = insertDelivery(subscriptionId, "delivery-lease", "FAILED_DLQ_PAYLOAD_90D");
        LocalDateTime now = LocalDateTime.of(2026, 8, 30, 12, 0);
        jdbcTemplate.update("UPDATE t_api_delivery SET status = 'SUCCEEDED', lease_token = 'active-lease', "
                + "lease_expires_at = ? WHERE id = ?", now.plusMinutes(1), deliveryId);

        ApiRetentionPurgeService.PurgeReport active = retentionPurgeService.purgeExpired(
                "DELIVERY", "FAILED_DLQ_PAYLOAD_90D", now, 10);
        assertEquals(0, active.purged());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_api_delivery WHERE id = ?", Integer.class, deliveryId));

        ApiRetentionPurgeService.PurgeReport expired = retentionPurgeService.purgeExpired(
                "DELIVERY", "FAILED_DLQ_PAYLOAD_90D", now.plusMinutes(1), 10);
        assertEquals(1, expired.purged());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_api_delivery WHERE id = ?", Integer.class, deliveryId));
    }

    @Test
    void activeLeaseでkeysetの先を通過しても次回走査で期限後rowを再評価する() {
        long subscriptionId = insertSubscription("lease-cursor-client", "https://example.invalid/lease-cursor");
        long activeLeaseId = insertDelivery(subscriptionId, "delivery-cursor-active", "FAILED_DLQ_PAYLOAD_90D");
        long freeDeliveryId = insertDelivery(subscriptionId, "delivery-cursor-free", "FAILED_DLQ_PAYLOAD_90D");
        LocalDateTime now = LocalDateTime.of(2026, 8, 30, 12, 0);
        jdbcTemplate.update("UPDATE t_api_delivery SET status = 'SUCCEEDED', lease_token = 'active-lease', "
                + "lease_expires_at = ? WHERE id = ?", now.plusMinutes(1), activeLeaseId);

        ApiRetentionPurgeService.PurgeReport first = retentionPurgeService.purgeExpired(
                "DELIVERY", "FAILED_DLQ_PAYLOAD_90D", now, 1);
        assertEquals(1, first.purged());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_api_delivery WHERE id = ?", Integer.class, freeDeliveryId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_api_delivery WHERE id = ?", Integer.class, activeLeaseId));

        // eligible集合の末尾まで到達した時点でcursorをresetし、lease満了後の再評価を可能にする。
        ApiRetentionPurgeService.PurgeReport beforeExpiry = retentionPurgeService.purgeExpired(
                "DELIVERY", "FAILED_DLQ_PAYLOAD_90D", now, 1);
        assertEquals(0, beforeExpiry.purged());
        ApiRetentionPurgeService.PurgeReport afterExpiry = retentionPurgeService.purgeExpired(
                "DELIVERY", "FAILED_DLQ_PAYLOAD_90D", now.plusMinutes(1), 1);
        assertEquals(1, afterExpiry.purged());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_api_delivery WHERE id = ?", Integer.class, activeLeaseId));
    }

    @Test
    void replay監査はdelivery削除を阻害せずaudit期限で独立purgeできる() {
        long subscriptionId = insertSubscription("audit-retention-client", "https://example.invalid/audit");
        long deliveryId = insertDelivery(subscriptionId, "delivery-audit-retention", "FAILED_DLQ_PAYLOAD_90D");
        LocalDateTime now = LocalDateTime.of(2026, 8, 30, 12, 0);
        String hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        jdbcTemplate.update("INSERT INTO t_api_delivery_replay_audit "
                        + "(delivery_id, event_id, replay_generation, operator_ref, reason_code, scope_digest, payload_hash, "
                        + "retention_class, retention_expires_at, created_at) VALUES (?, ?, 2, ?, ?, ?, ?, ?, ?, ?)",
                deliveryId, "delivery-audit-retention", "operator-1", "RECOVERY", hash, hash,
                "AUDIT_METADATA_1Y", now.minusDays(1), now.minusYears(2));

        assertEquals(1, retentionPurgeService.purgeExpired(
                "DELIVERY", "FAILED_DLQ_PAYLOAD_90D", now, 10).purged());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_api_delivery_replay_audit WHERE event_id = ?", Integer.class,
                "delivery-audit-retention"));
        assertNull(jdbcTemplate.queryForObject(
                "SELECT delivery_id FROM t_api_delivery_replay_audit WHERE event_id = ?", Long.class,
                "delivery-audit-retention"));

        assertEquals(1, retentionPurgeService.purgeExpired(
                "AUDIT", "AUDIT_METADATA_1Y", now, 10).purged());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_api_delivery_replay_audit WHERE event_id = ?", Integer.class,
                "delivery-audit-retention"));
    }

    @Test
    void 業務transactionのrollbackでdelivery_enqueueも原子的に取り消される() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        LocalDateTime now = LocalDateTime.of(2026, 8, 30, 12, 0);

        assertThrows(RollbackMarker.class, () -> template.executeWithoutResult(status -> {
            long subscriptionId = insertSubscription("atomic-enqueue-client", "https://example.invalid/atomic");
            ApiDelivery delivery = deliveryService.enqueue("delivery-atomic-rollback", subscriptionId, 1,
                    "atomic-enqueue-client", "scope", "tenant-a", "event.type", "v1",
                    "correlation-atomic-000001", ExternalDtoSnapshot.of(
                            "{\"eventId\":\"delivery-atomic-rollback\",\"eventType\":\"event.type\","
                                    + "\"schemaVersion\":\"v1\",\"createdAt\":\"2026-08-30T12:00:00Z\","
                                    + "\"publicResourceId\":\"resource-1\",\"correlationId\":"
                                    + "\"correlation-atomic-000001\",\"payload\":{\"status\":\"ACTIVE\"}}"), now);
            assertTrue(delivery.getId() != null);
            throw new RollbackMarker();
        }));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_api_delivery WHERE event_id = 'delivery-atomic-rollback'", Integer.class));
    }

    private long insertSubscription(String clientId, String endpoint) {
        jdbcTemplate.update("INSERT INTO m_webhook_subscription (client_id, direction, event_type, endpoint_url, "
                + "key_id, encrypted_signing_secret, crypto_key_version, data_scope_json) VALUES "
                + "(?, 'OUTBOUND', 'event.type', ?, 'key-1', 'IHG1:v1:iv:cipher', 'v1', '{}')", clientId, endpoint);
        return jdbcTemplate.queryForObject("SELECT id FROM m_webhook_subscription WHERE client_id = ?", Long.class, clientId);
    }

    private long insertDelivery(long subscriptionId, String eventId, String retentionClass) {
        String hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        jdbcTemplate.update("INSERT INTO t_api_delivery (event_id, subscription_id, delivery_generation, client_id, "
                + "scope_code, tenant_id, event_type, schema_version, provider_idempotency_key, external_dto_snapshot, payload_hash, status, "
                + "terminal_at, retention_class, retention_expires_at) VALUES "
                + "(?, ?, 1, 'retention-client', 'scope', 'tenant-a', 'event.type', 'v1', 'provider-key', '{\"status\":\"failed\"}', ?, 'FAILED', ?, ?, ?)",
                eventId, subscriptionId, hash, LocalDateTime.of(2026, 8, 1, 12, 0), retentionClass,
                LocalDateTime.of(2026, 8, 29, 12, 0));
        return jdbcTemplate.queryForObject("SELECT id FROM t_api_delivery WHERE event_id = ?", Long.class, eventId);
    }

    private static final class RollbackMarker extends RuntimeException {
    }
}
