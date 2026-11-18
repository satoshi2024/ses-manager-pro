package com.ses.migration;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RB-PRERUN 4: {@code db/migration-dev/V172_1} を、V145のseedと同じ形の最小表（H2・MySQL互換mode）で実行し、
 * seedが入れたrunだけにtenant 'default' を補うこと・推測で他の行を埋めないこと・再実行しても変わらないことを確かめる。
 * V173の停止条件（親runからtenantを写せないitemが残る）を同じ表で数え、補った後は0件になることも見る。
 * 実MySQLでdev profileのlocationsを空DBからLATESTまで適用する確認は {@code FlywayDevProfileFreshInstallMySqlTest}。
 *
 * <p>Springを起動せず、試験ごとに専用のin-memory DBを作って閉じる（共有DBへ行を残さない）。</p>
 */
class DevSeedAiRecommendationTenantH2Test {

    private static final String SCRIPT = "db/migration-dev/V172_1__seed_r3_ai_recommendation_tenant.sql";
    private static final String SEED_TRACE = "550e8400-e29b-41d4-a716-446655440001";

    @Test
    void seedのrunだけにtenantを補い他の行は推測で埋めず再実行しても変わらない() throws Exception {
        try (Connection connection = open("v172_1_seed"); Statement st = connection.createStatement()) {
            createTables(st);
            st.execute("INSERT INTO t_ai_recommendation_run (id, trace_id, tenant_id) VALUES (85071, '" + SEED_TRACE + "', NULL)");
            // seed以外のtenant不明の行（同じtrace_idでもIDが違う行を含む）は補わない（V173の推測しない方針を保つ）
            st.execute("INSERT INTO t_ai_recommendation_run (id, trace_id, tenant_id) VALUES (85072, '" + SEED_TRACE + "', NULL)");
            st.execute("INSERT INTO t_ai_recommendation_run (id, trace_id, tenant_id) VALUES (90001, 'app-created', '')");
            st.execute("INSERT INTO t_ai_recommendation_run (id, trace_id, tenant_id) VALUES (90002, 'app-created-2', 'tenant-b')");
            st.execute("INSERT INTO t_ai_recommendation_item (id, run_id) VALUES (85081, 85071)");
            assertEquals(1, unresolvedItems(st), "補う前はV173がseedのitemのtenantを特定できない");

            runScript(st);
            runScript(st);

            assertEquals("default", tenant(st, 85071));
            assertNull(tenant(st, 85072), "seedのrun以外は補わない");
            assertEquals("", tenant(st, 90001), "seed以外の空tenantは補わない");
            assertEquals("tenant-b", tenant(st, 90002), "tenantが入っている行は変更しない");
            assertEquals(0, unresolvedItems(st), "補った後はseedのitemがrunからtenantを写せる");
        }
    }

    @Test
    void seedのrunに既にtenantがあれば上書きしない() throws Exception {
        try (Connection connection = open("v172_1_existing"); Statement st = connection.createStatement()) {
            createTables(st);
            st.execute("INSERT INTO t_ai_recommendation_run (id, trace_id, tenant_id) VALUES (85071, '" + SEED_TRACE + "', 'tenant-x')");

            runScript(st);

            assertEquals("tenant-x", tenant(st, 85071));
        }
    }

    private static Connection open(String name) throws Exception {
        return DriverManager.getConnection("jdbc:h2:mem:" + name + "_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=0");
    }

    private static void createTables(Statement st) throws Exception {
        st.execute("CREATE TABLE t_ai_recommendation_run (id BIGINT PRIMARY KEY, trace_id VARCHAR(64), tenant_id VARCHAR(100))");
        st.execute("CREATE TABLE t_ai_recommendation_item (id BIGINT PRIMARY KEY, run_id BIGINT NOT NULL)");
    }

    private static void runScript(Statement st) throws Exception {
        String sql = new ClassPathResource(SCRIPT).getContentAsString(StandardCharsets.UTF_8).lines()
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining("\n")).trim();
        assertTrue(sql.endsWith(";") && sql.indexOf(';') == sql.length() - 1, "V172_1は1文だけであること");
        st.executeUpdate(sql.substring(0, sql.length() - 1));
    }

    private static String tenant(Statement st, long runId) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT tenant_id FROM t_ai_recommendation_run WHERE id = " + runId)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    /** V173の停止条件と同じ: 親runのtenantが無いitem。 */
    private static int unresolvedItems(Statement st) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM t_ai_recommendation_item i "
                + "JOIN t_ai_recommendation_run r ON r.id = i.run_id WHERE r.tenant_id IS NULL OR r.tenant_id = ''")) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }
}
