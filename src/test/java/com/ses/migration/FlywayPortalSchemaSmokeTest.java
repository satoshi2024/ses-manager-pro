package com.ses.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T082のMySQL smoke。V104（fresh full run）でportal DDL shapeと
 * 招待token一回性CAS・email一意・規約同意UNIQUEを実MySQLで検証する（design §6.3）。
 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayPortalSchemaSmokeTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_portal_v104")
            .withUsername("root")
            .withPassword("ses");

    /** legacy path検証用: V103_1適用済みDBへV104を適用する。 */
    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> LEGACY_MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_portal_legacy")
            .withUsername("root")
            .withPassword("ses");

    @Test
    void V104のportal_shapeがfreshとlegacyで一致し制約がMySQLで成立する() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            for (String table : new String[]{
                    "m_portal_organization", "t_portal_user", "t_portal_invitation",
                    "t_portal_user_permission", "t_portal_terms_consent", "t_portal_session"}) {
                assertTableExists(statement, table);
            }
            assertColumnExists(statement, "m_portal_organization", "customer_id");
            assertColumnExists(statement, "m_portal_organization", "bp_company_id");
            assertColumnExists(statement, "t_portal_user", "totp_secret_encrypted");
            assertColumnExists(statement, "t_portal_user", "recovery_code_hash");
            assertColumnExists(statement, "t_portal_user", "version");
            assertColumnExists(statement, "t_portal_invitation", "token_hash");
            assertColumnExists(statement, "t_portal_invitation", "used_at");
            assertColumnExists(statement, "t_portal_session", "token_hash");
            assertColumnExists(statement, "t_portal_session", "revoked_at");
            assertColumnExists(statement, "t_bp_payment", "received_confirmed_at");
            assertColumnExists(statement, "t_invoice", "received_confirmed_at");
            assertColumnExists(statement, "t_invoice", "payment_expected_date");
            assertColumnExists(statement, "t_invoice", "portal_inquiry");
            // UNIQUE/CHECK/FK
            assertIndexExists(statement, "m_portal_organization", "uk_portal_org_customer");
            assertIndexExists(statement, "m_portal_organization", "uk_portal_org_bp");
            assertIndexExists(statement, "t_portal_user", "uk_portal_user_email");
            assertIndexExists(statement, "t_portal_invitation", "uk_portal_invite_token_hash");
            assertIndexExists(statement, "t_portal_user_permission", "uk_portal_user_permission");
            assertIndexExists(statement, "t_portal_terms_consent", "uk_portal_terms_consent");
            assertIndexExists(statement, "t_portal_session", "uk_portal_session_token_hash");
            assertCheckExists(statement, "m_portal_organization", "chk_portal_org_type");
            assertCheckExists(statement, "t_portal_user", "chk_portal_user_status");
            assertCheckExists(statement, "t_portal_invitation", "chk_portal_invite_role");
            assertForeignKeyExists(statement, "t_portal_user", "fk_portal_user_org");
            assertForeignKeyExists(statement, "t_portal_terms_consent", "fk_portal_terms_user");
            assertForeignKeyExists(statement, "t_portal_session", "fk_portal_session_user");

            // ---- seed ----
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM m_system_config WHERE config_key='portal.terms.current-version'"));
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM m_system_config WHERE config_key='portal.base-domain'"));
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM m_menu WHERE menu_key='portal-admin'"));
            assertEquals(2, queryInt(statement,
                    "SELECT COUNT(*) FROM t_role_menu WHERE menu_id="
                            + "(SELECT id FROM m_menu WHERE menu_key='portal-admin')"));
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM t_permission_group_action pa "
                            + "JOIN m_permission_group g ON g.id=pa.group_id "
                            + "WHERE pa.tenant_id='default' AND g.group_key='role-sales' "
                            + "AND pa.action_key='portal-admin.*' AND pa.deny_flag=0"));

            // ---- データ投入と制約の実挙動 ----
            statement.executeUpdate("INSERT INTO m_customer (company_name) VALUES ('T082-mysql-customer')");
            long customerId = queryLong(statement,
                    "SELECT id FROM m_customer WHERE company_name='T082-mysql-customer'");
            statement.executeUpdate("INSERT INTO m_portal_organization (type, customer_id) "
                    + "VALUES ('CUSTOMER', " + customerId + ")");
            long orgId = queryLong(statement,
                    "SELECT id FROM m_portal_organization WHERE customer_id=" + customerId);

            // 型の不正値をCHECKで拒否
            boolean invalidTypeRejected = false;
            try {
                statement.executeUpdate("INSERT INTO m_portal_organization (type, customer_id) "
                        + "VALUES ('OTHER', 999)");
            } catch (SQLException expected) {
                invalidTypeRejected = true;
            }
            assertTrue(invalidTypeRejected, "portal組織の不正typeを拒否するはず（CHECK）");

            // 同一customerへの2組織目はUNIQUEで拒否
            boolean duplicateOrgRejected = false;
            try {
                statement.executeUpdate("INSERT INTO m_portal_organization (type, customer_id) "
                        + "VALUES ('CUSTOMER', " + customerId + ")");
            } catch (SQLException expected) {
                duplicateOrgRejected = true;
            }
            assertTrue(duplicateOrgRejected, "同一customerへの2組織目を拒否するはず（UNIQUE）");

            statement.executeUpdate("INSERT INTO t_portal_user (portal_org_id, email, display_name, status) "
                    + "VALUES (" + orgId + ", 't082@example.com', 'T082', 'ACTIVE')");
            long userId = queryLong(statement,
                    "SELECT id FROM t_portal_user WHERE email='t082@example.com'");

            // email一意
            boolean duplicateEmailRejected = false;
            try {
                statement.executeUpdate("INSERT INTO t_portal_user (portal_org_id, email) "
                        + "VALUES (" + orgId + ", 't082@example.com')");
            } catch (SQLException expected) {
                duplicateEmailRejected = true;
            }
            assertTrue(duplicateEmailRejected, "同一emailの重複userを拒否するはず（UNIQUE）");

            // 招待token一回性CAS: 同時使用でも1件だけ成功（design §6.3）
            statement.executeUpdate("INSERT INTO t_portal_invitation "
                    + "(portal_org_id, email, role, token_hash, expires_at) "
                    + "VALUES (" + orgId + ", 't082@example.com', 'MEMBER', "
                    + "'abc123', DATE_ADD(NOW(), INTERVAL 72 HOUR))");
            long inviteId = queryLong(statement,
                    "SELECT id FROM t_portal_invitation WHERE token_hash='abc123'");
            int consumed1 = statement.executeUpdate(
                    "UPDATE t_portal_invitation SET used_at=NOW(), accepted_by=" + userId
                            + " WHERE id=" + inviteId + " AND used_at IS NULL");
            int consumed2 = statement.executeUpdate(
                    "UPDATE t_portal_invitation SET used_at=NOW(), accepted_by=" + userId
                            + " WHERE id=" + inviteId + " AND used_at IS NULL");
            assertEquals(1, consumed1, "1回目のCASは1件成功するはず");
            assertEquals(0, consumed2, "2回目（同時使用の敗者）は0件のはず");

            // 規約同意UNIQUE(user_id, terms_version)
            statement.executeUpdate("INSERT INTO t_portal_terms_consent "
                    + "(user_id, terms_version, consented_at) VALUES (" + userId + ", '1', NOW())");
            boolean duplicateConsentRejected = false;
            try {
                statement.executeUpdate("INSERT INTO t_portal_terms_consent "
                        + "(user_id, terms_version, consented_at) VALUES (" + userId + ", '1', NOW())");
            } catch (SQLException expected) {
                duplicateConsentRejected = true;
            }
            assertTrue(duplicateConsentRejected, "同一versionへの二重同意を拒否するはず（UNIQUE）");
        }
    }

    @Test
    void V103_1適用済みlegacyDBへV104を順方向適用できshapeがfreshと一致する() throws Exception {
        // 既存DB（portal導入前shape）をV103_1まで適用してからV104を適用する。
        // 現在のV1はportal統合済みのため、V103_1適用後にportal追加分を除去して
        // 「portal導入前のlegacy shape」を再現し、V104のguarded DDLを単独で適用する。
        Flyway.configure()
                .dataSource(LEGACY_MYSQL.getJdbcUrl(), LEGACY_MYSQL.getUsername(), LEGACY_MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("103_1")
                .load()
                .migrate();

        try (Connection connection = LEGACY_MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            // ---- portal導入前shapeへの復元 ----
            statement.executeUpdate("ALTER TABLE t_portal_session DROP FOREIGN KEY fk_portal_session_user");
            statement.executeUpdate("DROP TABLE t_portal_session");
            statement.executeUpdate("ALTER TABLE t_portal_terms_consent DROP FOREIGN KEY fk_portal_terms_user");
            statement.executeUpdate("ALTER TABLE t_portal_user_permission DROP FOREIGN KEY fk_portal_user_perm_user");
            statement.executeUpdate("ALTER TABLE t_portal_invitation DROP FOREIGN KEY fk_portal_invite_org");
            statement.executeUpdate("ALTER TABLE t_portal_user DROP FOREIGN KEY fk_portal_user_org");
            statement.executeUpdate("ALTER TABLE m_portal_organization DROP FOREIGN KEY fk_portal_org_customer");
            statement.executeUpdate("ALTER TABLE m_portal_organization DROP FOREIGN KEY fk_portal_org_bp");
            statement.executeUpdate("DROP TABLE t_portal_terms_consent");
            statement.executeUpdate("DROP TABLE t_portal_user_permission");
            statement.executeUpdate("DROP TABLE t_portal_invitation");
            statement.executeUpdate("DROP TABLE t_portal_user");
            statement.executeUpdate("DROP TABLE m_portal_organization");
            // t_portal_sessionはV104_1が初めて追加するテーブル（target(103_1)時点では存在しない）。
            // t_bp_payment.received_confirmed_at はV104が初めて追加する列のため、
            // target(103_1)時点では存在せずDROP不要（V104のガード付きADDが存在判定する）。
            statement.executeUpdate("DELETE FROM m_menu WHERE menu_key='portal-admin'");
            statement.executeUpdate("DELETE FROM m_system_config WHERE config_key LIKE 'portal.%'");
        }

        // ---- V104/V104_1をlegacy DBへ順方向適用 ----
        Flyway.configure()
                .dataSource(LEGACY_MYSQL.getJdbcUrl(), LEGACY_MYSQL.getUsername(), LEGACY_MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("104_2")
                .load()
                .migrate();

        try (Connection connection = LEGACY_MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            for (String table : new String[]{
                    "m_portal_organization", "t_portal_user", "t_portal_invitation",
                    "t_portal_user_permission", "t_portal_terms_consent", "t_portal_session"}) {
                assertTableExists(statement, table);
            }
            assertColumnExists(statement, "t_bp_payment", "received_confirmed_at");
            assertColumnExists(statement, "t_invoice", "received_confirmed_at");
            assertColumnExists(statement, "t_invoice", "payment_expected_date");
            assertColumnExists(statement, "t_invoice", "portal_inquiry");
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM m_menu WHERE menu_key='portal-admin'"));

            // fresh（V1統合baseline）とlegacy（V104順方向適用）でshapeが一致する
            Flyway.configure()
                    .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                    .locations("classpath:db/migration")
                    .target("104_2")
                    .load()
                    .migrate();
            String freshShape = portalShape(MYSQL.createConnection(""));
            String legacyShape = portalShape(LEGACY_MYSQL.createConnection(""));
            assertEquals(freshShape, legacyShape, "fresh/legacyでportalテーブルのshapeが一致する");
        }
    }

    /** portal関連テーブル/列の定義を連結してfresh/legacy比較用のfingerprintを作る。 */
    private String portalShape(Connection connection) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT table_name, column_name, column_type, is_nullable, column_default "
                             + "FROM information_schema.columns "
                             + "WHERE table_schema = DATABASE() AND table_name IN "
                             + "('m_portal_organization','t_portal_user','t_portal_invitation',"
                             + "'t_portal_user_permission','t_portal_terms_consent','t_portal_session') "
                             + "ORDER BY table_name, ordinal_position")) {
            while (rs.next()) {
                sb.append(rs.getString(1)).append('|').append(rs.getString(2)).append('|')
                        .append(rs.getString(3)).append('|').append(rs.getString(4)).append('|')
                        .append(rs.getString(5)).append('\n');
            }
        }
        return sb.toString();
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

    private void assertCheckExists(Statement statement, String table, String constraint) throws Exception {
        assertTrue(queryInt(statement, "SELECT COUNT(*) FROM information_schema.table_constraints "
                + "WHERE constraint_schema=DATABASE() AND table_name='" + table
                + "' AND constraint_name='" + constraint + "' AND constraint_type='CHECK'") == 1,
                table + "." + constraint + "がCHECK制約として存在するはず");
    }

    private void assertForeignKeyExists(Statement statement, String table, String constraint) throws Exception {
        assertTrue(queryInt(statement, "SELECT COUNT(*) FROM information_schema.table_constraints "
                + "WHERE constraint_schema=DATABASE() AND table_name='" + table
                + "' AND constraint_name='" + constraint + "' AND constraint_type='FOREIGN KEY'") == 1,
                table + "." + constraint + "がFK制約として存在するはず");
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
