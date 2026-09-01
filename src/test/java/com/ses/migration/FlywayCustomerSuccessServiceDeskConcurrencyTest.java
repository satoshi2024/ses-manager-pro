package com.ses.migration;

import com.ses.test.MySQLContainer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NF-02の実MySQL競合制御とスナップショット追記専用防線を検証する。
 */
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class FlywayCustomerSuccessServiceDeskConcurrencyTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_service_desk_concurrency")
            .withUsername("root")
            .withPassword("ses");

    @Test
    void MySQLで状態versionCASは一方だけ成功しイベントを一件だけ記録する() throws Exception {
        migrate();
        long requestId = insertRequest();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> affectedRows = new ArrayList<>();

        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Thread worker = new Thread(() -> {
                try (Connection connection = MYSQL.createConnection("")) {
                    connection.setAutoCommit(false);
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    int affected;
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE t_service_request SET status = 'IN_PROGRESS', version = version + 1 "
                                    + "WHERE id = ? AND status = 'RECEIVED' AND version = 0")) {
                        update.setLong(1, requestId);
                        affected = update.executeUpdate();
                    }
                    if (affected == 1) {
                        try (PreparedStatement event = connection.prepareStatement(
                                "INSERT INTO t_service_state_event "
                                        + "(service_request_id, round_no, from_status, to_status, reason, actor_type, actor_id, actor_name) "
                                        + "VALUES (?, 1, 'RECEIVED', 'IN_PROGRESS', 'concurrency-test', 'SYSTEM', 0, 'TEST')")) {
                            event.setLong(1, requestId);
                            event.executeUpdate();
                        }
                    }
                    connection.commit();
                    synchronized (affectedRows) {
                        affectedRows.add(affected);
                    }
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }, "nf02-cas-worker-" + i);
            workers.add(worker);
            worker.start();
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        for (Thread worker : workers) {
            worker.join(15_000);
            assertTrue(!worker.isAlive(), "競合workerが終了していません");
        }

        assertEquals(List.of(0, 1), affectedRows.stream().sorted().toList());
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM t_service_state_event WHERE service_request_id = " + requestId));
            assertEquals(1, queryInt(statement,
                    "SELECT version FROM t_service_request WHERE id = " + requestId));
        }
    }

    @Test
    void MySQLのhealthSnapshotはUPDATE_DELETEを拒否し版番号を保持する() throws Exception {
        migrate();
        long customerId = insertCustomer(88002L, "スナップショット防線テスト");
        insertSnapshot(customerId, 1, "hash-1", "初回算定");

        try (Connection connection = MYSQL.createConnection("")) {
            assertThrows(SQLException.class, () -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE t_customer_health_snapshot SET total_score = 1 WHERE customer_id = ?")) {
                    statement.setLong(1, customerId);
                    statement.executeUpdate();
                }
            });
        }
        try (Connection connection = MYSQL.createConnection("")) {
            assertThrows(SQLException.class, () -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM t_customer_health_snapshot WHERE customer_id = ?")) {
                    statement.setLong(1, customerId);
                    statement.executeUpdate();
                }
            });
        }

        insertSnapshot(customerId, 2, "hash-2", "訂正理由");
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            assertEquals(2, queryInt(statement,
                    "SELECT COUNT(*) FROM t_customer_health_snapshot WHERE customer_id = " + customerId));
            assertEquals(2, queryInt(statement,
                    "SELECT MAX(version_no) FROM t_customer_health_snapshot WHERE customer_id = " + customerId));
        }
    }

    @Test
    void MySQLの同一snapshot内容の並行生成は一版だけに収束する() throws Exception {
        migrate();
        long customerId = insertCustomer(88003L, "スナップショット冪等性テスト");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger inserted = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Thread worker = new Thread(() -> {
                try (Connection connection = MYSQL.createConnection("")) {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    connection.setAutoCommit(false);
                    for (int attempt = 0; attempt < 3; attempt++) {
                        try {
                            Integer latestVersion = null;
                            String latestHash = null;
                            try (PreparedStatement select = connection.prepareStatement(
                                    "SELECT version_no, snapshot_hash FROM t_customer_health_snapshot "
                                            + "WHERE customer_id = ? AND snapshot_date = '2026-08-31' "
                                            + "ORDER BY version_no DESC LIMIT 1 FOR UPDATE")) {
                                select.setLong(1, customerId);
                                try (ResultSet rows = select.executeQuery()) {
                                    if (rows.next()) {
                                        latestVersion = rows.getInt(1);
                                        latestHash = rows.getString(2);
                                    }
                                }
                            }
                            if ("same-hash".equals(latestHash)) {
                                connection.commit();
                                skipped.incrementAndGet();
                                return;
                            }
                            int nextVersion = latestVersion == null ? 1 : latestVersion + 1;
                            try (PreparedStatement insert = connection.prepareStatement(
                                    "INSERT INTO t_customer_health_snapshot "
                                            + "(customer_id, snapshot_date, version_no, health_status, total_score, "
                                            + "snapshot_hash, revision_reason) VALUES (?, '2026-08-31', ?, 'HEALTHY', 100, ?, ?)")) {
                                insert.setLong(1, customerId);
                                insert.setInt(2, nextVersion);
                                insert.setString(3, "same-hash");
                                insert.setString(4, "同一内容の並行初回算定");
                                insert.executeUpdate();
                            }
                            connection.commit();
                            inserted.incrementAndGet();
                            return;
                        } catch (SQLException retryable) {
                            connection.rollback();
                            if (!("40001".equals(retryable.getSQLState())
                                    || "23000".equals(retryable.getSQLState())) || attempt == 2) {
                                throw retryable;
                            }
                        }
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            }, "nf02-snapshot-idempotency-worker-" + i);
            workers.add(worker);
            worker.start();
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        for (Thread worker : workers) {
            worker.join(15_000);
            assertTrue(!worker.isAlive(), "snapshot workerが終了していません");
        }

        assertTrue(failures.isEmpty(), failures.toString());
        assertEquals(1, inserted.get());
        assertEquals(1, skipped.get());
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM t_customer_health_snapshot WHERE customer_id = " + customerId));
            assertEquals(1, queryInt(statement,
                    "SELECT MAX(version_no) FROM t_customer_health_snapshot WHERE customer_id = " + customerId));
        }
    }

    private static void migrate() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private static long insertRequest() throws SQLException {
        insertCustomer(88001L, "CASテスト顧客");
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO t_service_request "
                             + "(request_no, customer_id, category, priority, channel, subject, description, status, reopen_count, version) "
                             + "VALUES ('REQ-NF02-CAS-001', 88001, 'SYSTEM', 'P1', 'INTERNAL', 'CAS', 'CAS', 'RECEIVED', 0, 0)",
                     Statement.RETURN_GENERATED_KEYS)) {
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private static long insertCustomer(long id, String name) throws SQLException {
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO m_customer (id, company_name, created_at, updated_at) VALUES (?, ?, NOW(), NOW())")) {
            statement.setLong(1, id);
            statement.setString(2, name);
            statement.executeUpdate();
            return id;
        }
    }

    private static void insertSnapshot(long customerId, int version, String hash, String reason) throws SQLException {
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO t_customer_health_snapshot "
                             + "(customer_id, snapshot_date, version_no, health_status, total_score, snapshot_hash, revision_reason) "
                             + "VALUES (?, '2026-08-31', ?, 'HEALTHY', 100, ?, ?)")) {
            statement.setLong(1, customerId);
            statement.setInt(2, version);
            statement.setString(3, hash);
            statement.setString(4, reason);
            statement.executeUpdate();
        }
    }

    private static int queryInt(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next(), "クエリ結果が空です: " + sql);
            return resultSet.getInt(1);
        }
    }
}
