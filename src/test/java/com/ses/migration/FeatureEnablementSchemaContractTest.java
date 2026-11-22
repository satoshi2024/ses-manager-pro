package com.ses.migration;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * FE-A01: 機能の開放の9表（V203）と、H2 の試験 schema の2つの定義（{@code schema-feature-enablement-h2.sql}・
 * {@code engineer-schema-h2.sql} の V203 の節）が、列名・型・NULL 可否・生成式・主キー・一意索引・索引・CHECK・FK（複合 FK を含む）で
 * 一致することを Docker なしで固定する（先例 {@link BackupQuiesceSchemaContractTest} の構文の読取りを使う）。V203 の trigger の件数と
 * 単文の SIGNAL、時刻の DEFAULT を使わないこと、H2 の上での負例（未決の申請の2件目・申請者による承認・複合 FK の不一致・生成列への
 * 書込み）も確かめる。H2 は CHECK と FK の検査の順が MySQL と同じとは限らないため、両方に当たる入力（申請者＝承認者の開放状態等）は
 * MySQL の試験だけで確かめる。実 MySQL 8.4 での負例と trigger の拒否は {@code FeatureEnablementSchemaMySqlTest}（mysql shard 1）が確かめる。
 */
class FeatureEnablementSchemaContractTest {

    private static final Path V203 = Path.of("src/main/resources/db/migration/V203__feature_enablement_console.sql");
    private static final Path H2_VARIANT = Path.of("src/test/resources/sql/schema-feature-enablement-h2.sql");
    private static final Path ENGINEER_SCHEMA = Path.of("src/test/resources/sql/engineer-schema-h2.sql");
    private static final Path TEST_CONFIG = Path.of("src/test/resources/application-test.yml");
    /** V203 の作成の順（FK の向き。design §2.1）。 */
    private static final List<String> TABLES = List.of("t_feature_enablement_generation", "t_feature_enablement_request",
            "t_feature_enablement_state", "t_feature_enablement_setting", "t_feature_sign_off",
            "t_feature_sign_off_extension", "t_feature_sign_off_bundle", "t_feature_sign_off_bundle_member",
            "t_feature_enablement_event");
    private static final List<String> APPEND_ONLY = List.of("t_feature_enablement_event",
            "t_feature_sign_off_bundle_member", "t_feature_sign_off_extension");
    private static final String V203_SECTION_MARKER = "-- V203（機能の開放を system 内で行う。FE-A01）";

    // ---------------------------------------------------------------- 定義の一致

