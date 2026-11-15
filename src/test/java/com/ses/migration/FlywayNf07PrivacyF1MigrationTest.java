package com.ses.migration;

import com.ses.test.MySQLContainer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V190（NF07 F1表）と V191（W-5 権限seed）を実MySQLで検証する（production-bridge-phased-plan.md §3.1.6）。
 *
 * <p>H2 変種（schema-privacy-f1-h2.sql）では生成列・CHECK・複合FKの挙動が異なり得るため、ここが正本の検証になる。
 * 行ロックの待ちは扱わない（LOCK_DATA に依存しない）。
 */
@Tag("mysql")
@Testcontainers
class FlywayNf07PrivacyF1MigrationTest {

    private static final int CHECK_VIOLATION = 3819;
    private static final int DUPLICATE_KEY = 1062;
    private static final int FOREIGN_KEY_VIOLATION = 1452;
    private static final int GENERATED_COLUMN_WRITE = 3105;

    private static final List<String> F1_TABLES = List.of("m_pii_data_element", "m_retention_policy", "t_legal_hold",
            "t_privacy_projection_state", "t_privacy_request", "t_privacy_request_subject_link", "t_privacy_action",
            "t_privacy_export_package");

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> FRESH = mysql("ses_nf07_f1_fresh");

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> UPGRADE = mysql("ses_nf07_f1_upgrade");

    private static MySQLContainer<?> mysql(String database) {
        return new MySQLContainer<>("mysql:8.0")
                .withDatabaseName(database)
                .withUsername("root")
                .withPassword("ses");
    }

