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
    @SuppressWarnings("resource") // ライフサイクルは Testcontainers Extension が管理する。
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

            // メール配信の invoice_id と索引(R3R-01: 重複V15を解消しV38で追加)。索引は一度だけ。
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

            // 外部要員空き状況管理(V45)
            assertTableExists(st, "t_bp_availability");
            assertTableExists(st, "t_bp_availability_ingestion");
            assertRowExists(st, "SELECT 1 FROM m_menu WHERE menu_key='bp-availability'");
            assertRowExists(st, "SELECT 1 FROM m_menu WHERE menu_key='bp-availability-ingestion'");

            // 資金繰り予測(V46, V48, V49) と スキルシートテンプレート(V55)
            assertRowExists(st, "SELECT 1 FROM m_system_config WHERE config_key='cashflow.opening-balance'");
            assertRowExists(st, "SELECT 1 FROM m_system_config WHERE config_key='cashflow.bp-payment-site-months'");
            assertRowExists(st, "SELECT 1 FROM m_system_config WHERE config_key='cashflow.payroll-employer-burden-rate'");
            assertRowExists(st, "SELECT 1 FROM m_system_config WHERE config_key='skillsheet.templates'");

            // 要員フォロー・定着リスク管理(V54, FR-11)
            assertTableExists(st, "t_engineer_followup");
            assertColumnExists(st, "t_engineer_followup", "next_date");
            assertRowExists(st, "SELECT 1 FROM m_system_config WHERE config_key='retention.risk.bench-warn-days'");
            assertRowExists(st, "SELECT 1 FROM m_system_config WHERE config_key='retention.risk.followup-interval-days'");
            assertRowExists(st, "SELECT 1 FROM m_system_config WHERE config_key='retention.risk.threshold'");

            // 組織・管理会計基盤(V60)
            assertTableExists(st, "m_organization_unit");
            assertTableExists(st, "t_user_organization");
            assertTableExists(st, "m_cost_center");
            assertTableExists(st, "t_management_budget");
            assertTableExists(st, "t_monthly_accounting_dimension");
            assertRowExists(st, "SELECT 1 FROM m_menu WHERE menu_key='organization'");
            assertRowExists(st, "SELECT 1 FROM m_menu WHERE menu_key='management-accounting'");
            assertRowExists(st, "SELECT 1 FROM t_role_menu rm JOIN m_menu m ON m.id=rm.menu_id "
                    + "WHERE rm.role='マネージャー' AND m.menu_key='organization'");
            assertColumnExists(st, "m_organization_unit", "version");
            assertColumnExists(st, "t_management_budget", "gross_profit");
            // 帰属の一次情報。アカウント連携任せにすると大半の実績が未配賦になる。
            assertColumnExists(st, "t_engineer", "organization_id");
            assertColumnExists(st, "t_contract", "cost_center_id");
            assertColumnExists(st, "t_invoice", "cost_center_id");
            assertColumnExists(st, "t_bp_payment", "cost_center_id");
            assertColumnExists(st, "t_notification", "organization_id");
            // 履歴テーブル(V61)。asOf解決の版元が無いと過去日の照会が現在値へ落ちる。
            assertColumnExists(st, "t_organization_relation_history", "parent_id");
            assertColumnExists(st, "t_organization_relation_history", "status");
            assertColumnExists(st, "t_organization_relation_history", "valid_from");
            assertColumnExists(st, "t_engineer_accounting_history", "cost_center_id");
            assertColumnExists(st, "t_engineer_accounting_history", "expected_unit_price");
            assertColumnExists(st, "t_engineer_accounting_history", "organization_id");
            assertColumnExists(st, "t_engineer_accounting_history", "organization_history_status");
            assertColumnExists(st, "t_engineer_accounting_history", "valid_from");
            // LEGACY組織はV60で作られるので、V61のbackfillで履歴の初版も必ず生まれる。
            assertRowExists(st, "SELECT 1 FROM t_organization_relation_history h "
                    + "JOIN m_organization_unit o ON o.id = h.organization_id WHERE o.code='LEGACY'");
            // 業務一意制約（生成列を含む）がMySQL 8で実在すること。
            assertIndexExists(st, "m_organization_unit", "uk_organization_code");
            assertIndexExists(st, "t_user_organization", "uk_user_org_active_primary");
            assertIndexExists(st, "t_user_organization", "uk_user_org_period");
            assertIndexExists(st, "t_management_budget", "uk_management_budget");
            // 「有効な主所属はユーザーごとに1件」がDBでも効くこと。
            assertRowExists(st, "SELECT 1 FROM m_organization_unit WHERE code='LEGACY'");
            st.execute("INSERT INTO t_user_organization (user_id, organization_id, primary_flag, valid_from) "
                    + "SELECT u.id, o.id, 1, '2026-01-01' FROM sys_user u, m_organization_unit o "
                    + "WHERE u.role='管理者' AND o.code='LEGACY' LIMIT 1");
            boolean duplicateRejected = false;
            try {
                st.execute("INSERT INTO t_user_organization (user_id, organization_id, primary_flag, valid_from) "
                        + "SELECT u.id, o.id, 1, '2026-02-01' FROM sys_user u, m_organization_unit o "
                        + "WHERE u.role='管理者' AND o.code='LEGACY' LIMIT 1");
            } catch (java.sql.SQLException expected) {
                duplicateRejected = true;
            }
            org.junit.jupiter.api.Assertions.assertTrue(duplicateRejected,
                    "有効な主所属の二重登録はDBのUNIQUEでも拒否されるはず");

            // 企業認証・MFA・session・action permission・file scan metadata(V63)
            assertTableExists(st, "m_identity_provider");
            assertTableExists(st, "t_user_external_identity");
            assertTableExists(st, "t_user_mfa");
            assertTableExists(st, "t_mfa_recovery_code");
            assertTableExists(st, "t_user_session");
            assertTableExists(st, "m_permission_group");
            assertTableExists(st, "t_user_permission_group");
            assertTableExists(st, "t_permission_group_action");
            assertTableExists(st, "t_file_security_metadata");
            assertColumnExists(st, "m_identity_provider", "issuer_uri");
            assertColumnExists(st, "t_user_external_identity", "subject");
            assertColumnExists(st, "t_user_mfa", "encrypted_totp_secret");
            assertColumnExists(st, "t_mfa_recovery_code", "code_hash");
            assertColumnExists(st, "t_user_session", "revoked_at");
            assertColumnExists(st, "t_permission_group_action", "action_key");
            assertColumnExists(st, "t_file_security_metadata", "scan_status");
            assertColumnExists(st, "t_file_security_metadata", "scanner_version");
            assertIndexExists(st, "m_identity_provider", "uk_identity_provider_issuer");
            assertIndexExists(st, "t_user_external_identity", "uk_external_identity_subject");
            assertIndexExists(st, "t_user_mfa", "uk_user_mfa_user");
            assertIndexExists(st, "t_mfa_recovery_code", "uk_mfa_recovery_code_hash");
            assertIndexExists(st, "t_user_session", "uk_user_session_hash");
            assertIndexExists(st, "m_permission_group", "uk_permission_group_key");
            assertIndexExists(st, "t_user_permission_group", "uk_user_permission_group");
            assertIndexExists(st, "t_permission_group_action", "uk_permission_group_action");
            assertIndexExists(st, "t_file_security_metadata", "uk_file_security_stored_name");
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM t_user_permission_group upg "
                            + "JOIN sys_user u ON u.id = upg.user_id "
                            + "JOIN m_permission_group g ON g.id = upg.group_id "
                            + "WHERE u.username = 'admin' AND g.group_key = 'role-admin' "
                            + "AND upg.deleted_flag = 0")) {
                org.junit.jupiter.api.Assertions.assertTrue(rs.next() && rs.getLong(1) == 1,
                        "既存adminがrole-admin groupへseedされるはず");
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM t_permission_group_action a "
                            + "JOIN m_permission_group g ON g.id = a.group_id "
                            + "WHERE g.group_key = 'role-sales' "
                            + "AND a.action_key IN ('contract.cost.view', 'export.execute') "
                            + "AND a.deleted_flag = 0")) {
                org.junit.jupiter.api.Assertions.assertTrue(rs.next() && rs.getLong(1) == 2,
                        "既存営業roleの原価閲覧・export後方互換actionがseedされるはず");
            }

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

    private void assertIndexExists(Statement st, String table, String index) throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()"
                        + " AND table_name='" + table + "' AND index_name='" + index + "'")) {
            org.junit.jupiter.api.Assertions.assertTrue(rs.next(), table + "." + index + " が存在するはず");
        }
    }

    private void assertRowExists(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "行が存在するはず: " + sql);
        }
    }
}
