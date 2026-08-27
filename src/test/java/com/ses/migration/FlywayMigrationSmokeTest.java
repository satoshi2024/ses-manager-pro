package com.ses.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import com.ses.test.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationSmokeTest {

    @Container
    @SuppressWarnings("resource") // ライフサイクルは Testcontainers Extension が管理する。
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_db")
            .withUsername("root")
            .withPassword("ses");

    /** V77適用済みlegacy DBへ、V78の成功/停止両経路を適用する専用container。 */
    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> LEGACY_V78 = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_legacy_v78")
            .withUsername("root")
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

            // HFP-01-002 (V102_4): 事業所境界と接続状態
            assertColumnExists(st, "t_freee_connection", "connection_status");
            assertColumnExists(st, "t_freee_employee_link", "freee_company_id");
            assertIndexExists(st, "t_freee_employee_link", "uk_freee_link_company_employee");
            // 旧employee単独UNIQUEは置換済みで存在しないこと
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.statistics"
                            + " WHERE table_schema=DATABASE() AND table_name='t_freee_employee_link'"
                            + " AND index_name='uk_freee_link_employee'")) {
                org.junit.jupiter.api.Assertions.assertTrue(rs.next() && rs.getLong(1) == 0,
                        "旧 uk_freee_link_employee はV102_4で削除されているはず");
            }

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

            // BP会社マスタ・発注コンプライアンス(V70 / bp-company-master-procurement-compliance)
            assertTableExists(st, "m_bp_company");
            assertTableExists(st, "t_bp_contact");
            assertTableExists(st, "t_bp_bank_account");
            assertTableExists(st, "t_bp_terms");
            assertTableExists(st, "t_engineer_bp_affiliation");
            assertTableExists(st, "t_bp_evaluation");
            assertTableExists(st, "t_bp_price_negotiation");
            assertColumnExists(st, "m_bp_company", "normalized_name");
            assertColumnExists(st, "m_bp_company", "compliance_applicability");
            assertColumnExists(st, "m_bp_company", "version");
            assertColumnExists(st, "t_bp_bank_account", "encrypted_account_number");
            assertColumnExists(st, "t_bp_bank_account", "masked_label");
            assertColumnExists(st, "t_bp_terms", "fee_bearer_exception_reason");
            assertColumnExists(st, "t_bp_terms", "fee_bearer_approved_by");
            assertColumnExists(st, "t_bp_availability", "bp_company_id");
            assertColumnExists(st, "t_bp_payment", "bp_company_id");
            assertColumnExists(st, "t_bp_payment", "bp_company_name_snapshot");
            assertColumnExists(st, "t_bp_payment", "terms_snapshot_json");
            assertColumnExists(st, "t_contract", "contract_date");
            assertColumnExists(st, "t_contract", "job_description");
            assertColumnExists(st, "t_contract", "work_location");
            assertColumnExists(st, "t_contract", "inspection_due_date");
            assertColumnExists(st, "t_contract", "payment_due_date");
            assertColumnExists(st, "t_contract", "payment_method");
            assertRowExists(st, "SELECT 1 FROM m_menu WHERE menu_key='bp-company'");
            assertRowExists(st, "SELECT 1 FROM t_role_menu rm JOIN m_menu m ON m.id=rm.menu_id "
                    + "WHERE rm.role='管理者' AND m.menu_key='bp-company'");
            assertRowExists(st, "SELECT 1 FROM m_system_config WHERE config_key='procurement.payment-max-days'");
            assertIndexExists(st, "m_bp_company", "uk_bp_company_normalized");

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
            // 本番MySQLではmanager_user_idの不正IDを直接fixtureできないため、FKを実測する。
            // H2の共有schemaはFKなしのため、resolverのmapper境界回帰はH2側で別途検証する。
            assertForeignKeyExists(st, "t_user_organization", "fk_user_org_manager",
                    "manager_user_id", "sys_user", "id");
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
            assertColumnExists(st, "t_break_glass_incident", "allowed_actions");
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
            assertColumnExists(st, "t_permission_group_action", "deny_flag");
            // V66.1: 全局wildcardは管理者だけに限定し、未知actionを既定拒否する。
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM t_permission_group_action a "
                            + "JOIN m_permission_group g ON g.id = a.group_id "
                            + "WHERE g.group_key IN ('role-admin','role-sales','role-hr','role-manager') "
                            + "AND a.action_key = '*' AND a.deny_flag = 0 AND a.deleted_flag = 0")) {
                org.junit.jupiter.api.Assertions.assertTrue(rs.next() && rs.getLong(1) == 1,
                        "全局wildcardは管理者だけにseedされるはず");
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM t_permission_group_action a "
                            + "JOIN m_permission_group g ON g.id = a.group_id "
                            + "WHERE g.group_key IN ('role-sales','role-hr','role-manager') "
                            + "AND a.action_key IN ('dashboard.*','proposal.*','notifications.*') "
                            + "AND a.deny_flag = 0 AND a.deleted_flag = 0")) {
                org.junit.jupiter.api.Assertions.assertTrue(rs.next() && rs.getLong(1) == 9,
                        "非管理者には既知resourceだけが明示seedされるはず");
            }
            // V66: role-managerの機密actionは拒否指定で外す。
            // V64はこの拒否が無く、action層がuser.*やpayroll.viewまで素通しだった。
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM t_permission_group_action a "
                            + "JOIN m_permission_group g ON g.id = a.group_id "
                            + "WHERE g.group_key = 'role-manager' AND a.deny_flag = 1 "
                            + "AND a.action_key IN ('user.*','permission.manage','payroll.view',"
                            + "'audit.security.view','file.scan.retry') AND a.deleted_flag = 0")) {
                org.junit.jupiter.api.Assertions.assertTrue(rs.next() && rs.getLong(1) == 5,
                        "マネージャーの機密actionが拒否指定されるはず");
            }
            // V66: 要員は本人向け経路のmy.*を持つ（V64のseedに無く、勤怠が403になっていた）。
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM t_permission_group_action a "
                            + "JOIN m_permission_group g ON g.id = a.group_id "
                            + "WHERE g.group_key = 'role-member' AND a.action_key = 'my.*' "
                            + "AND a.deny_flag = 0 AND a.deleted_flag = 0")) {
                org.junit.jupiter.api.Assertions.assertTrue(rs.next() && rs.getLong(1) == 1,
                        "要員へmy.*がseedされるはず");
            }

            // 契約一覧の担当営業join(su.real_name)が実MySQLで実行可能なこと(full_name誤りの回帰)
            try (ResultSet rs = st.executeQuery(
                    "SELECT c.id, su.real_name AS salesUserName FROM t_contract c " +
                    "LEFT JOIN sys_user su ON c.sales_user_id = su.id AND su.deleted_flag = 0 " +
                    "WHERE c.deleted_flag = 0 LIMIT 1")) {
                rs.next(); // 例外なく実行できればよい(0件でも可)
            }

            // V67: 法定文書台帳 (legal-document-ledger-archive) テーブル存在とスキーマ同期のassert (S04-R02-P0-01)
            assertTableExists(st, "m_document_type");
            assertTableExists(st, "t_document");
            assertTableExists(st, "t_document_version");
            assertTableExists(st, "t_document_link");
            assertTableExists(st, "t_document_access_log");
            assertTableExists(st, "t_document_disposal_request");

            assertColumnExists(st, "t_document_version", "tenant_id");
            assertColumnExists(st, "t_document_version", "business_key");
            assertColumnExists(st, "t_document_version", "version_discriminator");

            // uk_document_idempotency が (tenant_id, source_type, business_key, version_discriminator) の4列インデックスであること
            try (ResultSet rs = st.executeQuery(
                    "SELECT column_name FROM information_schema.statistics " +
                    "WHERE table_schema=DATABASE() AND table_name='t_document_version' AND index_name='uk_document_idempotency' " +
                    "ORDER BY seq_in_index ASC")) {
                java.util.List<String> cols = new java.util.ArrayList<>();
                while (rs.next()) {
                    cols.add(rs.getString("column_name"));
                }
                org.junit.jupiter.api.Assertions.assertEquals(
                        java.util.List.of("tenant_id", "source_type", "business_key", "version_discriminator"),
                        cols,
                        "uk_document_idempotency は tenant_id を含む4列インデックスでなければならない");
            }

            // V68: 横断検索・実ToDo・保存ビュー・一括操作 (productivity-search-saved-view)
            assertTableExists(st, "t_task");
            assertTableExists(st, "m_saved_view");
            assertTableExists(st, "t_task_notification_log");
            assertColumnExists(st, "t_task", "assignee_user_id");
            assertColumnExists(st, "t_task", "due_date");
            assertColumnExists(st, "m_saved_view", "page_key");
            assertColumnExists(st, "m_saved_view", "owner_user_id");

            // V69/V101: 生産性向上機能はAPIのみ実装（UI未実装）のため、V101で
            // メニュー・権限マスタから撤去する。m_menu に残さないこと。
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM m_menu"
                            + " WHERE menu_key IN ('search','tasks','skill-tag','saved-views','batch-operations')")) {
                org.junit.jupiter.api.Assertions.assertTrue(rs.next() && rs.getLong(1) == 0,
                        "未実装ページのメニュー行はV101で削除されていること");
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM t_role_menu rm JOIN m_menu m ON m.id = rm.menu_id"
                            + " WHERE m.menu_key IN ('search','tasks','skill-tag','saved-views','batch-operations')")) {
                org.junit.jupiter.api.Assertions.assertTrue(rs.next() && rs.getLong(1) == 0,
                        "未実装ページのロール付与はV101で削除されていること");
            }

            // V73: CRM複数担当者・商機管理 (crm-contact-opportunity)
            assertTableExists(st, "t_customer_contact");
            assertTableExists(st, "t_lead");
            assertTableExists(st, "t_opportunity");
            assertColumnExists(st, "t_customer_contact", "roles_json");
            assertColumnExists(st, "t_customer_contact", "valid_from");
            assertColumnExists(st, "t_customer_contact", "valid_to");
            assertColumnExists(st, "t_customer_contact", "active_primary_customer_id");
            assertColumnExists(st, "t_opportunity", "converted_project_id");
            assertColumnExists(st, "t_opportunity", "converted_quotation_id");
            assertColumnExists(st, "t_opportunity", "stage_changed_at");
            assertColumnExists(st, "t_opportunity", "probability_override_reason");
            assertColumnExists(st, "t_lead", "source_cost");
            assertNumericColumn(st, "t_lead", "source_cost", 14, 0);
            assertColumnExists(st, "t_lead", "company_name_normalized");
            assertColumnExists(st, "t_lead", "contact_email_normalized");
            assertColumnExists(st, "t_lead", "contact_phone_normalized");
            assertIndexExists(st, "t_lead", "idx_lead_company_normalized");
            assertIndexExists(st, "t_lead", "idx_lead_email_normalized");
            assertIndexExists(st, "t_lead", "idx_lead_phone_normalized");
            assertColumnExists(st, "t_proposal", "source_opportunity_id");
            assertColumnExists(st, "t_mail_delivery", "contact_id");
            assertColumnExists(st, "t_mail_delivery", "opportunity_id");
            // MySQL JSON型の往復。H2(CLOB)だけでは不正なJSONを検出できないため、実MySQLで検証する。
            st.execute("INSERT INTO t_customer_contact (customer_id, name, roles_json, valid_from, status) "
                    + "SELECT id, 'roles-json-smoke', JSON_ARRAY('決裁者','請求'), CURDATE(), '有効' "
                    + "FROM m_customer WHERE deleted_flag = 0 LIMIT 1");
            assertRowExists(st, "SELECT 1 FROM t_customer_contact WHERE name='roles-json-smoke' "
                    + "AND JSON_CONTAINS(roles_json, JSON_QUOTE('請求'))");
            assertColumnExists(st, "t_lead", "converted_opportunity_id");
            // t_sales_activity拡張はV6を編集せずV73のALTERで入る（fresh/legacy共通経路）
            assertColumnExists(st, "t_sales_activity", "contact_id");
            assertColumnExists(st, "t_sales_activity", "opportunity_id");
            assertColumnExists(st, "t_sales_activity", "assignee_user_id");
            // 冪等変換のUNIQUE（design §6.3）
            assertColumnExists(st, "t_project", "source_opportunity_id");
            assertColumnExists(st, "t_quotation", "source_opportunity_id");
            assertIndexExists(st, "t_project", "uk_project_source_opportunity");
            assertIndexExists(st, "t_quotation", "uk_quotation_source_opportunity");
            // 主担当一意（生成列＋UNIQUE）。VIRTUALであること。
            assertIndexExists(st, "t_customer_contact", "uk_customer_contact_active_primary");
            assertRowExists(st, "SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()"
                    + " AND table_name='t_customer_contact' AND column_name='active_primary_customer_id'"
                    + " AND extra LIKE '%VIRTUAL GENERATED%'");
            // メニューは 管理者/マネージャー/営業 の3ロールだけ（design §6.2）
            assertRowExists(st, "SELECT 1 FROM m_menu WHERE menu_key='crm-lead'"
                    + " AND path_prefix='/crm/leads' AND api_prefix='/api/crm/leads'");
            assertRowExists(st, "SELECT 1 FROM m_menu WHERE menu_key='crm-opportunity'"
                    + " AND path_prefix='/crm/opportunities' AND api_prefix='/api/crm/opportunities'");
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM t_role_menu rm JOIN m_menu m ON m.id = rm.menu_id"
                            + " WHERE m.menu_key IN ('crm-lead','crm-opportunity')")) {
                org.junit.jupiter.api.Assertions.assertTrue(rs.next() && rs.getLong(1) == 6,
                        "CRMメニュー2件 × 営業系3ロール = 6行が付与されているはず");
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM t_role_menu rm JOIN m_menu m ON m.id = rm.menu_id"
                            + " WHERE m.menu_key IN ('crm-lead','crm-opportunity')"
                            + " AND rm.role IN ('HR','要員')")) {
                org.junit.jupiter.api.Assertions.assertTrue(rs.next() && rs.getLong(1) == 0,
                        "HR/要員へCRMメニューを付与してはいけない（design §6.2）");
            }
            // 既存contactの移行: V2のseed顧客3件が初回contactへ移り、名称/emailが一致する
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM m_customer c JOIN t_customer_contact cc ON cc.customer_id = c.id"
                            + " WHERE c.contact_person IS NOT NULL AND c.contact_person <> ''"
                            + " AND c.deleted_flag = 0"
                            + " AND cc.name = c.contact_person"
                            + " AND (cc.email <=> c.contact_email)"
                            + " AND cc.primary_flag = 1 AND cc.valid_to IS NULL")) {
                org.junit.jupiter.api.Assertions.assertTrue(rs.next() && rs.getLong(1) == 3,
                        "V2のseed顧客3件が名称/emailを保ったまま初回contactへ移行されているはず");
            }

            // V74: action permission。menu層とaction層の母集団が一致していること。
            // 片方だけだと MenuPermissionFilter が管理者を含む全roleを403にする（R08 Round 2 CRM-R2-P1-01）。
            // crm(S08) と bp-company(S06) は 営業/マネージャー、
            // search/task/saved-view/batch-operation(S05) は 営業/HR/マネージャー。
            assertActionGrantedTo(st, "crm.*", "role-manager", "role-sales");
            assertActionGrantedTo(st, "bp-company.*", "role-manager", "role-sales");
            for (String action : new String[]{"search.*", "task.*", "saved-view.*", "batch-operation.*"}) {
                assertActionGrantedTo(st, action, "role-hr", "role-manager", "role-sales");
            }

            // V75: 承認ワークフロー・内部統制 (approval-workflow-internal-control)
            assertTableExists(st, "m_approval_route");
            assertTableExists(st, "m_approval_route_step");
            assertTableExists(st, "t_approval_request");
            assertTableExists(st, "t_approval_action");
            assertTableExists(st, "t_approval_delegation");
            assertColumnExists(st, "m_approval_route", "min_amount");
            assertColumnExists(st, "m_approval_route", "max_amount");
            assertColumnExists(st, "m_approval_route", "applicant_role_condition");
            assertNumericColumn(st, "m_approval_route", "min_amount", 14, 0);
            assertColumnExists(st, "t_approval_request", "route_snapshot_json");
            assertColumnExists(st, "t_approval_request", "target_version");
            assertColumnExists(st, "t_approval_request", "idempotency_key");
            assertColumnExists(st, "t_approval_action", "approver_slot_user_id");
            assertIndexExists(st, "m_approval_route", "idx_approval_route_lookup");
            assertTableExists(st, "t_approval_responsibility");
            assertIndexExists(st, "t_approval_responsibility", "idx_approval_responsibility_lookup");
            assertIndexExists(st, "t_approval_responsibility", "idx_approval_responsibility_user");
            assertForeignKeyExists(st, "t_approval_responsibility", "fk_approval_responsibility_org",
                    "organization_id", "m_organization_unit", "id");
            assertForeignKeyExists(st, "t_approval_responsibility", "fk_approval_responsibility_user",
                    "user_id", "sys_user", "id");
            assertForeignKeyExists(st, "t_approval_responsibility", "fk_approval_responsibility_created_by",
                    "created_by", "sys_user", "id");
            assertCheckConstraintExists(st, "t_approval_responsibility", "chk_approval_responsibility_type");
            assertCheckConstraintExists(st, "t_approval_responsibility", "chk_approval_responsibility_period");
            assertCheckConstraintExists(st, "t_approval_responsibility", "chk_approval_responsibility_organization");
            // V79.1のroute decision sourceがhistory上もsuccessで適用され、checksumが記録されていること。
            try (ResultSet rs = st.executeQuery(
                    "SELECT success, checksum FROM flyway_schema_history"
                            + " WHERE version = '79.1' ORDER BY installed_rank DESC LIMIT 1")) {
                assertTrue(rs.next(), "Flyway historyにV79.1が存在するはず");
                assertEquals(1, rs.getInt("success"), "V79.1はsuccessであるはず");
                org.junit.jupiter.api.Assertions.assertNotNull(rs.getObject("checksum"),
                        "V79.1のchecksumが記録されるはず");
            }
            assertIndexExists(st, "t_approval_request", "idx_approval_request_applicant");
            assertActionGrantedTo(st, "approval.*", "role-hr", "role-manager", "role-sales");
            // t_approval_action.uk_approval_action_slot が二重action防止のUNIQUEであること
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.statistics"
                            + " WHERE table_schema = DATABASE() AND table_name = 't_approval_action'"
                            + " AND index_name = 'uk_approval_action_slot' AND non_unique = 0")) {
                org.junit.jupiter.api.Assertions.assertTrue(rs.next() && rs.getLong(1) >= 1,
                        "t_approval_action.uk_approval_action_slotがUNIQUEでない");
            }

            // V78: Round/participant/delegation type と楽観ロックversionの実MySQLスキーマ。
            assertColumnExists(st, "t_approval_request", "round_no");
            assertColumnExists(st, "t_approval_action", "round_no");
            assertColumnExists(st, "t_approval_action", "slot_index");
            assertTableExists(st, "t_approval_participant");
            assertColumnExists(st, "t_approval_participant", "request_id");
            assertColumnExists(st, "t_approval_participant", "user_id");
            assertColumnExists(st, "t_approval_participant", "participant_role");
            assertColumnExists(st, "t_approval_participant", "round_no");
            assertIndexExists(st, "t_approval_participant", "uk_participant");
            assertIndexExists(st, "t_approval_participant", "idx_participant_user");
            assertTableExists(st, "t_approval_delegation_type");
            assertColumnExists(st, "t_approval_delegation_type", "delegation_id");
            assertColumnExists(st, "t_approval_delegation_type", "request_type");
            assertIndexExists(st, "t_approval_delegation_type", "PRIMARY");
            assertIndexExists(st, "t_approval_action", "uk_approval_action_slot");
            assertColumnExists(st, "t_quotation", "version");
            assertColumnExists(st, "t_contract", "version");
            assertColumnExists(st, "t_invoice", "version");
            assertColumnExists(st, "t_bp_payment", "version");
            try (ResultSet rs = st.executeQuery(
                    "SELECT column_name FROM information_schema.statistics "
                            + "WHERE table_schema = DATABASE() AND table_name = 't_approval_action' "
                            + "AND index_name = 'uk_approval_action_slot' ORDER BY seq_in_index")) {
                java.util.List<String> columns = new java.util.ArrayList<>();
                while (rs.next()) {
                    columns.add(rs.getString(1));
                }
                assertEquals(java.util.List.of("request_id", "round_no", "step_no", "approver_slot_user_id"),
                        columns, "V78のaction UNIQUEはroundとslot userを含む必要がある");
            }

            // V80: 注文・注文請・月次検収 (order-acceptance-workflow / S09)
            assertTableExists(st, "t_sales_order");
            assertTableExists(st, "t_sales_order_line");
            assertTableExists(st, "t_acceptance");
            assertColumnExists(st, "t_sales_order", "order_no");
            assertColumnExists(st, "t_sales_order", "customer_po_no");
            assertColumnExists(st, "t_sales_order", "total_amount_snapshot");
            assertColumnExists(st, "t_sales_order", "payment_terms_snapshot");
            assertColumnExists(st, "t_sales_order", "source_document_id");
            assertColumnExists(st, "t_sales_order", "acknowledgement_document_id");
            assertColumnExists(st, "t_sales_order", "version");
            assertIndexExists(st, "t_sales_order", "uk_sales_order_no");
            // V108.2: 見積→注文の1見積1注文（nullable UNIQUE）
            assertIndexExists(st, "t_sales_order", "uk_sales_order_quotation");
            assertColumnExists(st, "t_sales_order_line", "order_id");
            assertColumnExists(st, "t_sales_order_line", "line_no");
            assertColumnExists(st, "t_sales_order_line", "engineer_id");
            assertIndexExists(st, "t_sales_order_line", "uk_sales_order_line");
            // t_contract.order_line_id（UNIQUEで1明細→1契約）と acceptance_required
            assertColumnExists(st, "t_contract", "order_line_id");
            assertColumnExists(st, "t_contract", "acceptance_required");
            assertColumnExists(st, "t_contract", "acceptance_exemption_reason");
            assertIndexExists(st, "t_contract", "uk_contract_order_line");
            // R09-P1-05: 孤児order_line_idを拒否するFK（fresh/legacy同一形状）
            assertForeignKeyExists(st, "t_contract", "fk_contract_order_line",
                    "order_line_id", "t_sales_order_line", "id");
            assertRowExists(st, "SELECT 1 FROM information_schema.statistics "
                    + "WHERE table_schema=DATABASE() AND table_name='t_contract' "
                    + "AND index_name='uk_contract_order_line' AND non_unique=0 "
                    + "AND seq_in_index=1 AND column_name='order_line_id' AND sub_part IS NULL");
            assertRowExists(st, "SELECT 1 FROM information_schema.referential_constraints "
                    + "WHERE constraint_schema=DATABASE() AND table_name='t_contract' "
                    + "AND constraint_name='fk_contract_order_line' "
                    + "AND update_rule='CASCADE' AND delete_rule='SET NULL'");
            assertRowExists(st, "SELECT 1 FROM information_schema.table_constraints "
                    + "WHERE table_schema=DATABASE() AND table_name='t_contract' "
                    + "AND constraint_name='chk_contract_acceptance_exemption' AND constraint_type='CHECK'");
            assertTableExists(st, "t_document_hash_claim");
            // R09-P2-04: legacy backfillのrepair-safe marker
            assertTableExists(st, "t_contract_acceptance_backfill");
            assertRowExists(st, "SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE()"
                    + " AND table_name='t_contract_acceptance_backfill'");
            // acceptance_required は NOT NULL DEFAULT 1（未設定を「不要」に化けない）
            assertRowExists(st, "SELECT 1 FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name='t_contract' AND column_name='acceptance_required' "
                    + "AND is_nullable='NO' AND column_default='1'");
            assertColumnExists(st, "t_acceptance", "contract_id");
            assertColumnExists(st, "t_acceptance", "work_month");
            assertColumnExists(st, "t_acceptance", "status");
            assertColumnExists(st, "t_acceptance", "hours_snapshot");
            assertColumnExists(st, "t_acceptance", "amount_snapshot");
            assertColumnExists(st, "t_acceptance", "version");
            assertIndexExists(st, "t_acceptance", "uk_acceptance_contract_month");
            // メニューとrole_menu（管理者・営業・マネージャーへ付与。HRは不可視）
            assertRowExists(st, "SELECT 1 FROM m_menu WHERE menu_key='sales-order'");
            assertRowExists(st, "SELECT 1 FROM m_menu WHERE menu_key='acceptance'");
            assertRowExists(st, "SELECT 1 FROM t_role_menu rm JOIN m_menu m ON m.id=rm.menu_id "
                    + "WHERE rm.role='営業' AND m.menu_key='sales-order'");
            assertRowExists(st, "SELECT 1 FROM t_role_menu rm JOIN m_menu m ON m.id=rm.menu_id "
                    + "WHERE rm.role='マネージャー' AND m.menu_key='acceptance'");
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM t_role_menu rm JOIN m_menu m ON m.id=rm.menu_id "
                            + "WHERE rm.role='HR' AND m.menu_key IN ('sales-order','acceptance')")) {
                org.junit.jupiter.api.Assertions.assertTrue(rs.next() && rs.getLong(1) == 0,
                        "HRへ注文/検収メニューを付与してはならない");
            }
            // document type種別（order-acceptance-workflow design §3）
            assertRowExists(st, "SELECT 1 FROM m_document_type WHERE code='ORDER_RECEIVED'");
            assertRowExists(st, "SELECT 1 FROM m_document_type WHERE code='ORDER_ACKNOWLEDGEMENT'");
            assertRowExists(st, "SELECT 1 FROM m_document_type WHERE code='ACCEPTANCE'");
            // action permission（営業・マネージャーへ付与。HRへは付与しない）
            assertActionGrantedTo(st, "sales-order.*", "role-manager", "role-sales");
            assertActionGrantedTo(st, "acceptance.*", "role-manager", "role-sales");
            // 同一契約×同一月の検収がDBのUNIQUEでも1件に制限されること（R3 / R5）
            st.execute("INSERT INTO t_contract (contract_no, engineer_id, project_id, customer_id, "
                    + "start_date, selling_price, cost_price, status) "
                    + "SELECT 'SO-SMOKE-1', e.id, p.id, p.customer_id, '2026-01-01', 500000, 300000, '準備中' "
                    + "FROM t_engineer e, t_project p LIMIT 1");
            long smokeContractId = -1;
            try (ResultSet rs = st.executeQuery(
                    "SELECT id FROM t_contract WHERE contract_no='SO-SMOKE-1'")) {
                if (rs.next()) { smokeContractId = rs.getLong(1); }
            }
            st.execute("INSERT INTO t_acceptance (contract_id, work_month, status) "
                    + "VALUES (" + smokeContractId + ", '2026-01', '未提出')");
            boolean duplicateAcceptanceRejected = false;
            try {
                st.execute("INSERT INTO t_acceptance (contract_id, work_month, status) "
                        + "VALUES (" + smokeContractId + ", '2026-01', '未提出')");
            } catch (java.sql.SQLException expected) {
                duplicateAcceptanceRejected = true;
            }
            org.junit.jupiter.api.Assertions.assertTrue(duplicateAcceptanceRejected,
                    "同一契約×同一月の検収はDBのUNIQUEでも拒否されるはず");

            // V106 & V106.1: 会計・支払連携基盤 (accounting-payment-integration)
            assertTableExists(st, "m_integration_connection");
            assertTableExists(st, "m_external_mapping");
            assertTableExists(st, "t_integration_job");
            assertTableExists(st, "t_integration_job_event");
            assertColumnExists(st, "m_integration_connection", "tenant_id");
            assertColumnExists(st, "m_integration_connection", "encrypted_tokens");
            assertColumnExists(st, "m_integration_connection", "token_version");
            assertColumnExists(st, "m_integration_connection", "refresh_lease_token");
            assertColumnExists(st, "m_integration_connection", "refresh_lease_expires_at");
            assertColumnExists(st, "m_integration_connection", "legal_entity_key");
            assertColumnExists(st, "m_integration_connection", "active_slot");
            assertColumnExists(st, "m_external_mapping", "object_type");
            assertColumnExists(st, "m_external_mapping", "verified_at");
            assertColumnExists(st, "t_integration_job", "idempotency_key");
            assertColumnExists(st, "t_integration_job", "payload_hash");
            assertColumnExists(st, "t_integration_job", "payload_snapshot");
            assertColumnExists(st, "t_integration_job", "lease_token");
            assertColumnExists(st, "t_integration_job", "lease_expires_at");
            assertColumnExists(st, "t_integration_job", "tenant_id");
            assertColumnExists(st, "t_integration_job", "legal_entity_id");
            assertColumnExists(st, "t_integration_job", "organization_id");
            assertColumnExists(st, "t_integration_job", "next_retry_at");
            assertRowExists(st, "SELECT 1 FROM m_menu WHERE menu_key='accounting-integration'");

            // V109: 要員ライフサイクルワークフロー (engineer-lifecycle-workflow)
            assertTableExists(st, "m_lifecycle_template");
            assertTableExists(st, "m_lifecycle_template_task");
            assertTableExists(st, "m_lifecycle_template_task_dep");
            assertTableExists(st, "t_lifecycle_case");
            assertTableExists(st, "t_lifecycle_task");
            assertTableExists(st, "t_lifecycle_task_dep");
            assertTableExists(st, "t_lifecycle_evidence_link");
            assertTableExists(st, "t_lifecycle_event");
            assertColumnExists(st, "t_lifecycle_case", "version");
            assertColumnExists(st, "t_lifecycle_case", "engineer_snapshot_json");
            assertColumnExists(st, "t_lifecycle_task", "version");
            assertColumnExists(st, "t_lifecycle_task", "approval_request_id");
            assertRowExists(st, "SELECT 1 FROM m_menu WHERE menu_key='lifecycle'");
            assertRowExists(st, "SELECT 1 FROM m_menu WHERE menu_key='myLifecycle'");
            assertRowExists(st, "SELECT 1 FROM t_role_menu rm JOIN m_menu m ON m.id=rm.menu_id "
                    + "WHERE rm.role='HR' AND m.menu_key='lifecycle'");
            assertRowExists(st, "SELECT 1 FROM t_role_menu rm JOIN m_menu m ON m.id=rm.menu_id "
                    + "WHERE rm.role='要員' AND m.menu_key='myLifecycle'");
        }
    }

    @Test
    void V78legacy申請は終端済みならparticipantをbackfillし多名旧snapshotの進行中申請は停止する() throws Exception {
        Flyway.configure()
                .dataSource(LEGACY_V78.getJdbcUrl(), LEGACY_V78.getUsername(), LEGACY_V78.getPassword())
                .locations("classpath:db/migration")
                .target("77")
                .load()
                .migrate();

        LegacyFixture terminal = insertLegacyApprovalFixture(LEGACY_V78, "AR-V78-TERM", "approved");
        Flyway.configure()
                .dataSource(LEGACY_V78.getJdbcUrl(), LEGACY_V78.getUsername(), LEGACY_V78.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = LEGACY_V78.createConnection("");
             Statement statement = connection.createStatement()) {
            assertEquals(1, countParticipants(statement, terminal.requestId(), "applicant"));
            assertEquals(2, countParticipants(statement, terminal.requestId(), "approver"));
            // round総数は申請者1名＋旧snapshotの承認者2名で3件になる。
            assertEquals(3, countParticipantsForRound(statement, terminal.requestId(), 1));
        }

        // 成功経路のスキーマを一度初期化し、同じV77 legacy形状で停止経路を再現する。
        Flyway.configure()
                .dataSource(LEGACY_V78.getJdbcUrl(), LEGACY_V78.getUsername(), LEGACY_V78.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .clean();
        Flyway.configure()
                .dataSource(LEGACY_V78.getJdbcUrl(), LEGACY_V78.getUsername(), LEGACY_V78.getPassword())
                .locations("classpath:db/migration")
                .target("77")
                .load()
                .migrate();
        insertLegacyApprovalFixture(LEGACY_V78, "AR-V78-BLOCK", "in_review");

        Exception failure = assertThrows(Exception.class, () -> Flyway.configure()
                .dataSource(LEGACY_V78.getJdbcUrl(), LEGACY_V78.getUsername(), LEGACY_V78.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate());
        String messages = allMessages(failure);
        assertTrue(messages.contains("V78"), "V78の停止メッセージが例外chainに含まれるはず: " + messages);
        assertTrue(messages.contains("AR-V78-BLOCK"), "request_noが停止メッセージに含まれるはず: " + messages);
        assertTrue(messages.contains("in_review"), "statusが停止メッセージに含まれるはず: " + messages);
        assertTrue(messages.contains("flyway repair"), "repair後再実行の手順が停止メッセージに含まれるはず: " + messages);
    }

    /** 当該actionを許可されている既定groupが、期待どおりの集合に一致すること。 */
    private void assertActionGrantedTo(Statement st, String actionKey, String... expectedGroupKeys)
            throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT g.group_key FROM t_permission_group_action a"
                        + " JOIN m_permission_group g ON g.id = a.group_id"
                        + " WHERE a.action_key = '" + actionKey + "' AND a.deny_flag = 0"
                        + " AND g.group_key <> 'role-admin'"
                        + " ORDER BY g.group_key")) {
            java.util.List<String> actual = new java.util.ArrayList<>();
            while (rs.next()) {
                actual.add(rs.getString(1));
            }
            org.junit.jupiter.api.Assertions.assertEquals(
                    java.util.List.of(expectedGroupKeys), actual,
                    actionKey + " の付与先groupがmenu付与と一致しません");
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

    private void assertForeignKeyExists(Statement st, String table, String constraint,
                                        String column, String referencedTable, String referencedColumn)
            throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT 1 FROM information_schema.KEY_COLUMN_USAGE"
                        + " WHERE CONSTRAINT_SCHEMA=DATABASE()"
                        + " AND TABLE_NAME='" + table + "'"
                        + " AND CONSTRAINT_NAME='" + constraint + "'"
                        + " AND COLUMN_NAME='" + column + "'"
                        + " AND REFERENCED_TABLE_NAME='" + referencedTable + "'"
                        + " AND REFERENCED_COLUMN_NAME='" + referencedColumn + "'")) {
            assertTrue(rs.next(), table + "." + constraint + " が期待するFKを参照するはず");
        }
    }

    private void assertCheckConstraintExists(Statement st, String table, String constraint)
            throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT 1 FROM information_schema.table_constraints"
                        + " WHERE constraint_schema=DATABASE()"
                        + " AND table_name='" + table + "'"
                        + " AND constraint_name='" + constraint + "'"
                        + " AND constraint_type='CHECK'")) {
            assertTrue(rs.next(), table + "." + constraint + " がCHECK制約として存在するはず");
        }
    }

    private void assertNumericColumn(Statement st, String table, String column,
                                     int precision, int scale) throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT numeric_precision, numeric_scale FROM information_schema.columns"
                        + " WHERE table_schema=DATABASE() AND table_name='" + table
                        + "' AND column_name='" + column + "'")) {
            assertTrue(rs.next(), table + "." + column + " の数値型定義が存在するはず");
            assertEquals(precision, rs.getInt(1));
            assertEquals(scale, rs.getInt(2));
        }
    }

    private record LegacyFixture(long requestId) {
    }

    private LegacyFixture insertLegacyApprovalFixture(MySQLContainer<?> container,
                                                       String requestNo, String status) throws Exception {
        try (Connection connection = container.createConnection("");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO sys_user "
                    + "(username, password, real_name, role, email, status) VALUES "
                    + "('v78-legacy-approver-a', 'x', 'V78承認者A', '管理者', 'v78-a@ses.local', 1),"
                    + "('v78-legacy-approver-b', 'x', 'V78承認者B', '管理者', 'v78-b@ses.local', 1)");
            long applicantId = queryLong(statement, "SELECT id FROM sys_user WHERE username='admin'");
            long approverA = queryLong(statement,
                    "SELECT id FROM sys_user WHERE username='v78-legacy-approver-a'");
            long approverB = queryLong(statement,
                    "SELECT id FROM sys_user WHERE username='v78-legacy-approver-b'");
            String snapshot = "{\"routeId\":1,\"versionNo\":1,\"organizationId\":null,"
                    + "\"steps\":[{\"stepNo\":1,\"slaHours\":null,"
                    + "\"approverUserIds\":[" + approverA + "," + approverB + "]}]}";

            try (PreparedStatement prepared = connection.prepareStatement(
                    "INSERT INTO t_approval_request "
                            + "(request_no, request_type, target_type, target_id, target_version, "
                            + "applicant_id, organization_id, amount_snapshot, payload_json, diff_json, "
                            + "route_snapshot_json, status, current_step, requested_at, finalized_at, "
                            + "idempotency_key, version, created_by) "
                            + "VALUES (?, 'legacy.v78', 'CONTRACT', 1, NULL, ?, NULL, NULL, '{}', NULL, "
                            + "?, ?, 1, CURRENT_TIMESTAMP, NULL, NULL, 1, ?)")) {
                prepared.setString(1, requestNo);
                prepared.setLong(2, applicantId);
                prepared.setString(3, snapshot);
                prepared.setString(4, status);
                prepared.setLong(5, applicantId);
                prepared.executeUpdate();
            }
            return new LegacyFixture(queryLong(statement,
                    "SELECT id FROM t_approval_request WHERE request_no='" + requestNo + "'"));
        }
    }

    private long countParticipants(Statement statement, long requestId, String role) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(
                "SELECT COUNT(*) FROM t_approval_participant WHERE request_id=" + requestId
                        + " AND participant_role='" + role + "'")) {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }

    private long countParticipantsForRound(Statement statement, long requestId, int roundNo) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(
                "SELECT COUNT(*) FROM t_approval_participant WHERE request_id=" + requestId
                        + " AND round_no=" + roundNo)) {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }

    private long queryLong(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next(), "fixture query returned no row: " + sql);
            return resultSet.getLong(1);
        }
    }

    private String allMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return messages.toString();
    }

    private void assertRowExists(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), "行が存在するはず: " + sql);
        }
    }
}