    @Test
    void V203とH2の2つの定義は列_型_NULL可否_生成式_索引_CHECK_FKが一致する() throws Exception {
        Map<String, BackupQuiesceSchemaContractTest.TableShape> mysql = BackupQuiesceSchemaContractTest.parse(read(V203));
        Map<String, BackupQuiesceSchemaContractTest.TableShape> h2 =
                BackupQuiesceSchemaContractTest.parse(read(H2_VARIANT));
        Map<String, BackupQuiesceSchemaContractTest.TableShape> engineer =
                BackupQuiesceSchemaContractTest.parse(engineerSchemaSection());

        assertThat(mysql.keySet()).as("V203 の表と作成の順").containsExactlyElementsOf(TABLES);
        assertThat(h2).as("schema-feature-enablement-h2.sql").isEqualTo(mysql);
        assertThat(engineer).as("engineer-schema-h2.sql の V203 の節").isEqualTo(mysql);
        for (String table : TABLES) {
            List<String> order = new ArrayList<>(mysql.get(table).columns().keySet());
            assertThat(new ArrayList<>(h2.get(table).columns().keySet())).as("列の順: " + table).isEqualTo(order);
            assertThat(new ArrayList<>(engineer.get(table).columns().keySet())).as("列の順: " + table).isEqualTo(order);
            assertThat(mysql.get(table).columns()).as("論理削除しない: " + table).doesNotContainKey("deleted_flag");
            for (Map.Entry<String, BackupQuiesceSchemaContractTest.Column> column
                    : mysql.get(table).columns().entrySet()) {
                if (column.getKey().endsWith("_at") || column.getKey().equals("unverifiable_since")) {
                    assertThat(column.getValue().type()).as("時刻の列は DATETIME(6): " + table + "." + column.getKey())
                            .isEqualTo("DATETIME(6)");
                }
            }
        }

        BackupQuiesceSchemaContractTest.TableShape request = mysql.get("t_feature_enablement_request");
        assertThat(request.columns().get("pending_slot")).isEqualTo(new BackupQuiesceSchemaContractTest.Column(
                "TINYINT", true, "CASEWHENSTATUS='PENDING'THEN1ELSENULLEND", false));
        assertThat(request.uniqueKeys()).containsEntry("uk_fe_request_pending", List.of("tenant_id", "target_key",
                "pending_slot")).containsEntry("uk_fe_request_actors", List.of("id", "requested_by", "decided_by"));
        assertThat(mysql.get("t_feature_sign_off").columns().get("active_slot").generated())
                .isEqualTo("CASEWHENSTATUS='CONFIRMED'THEN1ELSENULLEND");
        assertThat(mysql.get("t_feature_sign_off_bundle").columns().get("active_slot").generated())
                .isEqualTo("CASEWHENSTATUS='CONFIRMED'THEN1ELSENULLEND");
        assertThat(mysql.get("t_feature_enablement_state").foreignKeys()).containsEntry("fk_fe_state_request",
                "(OPEN_REQUEST_ID,OPENED_BY,APPROVED_BY)REFERENCEST_FEATURE_ENABLEMENT_REQUEST(ID,REQUESTED_BY,DECIDED_BY)");
        assertThat(mysql.get("t_feature_enablement_setting").foreignKeys()).containsEntry("fk_fe_setting_request",
                "(REQUEST_ID,REQUESTED_BY,APPROVED_BY)REFERENCEST_FEATURE_ENABLEMENT_REQUEST(ID,REQUESTED_BY,DECIDED_BY)");
        assertThat(mysql.get("t_feature_sign_off_extension").foreignKeys()).containsEntry("fk_fe_extension_request",
                "(REQUEST_ID,REQUESTED_BY,CONFIRMED_BY)REFERENCEST_FEATURE_ENABLEMENT_REQUEST(ID,REQUESTED_BY,DECIDED_BY)");
        assertThat(mysql.get("t_feature_sign_off_bundle_member").foreignKeys()).containsEntry(
                "fk_fe_bundle_member_sign_off",
                "(SIGN_OFF_ID,KIND_CODE,TENANT_ID)REFERENCEST_FEATURE_SIGN_OFF(ID,KIND_CODE,TENANT_ID)");
        for (BackupQuiesceSchemaContractTest.TableShape table : mysql.values()) {
            assertThat(table.foreignKeys().values()).as("FK に参照動作を付けない")
                    .noneMatch(fk -> fk.contains("ONDELETE") || fk.contains("ONUPDATE"));
        }
    }

    @Test
    void V203は時刻をDEFAULTで書かずSTOREDの生成列と追記専用の3表のtrigger6件を持つ() throws Exception {
        String sql = withoutComments(read(V203));
        String upper = sql.toUpperCase(Locale.ROOT);

        assertThat(upper).doesNotContain("CURRENT_TIMESTAMP").doesNotContain("NOW(").doesNotContain("LOCALTIMESTAMP")
                .doesNotContain("SYSDATE").doesNotContain("PROCEDURE").doesNotContain("FUNCTION")
                .doesNotContain("DEFINER").doesNotContain("DELIMITER").doesNotContain("BEGIN");
        assertThat(countMatches(upper, "THEN 1 ELSE NULL END\\) STORED")).isEqualTo(3);
        Matcher trigger = Pattern.compile("CREATE\\s+TRIGGER\\s+(\\w+)\\s+BEFORE\\s+(UPDATE|DELETE)\\s+ON\\s+(\\w+)\\s+"
                + "FOR\\s+EACH\\s+ROW\\s+SIGNAL\\s+SQLSTATE\\s+'45000'\\s+SET\\s+MESSAGE_TEXT\\s*=\\s*'(\\w+) is append-only';",
                Pattern.CASE_INSENSITIVE).matcher(sql);
        Map<String, List<String>> byTable = new LinkedHashMap<>();
        while (trigger.find()) {
            assertThat(trigger.group(4)).as("理由の表名").isEqualTo(trigger.group(3));
            byTable.computeIfAbsent(trigger.group(3), key -> new ArrayList<>()).add(trigger.group(2).toUpperCase(Locale.ROOT));
        }
        assertThat(countMatches(upper, "CREATE\\s+TRIGGER")).as("trigger は単文の6件だけ").isEqualTo(6);
        assertThat(byTable.keySet()).containsExactlyInAnyOrderElementsOf(APPEND_ONLY);
        byTable.values().forEach(events -> assertThat(events).containsExactly("UPDATE", "DELETE"));
        for (Path h2 : List.of(H2_VARIANT, ENGINEER_SCHEMA)) {
            String text = h2.equals(ENGINEER_SCHEMA) ? engineerSchemaSection() : read(h2);
            assertThat(withoutComments(text).toUpperCase(Locale.ROOT)).as(h2 + " は trigger を作らない")
                    .doesNotContain("TRIGGER").doesNotContain(") STORED");
        }
        assertThat(read(V203)).contains("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT=");
    }

