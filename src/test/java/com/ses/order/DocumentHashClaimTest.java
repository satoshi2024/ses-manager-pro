package com.ses.order;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** tenant×document type×SHA-256のclaimが実MySQL 2txで1件だけ成功することを固定する。 */
@Testcontainers(disabledWithoutDocker = true)
class DocumentHashClaimTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_hash_claim")
            .withUsername("root")
            .withPassword("ses");

    @Test
    void 同一hashの2transactionは成功1件_duplicate1件へ直列化される() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load().migrate();
        long[] documentIds = insertDocuments();
        String hash = "b".repeat(64);
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch secondReady = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> insertClaim(documentIds[0], hash, firstInserted, releaseFirst));
            var second = executor.submit(() -> {
                assertTrue(firstInserted.await(10, TimeUnit.SECONDS));
                secondReady.countDown();
                return insertClaim(documentIds[1], hash, null, null);
            });
            assertTrue(secondReady.await(10, TimeUnit.SECONDS));
            releaseFirst.countDown();

            int successes = 0;
            int duplicates = 0;
            for (var future : java.util.List.of(first, second)) {
                String result = future.get(20, TimeUnit.SECONDS);
                if ("SUCCESS".equals(result)) successes++;
                if ("DUPLICATE".equals(result)) duplicates++;
            }
            assertEquals(1, successes);
            assertEquals(1, duplicates);
        } finally {
            executor.shutdownNow();
        }

        try (Connection connection = MYSQL.createConnection("");
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM t_document_hash_claim "
                     + "WHERE tenant_id='default' AND document_type='ORDER_RECEIVED' AND sha256='" + hash + "'")) {
            assertTrue(resultSet.next());
            assertEquals(1, resultSet.getInt(1));
        }
    }

    private String insertClaim(long documentId, String hash,
                               CountDownLatch inserted, CountDownLatch release) throws Exception {
        try (Connection connection = MYSQL.createConnection("");
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.executeUpdate("INSERT INTO t_document_hash_claim "
                        + "(tenant_id,document_type,sha256,document_id) VALUES "
                        + "('default','ORDER_RECEIVED','" + hash + "'," + documentId + ")");
                if (inserted != null) inserted.countDown();
                if (release != null) assertTrue(release.await(10, TimeUnit.SECONDS));
                connection.commit();
                return "SUCCESS";
            } catch (SQLException duplicate) {
                connection.rollback();
                return "DUPLICATE";
            }
        }
    }

    private long[] insertDocuments() throws Exception {
        try (Connection connection = MYSQL.createConnection("");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO t_document (document_type,direction,status) "
                    + "VALUES ('ORDER_RECEIVED','INCOMING','DRAFT'),('ORDER_RECEIVED','INCOMING','DRAFT')");
            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT id FROM t_document ORDER BY id DESC LIMIT 2")) {
                resultSet.next();
                long second = resultSet.getLong(1);
                resultSet.next();
                return new long[]{resultSet.getLong(1), second};
            }
        }
    }
}
