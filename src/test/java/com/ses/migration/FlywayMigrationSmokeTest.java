package com.ses.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 実MySQL上で db/migration のFlywayマイグレーションを「空DBから通しで」適用できることを検証する
 * スモークテスト。
 *
 * 通常のテストスイートは H2 + Flyway無効(spring.flyway.enabled=false, spring.sql.initで個別投入)で
 * 動くため、以下のクラスの不具合を検出できない:
 *   - MySQL方言依存の構文
 *   - マイグレーション間の不整合(例: V1とV3/V8の重複ADD COLUMN → Duplicate column)
 *   - 結合先カラム名の誤り(例: sys_user.full_name は存在しない)
 * 本テストがそのブラインドスポットを埋める。
 *
 * Dockerが利用できない環境では {@code @Testcontainers(disabledWithoutDocker = true)} により
 * 自動的にスキップされるため、通常の {@code mvn test} を壊さない。CI(Docker有)で実行される。
 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationSmokeTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_db")
            .withUsername("ses")
            .withPassword("ses");

    @Test
    void 空DBから全マイグレーションを通しで適用でき期待スキーマになる() throws Exception {
        // 空DBからの全マイグレーション適用(dev プロファイルの empty-DB 起動と同等)。
        // baseline は行わず V1 から順に適用する。SQL不整合があればここで例外→テスト失敗。
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection conn = MYSQL.createConnection(""); Statement st = conn.createStatement()) {
            // 不具合1(ユーザー管理の一覧空表示)の回帰: sys_user に両列があること
            assertColumnExists(st, "sys_user", "failed_count");
            assertColumnExists(st, "sys_user", "locked_until");

            // 契約帰属・インセンティブ個別設定カラム(V14)
            assertColumnExists(st, "t_contract", "sales_user_id");
            assertColumnExists(st, "t_contract", "commission_base_type");
            assertColumnExists(st, "t_contract", "commission_rate");

            // 要員担当営業テーブル(V14)
            assertTableExists(st, "t_engineer_sales");

            // メニューseedとインセンティブ既定規則(V14)
            assertRowExists(st, "SELECT 1 FROM m_menu WHERE menu_key='sales-performance'");
            assertRowExists(st, "SELECT 1 FROM m_system_config WHERE config_key='commission.base-type'");
            assertRowExists(st, "SELECT 1 FROM m_system_config WHERE config_key='commission.rate'");

            // Webhook設定(V15)
            assertRowExists(st, "SELECT 1 FROM m_system_config WHERE config_key='notification.webhook-url'");
            assertRowExists(st, "SELECT 1 FROM m_system_config WHERE config_key='notification.webhook-types'");

            // freee給与連携(V21)
            assertTableExists(st, "t_freee_connection");
            assertTableExists(st, "t_freee_employee_link");
            assertRowExists(st, "SELECT 1 FROM m_menu WHERE menu_key='payroll'");

            // 契約書テンプレート・電子署名(V20)
            assertTableExists(st, "m_contract_template");
            assertTableExists(st, "t_contract_document");
            assertRowExists(st, "SELECT 1 FROM m_menu WHERE menu_key='contract-document'");

            // 候補者管理テーブル(V16)
            assertTableExists(st, "t_candidate");
            assertTableExists(st, "t_candidate_activity");
            assertRowExists(st, "SELECT 1 FROM m_menu WHERE menu_key='candidate'");

            // 請求書への適用税率保存カラム(V27)
            assertColumnExists(st, "t_invoice", "tax_rate");

            // 請求書入金テーブル(V28 / ar-management)
            assertTableExists(st, "t_invoice_payment");
            assertColumnExists(st, "t_invoice_payment", "amount");
            assertColumnExists(st, "t_invoice_payment", "fee");
            // status ENUM に「一部入金」が追加されていること
            assertRowExists(st, "SELECT 1 FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name='t_invoice' AND column_name='status' "
                    + "AND column_type LIKE '%一部入金%'");

            // 見積(V29 / quotation-management)
            assertTableExists(st, "t_quotation");
            assertColumnExists(st, "t_contract", "quotation_id");
            assertRowExists(st, "SELECT 1 FROM m_menu WHERE menu_key='quotation'");

            // 月次締めメニュー(V31 / monthly-closing-checklist)
            assertRowExists(st, "SELECT 1 FROM m_menu WHERE menu_key='monthly-closing'");

            // 要員セルフサービス勤怠(V32 / engineer-self-service-timesheet)
            assertTableExists(st, "t_engineer_account_link");
            assertTableExists(st, "t_work_record_daily");
            assertColumnExists(st, "t_work_record_daily", "worked_hours");
            assertRowExists(st, "SELECT 1 FROM m_menu WHERE menu_key='my-timesheet'");
            // role ENUM に「要員」、work_record status ENUM に「提出済」「差戻し」
            assertRowExists(st, "SELECT 1 FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name='sys_user' AND column_name='role' "
                    + "AND column_type LIKE '%要員%'");
            assertRowExists(st, "SELECT 1 FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name='t_work_record' AND column_name='status' "
                    + "AND column_type LIKE '%差戻し%'");

            // 契約単価改定履歴(V33 / contract-price-history)
            assertTableExists(st, "t_contract_price_history");
            assertColumnExists(st, "t_contract_price_history", "apply_from_month");

            // メール配信の invoice_id と索引(R3R-01: V26 CREATEへ内包・V15重複解消)。索引は一度だけ。
            assertColumnExists(st, "t_mail_delivery", "invoice_id");
            assertRowExists(st, "SELECT 1 FROM (SELECT COUNT(*) c FROM information_schema.statistics "
                    + "WHERE table_schema=DATABASE() AND table_name='t_mail_delivery' "
                    + "AND index_name='idx_mail_delivery_invoice') t WHERE c > 0");

            // 通知宛先(R3R-02: V36が唯一の追加。V37は reject_comment のみ)。索引は一度だけ。
            assertColumnExists(st, "t_notification", "recipient_user_id");
            assertColumnExists(st, "t_work_record", "reject_comment");
            assertRowExists(st, "SELECT 1 FROM (SELECT COUNT(DISTINCT index_name) c FROM information_schema.statistics "
                    + "WHERE table_schema=DATABASE() AND table_name='t_notification' "
                    + "AND index_name='idx_notification_recipient') t WHERE c = 1");

            // 工数精度の統一(R3R-13: 日次・月次とも小数2桁)。
            assertRowExists(st, "SELECT 1 FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name='t_work_record' AND column_name='actual_hours' "
                    + "AND numeric_scale=2");
            assertRowExists(st, "SELECT 1 FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name='t_work_record_daily' AND column_name='worked_hours' "
                    + "AND numeric_scale=2");

            // 督促テンプレートseed(R3R-19)。
            assertRowExists(st, "SELECT 1 FROM m_email_template WHERE template_name='請求督促メール'");

            // 一意性制約カラム(V18) - 生成列の検証
            assertColumnExists(st, "t_bp_payment", "active_work_record_id");
            assertColumnExists(st, "t_bp_payment", "active_layer_order");
            assertColumnExists(st, "t_contract", "active_proposal_id");
            assertColumnExists(st, "t_contract", "active_renewed_from_contract_id");

            // 契約一覧の担当営業join(su.real_name)が実MySQLで実行可能なこと(full_name誤りの回帰)
            try (ResultSet rs = st.executeQuery(
                    "SELECT c.id, su.real_name AS salesUserName FROM t_contract c " +
                    "LEFT JOIN sys_user su ON c.sales_user_id = su.id AND su.deleted_flag = 0 " +
                    "WHERE c.deleted_flag = 0 LIMIT 1")) {
                rs.next(); // 例外なく実行できればよい(0件でも可)
            }
        }
    }

    private void assertColumnExists(Statement st, String table, String column) throws Exception {
        assertRowExists(st, "SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()"
                + " AND table_name='" + table + "' AND column_name='" + column + "'");
    }

    private void assertTableExists(Statement st, String table) throws Exception {
        assertRowExists(st, "SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE()"
                + " AND table_name='" + table + "'");
    }

    private void assertRowExists(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "行が存在するはず: " + sql);
        }
    }
}
