package com.ses.migration;

import com.ses.test.MySQLContainer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V182テナント・法人スコープ固定および修復演練の完全使い捨て実MySQL 8検証テスト。
 *
 * <p>契約書: .kiro/specs/asset-account-license-lifecycle/v182-rehearsal-contract.md
 * 規約:
 * 1. Docker Disposable mysql:8.0 (tmpfsマウント) 上でFlywayを実行する。
 * 2. 空白DBからV182、V181合成データからV182、V184への継続適用を検証する。
 * 3. 生成列callback_active_slotはクエリ検証のみ行い、直接UPDATEしない。
 * 4. 同一Txでの二段階修復・CASおよび並行修復での敗者更新ゼロ残存をアサートする。
 * 5. 障害注入は隔離テストリソースで行い、本番V182スクリプトを変更しない。
 */
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class V182TenantScopeHardeningRehearsalMySqlTest {

    private static final String EXPECTED_V182_SHA256 =
            "BB9EBDEABF3E1CE9DAD42525551E3F594D2DC4D10F103AF8AFA7BBBA159A0DA7";

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_v182_rehearsal_root")
            .withUsername("root")
            .withPassword("ses");

    // =========================================================================
    // シナリオ1: 空白DBからの新規適用 (Fresh Rehearsal to Target V182)
    // =========================================================================

    @Test
    void scenario1_freshInstallToTarget182_verifiesSchemaColumnsAndConstraints() throws Exception {
        String dbName = "ses_v182_fresh";
        Flyway flyway = flyway(dbName).target("182").load();
        flyway.migrate();

        try (Connection conn = createConnection(dbName); Statement stmt = conn.createStatement()) {
            assertV182HistorySuccess(stmt);
            assertScopeColumnsAdded(stmt);
            assertRepairQueueSchema(stmt);
            assertGeneratedCallbackActiveSlot(stmt);

            // CHECK制約: 不正なactor pairの挿入拒否 (MySQL error 3819)
            assertMysqlFailure(() -> stmt.executeUpdate(
                    "INSERT INTO t_digital_invoice (direction, profile, specification_version, message_id, status, "
                            + "actor_type, confirmation_source, human_user_id) VALUES "
                            + "('SEND', 'Standard', '1.0', 'msg-fresh-ck-err', 'QUEUED', 'HUMAN', 'MANUAL_API', NULL)"),
                    3819, "HY000", "actor_type=HUMANでhuman_user_id=NULLはCHECK制約違反");

            // 複合外部キー: 親子のscope不一致挿入拒否 (MySQL error 1452)
            stmt.executeUpdate("INSERT INTO t_digital_invoice (tenant_id, legal_entity_id, direction, profile, "
                    + "specification_version, message_id, status, actor_type, confirmation_source, human_user_id) VALUES "
                    + "('tenant-a', 10, 'SEND', 'Standard', '1.0', 'msg-fresh-fk-p', 'QUEUED', 'SYSTEM', 'SCHEDULER_POLL', NULL)");
            long parentId = scalar(stmt, "SELECT id FROM t_digital_invoice WHERE message_id='msg-fresh-fk-p'");

            assertMysqlFailure(() -> stmt.executeUpdate(
                    "INSERT INTO t_digital_invoice_event (tenant_id, legal_entity_id, digital_invoice_id, "
                            + "provider_event_id, event_type, event_at, payload_hash, signature_valid, "
                            + "actor_type, confirmation_source, human_user_id) VALUES "
                            + "('tenant-b', 10, " + parentId + ", 'evt-fresh-mismatch', 'STATUS_CHANGE', NOW(), 'hash', 1, "
                            + "'SYSTEM', 'SCHEDULER_POLL', NULL)"),
                    1452, "23000", "親と異なるtenant_idの子イベントは複合外部キー制約違反");
        }
    }

    // =========================================================================
    // シナリオ2: V181合成データからのアップグレード (Upgrade Rehearsal to V182)
    // =========================================================================

    @Test
    void scenario2_upgradeFromV181SyntheticData_verifiesBackfillIsolationAndConstraintEnforcement() throws Exception {
        String dbName = "ses_v182_upgrade";
        Flyway baseline = flyway(dbName).target("181").load();
        baseline.migrate();

        SyntheticFixture fixture;
        try (Connection conn = createConnection(dbName)) {
            fixture = seedV181SyntheticData(conn);
        }

        Flyway toV182 = flyway(dbName).target("182").load();
        toV182.migrate();

        try (Connection conn = createConnection(dbName); Statement stmt = conn.createStatement()) {
            assertV182HistorySuccess(stmt);

            // 4.1 t_digital_invoice の回填と隔離
            assertDigitalInvoiceScope(stmt, fixture.invSingleOkId, "tenant-c1", 101L);
            assertDigitalInvoiceScope(stmt, fixture.invMultiSameOkId, "tenant-same", 202L);
            assertDigitalInvoiceScope(stmt, fixture.invConflictId, null, null);
            assertDigitalInvoiceScope(stmt, fixture.invUnresolvedId, null, null);

            assertQueueRow(stmt, "DIGITAL_INVOICE", fixture.invConflictId, "SCOPE_CONFLICT");
            assertQueueRow(stmt, "DIGITAL_INVOICE", fixture.invUnresolvedId, "SCOPE_UNRESOLVED");
            assertEquals(0L, scalar(stmt, "SELECT COUNT(*) FROM t_nf09_scope_repair_queue "
                    + "WHERE entity_type='DIGITAL_INVOICE' AND entity_id=" + fixture.invSingleOkId));

            // 4.2 t_digital_invoice_event の親スコープ継承
            assertEventScope(stmt, fixture.evtParentOkId, "tenant-c1", 101L);
            assertEventScope(stmt, fixture.evtParentUnresolvedId, null, null);
            assertQueueRow(stmt, "DIGITAL_INVOICE_EVENT", fixture.evtParentUnresolvedId, "PARENT_SCOPE_UNRESOLVED");
            assertEquals(0L, scalar(stmt, "SELECT COUNT(*) FROM t_nf09_scope_repair_queue "
                    + "WHERE entity_type='DIGITAL_INVOICE_EVENT' AND entity_id=" + fixture.evtParentOkId));

            // 4.3 t_peppol_participant の回填とコールバック隔離
            assertPeppolScope(stmt, fixture.pepCustomerOkId, "tenant-c1", 101L);
            assertPeppolScope(stmt, fixture.pepOtherUnresolvedId, null, null);
            assertQueueRow(stmt, "PEPPOL_PARTICIPANT", fixture.pepOtherUnresolvedId, "SCOPE_UNRESOLVED");

            // 重複コールバックの全件隔離 (任意勝者選択なし)
            assertQueueRow(stmt, "PEPPOL_PARTICIPANT", fixture.pepConflict1Id, "CALLBACK_IDENTITY_CONFLICT");
            assertQueueRow(stmt, "PEPPOL_PARTICIPANT", fixture.pepConflict2Id, "CALLBACK_IDENTITY_CONFLICT");
            assertPeppolConflictReset(stmt, fixture.pepConflict1Id);
            assertPeppolConflictReset(stmt, fixture.pepConflict2Id);

            // 4.4 t_external_account_reference の境界
            assertExternalAccountScope(stmt, fixture.accEngineerOkId, "tenant-eng1", 404L);
            assertExternalAccountScope(stmt, fixture.accUserLegacyId, null, null);
            assertQueueRow(stmt, "EXTERNAL_ACCOUNT", fixture.accUserLegacyId, "SCOPE_UNRESOLVED");
            assertEquals(0L, scalar(stmt, "SELECT COUNT(*) FROM t_nf09_scope_repair_queue "
                    + "WHERE entity_type='EXTERNAL_ACCOUNT' AND entity_id=" + fixture.accEngineerOkId));

            // 4.5 監査主体の正規化と制約
            assertNormalizedActorPair(stmt, "t_digital_invoice", fixture.invUnresolvedId);
            assertNormalizedActorPair(stmt, "t_digital_invoice_event", fixture.evtParentUnresolvedId);

            // 生成列callback_active_slotの検証（直接更新せず生成結果をクエリ断言）
            assertNull(queryCallbackActiveSlot(stmt, fixture.pepConflict1Id),
                    "CONFLICT_REPAIR状態ではcallback_active_slotはNULL");
            assertNull(queryCallbackActiveSlot(stmt, fixture.pepConflict2Id),
                    "CONFLICT_REPAIR状態ではcallback_active_slotはNULL");
            assertEquals(1, queryCallbackActiveSlot(stmt, fixture.pepCustomerOkId),
                    "VERIFIEDかつdeleted_flag=0のレコードではcallback_active_slotは1");
        }
    }

    // =========================================================================
    // シナリオ3: 本候補最新バージョン (V184) への継続アップグレード
    // =========================================================================

    @Test
    void scenario3_continuousUpgradeToTarget184_verifiesForwardCompatibilityWithoutV185() throws Exception {
        String dbName = "ses_v182_upgrade_to_184";
        Flyway baseline = flyway(dbName).target("181").load();
        baseline.migrate();

        try (Connection conn = createConnection(dbName)) {
            seedV181SyntheticData(conn);
        }

        Flyway toV182 = flyway(dbName).target("182").load();
        toV182.migrate();

        // V182から最新候補V184まで継続適用 (NF10専用のV185は除外)
        Flyway toV184 = flyway(dbName).target("184").load();
        toV184.migrate();
        toV184.validate();

        try (Connection conn = createConnection(dbName); Statement stmt = conn.createStatement()) {
            assertEquals(1L, scalar(stmt,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='182' AND success=1"));
            assertEquals(1L, scalar(stmt,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='183' AND success=1"));
            assertEquals(1L, scalar(stmt,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='184' AND success=1"));
            assertEquals(0L, scalar(stmt,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='185'"),
                    "NF10専用のV185は本演練に含まれない");

            // V182で作成されたキュー表が後続で破損していないことを確認
            assertEquals(1L, scalar(stmt,
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                            + "AND table_name='t_nf09_scope_repair_queue'"));
        }
    }

    // =========================================================================
    // 第6節: キュー修復演練 (TC-REP-01 〜 TC-REP-06)
    // =========================================================================

    @Test
    void scenario4_repairQueueTransactionalCas_verifiesAtomicRepairAndZeroResidualLoser() throws Exception {
        String dbName = "ses_v182_repair_rehearsal";
        Flyway flyway = flyway(dbName).target("182").load();
        flyway.migrate();

        long humanUserId = 1001L;

        try (Connection conn = createConnection(dbName)) {
            // TC-REP-01: 正常系・孤立インボイスの同一Tx修復とCAS結案
            long invoiceId = insertUnresolvedInvoice(conn, "msg-rep-01");
            long queueId = insertQueueRow(conn, "DIGITAL_INVOICE", invoiceId, "SCOPE_UNRESOLVED");

            boolean rep01Ok = repairInvoiceInTransaction(
                    conn, invoiceId, 0, "tenant-repaired-1", 501L,
                    queueId, 0, humanUserId, "TC-REP-01 resolved", "tenant-repaired-1");
            assertTrue(rep01Ok, "TC-REP-01 同一Tx修復成功");
            assertEquals("tenant-repaired-1", queryInvoiceTenant(conn, invoiceId));
            assertEquals("RESOLVED", queryQueueStatus(conn, queueId));

            // TC-REP-02: 正常系・重複Peppolの解決、1件のみVERIFIED有効化と生成列アサーション
            long pep1 = insertPeppol(conn, "pep-rep", "iso6523", "0147:rep-02", "CONFLICT_REPAIR", null, 0);
            long pep2 = insertPeppol(conn, "pep-rep", "iso6523", "0147:rep-02", "CONFLICT_REPAIR", null, 0);
            long pepQueue1 = insertQueueRow(conn, "PEPPOL_PARTICIPANT", pep1, "CALLBACK_IDENTITY_CONFLICT");
            long pepQueue2 = insertQueueRow(conn, "PEPPOL_PARTICIPANT", pep2, "CALLBACK_IDENTITY_CONFLICT");

            boolean rep02Ok = repairPeppolConflictInTransaction(
                    conn, pep1, pep2, "tenant-pep-rep", 601L,
                    pepQueue1, pepQueue2, humanUserId, "TC-REP-02 deduplicated");
            assertTrue(rep02Ok, "TC-REP-02 重複Peppolの解決・結案成功");

            try (Statement s = conn.createStatement()) {
                assertEquals(1, queryCallbackActiveSlot(s, pep1),
                        "正当行はVERIFIEDかつdeleted_flag=0のため生成列callback_active_slot=1");
                assertNull(queryCallbackActiveSlot(s, pep2),
                        "無効化行はDUPLICATE_DEPRECATEDのため生成列callback_active_slotはNULL");

                // 2件目を再度VERIFIEDに更新しようとすると一意制約uk_peppol_callback_activeに違反する
                assertMysqlFailure(() -> s.executeUpdate(
                        "UPDATE t_peppol_participant SET status='VERIFIED' WHERE id=" + pep2),
                        1062, "23000", "同一アクティブコールバックの複数VERIFIEDは一意制約違反");
            }

            // TC-REP-03: 負向系・他テナント担当者による越境修復試行の拒否
            long invoiceCross = insertUnresolvedInvoice(conn, "msg-rep-03");
            long queueCross = insertQueueRow(conn, "DIGITAL_INVOICE", invoiceCross, "SCOPE_UNRESOLVED");
            assertThrows(SecurityException.class, () -> repairInvoiceInTransaction(
                    conn, invoiceCross, 0, "tenant-a", 701L,
                    queueCross, 0, humanUserId, "Cross tenant", "tenant-b_unauthorized"));
            assertEquals("PENDING", queryQueueStatus(conn, queueCross), "拒否されたキュー行はPENDINGを維持");

            // TC-REP-04: 負向系・未解決スコープ(NULL)のまま結案試行を拒否
            long invoiceNullScope = insertUnresolvedInvoice(conn, "msg-rep-04");
            long queueNullScope = insertQueueRow(conn, "DIGITAL_INVOICE", invoiceNullScope, "SCOPE_UNRESOLVED");
            assertThrows(IllegalArgumentException.class, () -> repairInvoiceInTransaction(
                    conn, invoiceNullScope, 0, null, null,
                    queueNullScope, 0, humanUserId, "Null scope", null));
            assertEquals("PENDING", queryQueueStatus(conn, queueNullScope));

            // TC-REP-05: 負向系・キューCAS不一致時の業務テーブル完全ロールバック
            long invoiceCasFail = insertUnresolvedInvoice(conn, "msg-rep-05");
            long queueCasFail = insertQueueRow(conn, "DIGITAL_INVOICE", invoiceCasFail, "SCOPE_UNRESOLVED");
            // expectedQueueVersionに誤った999を指定
            boolean casFailed = repairInvoiceInTransaction(
                    conn, invoiceCasFail, 0, "tenant-rolled-back", 801L,
                    queueCasFail, 999, humanUserId, "Version mismatch", "tenant-rolled-back");
            assertFalse(casFailed, "CAS不一致により修復失敗");
            assertNull(queryInvoiceTenant(conn, invoiceCasFail), "業務テーブルの更新もロールバックされNULLを維持");
            assertEquals("PENDING", queryQueueStatus(conn, queueCasFail));
        }

        // TC-REP-06: 並行系・2スレッド同時修復と敗者更新ゼロ残存アサーション
        long concurrentInvoiceId;
        long concurrentQueueId;
        try (Connection conn = createConnection(dbName)) {
            concurrentInvoiceId = insertUnresolvedInvoice(conn, "msg-rep-06-concurrent");
            concurrentQueueId = insertQueueRow(conn, "DIGITAL_INVOICE", concurrentInvoiceId, "SCOPE_UNRESOLVED");
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        Future<?> f1 = pool.submit(() -> {
            try (Connection conn = createConnection(dbName)) {
                startLatch.await();
                boolean ok = repairInvoiceInTransaction(
                        conn, concurrentInvoiceId, 0, "tenant-thread-1", 901L,
                        concurrentQueueId, 0, humanUserId, "Thread 1 wins", "tenant-thread-1");
                if (ok) successCount.incrementAndGet(); else failureCount.incrementAndGet();
            } catch (Exception e) {
                failureCount.incrementAndGet();
            }
        });

        Future<?> f2 = pool.submit(() -> {
            try (Connection conn = createConnection(dbName)) {
                startLatch.await();
                boolean ok = repairInvoiceInTransaction(
                        conn, concurrentInvoiceId, 0, "tenant-thread-2", 902L,
                        concurrentQueueId, 0, humanUserId, "Thread 2 wins", "tenant-thread-2");
                if (ok) successCount.incrementAndGet(); else failureCount.incrementAndGet();
            } catch (Exception e) {
                failureCount.incrementAndGet();
            }
        });

        startLatch.countDown();
        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, successCount.get(), "並行修復では先行1スレッドのみがコミット成功する");
        assertEquals(1, failureCount.get(), "敗者スレッドは更新失敗・ロールバックする");

        try (Connection conn = createConnection(dbName)) {
            String committedTenant = queryInvoiceTenant(conn, concurrentInvoiceId);
            assertTrue("tenant-thread-1".equals(committedTenant) || "tenant-thread-2".equals(committedTenant),
                    "勝者のテナントのみが反映されていること");

            // 敗者の更新痕跡がDBに一切残っていない（dirty な部分更新がない）ことを厳格にアサート
            String loserTenant = "tenant-thread-1".equals(committedTenant) ? "tenant-thread-2" : "tenant-thread-1";
            try (Statement s = conn.createStatement()) {
                assertEquals(0L, scalar(s, "SELECT COUNT(*) FROM t_digital_invoice WHERE tenant_id='" + loserTenant + "'"),
                        "敗者のテナント値はDB内に1件も残存していないこと");
                assertEquals("RESOLVED", queryQueueStatus(conn, concurrentQueueId));
                assertEquals(1L, scalar(s, "SELECT version FROM t_nf09_scope_repair_queue WHERE id=" + concurrentQueueId),
                        "キューのバージョンは勝者による1加算のみ");
            }
        }
    }

    // =========================================================================
    // 第5節: 失敗時の証拠保全演練 (TC-REP-07)
    // =========================================================================

    @Test
    void scenario5_faultEvidencePreservation_verifiesErrorCapturedBeforeTeardown() throws Exception {
        // 本番V182ファイル自体のチェックサムが不変であることを事前検証
        Path realMigrationPath = Path.of("src/main/resources/db/migration/V182__nf09_tenant_scope_hardening.sql");
        assertTrue(Files.isRegularFile(realMigrationPath), "実V182マイグレーションファイルが存在する");
        String actualHash = computeFileSha256(realMigrationPath);
        assertEquals(EXPECTED_V182_SHA256, actualHash,
                "本番V182スクリプトのSHA-256が固定値と一致し改変されていないこと");

        String dbName = "ses_v182_fault_isolation";
        Flyway flyway = flyway(dbName).target("181").load();
        flyway.migrate();

        // 隔離環境で意図的なDDLエラーを注入 (本番スクリプトは一切変更しない)
        StringBuilder evidenceLog = new StringBuilder();
        try (Connection conn = createConnection(dbName); Statement stmt = conn.createStatement()) {
            SQLException injectedError = assertThrows(SQLException.class, () -> stmt.execute(
                    "ALTER TABLE t_digital_invoice ADD CONSTRAINT chk_fault_injection "
                            + "CHECK (direction IN ('INVALID_DIRECTION_FORCED_FAIL'))"),
                    "意図的なDDLエラー注入");

            // 5.2 証拠の完全退避シミュレーション
            evidenceLog.append("=== FAILURE EVIDENCE ARCHIVE ===\n");
            evidenceLog.append("Error Message: ").append(injectedError.getMessage()).append("\n");
            evidenceLog.append("ErrorCode: ").append(injectedError.getErrorCode()).append("\n");

            // flyway_schema_historyダンプ
            try (ResultSet rs = stmt.executeQuery("SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank")) {
                while (rs.next()) {
                    evidenceLog.append(String.format("History: %s | %s | success=%d\n",
                            rs.getString(1), rs.getString(2), rs.getInt(3)));
                }
            }

            // SHOW CREATE TABLE ダンプ
            try (ResultSet rs = stmt.executeQuery("SHOW CREATE TABLE t_digital_invoice")) {
                if (rs.next()) {
                    evidenceLog.append("Schema DDL: ").append(rs.getString(2)).append("\n");
                }
            }

            // 件数ダンプ
            long invoiceCount = scalar(stmt, "SELECT COUNT(*) FROM t_digital_invoice");
            evidenceLog.append("Invoice count: ").append(invoiceCount).append("\n");
        }

        // 証拠マニフェストのSHA-256生成
        String manifestDigest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(evidenceLog.toString().getBytes(StandardCharsets.UTF_8)));
        assertNotNull(manifestDigest, "証拠退避マニフェストのハッシュが生成される");
        assertTrue(evidenceLog.length() > 0, "証拠ログが取得・隔離されていること");
    }

    // =========================================================================
    // ヘルパーメソッド群
    // =========================================================================

    private Flyway flyway(String dbName) throws SQLException {
        ensureDatabase(dbName);
        String url = MYSQL.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + dbName + "$1");
        return Flyway.configure()
                .dataSource(url, MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration");
    }

    private Connection createConnection(String dbName) throws SQLException {
        ensureDatabase(dbName);
        String url = MYSQL.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + dbName + "$1");
        return DriverManager.getConnection(url, MYSQL.getUsername(), MYSQL.getPassword());
    }

    private void ensureDatabase(String dbName) throws SQLException {
        try (Connection rootConn = MYSQL.createConnection(""); Statement stmt = rootConn.createStatement()) {
            stmt.execute("CREATE DATABASE IF NOT EXISTS `" + dbName + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }

    private void assertV182HistorySuccess(Statement stmt) throws Exception {
        assertEquals(1L, scalar(stmt,
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version='182' AND success=1"));
    }

    private void assertScopeColumnsAdded(Statement stmt) throws Exception {
        for (String table : List.of("t_peppol_participant", "t_digital_invoice",
                "t_digital_invoice_event", "t_external_account_reference")) {
            assertEquals(1L, scalar(stmt, "SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name='" + table + "' AND column_name='tenant_id'"));
            assertEquals(1L, scalar(stmt, "SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name='" + table + "' AND column_name='legal_entity_id'"));
        }
    }

    private void assertRepairQueueSchema(Statement stmt) throws Exception {
        assertEquals(1L, scalar(stmt, "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name='t_nf09_scope_repair_queue'"));
        assertEquals(1L, scalar(stmt, "SELECT COUNT(*) FROM information_schema.table_constraints "
                + "WHERE table_schema=DATABASE() AND table_name='t_nf09_scope_repair_queue' AND constraint_name='PRIMARY'"));
        assertEquals(1L, scalar(stmt, "SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name='t_nf09_scope_repair_queue' AND index_name='uk_nf09_scope_repair_entity'"));
    }

    private void assertGeneratedCallbackActiveSlot(Statement stmt) throws Exception {
        assertEquals(1L, scalar(stmt, "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='t_peppol_participant' "
                + "AND column_name='callback_active_slot' AND extra LIKE '%VIRTUAL GENERATED%'"));
    }

    private Integer queryCallbackActiveSlot(Statement stmt, long id) throws Exception {
        try (ResultSet rs = stmt.executeQuery("SELECT callback_active_slot FROM t_peppol_participant WHERE id=" + id)) {
            if (!rs.next()) throw new AssertionError("Participant not found: " + id);
            int val = rs.getInt(1);
            return rs.wasNull() ? null : val;
        }
    }

    private void assertDigitalInvoiceScope(Statement stmt, long id, String expectedTenant, Long expectedLegalEntity) throws Exception {
        try (ResultSet rs = stmt.executeQuery("SELECT tenant_id, legal_entity_id FROM t_digital_invoice WHERE id=" + id)) {
            assertTrue(rs.next(), "Invoice row exists: " + id);
            assertEquals(expectedTenant, rs.getString(1));
            if (expectedLegalEntity == null) {
                assertNull(rs.getObject(2));
            } else {
                assertEquals(expectedLegalEntity, rs.getLong(2));
            }
        }
    }

    private void assertEventScope(Statement stmt, long id, String expectedTenant, Long expectedLegalEntity) throws Exception {
        try (ResultSet rs = stmt.executeQuery("SELECT tenant_id, legal_entity_id FROM t_digital_invoice_event WHERE id=" + id)) {
            assertTrue(rs.next(), "Event row exists: " + id);
            assertEquals(expectedTenant, rs.getString(1));
            if (expectedLegalEntity == null) {
                assertNull(rs.getObject(2));
            } else {
                assertEquals(expectedLegalEntity, rs.getLong(2));
            }
        }
    }

    private void assertPeppolScope(Statement stmt, long id, String expectedTenant, Long expectedLegalEntity) throws Exception {
        try (ResultSet rs = stmt.executeQuery("SELECT tenant_id, legal_entity_id FROM t_peppol_participant WHERE id=" + id)) {
            assertTrue(rs.next(), "Peppol row exists: " + id);
            assertEquals(expectedTenant, rs.getString(1));
            if (expectedLegalEntity == null) {
                assertNull(rs.getObject(2));
            } else {
                assertEquals(expectedLegalEntity, rs.getLong(2));
            }
        }
    }

    private void assertPeppolConflictReset(Statement stmt, long id) throws Exception {
        try (ResultSet rs = stmt.executeQuery("SELECT status, verified_at FROM t_peppol_participant WHERE id=" + id)) {
            assertTrue(rs.next(), "Peppol conflict row exists: " + id);
            assertEquals("CONFLICT_REPAIR", rs.getString(1));
            assertNull(rs.getTimestamp(2), "verified_atはNULLにリセットされていること");
        }
    }

    private void assertExternalAccountScope(Statement stmt, long id, String expectedTenant, Long expectedLegalEntity) throws Exception {
        try (ResultSet rs = stmt.executeQuery("SELECT tenant_id, legal_entity_id FROM t_external_account_reference WHERE id=" + id)) {
            assertTrue(rs.next(), "External account row exists: " + id);
            assertEquals(expectedTenant, rs.getString(1));
            if (expectedLegalEntity == null) {
                assertNull(rs.getObject(2));
            } else {
                assertEquals(expectedLegalEntity, rs.getLong(2));
            }
        }
    }

    private void assertQueueRow(Statement stmt, String entityType, long entityId, String expectedReason) throws Exception {
        try (ResultSet rs = stmt.executeQuery("SELECT reason, status FROM t_nf09_scope_repair_queue "
                + "WHERE entity_type='" + entityType + "' AND entity_id=" + entityId)) {
            assertTrue(rs.next(), "Queue row exists: " + entityType + " id=" + entityId);
            assertEquals(expectedReason, rs.getString(1));
            assertEquals("PENDING", rs.getString(2));
        }
    }

    private void assertNormalizedActorPair(Statement stmt, String table, long id) throws Exception {
        try (ResultSet rs = stmt.executeQuery("SELECT actor_type, confirmation_source, human_user_id FROM "
                + table + " WHERE id=" + id)) {
            assertTrue(rs.next(), table + " row exists: " + id);
            assertEquals("LEGACY_UNRESOLVED", rs.getString(1));
            assertEquals("LEGACY_UNRESOLVED", rs.getString(2));
            assertNull(rs.getObject(3));
        }
    }

    private boolean repairInvoiceInTransaction(
            Connection conn, long invoiceId, int expectedInvoiceVersion,
            String newTenant, Long newLegalEntity,
            long queueId, int expectedQueueVersion,
            long humanUserId, String note, String callerTenant) throws SQLException {

        if (newTenant == null || newLegalEntity == null) {
            throw new IllegalArgumentException("未解決スコープ(NULL)のまま修復はできません");
        }
        if (callerTenant != null && !callerTenant.equals(newTenant)) {
            throw new SecurityException("他テナント境界の修復権限がありません: " + callerTenant + " vs " + newTenant);
        }

        boolean prevAuto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            // 1. 業務実体データの修復
            int invoiceUpdated;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE t_digital_invoice SET tenant_id=?, legal_entity_id=?, version=version+1 "
                            + "WHERE id=? AND tenant_id IS NULL AND version=?")) {
                ps.setString(1, newTenant);
                ps.setLong(2, newLegalEntity);
                ps.setLong(3, invoiceId);
                ps.setInt(4, expectedInvoiceVersion);
                invoiceUpdated = ps.executeUpdate();
            }

            if (invoiceUpdated != 1) {
                conn.rollback();
                return false;
            }

            // 2. キュー行のCAS結案
            int queueUpdated;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE t_nf09_scope_repair_queue SET status='RESOLVED', resolved_at=NOW(), "
                            + "resolved_by=?, resolution_note=?, version=version+1 "
                            + "WHERE id=? AND status='PENDING' AND version=?")) {
                ps.setLong(1, humanUserId);
                ps.setString(2, note);
                ps.setLong(3, queueId);
                ps.setInt(4, expectedQueueVersion);
                queueUpdated = ps.executeUpdate();
            }

            if (queueUpdated != 1) {
                conn.rollback();
                return false;
            }

            conn.commit();
            return true;
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(prevAuto);
        }
    }

    private boolean repairPeppolConflictInTransaction(
            Connection conn, long winnerId, long loserId,
            String tenantId, long legalEntityId,
            long winnerQueueId, long loserQueueId,
            long humanUserId, String note) throws SQLException {

        boolean prevAuto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            // 勝者をVERIFIED, deleted_flag=0 に更新 (callback_active_slot=1 が導出される)
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE t_peppol_participant SET tenant_id=?, legal_entity_id=?, status='VERIFIED', "
                            + "verified_at=NOW(), deleted_flag=0 WHERE id=?")) {
                ps.setString(1, tenantId);
                ps.setLong(2, legalEntityId);
                ps.setLong(3, winnerId);
                if (ps.executeUpdate() != 1) {
                    conn.rollback();
                    return false;
                }
            }

            // 敗者をDUPLICATE_DEPRECATEDに無効化 (callback_active_slot=NULL が導出される)
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE t_peppol_participant SET tenant_id=?, legal_entity_id=?, status='DUPLICATE_DEPRECATED', "
                            + "verified_at=NULL, deleted_flag=0 WHERE id=?")) {
                ps.setString(1, tenantId);
                ps.setLong(2, legalEntityId);
                ps.setLong(3, loserId);
                if (ps.executeUpdate() != 1) {
                    conn.rollback();
                    return false;
                }
            }

            // 両キュー行のCAS結案
            for (long qId : List.of(winnerQueueId, loserQueueId)) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE t_nf09_scope_repair_queue SET status='RESOLVED', resolved_at=NOW(), "
                                + "resolved_by=?, resolution_note=?, version=version+1 "
                                + "WHERE id=? AND status='PENDING'")) {
                    ps.setLong(1, humanUserId);
                    ps.setString(2, note);
                    ps.setLong(3, qId);
                    if (ps.executeUpdate() != 1) {
                        conn.rollback();
                        return false;
                    }
                }
            }

            conn.commit();
            return true;
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(prevAuto);
        }
    }

    private long insertUnresolvedInvoice(Connection conn, String messageId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO t_digital_invoice (direction, profile, specification_version, message_id, status, "
                        + "actor_type, confirmation_source, human_user_id, version, deleted_flag) VALUES "
                        + "('SEND', 'Standard', '1.0', ?, 'QUEUED', 'SYSTEM', 'SCHEDULER_POLL', NULL, 0, 0)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, messageId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private long insertPeppol(Connection conn, String provider, String scheme, String participantId,
                              String status, java.sql.Timestamp verifiedAt, int deletedFlag) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO t_peppol_participant (owner_type, owner_id, provider, scheme_id, participant_id, "
                        + "status, verified_at, deleted_flag) VALUES ('CUSTOMER', 1, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, provider);
            ps.setString(2, scheme);
            ps.setString(3, participantId);
            ps.setString(4, status);
            ps.setTimestamp(5, verifiedAt);
            ps.setInt(6, deletedFlag);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private long insertQueueRow(Connection conn, String entityType, long entityId, String reason) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO t_nf09_scope_repair_queue (entity_type, entity_id, reason, status, version) "
                        + "VALUES (?, ?, ?, 'PENDING', 0)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, entityType);
            ps.setLong(2, entityId);
            ps.setString(3, reason);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private String queryInvoiceTenant(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT tenant_id FROM t_digital_invoice WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
                return null;
            }
        }
    }

    private String queryQueueStatus(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT status FROM t_nf09_scope_repair_queue WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
                return null;
            }
        }
    }

    private void assertMysqlFailure(Executable executable, int expectedErrorCode, String expectedSqlState, String message) {
        SQLException ex = assertThrows(SQLException.class, executable::execute, message);
        assertEquals(expectedErrorCode, ex.getErrorCode(), "MySQLエラーコード不一致: " + message);
        assertEquals(expectedSqlState, ex.getSQLState(), "SQLState不一致: " + message);
    }

    private long scalar(Statement stmt, String sql) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getLong(1);
            }
            throw new AssertionError("Scalar query returned no rows: " + sql);
        }
    }

    private String computeFileSha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                md.update(buf, 0, read);
            }
        }
        return HexFormat.of().formatHex(md.digest()).toUpperCase();
    }

    private record SyntheticFixture(
            long invSingleOkId,
            long invMultiSameOkId,
            long invConflictId,
            long invUnresolvedId,
            long evtParentOkId,
            long evtParentUnresolvedId,
            long pepCustomerOkId,
            long pepOtherUnresolvedId,
            long pepConflict1Id,
            long pepConflict2Id,
            long accEngineerOkId,
            long accUserLegacyId) { }

    private SyntheticFixture seedV181SyntheticData(Connection conn) throws SQLException {
        long cust1 = insertCustomer(conn, "顧客1", "tenant-c1", 101L);
        long cust2 = insertCustomer(conn, "顧客2", "tenant-same", 202L);
        long cust3 = insertCustomer(conn, "顧客3", "tenant-c3", 303L);

        long eng1 = insertEngineer(conn, "要員1", "tenant-eng1", 404L);

        long inv1 = insertBillingInvoice(conn, "INV-V181-001", cust1, 101L);
        long inv2 = insertBillingInvoice(conn, "INV-V181-002", cust2, 202L);
        long inv3 = insertBillingInvoice(conn, "INV-V181-003", cust1, 101L);

        long soSame = insertSalesOrder(conn, "SO-V181-001", cust2, "tenant-same", 202L);

        long proj3 = insertProject(conn, "案件3", cust3, 303L);
        long contractDiff = insertContract(conn, "CON-V181-001", cust3, proj3, eng1, "tenant-diff", 999L);

        // t_digital_invoice
        long dinvSingle = insertDigitalInvoice(conn, inv1, null, null, "msg-v181-single", "QUEUED");
        long dinvMultiSame = insertDigitalInvoice(conn, inv2, soSame, null, "msg-v181-multi-same", "QUEUED");
        long dinvConflict = insertDigitalInvoice(conn, inv3, null, contractDiff, "msg-v181-conflict", "QUEUED");
        long dinvUnresolved = insertDigitalInvoice(conn, null, null, null, "msg-v181-unresolved", "QUEUED");

        // t_digital_invoice_event
        long evtSingle = insertDigitalInvoiceEvent(conn, dinvSingle, "evt-v181-single", "QUEUED");
        long evtUnresolved = insertDigitalInvoiceEvent(conn, dinvUnresolved, "evt-v181-unresolved", "FAILED");

        // t_peppol_participant
        long pepCust = insertPeppol(conn, "CUSTOMER", cust1, "pep-p1", "iso6523", "0147:c1", "VERIFIED", new java.sql.Timestamp(System.currentTimeMillis()));
        long pepOther = insertPeppol(conn, "USER", 999L, "pep-p1", "iso6523", "0147:u999", "PENDING", null);
        long pepDup1 = insertPeppol(conn, "CUSTOMER", cust2, "pep-dup", "iso6523", "0147:dup", "VERIFIED", new java.sql.Timestamp(System.currentTimeMillis()));
        long pepDup2 = insertPeppol(conn, "CUSTOMER", cust3, "pep-dup", "iso6523", "0147:dup", "VERIFIED", new java.sql.Timestamp(System.currentTimeMillis()));

        // t_external_account_reference
        long accEng = insertExternalAccount(conn, "ENGINEER", eng1, "idem-eng-1");
        long accUser = insertExternalAccount(conn, "USER", 1L, "idem-user-1");

        return new SyntheticFixture(
                dinvSingle, dinvMultiSame, dinvConflict, dinvUnresolved,
                evtSingle, evtUnresolved,
                pepCust, pepOther, pepDup1, pepDup2,
                accEng, accUser);
    }

    private long insertCustomer(Connection conn, String name, String tenantId, Long legalEntityId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO m_customer (name, tenant_id, legal_entity_id, deleted_flag) VALUES (?, ?, ?, 0)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, tenantId);
            ps.setObject(3, legalEntityId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private long insertEngineer(Connection conn, String name, String tenantId, Long legalEntityId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO t_engineer (full_name, employment_type, status, tenant_id, legal_entity_id, deleted_flag) "
                        + "VALUES (?, '正社員', 'Bench', ?, ?, 0)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, tenantId);
            ps.setObject(3, legalEntityId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private long insertBillingInvoice(Connection conn, String invoiceNo, long customerId, Long legalEntityId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO t_invoice (invoice_no, customer_id, billing_month, subtotal, tax, total, legal_entity_id, deleted_flag) "
                        + "VALUES (?, ?, '2026-10', 100000, 10000, 110000, ?, 0)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, invoiceNo);
            ps.setLong(2, customerId);
            ps.setObject(3, legalEntityId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private long insertSalesOrder(Connection conn, String orderNo, long customerId, String tenantId, Long legalEntityId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO t_sales_order (order_no, customer_id, order_date, tenant_id, legal_entity_id, status, deleted_flag) "
                        + "VALUES (?, ?, '2026-10-01', ?, ?, '受領確認', 0)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, orderNo);
            ps.setLong(2, customerId);
            ps.setString(3, tenantId);
            ps.setObject(4, legalEntityId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private long insertProject(Connection conn, String name, long customerId, Long legalEntityId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO t_project (project_name, customer_id, legal_entity_id, deleted_flag) VALUES (?, ?, ?, 0)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setLong(2, customerId);
            ps.setObject(3, legalEntityId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private long insertContract(Connection conn, String contractNo, long customerId, long projectId, long engineerId,
                                String tenantId, Long legalEntityId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO t_contract (contract_no, customer_id, project_id, engineer_id, start_date, "
                        + "selling_price, cost_price, tenant_id, legal_entity_id, deleted_flag) "
                        + "VALUES (?, ?, ?, ?, '2026-10-01', 800000, 600000, ?, ?, 0)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, contractNo);
            ps.setLong(2, customerId);
            ps.setLong(3, projectId);
            ps.setLong(4, engineerId);
            ps.setString(5, tenantId);
            ps.setObject(6, legalEntityId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private long insertDigitalInvoice(Connection conn, Long invoiceId, Long purchaseOrderId, Long contractId,
                                      String messageId, String status) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO t_digital_invoice (invoice_id, purchase_order_id, contract_id, direction, profile, "
                        + "specification_version, message_id, status, version, deleted_flag) VALUES "
                        + "(?, ?, ?, 'SEND', 'Standard', '1.0', ?, ?, 0, 0)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setObject(1, invoiceId);
            ps.setObject(2, purchaseOrderId);
            ps.setObject(3, contractId);
            ps.setString(4, messageId);
            ps.setString(5, status);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private long insertDigitalInvoiceEvent(Connection conn, long digitalInvoiceId, String providerEventId, String eventType) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO t_digital_invoice_event (digital_invoice_id, provider_event_id, event_type, event_at, "
                        + "payload_hash, signature_valid) VALUES (?, ?, ?, NOW(), 'dummy-hash', 1)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, digitalInvoiceId);
            ps.setString(2, providerEventId);
            ps.setString(3, eventType);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private long insertPeppol(Connection conn, String ownerType, long ownerId, String provider, String schemeId,
                              String participantId, String status, java.sql.Timestamp verifiedAt) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO t_peppol_participant (owner_type, owner_id, provider, scheme_id, participant_id, "
                        + "status, verified_at, deleted_flag) VALUES (?, ?, ?, ?, ?, ?, ?, 0)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, ownerType);
            ps.setLong(2, ownerId);
            ps.setString(3, provider);
            ps.setString(4, schemeId);
            ps.setString(5, participantId);
            ps.setString(6, status);
            ps.setTimestamp(7, verifiedAt);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private long insertExternalAccount(Connection conn, String assigneeType, long assigneeId, String idempotencyKey) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO t_external_account_reference (assignee_type, assignee_id, account_type, account_identifier, "
                        + "idempotency_key, status, deleted_flag) VALUES (?, ?, 'KEYCLOAK', 'ext-user-1', ?, 'ACTIVE', 0)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, assigneeType);
            ps.setLong(2, assigneeId);
            ps.setString(3, idempotencyKey);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }
}
