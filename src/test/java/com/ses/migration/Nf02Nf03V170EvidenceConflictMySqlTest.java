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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** V169のtenant証拠衝突をV170がdefaultへ寄せずrepair queueへ送ることを実MySQLで確認する。 */
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class Nf02Nf03V170EvidenceConflictMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_v170_evidence_conflict")
            .withUsername("root")
            .withPassword("ses");

    @Test
    void createdByTenantとconvertedEngineerTenantの衝突を不可視化する() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String creatorTenant = "creator-" + suffix;
        String engineerTenant = "engineer-" + suffix;
        long creatorId;
        long engineerId;
        long candidateId;
        long resumeId;

        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").target("169").load().migrate();

        try (Connection connection = MYSQL.createConnection("")) {
            connection.setAutoCommit(false);
            try (PreparedStatement user = connection.prepareStatement(
                    "INSERT INTO sys_user (username,password,real_name,role,tenant_id,status,deleted_flag) "
                            + "VALUES (?, 'pw', '証拠テスト', 'HR', ?, 1, 0)", Statement.RETURN_GENERATED_KEYS)) {
                user.setString(1, "v170-" + suffix);
                user.setString(2, creatorTenant);
                user.executeUpdate();
                try (ResultSet keys = user.getGeneratedKeys()) {
                    keys.next();
                    creatorId = keys.getLong(1);
                }
            }
            try (PreparedStatement engineer = connection.prepareStatement(
                    "INSERT INTO t_engineer (full_name,employment_type,status,tenant_id,created_by,deleted_flag) "
                            + "VALUES ('衝突要員','正社員','Bench',?, ?, 0)", Statement.RETURN_GENERATED_KEYS)) {
                engineer.setString(1, engineerTenant);
                engineer.setLong(2, creatorId);
                engineer.executeUpdate();
                try (ResultSet keys = engineer.getGeneratedKeys()) {
                    keys.next();
                    engineerId = keys.getLong(1);
                }
            }
            try (PreparedStatement candidate = connection.prepareStatement(
                    "INSERT INTO t_candidate (name,current_stage,converted_engineer_id,created_by,tenant_id,version,deleted_flag) "
                            + "VALUES ('衝突候補','入社',?,?,?,0,0)", Statement.RETURN_GENERATED_KEYS)) {
                candidate.setLong(1, engineerId);
                candidate.setLong(2, creatorId);
                candidate.setString(3, creatorTenant);
                candidate.executeUpdate();
                try (ResultSet keys = candidate.getGeneratedKeys()) {
                    keys.next();
                    candidateId = keys.getLong(1);
                }
            }
            try (PreparedStatement resume = connection.prepareStatement(
                    "INSERT INTO t_resume_ingestion "
                            + "(original_file_name,stored_file_name,file_ext,status,converted_engineer_id,created_by,tenant_id,version,deleted_flag) "
                            + "VALUES ('衝突.pdf', ?, 'pdf', '確定済', ?, ?, ?, 0, 0)", Statement.RETURN_GENERATED_KEYS)) {
                resume.setString(1, "v170-" + suffix + ".pdf");
                resume.setLong(2, engineerId);
                resume.setLong(3, creatorId);
                resume.setString(4, creatorTenant);
                resume.executeUpdate();
                try (ResultSet keys = resume.getGeneratedKeys()) {
                    keys.next();
                    resumeId = keys.getLong(1);
                }
            }
            connection.commit();
        }

        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load().migrate();

        try (Connection connection = MYSQL.createConnection("")) {
            assertEquals(0, scalar(connection,
                    "SELECT COUNT(*) FROM t_candidate WHERE id=" + candidateId
                            + " AND tenant_id IS NOT NULL"));
            assertEquals(0, scalar(connection,
                    "SELECT COUNT(*) FROM t_resume_ingestion WHERE id=" + resumeId
                            + " AND tenant_id IS NOT NULL"));
            assertQueueConflict(connection, "CANDIDATE", candidateId, engineerTenant);
            assertQueueConflict(connection, "RESUME_INGESTION", resumeId, engineerTenant);
        }
    }

    private void assertQueueConflict(Connection connection, String type, long id, String engineerTenant)
            throws Exception {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT reason, conflicting_tenant_id, evidence, evidence_hash "
                        + "FROM nf02_nf03_ownership_repair_queue WHERE entity_type=? AND entity_id=?")) {
            query.setString(1, type);
            query.setLong(2, id);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new AssertionError(type + " repair queue row is missing");
                assertEquals("TENANT_EVIDENCE_CONFLICT", rows.getString(1));
                assertEquals(engineerTenant, rows.getString(2));
                assertNotNull(rows.getString(3));
                assertNotNull(rows.getString(4));
            }
        }
    }

    private long scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
