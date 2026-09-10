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
 * NF-03 F1-1〜A2のMySQL smoke。V116〜V128のDDL shape・seed・FKを実MySQLで検証する。
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
            assertTrue(Integer.parseInt(latestVersion) >= 154,
                    "NF-03 continuity/budget migration以降まで適用されていること");

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

            assertColumnExists(statement, "t_certification_continuity_group", "tenant_id");
            assertColumnExists(statement, "t_certification_continuity_group", "engineer_id");
            assertColumnExists(statement, "t_certification_continuity_group", "certification_id");
            assertColumnExists(statement, "t_certification_continuity_group", "updated_at");
            assertIndexExists(statement, "t_certification_continuity_group", "uk_cert_continuity_group_ident");
            assertForeignKeyExists(statement, "t_certification_continuity_group", "fk_cert_continuity_group_eng");
            assertForeignKeyExists(statement, "t_certification_continuity_group", "fk_cert_continuity_group_cert");
            assertForeignKeyExists(statement, "t_engineer_certification", "fk_eng_cert_continuity_group");
            assertCheckConstraintExists(statement, "chk_eng_cert_current_holder");

            assertColumnExists(statement, "t_certification_event", "evidence_document_version_id");
            assertColumnExists(statement, "t_certification_event", "evidence_document_hash");
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
    void MySQL並行作成_複数の取得chainでcontinuity_group_idが重複衝突しないこと() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = MYSQL.createConnection("")) {
            long engineerId;
            long certificationId;
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("INSERT INTO t_engineer (full_name, employment_type, status, created_at, updated_at) "
                        + "VALUES ('並行テスト要員', '正社員', 'Bench', NOW(), NOW())", Statement.RETURN_GENERATED_KEYS);
                try (ResultSet rs = st.getGeneratedKeys()) {
                    assertTrue(rs.next());
                    engineerId = rs.getLong(1);
                }

                st.executeUpdate("INSERT INTO m_certification (tenant_id, issuer_key, name_key, identity_key, display_name, created_at, updated_at) "
                        + "VALUES ('tenant-smoke', 'IPA', 'AP', 'ident-ap', '応用情報', NOW(), NOW())", Statement.RETURN_GENERATED_KEYS);
                try (ResultSet rs = st.getGeneratedKeys()) {
                    assertTrue(rs.next());
                    certificationId = rs.getLong(1);
                }
            }

            int threadCount = 10;
            java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
            java.util.concurrent.ConcurrentHashMap<Long, Boolean> allocatedGroupIds = new java.util.concurrent.ConcurrentHashMap<>();
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount);
            java.util.concurrent.atomic.AtomicInteger failureCount = new java.util.concurrent.atomic.AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try (Connection conn = MYSQL.createConnection("")) {
                        try (Statement st = conn.createStatement()) {
                            st.executeUpdate("INSERT INTO t_certification_continuity_group "
                                    + "(tenant_id, engineer_id, certification_id, created_at, updated_at, created_by, deleted_flag) "
                                    + "VALUES ('tenant-smoke', " + engineerId + ", " + certificationId + ", NOW(), NOW(), 1, 0)",
                                    Statement.RETURN_GENERATED_KEYS);
                            try (ResultSet rs = st.getGeneratedKeys()) {
                                if (rs.next()) {
                                    long gid = rs.getLong(1);
                                    allocatedGroupIds.put(gid, Boolean.TRUE);
                                }
                            }
                        }
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(30, java.util.concurrent.TimeUnit.SECONDS), "並行作成がタイムアウトしないこと");
            executor.shutdown();
            assertEquals(0, failureCount.get(), "並行作成でエラーが発生しないこと");
            assertEquals(threadCount, allocatedGroupIds.size(), "並行生成された continuity_group_id はすべて一意で衝突しないこと");

            long continuityGroupId = allocatedGroupIds.keySet().iterator().next();
            long certRecord1Id;
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("INSERT INTO t_engineer_certification "
                        + "(tenant_id, engineer_id, certification_id, continuity_group_id, acquired_on, record_state, current_flag, current_holder_key, created_at, updated_at) "
                        + "VALUES ('tenant-smoke', " + engineerId + ", " + certificationId + ", " + continuityGroupId + ", '2026-01-01', 'ACTIVE', 1, " + continuityGroupId + ", NOW(), NOW())",
                        Statement.RETURN_GENERATED_KEYS);
                try (ResultSet rs = st.getGeneratedKeys()) {
                    assertTrue(rs.next());
                    certRecord1Id = rs.getLong(1);
                }

                // renew: 旧レコードを SUPERSEDED (current_flag=0, current_holder_key=NULL) に更新し、新レコードを同一 continuity_group_id で作成
                st.executeUpdate("UPDATE t_engineer_certification SET record_state='SUPERSEDED', current_flag=0, current_holder_key=NULL "
                        + "WHERE id=" + certRecord1Id);
                st.executeUpdate("INSERT INTO t_engineer_certification "
                        + "(tenant_id, engineer_id, certification_id, continuity_group_id, acquired_on, record_state, current_flag, current_holder_key, created_at, updated_at) "
                        + "VALUES ('tenant-smoke', " + engineerId + ", " + certificationId + ", " + continuityGroupId + ", '2027-01-01', 'ACTIVE', 1, " + continuityGroupId + ", NOW(), NOW())");

                int activeHolderCount = queryInt(st, "SELECT COUNT(*) FROM t_engineer_certification WHERE continuity_group_id=" + continuityGroupId + " AND current_flag=1");
                assertEquals(1, activeHolderCount, "同一continuity groupで有効なcurrent holderは常に1件であること");

                // CHECK制約違反 (current_flag=1 かつ current_holder_key IS NULL) の拒絶を検証
                boolean checkFailed = false;
                try {
                    st.executeUpdate("INSERT INTO t_engineer_certification "
                            + "(tenant_id, engineer_id, certification_id, continuity_group_id, acquired_on, record_state, current_flag, current_holder_key, created_at, updated_at) "
                            + "VALUES ('tenant-smoke', " + engineerId + ", " + certificationId + ", " + continuityGroupId + ", '2028-01-01', 'ACTIVE', 1, NULL, NOW(), NOW())");
                } catch (Exception e) {
                    checkFailed = true;
                }
                assertTrue(checkFailed, "MySQLのchk_eng_cert_current_holder制約によりcurrent_flag=1かつcurrent_holder_key IS NULLは拒絶されること");

                // 外部キー違反 (存在しない continuity_group_id) の拒絶を検証
                boolean fkFailed = false;
                try {
                    st.executeUpdate("INSERT INTO t_engineer_certification "
                            + "(tenant_id, engineer_id, certification_id, continuity_group_id, acquired_on, record_state, current_flag, current_holder_key, created_at, updated_at) "
                            + "VALUES ('tenant-smoke', " + engineerId + ", " + certificationId + ", 999999999, '2028-01-01', 'DRAFT', 0, NULL, NOW(), NOW())");
                } catch (Exception e) {
                    fkFailed = true;
                }
                assertTrue(fkFailed, "MySQLのfk_eng_cert_continuity_group制約により存在しないcontinuity_group_idは拒絶されること");
            }
        }
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

    private void assertCheckConstraintExists(Statement statement, String constraint) throws Exception {
        assertTrue(queryInt(statement, "SELECT COUNT(*) FROM information_schema.check_constraints "
                + "WHERE constraint_schema=DATABASE() AND constraint_name='" + constraint + "'") == 1,
                constraint + "がCHECK制約として存在するはず");
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