    @Test
    void V203はmenuを管理者だけに付け権限groupのactionを足さずdata_elementを2件入れる() throws Exception {
        String sql = withoutComments(read(V203));
        String h2 = withoutComments(read(H2_VARIANT));
        for (String script : List.of(sql, h2)) {
            assertThat(script).contains("SELECT 'feature-enablement', '機能の開放', '/feature-enablement', "
                    + "'/api/feature-enablement', 263");
            assertThat(script).contains("SELECT '管理者', m.id").doesNotContain("t_permission_group_action");
            assertThat(script).contains("'FEATURE_SIGN_OFF_SIGNER_NAME'").contains("'FEATURE_SIGN_OFF_DOCUMENT'")
                    .contains("'pii-inventory.md#DB-151'");
        }
    }

    @Test
    void H2のreplayはV202の変種の後にV203の変種を流しengineer_schemaは子から順に消す() throws Exception {
        String replay = read(TEST_CONFIG);
        int v202 = replay.indexOf("- classpath:sql/schema-backup-quiesce-h2.sql");
        int v203 = replay.indexOf("- classpath:sql/schema-feature-enablement-h2.sql");

        assertThat(v202).isPositive();
        assertThat(v203).as("V202 の変種の後").isGreaterThan(v202);
        assertThat(replay).doesNotContain("classpath:db/migration/V203__");
        String engineer = engineerSchemaSection();
        List<String> dropOrder = List.of("t_feature_enablement_event", "t_feature_sign_off_bundle_member",
                "t_feature_sign_off_bundle", "t_feature_sign_off_extension", "t_feature_sign_off",
                "t_feature_enablement_setting", "t_feature_enablement_state", "t_feature_enablement_request",
                "t_feature_enablement_generation");
        int previous = -1;
        for (String table : dropOrder) {
            int position = engineer.indexOf("DROP TABLE IF EXISTS " + table + " CASCADE;");
            assertThat(position).as("DROP の順: " + table).isGreaterThan(previous);
            previous = position;
        }
    }

    // ---------------------------------------------------------------- H2 の上での負例

    @Test
    void H2では未決の申請は対象ごとに1件で申請者本人の決定と複合FKの不一致が拒否される() throws Exception {
        try (Connection connection = h2(); Statement st = connection.createStatement()) {
            st.executeUpdate(request(1, "NF01", "PENDING", 10, "NULL"));
            assertThat(scalar(st, "SELECT pending_slot FROM t_feature_enablement_request WHERE id = 1")).isEqualTo(1L);
            assertRejected(st, request(2, "NF01", "PENDING", 11, "NULL"), "23505", "uk_fe_request_pending");
            // 生成列へは書けない
            SQLException generated = catchThrowableOfType(() -> st.executeUpdate(
                    "UPDATE t_feature_enablement_request SET pending_slot = NULL WHERE id = 1"), SQLException.class);
            assertThat((Throwable) generated).isNotNull();
            assertThat(generated.getErrorCode()).as(generated.getMessage()).isEqualTo(90154);

            // 申請者本人の承認・却下、申請者以外の取下げ
            assertRejected(st, "UPDATE t_feature_enablement_request SET status = 'APPROVED', decided_by = 10, "
                    + "decided_at = TIMESTAMP '2026-11-23 10:00:00' WHERE id = 1", "23513", "ck_fe_request_decision");
            assertRejected(st, "UPDATE t_feature_enablement_request SET status = 'REJECTED', decided_by = 10, "
                    + "decided_at = TIMESTAMP '2026-11-23 10:00:00' WHERE id = 1", "23513", "ck_fe_request_decision");
            assertRejected(st, "UPDATE t_feature_enablement_request SET status = 'WITHDRAWN', decided_by = 20, "
                    + "decided_at = TIMESTAMP '2026-11-23 10:00:00' WHERE id = 1", "23513", "ck_fe_request_decision");
            st.executeUpdate("UPDATE t_feature_enablement_request SET status = 'APPROVED', decided_by = 20, "
                    + "decided_at = TIMESTAMP '2026-11-23 10:00:00' WHERE id = 1");
            assertThat(scalar(st, "SELECT COUNT(*) FROM t_feature_enablement_request WHERE pending_slot IS NULL"))
                    .isEqualTo(1L);
            assertThat(st.executeUpdate(request(3, "NF01", "PENDING", 11, "NULL"))).as("終えると次を申請できる")
                    .isEqualTo(1);

            // 開放状態の申請者・承認者は参照する申請の行と一致する（複合 FK）
            assertRejected(st, state("NF01", "OPEN", 1, 10, 30), "23506", "fk_fe_state_request");
            assertRejected(st, state("NF01", "OPEN", 1, 20, 10), "23506", "fk_fe_state_request");
            assertRejected(st, state("NF02", "OPEN_PENDING_RESTART", 1, 10, 20), "23513", "ck_fe_state_restart");
            assertThat(st.executeUpdate(state("NF01", "OPEN", 1, 10, 20))).isEqualTo(1);
            assertRejected(st, "INSERT INTO t_feature_enablement_setting (tenant_id, setting_key, setting_value, request_id, "
                    + "requested_by, approved_by, approved_at) VALUES ('default', 'nf02-customer-portal.visible-since', "
                    + "NULL, 1, 20, 10, TIMESTAMP '2026-11-23 10:00:00')", "23506", "fk_fe_setting_request");
        }
    }

