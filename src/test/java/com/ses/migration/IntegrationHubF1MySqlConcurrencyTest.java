package com.ses.migration;

import com.ses.test.MySQLContainer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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

/** NF-05 F1: MySQL row lock、delivery CAS、active lease purge predicateの実証。 */
@Tag("mysql")
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

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @AfterEach
    void cleanup() throws Exception {
        try (Connection connection = MYSQL.createConnection("")) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM t_api_delivery WHERE client_id = '" + CLIENT_ID + "'");
                statement.executeUpdate("DELETE FROM m_webhook_subscription WHERE client_id = '" + CLIENT_ID + "'");
                statement.executeUpdate("DELETE FROM t_api_usage_bucket WHERE client_id = '" + CLIENT_ID + "'");
            }
        }
    }

    @Test
    void usageBucketは複数connectionの同時incrementを一つのrowへ直列化する() throws Exception {
        try (Connection connection = MYSQL.createConnection("")) {
            insertUsageBucket(connection);
        }

        int workers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                try (Connection connection = MYSQL.createConnection("")) {
                    connection.setAutoCommit(false);
                    int current;
                    int version;
                    try (PreparedStatement select = connection.prepareStatement(
                            "SELECT minute_count, version FROM t_api_usage_bucket "
                                    + "WHERE client_id = ? AND scope_code = ? AND tenant_id = ? "
                                    + "AND route_template = ? FOR UPDATE")) {
                        select.setString(1, CLIENT_ID);
                        select.setString(2, SCOPE);
                        select.setString(3, TENANT_ID);
                        select.setString(4, ROUTE);
                        try (ResultSet rs = select.executeQuery()) {
                            if (!rs.next()) {
                                throw new IllegalStateException("usage bucket missing");
                            }
                            current = rs.getInt(1);
                            version = rs.getInt(2);
                        }
                    }
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE t_api_usage_bucket SET minute_count = ?, version = version + 1 "
                                    + "WHERE client_id = ? AND scope_code = ? AND tenant_id = ? "
                                    + "AND route_template = ? AND version = ? AND minute_count < 60")) {
                        update.setInt(1, current + 1);
                        update.setString(2, CLIENT_ID);
                        update.setString(3, SCOPE);
                        update.setString(4, TENANT_ID);
                        update.setString(5, ROUTE);
                        update.setInt(6, version);
                        int updated = update.executeUpdate();
                        connection.commit();
                        return updated;
                    } catch (Exception e) {
                        connection.rollback();
                        throw e;
                    }
                }
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
        long deliveryId;
        String providerKey = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
        try (Connection connection = MYSQL.createConnection("")) {
            subscriptionId = insertSubscription(connection);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO t_api_delivery (event_id, subscription_id, delivery_generation, client_id, "
                            + "scope_code, tenant_id, event_type, schema_version, provider_idempotency_key, "
                            + "external_dto_snapshot, payload_hash, status, lease_token, lease_expires_at) "
                            + "VALUES ('f1-delivery-cas', ?, 1, ?, ?, ?, 'resource.changed', 'v1', ?, '{\"status\":\"ok\"}', ?, 'CLAIMED', 'lease-1', ?)")) {
                insert.setLong(1, subscriptionId);
                insert.setString(2, CLIENT_ID);
                insert.setString(3, SCOPE);
                insert.setString(4, TENANT_ID);
                insert.setString(5, providerKey);
                insert.setString(6, HASH);
                insert.setObject(7, NOW.plusMinutes(5));
                insert.executeUpdate();
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT id FROM t_api_delivery WHERE event_id = 'f1-delivery-cas'")) {
                rs.next();
                deliveryId = rs.getLong(1);
            }
        }

        String update = "UPDATE t_api_delivery SET status = 'SUCCEEDED', terminal_at = ?, "
                + "retention_class = 'SUCCEEDED_PAYLOAD_30D', retention_expires_at = ?, "
                + "lease_token = NULL, lease_expires_at = NULL, version = version + 1 "
                + "WHERE id = ? AND version = 0 AND delivery_generation = ? AND lease_token = ? AND provider_idempotency_key = ? "
                + "AND payload_hash = ? AND status = 'CLAIMED'";
        try (Connection connection = MYSQL.createConnection(""); PreparedStatement statement = connection.prepareStatement(update)) {
            statement.setObject(1, NOW);
            statement.setObject(2, NOW.plusDays(30));
            statement.setLong(3, deliveryId);
            statement.setInt(4, 2);
            statement.setString(5, "lease-1");
            statement.setString(6, providerKey);
            statement.setString(7, HASH);
            assertEquals(0, statement.executeUpdate());
        }
        try (Connection connection = MYSQL.createConnection(""); PreparedStatement statement = connection.prepareStatement(update)) {
            statement.setObject(1, NOW);
            statement.setObject(2, NOW.plusDays(30));
            statement.setLong(3, deliveryId);
            statement.setInt(4, 1);
            statement.setString(5, "lease-1");
            statement.setString(6, providerKey);
            statement.setString(7, HASH);
            assertEquals(1, statement.executeUpdate());
        }
        try (Connection connection = MYSQL.createConnection(""); PreparedStatement statement = connection.prepareStatement(update)) {
            statement.setObject(1, NOW);
            statement.setObject(2, NOW.plusDays(30));
            statement.setLong(3, deliveryId);
            statement.setInt(4, 1);
            statement.setString(5, "lease-1");
            statement.setString(6, providerKey);
            statement.setString(7, HASH);
            assertEquals(0, statement.executeUpdate());
        }
    }

    @Test
    void activeLease中のpurgeは削除せず期限後だけCAS削除する() throws Exception {
        long subscriptionId;
        try (Connection connection = MYSQL.createConnection("")) {
            subscriptionId = insertSubscription(connection);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO t_api_delivery (event_id, subscription_id, delivery_generation, client_id, "
                            + "scope_code, tenant_id, event_type, schema_version, provider_idempotency_key, "
                            + "external_dto_snapshot, payload_hash, status, lease_token, lease_expires_at, "
                            + "terminal_at, retention_class, retention_expires_at) VALUES "
                            + "('f1-delivery-lease', ?, 1, ?, ?, ?, 'resource.changed', 'v1', ?, '{\"status\":\"ok\"}', ?, 'SUCCEEDED', 'active-lease', ?, ?, 'SUCCEEDED_PAYLOAD_30D', ?)")) {
                insert.setLong(1, subscriptionId);
                insert.setString(2, CLIENT_ID);
                insert.setString(3, SCOPE);
                insert.setString(4, TENANT_ID);
                insert.setString(5, HASH);
                insert.setString(6, HASH);
                insert.setObject(7, NOW.plusMinutes(1));
                insert.setObject(8, NOW.minusDays(1));
                insert.setObject(9, NOW.minusSeconds(1));
                insert.executeUpdate();
            }
        }
        try (Connection connection = MYSQL.createConnection("")) {
            assertEquals(0, deleteExpiredDelivery(connection, NOW));
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE t_api_delivery SET lease_expires_at = '2026-08-30 12:00:00' "
                        + "WHERE event_id = 'f1-delivery-lease'");
            }
            assertEquals(1, deleteExpiredDelivery(connection, NOW));
        }
    }

    private int deleteExpiredDelivery(Connection connection, LocalDateTime now) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM t_api_delivery WHERE event_id = 'f1-delivery-lease' AND version = 0 "
                        + "AND retention_expires_at <= ? AND status IN ('SUCCEEDED', 'FAILED', 'DLQ') "
                        + "AND (lease_token IS NULL OR lease_expires_at IS NULL OR lease_expires_at <= ?)")) {
            statement.setObject(1, now);
            statement.setObject(2, now);
            return statement.executeUpdate();
        }
    }

    private void insertUsageBucket(Connection connection) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO t_api_usage_bucket (client_id, scope_code, tenant_id, route_template, "
                        + "minute_window_start, day_window_start, burst_last_refill_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, CLIENT_ID);
            insert.setString(2, SCOPE);
            insert.setString(3, TENANT_ID);
            insert.setString(4, ROUTE);
            insert.setObject(5, NOW.withSecond(0));
            insert.setObject(6, NOW.toLocalDate().atStartOfDay());
            insert.setObject(7, NOW);
            insert.executeUpdate();
        }
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
}
