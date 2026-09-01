package com.ses.migration;

import com.ses.test.MySQLContainer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REV-P0-001 / REV-P2-003: V108.3 時点の歴史データから V110（admin boundary; main の lifecycle は V109）へ前進のみで隔離・権限掃除・監査基数を検証する。
 */
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class FlywayV110AdminBoundaryUpgradeSmokeTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_v110_admin_boundary")
            .withUsername("root")
            .withPassword("ses")
            .withStartupTimeout(Duration.ofMinutes(10))
            .withStartupAttempts(3);

    @Test
    void migrateFrom108_3_quarantinesBindings_cleansPermissions_andIsIdempotent() throws Exception {
        Flyway to108_3 = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("108.3")
                .load();
        to108_3.migrate();

        long adminId;
        long salesGroupId;
        long customGroupId;
        long providerDefaultId;
        long providerOtherTenantId;
        long bindingAdmin;
        long bindingSalesA;
        long bindingSalesB;
        long bindingOtherTenant;
        String subjectAdmin = "sub-admin-prepatch";
        String subjectSalesA = "sub-sales-a";
        String subjectSalesB = "sub-sales-b";
        String subjectOther = "sub-other-tenant";

        try (Connection conn = MYSQL.createConnection(""); Statement st = conn.createStatement()) {
            adminId = queryLong(st, "SELECT id FROM sys_user WHERE username='admin' LIMIT 1");
            st.executeUpdate("INSERT INTO sys_user (username, password, real_name, role, email, status) VALUES "
                    + "('v109-sales', 'x', 'V109 Sales', '営業', 'v109-sales@example.test', 1)");
            long salesUserId = queryLong(st, "SELECT id FROM sys_user WHERE username='v109-sales'");
            salesGroupId = queryLong(st,
                    "SELECT id FROM m_permission_group WHERE tenant_id='default' AND group_key='role-sales'");

            st.executeUpdate("INSERT INTO m_permission_group "
                    + "(tenant_id, group_key, group_name, description, enabled, deleted_flag) VALUES "
                    + "('acme', 'custom-ops', 'Custom Ops', 'non-default tenant custom', 1, 0)");
            customGroupId = queryLong(st,
                    "SELECT id FROM m_permission_group WHERE tenant_id='acme' AND group_key='custom-ops'");

            // 旧allow（V66相当）と、soft-deleted deny、非default tenant custom allow を用意する。
            st.executeUpdate("INSERT INTO t_permission_group_action "
                    + "(tenant_id, group_id, action_key, deny_flag, deleted_flag) VALUES "
                    + "('acme', " + customGroupId + ", 'identity-provider.*', 0, 0)");
            st.executeUpdate("INSERT INTO t_permission_group_action "
                    + "(tenant_id, group_id, action_key, deny_flag, deleted_flag) VALUES "
                    + "('acme', " + customGroupId + ", 'system-config.*', 1, 1)");

            st.executeUpdate("INSERT INTO m_identity_provider "
                    + "(tenant_id, provider_type, issuer_uri, client_id, enabled, deleted_flag) VALUES "
                    + "('default', 'OIDC', 'https://issuer.default.test', 'client-default', 1, 0)");
            providerDefaultId = queryLong(st,
                    "SELECT id FROM m_identity_provider WHERE tenant_id='default' "
                            + "AND issuer_uri='https://issuer.default.test'");
            st.executeUpdate("INSERT INTO m_identity_provider "
                    + "(tenant_id, provider_type, issuer_uri, client_id, enabled, deleted_flag) VALUES "
                    + "('acme', 'OIDC', 'https://issuer.acme.test', 'client-acme', 1, 0)");
            providerOtherTenantId = queryLong(st,
                    "SELECT id FROM m_identity_provider WHERE tenant_id='acme' "
                            + "AND issuer_uri='https://issuer.acme.test'");

            st.executeUpdate("INSERT INTO t_user_external_identity "
                    + "(tenant_id, user_id, provider_id, subject, email_snapshot, linked_at, deleted_flag) VALUES "
                    + "('default', " + adminId + ", " + providerDefaultId + ", '" + subjectAdmin
                    + "', 'admin@example.test', NOW(), 0)");
            bindingAdmin = queryLong(st,
                    "SELECT id FROM t_user_external_identity WHERE subject='" + subjectAdmin + "'");
            st.executeUpdate("INSERT INTO t_user_external_identity "
                    + "(tenant_id, user_id, provider_id, subject, linked_at, deleted_flag) VALUES "
                    + "('default', " + salesUserId + ", " + providerDefaultId + ", '" + subjectSalesA
                    + "', NOW(), 0)");
            bindingSalesA = queryLong(st,
                    "SELECT id FROM t_user_external_identity WHERE subject='" + subjectSalesA + "'");
            st.executeUpdate("INSERT INTO t_user_external_identity "
                    + "(tenant_id, user_id, provider_id, subject, linked_at, deleted_flag) VALUES "
                    + "('default', " + salesUserId + ", " + providerDefaultId + ", '" + subjectSalesB
                    + "', NOW(), 0)");
            bindingSalesB = queryLong(st,
                    "SELECT id FROM t_user_external_identity WHERE subject='" + subjectSalesB + "'");
            st.executeUpdate("INSERT INTO t_user_external_identity "
                    + "(tenant_id, user_id, provider_id, subject, linked_at, deleted_flag) VALUES "
                    + "('acme', " + adminId + ", " + providerOtherTenantId + ", '" + subjectOther
                    + "', NOW(), 0)");
            bindingOtherTenant = queryLong(st,
                    "SELECT id FROM t_user_external_identity WHERE subject='" + subjectOther + "'");

            assertTrue(hasRow(st, "SELECT 1 FROM t_permission_group_action WHERE group_id=" + salesGroupId
                            + " AND action_key='identity-provider.*' AND deny_flag=0 AND deleted_flag=0"),
                    "V108.3時点でrole-salesへidentity-provider allowが残っていること");
        }

        Flyway toLatest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load();
        toLatest.migrate();
        toLatest.validate();

        try (Connection conn = MYSQL.createConnection(""); Statement st = conn.createStatement()) {
            assertEquals("149", queryString(st,
                    "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL "
                            + "ORDER BY installed_rank DESC LIMIT 1"));
            assertPostConditions(st, salesGroupId, customGroupId,
                    bindingAdmin, bindingSalesA, bindingSalesB, bindingOtherTenant,
                    subjectAdmin, subjectSalesA, subjectSalesB, subjectOther);

            // 部分再実行: V110 SQLを再度適用しても基数・状態が壊れないこと
            executeSqlScript(st, Path.of(
                    "src/main/resources/db/migration/V110__restore_admin_boundaries_for_identity_and_system_config.sql"));
            assertPostConditions(st, salesGroupId, customGroupId,
                    bindingAdmin, bindingSalesA, bindingSalesB, bindingOtherTenant,
                    subjectAdmin, subjectSalesA, subjectSalesB, subjectOther);

            assertFalse(Files.exists(Path.of("src/main/resources/db/migration")
                            .resolve("U110__restore_admin_boundaries_for_identity_and_system_config.sql")),
                    "V110のdown migrationは持たない（forward-only）");
        }
    }

    @Test
    void partialFailureOfV110_repairAndRemigrateSucceeds() throws Exception {
        Path dir = prepareMigrationDir();
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("filesystem:" + dir)
                .cleanDisabled(false)
                .load()
                .clean();

        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("filesystem:" + dir)
                .target("108.3")
                .load()
                .migrate();

        SeededBindings seeded;
        try (Connection conn = MYSQL.createConnection(""); Statement st = conn.createStatement()) {
            seeded = seedHistoricalBindings(st);
        }

        installFailingV110(dir);
        Flyway failing = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("filesystem:" + dir)
                .load();
        Exception failure = org.junit.jupiter.api.Assertions.assertThrows(Exception.class, failing::migrate);
        assertTrue(allMessages(failure).contains("V110")
                        || allMessages(failure).contains("syntax")
                        || allMessages(failure).contains("THIS_IS_NOT_VALID_SQL"),
                "V110途中失敗であるはず: " + allMessages(failure));

        try (Connection conn = MYSQL.createConnection(""); Statement st = conn.createStatement()) {
            assertTrue(hasRow(st,
                            "SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() "
                                    + "AND table_name='t_user_external_identity' AND column_name='review_status'"),
                    "部分適用で review_status 列は追加済みのはず");
            assertEquals(0, queryLong(st, "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema=DATABASE() AND table_name='t_oidc_binding_review_inventory'"),
                    "inventory 作成前で失敗していること");
            assertTrue(hasRow(st,
                            "SELECT 1 FROM flyway_schema_history WHERE version='110' AND success=0"),
                    "V110 の failed history が残ること");
        }

        restoreRealV110(dir);
        Flyway repaired = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("filesystem:" + dir)
                .load();
        repaired.repair();
        repaired.migrate();
        repaired.validate();

        try (Connection conn = MYSQL.createConnection(""); Statement st = conn.createStatement()) {
            assertEquals(1, queryLong(st,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='110' AND success=1"));
            assertEquals(0, queryLong(st,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='110' AND success=0"));
            assertPostConditions(st, seeded.salesGroupId, seeded.customGroupId,
                    seeded.bindingAdmin, seeded.bindingSalesA, seeded.bindingSalesB, seeded.bindingOtherTenant,
                    seeded.subjectAdmin, seeded.subjectSalesA, seeded.subjectSalesB, seeded.subjectOther);
        }
    }

    private record SeededBindings(
            long salesGroupId,
            long customGroupId,
            long bindingAdmin,
            long bindingSalesA,
            long bindingSalesB,
            long bindingOtherTenant,
            String subjectAdmin,
            String subjectSalesA,
            String subjectSalesB,
            String subjectOther) {
    }

    private SeededBindings seedHistoricalBindings(Statement st) throws Exception {
        long adminId = queryLong(st, "SELECT id FROM sys_user WHERE username='admin' LIMIT 1");
        st.executeUpdate("INSERT INTO sys_user (username, password, real_name, role, email, status) VALUES "
                + "('v109-sales', 'x', 'V109 Sales', '営業', 'v109-sales@example.test', 1)");
        long salesUserId = queryLong(st, "SELECT id FROM sys_user WHERE username='v109-sales'");
        long salesGroupId = queryLong(st,
                "SELECT id FROM m_permission_group WHERE tenant_id='default' AND group_key='role-sales'");

        st.executeUpdate("INSERT INTO m_permission_group "
                + "(tenant_id, group_key, group_name, description, enabled, deleted_flag) VALUES "
                + "('acme', 'custom-ops', 'Custom Ops', 'non-default tenant custom', 1, 0)");
        long customGroupId = queryLong(st,
                "SELECT id FROM m_permission_group WHERE tenant_id='acme' AND group_key='custom-ops'");

        st.executeUpdate("INSERT INTO t_permission_group_action "
                + "(tenant_id, group_id, action_key, deny_flag, deleted_flag) VALUES "
                + "('acme', " + customGroupId + ", 'identity-provider.*', 0, 0)");
        st.executeUpdate("INSERT INTO t_permission_group_action "
                + "(tenant_id, group_id, action_key, deny_flag, deleted_flag) VALUES "
                + "('acme', " + customGroupId + ", 'system-config.*', 1, 1)");

        st.executeUpdate("INSERT INTO m_identity_provider "
                + "(tenant_id, provider_type, issuer_uri, client_id, enabled, deleted_flag) VALUES "
                + "('default', 'OIDC', 'https://issuer.default.test', 'client-default', 1, 0)");
        long providerDefaultId = queryLong(st,
                "SELECT id FROM m_identity_provider WHERE tenant_id='default' "
                        + "AND issuer_uri='https://issuer.default.test'");
        st.executeUpdate("INSERT INTO m_identity_provider "
                + "(tenant_id, provider_type, issuer_uri, client_id, enabled, deleted_flag) VALUES "
                + "('acme', 'OIDC', 'https://issuer.acme.test', 'client-acme', 1, 0)");
        long providerOtherTenantId = queryLong(st,
                "SELECT id FROM m_identity_provider WHERE tenant_id='acme' "
                        + "AND issuer_uri='https://issuer.acme.test'");

        String subjectAdmin = "sub-admin-prepatch";
        String subjectSalesA = "sub-sales-a";
        String subjectSalesB = "sub-sales-b";
        String subjectOther = "sub-other-tenant";

        st.executeUpdate("INSERT INTO t_user_external_identity "
                + "(tenant_id, user_id, provider_id, subject, email_snapshot, linked_at, deleted_flag) VALUES "
                + "('default', " + adminId + ", " + providerDefaultId + ", '" + subjectAdmin
                + "', 'admin@example.test', DATE_SUB(NOW(), INTERVAL 30 DAY), 0)");
        long bindingAdmin = queryLong(st,
                "SELECT id FROM t_user_external_identity WHERE subject='" + subjectAdmin + "'");
        st.executeUpdate("INSERT INTO t_user_external_identity "
                + "(tenant_id, user_id, provider_id, subject, linked_at, deleted_flag) VALUES "
                + "('default', " + salesUserId + ", " + providerDefaultId + ", '" + subjectSalesA
                + "', DATE_SUB(NOW(), INTERVAL 20 DAY), 0)");
        long bindingSalesA = queryLong(st,
                "SELECT id FROM t_user_external_identity WHERE subject='" + subjectSalesA + "'");
        st.executeUpdate("INSERT INTO t_user_external_identity "
                + "(tenant_id, user_id, provider_id, subject, linked_at, deleted_flag) VALUES "
                + "('default', " + salesUserId + ", " + providerDefaultId + ", '" + subjectSalesB
                + "', DATE_SUB(NOW(), INTERVAL 10 DAY), 0)");
        long bindingSalesB = queryLong(st,
                "SELECT id FROM t_user_external_identity WHERE subject='" + subjectSalesB + "'");
        st.executeUpdate("INSERT INTO t_user_external_identity "
                + "(tenant_id, user_id, provider_id, subject, linked_at, deleted_flag) VALUES "
                + "('acme', " + adminId + ", " + providerOtherTenantId + ", '" + subjectOther
                + "', DATE_SUB(NOW(), INTERVAL 5 DAY), 0)");
        long bindingOtherTenant = queryLong(st,
                "SELECT id FROM t_user_external_identity WHERE subject='" + subjectOther + "'");

        return new SeededBindings(salesGroupId, customGroupId,
                bindingAdmin, bindingSalesA, bindingSalesB, bindingOtherTenant,
                subjectAdmin, subjectSalesA, subjectSalesB, subjectOther);
    }

    private Path prepareMigrationDir() throws Exception {
        Path source = Path.of("src/main/resources/db/migration");
        Path temp = Files.createTempDirectory("v110-fixture");
        try (var files = Files.list(source)) {
            for (Path file : files.toList()) {
                Files.copy(file, temp.resolve(file.getFileName().toString()));
            }
        }
        return temp;
    }

    /** inventory 作成前で失敗させ、review_status 追加までは通す。 */
    private void installFailingV110(Path dir) throws Exception {
        Path v110 = dir.resolve("V110__restore_admin_boundaries_for_identity_and_system_config.sql");
        String original = Files.readString(v110, StandardCharsets.UTF_8);
        int cut = original.indexOf("-- 2. ");
        assertTrue(cut > 0, "V110 inventory section の開始位置が見つかるはず");
        // section 2 の直前の区切り行ごと落とす
        int block = original.lastIndexOf("-- ------------------------------------------------------------", cut);
        if (block > 0) {
            cut = block;
        }
        Files.writeString(v110,
                original.substring(0, cut)
                        + "\n-- V110途中失敗fixture\nTHIS_IS_NOT_VALID_SQL;\n",
                StandardCharsets.UTF_8);
    }

    private void restoreRealV110(Path dir) throws Exception {
        Files.copy(
                Path.of("src/main/resources/db/migration/V110__restore_admin_boundaries_for_identity_and_system_config.sql"),
                dir.resolve("V110__restore_admin_boundaries_for_identity_and_system_config.sql"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private String allMessages(Throwable error) {
        StringBuilder sb = new StringBuilder();
        for (Throwable cur = error; cur != null; cur = cur.getCause()) {
            if (cur.getMessage() != null) {
                sb.append(cur.getMessage()).append('\n');
            }
        }
        return sb.toString();
    }

    private void assertPostConditions(Statement st,
                                      long salesGroupId,
                                      long customGroupId,
                                      long bindingAdmin,
                                      long bindingSalesA,
                                      long bindingSalesB,
                                      long bindingOtherTenant,
                                      String subjectAdmin,
                                      String subjectSalesA,
                                      String subjectSalesB,
                                      String subjectOther) throws Exception {
        assertEquals(0, queryLong(st,
                "SELECT COUNT(*) FROM t_permission_group_action a "
                        + "JOIN m_permission_group g ON g.id=a.group_id "
                        + "WHERE a.action_key IN ('identity-provider.*','system-config.*') "
                        + "AND a.deny_flag=0 AND a.deleted_flag=0 AND g.group_key<>'role-admin'"),
                "非管理者groupのidentity-provider/system-config allowが残っていないこと");

        assertTrue(hasRow(st, "SELECT 1 FROM t_permission_group_action WHERE group_id=" + salesGroupId
                        + " AND action_key='identity-provider.*' AND deny_flag=1 AND deleted_flag=0"),
                "role-salesへidentity-provider denyが書き込まれること");
        assertTrue(hasRow(st, "SELECT 1 FROM t_permission_group_action WHERE group_id=" + customGroupId
                        + " AND action_key='identity-provider.*' AND deny_flag=1 AND deleted_flag=0"),
                "非default tenantのcustom group allowがdenyへ置換されること");
        assertTrue(hasRow(st, "SELECT 1 FROM t_permission_group_action WHERE group_id=" + customGroupId
                        + " AND action_key='system-config.*' AND deny_flag=1 AND deleted_flag=0"),
                "soft-deleted denyが復活すること");

        for (long bindingId : List.of(bindingAdmin, bindingSalesA, bindingSalesB, bindingOtherTenant)) {
            assertEquals("QUARANTINED", queryString(st,
                    "SELECT review_status FROM t_user_external_identity WHERE id=" + bindingId));
            assertTrue(hasRow(st, "SELECT 1 FROM t_oidc_binding_review_inventory WHERE binding_id=" + bindingId),
                    "inventoryにbinding " + bindingId + " があること");
            assertTrue(hasRow(st, "SELECT 1 FROM t_audit_log WHERE application_code='OIDC_BINDING_QUARANTINE' "
                            + "AND uri='/internal/oidc-bindings/" + bindingId + "/quarantine'"),
                    "監査がbinding単位で残ること: " + bindingId);
        }

        assertEquals(4, queryLong(st,
                "SELECT COUNT(*) FROM t_oidc_binding_review_inventory "
                        + "WHERE binding_id IN (" + bindingAdmin + "," + bindingSalesA + ","
                        + bindingSalesB + "," + bindingOtherTenant + ")"));
        assertEquals(4, queryLong(st,
                "SELECT COUNT(*) FROM t_audit_log WHERE application_code='OIDC_BINDING_QUARANTINE' "
                        + "AND uri IN ("
                        + "'/internal/oidc-bindings/" + bindingAdmin + "/quarantine',"
                        + "'/internal/oidc-bindings/" + bindingSalesA + "/quarantine',"
                        + "'/internal/oidc-bindings/" + bindingSalesB + "/quarantine',"
                        + "'/internal/oidc-bindings/" + bindingOtherTenant + "/quarantine') "
                        + "AND created_at >= DATE_SUB(NOW(), INTERVAL 1 HOUR)"),
                "同一user複数subjectでも監査が畳み込まれず、created_atはmigration実行時刻であること");

        assertEquals(sha256Hex(subjectAdmin), queryString(st,
                "SELECT subject_sha256 FROM t_oidc_binding_review_inventory WHERE binding_id=" + bindingAdmin));
        assertEquals(sha256Hex(subjectSalesA), queryString(st,
                "SELECT subject_sha256 FROM t_oidc_binding_review_inventory WHERE binding_id=" + bindingSalesA));
        assertEquals(sha256Hex(subjectSalesB), queryString(st,
                "SELECT subject_sha256 FROM t_oidc_binding_review_inventory WHERE binding_id=" + bindingSalesB));
        assertEquals(sha256Hex(subjectOther), queryString(st,
                "SELECT subject_sha256 FROM t_oidc_binding_review_inventory WHERE binding_id=" + bindingOtherTenant));

        assertTrue(hasRow(st, "SELECT 1 FROM t_oidc_binding_review_inventory WHERE binding_id=" + bindingOtherTenant
                        + " AND tenant_id='acme' AND provider_id IS NOT NULL AND user_id IS NOT NULL "
                        + "AND user_role IS NOT NULL AND linked_at IS NOT NULL"),
                "inventoryにtenant/provider/user/role/linked_atが揃うこと");

        assertTrue(hasRow(st, "SELECT 1 FROM information_schema.table_constraints "
                        + "WHERE table_schema=DATABASE() AND table_name='t_user_external_identity' "
                        + "AND constraint_name='chk_external_identity_approved_reviewer'"),
                "APPROVED には reviewed_at/reviewed_by 必須の CHECK があること");

        st.executeUpdate("DELETE FROM t_user_external_identity WHERE subject IN "
                + "('sub-check-reject', 'sub-check-approved-ok')");

        boolean rejected = false;
        String sqlState = null;
        int errorCode = -1;
        String sqlMessage = "";
        try {
            st.executeUpdate("INSERT INTO t_user_external_identity "
                    + "(tenant_id, user_id, provider_id, subject, linked_at, review_status, "
                    + "reviewed_at, reviewed_by, deleted_flag) VALUES "
                    + "('default', (SELECT id FROM sys_user WHERE username='admin' LIMIT 1), "
                    + "(SELECT id FROM m_identity_provider WHERE tenant_id='default' LIMIT 1), "
                    + "'sub-check-reject', NOW(), 'APPROVED', NULL, NULL, 0)");
        } catch (java.sql.SQLException expected) {
            rejected = true;
            sqlState = expected.getSQLState();
            errorCode = expected.getErrorCode();
            sqlMessage = expected.getMessage() == null ? "" : expected.getMessage();
        }
        assertTrue(rejected, "APPROVED かつ reviewer 空の行は CHECK で拒否されること");
        assertTrue((sqlState != null && sqlState.startsWith("23"))
                        || errorCode == 3819
                        || sqlMessage.contains("chk_external_identity_approved_reviewer"),
                "CHECK 違反として拒否されること sqlState=" + sqlState + " errorCode=" + errorCode
                        + " message=" + sqlMessage);

        st.executeUpdate("INSERT INTO t_user_external_identity "
                + "(tenant_id, user_id, provider_id, subject, linked_at, review_status, "
                + "reviewed_at, reviewed_by, deleted_flag) VALUES "
                + "('default', (SELECT id FROM sys_user WHERE username='admin' LIMIT 1), "
                + "(SELECT id FROM m_identity_provider WHERE tenant_id='default' LIMIT 1), "
                + "'sub-check-approved-ok', NOW(), 'APPROVED', NOW(), "
                + "(SELECT id FROM sys_user WHERE username='admin' LIMIT 1), 0)");
        assertTrue(hasRow(st, "SELECT 1 FROM t_user_external_identity "
                        + "WHERE subject='sub-check-approved-ok' AND review_status='APPROVED' "
                        + "AND reviewed_at IS NOT NULL AND reviewed_by IS NOT NULL"),
                "reviewer 付き APPROVED は INSERT できること");
    }

    private void executeSqlScript(Statement st, Path path) throws Exception {
        String sql = Files.readString(path, StandardCharsets.UTF_8);
        List<String> statements = splitStatements(sql);
        for (String statement : statements) {
            if (!statement.isBlank()) {
                st.execute(statement);
            }
        }
    }

    private List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : sql.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--")) {
                continue;
            }
            current.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                out.add(current.toString().trim());
                current.setLength(0);
            }
        }
        if (!current.toString().isBlank()) {
            out.add(current.toString().trim());
        }
        return out;
    }

    private static String sha256Hex(String value) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private long queryLong(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "expected row for: " + sql);
            return rs.getLong(1);
        }
    }

    private String queryString(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "expected row for: " + sql);
            return rs.getString(1);
        }
    }

    private boolean hasRow(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            return rs.next();
        }
    }
}
