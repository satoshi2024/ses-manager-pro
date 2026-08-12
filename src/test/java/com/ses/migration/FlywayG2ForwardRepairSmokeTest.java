package com.ses.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G2-MIG-17: 同名だが誤ったpartial indexを成功扱いせず、forward repairを要求する。
 * 適用後のgit revertをrollbackと扱わないため、MySQLの実DDL境界で検証する。
 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayG2ForwardRepairSmokeTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_g2_forward_repair")
            .withUsername("root")
            .withPassword("ses");

    @Test
    void V102は失敗した同一DBをforwardRepair後に再実行できる() throws Exception {
        Flyway baseline = configureFlyway("101");
        baseline.clean();
        baseline.migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE t_compliance_responsible_assignment DROP INDEX uk_g2_assignment_active_slot");
            statement.execute("CREATE UNIQUE INDEX uk_g2_assignment_active_slot "
                    + "ON t_compliance_responsible_assignment (tenant_id, effective_from)");
        }

        FlywayException failure = assertThrows(FlywayException.class, () -> configureFlyway("102").migrate());
        assertTrue(messageChain(failure).contains("G2_V102_INDEX_SHAPE_MISMATCH"));

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            // Flywayは失敗したmigrationもsuccess=0の行として履歴に残すため、
            // 「成功として記録されていないこと」を検証する（success=1が0件）。
            assertEquals(0, queryInt(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='102' AND success=1"));
            // 誤定義indexは2列（tenant_id,effective_from）構成のためinformation_schema.statisticsは
            // 列ごとに1行ずつ計2行を返す。raw countではなく列順と一意性を明示assertする（R22-P1-04）。
            assertEquals("tenant_id,effective_from", queryString(statement,
                    "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.statistics "
                            + "WHERE table_schema=DATABASE() AND table_name='t_compliance_responsible_assignment' "
                            + "AND index_name='uk_g2_assignment_active_slot'"));
            // statisticsは列ごとに1行返すため、「同名誤定義indexが残っていること」はDISTINCT index名で検証する。
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema=DATABASE() "
                            + "AND table_name='t_compliance_responsible_assignment' "
                            + "AND index_name='uk_g2_assignment_active_slot' AND non_unique=0"));
        }

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE t_compliance_responsible_assignment DROP INDEX uk_g2_assignment_active_slot");
            statement.execute("CREATE UNIQUE INDEX uk_g2_assignment_active_slot "
                    + "ON t_compliance_responsible_assignment (tenant_id, workplace_id, active_slot)");
        }
        configureFlyway("102").repair();
        configureFlyway("102").migrate();
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='102' AND success=1"));
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema=DATABASE() "
                            + "AND table_name='m_compliance_mapping_source' "
                            + "AND constraint_name='fk_g2_source_mapping' AND constraint_type='FOREIGN KEY'"));
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() "
                            + "AND trigger_name='trg_g2_mapping_source_freeze_insert'"));
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() "
                            + "AND trigger_name='trg_g2_operation_claim_insert'"));
            assertEquals("tenant_id,workplace_id,active_slot", queryString(statement,
                    "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.statistics "
                            + "WHERE table_schema=DATABASE() AND table_name='t_compliance_responsible_assignment' "
                            + "AND index_name='uk_g2_assignment_active_slot'"));
        }
    }

    @Test
    void V102は同名誤定義CHECKを同一適用でcanonicalへrepairする() throws Exception {
        Flyway baseline = configureFlyway("101");
        baseline.clean();
        baseline.migrate();
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE t_compliance_responsible_assignment DROP CHECK chk_g2_assignment_period");
            statement.execute("ALTER TABLE t_compliance_responsible_assignment "
                    + "ADD CONSTRAINT chk_g2_assignment_period CHECK (1=1)");
        }
        configureFlyway("102").migrate();
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='102' AND success=1"));
            String checkClause;
            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS "
                            + "WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='chk_g2_assignment_period'")) {
                assertTrue(resultSet.next());
                checkClause = resultSet.getString(1);
            }
            assertTrue(checkClause.toLowerCase().contains("effective_to"));
            assertTrue(!checkClause.contains("1=1"));
        }
    }

    private Flyway configureFlyway(String target) {
        return Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .cleanDisabled(false)
                .load();
    }

    private String messageChain(Throwable failure) {
        StringBuilder message = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            message.append(current.getMessage()).append('\n');
            current = current.getCause();
        }
        return message.toString();
    }

    private int queryInt(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private String queryString(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }
}
