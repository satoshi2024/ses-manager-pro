package com.ses.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import com.ses.test.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** T103のMySQL smoke。V107でJP PINT関連テーブルが実MySQLで構築できることを検証する。 */
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class FlywayJpPintDigitalInvoiceSchemaSmokeTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_jppint_v107")
            .withUsername("root")
            .withPassword("ses");

    @Test
    void V107のJpPintテーブルがMySQLで構築できる() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("107")
                .load()
                .migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            for (String table : new String[]{
                    "t_peppol_participant", "t_digital_invoice", "t_digital_invoice_event"}) {
                assertTableExists(statement, table);
            }
            assertColumnExists(statement, "m_customer", "delivery_preference");
            assertIndexExists(statement, "t_peppol_participant", "uk_peppol_participant_owner");
            assertIndexExists(statement, "t_digital_invoice", "uk_digital_invoice_message");
            assertIndexExists(statement, "t_digital_invoice_event", "uk_digital_invoice_event_provider");
        }
    }

    private void assertTableExists(Statement statement, String table) throws Exception {
        assertTrue(queryInt(statement, "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name='" + table + "'") == 1,
                table + "が存在するはず");
    }

    private void assertColumnExists(Statement statement, String table, String column) throws Exception {
        assertTrue(queryInt(statement, "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='" + table + "' AND column_name='" + column + "'") == 1,
                table + "." + column + "が存在するはず");
    }

    private void assertIndexExists(Statement statement, String table, String index) throws Exception {
        assertTrue(queryInt(statement, "SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name='" + table + "' AND index_name='" + index + "'") > 0,
                table + "." + index + "が存在するはず");
    }

    private int queryInt(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }
}
