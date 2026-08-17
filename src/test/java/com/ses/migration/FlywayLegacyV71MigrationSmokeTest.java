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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公開済みlatest（V71 = BP master/発注コンプライアンス）まで適用済みの既存DBへ、
     * V73/V74/V74.1/V74.2/V74.3（CRM複数担当者・商機管理とforward fix）を追加適用できることを検証する legacy smoke。
 *
 * <p>platform-invariants §4.2 の「freshの成功をlegacyの合格とみなさない」に対応する。
 * V73はCRMの定義をV1統合baselineへ書き戻さない設計なので、
 * 「V1が作る（fresh）」「V73が作る（legacy）」の分岐が無く、両経路が同一スキーマへ収束する。
     * 本testはlegacy側でV73後の補完がRepeatable履歴に依存せず、既存contactのbackfillまで効くことを固定する。
 *
 * <p>Dockerが無い環境ではTestcontainersの規約によりskipする。
 */
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class FlywayLegacyV71MigrationSmokeTest {

    @Container
    @SuppressWarnings("resource") // ライフサイクルは Testcontainers Extension が管理する。
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_legacy_v71")
            .withUsername("root")
            .withPassword("ses");

    @Test
    void V71適用済みDBへV73だけを追加適用できCRMスキーマと移行が揃う() throws Exception {
        // 1) 公開済みlatest（V71）までの既存DBを作る
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("71")
                .load()
                .migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            // V73適用前はCRMの一切が存在しない（＝V1へ書き戻していないことの裏取り）
            assertTableAbsent(statement, "t_customer_contact");
            assertTableAbsent(statement, "t_lead");
            assertTableAbsent(statement, "t_opportunity");
            assertColumnAbsent(statement, "t_sales_activity", "contact_id");
            assertColumnAbsent(statement, "t_project", "source_opportunity_id");
            statement.execute("INSERT INTO m_customer (company_name, contact_person, contact_email, contact_phone, deleted_flag) VALUES "
                    + "('email-only legacy', NULL, 'email-only@example.com', NULL, 0),"
                    + "('phone-only legacy', NULL, NULL, '03-1234-5678', 0),"
                    + "('empty legacy', NULL, NULL, NULL, 0)");
        }

        // 2) V73までを追加適用し、V74.2/V74.3適用前の既存leadをfixtureへ入れる
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("73")
                .load()
                .migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO t_lead (company_name, contact_email, contact_phone, source, status, deleted_flag) VALUES "
                    + "('ＦＵＬＬＷＩＤＴＨ株式会社', 'ｔｅｓｔ＠ｅｘａｍｐｌｅ．ｃｏｍ', '０３－１２３４－５６７８', 'legacy', '未対応', 0),"
                    + "('空キー会社', '－－', '－－', 'legacy', '未対応', 0)");
        }

        // 3) V74.2/V74.3を含むlatestまでを適用する
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            assertTableExists(statement, "t_customer_contact");
            assertTableExists(statement, "t_lead");
            assertTableExists(statement, "t_opportunity");

            assertColumnExists(statement, "t_sales_activity", "contact_id");
            assertColumnExists(statement, "t_sales_activity", "opportunity_id");
            assertColumnExists(statement, "t_sales_activity", "assignee_user_id");
            assertColumnExists(statement, "t_sales_activity", "version");
            assertColumnExists(statement, "t_project", "source_opportunity_id");
            assertColumnExists(statement, "t_quotation", "source_opportunity_id");
            assertColumnExists(statement, "t_opportunity", "stage_changed_at");
            assertColumnExists(statement, "t_opportunity", "probability_override_reason");
            assertColumnExists(statement, "t_lead", "source_cost");
            assertNumericColumn(statement, "t_lead", "source_cost", 14, 0);
            assertColumnExists(statement, "t_lead", "company_name_normalized");
            assertColumnExists(statement, "t_lead", "contact_email_normalized");
            assertColumnExists(statement, "t_lead", "contact_phone_normalized");
            assertColumnExists(statement, "t_proposal", "source_opportunity_id");
            assertColumnExists(statement, "t_mail_delivery", "contact_id");
            assertColumnExists(statement, "t_mail_delivery", "opportunity_id");

            // FK集合が fresh と一致すること（Round 1 の CRM-R1-P1-02）
            assertConstraintExists(statement, "t_customer_contact", "fk_customer_contact_customer");
            assertConstraintExists(statement, "t_lead", "fk_lead_owner");
            assertConstraintExists(statement, "t_lead", "fk_lead_converted_customer");
            assertConstraintExists(statement, "t_opportunity", "fk_opportunity_customer");
            assertConstraintExists(statement, "t_opportunity", "fk_opportunity_owner");
            assertConstraintExists(statement, "t_opportunity", "fk_opportunity_project");
            assertConstraintExists(statement, "t_opportunity", "fk_opportunity_quotation");
            assertConstraintExists(statement, "t_sales_activity", "fk_activity_contact");
            assertConstraintExists(statement, "t_sales_activity", "fk_activity_opportunity");
            assertConstraintExists(statement, "t_sales_activity", "fk_activity_assignee");
            assertConstraintExists(statement, "t_project", "fk_project_source_opportunity");
            assertConstraintExists(statement, "t_quotation", "fk_quotation_source_opportunity");

            assertIndexExists(statement, "t_project", "uk_project_source_opportunity");
            assertIndexExists(statement, "t_quotation", "uk_quotation_source_opportunity");
            assertIndexExists(statement, "t_customer_contact", "uk_customer_contact_active_primary");
            assertIndexExists(statement, "t_mail_delivery", "idx_mail_delivery_contact");
            assertIndexExists(statement, "t_mail_delivery", "idx_mail_delivery_opportunity");
            assertIndexExists(statement, "t_lead", "idx_lead_company_normalized");
            assertIndexExists(statement, "t_lead", "idx_lead_email_normalized");
            assertIndexExists(statement, "t_lead", "idx_lead_phone_normalized");
            assertNormalizedLegacyLead(statement);

            // backfill: 既存顧客の担当者が件数・値とも一致して移行される
            assertEquals(countCustomersWithContact(statement), countMigratedContacts(statement),
                    "contact_personを持つ顧客の件数と移行されたcontactの件数が一致するはず");
            try (ResultSet rs = statement.executeQuery(
                    "SELECT COUNT(*) FROM t_customer_contact cc JOIN m_customer c ON c.id = cc.customer_id"
                            + " WHERE cc.name <> c.contact_person"
                            + " OR NOT (cc.email <=> c.contact_email)"
                            + " OR NOT (cc.phone <=> c.contact_phone)")) {
                assertTrue(rs.next() && rs.getLong(1) == 0, "移行後の名称/email/電話が移行前と一致するはず");
            }
            try (ResultSet rs = statement.executeQuery(
                    "SELECT COUNT(*) FROM t_customer_contact cc JOIN m_customer c ON c.id = cc.customer_id"
                            + " WHERE c.company_name IN ('email-only legacy','phone-only legacy')"
                            + " AND cc.name='顧客担当者' AND cc.primary_flag=1"
                            + " AND ((c.company_name='email-only legacy' AND cc.email='email-only@example.com' AND cc.phone IS NULL)"
                            + " OR (c.company_name='phone-only legacy' AND cc.email IS NULL AND cc.phone='03-1234-5678'))")) {
                assertTrue(rs.next() && rs.getLong(1) == 2, "email-only/phone-only legacyも既定contactへ移行されるはず");
            }
            try (ResultSet rs = statement.executeQuery(
                    "SELECT COUNT(*) FROM t_customer_contact cc JOIN m_customer c ON c.id = cc.customer_id"
                            + " WHERE c.company_name='empty legacy'")) {
                assertTrue(rs.next() && rs.getLong(1) == 0, "全NULL legacy顧客はcontactを生成しないはず");
            }
            try (ResultSet rs = statement.executeQuery(
                    "SELECT COUNT(*) FROM t_customer_contact WHERE valid_from > CURDATE()")) {
                assertTrue(rs.next() && rs.getLong(1) == 0, "移行行のvalid_fromが未来日になってはいけない");
            }

            // メニュー登録（design §6.2: 管理者/マネージャー/営業のみ）
            try (ResultSet rs = statement.executeQuery(
                    "SELECT COUNT(*) FROM t_role_menu rm JOIN m_menu m ON m.id = rm.menu_id"
                            + " WHERE m.menu_key IN ('crm-lead','crm-opportunity')")) {
                assertTrue(rs.next() && rs.getLong(1) == 6, "CRMメニュー2件 × 営業系3ロール = 6行");
            }
        }

        // 4) validateと二回目migrateで壊れない（V74.3を含む正規化の正規経路も確認）
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .validate();
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private long countCustomersWithContact(Statement statement) throws Exception {
        try (ResultSet rs = statement.executeQuery(
                "SELECT COUNT(*) FROM m_customer WHERE deleted_flag = 0"
                        + " AND (NULLIF(TRIM(contact_person), '') IS NOT NULL"
                        + " OR NULLIF(TRIM(contact_email), '') IS NOT NULL"
                        + " OR NULLIF(TRIM(contact_phone), '') IS NOT NULL)")) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private long countMigratedContacts(Statement statement) throws Exception {
        try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM t_customer_contact")) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private void assertNormalizedLegacyLead(Statement statement) throws Exception {
        try (ResultSet rs = statement.executeQuery(
                "SELECT COUNT(*) FROM t_lead WHERE company_name='ＦＵＬＬＷＩＤＴＨ株式会社'"
                        + " AND company_name_normalized='fullwidth株式会社'"
                        + " AND contact_email_normalized='test@example.com'"
                        + " AND contact_phone_normalized='0312345678'")) {
            assertTrue(rs.next() && rs.getLong(1) == 1,
                    "V74.3は既存leadへ実行時と同じNFKC正規化を適用するはず");
        }
        try (ResultSet rs = statement.executeQuery(
                "SELECT COUNT(*) FROM t_lead WHERE company_name='空キー会社'"
                        + " AND contact_email_normalized IS NULL AND contact_phone_normalized IS NULL")) {
            assertTrue(rs.next() && rs.getLong(1) == 1,
                    "正規化後に空文字となるemail/phoneはNULLへ収束するはず");
        }
    }

    private void assertTableExists(Statement statement, String table) throws Exception {
        try (ResultSet rs = statement.executeQuery(
                "SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE()"
                        + " AND table_name='" + table + "'")) {
            assertTrue(rs.next(), table + " が存在するはず");
        }
    }

    private void assertTableAbsent(Statement statement, String table) throws Exception {
        try (ResultSet rs = statement.executeQuery(
                "SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE()"
                        + " AND table_name='" + table + "'")) {
            assertTrue(!rs.next(), table + " はV73適用前には存在しないはず");
        }
    }

    private void assertColumnExists(Statement statement, String table, String column) throws Exception {
        try (ResultSet rs = statement.executeQuery(
                "SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()"
                        + " AND table_name='" + table + "' AND column_name='" + column + "'")) {
            assertTrue(rs.next(), table + "." + column + " が存在するはず");
        }
    }

    private void assertColumnAbsent(Statement statement, String table, String column) throws Exception {
        try (ResultSet rs = statement.executeQuery(
                "SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()"
                        + " AND table_name='" + table + "' AND column_name='" + column + "'")) {
            assertTrue(!rs.next(), table + "." + column + " はV73適用前には存在しないはず");
        }
    }

    private void assertConstraintExists(Statement statement, String table, String constraint) throws Exception {
        try (ResultSet rs = statement.executeQuery(
                "SELECT 1 FROM information_schema.table_constraints WHERE table_schema=DATABASE()"
                        + " AND table_name='" + table + "' AND constraint_name='" + constraint + "'")) {
            assertTrue(rs.next(), table + "." + constraint + " が存在するはず");
        }
    }

    private void assertIndexExists(Statement statement, String table, String index) throws Exception {
        try (ResultSet rs = statement.executeQuery(
                "SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()"
                        + " AND table_name='" + table + "' AND index_name='" + index + "'")) {
            assertTrue(rs.next(), table + "." + index + " が存在するはず");
        }
    }

    private void assertNumericColumn(Statement statement, String table, String column,
                                     int precision, int scale) throws Exception {
        try (ResultSet rs = statement.executeQuery(
                "SELECT numeric_precision, numeric_scale FROM information_schema.columns"
                        + " WHERE table_schema=DATABASE() AND table_name='" + table
                        + "' AND column_name='" + column + "'")) {
            assertTrue(rs.next(), table + "." + column + " の数値型定義が存在するはず");
            assertEquals(precision, rs.getInt(1));
            assertEquals(scale, rs.getInt(2));
        }
    }
}