    @Test
    void 空DBから最新まで適用しF1表は行を持たずbinary照合で作られる() throws Exception {
        Flyway flyway = flyway(FRESH).load();
        flyway.migrate();
        flyway.validate();

        try (Connection connection = FRESH.createConnection(""); Statement st = connection.createStatement()) {
            assertEquals(1L, scalar(st, "SELECT COUNT(*) FROM flyway_schema_history WHERE version='190' AND success=1"));
            assertEquals(1L, scalar(st, "SELECT COUNT(*) FROM flyway_schema_history WHERE version='191' AND success=1"));
            assertEquals(LatestMigrationVersion.LATEST, flyway.info().current().getVersion().getVersion());
            for (String table : F1_TABLES) {
                assertEquals("utf8mb4_bin", queryString(st, "SELECT table_collation FROM information_schema.tables "
                        + "WHERE table_schema=DATABASE() AND table_name='" + table + "'"), table + " の照合順序");
                if ("m_pii_data_element".equals(table)) {
                    // V196（F2）は catalog の構造行だけを seed する。owner・分類・inventory 状態は UNKNOWN のまま
                    assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM m_pii_data_element WHERE owner_state <> 'UNKNOWN' "
                                    + "OR data_class <> 'UNKNOWN' OR inventory_status <> 'UNKNOWN' OR owner_ref IS NOT NULL"),
                            "catalog の seed は構造行だけ（法的な値をseedしない）");
                    continue;
                }
                // 同じcontainerを使う他のtestは tenant_id を nf07- で始める。migration 自体が作る行は0件であること。
                assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM " + table + " WHERE tenant_id NOT LIKE 'nf07-%'"),
                        table + " はmigrationで行を作らない（法的な値をseedしない）");
            }
            String extra = queryString(st, "SELECT extra FROM information_schema.columns WHERE table_schema=DATABASE() "
                    + "AND table_name='m_retention_policy' AND column_name='current_key'");
            assertTrue(extra.toUpperCase().contains("STORED GENERATED"), extra);
            assertEquals("UNKNOWN", queryString(st, "SELECT column_default FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name='m_pii_data_element' "
                    + "AND column_name='subject_attribution'"));
            for (String[] check : new String[][]{
                    {"m_pii_data_element", "chk_pii_data_element_attribution"},
                    {"m_retention_policy", "chk_retention_policy_activation"},
                    {"t_legal_hold", "chk_legal_hold_target"},
                    {"t_privacy_projection_state", "chk_projection_pending"},
                    {"t_privacy_request", "chk_privacy_request_sod"},
                    {"t_privacy_request", "chk_privacy_request_approval_binding"},
                    {"t_privacy_export_package", "chk_privacy_export_package_sod"},
                    {"t_privacy_export_package", "chk_privacy_export_package_approval"}}) {
                assertEquals(1L, scalar(st, "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE constraint_schema=DATABASE() AND table_name='" + check[0] + "' AND constraint_name='"
                        + check[1] + "' AND constraint_type='CHECK'"), check[1]);
            }
            for (String[] fk : new String[][]{
                    {"t_privacy_request", "fk_privacy_request_reopen", "t_privacy_request", "tenant_id,reopen_of_request_id", "tenant_id,id"},
                    {"t_privacy_request_subject_link", "fk_privacy_subject_link_request", "t_privacy_request", "tenant_id,request_id", "tenant_id,id"},
                    {"t_privacy_action", "fk_privacy_action_request", "t_privacy_request", "tenant_id,request_id", "tenant_id,id"},
                    {"t_privacy_export_package", "fk_privacy_export_request", "t_privacy_request", "tenant_id,request_id", "tenant_id,id"},
                    {"t_privacy_request", "fk_privacy_request_approved_package", "t_privacy_export_package",
                            "tenant_id,approved_package_id,id", "tenant_id,id,request_id"}}) {
                assertForeignKey(st, fk[0], fk[1], fk[2], fk[3], fk[4]);
            }
        }
    }

    @Test
    void current_pointerは生成列とUNIQUEでkeyごとに1件だけになりapplicationから書けない() throws Exception {
        migrateFresh();
        try (Connection connection = FRESH.createConnection(""); Statement st = connection.createStatement()) {
            st.executeUpdate("DELETE FROM m_retention_policy WHERE tenant_id LIKE 'nf07-gen-%'");
            insertPolicy(connection, "nf07-gen-a", "p-1", "r1", "gemini-adapter", "CURRENT", 2L);
            assertEquals("nf07-gen-a|NF08_AI_SUMMARY|0|AI_PROMPT_LOG|EXTERNAL_DISPATCH|gemini-adapter",
                    queryString(st, "SELECT current_key FROM m_retention_policy WHERE tenant_id='nf07-gen-a' "
                            + "AND policy_revision='r1'"));
            assertMysqlFailure(() -> insertPolicy(connection, "nf07-gen-a", "p-1", "r2", "gemini-adapter", "CURRENT", 2L),
                    DUPLICATE_KEY, "uk_retention_policy_current", "同じkeyの2件目のCURRENT");
            // CURRENT 以外は生成列が NULL なので何件でも持てる。provider が違えば別の current pointer。
            insertPolicy(connection, "nf07-gen-a", "p-1", "r3", "gemini-adapter", "CANDIDATE", null);
            insertPolicy(connection, "nf07-gen-a", "p-1", "r4", "gemini-adapter", "SUPERSEDED", null);
            insertPolicy(connection, "nf07-gen-a", "p-1", "r5", "other-adapter", "CURRENT", 2L);
            insertPolicy(connection, "nf07-gen-b", "p-1", "r1", "gemini-adapter", "CURRENT", 2L);
            assertNull(queryString(st, "SELECT current_key FROM m_retention_policy WHERE tenant_id='nf07-gen-a' "
                    + "AND policy_revision='r3'"));
            assertMysqlFailure(() -> st.executeUpdate("INSERT INTO m_retention_policy (tenant_id, policy_id, "
                            + "policy_revision, module_code, data_element_id, target_action, target_provider, status, "
                            + "current_key, envelope_schema_version, envelope_canonical, envelope_signature, envelope_digest, "
                            + "authority_id, key_id, registered_by, registered_at) VALUES ('nf07-gen-c','p','r','M','E','A',"
                            + "'P','CANDIDATE','forged',1,x'01',x'02',REPEAT('a',64),'a','k',1,NOW(6))"),
                    GENERATED_COLUMN_WRITE, "current_key", "生成列への直接書込み");
            assertMysqlFailure(() -> insertPolicy(connection, "nf07-gen-c", "p-1", "r1", "gemini-adapter", "CURRENT", null),
                    CHECK_VIOLATION, "chk_retention_policy_activation", "有効化者の無いCURRENT");
            assertMysqlFailure(() -> insertPolicy(connection, "nf07-gen-c", "p-1", "r2", "gemini-adapter", "CURRENT", 1L),
                    CHECK_VIOLATION, "chk_retention_policy_activation", "登録者と同じ有効化者");
        }
    }

    @Test
    void DSARのSoDと承認束縛と複合FKはtenantとrequestをまたぐ関連を拒否する() throws Exception {
        migrateFresh();
        try (Connection connection = FRESH.createConnection(""); Statement st = connection.createStatement()) {
            long requestA = insertRequest(connection, "nf07-dsar-a", "REQ-A");
            long requestB = insertRequest(connection, "nf07-dsar-a", "REQ-B");
            long otherTenantRequest = insertRequest(connection, "nf07-dsar-b", "REQ-A");
            long packageA = insertPackage(connection, "nf07-dsar-a", requestA, "stored-a");
            long packageB = insertPackage(connection, "nf07-dsar-a", requestB, "stored-b");

            // 複合FK: 他 tenant の request へ子行を作れない
            assertMysqlFailure(() -> st.executeUpdate("INSERT INTO t_privacy_request_subject_link (tenant_id, "
                            + "request_id, subject_ref, link_state) VALUES ('nf07-dsar-b', " + requestA
                            + ", 'ENGINEER:1', 'CANDIDATE')"),
                    FOREIGN_KEY_VIOLATION, "fk_privacy_subject_link_request", "他tenantのrequestへのlink");
            assertMysqlFailure(() -> insertPackage(connection, "nf07-dsar-b", requestA, "stored-x"),
                    FOREIGN_KEY_VIOLATION, "fk_privacy_export_request", "他tenantのrequestへのpackage");
            assertMysqlFailure(() -> st.executeUpdate("INSERT INTO t_privacy_action (tenant_id, request_id, "
                            + "action_type, status, idempotency_key) VALUES ('nf07-dsar-b', " + requestA
                            + ", 'SEARCH', 'PENDING', 'idem-1')"),
                    FOREIGN_KEY_VIOLATION, "fk_privacy_action_request", "他tenantのrequestへのaction");
            assertMysqlFailure(() -> st.executeUpdate("UPDATE t_privacy_request SET reopen_of_request_id = "
                            + otherTenantRequest + " WHERE id = " + requestA),
                    FOREIGN_KEY_VIOLATION, "fk_privacy_request_reopen", "他tenantのrequestの再開");

            // SoD: 準備者が未記録のまま承認できない／準備者と同じ承認者は拒否
            assertMysqlFailure(() -> approve(connection, requestA, null, 2L, packageA),
                    CHECK_VIOLATION, "chk_privacy_request_sod", "準備者の無い承認");
            assertMysqlFailure(() -> approve(connection, requestA, 2L, 2L, packageA),
                    CHECK_VIOLATION, "chk_privacy_request_sod", "準備者と同じ承認者");
            // 承認束縛: 承認4項目は揃うか全て空
            assertMysqlFailure(() -> st.executeUpdate("UPDATE t_privacy_request SET prepared_by=1, approved_by=2, "
                            + "approved_at=NOW(6) WHERE id=" + requestA),
                    CHECK_VIOLATION, "chk_privacy_request_approval_binding", "packageを束縛しない承認");
            // 同じ tenant の別 request の package は承認対象にできない（R2-P3-5）
            assertMysqlFailure(() -> approve(connection, requestA, 1L, 2L, packageB),
                    FOREIGN_KEY_VIOLATION, "fk_privacy_request_approved_package", "別requestのpackageの承認");
            approve(connection, requestA, 1L, 2L, packageA);
            assertEquals(packageA, scalar(st, "SELECT approved_package_id FROM t_privacy_request WHERE id=" + requestA));

            // package の SoD と承認の対
            assertMysqlFailure(() -> st.executeUpdate("UPDATE t_privacy_export_package SET approved_by=created_by, "
                            + "approved_at=NOW(6) WHERE id=" + packageB),
                    CHECK_VIOLATION, "chk_privacy_export_package_sod", "作成者と同じpackage承認者");
            assertMysqlFailure(() -> st.executeUpdate("UPDATE t_privacy_export_package SET approved_by=9 WHERE id="
                            + packageB),
                    CHECK_VIOLATION, "chk_privacy_export_package_approval", "日時の無いpackage承認");
        }
    }

    @Test
    void 分類の既定はUNKNOWNで4値以外とhold_projectionの不整合は拒否する() throws Exception {
        migrateFresh();
        try (Connection connection = FRESH.createConnection(""); Statement st = connection.createStatement()) {
            st.executeUpdate("INSERT INTO m_pii_data_element (tenant_id, data_element_id, storage_kind, storage_ref) "
                    + "VALUES ('nf07-cat', 'AI_PROMPT_LOG', 'AI_PAYLOAD', 't_ai_recommendation_run.prompt')");
            assertEquals("UNKNOWN", queryString(st, "SELECT subject_attribution FROM m_pii_data_element "
                    + "WHERE tenant_id='nf07-cat'"));
            assertEquals("UNKNOWN", queryString(st, "SELECT inventory_status FROM m_pii_data_element "
                    + "WHERE tenant_id='nf07-cat'"));
            for (String invalid : new String[]{"unknown", "NONE", ""}) {
                assertMysqlFailure(() -> st.executeUpdate("UPDATE m_pii_data_element SET subject_attribution='"
                                + invalid + "' WHERE tenant_id='nf07-cat'"),
                        CHECK_VIOLATION, "chk_pii_data_element_attribution", "未知の分類 " + invalid);
            }
            assertMysqlFailure(() -> st.executeUpdate("INSERT INTO t_legal_hold (tenant_id, hold_id, target_type, "
                            + "status, reason_code, reason_hash, ticket_ref, requested_by, requested_at, journal_revision, "
                            + "journal_event_hash) VALUES ('nf07-cat','h-1','SUBJECT','ACTIVE','CODE',REPEAT('a',64),"
                            + "'T-1',1,NOW(6),1,REPEAT('b',64))"),
                    CHECK_VIOLATION, "chk_legal_hold_target", "subject参照の無いSUBJECT hold");
            assertMysqlFailure(() -> st.executeUpdate("INSERT INTO t_privacy_projection_state (tenant_id, journal_id, "
                            + "engaged_at, pending_command_id) VALUES ('nf07-cat','j-1',NOW(6),'cmd-1')"),
                    CHECK_VIOLATION, "chk_projection_pending", "pending日時の無いpending");
        }
    }

    @Test
    void V191はprivacy_admin以外の全groupでdenyしprivacy_adminだけに明示許可を与え割当はしない() throws Exception {
        migrateFresh();
        try (Connection connection = FRESH.createConnection(""); Statement st = connection.createStatement()) {
            assertPrivacySeed(st, "default", 5);
            assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM t_user_permission_group u JOIN m_permission_group g "
                    + "ON g.id = u.group_id WHERE g.group_key='privacy-admin'"), "privacy-adminへ誰も割り当てない");
            assertEquals(List.of("privacy-retention:260:/privacy/holds:/api/privacy/holds",
                            "privacy-dsar:261:/privacy/dsar:/api/privacy/dsar"),
                    rows(st, "SELECT CONCAT(menu_key, ':', sort_order, ':', path_prefix, ':', api_prefix) FROM m_menu "
                            + "WHERE menu_key IN ('privacy-retention','privacy-dsar') ORDER BY sort_order"));
            assertEquals(List.of("管理者", "管理者"), rows(st, "SELECT r.role FROM t_role_menu r JOIN m_menu m "
                    + "ON m.id = r.menu_id WHERE m.menu_key IN ('privacy-retention','privacy-dsar') ORDER BY m.sort_order"));
            assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM m_menu WHERE sort_order IN (260, 261) "
                    + "AND menu_key NOT IN ('privacy-retention','privacy-dsar')"), "sort_orderは未使用値");
        }
    }

    @Test
    void V186から更新すると既存の全tenantと手作業のgroupにもprivacyのdenyが入る() throws Exception {
        Flyway atBase = flyway(UPGRADE).target("186").load();
        atBase.migrate();
        try (Connection connection = UPGRADE.createConnection(""); Statement st = connection.createStatement()) {
            // 運用で '*' を複製した group と、別 tenant の role-admin（無効）を V191 の前に用意する
            st.executeUpdate("INSERT INTO m_permission_group (tenant_id, group_key, group_name, enabled) "
                    + "VALUES ('default', 'ops-copy', '複製group', 1), ('tenant-x', 'role-admin', 'X管理者', 0)");
            st.executeUpdate("INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag) "
                    + "SELECT tenant_id, id, '*', 0 FROM m_permission_group WHERE group_key IN ('ops-copy') "
                    + "OR (tenant_id='tenant-x' AND group_key='role-admin')");
            // 既に privacy.* の許可行を持つ group（誤設定）は deny へ反転させる
            st.executeUpdate("INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag, deleted_flag) "
                    + "SELECT tenant_id, id, 'privacy.*', 0, 1 FROM m_permission_group WHERE group_key = 'role-sales' "
                    + "AND tenant_id='default'");
        }

        Flyway latest = flyway(UPGRADE).load();
        latest.migrate();
        latest.validate();

        try (Connection connection = UPGRADE.createConnection(""); Statement st = connection.createStatement()) {
            assertPrivacySeed(st, "default", 6);
            assertPrivacySeed(st, "tenant-x", 1);
            assertEquals(1L, scalar(st, "SELECT COUNT(*) FROM t_permission_group_action a JOIN m_permission_group g "
                    + "ON g.id = a.group_id WHERE g.group_key='role-sales' AND g.tenant_id='default' "
                    + "AND a.action_key='privacy.*' AND a.deny_flag=1 AND a.deleted_flag=0"),
                    "既存の privacy.* 行は deny・有効へ揃える");
        }
    }

    /**
     * V196（F2 catalog seed）: sys_user の tenant ごとに1回だけ入り、再実行しても重複しない。gate に接続する
     * data element（§3.4.1）は UNKNOWN にならず、collation の異なる sys_user / m_document_type との比較で失敗しない。
     */
    @Test
    void V196のcatalog_seedはtenantごとに1回だけ入り再実行しても重複しない() throws Exception {
        migrateFresh();
        try (Connection connection = FRESH.createConnection(""); Statement st = connection.createStatement()) {
            assertEquals(1L, scalar(st, "SELECT COUNT(*) FROM flyway_schema_history WHERE version='196' AND success=1"));
            // 件数は文書種別の数に依存させない（V197 が文書種別と DOCUMENT:<code> を足すため。P3-3）
            long documentTypes = scalar(st, "SELECT COUNT(*) FROM m_document_type");
            assertEquals(CATALOG_FIXED_ELEMENTS, scalar(st, "SELECT COUNT(*) FROM m_pii_data_element "
                    + "WHERE tenant_id='default' AND data_element_id NOT LIKE 'DOCUMENT:%'"), "文書以外の固定の data element");
            assertEquals(documentTypes, scalar(st, "SELECT COUNT(*) FROM m_pii_data_element "
                    + "WHERE tenant_id='default' AND data_element_id LIKE 'DOCUMENT:%'"), "文書種別ごとの DOCUMENT:<code>");
            long defaultRows = scalar(st, "SELECT COUNT(*) FROM m_pii_data_element WHERE tenant_id='default'");
            assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM m_pii_data_element WHERE tenant_id='default' "
                    + "AND subject_attribution='UNKNOWN' AND (provider_id IS NOT NULL OR data_element_id LIKE 'DOCUMENT:%' "
                    + "OR data_element_id LIKE 'INTEGRATION_HUB_%' OR data_element_id='EXTERNAL_API_READ_SNAPSHOT')"),
                    "provider が扱う要素と gate に接続する要素は分類済み");
            assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM m_document_type dt WHERE NOT EXISTS (SELECT 1 FROM "
                    + "m_pii_data_element e WHERE e.tenant_id='default' AND e.data_element_id=CONCAT('DOCUMENT:', dt.code) "
                    + "AND e.subject_attribution IN ('ATTRIBUTABLE','NOT_ATTRIBUTABLE') AND e.provider_id='document-file')"), "全文書種別の要素");
            // 本人を導出できない文書種別は NOT_ATTRIBUTABLE（DocumentFileProvider.DOCUMENT_TYPE_RULES と同じ。V196・V197）
            assertEquals(List.of("DOCUMENT:IMPORT", "DOCUMENT:MANAGEMENT_REPORT", "DOCUMENT:SERVICE_REQUEST_ATTACHMENT"),
                    rows(st, "SELECT data_element_id FROM m_pii_data_element WHERE tenant_id='default' "
                            + "AND data_element_id LIKE 'DOCUMENT:%' AND subject_attribution='NOT_ATTRIBUTABLE' "
                            + "ORDER BY data_element_id"), "残余の文書種別");

            st.executeUpdate("INSERT INTO sys_user (tenant_id, username, password, real_name, role, status) "
                    + "VALUES ('nf07-seed', 'nf07-seed-user', 'x', 'NF07 seed', '管理者', 1)");
            for (int i = 0; i < 2; i++) {
                for (String script : List.of("db/migration/V196__nf07_privacy_data_element_catalog_seed.sql",
                        "db/migration/V197__resume_ingestion_and_import_document_types.sql")) {
                    org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                            new org.springframework.core.io.support.EncodedResource(
                                    new org.springframework.core.io.ClassPathResource(script),
                                    java.nio.charset.StandardCharsets.UTF_8));
                }
                assertEquals(defaultRows, scalar(st, "SELECT COUNT(*) FROM m_pii_data_element WHERE tenant_id='default'"),
                        "再実行で既存 tenant の行は増えない");
                assertEquals(defaultRows, scalar(st, "SELECT COUNT(*) FROM m_pii_data_element WHERE tenant_id='nf07-seed'"),
                        "新しい tenant には1回だけ入る");
                assertEquals(documentTypes, scalar(st, "SELECT COUNT(*) FROM m_document_type"), "文書種別も重複しない");
            }
            assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM (SELECT tenant_id, data_element_id FROM m_pii_data_element "
                    + "GROUP BY tenant_id, data_element_id HAVING COUNT(*) > 1) d"), "重複なし");
        }
    }

    /** V196 が tenant ごとに入れる文書以外の固定の data element の件数（DOCUMENT:<code> は文書種別ごとに数える）。 */
    private static final long CATALOG_FIXED_ELEMENTS = 27L;

    // ---------------------------------------------------------------- helper

    private static void assertPrivacySeed(Statement st, String tenant, int otherGroups) throws SQLException {
        assertEquals(1L, scalar(st, "SELECT COUNT(*) FROM m_permission_group WHERE tenant_id='" + tenant
                + "' AND group_key='privacy-admin'"), tenant + " の privacy-admin");
        // V191 が入れる許可行（'*'・'privacy.*'）だけを見る。後続の migration は privacy-admin に他の明示許可を足し得る
        // （V193 の import.metadata.manage）ため、最新版まで上げた後の比較は FlywayProdFreshInstallMySqlTest と同じく
        // key を限定する。
        assertEquals(List.of("*", "privacy.*"), rows(st, "SELECT a.action_key FROM t_permission_group_action a "
                + "JOIN m_permission_group g ON g.id = a.group_id WHERE g.tenant_id='" + tenant
                + "' AND g.group_key='privacy-admin' AND a.deny_flag=0 AND a.deleted_flag=0 "
                + "AND a.action_key IN ('*', 'privacy.*') ORDER BY a.action_key"));
        assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM t_permission_group_action a JOIN m_permission_group g "
                + "ON g.id = a.group_id WHERE g.tenant_id='" + tenant + "' AND g.group_key='privacy-admin' "
                + "AND a.deny_flag=1"), "privacy-admin は deny を持たない");
        assertEquals((long) otherGroups, scalar(st, "SELECT COUNT(*) FROM t_permission_group_action a "
                + "JOIN m_permission_group g ON g.id = a.group_id WHERE g.tenant_id='" + tenant
                + "' AND g.group_key <> 'privacy-admin' AND a.action_key='privacy.*' AND a.deny_flag=1 "
                + "AND a.deleted_flag=0"), tenant + " の privacy.* deny 件数");
        assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM m_permission_group g WHERE g.tenant_id='" + tenant
                + "' AND g.group_key <> 'privacy-admin' AND NOT EXISTS (SELECT 1 FROM t_permission_group_action d "
                + "WHERE d.group_id = g.id AND d.action_key='privacy.*' AND d.deny_flag=1 AND d.deleted_flag=0)"),
                tenant + " に privacy.* の deny を欠く group がある");
        // prod 起動検査（PermissionGroupMapper.selectWildcardGroupKeysMissingPrivacyDeny）と同じ条件で0件
        assertEquals(0L, scalar(st, "SELECT COUNT(*) FROM m_permission_group g WHERE g.tenant_id='" + tenant
                + "' AND g.enabled=1 AND g.deleted_flag=0 AND g.group_key <> 'privacy-admin' "
                + "AND EXISTS (SELECT 1 FROM t_permission_group_action a WHERE a.tenant_id=g.tenant_id "
                + "AND a.group_id=g.id AND a.action_key='*' AND a.deny_flag=0 AND a.deleted_flag=0) "
                + "AND NOT EXISTS (SELECT 1 FROM t_permission_group_action d WHERE d.tenant_id=g.tenant_id "
                + "AND d.group_id=g.id AND d.action_key='privacy.*' AND d.deny_flag=1 AND d.deleted_flag=0)"));
    }

    private void migrateFresh() {
        Flyway flyway = flyway(FRESH).load();
        flyway.migrate();
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration flyway(MySQLContainer<?> container) {
        return Flyway.configure()
                .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
                .locations("classpath:db/migration");
    }

    private static void insertPolicy(Connection connection, String tenant, String policyId, String revision,
                                     String provider, String status, Long activatedBy) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO m_retention_policy (tenant_id, "
                + "policy_id, policy_revision, module_code, data_element_id, target_action, target_provider, status, "
                + "envelope_schema_version, envelope_canonical, envelope_signature, envelope_digest, authority_id, "
                + "key_id, registered_by, registered_at, activated_by, activated_at) VALUES (?,?,?,'NF08_AI_SUMMARY',"
                + "'AI_PROMPT_LOG','EXTERNAL_DISPATCH',?,?,1,?,?,REPEAT('a',64),'authority','key',1,NOW(6),?,?)")) {
            insert.setString(1, tenant);
            insert.setString(2, policyId);
            insert.setString(3, revision);
            insert.setString(4, provider);
            insert.setString(5, status);
            insert.setBytes(6, new byte[]{1, 2, 3});
            insert.setBytes(7, new byte[64]);
            if (activatedBy == null) {
                insert.setNull(8, java.sql.Types.BIGINT);
                insert.setNull(9, java.sql.Types.TIMESTAMP);
            } else {
                insert.setLong(8, activatedBy);
                insert.setTimestamp(9, java.sql.Timestamp.valueOf("2026-11-14 10:00:00"));
            }
            insert.executeUpdate();
        }
    }

    private static long insertRequest(Connection connection, String tenant, String requestNo) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO t_privacy_request (tenant_id, "
                + "request_no, request_type, status, channel, received_at, intake_ticket_ref, created_by) "
                + "VALUES (?,?,'ACCESS','RECEIVED','EMAIL',NOW(6),'TICKET-1',1)", Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, tenant);
            insert.setString(2, requestNo);
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static long insertPackage(Connection connection, String tenant, long requestId, String storedName)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO t_privacy_export_package (tenant_id, "
                + "request_id, stored_name, content_sha256, size_bytes, status, purge_after, created_by) "
                + "VALUES (?,?,?,REPEAT('c',64),10,'DRAFT',NOW(6),1)", Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, tenant);
            insert.setLong(2, requestId);
            insert.setString(3, storedName);
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static void approve(Connection connection, long requestId, Long preparedBy, long approvedBy, long packageId)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("UPDATE t_privacy_request SET prepared_by=?, "
                + "approved_by=?, approved_at=NOW(6), approved_package_id=?, approved_content_sha256=REPEAT('c',64) "
                + "WHERE id=?")) {
            if (preparedBy == null) {
                update.setNull(1, java.sql.Types.BIGINT);
            } else {
                update.setLong(1, preparedBy);
            }
            update.setLong(2, approvedBy);
            update.setLong(3, packageId);
            update.setLong(4, requestId);
            update.executeUpdate();
        }
    }

    private static void assertForeignKey(Statement st, String table, String constraint, String referencedTable,
                                         String columns, String referencedColumns) throws SQLException {
        List<String> actualColumns = new ArrayList<>();
        List<String> actualReferenced = new ArrayList<>();
        try (PreparedStatement query = st.getConnection().prepareStatement(
                "SELECT column_name, referenced_column_name FROM information_schema.key_column_usage "
                        + "WHERE constraint_schema=DATABASE() AND table_name=? AND constraint_name=? "
                        + "AND referenced_table_name=? ORDER BY ordinal_position")) {
            query.setString(1, table);
            query.setString(2, constraint);
            query.setString(3, referencedTable);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    actualColumns.add(rows.getString(1));
                    actualReferenced.add(rows.getString(2));
                }
            }
        }
        assertEquals(List.of(columns.split(",")), actualColumns, constraint + " のFK列");
        assertEquals(List.of(referencedColumns.split(",")), actualReferenced, constraint + " の参照先列");
    }

    private static void assertMysqlFailure(Executable operation, int expectedVendorCode, String expectedName,
                                           String message) {
        SQLException failure = assertThrows(SQLException.class, operation, message);
        assertEquals(expectedVendorCode, failure.getErrorCode(), message + "（MySQLエラー番号）: " + failure.getMessage());
        assertTrue(failure.getMessage().contains(expectedName), message + ": " + failure.getMessage());
    }

    private static long scalar(Statement st, String sql) throws SQLException {
        try (ResultSet result = st.executeQuery(sql)) {
            assertTrue(result.next(), "集計queryが結果行を返す");
            return result.getLong(1);
        }
    }

    private static String queryString(Statement st, String sql) throws SQLException {
        try (ResultSet result = st.executeQuery(sql)) {
            assertTrue(result.next(), "queryが結果行を返す");
            return result.getString(1);
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
