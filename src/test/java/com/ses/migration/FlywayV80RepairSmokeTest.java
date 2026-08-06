package com.ses.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V80専用の実MySQL migration gate（R09-P2-02対応）。
 *
 * <p>V80のrepair-safe backfill設計（markerテーブル）は主に静的推理で担保されていたため、
 * 「V80途中失敗 → flyway repair → 再適用」を実MySQL fixtureで再現し、次の最終値を証明する。
 * <ol>
 *   <li>marker固定（INSERT）・UPDATE適用後にV80が途中失敗したpartial状態を再現する。</li>
 *   <li>失敗中に作られた新規契約は、repair後の再適用でbackfillに巻き込まれず
 *       {@code acceptance_required=1}（検収要）のままである。</li>
 *   <li>既存契約は{@code acceptance_required=0}＋固定理由のまま維持される。</li>
 *   <li>metadataは最終形（t_acceptance / UNIQUE / FK / NOT NULL DEFAULT 1 / MODIFY収束）へ収束する。</li>
 * </ol>
 *
 * <p>DB履歴と適用scriptを同一sourceにするため、全migrationをtemp dirへコピーし、
 * そのfilesystem locationだけをFlywayへ渡す（classpathの一時アーティファクト混入を避ける）。
 * V80はR09-P2-02対応でmarker固定とUPDATEの後ろに明示COMMITを持つ。これにより
 * 途中失敗してもmarker行・backfill結果がROLLBACKされず、repair→再適用で新規契約を0化しない。
 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayV80RepairSmokeTest {

    private static final String V80_VERSION = "80";
    private static final String MIGRATION_DIR = "src/main/resources/db/migration";

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_v80_repair")
            .withUsername("ses")
            .withPassword("ses");

    @Test
    void V80部分適用後_repair再適用でmarker固定_新規契約をbackfillで0化しない() throws Exception {
        resetDatabase();
        Path dir = prepareMigrationDir();

        // 1) V79.1まで適用（legacy基盤）
        flywayFilesystem(dir, "79.1").migrate();

        // 2) V80適用時点で存在する既存契約（order_line_id NULL）を投入
        long legacyContractId = insertLegacyContract("SO-V80-LEGACY-1");

        // 3) V80を「marker固定・UPDATE適用後、t_acceptance以前」で途中失敗させる
        installFailingV80(dir);
        Flyway failing = flywayFilesystem(dir, null);
        FlywayException failure = assertThrows(FlywayException.class, failing::migrate);
        assertTrue(allMessages(failure).contains("V80") || allMessages(failure).contains("syntax"),
                "V80の途中失敗であるはず: " + allMessages(failure));

        try (Connection connection = MYSQL.createConnection("");
             Statement statement = connection.createStatement()) {
            // 部分状態: markerテーブルは作成済み・legacy契約が固定済み・UPDATE適用済み
            assertTrue(hasTable(statement, "t_contract_acceptance_backfill"),
                    "markerテーブルが作成されているはず");
            assertTrue(hasRow(statement, "SELECT 1 FROM t_contract_acceptance_backfill WHERE contract_id=" + legacyContractId),
                    "markerにlegacy契約が固定されているはず（明示COMMITにより途中失敗でもROLLBACKされない）");
            assertEquals(0, queryInt(statement,
                    "SELECT acceptance_required FROM t_contract WHERE id=" + legacyContractId),
                    "partial状態でもlegacy契約は検収不要へ移行済みのはず");
            // failed historyが残っている
            assertTrue(hasRow(statement, "SELECT 1 FROM flyway_schema_history WHERE version='" + V80_VERSION + "' AND success=0"),
                    "V80のfailed historyが残るはず");
        }

        // 4) 失敗中（marker固定後）に新規契約を追加。acceptance_requiredはDEFAULT 1のまま
        long newContractId = insertLegacyContract("SO-V80-NEW-AFTER-MARKER");

        // 5) 実scriptへ戻し、repairでfailed historyを除去して再適用（markerは空ではないためINSERTはskip）
        restoreRealV80(dir);
        Flyway latest = flywayFilesystem(dir, null);
        latest.repair();
        latest.migrate();
        latest.validate();

        try (Connection connection = MYSQL.createConnection("");
             Statement statement = connection.createStatement()) {
            // 旧契約: 0（検収不要）+ 固定理由
            assertEquals(0, queryInt(statement,
                    "SELECT acceptance_required FROM t_contract WHERE id=" + legacyContractId),
                    "既存契約は検収不要のまま");
            assertEquals("移行前契約（V80適用時点の既存契約）", queryString(statement,
                    "SELECT acceptance_exemption_reason FROM t_contract WHERE id=" + legacyContractId),
                    "既存契約には固定理由が設定される");
            // 新規契約: 1のまま（backfillで0化されない）
            assertEquals(1, queryInt(statement,
                    "SELECT acceptance_required FROM t_contract WHERE id=" + newContractId),
                    "marker固定後の新規契約はbackfillで0化されない");
            assertTrue(hasRow(statement,
                    "SELECT 1 FROM t_contract WHERE id=" + newContractId
                            + " AND acceptance_exemption_reason IS NULL"),
                    "新規契約の理由はNULLのまま");
            // marker: legacyのみ固定され、新規契約は含まれない
            assertTrue(hasRow(statement, "SELECT 1 FROM t_contract_acceptance_backfill WHERE contract_id=" + legacyContractId),
                    "markerにlegacy契約が残る");
            assertFalse(hasRow(statement, "SELECT 1 FROM t_contract_acceptance_backfill WHERE contract_id=" + newContractId),
                    "markerに新規契約は含まれない");
            // metadata収束: t_acceptance / UNIQUE / FK / NOT NULL DEFAULT 1 / MODIFY収束
            assertTrue(hasTable(statement, "t_acceptance"), "再適用でt_acceptanceが作成される");
            assertTrue(hasIndex(statement, "t_acceptance", "uk_acceptance_contract_month"),
                    "t_acceptanceのUNIQUE(contract_id, work_month)が存在する");
            assertTrue(hasIndex(statement, "t_contract", "uk_contract_order_line"),
                    "t_contract.order_line_idのUNIQUEが存在する");
            assertTrue(hasForeignKey(statement, "t_contract", "fk_contract_order_line"),
                    "t_contractの孤児拒否FKが存在する");
            assertTrue(hasRow(statement, "SELECT 1 FROM information_schema.columns"
                    + " WHERE table_schema=DATABASE() AND table_name='t_contract' AND column_name='acceptance_required'"
                    + " AND is_nullable='NO' AND column_default='1'"),
                    "acceptance_requiredはNOT NULL DEFAULT 1のまま");
            // MODIFY収束（V1と同一のCOMMENT・位置）
            assertTrue(hasRow(statement, "SELECT 1 FROM information_schema.columns"
                    + " WHERE table_schema=DATABASE() AND table_name='t_contract' AND column_name='order_line_id'"
                    + " AND column_comment='注文明細ID（1明細→1契約）'"),
                    "order_line_idのCOMMENTがV1と同一へ収束する");
            assertTrue(hasRow(statement, "SELECT 1 FROM m_menu WHERE menu_key='sales-order'"),
                    "注文管理メニューが登録される");
            assertTrue(hasRow(statement, "SELECT 1 FROM m_document_type WHERE code='ACCEPTANCE'"),
                    "検収書document typeが登録される");
        }
    }

    /** 全migrationをtemp dirへコピーする（V80は実scriptのまま）。 */
    private Path prepareMigrationDir() throws Exception {
        Path source = Paths.get(MIGRATION_DIR);
        Path temp = Files.createTempDirectory("v80-fixture");
        try (Stream<Path> files = Files.list(source)) {
            for (Path file : files.toList()) {
                Files.copy(file, temp.resolve(file.getFileName().toString()),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return temp;
    }

    /** V80を「section 4（t_acceptance）の直前」で打ち切り、末尾に構文エラーを足したscriptへ差し替える。 */
    private void installFailingV80(Path dir) throws Exception {
        Path v80 = dir.resolve("V80__order_acceptance_workflow.sql");
        String original = Files.readString(v80, StandardCharsets.UTF_8);
        int cut = original.indexOf("-- 4. t_acceptance");
        assertTrue(cut > 0, "V80のsection4開始位置が見つかるはず");
        String truncated = original.substring(0, cut);
        Files.writeString(v80, truncated + "\n-- V80途中失敗fixture用の強制構文エラー\nTHIS_IS_NOT_VALID_SQL;\n",
                StandardCharsets.UTF_8);
    }

    /** V80を実scriptへ戻す。 */
    private void restoreRealV80(Path dir) throws Exception {
        Path source = Paths.get(MIGRATION_DIR, "V80__order_acceptance_workflow.sql");
        Files.copy(source, dir.resolve("V80__order_acceptance_workflow.sql"),
                StandardCopyOption.REPLACE_EXISTING);
    }

    private Flyway flywayFilesystem(Path dir, String target) {
        if (target == null) {
            return Flyway.configure()
                    .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                    .locations("filesystem:" + dir)
                    .cleanDisabled(false)
                    .load();
        }
        return Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("filesystem:" + dir)
                .target(target)
                .cleanDisabled(false)
                .load();
    }

    private long insertLegacyContract(String contractNo) throws Exception {
        try (Connection connection = MYSQL.createConnection("");
             Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO t_contract (contract_no, engineer_id, project_id, customer_id, "
                    + "start_date, selling_price, cost_price, status) "
                    + "SELECT '" + contractNo + "', e.id, p.id, p.customer_id, '2026-01-01', 500000, 300000, '準備中' "
                    + "FROM t_engineer e, t_project p LIMIT 1");
            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT id FROM t_contract WHERE contract_no='" + contractNo + "'")) {
                assertTrue(resultSet.next(), contractNo + " が投入されるはず");
                return resultSet.getLong(1);
            }
        }
    }

    private void resetDatabase() throws Exception {
        try (Connection connection = MYSQL.createConnection("");
             Statement statement = connection.createStatement()) {
            if (!hasTable(statement, "flyway_schema_history")) {
                return;
            }
        }
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .clean();
    }

    private boolean hasTable(Statement statement, String table) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(
                "SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='" + table + "'")) {
            return resultSet.next();
        }
    }

    private boolean hasRow(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next();
        }
    }

    private boolean hasIndex(Statement statement, String table, String index) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(
                "SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()"
                        + " AND table_name='" + table + "' AND index_name='" + index + "'")) {
            return resultSet.next();
        }
    }

    private boolean hasForeignKey(Statement statement, String table, String constraint) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(
                "SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE()"
                        + " AND table_name='" + table + "' AND constraint_name='" + constraint + "'"
                        + " AND constraint_type='FOREIGN KEY'")) {
            return resultSet.next();
        }
    }

    private int queryInt(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next(), "fixture query returned no row: " + sql);
            return resultSet.getInt(1);
        }
    }

    private String queryString(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next(), "fixture query returned no row: " + sql);
            return resultSet.getString(1);
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
}
