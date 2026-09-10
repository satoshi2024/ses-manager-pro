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
    void MySQLのCSAT一意キーは同一requestの同時回答を一件へ収束させる() throws Exception {
        migrate();
        long requestId = insertRequestWithCustomer(88004L, "CSAT同時回答顧客");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger inserted = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<Thread> workers = new ArrayList<>();

        for (int i = 0; i < 2; i++) {
            Thread worker = new Thread(() -> {
                try (Connection connection = MYSQL.createConnection("")) {
                    connection.setAutoCommit(false);
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try (PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO t_customer_csat "
                                    + "(service_request_id, customer_id, portal_user_id, score, feedback_comment) "
                                    + "VALUES (?, 88004, 200, 5, '同時回答')")) {
                        statement.setLong(1, requestId);
                        statement.executeUpdate();
                        connection.commit();
                        inserted.incrementAndGet();
                    } catch (SQLException duplicate) {
                        connection.rollback();
                        if ("23000".equals(duplicate.getSQLState())) {
                            conflicts.incrementAndGet();
                        } else {
                            throw duplicate;
                        }
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            }, "nf02-csat-worker-" + i);
            workers.add(worker);
            worker.start();
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        for (Thread worker : workers) {
            worker.join(15_000L);
            assertTrue(!worker.isAlive(), "CSAT競合workerが終了していません");
        }
        assertTrue(failures.isEmpty(), failures.toString());
        assertEquals(1, inserted.get());
        assertEquals(1, conflicts.get());
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM t_customer_csat WHERE service_request_id = " + requestId));
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
        return insertRequestWithCustomer(88001L, "CASテスト顧客");
    }

    private static long insertRequestWithCustomer(long customerId, String customerName) throws SQLException {
        insertCustomer(customerId, customerName);
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO t_service_request "
                             + "(request_no, customer_id, category, priority, channel, subject, description, status, reopen_count, version) "
                             + "VALUES (?, ?, 'SYSTEM', 'P1', 'INTERNAL', 'CAS', 'CAS', 'RECEIVED', 0, 0)",
                     Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, "REQ-NF02-CAS-" + customerId);
            statement.setLong(2, customerId);
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

    @Test
    void MySQLで20並行トランザクションによる同月リクエスト採番と一意性を検証しロールバックで汚染されないこと() throws Exception {
        migrate();
        long customerId = insertCustomer(88004L, "採番並行テスト顧客");

        String month = "202609";

        // 事前初期化（テーブル行の作成）
        try (Connection connection = MYSQL.createConnection("")) {
            try (PreparedStatement stmt = connection.prepareStatement(
                    "INSERT INTO t_service_request_sequence (sequence_month, current_val, updated_at) "
                            + "VALUES (?, 0, NOW()) ON DUPLICATE KEY UPDATE sequence_month = sequence_month")) {
                stmt.setString(1, month);
                stmt.executeUpdate();
            }
        }

        // --- 1. ロールバック非汚染検証 ---
        // トランザクション1: 番号をロック・採番し、リクエストを挿入後、意図的にROLLBACK
        try (Connection connection = MYSQL.createConnection("")) {
            connection.setAutoCommit(false);
            int seq = allocateSequence(connection, month);
            assertEquals(1, seq);
            insertServiceRequestWithNo(connection, customerId, String.format("REQ-%s-%04d", month, seq));
            connection.rollback();
        }

        // トランザクション2: 再度採番すると、ロールバックされたため再度 seq = 1 が取得され、正常にCOMMITできること
        try (Connection connection = MYSQL.createConnection("")) {
            connection.setAutoCommit(false);
            int seq = allocateSequence(connection, month);
            assertEquals(1, seq, "ロールバックされたトランザクションの採番はコミットされず、再利用されること");
            insertServiceRequestWithNo(connection, customerId, String.format("REQ-%s-%04d", month, seq));
            connection.commit();
        }

        try (Connection connection = MYSQL.createConnection(""); Statement stmt = connection.createStatement()) {
            assertEquals(1, queryInt(stmt, "SELECT COUNT(*) FROM t_service_request WHERE customer_id = " + customerId));
            assertEquals(1, queryInt(stmt, "SELECT current_val FROM t_service_request_sequence WHERE sequence_month = '" + month + "'"));
        }

        // --- 2. 20並行トランザクションによる採番・一意性検証 ---
        String testMonth = "202610";
        try (Connection connection = MYSQL.createConnection("")) {
            try (PreparedStatement stmt = connection.prepareStatement(
                    "INSERT INTO t_service_request_sequence (sequence_month, current_val, updated_at) "
                            + "VALUES (?, 0, NOW()) ON DUPLICATE KEY UPDATE sequence_month = sequence_month")) {
                stmt.setString(1, testMonth);
                stmt.executeUpdate();
            }
        }

        int concurrency = 20;
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> generatedNos = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            Thread worker = new Thread(() -> {
                try (Connection connection = MYSQL.createConnection("")) {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    connection.setAutoCommit(false);

                    int seq = allocateSequence(connection, testMonth);
                    String requestNo = String.format("REQ-%s-%04d", testMonth, seq);
                    insertServiceRequestWithNo(connection, customerId, requestNo);
                    connection.commit();

                    generatedNos.add(requestNo);
                } catch (Throwable t) {
                    errors.add(t);
                }
            }, "nf02-seq-worker-" + i);
            workers.add(worker);
            worker.start();
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        for (Thread worker : workers) {
            worker.join(15_000);
            assertTrue(!worker.isAlive(), "採番workerが終了していません");
        }

        assertTrue(errors.isEmpty(), "並行採番中にエラーが発生しました: " + errors);
        assertEquals(concurrency, generatedNos.size());

        // 全てREQ-202610-0001〜0020で重複がないことを検証
        List<String> sortedNos = generatedNos.stream().sorted().toList();
        List<String> expectedNos = new ArrayList<>();
        for (int i = 1; i <= concurrency; i++) {
            expectedNos.add(String.format("REQ-%s-%04d", testMonth, i));
        }
        assertEquals(expectedNos, sortedNos, "20並行の全採番が一意かつ連続した番号で採番されていること");

        try (Connection connection = MYSQL.createConnection(""); Statement stmt = connection.createStatement()) {
            assertEquals(concurrency, queryInt(stmt,
                    "SELECT COUNT(*) FROM t_service_request WHERE request_no LIKE 'REQ-" + testMonth + "-%'"));
            assertEquals(concurrency, queryInt(stmt,
                    "SELECT current_val FROM t_service_request_sequence WHERE sequence_month = '" + testMonth + "'"));
        }
    }

    private static int allocateSequence(Connection connection, String month) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO t_service_request_sequence (sequence_month, current_val, updated_at) "
                        + "VALUES (?, 0, NOW()) ON DUPLICATE KEY UPDATE sequence_month = sequence_month")) {
            insert.setString(1, month);
            insert.executeUpdate();
        }
        int currentVal = 0;
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT current_val FROM t_service_request_sequence WHERE sequence_month = ? FOR UPDATE")) {
            select.setString(1, month);
            try (ResultSet rs = select.executeQuery()) {
                if (rs.next()) {
                    currentVal = rs.getInt(1);
                }
            }
        }
        int nextVal = currentVal + 1;
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE t_service_request_sequence SET current_val = ?, updated_at = NOW() WHERE sequence_month = ?")) {
            update.setInt(1, nextVal);
            update.setString(2, month);
            update.executeUpdate();
        }
        return nextVal;
    }

    private static void insertServiceRequestWithNo(Connection connection, long customerId, String requestNo) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO t_service_request "
                        + "(request_no, customer_id, category, priority, channel, subject, description, status, reopen_count, version) "
                        + "VALUES (?, ?, 'SYSTEM', 'P1', 'INTERNAL', '採番テスト', '採番テスト本文', 'RECEIVED', 0, 0)")) {
            statement.setString(1, requestNo);
            statement.setLong(2, customerId);
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