    @Test
    void H2では確認済みの署名は種別ごとに1件で登録者本人の確認と組のmemberの種別の不一致が拒否される() throws Exception {
        try (Connection connection = h2(); Statement st = connection.createStatement()) {
            st.executeUpdate(signOff(1, "NF04_SRE", "CONFIRMED", 10, "20"));
            assertRejected(st, signOff(2, "NF04_SRE", "CONFIRMED", 10, "20"), "23505", "uk_fe_sign_off_active");
            assertRejected(st, signOff(3, "NF04_PRIVACY", "CONFIRMED", 10, "10"), "23513", "ck_fe_sign_off_separation");
            assertRejected(st, signOff(4, "NF04_PRIVACY", "CONFIRMED", 10, "NULL"), "23513", "ck_fe_sign_off_confirmed");
            assertRejected(st, "INSERT INTO t_feature_sign_off (tenant_id, kind_code, reference, signer_role, signed_on, "
                    + "expires_on, kind_class, content_sha256, size_bytes, status, registered_by, registered_at) VALUES "
                    + "('default', 'NF04_SECURITY', 'SEC-1', 'SECURITY', DATE '2026-11-23', DATE '2026-11-23', 'DECISION', '"
                    + "a".repeat(64) + "', 10, 'REGISTERED', 10, TIMESTAMP '2026-11-23 10:00:00')", "23513",
                    "ck_fe_sign_off_dates");
            assertRejected(st, "UPDATE t_feature_sign_off SET status = 'PURGED', purged_at = TIMESTAMP '2026-11-23 10:00:00' "
                    + "WHERE id = 1", "23513", "ck_fe_sign_off_purged");

            st.executeUpdate("INSERT INTO t_feature_sign_off_bundle (id, tenant_id, bundle_kind, status, registered_by, "
                    + "registered_at) VALUES (1, 'default', 'TEST_BUNDLE', 'REGISTERED', 10, TIMESTAMP '2026-11-23 10:00:00')");
            assertRejected(st, "INSERT INTO t_feature_sign_off_bundle_member (bundle_id, tenant_id, kind_code, sign_off_id, "
                    + "carried_over) VALUES (1, 'default', 'NF04_PRIVACY', 1, 0)", "23506", "fk_fe_bundle_member_sign_off");
            assertRejected(st, "INSERT INTO t_feature_sign_off_bundle_member (bundle_id, tenant_id, kind_code, sign_off_id, "
                    + "carried_over) VALUES (1, 'tenant-x', 'NF04_SRE', 1, 0)", "23506", "fk_fe_bundle_member");
            assertThat(st.executeUpdate("INSERT INTO t_feature_sign_off_bundle_member (bundle_id, tenant_id, kind_code, "
                    + "sign_off_id, carried_over) VALUES (1, 'default', 'NF04_SRE', 1, 0)")).isEqualTo(1);
        }
    }

    @Test
    void H2では延長の記録の承認者と期限と申請の複合FKが検査される() throws Exception {
        try (Connection connection = h2(); Statement st = connection.createStatement()) {
            st.executeUpdate(signOff(1, "NF04_SRE", "CONFIRMED", 10, "20"));
            st.executeUpdate(request(1, "signoff:1", "PENDING", 30, "NULL"));
            st.executeUpdate("UPDATE t_feature_enablement_request SET request_type = 'SIGN_OFF_EXTENSION', status = 'APPROVED', "
                    + "decided_by = 40, decided_at = TIMESTAMP '2026-11-23 10:00:00' WHERE id = 1");
            assertRejected(st, extension(1, 1, "2027-11-23", "2027-11-23", 30, 40), "23513", "ck_fe_extension_dates");
            assertRejected(st, extension(1, 1, "2027-11-23", "2028-11-23", 30, 50), "23506", "fk_fe_extension_request");
            assertThat(st.executeUpdate(extension(1, 1, "2027-11-23", "2028-11-23", 30, 40))).isEqualTo(1);
        }
    }

