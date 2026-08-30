package com.ses.migration;

import com.ses.entity.integrationhub.ApiDelivery;
import com.ses.mapper.ApiDeliveryMapper;
import com.ses.mapper.InboundEventMapper;
import com.ses.service.integrationhub.ApiDeliveryService;
import com.ses.service.integrationhub.ApiRetentionPurgeService;
import com.ses.service.integrationhub.ApiUsageBucketService;
import com.ses.service.integrationhub.ExternalDtoSnapshot;
import com.ses.service.integrationhub.InboundEventService;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NF-05 F1: MySQL row lock、delivery CAS、active lease purge predicateの実証。 */
@Tag("mysql")
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class IntegrationHubF1MySqlConcurrencyTest {
    private static final String CLIENT_ID = "f1-mysql-concurrency-client";
    private static final String SCOPE = "integration.project.read";
    private static final String TENANT_ID = "f1-tenant";
    private static final String ROUTE = "/external-api/v1/projects";
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 30, 12, 0);

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_f1_concurrency")
            .withUsername("root")
            .withPassword("ses");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Autowired
    private ApiUsageBucketService usageBucketService;
    @Autowired
    private ApiDeliveryService deliveryService;
    @Autowired
    private ApiDeliveryMapper deliveryMapper;
    @Autowired
    private ApiRetentionPurgeService retentionPurgeService;
    @Autowired
    private InboundEventService inboundEventService;
    @Autowired
    private InboundEventMapper inboundEventMapper;

    @AfterEach
    void cleanup() throws Exception {
        try (Connection connection = MYSQL.createConnection("")) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM t_api_delivery_replay_audit WHERE event_id LIKE 'f1-%'");
                statement.executeUpdate("DELETE FROM t_api_delivery WHERE client_id = '" + CLIENT_ID + "'");
                statement.executeUpdate("DELETE FROM m_webhook_subscription WHERE client_id = '" + CLIENT_ID + "'");
                statement.executeUpdate("DELETE FROM t_api_usage_bucket WHERE client_id = '" + CLIENT_ID + "'");
                statement.executeUpdate("DELETE FROM t_inbound_event WHERE client_id = '" + CLIENT_ID + "'");
                statement.executeUpdate("DELETE FROM t_api_retention_hold");
                statement.executeUpdate("DELETE FROM t_api_purge_checkpoint");
            }
        }
    }

    @Test
    void usageBucketは複数connectionの同時incrementを一つのrowへ直列化する() throws Exception {
        int workers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return usageBucketService.consumeAt(CLIENT_ID, SCOPE, TENANT_ID, ROUTE, NOW).allowed() ? 1 : 0;
            }));
        }
        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        for (java.util.concurrent.Future<Integer> future : futures) {
            assertEquals(1, future.get(30, TimeUnit.SECONDS));
        }
        executor.shutdownNow();

        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT minute_count FROM t_api_usage_bucket WHERE client_id = ?")) {
            statement.setString(1, CLIENT_ID);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                assertEquals(workers, rs.getInt(1));
            }
        }
    }

    @Test
    void deliveryCASはproviderKey_payloadHash_version_lease_generationを同時に要求する() throws Exception {
        long subscriptionId;
        try (Connection connection = MYSQL.createConnection("")) {
            subscriptionId = insertSubscription(connection);
        }

        ApiDelivery enqueued = deliveryService.enqueue("f1-delivery-cas", subscriptionId, 1, CLIENT_ID, SCOPE,
                TENANT_ID, "resource.changed", "v1", "correlation-f1-000001",
                ExternalDtoSnapshot.of(outboundSnapshot("f1-delivery-cas", "correlation-f1-000001")), NOW);
        ApiDelivery claimed = deliveryService.claim(enqueued.getId(), "lease-1", NOW, NOW.plusMinutes(5));
        assertEquals("CLAIMED", claimed.getStatus());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return deliveryService.markSucceeded(claimed.getId(), claimed.getVersion(),
                        claimed.getDeliveryGeneration(), claimed.getLeaseToken(), claimed.getProviderIdempotencyKey(),
                        claimed.getPayloadHash(), "provider-request-1", NOW);
            }));
        }
        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        int succeeded = 0;
        for (java.util.concurrent.Future<Boolean> future : futures) {
            if (future.get(30, TimeUnit.SECONDS)) {
                succeeded++;
            }
        }
        executor.shutdownNow();
        assertEquals(1, succeeded, "同じlease/version/generationのresult CASは一つだけ成功すること");
        assertEquals("SUCCEEDED", deliveryMapper.selectById(enqueued.getId()).getStatus());
    }

    @Test
    void timeout_5xx_attempt8とprovider成功直後crashは実DBのretry_DLQ_recoveryへ収束する() throws Exception {
        long subscriptionId;
        try (Connection connection = MYSQL.createConnection("")) {
            subscriptionId = insertSubscription(connection);
        }

        ApiDelivery timeout = deliveryService.enqueue("f1-delivery-timeout", subscriptionId, 1, CLIENT_ID, SCOPE,
                TENANT_ID, "resource.changed", "v1", "correlation-timeout-000001",
                ExternalDtoSnapshot.of(outboundSnapshot("f1-delivery-timeout", "correlation-timeout-000001")), NOW);
        ApiDelivery timeoutClaimed = deliveryService.claim(timeout.getId(), "lease-timeout", NOW,
                NOW.plusMinutes(5));
        assertTrue(deliveryService.markRetryable(timeoutClaimed.getId(), timeoutClaimed.getVersion(),
                timeoutClaimed.getDeliveryGeneration(), timeoutClaimed.getLeaseToken(),
                timeoutClaimed.getProviderIdempotencyKey(), timeoutClaimed.getPayloadHash(), "TRANSPORT_ERROR",
                NOW, NOW.plusSeconds(10)));
        assertEquals("RETRYABLE", deliveryMapper.selectById(timeout.getId()).getStatus());

        ApiDelivery fiveHundred = deliveryService.enqueue("f1-delivery-5xx", subscriptionId, 1, CLIENT_ID, SCOPE,
                TENANT_ID, "resource.changed", "v1", "correlation-5xx-000001",
                ExternalDtoSnapshot.of(outboundSnapshot("f1-delivery-5xx", "correlation-5xx-000001")), NOW);
        ApiDelivery fiveHundredClaimed = deliveryService.claim(fiveHundred.getId(), "lease-5xx", NOW,
                NOW.plusMinutes(5));
        assertTrue(deliveryService.markRetryable(fiveHundredClaimed.getId(), fiveHundredClaimed.getVersion(),
                fiveHundredClaimed.getDeliveryGeneration(), fiveHundredClaimed.getLeaseToken(),
                fiveHundredClaimed.getProviderIdempotencyKey(), fiveHundredClaimed.getPayloadHash(), "HTTP_5XX",
                NOW, NOW.plusSeconds(20)));
        assertEquals("HTTP_5XX", deliveryMapper.selectById(fiveHundred.getId()).getLastErrorCode());

        ApiDelivery attemptEight = deliveryService.enqueue("f1-delivery-attempt-8", subscriptionId, 1, CLIENT_ID, SCOPE,
                TENANT_ID, "resource.changed", "v1", "correlation-attempt-000001",
                ExternalDtoSnapshot.of(outboundSnapshot("f1-delivery-attempt-8", "correlation-attempt-000001")), NOW);
        ApiDelivery attemptEightClaimed = deliveryService.claim(attemptEight.getId(), "lease-attempt-8", NOW,
                NOW.plusMinutes(5));
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE t_api_delivery SET attempt_count = 8 WHERE id = ?")) {
            statement.setLong(1, attemptEight.getId());
            statement.executeUpdate();
        }
        assertTrue(deliveryService.markRetryable(attemptEightClaimed.getId(), attemptEightClaimed.getVersion(),
                attemptEightClaimed.getDeliveryGeneration(), attemptEightClaimed.getLeaseToken(),
                attemptEightClaimed.getProviderIdempotencyKey(), attemptEightClaimed.getPayloadHash(), "HTTP_5XX",
                NOW, NOW.plusSeconds(20)));
        assertEquals("DLQ", deliveryMapper.selectById(attemptEight.getId()).getStatus());

        ApiDelivery providerAccepted = deliveryService.enqueue("f1-delivery-provider-crash", subscriptionId, 1,
                CLIENT_ID, SCOPE, TENANT_ID, "resource.changed", "v1", "correlation-crash-000001",
                ExternalDtoSnapshot.of(outboundSnapshot("f1-delivery-provider-crash", "correlation-crash-000001")), NOW);
        ApiDelivery providerClaimed = deliveryService.claim(providerAccepted.getId(), "lease-provider-crash", NOW,
                NOW.plusMinutes(5));
        // provider側では同一Idempotency-Keyを受理した直後にworkerがcrashした想定。
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE t_api_delivery SET lease_expires_at = ? WHERE id = ?")) {
            statement.setObject(1, NOW.minusSeconds(1));
            statement.setLong(2, providerAccepted.getId());
            statement.executeUpdate();
        }
        assertEquals(1, deliveryService.recoverExpiredLeases(NOW));
        ApiDelivery recovered = deliveryMapper.selectById(providerAccepted.getId());
        assertEquals("RETRYABLE", recovered.getStatus());
        assertEquals(providerClaimed.getProviderIdempotencyKey(), recovered.getProviderIdempotencyKey());
    }

    @Test
    void concurrentClaimは同一rowを一workerだけ取得する() throws Exception {
        long subscriptionId;
        try (Connection connection = MYSQL.createConnection("")) {
            subscriptionId = insertSubscription(connection);
        }
        ApiDelivery delivery = deliveryService.enqueue("f1-delivery-claim-race", subscriptionId, 1, CLIENT_ID, SCOPE,
                TENANT_ID, "resource.changed", "v1", "correlation-claim-000001",
                ExternalDtoSnapshot.of(outboundSnapshot("f1-delivery-claim-race", "correlation-claim-000001")), NOW);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<ApiDelivery>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            int worker = i;
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return deliveryService.claim(delivery.getId(), "lease-race-" + worker, NOW, NOW.plusMinutes(5));
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        int claimed = 0;
        for (var future : futures) {
            if (future.get(30, TimeUnit.SECONDS) != null) {
                claimed++;
            }
        }
        executor.shutdownNow();
        assertEquals(1, claimed, "同一deliveryのclaimは一workerだけ成功すること");
    }

    @Test
    void replay監査はdeliveryの90日purge後もauditの1年期限まで残る() throws Exception {
        long subscriptionId;
        try (Connection connection = MYSQL.createConnection("")) {
            subscriptionId = insertSubscription(connection);
        }
        long deliveryId;
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO t_api_delivery (event_id, subscription_id, delivery_generation, client_id, "
                            + "scope_code, tenant_id, scope_digest, event_type, schema_version, provider_idempotency_key, "
                            + "external_dto_snapshot, payload_hash, status, terminal_at, retention_class, "
                            + "retention_expires_at) VALUES ('f1-delivery-replay-retention', ?, 1, ?, ?, ?, "
                            + "?, 'resource.changed', 'v1', ?, '{\"status\":\"ok\"}', ?, 'DLQ', ?, "
                            + "'FAILED_DLQ_PAYLOAD_90D', ?)")) {
            insert.setLong(1, subscriptionId);
            insert.setString(2, CLIENT_ID);
            insert.setString(3, SCOPE);
            insert.setString(4, TENANT_ID);
            insert.setString(5, HASH);
            insert.setString(6, HASH);
            insert.setString(7, HASH);
            insert.setObject(8, NOW.minusDays(100));
            insert.setObject(9, NOW.minusDays(1));
            insert.executeUpdate();
        }
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id FROM t_api_delivery WHERE event_id = 'f1-delivery-replay-retention'")) {
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                deliveryId = rs.getLong(1);
            }
        }
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO t_api_delivery_replay_audit (delivery_id, event_id, replay_generation, "
                             + "operator_ref, reason_code, scope_digest, payload_hash, retention_class, "
                             + "retention_expires_at, created_at) VALUES (?, 'f1-delivery-replay-retention', 2, "
                             + "'operator-1', 'RECOVERY', ?, ?, 'AUDIT_METADATA_1Y', ?, ?)")) {
            insert.setLong(1, deliveryId);
            insert.setString(2, HASH);
            insert.setString(3, HASH);
            insert.setObject(4, NOW.minusDays(1));
            insert.setObject(5, NOW.minusYears(2));
            insert.executeUpdate();
        }

        assertEquals(1, retentionPurgeService.purgeExpired(
                "DELIVERY", "FAILED_DLQ_PAYLOAD_90D", NOW, 10).purged());
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM t_api_delivery_replay_audit WHERE event_id = 'f1-delivery-replay-retention'")) {
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
        }
        assertEquals(1, retentionPurgeService.purgeExpired(
                "AUDIT", "AUDIT_METADATA_1Y", NOW, 10).purged());
    }

    @Test
    void holdとpurgeの同時処理は共通lock順序でdeadlockせずどちらか一つへ収束する() throws Exception {
        long subscriptionId;
        try (Connection connection = MYSQL.createConnection("")) {
            subscriptionId = insertSubscription(connection);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO t_api_delivery (event_id, subscription_id, delivery_generation, client_id, "
                            + "scope_code, tenant_id, scope_digest, event_type, schema_version, provider_idempotency_key, "
                            + "external_dto_snapshot, payload_hash, status, lease_token, lease_expires_at, "
                            + "terminal_at, retention_class, retention_expires_at) VALUES "
                            + "('f1-delivery-hold-race', ?, 1, ?, ?, ?, ?, 'resource.changed', 'v1', ?, '{\"status\":\"ok\"}', ?, 'SUCCEEDED', ?, ?, ?, 'SUCCEEDED_PAYLOAD_30D', ?)")) {
                insert.setLong(1, subscriptionId);
                insert.setString(2, CLIENT_ID);
                insert.setString(3, SCOPE);
                insert.setString(4, TENANT_ID);
                insert.setString(5, HASH);
                insert.setString(6, HASH);
                insert.setString(7, HASH);
                insert.setObject(8, null);
                insert.setObject(9, null);
                insert.setObject(10, NOW.minusDays(1));
                insert.setObject(11, NOW.minusSeconds(1));
                insert.executeUpdate();
            }
        }

        long deliveryId;
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id FROM t_api_delivery WHERE event_id = 'f1-delivery-hold-race'")) {
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                deliveryId = rs.getLong(1);
            }
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var holdFuture = executor.submit(() -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            return retentionPurgeService.acquireHold("DELIVERY", deliveryId, "MYSQL_RACE", NOW);
        });
        var purgeFuture = executor.submit(() -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            return retentionPurgeService.purgeExpired("DELIVERY", "SUCCEEDED_PAYLOAD_30D", NOW, 10);
        });
        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        boolean held = holdFuture.get(30, TimeUnit.SECONDS);
        ApiRetentionPurgeService.PurgeReport report = purgeFuture.get(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        long remaining;
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM t_api_delivery WHERE id = ?")) {
            statement.setLong(1, deliveryId);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                remaining = rs.getLong(1);
            }
        }
        assertTrue((held && remaining == 1 && report.purged() == 0)
                || (!held && remaining == 0 && report.purged() == 1),
                "hold/purgeの勝者とDB状態が一致すること");
    }

    @Test
    void activeLeaseまたは期限欠落rowはpurgeせず期限後にCAS削除する() throws Exception {
        long subscriptionId;
        try (Connection connection = MYSQL.createConnection("")) {
            subscriptionId = insertSubscription(connection);
        }
        ApiDelivery delivery = deliveryService.enqueue("f1-delivery-lease", subscriptionId, 1, CLIENT_ID, SCOPE,
                TENANT_ID, "resource.changed", "v1", "correlation-lease-000001",
                ExternalDtoSnapshot.of(outboundSnapshot("f1-delivery-lease", "correlation-lease-000001")), NOW);
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE t_api_delivery SET status = 'SUCCEEDED', terminal_at = ?, "
                             + "retention_class = 'SUCCEEDED_PAYLOAD_30D', retention_expires_at = ?, "
                             + "lease_token = 'active-lease', lease_expires_at = ? WHERE id = ?")) {
            statement.setObject(1, NOW.minusDays(1));
            statement.setObject(2, NOW.minusSeconds(1));
            statement.setObject(3, null);
            statement.setLong(4, delivery.getId());
            statement.executeUpdate();
        }
        assertEquals(0, retentionPurgeService.purgeExpired(
                "DELIVERY", "SUCCEEDED_PAYLOAD_30D", NOW, 10).purged());
        assertNotNull(deliveryMapper.selectById(delivery.getId()));

        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE t_api_delivery SET lease_expires_at = ? WHERE id = ?")) {
            statement.setObject(1, NOW.plusMinutes(1));
            statement.setLong(2, delivery.getId());
            statement.executeUpdate();
        }
        assertEquals(0, retentionPurgeService.purgeExpired(
                "DELIVERY", "SUCCEEDED_PAYLOAD_30D", NOW, 10).purged());

        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE t_api_delivery SET lease_expires_at = ? WHERE id = ?")) {
            statement.setObject(1, NOW);
            statement.setLong(2, delivery.getId());
            statement.executeUpdate();
        }
        assertEquals(1, retentionPurgeService.purgeExpired(
                "DELIVERY", "SUCCEEDED_PAYLOAD_30D", NOW, 10).purged());
        assertNull(deliveryMapper.selectById(delivery.getId()));
    }

    @Test
    void inboundDuplicateKeyのhash競合は実serviceでCONFLICTへ永続化する() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        var first = executor.submit(() -> recordInbound(HASH, ready, start));
        var second = executor.submit(() -> recordInbound(
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", ready, start));
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        var firstReceipt = first.get(30, TimeUnit.SECONDS);
        var secondReceipt = second.get(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertTrue(firstReceipt.conflict() || secondReceipt.conflict());
        assertEquals("CONFLICT", inboundEventMapper.selectByProviderEvent(
                CLIENT_ID, "provider-f1", "provider-event-race").getStatus());
    }

    private InboundEventService.Receipt recordInbound(String hash, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        return inboundEventService.recordReceived(CLIENT_ID, "provider-f1", "provider-event-race", hash,
                NOW, ExternalDtoSnapshot.ofAllowList("{\"eventType\":\"resource.changed\"}",
                        ExternalDtoSnapshot.INBOUND_FIELDS), true, NOW);
    }

    private long insertSubscription(Connection connection) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO m_webhook_subscription (client_id, direction, event_type, endpoint_url, key_id, "
                        + "encrypted_signing_secret, crypto_key_version, data_scope_json) VALUES (?, 'OUTBOUND', "
                        + "'resource.changed', ?, 'key-1', 'IHG1:v1:iv:cipher', 'v1', '{}')",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, CLIENT_ID);
            insert.setString(2, "https://example.invalid/f1");
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private String outboundSnapshot(String eventId, String correlationId) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"resource.changed\","
                + "\"schemaVersion\":\"v1\",\"createdAt\":\"2026-08-30T12:00:00Z\","
                + "\"publicResourceId\":\"resource-1\",\"correlationId\":\"" + correlationId
                + "\",\"payload\":{\"status\":\"ACTIVE\"}}";
    }
}
