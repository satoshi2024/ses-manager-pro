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
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NF-03 F1-1〜A2のMySQL smoke。V116〜V171のDDL shape・seed・FKを実MySQLで検証する。
 */
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class FlywayCertificationLearningSkillGapSchemaSmokeTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_cert_learning_gap")
            .withUsername("root")
            .withPassword("ses");

    @Test
    void V116からV133のNF03_shapeがMySQLで成立する() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            String latestVersion = queryString(statement,
                    "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1");
            assertTrue(Integer.parseInt(latestVersion) >= 171,
                    "NF-02/NF-03の最新tenant/CAS migration以降まで適用されていること");

            for (String table : new String[]{
                    "m_certification", "m_certification_alias", "t_engineer_certification",
                    "t_certification_continuity_group",
                    "t_certification_event",
                    "m_training_course", "t_training_course_skill", "t_learning_plan", "t_learning_plan_skill",
                    "t_training_enrollment", "t_training_enrollment_expense",
                    "t_engineer_skill_event", "t_project_skill_event", "t_project_position_event",
                    "t_skill_gap_snapshot", "t_skill_tag_alias",
                    "t_engineer_skill_assessment", "t_learning_decision_event"}) {
                assertTableExists(statement, table);
            }

            assertColumnExists(statement, "t_certification_event", "evidence_document_version_id");
            assertColumnExists(statement, "t_certification_event", "evidence_document_hash");
            assertColumnExists(statement, "m_certification", "version");
            assertColumnExists(statement, "m_customer", "tenant_id");
            assertColumnExists(statement, "t_engineer", "tenant_id");
            assertColumnExists(statement, "t_project_ingestion", "tenant_id");
            assertColumnExists(statement, "t_project_ingestion", "version");
            assertColumnExists(statement, "t_candidate", "tenant_id");
            assertColumnExists(statement, "t_candidate", "version");
            assertColumnExists(statement, "t_resume_ingestion", "tenant_id");
            assertColumnExists(statement, "t_resume_ingestion", "version");
            assertColumnExists(statement, "t_contract", "tenant_id");
            assertIndexExists(statement, "t_project_ingestion", "idx_project_ingestion_tenant_status");
            assertTableExists(statement, "nf02_nf03_ownership_repair_queue");
            for (String column : new String[]{"status", "assignee_user_id", "repair_tenant_id",
                    "resolution_reason", "evidence", "resolved_at", "resolved_by", "last_checked_at"}) {
                assertColumnExists(statement, "nf02_nf03_ownership_repair_queue", column);
            }
            assertColumnExists(statement, "nf02_nf03_ownership_repair_queue", "conflicting_tenant_id");
            assertIndexExists(statement, "nf02_nf03_ownership_repair_queue", "idx_nf02_nf03_repair_status_age");
            assertIndexExists(statement, "nf02_nf03_ownership_repair_queue", "idx_nf02_nf03_repair_conflict");
            assertIndexExists(statement, "m_customer", "idx_customer_tenant_population");
            assertIndexExists(statement, "t_engineer", "idx_engineer_tenant_population");
            assertColumnExists(statement, "t_bp_availability", "tenant_id");
            assertIndexExists(statement, "t_bp_availability", "idx_bp_availability_tenant_population");
            assertIndexExists(statement, "t_engineer_account_link", "idx_engineer_account_link_tenant_owner");
            assertIndexExists(statement, "t_contract", "idx_contract_tenant_customer_status_sales");
            for (String table : new String[]{"t_ai_recommendation_run", "t_ai_recommendation_item",
                    "t_ai_feedback", "t_ai_outcome"}) {
                assertColumnExists(statement, table, "tenant_id");
            }
            assertIndexExists(statement, "t_ai_recommendation_item", "idx_ai_item_tenant_target");
            assertIndexExists(statement, "t_ai_feedback", "idx_ai_feedback_tenant_item");
            assertIndexExists(statement, "t_ai_outcome", "idx_ai_outcome_tenant_item");
            assertColumnExists(statement, "t_learning_plan", "amended_cost_jpy");
            assertColumnExists(statement, "t_learning_plan", "amendment_approval_request_id");
            assertColumnExists(statement, "t_project_position_event", "skills_json");
            assertColumnExists(statement, "t_skill_gap_snapshot", "result_hash");
            assertColumnExists(statement, "t_training_course_skill", "updated_at");
            assertColumnExists(statement, "t_learning_plan_skill", "updated_at");
            assertColumnExists(statement, "t_training_enrollment_expense", "updated_at");
            assertIndexExists(statement, "t_training_course_skill", "uk_course_skill");
            assertIndexExists(statement, "t_learning_plan_skill", "uk_plan_skill");
            assertIndexExists(statement, "t_training_enrollment_expense", "uk_enrollment_expense");
            assertIndexExists(statement, "t_skill_tag_alias", "uk_skill_alias_active");
            assertForeignKeyExists(statement, "t_certification_event", "fk_cert_event_record");
            assertForeignKeyExists(statement, "t_engineer_certification", "fk_eng_cert_continuity_group");
            assertForeignKeyExists(statement, "t_training_enrollment_expense", "fk_enroll_expense_request");
            String expenseTableDdl;
            try (ResultSet resultSet = statement.executeQuery("SHOW CREATE TABLE t_expense_request")) {
                assertTrue(resultSet.next());
                expenseTableDdl = resultSet.getString(2);
            }
            assertTrue(expenseTableDdl.contains("研修費"), "既存ExpenseRequestの研修費カテゴリが許可されること");

            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM m_document_type WHERE code='CERTIFICATION_EVIDENCE'"),
                    "CERTIFICATION_EVIDENCE文書種別seed");
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM m_ai_artifact_version WHERE use_case='LEARNING_CANDIDATE' "
                            + "AND status='ACTIVE' AND deleted_flag=0"),
                    "LEARNING_CANDIDATE artifact seed");
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM m_menu WHERE menu_key='certification-learning-skill-gap'"),
                    "資格・学習・skill gap menu seed");
            assertEquals(3, queryInt(statement,
                    "SELECT COUNT(*) FROM t_role_menu rm JOIN m_menu m ON m.id=rm.menu_id "
                            + "WHERE m.menu_key='certification-learning-skill-gap' "
                            + "AND rm.role IN ('管理者','HR','マネージャー')"),
                    "資格・学習・skill gap role menu seed");
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM m_menu WHERE menu_key='myCertificationLearningGap'"),
                    "本人資格・学習計画menu seed");
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM t_role_menu rm JOIN m_menu m ON m.id=rm.menu_id "
                            + "WHERE m.menu_key='myCertificationLearningGap' AND rm.role='要員'"),
                    "本人資格・学習計画role menu seed");
        }
    }

    @Test
    void MySQLの資格mastertenant付きversionCASは同時更新を一件だけ成功させる() throws Exception {
        migrate();
        String tenantId = "cert-cas-" + UUID.randomUUID();
        String otherTenantId = "cert-other-" + UUID.randomUUID();
        long certificationId;
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO m_certification "
                             + "(tenant_id, issuer_key, name_key, identity_key, display_name) VALUES (?, ?, ?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, tenantId);
            insert.setString(2, "ipa");
            insert.setString(3, "cas-name");
            insert.setString(4, "cas-" + UUID.randomUUID());
            insert.setString(5, "CAS資格");
            insert.executeUpdate();
            try (var keys = insert.getGeneratedKeys()) {
                assertTrue(keys.next());
                certificationId = keys.getLong(1);
            }
        }

        long otherCertificationId;
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO m_certification "
                             + "(tenant_id, issuer_key, name_key, identity_key, display_name) VALUES (?, ?, ?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, otherTenantId);
            insert.setString(2, "ipa");
            insert.setString(3, "other-name");
            insert.setString(4, "other-" + UUID.randomUUID());
            insert.setString(5, "別tenant資格");
            insert.executeUpdate();
            try (var keys = insert.getGeneratedKeys()) {
                assertTrue(keys.next());
                otherCertificationId = keys.getLong(1);
            }
        }

        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement select = connection.prepareStatement(
                     "SELECT COUNT(*) FROM m_certification WHERE id = ? AND tenant_id = ?");
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE m_certification SET display_name = ? "
                             + "WHERE id = ? AND tenant_id = ? AND version = 0")) {
            select.setLong(1, certificationId);
            select.setString(2, otherTenantId);
            try (ResultSet rows = select.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1), "別tenantからtenant-a資格を読めないこと");
            }
            select.setLong(1, otherCertificationId);
            select.setString(2, otherTenantId);
            try (ResultSet rows = select.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1), "別tenant資格は自身のtenantでだけ読めること");
            }
            update.setString(1, "越境更新");
            update.setLong(2, certificationId);
            update.setString(3, otherTenantId);
            assertEquals(0, update.executeUpdate(), "tenant条件なしの越境更新を許さないこと");
        }

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> affectedRows = java.util.Collections.synchronizedList(new ArrayList<>());
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final int workerNo = i;
            Thread worker = new Thread(() -> {
                try (Connection connection = MYSQL.createConnection("");
                     PreparedStatement update = connection.prepareStatement(
                             "UPDATE m_certification SET display_name = ?, version = version + 1 "
                                     + "WHERE id = ? AND tenant_id = ? AND version = 0")) {
                    connection.setAutoCommit(false);
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("CAS workerの開始待機がタイムアウトしました");
                    }
                    update.setString(1, "CAS更新" + workerNo);
                    update.setLong(2, certificationId);
                    update.setString(3, tenantId);
                    affectedRows.add(update.executeUpdate());
                    connection.commit();
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            }, "certification-master-cas-" + workerNo);
            workers.add(worker);
            worker.start();
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        for (Thread worker : workers) {
            worker.join(15_000L);
            assertTrue(!worker.isAlive(), "CAS workerが終了していません");
        }
        assertTrue(failures.isEmpty(), failures.toString());
        assertEquals(List.of(0, 1), affectedRows.stream().sorted().toList());
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            assertEquals(1, queryInt(statement,
                    "SELECT version FROM m_certification WHERE id = " + certificationId));
        }
    }

    @Test
    void MySQLのunresolved修復queueは推測せず監査付きで解決できる() throws Exception {
        migrate();
        String tenantId = "repair-" + UUID.randomUUID();
        long customerId;
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO m_customer (company_name, tenant_id, deleted_flag) VALUES (?, NULL, 0)",
                     Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, "unresolved-customer-" + tenantId);
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                assertTrue(keys.next());
                customerId = keys.getLong(1);
            }
        }

        long queueId;
        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO nf02_nf03_ownership_repair_queue "
                             + "(entity_type, entity_id, reason) VALUES ('CUSTOMER', ?, 'TENANT_UNRESOLVED')",
                     Statement.RETURN_GENERATED_KEYS)) {
            insert.setLong(1, customerId);
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                assertTrue(keys.next());
                queueId = keys.getLong(1);
            }
        }

        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement updateCustomer = connection.prepareStatement(
                     "UPDATE m_customer SET tenant_id = ? WHERE id = ? AND tenant_id IS NULL");
             PreparedStatement resolveQueue = connection.prepareStatement(
                     "UPDATE nf02_nf03_ownership_repair_queue SET status='RESOLVED', repair_tenant_id=?, "
                             + "resolution_reason=?, evidence=?, resolved_at=NOW(), last_checked_at=NOW() "
                             + "WHERE id=? AND status='PENDING'")) {
            updateCustomer.setString(1, tenantId);
            updateCustomer.setLong(2, customerId);
            assertEquals(1, updateCustomer.executeUpdate());
            resolveQueue.setString(1, tenantId);
            resolveQueue.setString(2, "契約台帳でtenantを確認");
            resolveQueue.setString(3, "ticket=" + queueId);
            resolveQueue.setLong(4, queueId);
            assertEquals(1, resolveQueue.executeUpdate());
        }

        try (Connection connection = MYSQL.createConnection("");
             PreparedStatement select = connection.prepareStatement(
                     "SELECT c.tenant_id, q.status, q.repair_tenant_id, q.evidence "
                             + "FROM m_customer c JOIN nf02_nf03_ownership_repair_queue q "
                             + "ON q.entity_type='CUSTOMER' AND q.entity_id=c.id WHERE c.id=?")) {
            select.setLong(1, customerId);
            try (ResultSet rows = select.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(tenantId, rows.getString(1));
                assertEquals("RESOLVED", rows.getString(2));
                assertEquals(tenantId, rows.getString(3));
                assertTrue(rows.getString(4).contains("ticket=" + queueId));
            }
        }
    }

    private void migrate() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
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

    private String queryString(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }
}
