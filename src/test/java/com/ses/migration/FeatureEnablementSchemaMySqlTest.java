package com.ses.migration;

import com.ses.test.MySQLContainer;
import com.ses.test.MySqlConstraintRejections;
import com.ses.test.MySqlTestImages;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FE-A01（tasks.md の MySQL の試験。P3-M3 を含む）: 実 MySQL 8.4 の空 DB へ V1 から V203 まで適用し、機能の開放の9表の CHECK・
 * 一意索引・複合 FK と、追記専用の3表（履歴・組の member・延長の記録）の trigger が、二人の承認・二人の確認・1件の枠を DB で
 * 強制することを確かめる。CHECK の違反（3819）は SQLState が HY000 のため {@link MySqlConstraintRejections} で error code と制約名で
 * 判定する。MySQL は CHECK を FK より先に評価するため、CHECK と複合 FK の両方に当たる入力（申請者＝承認者の開放状態等）は CHECK の
 * 名前で拒否される。H2 の上での同じ負例と定義の一致は {@code FeatureEnablementSchemaContractTest} が確かめる。
 */
@Tag("mysql")
@Testcontainers
class FeatureEnablementSchemaMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MySqlTestImages.image())
            .withDatabaseName("ses_feature_enablement_schema").withUsername("root").withPassword("ses");

    /** SIGNAL SQLSTATE '45000' の error code。 */
    private static final int SIGNAL = 1644;
    private static final int DUPLICATE = 1062;

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(),
                MYSQL.getPassword());
        jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
    }

    @Test
    void V203まで適用され9表とtrigger6件がある() {
        assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version = '203'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() "
                + "AND table_name LIKE 't\\_feature\\_%'", Integer.class)).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = DATABASE() "
                + "AND event_object_table LIKE 't\\_feature\\_%'", Integer.class)).isEqualTo(6);
    }

    /**
     * review rb-fe-a1 P3-11: MySQL 8.4 の既定（{@code restrict_fk_on_non_standard_key=ON}）の下で V203 を適用でき、3つの複合 FK は
     * {@code uk_fe_request_actors}（申請の id・申請者・決定者の UNIQUE の全ての列）を参照する。8.0 の image で流す回帰の確認では
     * 変数が無いため、版が 8.4 以上の時だけ変数を確かめる（skip しない）。
     */
    @Test
    void MySQL84の非標準keyへのFKの制限の下で適用され複合FKはUNIQUEの全ての列を参照する() {
        String version = jdbc.queryForObject("SELECT VERSION()", String.class);
        String[] parts = version.split("[.-]");
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);
        if (major > 8 || (major == 8 && minor >= 4)) {
            assertThat(jdbc.queryForObject("SELECT @@GLOBAL.restrict_fk_on_non_standard_key", Integer.class))
                    .as("8.4 の既定は ON（%s）", version).isEqualTo(1);
        }
        assertThat(jdbc.queryForList("SELECT constraint_name FROM information_schema.referential_constraints "
                + "WHERE constraint_schema = DATABASE() AND unique_constraint_name = 'uk_fe_request_actors' "
                + "ORDER BY constraint_name", String.class))
                .containsExactly("fk_fe_extension_request", "fk_fe_setting_request", "fk_fe_state_request");
        assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version = '203'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void 申請者本人の承認と却下と申請者以外の取下げと未決の2件目は拒否される() {
        String tenant = tenant();
        long id = request(tenant, "NF01", 10);
        for (String status : new String[]{"APPROVED", "REJECTED"}) {
            MySqlConstraintRejections.assertRejectedBy(() -> jdbc.update("UPDATE t_feature_enablement_request SET status = ?, "
                    + "decided_by = 10, decided_at = NOW(6) WHERE id = ?", status, id),
                    MySqlConstraintRejections.CHECK_VIOLATION, "ck_fe_request_decision");
        }
        MySqlConstraintRejections.assertRejectedBy(() -> jdbc.update("UPDATE t_feature_enablement_request SET status = "
                + "'WITHDRAWN', decided_by = 20, decided_at = NOW(6) WHERE id = ?", id),
                MySqlConstraintRejections.CHECK_VIOLATION, "ck_fe_request_decision");
        MySqlConstraintRejections.assertRejectedBy(() -> request(tenant, "NF01", 11), DUPLICATE, "uk_fe_request_pending");

        jdbc.update("UPDATE t_feature_enablement_request SET status = 'WITHDRAWN', decided_by = 10, decided_at = NOW(6) "
                + "WHERE id = ?", id);
        assertThat(request(tenant, "NF01", 11)).as("終えた後は次を申請できる").isPositive();
    }

    @Test
    void 開放状態は申請の行と申請者と承認者が一致しなければならずNF05以外は再起動待ちにできない() {
        String tenant = tenant();
        long id = approvedRequest(tenant, "NF01", 10, 20);
        MySqlConstraintRejections.assertRejectedBy(() -> state(tenant, "NF01", "OPEN", id, 10, 10),
                MySqlConstraintRejections.CHECK_VIOLATION, "ck_fe_state_open");
        MySqlConstraintRejections.assertRejectedBy(() -> state(tenant, "NF01", "OPEN", id, 10, 30),
                MySqlConstraintRejections.FOREIGN_KEY_VIOLATION, "fk_fe_state_request");
        MySqlConstraintRejections.assertRejectedBy(() -> state(tenant, "NF01", "OPEN", id, 20, 10),
                MySqlConstraintRejections.FOREIGN_KEY_VIOLATION, "fk_fe_state_request");
        long other = approvedRequest(tenant, "NF02", 10, 20);
        MySqlConstraintRejections.assertRejectedBy(() -> state(tenant, "NF02", "OPEN_PENDING_RESTART", other, 10, 20),
                MySqlConstraintRejections.CHECK_VIOLATION, "ck_fe_state_restart");
        assertThat(state(tenant, "NF01", "OPEN", id, 10, 20)).isEqualTo(1);
        long inbound = approvedRequest(tenant, "NF05_INBOUND", 10, 20);
        assertThat(state(tenant, "NF05_INBOUND", "OPEN_PENDING_RESTART", inbound, 10, 20)).isEqualTo(1);
    }

    @Test
    void 設定の行の申請者と承認者は別人で申請の行と一致しなければならない() {
        String tenant = tenant();
        long id = approvedRequest(tenant, "nf02-customer-portal.visible-since", 10, 20);
        MySqlConstraintRejections.assertRejectedBy(() -> setting(tenant, id, 10, 10),
                MySqlConstraintRejections.CHECK_VIOLATION, "ck_fe_setting_separation");
        MySqlConstraintRejections.assertRejectedBy(() -> setting(tenant, id, 20, 10),
                MySqlConstraintRejections.FOREIGN_KEY_VIOLATION, "fk_fe_setting_request");
        assertThat(setting(tenant, id, 10, 20)).isEqualTo(1);
    }

    @Test
    void 署名は種別ごとに確認済み1件で登録者本人の確認と失効日の逆転と消去の残りが拒否される() {
        String tenant = tenant();
        long id = signOff(tenant, "NF04_SRE", "CONFIRMED", 10, 20L);
        MySqlConstraintRejections.assertRejectedBy(() -> signOff(tenant, "NF04_SRE", "CONFIRMED", 10, 30L), DUPLICATE,
                "uk_fe_sign_off_active");
        MySqlConstraintRejections.assertRejectedBy(() -> signOff(tenant, "NF04_PRIVACY", "CONFIRMED", 10, 10L),
                MySqlConstraintRejections.CHECK_VIOLATION, "ck_fe_sign_off_separation");
        MySqlConstraintRejections.assertRejectedBy(() -> jdbc.update("INSERT INTO t_feature_sign_off (tenant_id, kind_code, "
                + "reference, signer_role, signed_on, expires_on, kind_class, content_sha256, size_bytes, status, "
                + "registered_by, registered_at) VALUES (?, 'NF04_SECURITY', 'SEC-1', 'SECURITY', '2026-11-23', '2026-11-23', "
                + "'DECISION', ?, 10, 'REGISTERED', 10, NOW(6))", tenant, "a".repeat(64)),
                MySqlConstraintRejections.CHECK_VIOLATION, "ck_fe_sign_off_dates");
        MySqlConstraintRejections.assertRejectedBy(() -> jdbc.update("UPDATE t_feature_sign_off SET status = 'PURGED', "
                + "stored_name = NULL, signer_name = NULL, purged_at = NOW(6) WHERE id = ?", id),
                MySqlConstraintRejections.CHECK_VIOLATION, "ck_fe_sign_off_purged");
        assertThat(jdbc.update("UPDATE t_feature_sign_off SET status = 'PURGED', stored_name = NULL, signer_name = NULL, "
                + "original_name = NULL, purged_at = NOW(6) WHERE id = ?", id)).isEqualTo(1);
    }

    @Test
    void 組は確認済み1件で登録者本人の確認とmemberの種別とtenantの不一致が拒否され組のmemberは追記専用() {
        String tenant = tenant();
        long signOff = signOff(tenant, "NF07_D01", "REGISTERED", 10, null);
        long bundle = bundle(tenant, "CONFIRMED", 10, 20L);
        MySqlConstraintRejections.assertRejectedBy(() -> bundle(tenant, "CONFIRMED", 10, 30L), DUPLICATE,
                "uk_fe_bundle_active");
        MySqlConstraintRejections.assertRejectedBy(() -> bundle(tenant, "REGISTERED", 10, 10L),
                MySqlConstraintRejections.CHECK_VIOLATION, "ck_fe_bundle_separation");
        MySqlConstraintRejections.assertRejectedBy(() -> member(bundle, tenant, "NF07_D09", signOff),
                MySqlConstraintRejections.FOREIGN_KEY_VIOLATION, "fk_fe_bundle_member_sign_off");
        MySqlConstraintRejections.assertRejectedBy(() -> member(bundle, tenant(), "NF07_D01", signOff),
                MySqlConstraintRejections.FOREIGN_KEY_VIOLATION, "fk_fe_bundle_member");
        assertThat(member(bundle, tenant, "NF07_D01", signOff)).isEqualTo(1);

        MySqlConstraintRejections.assertRejectedBy(() -> jdbc.update("UPDATE t_feature_sign_off_bundle_member "
                + "SET carried_over = 1 WHERE bundle_id = ?", bundle), SIGNAL, "t_feature_sign_off_bundle_member is append-only");
        MySqlConstraintRejections.assertRejectedBy(() -> jdbc.update("DELETE FROM t_feature_sign_off_bundle_member "
                + "WHERE bundle_id = ?", bundle), SIGNAL, "t_feature_sign_off_bundle_member is append-only");
    }

    @Test
    void 延長の記録は申請者以外の承認と期限の延びを要し申請の行と一致し追記専用() {
        String tenant = tenant();
        long signOff = signOff(tenant, "NF04_SRE", "CONFIRMED", 10, 20L);
        long request = approvedRequest(tenant, "signoff:" + signOff, 30, 40);
        MySqlConstraintRejections.assertRejectedBy(() -> extension(tenant, signOff, request, "2028-11-20", 30, 30),
                MySqlConstraintRejections.CHECK_VIOLATION, "ck_fe_extension_separation");
        MySqlConstraintRejections.assertRejectedBy(() -> extension(tenant, signOff, request, "2027-11-20", 30, 40),
                MySqlConstraintRejections.CHECK_VIOLATION, "ck_fe_extension_dates");
        MySqlConstraintRejections.assertRejectedBy(() -> extension(tenant, signOff, request, "2028-11-20", 30, 50),
                MySqlConstraintRejections.FOREIGN_KEY_VIOLATION, "fk_fe_extension_request");
        assertThat(extension(tenant, signOff, request, "2028-11-20", 30, 40)).isEqualTo(1);

        MySqlConstraintRejections.assertRejectedBy(() -> jdbc.update("UPDATE t_feature_sign_off_extension "
                + "SET new_expires_on = '2029-11-20' WHERE sign_off_id = ?", signOff), SIGNAL,
                "t_feature_sign_off_extension is append-only");
        MySqlConstraintRejections.assertRejectedBy(() -> jdbc.update("DELETE FROM t_feature_sign_off_extension "
                + "WHERE sign_off_id = ?", signOff), SIGNAL, "t_feature_sign_off_extension is append-only");
    }

    @Test
    void 履歴は追記専用で更新と削除がtriggerで拒否される() {
        String tenant = tenant();
        jdbc.update("INSERT INTO t_feature_enablement_event (tenant_id, subject_type, subject_key, event_type, actor_type, "
                + "generation, occurred_at) VALUES (?, 'FEATURE', 'NF01', 'REQUESTED', 'USER', 1, NOW(6))", tenant);
        MySqlConstraintRejections.assertRejectedBy(() -> jdbc.update("UPDATE t_feature_enablement_event SET reason_code = 'X' "
                + "WHERE tenant_id = ?", tenant), SIGNAL, "t_feature_enablement_event is append-only");
        MySqlConstraintRejections.assertRejectedBy(() -> jdbc.update("DELETE FROM t_feature_enablement_event "
                + "WHERE tenant_id = ?", tenant), SIGNAL, "t_feature_enablement_event is append-only");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_feature_enablement_event WHERE tenant_id = ?", Integer.class,
                tenant)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- helper

    private static String tenant() {
        return "fe-" + UUID.randomUUID();
    }

    private static long request(String tenant, String target, long requestedBy) {
        jdbc.update("INSERT INTO t_feature_enablement_request (tenant_id, request_type, target_key, status, "
                + "condition_fingerprint, condition_summary, requested_by, requested_at, expires_at) VALUES (?, 'OPEN', ?, "
                + "'PENDING', ?, 'ok', ?, NOW(6), NOW(6))", tenant, target, "0".repeat(64), requestedBy);
        return jdbc.queryForObject("SELECT MAX(id) FROM t_feature_enablement_request WHERE tenant_id = ? AND target_key = ?",
                Long.class, tenant, target);
    }

    private static long approvedRequest(String tenant, String target, long requestedBy, long decidedBy) {
        long id = request(tenant, target, requestedBy);
        jdbc.update("UPDATE t_feature_enablement_request SET status = 'APPROVED', decided_by = ?, decided_at = NOW(6) "
                + "WHERE id = ?", decidedBy, id);
        return id;
    }

    private static int state(String tenant, String feature, String value, long requestId, long openedBy, long approvedBy) {
        return jdbc.update("INSERT INTO t_feature_enablement_state (tenant_id, feature_key, state, open_request_id, "
                + "opened_by, approved_by, opened_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, NOW(6), NOW(6))", tenant, feature,
                value, requestId, openedBy, approvedBy);
    }

    private static int setting(String tenant, long requestId, long requestedBy, long approvedBy) {
        return jdbc.update("INSERT INTO t_feature_enablement_setting (tenant_id, setting_key, setting_value, request_id, "
                + "requested_by, approved_by, approved_at) VALUES (?, 'nf02-customer-portal.visible-since', NULL, ?, ?, ?, "
                + "NOW(6))", tenant, requestId, requestedBy, approvedBy);
    }

    private static long signOff(String tenant, String kind, String status, long registeredBy, Long confirmedBy) {
        String stored = UUID.randomUUID().toString().replace("-", "") + ".pdf";
        jdbc.update("INSERT INTO t_feature_sign_off (tenant_id, kind_code, reference, signer_role, signer_name, signed_on, "
                + "expires_on, kind_class, content_sha256, size_bytes, stored_name, original_name, status, registered_by, "
                + "registered_at, confirmed_by, confirmed_at) VALUES (?, ?, 'REF-1', 'SRE', '署名者', '2026-11-20', "
                + "'2027-11-20', 'EVIDENCE', ?, 10, ?, 'original.pdf', ?, ?, NOW(6), ?, ?)", tenant, kind, "a".repeat(64),
                stored, status, registeredBy, confirmedBy, confirmedBy == null ? null : java.time.LocalDateTime.now());
        return jdbc.queryForObject("SELECT id FROM t_feature_sign_off WHERE stored_name = ?", Long.class, stored);
    }

    private static long bundle(String tenant, String status, long registeredBy, Long confirmedBy) {
        String kind = "TEST_BUNDLE";
        jdbc.update("INSERT INTO t_feature_sign_off_bundle (tenant_id, bundle_kind, status, registered_by, registered_at, "
                + "confirmed_by, confirmed_at) VALUES (?, ?, ?, ?, NOW(6), ?, ?)", tenant, kind, status, registeredBy,
                confirmedBy, confirmedBy == null ? null : java.time.LocalDateTime.now());
        return jdbc.queryForObject("SELECT MAX(id) FROM t_feature_sign_off_bundle WHERE tenant_id = ?", Long.class, tenant);
    }

    private static int member(long bundle, String tenant, String kind, long signOff) {
        return jdbc.update("INSERT INTO t_feature_sign_off_bundle_member (bundle_id, tenant_id, kind_code, sign_off_id, "
                + "carried_over) VALUES (?, ?, ?, ?, 0)", bundle, tenant, kind, signOff);
    }

    private static int extension(String tenant, long signOff, long request, String newExpires, long requestedBy,
                                 long confirmedBy) {
        return jdbc.update("INSERT INTO t_feature_sign_off_extension (tenant_id, sign_off_id, request_id, old_expires_on, "
                + "new_expires_on, requested_by, confirmed_by, confirmed_at) VALUES (?, ?, ?, '2027-11-20', ?, ?, ?, NOW(6))",
                tenant, signOff, request, newExpires, requestedBy, confirmedBy);
    }
}
