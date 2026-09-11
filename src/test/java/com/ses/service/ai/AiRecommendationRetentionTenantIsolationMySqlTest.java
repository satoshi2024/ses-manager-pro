package com.ses.service.ai;

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
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NF08: MySQL上のAI retention SQLは tenant_id=#{tenantId} のみを使い、他tenantへ波及しない。
 */
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class AiRecommendationRetentionTenantIsolationMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_ai_retention_iso")
            .withUsername("root")
            .withPassword("ses");

    @Test
    void 双tenant過期データは指定tenantだけpurgeされる() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = MYSQL.createConnection("");
             Statement statement = connection.createStatement()) {
            long versionId = queryLong(statement,
                    "SELECT id FROM m_ai_artifact_version WHERE use_case='MATCHING' AND status='ACTIVE' LIMIT 1");
            long runA = insertExpired(connection, "tenant-a", versionId);
            long runB = insertExpired(connection, "tenant-b", versionId);

            // AiRecommendationRunMapper.purgeExpiredSummaries と同じSQL（tenant必須）
            try (PreparedStatement ps = connection.prepareStatement("""
                    UPDATE t_ai_recommendation_run SET redacted_summary_json = NULL, updated_at = ?
                    WHERE id IN (SELECT id FROM (SELECT id FROM t_ai_recommendation_run
                    WHERE created_at < ? AND redacted_summary_json IS NOT NULL AND deleted_flag = 0
                    AND tenant_id = ?
                    ORDER BY created_at, id LIMIT ?) candidates)
                    """)) {
                LocalDateTime now = LocalDateTime.of(2026, 9, 11, 0, 0);
                ps.setTimestamp(1, Timestamp.valueOf(now));
                ps.setTimestamp(2, Timestamp.valueOf(now.minusDays(730)));
                ps.setString(3, "tenant-a");
                ps.setInt(4, 100);
                assertTrue(ps.executeUpdate() >= 1);
            }

            assertNull(queryString(statement,
                    "SELECT redacted_summary_json FROM t_ai_recommendation_run WHERE id=" + runA));
            assertNotNull(queryString(statement,
                    "SELECT redacted_summary_json FROM t_ai_recommendation_run WHERE id=" + runB));

            // tenant_id 列幅は V162 で VARCHAR(100) へ揃済み（V182不要）
            assertEquals(100, queryLong(statement, """
                    SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns
                    WHERE table_schema=DATABASE() AND table_name='t_ai_recommendation_run'
                      AND column_name='tenant_id'
                    """));
        }
    }

    private static long insertExpired(Connection connection, String tenantId, long versionId) throws SQLException {
        String traceId = ("ret-iso-" + UUID.randomUUID()).substring(0, 36);
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO t_ai_recommendation_run
                (tenant_id, trace_id, use_case, artifact_version_id, input_hash,
                 redacted_summary_json, status, status_version, created_at)
                VALUES (?, ?, 'MATCHING', ?,
                '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
                '{"ok":true}', 'SUCCEEDED', 0, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, tenantId);
            ps.setString(2, traceId);
            ps.setLong(3, versionId);
            ps.setTimestamp(4, Timestamp.valueOf(LocalDateTime.of(2020, 1, 1, 0, 0)));
            assertEquals(1, ps.executeUpdate());
            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private static long queryLong(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private static String queryString(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }
}
