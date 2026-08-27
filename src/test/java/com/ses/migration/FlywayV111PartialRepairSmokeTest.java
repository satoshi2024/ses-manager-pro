package com.ses.migration;

import com.ses.test.MySQLContainer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URL;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REV-RP-P1-002: V111 が途中 DDL（t_engineer.version のみ成功）で失敗したあとも、
 * failed history → repair → remigrate で冪等完走できることを検証する。
 *
 * <p>U111（undo）は作らない。本テストはクラスパスに U111 が無いことも確認する。
 */
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class FlywayV111PartialRepairSmokeTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_v111_partial")
            .withUsername("root")
            .withPassword("ses");

    @Test
    void V111部分適用のfailed履歴からrepair後に再migrateできる() throws Exception {
        // U111（undo）は意図的に存在しない（REV-RP-P1-002）
        URL undo = Thread.currentThread().getContextClassLoader()
                .getResource("db/migration/U111__optimistic_lock_version_core_entities.sql");
        assertNull(undo, "U111 undo migration を追加してはいけない");

        // V110 まで適用（V111 直前: lifecycle V109 + admin boundary V110）
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("110")
                .load()
                .migrate();

        long engineerCount;
        long customerCount;
        long workRecordCount;
        long engineerId;

        try (Connection connection = MYSQL.createConnection(""); Statement st = connection.createStatement()) {
            // FK を満たす最小シード（顧客→案件→要員→契約→実績）
            st.executeUpdate("INSERT INTO m_customer (company_name) VALUES ('V110-partial-customer')");
            st.executeUpdate("INSERT INTO t_engineer (full_name, employment_type, status) "
                    + "VALUES ('V110-partial-engineer', '正社員', 'Bench')");
            st.executeUpdate("INSERT INTO t_project (project_name, customer_id, status) "
                    + "SELECT 'V110-partial-project', id, '募集中' FROM m_customer "
                    + "WHERE company_name='V110-partial-customer' LIMIT 1");
            st.executeUpdate("INSERT INTO t_contract (contract_no, engineer_id, project_id, customer_id, "
                    + "start_date, selling_price, cost_price, status) "
                    + "SELECT 'V110-PARTIAL-1', e.id, p.id, p.customer_id, '2026-01-01', 500000, 300000, '準備中' "
                    + "FROM t_engineer e, t_project p "
                    + "WHERE e.full_name='V110-partial-engineer' AND p.project_name='V110-partial-project' LIMIT 1");
            st.executeUpdate("INSERT INTO t_work_record (contract_id, work_month, actual_hours, status) "
                    + "SELECT id, '2026-01', 160.0, '入力中' FROM t_contract WHERE contract_no='V110-PARTIAL-1'");

            engineerCount = countRows(st, "t_engineer");
            customerCount = countRows(st, "m_customer");
            workRecordCount = countRows(st, "t_work_record");

            try (ResultSet rs = st.executeQuery(
                    "SELECT id FROM t_engineer WHERE full_name='V110-partial-engineer'")) {
                assertTrue(rs.next());
                engineerId = rs.getLong(1);
            }

            // 部分 DDL 成功を再現: t_engineer にだけ version を先行追加
            assertTrue(!hasColumn(st, "t_engineer", "version"));
            st.execute("ALTER TABLE t_engineer ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '楽観ロック'");
            assertTrue(hasColumn(st, "t_engineer", "version"));
            assertTrue(!hasColumn(st, "m_customer", "version"));
            assertTrue(!hasColumn(st, "t_work_record", "version"));

            // V111 failed history を挿入（非冪等 ALTER が途中で落ちた状態）
            st.executeUpdate("INSERT INTO flyway_schema_history "
                    + "(installed_rank, version, description, type, script, checksum, installed_by, "
                    + "installed_on, execution_time, success) "
                    + "SELECT COALESCE(MAX(installed_rank), 0) + 1, '111', "
                    + "'optimistic lock version core entities', 'SQL', "
                    + "'V111__optimistic_lock_version_core_entities.sql', 0, CURRENT_USER(), NOW(), 1, 0 "
                    + "FROM flyway_schema_history");
            assertEquals(1, queryInt(st,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='111' AND success=0"));
        }

        Flyway repaired = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load();
        repaired.repair();
        repaired.migrate();
        repaired.validate();

        try (Connection connection = MYSQL.createConnection(""); Statement st = connection.createStatement()) {
            assertTrue(hasColumn(st, "t_engineer", "version"));
            assertTrue(hasColumn(st, "m_customer", "version"));
            assertTrue(hasColumn(st, "t_work_record", "version"));
            assertEquals(1, queryInt(st,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='111' AND success=1"));
            assertEquals(0, queryInt(st,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='111' AND success=0"));

            // 行数は不変、既存行の version は DEFAULT 0
            assertEquals(engineerCount, countRows(st, "t_engineer"));
            assertEquals(customerCount, countRows(st, "m_customer"));
            assertEquals(workRecordCount, countRows(st, "t_work_record"));
            assertEquals(0, queryInt(st,
                    "SELECT version FROM t_engineer WHERE full_name='V110-partial-engineer'"));
            assertEquals(0, queryInt(st,
                    "SELECT version FROM m_customer WHERE company_name='V110-partial-customer'"));
            assertEquals(0, queryInt(st,
                    "SELECT wr.version FROM t_work_record wr "
                            + "JOIN t_contract c ON c.id = wr.contract_id "
                            + "WHERE c.contract_no='V110-PARTIAL-1'"));

            // MyBatis-Plus updateById(@Version) 相当: version=0 の楽観更新が成功し version=1 になる
            int updated = st.executeUpdate(
                    "UPDATE t_engineer SET full_name='V110-partial-engineer', version = 1 "
                            + "WHERE id = " + engineerId + " AND version = 0");
            assertEquals(1, updated, "楽観ロック初回更新は1行成功するはず");
            assertEquals(1, queryInt(st,
                    "SELECT version FROM t_engineer WHERE id = " + engineerId));
        }
    }

    private boolean hasColumn(Statement st, String table, String column) throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='"
                        + table + "' AND column_name='" + column + "'")) {
            return rs.next();
        }
    }

    private long countRows(Statement st, String table) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private int queryInt(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }
}