    // ---------------------------------------------------------------- helper

    private static String engineerSchemaSection() throws Exception {
        String engineer = read(ENGINEER_SCHEMA);
        int start = engineer.indexOf(V203_SECTION_MARKER);
        assertThat(start).as("engineer-schema-h2.sql に V203 の節がある").isNotNegative();
        return engineer.substring(start);
    }

    private static String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String withoutComments(String sql) {
        return Pattern.compile("--[^\\n]*").matcher(sql).replaceAll("");
    }

    private static int countMatches(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /** H2 変種の表の部分だけ（seed の m_menu・m_pii_data_element・sys_user は素の H2 に無い）を作る。 */
    private static Connection h2() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:h2:mem:fe_contract_"
                + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL", "sa", "");
        String script = read(H2_VARIANT);
        String tables = script.substring(0, script.indexOf("INSERT INTO m_menu"));
        ScriptUtils.executeSqlScript(connection, new ByteArrayResource(tables.getBytes(StandardCharsets.UTF_8)));
        return connection;
    }

    private static void assertRejected(Statement st, String sql, String sqlState, String constraint) {
        SQLException rejected = catchThrowableOfType(() -> st.executeUpdate(sql), SQLException.class);
        assertThat((Throwable) rejected).as(constraint + ": " + sql).isNotNull();
        assertThat(rejected.getSQLState()).as(rejected.getMessage()).isEqualTo(sqlState);
        assertThat(rejected.getMessage()).containsIgnoringCase(constraint);
    }

    private static String request(long id, String target, String status, long requestedBy, String decidedBy) {
        return "INSERT INTO t_feature_enablement_request (id, tenant_id, request_type, target_key, status, "
                + "condition_fingerprint, condition_summary, requested_by, requested_at, expires_at, decided_by) VALUES ("
                + id + ", 'default', 'OPEN', '" + target + "', '" + status + "', '" + "0".repeat(64) + "', 'ok', "
                + requestedBy + ", TIMESTAMP '2026-11-23 09:00:00', TIMESTAMP '2026-11-30 09:00:00', " + decidedBy + ")";
    }

    private static String state(String feature, String value, long requestId, long openedBy, long approvedBy) {
        return "INSERT INTO t_feature_enablement_state (tenant_id, feature_key, state, open_request_id, opened_by, "
                + "approved_by, opened_at, updated_at) VALUES ('default', '" + feature + "', '" + value + "', " + requestId
                + ", " + openedBy + ", " + approvedBy + ", TIMESTAMP '2026-11-23 10:00:00', TIMESTAMP '2026-11-23 10:00:00')";
    }

    private static String signOff(long id, String kind, String status, long registeredBy, String confirmedBy) {
        String confirmedAt = "NULL".equals(confirmedBy) ? "NULL" : "TIMESTAMP '2026-11-23 10:00:00'";
        return "INSERT INTO t_feature_sign_off (id, tenant_id, kind_code, reference, signer_role, signed_on, expires_on, "
                + "kind_class, content_sha256, size_bytes, stored_name, status, registered_by, registered_at, confirmed_by, "
                + "confirmed_at) VALUES (" + id + ", 'default', '" + kind + "', 'REF-" + id + "', 'SRE', DATE '2026-11-20', "
                + "DATE '2027-11-20', 'EVIDENCE', '" + "a".repeat(64) + "', 10, 'doc" + id + ".pdf', '" + status + "', "
                + registeredBy + ", TIMESTAMP '2026-11-23 09:00:00', " + confirmedBy + ", " + confirmedAt + ")";
    }

    private static String extension(long signOffId, long requestId, String oldExpires, String newExpires,
                                     long requestedBy, long confirmedBy) {
        return "INSERT INTO t_feature_sign_off_extension (tenant_id, sign_off_id, request_id, old_expires_on, "
                + "new_expires_on, requested_by, confirmed_by, confirmed_at) VALUES ('default', " + signOffId + ", "
                + requestId + ", DATE '" + oldExpires + "', DATE '" + newExpires + "', " + requestedBy + ", " + confirmedBy
                + ", TIMESTAMP '2026-11-23 10:00:00')";
    }

    private static long scalar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).as(sql).isTrue();
            return rs.getLong(1);
        }
    }
}
