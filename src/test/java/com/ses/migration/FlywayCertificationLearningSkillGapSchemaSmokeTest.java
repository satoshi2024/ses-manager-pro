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
    void V116からV131のNF03_shapeがMySQLで成立する() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            String latestVersion = queryString(statement,
                    "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1");
            assertEquals("131", latestVersion, "最新マイグレーションバージョンは131であること");

            for (String table : new String[]{
                    "m_certification", "m_certification_alias", "t_engineer_certification",
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
