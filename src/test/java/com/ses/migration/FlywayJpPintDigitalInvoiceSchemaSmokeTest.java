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
    void JpPintテーブルがMySQLで最新まで構築できる() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                
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
            assertIndexDoesNotExist(statement, "t_digital_invoice", "uk_digital_invoice_send");
            
            // Inbound Columns
            assertColumnExists(statement, "t_digital_invoice", "supplier_company_id");
            assertColumnExists(statement, "t_digital_invoice", "match_status");
            
            // Menu entries (V107_2 seed)
            assertTrue(queryInt(statement, "SELECT COUNT(*) FROM m_menu WHERE menu_key IN ('digital-invoice', 'inbound-invoice')") == 2,
                    "digital-invoice / inbound-invoice メニューが2件あるはず");

            // Permissions: action_key on t_permission_group_action, group_key on m_permission_group
            assertTrue(queryInt(statement,
                    "SELECT COUNT(*) FROM t_permission_group_action a "
                            + "JOIN m_permission_group g ON g.id = a.group_id "
                            + "WHERE g.group_key='role-admin' AND a.action_key LIKE 'digital-invoice%'") > 0,
                    "role-admin に digital-invoice 権限があるはず");
            assertTrue(queryInt(statement,
                    "SELECT COUNT(*) FROM t_permission_group_action a "
                            + "JOIN m_permission_group g ON g.id = a.group_id "
                            + "WHERE g.group_key='role-manager' AND a.action_key LIKE 'inbound-invoice%'") > 0,
                    "role-manager に inbound-invoice 権限があるはず");
            
            // connection_id nullability
            assertTrue(queryInt(statement, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='t_integration_job' AND column_name='connection_id' AND is_nullable='YES'") == 1);
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

    private void assertIndexDoesNotExist(Statement statement, String table, String index) throws Exception {
        assertTrue(queryInt(statement, "SELECT COUNT(*) FROM information_schema.statistics " + "WHERE table_schema=DATABASE() AND table_name='" + table + "' AND index_name='" + index + "'") == 0, table + "." + index + "が存在しないはず");
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


