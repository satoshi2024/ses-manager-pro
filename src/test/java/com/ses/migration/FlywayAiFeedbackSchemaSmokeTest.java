package com.ses.migration;

import com.ses.test.MySQLContainer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T110 の MySQL smoke。V108 の ACTIVE 一意・outcome 冪等・tenant/raw 不在を検証する。 */
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class FlywayAiFeedbackSchemaSmokeTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_ai_feedback_v108")
            .withUsername("root")
            .withPassword("ses");

    @Test
    void AiFeedbackテーブルがMySQLで構築できACTIVE一意とoutcome冪等が効く() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = MYSQL.createConnection("");
             Statement statement = connection.createStatement()) {
            for (String table : new String[] {
                    "m_ai_artifact_version", "t_ai_recommendation_run",
                    "t_ai_recommendation_item", "t_ai_feedback",
                    "t_ai_outcome", "t_ai_evaluation"}) {
                assertEquals(1, queryInt(statement,
                        "SELECT COUNT(*) FROM information_schema.tables "
                                + "WHERE table_schema=DATABASE() AND table_name='" + table + "'"),
                        table + " が存在するはず");
                assertEquals(0, queryInt(statement,
                        "SELECT COUNT(*) FROM information_schema.columns "
                                + "WHERE table_schema=DATABASE() AND table_name='" + table
                                + "' AND column_name='tenant_id'"),
                        table + " に tenant_id があってはならない");
            }
            assertEquals(0, queryInt(statement,
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_schema=DATABASE() AND table_name='t_ai_recommendation_run' "
                            + "AND column_name IN ('raw_prompt','request_params','prompt')"));
            assertTrue(queryInt(statement,
                    "SELECT COUNT(*) FROM information_schema.statistics "
                            + "WHERE table_schema=DATABASE() AND table_name='m_ai_artifact_version' "
                            + "AND index_name='uk_ai_artifact_active_use_case'") > 0);
            assertTrue(queryInt(statement,
                    "SELECT COUNT(*) FROM information_schema.statistics "
                            + "WHERE table_schema=DATABASE() AND table_name='t_ai_outcome' "
                            + "AND index_name='uk_ai_outcome_idempotent'") > 0);
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_schema=DATABASE() AND table_name='t_ai_outcome' "
                            + "AND column_name='original_end_date'"));
            assertEquals(3, queryInt(statement,
                    "SELECT COUNT(*) FROM m_ai_artifact_version WHERE status='ACTIVE'"));

            SQLException duplicateActive = assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO m_ai_artifact_version "
                            + "(use_case, provider, model_name, prompt_version, rule_version, config_hash, "
                            + "status, status_version) VALUES "
                            + "('MATCHING','mock','x','x','mock',"
                            + "'0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',"
                            + "'ACTIVE',0)"));
            assertTrue(duplicateActive.getMessage() == null
                    || duplicateActive.getMessage().toLowerCase().contains("duplicate")
                    || duplicateActive.getErrorCode() == 1062);

            long versionId = queryLong(statement,
                    "SELECT id FROM m_ai_artifact_version WHERE use_case='MATCHING' AND status='ACTIVE' LIMIT 1");
            statement.execute("INSERT INTO t_ai_recommendation_run "
                    + "(trace_id, use_case, artifact_version_id, input_hash, status, status_version) VALUES "
                    + "('11111111-1111-1111-1111-111111111111','MATCHING'," + versionId + ","
                    + "'0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',"
                    + "'SUCCEEDED',0)");
            long runId = queryLong(statement, "SELECT id FROM t_ai_recommendation_run "
                    + "WHERE trace_id='11111111-1111-1111-1111-111111111111'");
            statement.execute("INSERT INTO t_ai_recommendation_item "
                    + "(run_id, rank_no, target_type, target_id, selected_flag) VALUES ("
                    + runId + ",1,'ENGINEER',1,1)");
            long itemId = queryLong(statement,
                    "SELECT id FROM t_ai_recommendation_item WHERE run_id=" + runId);
            statement.execute("INSERT INTO t_ai_outcome "
                    + "(item_id, outcome_type, source_type, source_id, occurred_at) VALUES ("
                    + itemId + ",'WIN','PROPOSAL',1,NOW())");
            SQLException duplicateOutcome = assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO t_ai_outcome "
                            + "(item_id, outcome_type, source_type, source_id, occurred_at) VALUES ("
                            + itemId + ",'WIN','PROPOSAL',1,NOW())"));
            assertTrue(duplicateOutcome.getErrorCode() == 1062
                    || (duplicateOutcome.getMessage() != null
                    && duplicateOutcome.getMessage().toLowerCase().contains("duplicate")));
        }
    }

    private static int queryInt(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private static long queryLong(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }
}
