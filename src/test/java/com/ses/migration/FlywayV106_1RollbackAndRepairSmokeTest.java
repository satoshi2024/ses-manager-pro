package com.ses.migration;

import com.ses.test.MySQLContainer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S15 Stage B (R1-P1-04): V106.1 forward repair migration と完全ロールバックを実MySQL 8 で検証する。
 *
 * <p>対象形状 (design.md §1.2 の 5形状契約):
 * <ul>
 *   <li>fresh V106 (V1 consolidated) への V106.1 適用前に runbook が SIGNAL で拒否されること</li>
 *   <li>legacy V106 -> V106.1 (NULL法人 active 重複2件の backfill / survivorship)</li>
 *   <li>各 partial 中断点 (追加列の一部だけが存在する形状・存在しない列はガードでスキップ) からの runbook rollback</li>
 *   <li>rollback 後の V106 期待形状完全一致 (全11列 DROP・旧 UNIQUE 復元・全行復元・backup 削除)</li>
 *   <li>flyway repair -> V106.1 再適用 (差分なし正常終了)</li>
 * </ul>
 * ロールバックは {@code sql/runbook/v106_1-rollback.sql} の実スクリプトを実行する。
 */
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class FlywayV106_1RollbackAndRepairSmokeTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_v106_smoke")
            .withUsername("root")
            .withPassword("ses")
            .withStartupTimeout(Duration.ofMinutes(10))
            .withStartupAttempts(3);

    private static final List<String> CONN_ADDED_COLUMNS = List.of(
            "token_version", "refresh_lease_token", "refresh_lease_expires_at", "legal_entity_key", "active_slot");
    private static final List<String> JOB_ADDED_COLUMNS = List.of(
            "payload_snapshot", "lease_token", "lease_expires_at", "tenant_id", "legal_entity_id", "organization_id");

    @Test
    void migrateToV106_1_rollbackToV106_andRepairReapply() throws Exception {
        // Step 0: fresh DB (V106 未適用) で runbook が SIGNAL により拒否されること (誤実行ガード)
        try (Connection conn = MYSQL.createConnection(""); Statement st = conn.createStatement()) {
            assertThrows(java.sql.SQLException.class, () -> executeRunbook(st),
                    "backupテーブル不在 (V106.1未適用) では runbook が SIGNAL で拒否されること");
            // Flyway migrate 前に schema を空へ戻す (プロシージャを削除)
            st.execute("DROP PROCEDURE IF EXISTS v106_1_rollback");
        }

        // Step 1: V106 baseline まで migrate
        Flyway flywayV106 = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("106")
                .load();
        flywayV106.migrate();

        // Step 2: V1 (consolidated) は最新形状を持つため、Legacy V106 形状へ戻してから重複データを投入する
        //   (旧 UNIQUE (tenant_id, legal_entity_id, provider, product, deleted_flag) では
        //    NULL 法人の active 行が複数存在し得る)
        try (Connection conn = MYSQL.createConnection(""); Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE m_integration_connection DROP INDEX uk_int_conn");
            st.execute("ALTER TABLE m_integration_connection DROP COLUMN active_slot");
            st.execute("ALTER TABLE m_integration_connection DROP COLUMN legal_entity_key");
            st.execute("ALTER TABLE m_integration_connection DROP COLUMN refresh_lease_expires_at");
            st.execute("ALTER TABLE m_integration_connection DROP COLUMN refresh_lease_token");
            st.execute("ALTER TABLE m_integration_connection DROP COLUMN token_version");
            st.execute("ALTER TABLE m_integration_connection ADD UNIQUE KEY uk_int_conn (tenant_id, legal_entity_id, provider, product, deleted_flag)");
            st.execute("ALTER TABLE t_integration_job DROP COLUMN payload_snapshot");
            st.execute("ALTER TABLE t_integration_job DROP COLUMN lease_token");
            st.execute("ALTER TABLE t_integration_job DROP COLUMN lease_expires_at");
            st.execute("ALTER TABLE t_integration_job DROP COLUMN tenant_id");
            st.execute("ALTER TABLE t_integration_job DROP COLUMN legal_entity_id");
            st.execute("ALTER TABLE t_integration_job DROP COLUMN organization_id");

            // NULL法人 active 重複2件 + ジョブを投入 (Legacy V106 で許容される形状)
            st.executeUpdate("INSERT INTO m_integration_connection " +
                    "(id, tenant_id, legal_entity_id, provider, product, external_company_id, company_name, status, encrypted_tokens, expires_at, deleted_flag, version) " +
                    "VALUES (8001, 'tenant_dup', NULL, 'freee', 'accounting', 101, 'Corp A', 'CONNECTED', 'token1', DATE_ADD(NOW(), INTERVAL 1 DAY), 0, 1)");
            st.executeUpdate("INSERT INTO m_integration_connection " +
                    "(id, tenant_id, legal_entity_id, provider, product, external_company_id, company_name, status, encrypted_tokens, expires_at, deleted_flag, version) " +
                    "VALUES (8002, 'tenant_dup', NULL, 'freee', 'accounting', 102, 'Corp B', 'CONNECTED', 'token2', DATE_ADD(NOW(), INTERVAL 2 DAY), 0, 1)");
            st.executeUpdate("INSERT INTO t_integration_job " +
                    "(id, connection_id, target_type, target_id, job_type, idempotency_key, payload_hash, status, attempt_count, max_attempts, version, deleted_flag) " +
                    "VALUES (9001, 1, 'INVOICE', 1001, 'SALES_INVOICE_SYNC', 'idemp_9001', 'hash_9001', 'PENDING', 0, 5, 1, 0)");
        }

        // Step 3: V106.1 適用 (backfill 実行)
        Flyway flywayV106_1 = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("106.1")
                .load();
        flywayV106_1.migrate();

        // Step 4: V106.1 形状 assert
        try (Connection conn = MYSQL.createConnection(""); Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM m_integration_connection_backup_v106_1")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "重複2件のうち1件が退避テーブルへ保存されること");
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT id, deleted_flag, legal_entity_key, active_slot, token_version FROM m_integration_connection WHERE id IN (8001, 8002) ORDER BY id")) {
                assertTrue(rs.next());
                assertEquals(8001, rs.getLong("id"));
                assertEquals(1, rs.getInt("deleted_flag"), "survivor 以外は論理削除されること");
                assertTrue(rs.next());
                assertEquals(8002, rs.getLong("id"));
                assertEquals(0, rs.getInt("deleted_flag"));
                assertEquals(0L, rs.getLong("legal_entity_key"));
                assertEquals(1, rs.getInt("active_slot"));
                assertEquals(1, rs.getInt("token_version"));
            }
            for (String col : CONN_ADDED_COLUMNS) {
                assertTrue(hasColumn(st, "m_integration_connection", col), "connection列 " + col + " が存在すること");
            }
            for (String col : JOB_ADDED_COLUMNS) {
                assertTrue(hasColumn(st, "t_integration_job", col), "job列 " + col + " が存在すること");
            }
            assertEquals(Set.of("tenant_id", "legal_entity_key", "provider", "product", "active_slot"),
                    indexColumns(st, "m_integration_connection", "uk_int_conn"), "新UNIQUEの列構成");
        }

        // Step 5: partial 中断点シミュレーション
        //   V106.1 が途中失敗した状態: Step 4 (追加列) の一部だけが適用され、Step 5 (新UNIQUE) 前で中断。
        //   (存在しない列は runbook の information_schema ガードで 'SELECT 1' スキップされる)
        try (Connection conn = MYSQL.createConnection(""); Statement st = conn.createStatement()) {
            // 新UNIQUEが生成列を参照しているため、先に新UNIQUEを解除してから列を落とす (MySQLの再チェック回避)
            st.execute("ALTER TABLE m_integration_connection DROP INDEX uk_int_conn");
            st.execute("ALTER TABLE m_integration_connection DROP COLUMN active_slot");
            st.execute("ALTER TABLE m_integration_connection DROP COLUMN legal_entity_key");
            st.execute("ALTER TABLE t_integration_job DROP COLUMN lease_token");
            st.execute("ALTER TABLE t_integration_job DROP COLUMN organization_id");
            st.execute("DELETE FROM flyway_schema_history WHERE version = '106.1'");
            st.execute("INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, installed_by, installed_on, execution_time, success) "
                    + "SELECT COALESCE(MAX(installed_rank), 0) + 1, '106.1', 'accounting integration snapshot and slot', 'SQL', "
                    + "'V106_1__accounting_integration_snapshot_and_slot.sql', CURRENT_USER(), NOW(), 1, 0 FROM flyway_schema_history");
            assertFalse(hasColumn(st, "m_integration_connection", "active_slot"), "partial状態: active_slot 未追加のまま");
            assertTrue(hasColumn(st, "m_integration_connection", "token_version"), "partial状態: token_version は追加済み");

            // Step 5b: 実 runbook を実行して完全ロールバック
            executeRunbook(st);
        }

        // Step 6: V106 期待形状完全一致 assert
        try (Connection conn = MYSQL.createConnection(""); Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM m_integration_connection WHERE tenant_id = 'tenant_dup' AND deleted_flag = 0")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1), "ロールバックで重複2件とも active に復元されること");
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'm_integration_connection_backup_v106_1'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "バックアップテーブルが削除されること");
            }
            for (String col : CONN_ADDED_COLUMNS) {
                assertFalse(hasColumn(st, "m_integration_connection", col), "rollback後にconnection列 " + col + " が残っています");
            }
            for (String col : JOB_ADDED_COLUMNS) {
                assertFalse(hasColumn(st, "t_integration_job", col), "rollback後にjob列 " + col + " が残っています");
            }
            assertEquals(Set.of("tenant_id", "legal_entity_id", "provider", "product", "deleted_flag"),
                    indexColumns(st, "m_integration_connection", "uk_int_conn"), "旧UNIQUEの列構成が完全復元されること");
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '106.1'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "rollback runbookで flyway history の 106.1 行が削除されること");
            }
        }

        // Step 7: flyway repair -> V106.1 再適用 (差分なしで正常終了)
        Flyway flywayRepairAndReapply = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("106.1")
                .load();
        flywayRepairAndReapply.repair();
        flywayRepairAndReapply.migrate();
        flywayRepairAndReapply.validate();

        // Step 8: 再適用後の V106.1 完全形状 assert
        try (Connection conn = MYSQL.createConnection(""); Statement st = conn.createStatement()) {
            for (String col : CONN_ADDED_COLUMNS) {
                assertTrue(hasColumn(st, "m_integration_connection", col), "再適用後にconnection列 " + col + " が存在すること");
            }
            for (String col : JOB_ADDED_COLUMNS) {
                assertTrue(hasColumn(st, "t_integration_job", col), "再適用後にjob列 " + col + " が存在すること");
            }
            assertEquals(Set.of("tenant_id", "legal_entity_key", "provider", "product", "active_slot"),
                    indexColumns(st, "m_integration_connection", "uk_int_conn"), "再適用後に新UNIQUEが存在すること");
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '106.1' AND success = 1")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "再適用後に success=1 の 106.1 行があること");
            }
        }
    }

    /** runbook を実ファイルから読み込み、DROP/CREATE PROCEDURE と CALL を実行する。 */
    private void executeRunbook(Statement st) throws Exception {
        String runbook = Files.readString(Path.of("sql", "runbook", "v106_1-rollback.sql"), StandardCharsets.UTF_8);
        for (String block : runbook.replace("DELIMITER $$", "").replace("DELIMITER ;", "").split("\\$\\$")) {
            String executable = Arrays.stream(block.split("\\R"))
                    .filter(line -> !line.trim().startsWith("--"))
                    .reduce("", (a, b) -> a + b + "\n").trim();
            if (executable.startsWith("DROP PROCEDURE") || executable.startsWith("CREATE PROCEDURE")
                    || executable.startsWith("CALL ")) {
                st.execute(executable);
            }
        }
    }

    private boolean hasColumn(Statement st, String table, String column) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = '" + table + "' AND column_name = '" + column + "'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }

    /** 指定インデックスの列順セットを返す。 */
    private Set<String> indexColumns(Statement st, String table, String index) throws Exception {
        Set<String> cols = new HashSet<>();
        try (ResultSet rs = st.executeQuery("SELECT column_name FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = '" + table + "' AND index_name = '" + index + "' "
                + "ORDER BY seq_in_index")) {
            while (rs.next()) {
                cols.add(rs.getString("column_name"));
            }
        }
        return cols;
    }
}
