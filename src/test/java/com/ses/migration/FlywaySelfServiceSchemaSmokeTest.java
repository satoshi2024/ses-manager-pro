package com.ses.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import com.ses.test.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T088 (F1)のMySQL smoke。V105（fresh full run）でengineer self-service portal V2の
 * DDL shapeと制約（UNIQUE冪等・CHECK・FK・seed）を実MySQLで検証する（design §6.3）。
 */
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class FlywaySelfServiceSchemaSmokeTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_selfservice_v105")
            .withUsername("root")
            .withPassword("ses");

    /** legacy path検証用: V104_4適用済みDBへV105を適用する。 */
    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> LEGACY_MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_selfservice_legacy")
            .withUsername("root")
            .withPassword("ses");

    /** 4fa3a689版V105_1（phone/template_snapshot_version未追加）環境からの移行検証用container */
    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> HISTORICAL_V105_1_MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_selfservice_historical_v105_1")
            .withUsername("root")
            .withPassword("ses");

    @Test
    void V105のselfservice_shapeがfreshとlegacyで一致し制約がMySQLで成立する() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            // ---- 最新version=143（repeatable migration（version=NULL）を除く） ----
            String latestVersion = queryString(statement,
                    "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1");
            assertEquals("145", latestVersion, "最新のマイグレーションバージョンは145であること");

            for (String table : new String[]{
                    "t_engineer_change_request", "t_expense_request", "t_expense_accounting_job",
                    "t_one_on_one_request", "m_survey_template", "t_survey_campaign", "t_survey_response"}) {
                assertTableExists(statement, table);
            }
            for (String table : new String[]{
                    "m_report_template", "m_report_template_version", "m_report_schedule",
                    "t_report_run", "t_report_section_snapshot", "t_report_section_attempt",
                    "t_report_delivery"}) {
                assertTableExists(statement, table);
            }
            assertColumnExists(statement, "m_report_template_version", "updated_at");
            assertColumnExists(statement, "m_report_schedule", "scope_hash");
            assertColumnExists(statement, "m_report_schedule", "retry_scheduled_at");
            assertColumnExists(statement, "m_report_schedule", "processing_logical_run_at");
            assertColumnExists(statement, "m_report_schedule", "processing_claimed_at");
            assertColumnExists(statement, "t_report_run", "snapshot_version");
            assertColumnExists(statement, "t_report_delivery", "reauth_required");
            assertColumnExists(statement, "t_report_delivery", "notification_outbox_id");
            assertIndexExists(statement, "t_report_run", "uk_report_run_key");
            assertIndexExists(statement, "t_report_section_snapshot", "uk_report_section_snapshot");
            assertIndexExists(statement, "t_report_section_attempt", "idx_report_section_attempt_run");
            assertIndexExists(statement, "t_report_delivery", "uk_report_delivery_dedupe");
            assertIndexExists(statement, "t_report_delivery", "idx_report_delivery_outbox");
            // t_document_linkにskill sheet確認列が追加されていること（design §1/§6.1）
            assertColumnExists(statement, "t_document_link", "skill_sheet_confirmed_at");
            assertColumnExists(statement, "t_document_link", "skill_sheet_confirmed_version");
            // 列shape
            assertColumnExists(statement, "t_engineer", "phone");
            assertColumnExists(statement, "t_engineer_change_request", "reason");
            assertColumnExists(statement, "t_engineer_change_request", "attachment_document_id");
            assertColumnExists(statement, "t_pwa_client_mutation", "operation");
            assertColumnExists(statement, "t_survey_campaign", "template_snapshot_json");
            assertColumnExists(statement, "t_survey_campaign", "template_snapshot_version");
            assertColumnExists(statement, "t_expense_request", "accounting_job_id");
            assertColumnExists(statement, "t_expense_request", "paid_at");
            assertColumnExists(statement, "t_one_on_one_request", "private_note_ref");
            assertColumnExists(statement, "t_survey_response", "comment_visibility");
            assertColumnExists(statement, "t_survey_response", "template_version");
            // UNIQUE / CHECK / FK
            assertIndexExists(statement, "t_expense_request", "uk_expense_no");
            assertIndexExists(statement, "t_expense_request", "uk_expense_accounting_job");
            assertIndexExists(statement, "t_expense_accounting_job", "uk_expense_job_request");
            assertIndexExists(statement, "t_survey_response", "uk_survey_response");
            assertIndexExists(statement, "m_survey_template", "uk_survey_template_key");
            assertCheckExists(statement, "t_expense_request", "chk_expense_category");
            assertCheckExists(statement, "t_expense_request", "chk_expense_status");
            assertCheckExists(statement, "t_one_on_one_request", "chk_1on1_status");
            assertCheckExists(statement, "t_survey_response", "chk_survey_visibility");
            assertForeignKeyExists(statement, "t_expense_request", "fk_expense_engineer");
            assertForeignKeyExists(statement, "t_expense_request", "fk_expense_receipt_document");
            assertForeignKeyExists(statement, "t_expense_accounting_job", "fk_expense_job_request");
            assertForeignKeyExists(statement, "t_survey_response", "fk_survey_response_campaign");

            // ---- seeds ----
            assertEquals(4, queryInt(statement,
                    "SELECT COUNT(*) FROM m_document_type WHERE code IN "
                            + "('SKILL_SHEET','RECEIPT','PRIVATE_NOTE','CHANGE_REQUEST_ATTACHMENT')"));
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM m_document_type WHERE code='CHANGE_REQUEST_ATTACHMENT'"),
                    "CHANGE_REQUEST_ATTACHMENT文書種別seed（R2-P1-01）");
            for (String menuKey : new String[]{
                    "myDashboard", "myProfile", "myPayroll", "myExpenses", "myOneOnOnes", "mySurveys",
                    "engineerChangeRequests", "expenseManagement", "oneOnOneManagement", "surveyManagement"}) {
                assertEquals(1, queryInt(statement,
                        "SELECT COUNT(*) FROM m_menu WHERE menu_key='" + menuKey + "'"), menuKey + " menu seed");
            }
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM m_system_config WHERE config_key='survey.min-answers'"));
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM m_system_config WHERE config_key='expense.accounting.provider'"));
            // 要員roleへmyメニューが付与されていること
            assertEquals(6, queryInt(statement,
                    "SELECT COUNT(*) FROM t_role_menu WHERE role='要員' AND menu_id IN "
                            + "(SELECT id FROM m_menu WHERE menu_key IN "
                            + "('myDashboard','myProfile','myPayroll','myExpenses','myOneOnOnes','mySurveys'))"));
            // 権限seed（baseline+deny方式。non-admin groupが403にならないため）
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM t_permission_group_action pa "
                            + "JOIN m_permission_group g ON g.id=pa.group_id "
                            + "WHERE pa.tenant_id='default' AND g.group_key='role-manager' "
                            + "AND pa.action_key='expense-request.*' AND pa.deny_flag=0"));
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM t_permission_group_action pa "
                            + "JOIN m_permission_group g ON g.id=pa.group_id "
                            + "WHERE pa.tenant_id='default' AND g.group_key='role-hr' "
                            + "AND pa.action_key='survey.*' AND pa.deny_flag=0"));

            // ---- 制約の実挙動 ----
            statement.executeUpdate("INSERT INTO t_engineer (full_name, employment_type) VALUES ('T088-engineer', '正社員')");
            long engineerId = queryLong(statement,
                    "SELECT id FROM t_engineer WHERE full_name='T088-engineer'");

            // 経費: 不正categoryはCHECKで拒否
            boolean invalidCategoryRejected = false;
            try {
                statement.executeUpdate("INSERT INTO t_expense_request (engineer_id, expense_date, category, amount) "
                        + "VALUES (" + engineerId + ", '2026-08-01', '接待費', 1000)");
            } catch (SQLException expected) {
                invalidCategoryRejected = true;
            }
            assertTrue(invalidCategoryRejected, "経費の不正categoryを拒否するはず（CHECK）");

            // 経費: 同一accounting_job_idの二重登録はUNIQUEで拒否（job二重生成防止。design §6.3）
            statement.executeUpdate("INSERT INTO t_expense_request (engineer_id, expense_date, category, amount) "
                    + "VALUES (" + engineerId + ", '2026-08-01', '交通費', 1500)");
            long expenseId = queryLong(statement,
                    "SELECT id FROM t_expense_request WHERE engineer_id=" + engineerId + " AND amount=1500");
            statement.executeUpdate("INSERT INTO t_expense_accounting_job (expense_request_id, payload_hash) "
                    + "VALUES (" + expenseId + ", '" + "a".repeat(64) + "')");
            long jobId = queryLong(statement,
                    "SELECT id FROM t_expense_accounting_job WHERE expense_request_id=" + expenseId);
            statement.executeUpdate("UPDATE t_expense_request SET accounting_job_id=" + jobId
                    + " WHERE id=" + expenseId);
            boolean duplicateJobRejected = false;
            try {
                statement.executeUpdate("INSERT INTO t_expense_accounting_job (expense_request_id, payload_hash) "
                        + "VALUES (" + expenseId + ", '" + "b".repeat(64) + "')");
            } catch (SQLException expected) {
                duplicateJobRejected = true;
            }
            assertTrue(duplicateJobRejected, "同一経費からの2件目のjobを拒否するはず（UNIQUE）");

            // 経費番号の一意性（二重採番の防止）
            statement.executeUpdate("UPDATE t_expense_request SET expense_no='EX-1' WHERE id=" + expenseId);
            boolean duplicateExpenseNoRejected = false;
            try {
                statement.executeUpdate("INSERT INTO t_expense_request "
                        + "(engineer_id, expense_date, category, amount, expense_no) "
                        + "VALUES (" + engineerId + ", '2026-08-02', '立替経費', 2000, 'EX-1')");
            } catch (SQLException expected) {
                duplicateExpenseNoRejected = true;
            }
            assertTrue(duplicateExpenseNoRejected, "同一expense_noの重複を拒否するはず（UNIQUE）");

            // survey: 同一(campaign, engineer, question_key)の二重回答行はUNIQUEで拒否（再回答はupsertで上書き）
            statement.executeUpdate("INSERT INTO m_survey_template "
                    + "(template_key, title, questions_json, status, version) "
                    + "VALUES ('t088', 'T088', '[]', 'ACTIVE', 1)");
            long templateId = queryLong(statement,
                    "SELECT id FROM m_survey_template WHERE template_key='t088'");
            statement.executeUpdate("INSERT INTO t_survey_campaign (template_id, title, status) "
                    + "VALUES (" + templateId + ", 'T088-campaign', 'ACTIVE')");
            long campaignId = queryLong(statement,
                    "SELECT id FROM t_survey_campaign WHERE title='T088-campaign'");
            statement.executeUpdate("INSERT INTO t_survey_response "
                    + "(campaign_id, engineer_id, question_key, answer_value, comment_visibility, consent_flag, template_version) "
                    + "VALUES (" + campaignId + ", " + engineerId + ", 'q1', 4, 'PUBLIC', 1, 1)");
            boolean duplicateResponseRejected = false;
            try {
                statement.executeUpdate("INSERT INTO t_survey_response "
                        + "(campaign_id, engineer_id, question_key, answer_value, comment_visibility, consent_flag, template_version) "
                        + "VALUES (" + campaignId + ", " + engineerId + ", 'q1', 5, 'PUBLIC', 1, 1)");
            } catch (SQLException expected) {
                duplicateResponseRejected = true;
            }
            assertTrue(duplicateResponseRejected, "同一質問への二重回答行を拒否するはず（UNIQUE）");

            // survey: 範囲外answerはCHECKで拒否
            boolean outOfRangeAnswerRejected = false;
            try {
                statement.executeUpdate("INSERT INTO t_survey_response "
                        + "(campaign_id, engineer_id, question_key, answer_value, comment_visibility, consent_flag, template_version) "
                        + "VALUES (" + campaignId + ", " + engineerId + ", 'q2', 9, 'PUBLIC', 1, 1)");
            } catch (SQLException expected) {
                outOfRangeAnswerRejected = true;
            }
            assertTrue(outOfRangeAnswerRejected, "範囲外answerを拒否するはず（CHECK）");

            // 1on1: 不正statusはCHECKで拒否
            boolean invalidOneOnOneStatusRejected = false;
            try {
                statement.executeUpdate("INSERT INTO t_one_on_one_request "
                        + "(engineer_id, counterpart_user_id, candidate_dates_json, status) "
                        + "VALUES (" + engineerId + ", 1, '[\"2026-09-01\"]', '完了')");
            } catch (SQLException expected) {
                invalidOneOnOneStatusRejected = true;
            }
            assertTrue(invalidOneOnOneStatusRejected, "1on1の不正statusを拒否するはず（CHECK）");

            // change request: 不正typeはCHECKで拒否
            boolean invalidChangeTypeRejected = false;
            try {
                statement.executeUpdate("INSERT INTO t_engineer_change_request "
                        + "(engineer_id, request_type, payload_json, diff_json, status) "
                        + "VALUES (" + engineerId + ", 'salary.change', '{}', '{}', '下書き')");
            } catch (SQLException expected) {
                invalidChangeTypeRejected = true;
            }
            assertTrue(invalidChangeTypeRejected, "変更申請の不正typeを拒否するはず（CHECK）");
        }
    }

    @Test
    void V104_4適用済みlegacyDBへV105を順方向適用できshapeがfreshと一致する() throws Exception {
        // 既存DB（selfservice導入前shape）をV104_4まで適用する。
        Flyway.configure()
                .dataSource(LEGACY_MYSQL.getJdbcUrl(), LEGACY_MYSQL.getUsername(), LEGACY_MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("104_4")
                .load()
                .migrate();

        try (Connection connection = LEGACY_MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            // ---- selfservice導入前shapeへの復元 ----
            // 現在のV1はselfservice統合済みのため、V104_4適用後にselfservice追加分を除去して
            // 「selfservice導入前のlegacy shape」を再現し、V105のguarded DDLを単独で適用する。
            statement.executeUpdate("DROP TABLE t_expense_accounting_job");
            statement.executeUpdate("DROP TABLE t_expense_request");
            statement.executeUpdate("DROP TABLE t_one_on_one_request");
            statement.executeUpdate("DROP TABLE t_survey_response");
            statement.executeUpdate("DROP TABLE t_survey_campaign");
            statement.executeUpdate("DROP TABLE m_survey_template");
            statement.executeUpdate("DROP TABLE t_engineer_change_request");
            statement.executeUpdate("ALTER TABLE t_document_link DROP COLUMN skill_sheet_confirmed_version");
            statement.executeUpdate("ALTER TABLE t_document_link DROP COLUMN skill_sheet_confirmed_at");
            statement.executeUpdate("DELETE FROM t_role_menu WHERE menu_id IN "
                    + "(SELECT id FROM m_menu WHERE menu_key IN "
                    + "('myDashboard','myProfile','myPayroll','myExpenses','myOneOnOnes','mySurveys',"
                    + "'engineerChangeRequests','expenseManagement','oneOnOneManagement','surveyManagement'))");
            statement.executeUpdate("DELETE FROM m_menu WHERE menu_key IN "
                    + "('myDashboard','myProfile','myPayroll','myExpenses','myOneOnOnes','mySurveys',"
                    + "'engineerChangeRequests','expenseManagement','oneOnOneManagement','surveyManagement')");

            // selfservice導入前shapeの確認: V105のテーブルが存在しないこと
            assertEquals(0, queryInt(statement,
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema=DATABASE() AND table_name='t_expense_request'"));
            assertEquals(0, queryInt(statement,
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_schema=DATABASE() AND table_name='t_document_link' "
                            + "AND column_name='skill_sheet_confirmed_at'"));
        }

        // ---- V105/V105_1をlegacy DBへ順方向適用 ----
        Flyway.configure()
                .dataSource(LEGACY_MYSQL.getJdbcUrl(), LEGACY_MYSQL.getUsername(), LEGACY_MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = LEGACY_MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            String latestVersion = queryString(statement,
                    "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1");
            assertEquals("145", latestVersion, "legacy DBの最新マイグレーションバージョンは145であること");

            for (String table : new String[]{
                    "t_engineer_change_request", "t_expense_request", "t_expense_accounting_job",
                    "t_one_on_one_request", "m_survey_template", "t_survey_campaign", "t_survey_response"}) {
                assertTableExists(statement, table);
            }
            for (String table : new String[]{
                    "m_report_template", "m_report_template_version", "m_report_schedule",
                    "t_report_run", "t_report_section_snapshot", "t_report_section_attempt",
                    "t_report_delivery"}) {
                assertTableExists(statement, table);
            }
            assertColumnExists(statement, "t_report_delivery", "notification_outbox_id");
            assertColumnExists(statement, "m_report_schedule", "processing_logical_run_at");
            assertColumnExists(statement, "m_report_schedule", "processing_claimed_at");
            assertIndexExists(statement, "t_report_section_attempt", "idx_report_section_attempt_run");
            assertIndexExists(statement, "t_report_delivery", "idx_report_delivery_outbox");
            assertColumnExists(statement, "t_document_link", "skill_sheet_confirmed_at");
            assertColumnExists(statement, "t_document_link", "skill_sheet_confirmed_version");
            assertColumnExists(statement, "t_pwa_client_mutation", "operation");
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM m_menu WHERE menu_key='myPayroll'"));
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM m_document_type WHERE code='CHANGE_REQUEST_ATTACHMENT'"),
                    "legacy DBへV105.3のCHANGE_REQUEST_ATTACHMENT seedが適用されること（R2-P1-01）");

            // fresh（V1統合baseline）とlegacy（V105/V105_1順方向適用）でshapeが一致する
            Flyway.configure()
                    .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            String freshShape = selfServiceShape(MYSQL.createConnection(""));
            String legacyShape = selfServiceShape(LEGACY_MYSQL.createConnection(""));
            assertEquals(freshShape, legacyShape, "fresh/legacyでselfserviceテーブルのshapeが一致する");
        }
    }

    @Test
    void 旧4fa3a689版V105_1適用済みDBからV105_2へ順方向適用できる() throws Exception {
        // 1. 隔離された専用containerでV104_4まで適用
        Flyway.configure()
                .dataSource(HISTORICAL_V105_1_MYSQL.getJdbcUrl(), HISTORICAL_V105_1_MYSQL.getUsername(), HISTORICAL_V105_1_MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("104_4")
                .load()
                .migrate();

        // 2. V1統合baselineのselfservice追加分を除去して「V104.4 legacy shape」を再現
        try (Connection connection = HISTORICAL_V105_1_MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS t_expense_accounting_job");
            statement.executeUpdate("DROP TABLE IF EXISTS t_expense_request");
            statement.executeUpdate("DROP TABLE IF EXISTS t_one_on_one_request");
            statement.executeUpdate("DROP TABLE IF EXISTS t_survey_response");
            statement.executeUpdate("DROP TABLE IF EXISTS t_survey_campaign");
            statement.executeUpdate("DROP TABLE IF EXISTS m_survey_template");
            statement.executeUpdate("DROP TABLE IF EXISTS t_engineer_change_request");
            if (queryInt(statement, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='t_engineer' AND column_name='phone'") > 0) {
                statement.executeUpdate("ALTER TABLE t_engineer DROP COLUMN phone");
            }
            if (queryInt(statement, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='t_document_link' AND column_name='skill_sheet_confirmed_at'") > 0) {
                statement.executeUpdate("ALTER TABLE t_document_link DROP COLUMN skill_sheet_confirmed_at");
                statement.executeUpdate("ALTER TABLE t_document_link DROP COLUMN skill_sheet_confirmed_version");
            }
            statement.executeUpdate("DELETE FROM t_role_menu WHERE menu_id IN "
                    + "(SELECT id FROM m_menu WHERE menu_key IN "
                    + "('myDashboard','myProfile','myPayroll','myExpenses','myOneOnOnes','mySurveys',"
                    + "'engineerChangeRequests','expenseManagement','oneOnOneManagement','surveyManagement'))");
            statement.executeUpdate("DELETE FROM m_menu WHERE menu_key IN "
                    + "('myDashboard','myProfile','myPayroll','myExpenses','myOneOnOnes','mySurveys',"
                    + "'engineerChangeRequests','expenseManagement','oneOnOneManagement','surveyManagement')");
        }

        // 3. 4fa3a689版V105.1まで順方向適用（V105 + V105.1）
        Flyway.configure()
                .dataSource(HISTORICAL_V105_1_MYSQL.getJdbcUrl(), HISTORICAL_V105_1_MYSQL.getUsername(), HISTORICAL_V105_1_MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("105.1")
                .load()
                .migrate();

        // 4. V105.1適用時点の状態を明示assert:
        //    - flyway_schema_history の最新versionが '105.1'
        //    - t_engineer.phone が存在しない
        //    - t_survey_campaign.template_snapshot_version が存在しない
        try (Connection connection = HISTORICAL_V105_1_MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            String latestVersion = queryString(statement,
                    "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1");
            assertEquals("105.1", latestVersion, "V105.1適用時点の最新マイグレーションバージョンは105.1であること");

            assertEquals(0, queryInt(statement,
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='t_engineer' AND column_name='phone'"),
                    "V105.1時点ではt_engineer.phone列が存在しないこと");

            assertEquals(0, queryInt(statement,
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='t_survey_campaign' AND column_name='template_snapshot_version'"),
                    "V105.1時点ではt_survey_campaign.template_snapshot_version列が存在しないこと");
        }

        // 5. ここから V105.2 へ順方向適用
        Flyway.configure()
                .dataSource(HISTORICAL_V105_1_MYSQL.getJdbcUrl(), HISTORICAL_V105_1_MYSQL.getUsername(), HISTORICAL_V105_1_MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("105.2")
                // historical fixture作成時にJava migration 74.3が解決対象外になった場合も、
                // V105.2への順方向遷移で未適用の過去migrationを実行して検証を継続する。
                .outOfOrder(true)
                .load()
                .migrate();

        // 6. V105.2適用後の状態を明示assert:
        //    - flyway_schema_history の最新versionが '105.2'
        //    - t_engineer.phone が存在する
        //    - t_survey_campaign.template_snapshot_version が存在する
        try (Connection connection = HISTORICAL_V105_1_MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            String latestVersion = queryString(statement,
                    "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1");
            assertEquals("105.2", latestVersion, "V105.2適用後の最新マイグレーションバージョンは105.2であること");

            assertColumnExists(statement, "t_engineer", "phone");
            assertColumnExists(statement, "t_survey_campaign", "template_snapshot_version");
        }

        // 7. ここから V105.3 へ順方向適用
        Flyway.configure()
                .dataSource(HISTORICAL_V105_1_MYSQL.getJdbcUrl(), HISTORICAL_V105_1_MYSQL.getUsername(), HISTORICAL_V105_1_MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("105.3")
                .load()
                .migrate();

        // 8. V105.3適用後の状態を明示assert:
        //    - flyway_schema_history の最新versionが '105.3'
        //    - CHANGE_REQUEST_ATTACHMENT seedが存在する（R2-P1-01）
        try (Connection connection = HISTORICAL_V105_1_MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            String latestVersion = queryString(statement,
                    "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1");
            assertEquals("105.3", latestVersion, "V105.3適用後の最新マイグレーションバージョンは105.3であること");

            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM m_document_type WHERE code='CHANGE_REQUEST_ATTACHMENT'"),
                    "historical DBへV105.3のCHANGE_REQUEST_ATTACHMENT seedが適用されること（R2-P1-01）");
        }
    }

    /** selfservice関連テーブル/列の定義を連結してfresh/legacy比較用のfingerprintを作る。 */
    private String selfServiceShape(Connection connection) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT table_name, column_name, column_type, is_nullable, column_default "
                             + "FROM information_schema.columns "
                             + "WHERE table_schema = DATABASE() AND table_name IN "
                             + "('t_engineer_change_request','t_expense_request','t_expense_accounting_job',"
                             + "'t_one_on_one_request','m_survey_template','t_survey_campaign','t_survey_response') "
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

    private String queryString(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }
}
