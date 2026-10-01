package com.ses.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import com.ses.test.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
                .target(MigrationVersion.fromVersion("181"))
                .load()
                .migrate();

        seedV181LegacyScopeFixtures();

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
            assertColumnExists(statement, "t_peppol_participant", "tenant_id");
            assertColumnExists(statement, "t_peppol_participant", "legal_entity_id");
            assertColumnExists(statement, "t_digital_invoice", "tenant_id");
            assertColumnExists(statement, "t_digital_invoice", "legal_entity_id");
            assertColumnExists(statement, "t_digital_invoice_event", "tenant_id");
            assertColumnExists(statement, "t_digital_invoice_event", "legal_entity_id");
            assertColumnExists(statement, "t_external_account_reference", "tenant_id");
            assertColumnExists(statement, "t_external_account_reference", "legal_entity_id");
            assertTableExists(statement, "t_nf09_scope_repair_queue");
            assertIndexDoesNotExist(statement, "t_peppol_participant", "uk_peppol_participant_owner");
            assertIndexDoesNotExist(statement, "t_digital_invoice", "uk_digital_invoice_message");
            assertIndexExists(statement, "t_peppol_participant", "uk_peppol_scope_owner");
            assertIndexExists(statement, "t_peppol_participant", "uk_peppol_callback_active");
            assertIndexExists(statement, "t_digital_invoice", "uk_digital_invoice_scope_message");
            assertIndexExists(statement, "t_digital_invoice_event", "uk_digital_invoice_event_provider");
            // V182でtenant・法人を含む有効SEND一意制約へ置換する。
            assertIndexDoesNotExist(statement, "t_digital_invoice", "uk_digital_invoice_send");
            assertIndexExists(statement, "t_digital_invoice", "uk_digital_invoice_scope_send");
            assertIndexDoesNotExist(statement, "t_external_account_reference", "uq_ext_idempotency");
            assertIndexExists(statement, "t_external_account_reference", "uq_ext_scope_idempotency");
            assertColumnExists(statement, "t_digital_invoice", "send_active_slot");
            assertConstraintExists(statement, "t_digital_invoice_event", "fk_digital_invoice_event_scope");
            assertConstraintExists(statement, "t_digital_invoice", "ck_digital_invoice_actor_pair");
            assertConstraintExists(statement, "t_digital_invoice_event", "ck_digital_invoice_event_actor_pair");
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM t_digital_invoice "
                            + "WHERE message_id='V182-RESOLVED' AND tenant_id='tenant-v182' AND legal_entity_id=7001 "
                            + "AND actor_type='LEGACY_UNRESOLVED' AND confirmation_source='LEGACY_UNRESOLVED'"));
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM t_digital_invoice_event e "
                            + "JOIN t_digital_invoice d ON d.id=e.digital_invoice_id "
                            + "WHERE d.message_id='V182-RESOLVED' AND e.tenant_id=d.tenant_id "
                            + "AND e.legal_entity_id=d.legal_entity_id "
                            + "AND e.actor_type='LEGACY_UNRESOLVED'"));
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM t_nf09_scope_repair_queue q "
                            + "JOIN t_digital_invoice d ON d.id=q.entity_id "
                            + "WHERE q.entity_type='DIGITAL_INVOICE' AND d.message_id='V182-ORPHAN' "
                            + "AND q.reason='SCOPE_UNRESOLVED'"));
            assertEquals(2, queryInt(statement,
                    "SELECT COUNT(*) FROM t_peppol_participant "
                            + "WHERE participant_id='0088:v182-duplicate' "
                            + "AND status='CONFLICT_REPAIR' AND verified_at IS NULL"));
            long resolvedDigitalId = queryLong(statement,
                    "SELECT id FROM t_digital_invoice WHERE message_id='V182-RESOLVED'");
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO t_digital_invoice "
                            + "(tenant_id, legal_entity_id, direction, profile, specification_version, "
                            + "message_id, status, actor_type, confirmation_source, human_user_id) VALUES "
                            + "('tenant-v182', 7001, 'SEND', 'JP_PINT', '1.0', "
                            + "'V182-INVALID-ACTOR', 'QUEUED', 'SYSTEM', 'MANUAL_API', NULL)"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO t_digital_invoice_event "
                            + "(tenant_id, legal_entity_id, digital_invoice_id, provider_event_id, event_type, "
                            + "event_at, payload_hash, signature_valid, actor_type, confirmation_source) VALUES "
                            + "('other-tenant', 7001, " + resolvedDigitalId + ", 'V182-CROSS-SCOPE-EVENT', "
                            + "'DELIVERED', NOW(), REPEAT('b',64), 1, 'PROVIDER', 'PROVIDER_CALLBACK')"));
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

    private void seedV181LegacyScopeFixtures() throws Exception {
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO m_customer (tenant_id, legal_entity_id, company_name) "
                    + "VALUES ('tenant-v182', 7001, 'V182顧客A')");
            long customerA = lastInsertId(statement);
            statement.executeUpdate("INSERT INTO m_customer (tenant_id, legal_entity_id, company_name) "
                    + "VALUES ('tenant-v182', 7001, 'V182顧客B')");
            long customerB = lastInsertId(statement);
            statement.executeUpdate("INSERT INTO t_invoice "
                    + "(invoice_no, customer_id, billing_month, subtotal, tax, total, status, legal_entity_id) VALUES "
                    + "('INV-V182-UPGRADE', " + customerA + ", '2026-09', 1000, 100, 1100, '未送付', 7001)");
            long invoiceId = lastInsertId(statement);
            statement.executeUpdate("INSERT INTO t_digital_invoice "
                    + "(invoice_id, direction, profile, specification_version, message_id, status) VALUES "
                    + "(" + invoiceId + ", 'SEND', 'JP_PINT', '1.0', 'V182-RESOLVED', 'QUEUED')");
            long resolvedDigitalId = lastInsertId(statement);
            statement.executeUpdate("INSERT INTO t_digital_invoice_event "
                    + "(digital_invoice_id, provider_event_id, event_type, event_at, payload_hash, signature_valid) VALUES "
                    + "(" + resolvedDigitalId + ", 'V182-EVENT', 'QUEUED', NOW(), REPEAT('a',64), 1)");
            statement.executeUpdate("INSERT INTO t_digital_invoice "
                    + "(direction, profile, specification_version, message_id, status) VALUES "
                    + "('SEND', 'JP_PINT', '1.0', 'V182-ORPHAN', 'QUEUED')");
            statement.executeUpdate("INSERT INTO t_peppol_participant "
                    + "(owner_type, owner_id, scheme_id, participant_id, provider, status, verified_at) VALUES "
                    + "('CUSTOMER', " + customerA + ", '0088', '0088:v182-duplicate', 'FAST_ACCOUNTING', 'VERIFIED', NOW()), "
                    + "('CUSTOMER', " + customerB + ", '0088', '0088:v182-duplicate', 'FAST_ACCOUNTING', 'VERIFIED', NOW())");
        }
    }

    private long lastInsertId(Statement statement) throws Exception {
        try (ResultSet resultSet = statement.executeQuery("SELECT LAST_INSERT_ID()")) {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
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

    private void assertConstraintExists(Statement statement, String table, String constraint) throws Exception {
        assertTrue(queryInt(statement, "SELECT COUNT(*) FROM information_schema.table_constraints "
                + "WHERE table_schema=DATABASE() AND table_name='" + table
                + "' AND constraint_name='" + constraint + "'") == 1,
                table + "." + constraint + "が存在するはず");
    }

    private int queryInt(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private long queryLong(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }
}
