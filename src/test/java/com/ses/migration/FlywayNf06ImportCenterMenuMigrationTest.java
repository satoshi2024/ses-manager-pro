package com.ses.migration;

import com.ses.service.security.ActionPermissionResolver;
import com.ses.test.MySQLContainer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V193（NF-06 取込センター画面のmenuと import.metadata.manage のseed、W3-12）を実MySQLで検証する。
 *
 * <p>本体は MySQL の {@code UPDATE ... JOIN} を使い、H2 変種（schema-import-center-menu-permission-h2.sql）とは
 * 文が異なるため、ここが正本の検証になる。行ロックの待ちは扱わない（LOCK_DATA に依存しない）。
 */
@Tag("mysql")
@Testcontainers
class FlywayNf06ImportCenterMenuMigrationTest {

    private static final String ACTION = "import.metadata.manage";

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> FRESH = mysql("ses_nf06_menu_fresh");

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> UPGRADE = mysql("ses_nf06_menu_upgrade");

    private static MySQLContainer<?> mysql(String database) {
        return new MySQLContainer<>("mysql:8.0")
                .withDatabaseName(database)
                .withUsername("root")
                .withPassword("ses");
    }

    @Test
    void 空DBから最新まで適用すると管理者だけのmenuと管理者専用groupの明示許可と他groupのdenyが入る() throws Exception {
        Flyway flyway = flyway(FRESH).load();
        flyway.migrate();
        flyway.validate();

        try (Connection connection = FRESH.createConnection(""); Statement st = connection.createStatement()) {
            assertEquals(1L, scalar(st, "SELECT COUNT(*) FROM flyway_schema_history WHERE version='193' AND success=1"));
            assertEquals(LatestMigrationVersion.LATEST, flyway.info().current().getVersion().getVersion());
            assertEquals(List.of("import-center:262:/import-center:/api/import-center/sources"),
                    rows(st, "SELECT CONCAT(menu_key, ':', sort_order, ':', path_prefix, ':', api_prefix) FROM m_menu "
                            + "WHERE menu_key = 'import-center'"));
            assertEquals(List.of("管理者"), rows(st, "SELECT r.role FROM t_role_menu r JOIN m_menu m ON m.id = r.menu_id "
                    + "WHERE m.menu_key = 'import-center'"), "menuは管理者だけへ付与する");
            assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM m_menu WHERE sort_order = 262 "
                    + "AND menu_key <> 'import-center'"), "sort_orderは未使用値");
            String apiPrefix = rows(st, "SELECT api_prefix FROM m_menu WHERE menu_key = 'import-center'").get(0);
            assertEquals(ACTION, ActionPermissionResolver.resolve("GET", apiPrefix),
                    "menuのapi_prefixがaction keyへ解決できる（MenuPermissionFilterの全員403を防ぐ）");
            assertImportSeed(st, "default", 4);
        }
    }

    @Test
    void V192から更新すると既存の全tenantと手作業のgroupにも同じseedが入り既存行は許可と拒否へ揃える() throws Exception {
        Flyway atBase = flyway(UPGRADE).target("192").load();
        atBase.migrate();
        try (Connection connection = UPGRADE.createConnection(""); Statement st = connection.createStatement()) {
            // 運用で '*' を複製した group と、別 tenant の role-admin（無効）を V193 の前に用意する
            st.executeUpdate("INSERT INTO m_permission_group (tenant_id, group_key, group_name, enabled) "
                    + "VALUES ('default', 'ops-copy', '複製group', 1), ('tenant-x', 'role-admin', 'X管理者', 0)");
            st.executeUpdate("INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag) "
                    + "SELECT tenant_id, id, '*', 0 FROM m_permission_group WHERE group_key = 'ops-copy' "
                    + "OR (tenant_id = 'tenant-x' AND group_key = 'role-admin')");
            // 誤設定: 営業groupの論理削除済み許可行と、管理者groupの論理削除済み拒否行
            st.executeUpdate("INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag, deleted_flag) "
                    + "SELECT tenant_id, id, '" + ACTION + "', 0, 1 FROM m_permission_group "
                    + "WHERE group_key = 'role-sales' AND tenant_id = 'default'");
            st.executeUpdate("INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag, deleted_flag) "
                    + "SELECT tenant_id, id, '" + ACTION + "', 1, 1 FROM m_permission_group "
                    + "WHERE group_key = 'role-admin' AND tenant_id = 'default'");
        }

        Flyway latest = flyway(UPGRADE).load();
        latest.migrate();
        latest.validate();

        try (Connection connection = UPGRADE.createConnection(""); Statement st = connection.createStatement()) {
            assertImportSeed(st, "default", 5);
            assertImportSeed(st, "tenant-x", 0);
            assertEquals(1L, scalar(st, "SELECT COUNT(*) FROM t_permission_group_action a JOIN m_permission_group g "
                    + "ON g.id = a.group_id WHERE g.group_key = 'role-sales' AND g.tenant_id = 'default' "
                    + "AND a.action_key = '" + ACTION + "' AND a.deny_flag = 1 AND a.deleted_flag = 0"),
                    "既存の許可行は拒否・有効へ揃える");
            assertEquals(1L, scalar(st, "SELECT COUNT(*) FROM t_permission_group_action a JOIN m_permission_group g "
                    + "ON g.id = a.group_id WHERE g.group_key = 'ops-copy' AND a.action_key = '" + ACTION + "' "
                    + "AND a.deny_flag = 1"), "'*' を複製したgroupも拒否する");
        }
    }

    // ---------------------------------------------------------------- helper

    private static void assertImportSeed(Statement st, String tenant, int deniedGroups) throws SQLException {
        for (String adminGroup : List.of("role-admin", "privacy-admin")) {
            long groups = scalar(st, "SELECT COUNT(*) FROM m_permission_group WHERE tenant_id='" + tenant
                    + "' AND group_key='" + adminGroup + "'");
            assertEquals(groups, scalar(st, "SELECT COUNT(*) FROM t_permission_group_action a "
                    + "JOIN m_permission_group g ON g.id = a.group_id WHERE g.tenant_id='" + tenant
                    + "' AND g.group_key='" + adminGroup + "' AND a.action_key='" + ACTION
                    + "' AND a.deny_flag=0 AND a.deleted_flag=0"), tenant + " " + adminGroup + " の明示許可");
            assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM t_permission_group_action a "
                    + "JOIN m_permission_group g ON g.id = a.group_id WHERE g.tenant_id='" + tenant
                    + "' AND g.group_key='" + adminGroup + "' AND a.action_key IN ('*', 'import.*', '" + ACTION
                    + "') AND a.deny_flag=1 AND a.deleted_flag=0"), tenant + " " + adminGroup + " は拒否行を持たない");
        }
        assertEquals((long) deniedGroups, scalar(st, "SELECT COUNT(*) FROM t_permission_group_action a "
                + "JOIN m_permission_group g ON g.id = a.group_id WHERE g.tenant_id='" + tenant
                + "' AND g.group_key NOT IN ('role-admin', 'privacy-admin') AND a.action_key='" + ACTION
                + "' AND a.deny_flag=1 AND a.deleted_flag=0"), tenant + " の拒否件数");
        assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM m_permission_group g WHERE g.tenant_id='" + tenant
                + "' AND g.group_key NOT IN ('role-admin', 'privacy-admin') AND NOT EXISTS (SELECT 1 "
                + "FROM t_permission_group_action d WHERE d.group_id = g.id AND d.action_key='" + ACTION
                + "' AND d.deny_flag=1 AND d.deleted_flag=0)"), tenant + " に拒否を欠くgroupがある");
        assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM t_user_permission_group u JOIN m_permission_group g "
                + "ON g.id = u.group_id WHERE g.tenant_id='" + tenant + "' AND g.group_key = 'privacy-admin'"),
                "V193は利用者をgroupへ割り当てない");
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration flyway(MySQLContainer<?> container) {
        return Flyway.configure()
                .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
                .locations("classpath:db/migration");
    }

    private static long scalar(Statement st, String sql) throws SQLException {
        try (ResultSet result = st.executeQuery(sql)) {
            assertTrue(result.next(), "集計queryが結果行を返す");
            return result.getLong(1);
        }
    }

    private static List<String> rows(Statement st, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (ResultSet result = st.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }
}
