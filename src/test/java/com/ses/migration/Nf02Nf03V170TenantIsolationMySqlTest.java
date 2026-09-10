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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V170後の案件取込・候補者のtenant母集団とCASを実MySQLで確認する。 */
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class Nf02Nf03V170TenantIsolationMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_v170_tenant_isolation")
            .withUsername("root")
            .withPassword("ses");

    @Test
    void 案件取込と候補者の一覧はtenant条件を要求し同一version更新は一件だけ成功する() throws Exception {
        migrate();
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String tenantA = "a-" + suffix;
        String tenantB = "b-" + suffix;
        long projectA;
        long projectB;
        long candidateA;
        try (Connection connection = MYSQL.createConnection("")) {
            projectA = insertProject(connection, tenantA, "A案件-" + suffix);
            projectB = insertProject(connection, tenantB, "B案件-" + suffix);
            candidateA = insertCandidate(connection, tenantA, "A候補-" + suffix);
            insertCandidate(connection, tenantB, "B候補-" + suffix);
        }

        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement query = connection.prepareStatement(
                     "SELECT COUNT(*) FROM t_project_ingestion WHERE tenant_id=? AND deleted_flag=0")) {
            query.setString(1, tenantA);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                assertEquals(1, rows.getInt(1));
            }
            query.setString(1, tenantB);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                assertEquals(1, rows.getInt(1));
            }
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM t_project_ingestion WHERE id=" + projectB
                    + " AND tenant_id='" + tenantA + "'"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM t_candidate WHERE id=" + candidateA
                    + " AND tenant_id='" + tenantB + "'"));
        }

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);
        List<Integer> affected = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final int worker = i;
            Thread thread = new Thread(() -> {
                try (Connection connection = MYSQL.createConnection("");
                     PreparedStatement update = connection.prepareStatement(
                             "UPDATE t_candidate SET remarks=?, version=version+1 "
                                     + "WHERE id=? AND tenant_id=? AND version=0 AND deleted_flag=0")) {
                    connection.setAutoCommit(false);
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    update.setString(1, "CAS-" + worker);
                    update.setLong(2, candidateA);
                    update.setString(3, tenantA);
                    affected.add(update.executeUpdate());
                    connection.commit();
                } catch (Throwable failure) {
                    failures.add(failure);
                } finally {
                    finished.countDown();
                }
            }, "v170-candidate-cas-" + worker);
            threads.add(thread);
            thread.start();
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        assertTrue(finished.await(15, TimeUnit.SECONDS), "CAS workerが終了していません");
        for (Thread thread : threads) {
            thread.join(1_000L);
            assertTrue(!thread.isAlive(), "CAS workerが終了していません");
        }
        assertTrue(failures.isEmpty(), failures.toString());
        assertEquals(List.of(0, 1), affected.stream().sorted().toList());
        try (Connection connection = MYSQL.createConnection("")) {
            assertEquals(1, scalar(connection, "SELECT version FROM t_candidate WHERE id=" + candidateA));
        }
    }

    private long insertProject(Connection connection, String tenantId, String name) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO t_project_ingestion (tenant_id,source_type,original_file_name,raw_text,status,deleted_flag,version) "
                        + "VALUES (?, 'PASTE', ?, 'body', '要確認', 0, 0)", Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, tenantId);
            insert.setString(2, name + ".eml");
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private long insertCandidate(Connection connection, String tenantId, String name) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO t_candidate (tenant_id,name,current_stage,deleted_flag,version) VALUES (?, ?, '応募受付', 0, 0)",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, tenantId);
            insert.setString(2, name);
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private void migrate() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load().migrate();
    }

    private long scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
