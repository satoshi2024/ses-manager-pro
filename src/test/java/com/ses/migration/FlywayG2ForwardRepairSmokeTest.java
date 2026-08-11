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
    void V102は同名誤定義indexを成功扱いせずforwardRepairを要求する() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("101")
                .load()
                .migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE t_compliance_responsible_assignment DROP INDEX uk_g2_assignment_active_slot");
            statement.execute("CREATE INDEX uk_g2_assignment_active_slot "
                    + "ON t_compliance_responsible_assignment (tenant_id, effective_from)");
        }

        FlywayException failure = assertThrows(FlywayException.class, () -> Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("102")
                .load()
                .migrate());
        assertTrue(messageChain(failure).contains("G2_V102_INDEX_SHAPE_MISMATCH"));

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            assertEquals(0, queryInt(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='102'"));
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() "
                            + "AND table_name='t_compliance_responsible_assignment' "
                            + "AND index_name='uk_g2_assignment_active_slot' AND non_unique=1"));
        }
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
}
