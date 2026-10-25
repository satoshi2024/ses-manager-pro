package com.ses.migration;

import com.ses.test.MySQLContainer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

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
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * V182テナント・法人スコープ固定および修復演練の完全使い捨て実MySQL 8検証テスト。
 *
 * <p>契約書: .kiro/specs/asset-account-license-lifecycle/v182-rehearsal-contract.md
 * 規約:
 * 1. Docker Disposable mysql:8.0 (tmpfsマウント) 上でFlywayを実行する (Docker必須・零skip)。
 * 2. 空白DBからV182、V181合成データからV182、V184への継続適用を検証する。
 * 3. 生成列callback_active_slotはクエリ検証のみ行い、直接UPDATEしない。
 * 4. 同一Txでの二段階修復・CASおよび可観測な並行修復での敗者更新ゼロ残存をアサートする。
 * 5. 障害注入は隔離テストリソースで行い、本番V182スクリプトを変更しない。
 * 6. クリーンリプレイでの証拠保全を検証し、部分マイグレーション回復やforward-fixとは混称しない。
 */
@Tag("mysql")
@Testcontainers
class V182TenantScopeHardeningRehearsalMySqlTest {

    private static final String EXPECTED_V182_SHA256 =
            "BB9EBDEABF3E1CE9DAD42525551E3F594D2DC4D10F103AF8AFA7BBBA159A0DA7";

    private static final Set<String> REQUIRED_EVIDENCE_FILES = Set.of(
            "flyway_schema_history.csv",
            "tables_ddl.sql",
            "table_counts.json",
            "scope_repair_queue.csv",
            "execution_diagnostics.txt",
            "flyway_execution.log",
            "manifest-sha256.txt"
    );

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
        Flyway flyway = flywayConfig(dbName).target("182").load();
        flyway.migrate();

        try (Connection conn = createConnection(dbName); Statement stmt = conn.createStatement()) {
            assertV182HistorySuccess(stmt);
            assertScopeColumnsAdded(stmt);
            assertRepairQueueSchema(stmt);
            assertGeneratedCallbackActiveSlot(stmt);

            // 第3節: 複合インデックスを有序列集合・一意性・インデックス名で網羅検証
            assertCompositeIndexes(conn);

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
        Flyway baseline = flywayConfig(dbName).target("181").load();
        baseline.migrate();

        SyntheticFixture fixture;
        try (Connection conn = createConnection(dbName)) {
            fixture = seedV181SyntheticData(conn);
        }

        Flyway toV182 = flywayConfig(dbName).target("182").load();
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
        Flyway baseline = flywayConfig(dbName).target("181").load();
        baseline.migrate();

        try (Connection conn = createConnection(dbName)) {
            seedV181SyntheticData(conn);
        }

        Flyway toV182 = flywayConfig(dbName).target("182").load();
        toV182.migrate();

        // V182から最新候補V184まで継続適用 (NF10専用のV185は除外)
        Flyway toV184 = flywayConfig(dbName).target("184").load();
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
        Flyway flyway = flywayConfig(dbName).target("182").load();
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
            // 合法かつ区別可能な顧客所有者を作成
            long custA = insertCustomer(conn, "顧客Peppol-A", "tenant-pep-rep", 601L);
            long custB = insertCustomer(conn, "顧客Peppol-B", "tenant-pep-rep", 601L);

            long pep1 = insertPeppol(conn, "CUSTOMER", custA, "pep-rep", "iso6523", "0147:rep-02", "CONFLICT_REPAIR", null, 0);
            long pep2 = insertPeppol(conn, "CUSTOMER", custB, "pep-rep", "iso6523", "0147:rep-02", "CONFLICT_REPAIR", null, 0);
            long pepQueue1 = insertQueueRow(conn, "PEPPOL_PARTICIPANT", pep1, "CALLBACK_IDENTITY_CONFLICT");
            long pepQueue2 = insertQueueRow(conn, "PEPPOL_PARTICIPANT", pep2, "CALLBACK_IDENTITY_CONFLICT");

            // 負例1: scope完全性チェック (未解scope拒否)
            assertThrows(IllegalArgumentException.class, () -> repairPeppolConflictInTransaction(
                    conn, pep1, pep2, null, null,
                    pepQueue1, 0, pepQueue2, 0, humanUserId, "Invalid scope"));
            assertPeppolAndQueueUnchanged(conn, pep1, pep2, pepQueue1, pepQueue2);

            // 負例2: 顧客所有者不一致拒否 (別テナント・別法人のスコープを渡す)
            assertThrows(SecurityException.class, () -> repairPeppolConflictInTransaction(
                    conn, pep1, pep2, "tenant-mismatched", 999L,
                    pepQueue1, 0, pepQueue2, 0, humanUserId, "Mismatched ownership scope"));
            assertPeppolAndQueueUnchanged(conn, pep1, pep2, pepQueue1, pepQueue2);

            // 負例3: 削除済み顧客所有者拒否
            try (Statement s = conn.createStatement()) {
                s.executeUpdate("UPDATE m_customer SET deleted_flag=1 WHERE id=" + custB);
            }
            assertThrows(SecurityException.class, () -> repairPeppolConflictInTransaction(
                    conn, pep1, pep2, "tenant-pep-rep", 601L,
                    pepQueue1, 0, pepQueue2, 0, humanUserId, "Deleted customer owner"));
            assertPeppolAndQueueUnchanged(conn, pep1, pep2, pepQueue1, pepQueue2);
            // 顧客復元
            try (Statement s = conn.createStatement()) {
                s.executeUpdate("UPDATE m_customer SET deleted_flag=0 WHERE id=" + custB);
            }

            // 負例4: 残留重複の拒否 (両方をVERIFIEDにしようとする不正操作の拒否、MySQLエラーコード1062を明示核対)
            SQLException dupEx = assertThrows(SQLException.class, () -> attemptIllegalDuplicateActivation(
                    conn, pep1, pep2, "tenant-pep-rep", 601L));
            assertEquals(1062, dupEx.getErrorCode(), "アクティブコールバック重複時は明確にMySQLエラーコード1062であること");
            assertEquals("23000", dupEx.getSQLState(), "SQLStateは23000であること");
            assertPeppolAndQueueUnchanged(conn, pep1, pep2, pepQueue1, pepQueue2);

            // 負例5: expected-version CAS (キュー版数不一致で失敗・ロールバック)
            boolean pepCasFailed = repairPeppolConflictInTransaction(
                    conn, pep1, pep2, "tenant-pep-rep", 601L,
                    pepQueue1, 999, pepQueue2, 0, humanUserId, "Stale queue version");
            assertFalse(pepCasFailed, "キュー版数不一致時はCAS失敗");
            assertPeppolAndQueueUnchanged(conn, pep1, pep2, pepQueue1, pepQueue2);

            // 正当な修復結案 (正当な所有者・スコープ・版数で実行)
            boolean rep02Ok = repairPeppolConflictInTransaction(
                    conn, pep1, pep2, "tenant-pep-rep", 601L,
                    pepQueue1, 0, pepQueue2, 0, humanUserId, "TC-REP-02 deduplicated");
            assertTrue(rep02Ok, "TC-REP-02 重複Peppolの解決・結案成功");

            try (Statement s = conn.createStatement()) {
                assertEquals(1, queryCallbackActiveSlot(s, pep1),
                        "正当行はVERIFIEDかつdeleted_flag=0のため生成列callback_active_slot=1");
                assertNull(queryCallbackActiveSlot(s, pep2),
                        "無効化行はDUPLICATE_DEPRECATEDのため生成列callback_active_slotはNULL");

                // 2件目を再度VERIFIEDに更新しようとすると一意制約uk_peppol_callback_activeに違反する (1062核対)
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

        // TC-REP-06: 並行系・可観測なロック競合実証と敗者更新ゼロ残存アサーション
        long concurrentInvoiceId;
        long concurrentQueueId;
        try (Connection conn = createConnection(dbName)) {
            concurrentInvoiceId = insertUnresolvedInvoice(conn, "msg-rep-06-concurrent");
            concurrentQueueId = insertQueueRow(conn, "DIGITAL_INVOICE", concurrentInvoiceId, "SCOPE_UNRESOLVED");
        }

        Throwable primaryFailure = null;
        List<Throwable> cleanupFailures = new ArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Connection conn1 = null;
        Connection conn2 = null;
        Connection monitorConn = null;
        try {
            conn1 = createConnection(dbName);
            conn2 = createConnection(dbName);
            monitorConn = createConnection(dbName);

            conn1.setAutoCommit(false);
            conn2.setAutoCommit(false);

            long holderConnId;
            try (Statement s = conn1.createStatement()) {
                holderConnId = scalar(s, "SELECT CONNECTION_ID()");
            }
            long contenderConnId;
            try (Statement s = conn2.createStatement()) {
                contenderConnId = scalar(s, "SELECT CONNECTION_ID()");
            }

            CountDownLatch workerEnteringUpdate = new CountDownLatch(1);
            Future<RepairOutcome> future2 = null;
            boolean tx1Committed = false;

            try {
                // 1. 第1トランザクションがインボイスを先行更新し、排他行ロックを保持したまま待機
                int tx1InvoiceUpdated;
                try (PreparedStatement ps = conn1.prepareStatement(
                        "UPDATE t_digital_invoice SET tenant_id=?, legal_entity_id=?, version=version+1 "
                                + "WHERE id=? AND tenant_id IS NULL AND version=0")) {
                    ps.setString(1, "tenant-winner-tx1");
                    ps.setLong(2, 901L);
                    ps.setLong(3, concurrentInvoiceId);
                    tx1InvoiceUpdated = ps.executeUpdate();
                }
                assertEquals(1, tx1InvoiceUpdated, "第1トランザクションの先行更新が行ロックを獲得");

                // 2. 第2トランザクションを別スレッドで実行開始。同一インボイス行を更新しようとしてInnoDB行ロック待ちに入る
                future2 = pool.submit(() -> {
                    try {
                        workerEnteringUpdate.countDown();
                        int tx2Updated;
                        try (PreparedStatement ps = conn2.prepareStatement(
                                "UPDATE t_digital_invoice SET tenant_id=?, legal_entity_id=?, version=version+1 "
                                        + "WHERE id=? AND tenant_id IS NULL AND version=0")) {
                            ps.setString(1, "tenant-loser-tx2");
                            ps.setLong(2, 902L);
                            ps.setLong(3, concurrentInvoiceId);
                            tx2Updated = ps.executeUpdate();
                        }

                        // 正常なCAS競合敗北: Tx1が先行コミット(version=1)したため、
                        // 行ロック解除後のUPDATE条件(version=0かつtenant_id IS NULL)に合致せず更新行数0となる
                        if (tx2Updated == 0) {
                            conn2.rollback();
                            return RepairOutcome.CAS_CONFLICT;
                        }

                        // 異常系: ロック競合したはずの状況でTx2が先行更新してしまった場合
                        if (tx2Updated == 1) {
                            conn2.rollback();
                            throw new IllegalStateException("Tx2 unexpectedly updated invoice row despite Tx1 holding row lock first!");
                        }

                        conn2.rollback();
                        throw new IllegalStateException("Tx2 returned unexpected affected rows: " + tx2Updated);
                    } catch (SQLException e) {
                        conn2.rollback();
                        // 1205 (Lock wait timeout) や 1213 (Deadlock) は本シナリオで要求する「正常なアンブロック後のUPDATE 0行」とは異なるため、
                        // CAS_CONFLICTとして握り潰さず例外として再スローする
                        throw new RuntimeException(String.format(
                                "Contender worker failed with SQLException [errorCode=%d, sqlState=%s]: %s",
                                e.getErrorCode(), e.getSQLState(), e.getMessage()), e);
                    }
                });

                // workerがUPDATE直前まで到達したことを有限待機(5秒)で確認
                assertTrue(workerEnteringUpdate.await(5, TimeUnit.SECONDS),
                        "Worker thread failed to signal before entering UPDATE within 5 seconds");

                // 3. MySQL内部のrequester→blockerロック待ち関係を接続ID・対象テーブル・PRIMARYインデックス・ロックデータで確認
                long deadline = System.currentTimeMillis() + 5000;
                boolean lockWaitObserved = false;
                while (System.currentTimeMillis() < deadline) {
                    // (2) 観測ループでfuture.isDone()を検査: 早期終了時は即座に結果・例外を取得
                    if (future2.isDone()) {
                        try {
                            RepairOutcome prematureResult = future2.get();
                            fail(String.format("Contender worker completed prematurely with outcome %s before lock wait was observed! "
                                    + "[Holder Conn ID: %d, Contender Conn ID: %d]", prematureResult, holderConnId, contenderConnId));
                        } catch (ExecutionException ee) {
                            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                            fail(String.format("Contender worker failed prematurely before lock wait was observed! "
                                    + "Exception: %s: %s [Holder Conn ID: %d, Contender Conn ID: %d]",
                                    cause.getClass().getName(), cause.getMessage(), holderConnId, contenderConnId), cause);
                        }
                    }

                    try {
                        if (hasTargetInvoiceRecordLockWait(monitorConn, dbName, concurrentInvoiceId, contenderConnId, holderConnId)) {
                            lockWaitObserved = true;
                            break;
                        }
                    } catch (SQLException sqlEx) {
                        // 観測クエリ自体の失敗: 原例外(SQLState, errorCode)を保持し、即座にテストを失敗させる (診断失敗で上書きしない)
                        String diagnostics = "";
                        try {
                            diagnostics = captureLockContentionDiagnostics(
                                    monitorConn, holderConnId, contenderConnId, concurrentInvoiceId, future2);
                        } catch (Throwable diagEx) {
                            sqlEx.addSuppressed(diagEx);
                        }
                        String msg = String.format("ロック待ち観測クエリ実行中にSQLExceptionが発生しました [errorCode=%d, sqlState=%s]: %s\n%s",
                                sqlEx.getErrorCode(), sqlEx.getSQLState(), sqlEx.getMessage(), diagnostics);
                        throw new AssertionError(msg, sqlEx);
                    }
                    Thread.sleep(20);
                }

                if (!lockWaitObserved) {
                    String diagnostics = "";
                    try {
                        diagnostics = captureLockContentionDiagnostics(
                                monitorConn, holderConnId, contenderConnId, concurrentInvoiceId, future2);
                    } catch (Throwable diagEx) {
                        diagnostics = "\n(診断情報採取中に例外が発生しました: " + diagEx.getMessage() + ")";
                    }
                    fail("第2トランザクションが第1トランザクションの行ロック解放待ち(LOCK WAIT)に入ったことをMySQL側で観測実証できませんでした。\n"
                            + diagnostics);
                }

                // 4. ロック競合の実証を確認した後、第1トランザクションがキューを結案しコミット (ロック解放)
                int tx1QueueUpdated;
                try (PreparedStatement ps = conn1.prepareStatement(
                        "UPDATE t_nf09_scope_repair_queue SET status='RESOLVED', resolved_at=NOW(), "
                                + "resolved_by=?, resolution_note=?, version=version+1 "
                                + "WHERE id=? AND status='PENDING' AND version=0")) {
                    ps.setLong(1, humanUserId);
                    ps.setString(2, "Tx1 wins");
                    ps.setLong(3, concurrentQueueId);
                    tx1QueueUpdated = ps.executeUpdate();
                }
                assertEquals(1, tx1QueueUpdated, "第1トランザクションのキュー結案成功");
                conn1.commit();
                tx1Committed = true;

                // 5. 第2トランザクションの完了結果を取得 (有界タイムアウト保護付き)
                RepairOutcome result2 = future2.get(5, TimeUnit.SECONDS);
                assertEquals(RepairOutcome.CAS_CONFLICT, result2,
                        "第2トランザクションは行ロック解放後に再開されたが、version/tenant_id条件不一致によりCAS敗北(UPDATE 0行)すること");

                // 6. DB内の確定状態を厳格に核対: 勝者の反映、および敗者更新の完全ゼロ残存
                try (Connection verifyConn = createConnection(dbName); Statement s = verifyConn.createStatement()) {
                    assertEquals("tenant-winner-tx1", queryInvoiceTenant(verifyConn, concurrentInvoiceId));
                    assertEquals(901L, queryInvoiceLegalEntity(verifyConn, concurrentInvoiceId));
                    assertEquals(1L, scalar(s, "SELECT version FROM t_digital_invoice WHERE id=" + concurrentInvoiceId),
                            "インボイスのバージョンは勝者Tx1による1加算のみ");

                    // 敗者(Tx2)のゼロ残存検証
                    assertEquals(0L, scalar(s, "SELECT COUNT(*) FROM t_digital_invoice WHERE tenant_id='tenant-loser-tx2'"),
                            "敗者のテナント値はDB内に1件も残存していないこと (dirty更新ゼロ残存)");

                    // キュー状態の検証
                    assertEquals("RESOLVED", queryQueueStatus(verifyConn, concurrentQueueId));
                    assertEquals(1L, scalar(s, "SELECT version FROM t_nf09_scope_repair_queue WHERE id=" + concurrentQueueId),
                            "キューのバージョンは勝者Tx1による1加算のみ");
                    assertEquals(humanUserId, queryQueueResolvedBy(verifyConn, concurrentQueueId),
                            "キューの解決者は勝者Tx1のhumanUserId");
                }
            } catch (Throwable t) {
                primaryFailure = t;
                throw t;
            } finally {
                // 5. 失敗クリーンアップ時: 安全にholderを解放(未コミット時はロールバック)し、有界にworkerの最終結果を回収
                if (!tx1Committed && conn1 != null) {
                    try {
                        conn1.rollback();
                    } catch (Throwable t) {
                        recordCleanupFailure(primaryFailure, cleanupFailures, t);
                    }
                }
                if (future2 != null) {
                    try {
                        RepairOutcome workerResult = future2.get(2, TimeUnit.SECONDS);
                    } catch (ExecutionException ee) {
                        Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                        recordCleanupFailure(primaryFailure, cleanupFailures,
                                new AssertionError("クリーンアップ中に検知されたWorker実行失敗: " + cause.getMessage(), cause));
                    } catch (TimeoutException te) {
                        recordCleanupFailure(primaryFailure, cleanupFailures,
                                new AssertionError("クリーンアップ中のWorker終了待機(2秒)がタイムアウトしました", te));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        recordCleanupFailure(primaryFailure, cleanupFailures,
                                new AssertionError("クリーンアップ中のWorker待機が割り込まれました", ie));
                    } catch (Throwable t) {
                        recordCleanupFailure(primaryFailure, cleanupFailures, t);
                    }
                }
                closeConnection(conn1, primaryFailure, cleanupFailures);
                closeConnection(conn2, primaryFailure, cleanupFailures);
                closeConnection(monitorConn, primaryFailure, cleanupFailures);
            }
        } catch (Throwable t) {
            if (primaryFailure == null) {
                primaryFailure = t;
            }
            throw t;
        } finally {
            try {
                pool.shutdownNow();
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    recordCleanupFailure(primaryFailure, cleanupFailures,
                            new AssertionError("スレッドプールが有界時間(5秒)内に安全終了しませんでした"));
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                recordCleanupFailure(primaryFailure, cleanupFailures,
                        new AssertionError("スレッドプール終了待機が割り込まれました", ie));
            } catch (Throwable t) {
                recordCleanupFailure(primaryFailure, cleanupFailures, t);
            }

            if (primaryFailure == null && !cleanupFailures.isEmpty()) {
                throwCleanupFailures(cleanupFailures);
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

        // 1. 独立した証拠退避ディレクトリを事前作成 (断言失敗時も取得済み現場を実ディスクに保持可能とする)
        Path evidenceDir = Path.of("target/v182-rehearsal-evidence/run-"
                + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(evidenceDir);

        String dbName = "ses_v182_fault_isolation";
        // 隔離テストロケーションから意図的な失敗マイグレーションを注入し、確定的中途失敗を再現
        // 対象バージョンを明示的に182.99に限定
        FluentConfiguration faultConfig = flywayConfig(dbName)
                .locations("classpath:db/migration", "classpath:migration-test/v182-fault")
                .target("182.99");

        // 2. migrate開始前に既存ロギングフレームワークのテスト用コレクタを接続
        Logger flywayLogger = (Logger) LoggerFactory.getLogger("org.flywaydb");
        Level originalLevel = flywayLogger.getLevel();
        flywayLogger.setLevel(Level.INFO);
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        logAppender.start();
        flywayLogger.addAppender(logAppender);

        Throwable caughtMigrationError = null;
        try {
            faultConfig.load().migrate();
        } catch (Throwable t) {
            caughtMigrationError = t;
        } finally {
            try {
                flywayLogger.detachAppender(logAppender);
                logAppender.stop();
            } finally {
                flywayLogger.setLevel(originalLevel);
            }
        }
        List<ILoggingEvent> capturedLogEvents = new ArrayList<>(logAppender.list);

        // 3. 失敗検知後、まずリアルログ、元例外、取得可能なDB証拠を退避 (断言より先行実行)
        List<String> collectionWarnings = new ArrayList<>();

        // (a) 実Flyway実行ログの落盤 (手動メタデータサマリで冒称しない)
        try {
            dumpCapturedFlywayLogs(evidenceDir.resolve("flyway_execution.log"), capturedLogEvents);
        } catch (Throwable t) {
            collectionWarnings.add("flyway_execution.log dump failed: " + t.getMessage());
        }

        // (b) 取得可能なDB証拠の落盤 (一部テーブル不存在や早期失敗時も他の証拠収集を継続)
        try (Connection conn = createConnection(dbName)) {
            dumpHistoryToDisk(conn, evidenceDir.resolve("flyway_schema_history.csv"), collectionWarnings);
            dumpDdlToDisk(conn, evidenceDir.resolve("tables_ddl.sql"), collectionWarnings);
            dumpCountsToDisk(conn, evidenceDir.resolve("table_counts.json"), collectionWarnings);
            dumpQueueToDisk(conn, evidenceDir.resolve("scope_repair_queue.csv"), collectionWarnings);
        } catch (Throwable t) {
            collectionWarnings.add("Database connection failed during evidence collection: " + t.getMessage());
        }

        // (c) 診断情報・例外原因チェーン・AI-1帰属通知・メタデータサマリの落盤
        try {
            dumpDiagnosticsToDisk(evidenceDir.resolve("execution_diagnostics.txt"),
                    caughtMigrationError, collectionWarnings);
        } catch (Throwable t) {
            collectionWarnings.add("execution_diagnostics.txt dump failed: " + t.getMessage());
        }

        // (d) SHA-256マニフェスト作成
        try {
            createManifestFile(evidenceDir);
        } catch (Throwable t) {
            collectionWarnings.add("manifest-sha256.txt generation failed: " + t.getMessage());
        }

        // 4. 必需証拠ファイルが実ディスクにすべて揃い、読込可能であり、SHA-256マニフェストと一致することを検証
        verifyPersistedEvidence(evidenceDir);

        // 取証エラーが存在する場合はテストを失敗させ、欠損証拠を合格と見做さない
        assertTrue(collectionWarnings.isEmpty(),
                "証拠収集プロセスが例外なく完了していること (収集異常: " + collectionWarnings + ")");

        // 5. 証拠落盤完了後に、マイグレーション失敗の型・エラーコード・制約名・DB状態・実ログ取得を検証
        assertFalse(capturedLogEvents.isEmpty(),
                "実際のFlywayログイベントが1件以上取得されていること");

        assertNotNull(caughtMigrationError, "隔離マイグレーションリソースによる確定的中途失敗が検知されること");
        assertTrue(caughtMigrationError instanceof FlywayException,
                "発生した例外がFlywayExceptionであること: " + caughtMigrationError.getClass().getName());

        SQLException rootSqlEx = findSqlException((Exception) caughtMigrationError);
        assertNotNull(rootSqlEx, "FlywayExceptionの原因チェーンにSQLExceptionが含まれること");
        assertEquals(3819, rootSqlEx.getErrorCode(), "予期したCHECK制約違反エラーコード(3819)であること");
        assertEquals("HY000", rootSqlEx.getSQLState(), "SQLStateはHY000であること");
        assertTrue(rootSqlEx.getMessage().contains("chk_v182_fault_sim"),
                "エラーメッセージに注入CHECK制約名が含まれること: " + rootSqlEx.getMessage());

        // データベース内の状態を検証: V182は成功、182.99は失敗、前置DDLは暗黙コミットされていること
        try (Connection conn = createConnection(dbName); Statement stmt = conn.createStatement()) {
            assertEquals(1L, scalar(stmt,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='182' AND success=1"),
                    "本番V182マイグレーションは正常完了していること");
            assertEquals(1L, scalar(stmt,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='182.99' AND success=0"),
                    "隔離V182.99マイグレーションは失敗(success=0)として記録されていること");
            assertEquals(1L, scalar(stmt,
                    "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema=DATABASE() "
                            + "AND table_name='t_digital_invoice' AND constraint_name='chk_v182_fault_sim'"),
                    "障害注入の前置DDL(ALTER TABLE)がMySQLの暗黙コミットにより永続化されていること");
        }

        // 重要注釈: 本演練はV182完了後の隔離故障注入による証拠保全の事前検証であり、
        // 本番環境における中途コミット破損状態からの部分マイグレーション回復やforward-fixを検証したと混称しない。
    }

    // =========================================================================
    // ヘルパーメソッド群
    // =========================================================================

    private FluentConfiguration flywayConfig(String dbName) throws SQLException {
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
    }

    private void assertGeneratedCallbackActiveSlot(Statement stmt) throws Exception {
        assertEquals(1L, scalar(stmt, "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='t_peppol_participant' "
                + "AND column_name='callback_active_slot' AND extra LIKE '%VIRTUAL GENERATED%'"));
    }

    private void assertCompositeIndexes(Connection conn) throws SQLException {
        // t_digital_invoice
        assertIndexDefinition(conn, "t_digital_invoice", "uk_digital_invoice_scope_message", true,
                List.of("tenant_id", "legal_entity_id", "message_id"));
        assertIndexDefinition(conn, "t_digital_invoice", "uk_digital_invoice_scope_send", true,
                List.of("tenant_id", "legal_entity_id", "invoice_id", "direction", "profile", "specification_version", "send_active_slot"));
        assertIndexDefinition(conn, "t_digital_invoice", "uk_digital_invoice_scope_id", true,
                List.of("tenant_id", "legal_entity_id", "id"));
        assertIndexDefinition(conn, "t_digital_invoice", "idx_digital_invoice_scope_status", false,
                List.of("tenant_id", "legal_entity_id", "direction", "status", "id"));
        assertIndexDefinition(conn, "t_digital_invoice", "idx_digital_invoice_scope_invoice", false,
                List.of("tenant_id", "legal_entity_id", "invoice_id", "direction", "profile", "id"));

        // t_digital_invoice_event
        assertIndexDefinition(conn, "t_digital_invoice_event", "idx_digital_invoice_event_scope", false,
                List.of("tenant_id", "legal_entity_id", "digital_invoice_id", "event_at", "id"));

        // t_peppol_participant
        assertIndexDefinition(conn, "t_peppol_participant", "uk_peppol_scope_owner", true,
                List.of("tenant_id", "legal_entity_id", "owner_type", "owner_id"));
        assertIndexDefinition(conn, "t_peppol_participant", "uk_peppol_callback_active", true,
                List.of("provider", "scheme_id", "participant_id", "callback_active_slot"));
        assertIndexDefinition(conn, "t_peppol_participant", "idx_peppol_scope_owner", false,
                List.of("tenant_id", "legal_entity_id", "owner_type", "owner_id", "deleted_flag"));
        assertIndexDefinition(conn, "t_peppol_participant", "idx_peppol_callback_identity", false,
                List.of("provider", "scheme_id", "participant_id", "status", "deleted_flag"));

        // t_external_account_reference
        assertIndexDefinition(conn, "t_external_account_reference", "uq_ext_scope_idempotency", true,
                List.of("tenant_id", "legal_entity_id", "idempotency_key"));
        assertIndexDefinition(conn, "t_external_account_reference", "idx_external_account_scope_target", false,
                List.of("tenant_id", "legal_entity_id", "assignee_type", "assignee_id", "status"));
        assertIndexDefinition(conn, "t_external_account_reference", "idx_external_account_scope_retry", false,
                List.of("tenant_id", "legal_entity_id", "status", "next_retry_at"));

        // t_nf09_scope_repair_queue
        assertIndexDefinition(conn, "t_nf09_scope_repair_queue", "uk_nf09_scope_repair_entity", true,
                List.of("entity_type", "entity_id"));
        assertIndexDefinition(conn, "t_nf09_scope_repair_queue", "idx_nf09_scope_repair_status", false,
                List.of("status", "entity_type", "created_at"));
    }

    private void assertIndexDefinition(Connection conn, String table, String indexName, boolean unique, List<String> expectedColumns) throws SQLException {
        String sql = "SELECT non_unique, column_name "
                + "FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ? "
                + "ORDER BY seq_in_index";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> actualColumns = new ArrayList<>();
                Boolean actualUnique = null;
                while (rs.next()) {
                    if (actualUnique == null) {
                        actualUnique = rs.getInt("non_unique") == 0;
                    }
                    actualColumns.add(rs.getString("column_name"));
                }
                assertNotNull(actualUnique, "インデックスが存在すること: " + table + "." + indexName);
                assertEquals(unique, actualUnique, "一意性フラグの一致: " + table + "." + indexName);
                assertEquals(expectedColumns, actualColumns, "有序列集合の一致: " + table + "." + indexName);
            }
        }
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

        if (newTenant == null || newTenant.isBlank() || newLegalEntity == null) {
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

    private enum RepairOutcome {
        SUCCESS,
        CAS_CONFLICT
    }

    private boolean hasTargetInvoiceRecordLockWait(
            Connection conn, String dbName, long invoiceId, long requestingConnId, long blockingConnId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                + "JOIN performance_schema.data_locks req ON req.ENGINE = w.ENGINE "
                + "  AND req.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID "
                + "JOIN performance_schema.data_locks blk ON blk.ENGINE = w.ENGINE "
                + "  AND blk.ENGINE_LOCK_ID = w.BLOCKING_ENGINE_LOCK_ID "
                + "JOIN performance_schema.threads rt ON rt.THREAD_ID = req.THREAD_ID "
                + "JOIN performance_schema.threads bt ON bt.THREAD_ID = blk.THREAD_ID "
                + "WHERE (req.OBJECT_SCHEMA = DATABASE() OR req.OBJECT_SCHEMA = ?) "
                + "  AND req.OBJECT_NAME = 't_digital_invoice' "
                + "  AND req.INDEX_NAME = 'PRIMARY' AND req.LOCK_TYPE = 'RECORD' "
                + "  AND (blk.OBJECT_SCHEMA = DATABASE() OR blk.OBJECT_SCHEMA = ?) "
                + "  AND blk.OBJECT_NAME = 't_digital_invoice' "
                + "  AND blk.INDEX_NAME = 'PRIMARY' AND blk.LOCK_TYPE = 'RECORD' "
                + "  AND (CAST(req.LOCK_DATA AS UNSIGNED) = ? OR req.LOCK_DATA = ?) "
                + "  AND (CAST(blk.LOCK_DATA AS UNSIGNED) = ? OR blk.LOCK_DATA = ?) "
                + "  AND rt.PROCESSLIST_ID = ? AND bt.PROCESSLIST_ID = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dbName);
            ps.setString(2, dbName);
            ps.setLong(3, invoiceId);
            ps.setString(4, String.valueOf(invoiceId));
            ps.setLong(5, invoiceId);
            ps.setString(6, String.valueOf(invoiceId));
            ps.setLong(7, requestingConnId);
            ps.setLong(8, blockingConnId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getLong(1) > 0;
            }
        }
    }

    private String captureLockContentionDiagnostics(
            Connection conn, long holderConnId, long contenderConnId,
            long invoiceId, Future<RepairOutcome> future2) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== LOCK CONTENTION DIAGNOSTICS ===\n");
        sb.append(String.format("Target Invoice ID: %d\n", invoiceId));
        sb.append(String.format("Holder Connection ID: %d, Contender Connection ID: %d\n", holderConnId, contenderConnId));
        sb.append(String.format("Worker Future isDone: %b\n", future2 != null && future2.isDone()));
        if (future2 != null && future2.isDone()) {
            try {
                RepairOutcome outcome = future2.get(0, TimeUnit.MILLISECONDS);
                sb.append(String.format("Worker Future Outcome: %s\n", outcome));
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                sb.append(String.format("Worker Future Exception: %s: %s\n", cause.getClass().getName(), cause.getMessage()));
            } catch (TimeoutException te) {
                sb.append("Worker Future: Timeout checking completion status\n");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                sb.append("Worker Future: Interrupted checking completion status\n");
            } catch (Throwable t) {
                sb.append(String.format("Worker Future Status: %s\n", t.getMessage()));
            }
        }

        // 1. Transactions from information_schema.innodb_trx
        sb.append("--- Active InnoDB Transactions (information_schema.innodb_trx) ---\n");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT trx_id, trx_state, trx_mysql_thread_id, trx_query, trx_wait_started, "
                             + "trx_tables_in_use, trx_tables_locked, trx_rows_locked "
                             + "FROM information_schema.innodb_trx")) {
            boolean found = false;
            while (rs.next()) {
                found = true;
                sb.append(String.format("  trx_id=%s, state=%s, conn_id=%d, query=%s, wait_started=%s, tables_locked=%s, rows_locked=%s\n",
                        rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                        rs.getString(5), rs.getString(7), rs.getString(8)));
            }
            if (!found) {
                sb.append("  (No active transactions found in innodb_trx)\n");
            }
        } catch (SQLException e) {
            sb.append("  (Failed to query innodb_trx: ").append(e.getMessage()).append(")\n");
        }

        // 2. Data locks from performance_schema.data_locks
        sb.append("--- Data Locks (performance_schema.data_locks) ---\n");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT dl.OBJECT_SCHEMA, dl.OBJECT_NAME, dl.INDEX_NAME, dl.LOCK_TYPE, dl.LOCK_MODE, "
                             + "dl.LOCK_STATUS, dl.LOCK_DATA, th.PROCESSLIST_ID "
                             + "FROM performance_schema.data_locks dl "
                             + "JOIN performance_schema.threads th ON th.THREAD_ID = dl.THREAD_ID "
                             + "WHERE dl.OBJECT_NAME = 't_digital_invoice' OR th.PROCESSLIST_ID IN (" + holderConnId + ", " + contenderConnId + ")")) {
            boolean found = false;
            while (rs.next()) {
                found = true;
                sb.append(String.format("  schema=%s, table=%s, index=%s, type=%s, mode=%s, status=%s, data=%s, conn_id=%d\n",
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8)));
            }
            if (!found) {
                sb.append("  (No data locks found for target table/connections)\n");
            }
        } catch (SQLException e) {
            sb.append("  (Failed to query data_locks: ").append(e.getMessage()).append(")\n");
        }

        // 3. Lock waits from performance_schema.data_lock_waits
        sb.append("--- Data Lock Waits (performance_schema.data_lock_waits) ---\n");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT req_th.PROCESSLIST_ID AS req_conn, blk_th.PROCESSLIST_ID AS blk_conn, "
                             + "req.OBJECT_NAME, req.INDEX_NAME, req.LOCK_TYPE, req.LOCK_DATA, "
                             + "req.LOCK_MODE AS req_mode, blk.LOCK_MODE AS blk_mode "
                             + "FROM performance_schema.data_lock_waits w "
                             + "JOIN performance_schema.data_locks req ON req.ENGINE = w.ENGINE AND req.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID "
                             + "JOIN performance_schema.data_locks blk ON blk.ENGINE = w.ENGINE AND blk.ENGINE_LOCK_ID = w.BLOCKING_ENGINE_LOCK_ID "
                             + "JOIN performance_schema.threads req_th ON req_th.THREAD_ID = req.THREAD_ID "
                             + "JOIN performance_schema.threads blk_th ON blk_th.THREAD_ID = blk.THREAD_ID")) {
            boolean found = false;
            while (rs.next()) {
                found = true;
                sb.append(String.format("  requester_conn=%d -> blocker_conn=%d, table=%s, index=%s, lock_data=%s, req_mode=%s, blk_mode=%s\n",
                        rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(6), rs.getString(7), rs.getString(8)));
            }
            if (!found) {
                sb.append("  (No data lock waits found in data_lock_waits)\n");
            }
        } catch (SQLException e) {
            sb.append("  (Failed to query data_lock_waits: ").append(e.getMessage()).append(")\n");
        }

        // 4. Processlist state
        sb.append("--- Processlist States ---\n");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT ID, USER, HOST, DB, COMMAND, TIME, STATE, INFO "
                             + "FROM information_schema.processlist "
                             + "WHERE ID IN (" + holderConnId + ", " + contenderConnId + ")")) {
            while (rs.next()) {
                sb.append(String.format("  conn_id=%d, command=%s, time=%d, state=%s, info=%s\n",
                        rs.getLong(1), rs.getString(5), rs.getInt(6), rs.getString(7), rs.getString(8)));
            }
        } catch (SQLException e) {
            sb.append("  (Failed to query processlist: ").append(e.getMessage()).append(")\n");
        }
        sb.append("===================================\n");
        return sb.toString();
    }

    private void recordCleanupFailure(Throwable primaryFailure, List<Throwable> cleanupFailures, Throwable newError) {
        if (newError == null) {
            return;
        }
        if (primaryFailure != null) {
            primaryFailure.addSuppressed(newError);
        } else {
            cleanupFailures.add(newError);
        }
    }

    private void closeConnection(Connection conn, Throwable primaryFailure, List<Throwable> cleanupFailures) {
        if (conn != null) {
            try {
                conn.close();
            } catch (Throwable t) {
                recordCleanupFailure(primaryFailure, cleanupFailures, t);
            }
        }
    }

    private void throwCleanupFailures(List<Throwable> failures) throws Exception {
        if (failures == null || failures.isEmpty()) {
            return;
        }
        Throwable first = failures.get(0);
        for (int i = 1; i < failures.size(); i++) {
            first.addSuppressed(failures.get(i));
        }
        if (first instanceof Exception ex) {
            throw ex;
        } else if (first instanceof Error err) {
            throw err;
        } else {
            throw new RuntimeException("クリーンアップ中に例外が発生しました", first);
        }
    }

    private void verifyCustomerOwnership(Connection conn, long participantId, String expectedTenant, Long expectedLegalEntity) throws SQLException {
        String ownerType;
        long ownerId;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT owner_type, owner_id FROM t_peppol_participant WHERE id = ?")) {
            ps.setLong(1, participantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SecurityException("Peppol参加者レコードが存在しません: id=" + participantId);
                }
                ownerType = rs.getString("owner_type");
                ownerId = rs.getLong("owner_id");
            }
        }

        if (!"CUSTOMER".equals(ownerType)) {
            throw new SecurityException("参加者の所有者区分がCUSTOMERではありません: " + ownerType + " (id=" + participantId + ")");
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT tenant_id, legal_entity_id, deleted_flag FROM m_customer WHERE id = ?")) {
            ps.setLong(1, ownerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SecurityException("紐づく顧客が存在しません: customerId=" + ownerId);
                }
                if (rs.getInt("deleted_flag") != 0) {
                    throw new SecurityException("紐づく顧客は削除済みです: customerId=" + ownerId);
                }
                String custTenant = rs.getString("tenant_id");
                Long custLegalEntity = rs.getObject("legal_entity_id") == null ? null : rs.getLong("legal_entity_id");
                if (custTenant == null || !custTenant.equals(expectedTenant)
                        || custLegalEntity == null || !custLegalEntity.equals(expectedLegalEntity)) {
                    throw new SecurityException(String.format(
                            "顧客のスコープ境界不一致: expected (%s, %d) but customer is (%s, %s)",
                            expectedTenant, expectedLegalEntity, custTenant, custLegalEntity));
                }
            }
        }
    }

    private boolean repairPeppolConflictInTransaction(
            Connection conn, long winnerId, long loserId,
            String tenantId, Long legalEntityId,
            long winnerQueueId, int expectedWinnerQueueVersion,
            long loserQueueId, int expectedLoserQueueVersion,
            long humanUserId, String note) throws SQLException {

        if (tenantId == null || tenantId.isBlank() || legalEntityId == null) {
            throw new IllegalArgumentException("Peppolスコープが未確定(NULL)のまま結案はできません");
        }

        // 真正なCustomer Ownership検証 (存在・非削除・スコープ一致)
        verifyCustomerOwnership(conn, winnerId, tenantId, legalEntityId);
        verifyCustomerOwnership(conn, loserId, tenantId, legalEntityId);

        boolean prevAuto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            // 勝者をVERIFIED, deleted_flag=0 に更新 (callback_active_slot=1 が自動導出される)
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

            // 敗者をDUPLICATE_DEPRECATEDに無効化 (callback_active_slot=NULL が自動導出される)
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

            // 両キュー行のexpected-version CAS結案
            int wUpdated;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE t_nf09_scope_repair_queue SET status='RESOLVED', resolved_at=NOW(), "
                            + "resolved_by=?, resolution_note=?, version=version+1 "
                            + "WHERE id=? AND status='PENDING' AND version=?")) {
                ps.setLong(1, humanUserId);
                ps.setString(2, note);
                ps.setLong(3, winnerQueueId);
                ps.setInt(4, expectedWinnerQueueVersion);
                wUpdated = ps.executeUpdate();
            }

            if (wUpdated != 1) {
                conn.rollback();
                return false;
            }

            int lUpdated;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE t_nf09_scope_repair_queue SET status='RESOLVED', resolved_at=NOW(), "
                            + "resolved_by=?, resolution_note=?, version=version+1 "
                            + "WHERE id=? AND status='PENDING' AND version=?")) {
                ps.setLong(1, humanUserId);
                ps.setString(2, note);
                ps.setLong(3, loserQueueId);
                ps.setInt(4, expectedLoserQueueVersion);
                lUpdated = ps.executeUpdate();
            }

            if (lUpdated != 1) {
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

    private void attemptIllegalDuplicateActivation(Connection conn, long id1, long id2, String tenantId, long legalEntityId) throws SQLException {
        boolean prevAuto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE t_peppol_participant SET tenant_id=?, legal_entity_id=?, status='VERIFIED', "
                            + "deleted_flag=0 WHERE id=?")) {
                ps.setString(1, tenantId);
                ps.setLong(2, legalEntityId);
                ps.setLong(3, id1);
                ps.executeUpdate();

                ps.setString(1, tenantId);
                ps.setLong(2, legalEntityId);
                ps.setLong(3, id2);
                ps.executeUpdate(); // uk_peppol_callback_active 重複違反 (1062) が発生する
            }
            conn.commit();
        } finally {
            conn.rollback();
            conn.setAutoCommit(prevAuto);
        }
    }

    private void assertPeppolAndQueueUnchanged(Connection conn, long pep1, long pep2, long queue1, long queue2) throws SQLException {
        try (Statement s = conn.createStatement()) {
            // pep1
            try (ResultSet rs = s.executeQuery("SELECT tenant_id, legal_entity_id, status, verified_at FROM t_peppol_participant WHERE id=" + pep1)) {
                assertTrue(rs.next());
                assertNull(rs.getString("tenant_id"));
                assertNull(rs.getObject("legal_entity_id"));
                assertEquals("CONFLICT_REPAIR", rs.getString("status"));
                assertNull(rs.getTimestamp("verified_at"));
            }
            // pep2
            try (ResultSet rs = s.executeQuery("SELECT tenant_id, legal_entity_id, status, verified_at FROM t_peppol_participant WHERE id=" + pep2)) {
                assertTrue(rs.next());
                assertNull(rs.getString("tenant_id"));
                assertNull(rs.getObject("legal_entity_id"));
                assertEquals("CONFLICT_REPAIR", rs.getString("status"));
                assertNull(rs.getTimestamp("verified_at"));
            }
            // queue1
            try (ResultSet rs = s.executeQuery("SELECT status, version, resolved_at, resolved_by, resolution_note FROM t_nf09_scope_repair_queue WHERE id=" + queue1)) {
                assertTrue(rs.next());
                assertEquals("PENDING", rs.getString("status"));
                assertEquals(0, rs.getInt("version"));
                assertNull(rs.getTimestamp("resolved_at"));
                assertNull(rs.getObject("resolved_by"));
                assertNull(rs.getString("resolution_note"));
            }
            // queue2
            try (ResultSet rs = s.executeQuery("SELECT status, version, resolved_at, resolved_by, resolution_note FROM t_nf09_scope_repair_queue WHERE id=" + queue2)) {
                assertTrue(rs.next());
                assertEquals("PENDING", rs.getString("status"));
                assertEquals(0, rs.getInt("version"));
                assertNull(rs.getTimestamp("resolved_at"));
                assertNull(rs.getObject("resolved_by"));
                assertNull(rs.getString("resolution_note"));
            }
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

    private Long queryInvoiceLegalEntity(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT legal_entity_id FROM t_digital_invoice WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long val = rs.getLong(1);
                    return rs.wasNull() ? null : val;
                }
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

    private Long queryQueueResolvedBy(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT resolved_by FROM t_nf09_scope_repair_queue WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long val = rs.getLong(1);
                    return rs.wasNull() ? null : val;
                }
                return null;
            }
        }
    }

    private void assertMysqlFailure(Executable executable, int expectedErrorCode, String expectedSqlState, String message) {
        SQLException ex = assertThrows(SQLException.class, executable::execute, message);
        assertEquals(expectedErrorCode, ex.getErrorCode(), "MySQLエラーコード不一致: " + message);
        assertEquals(expectedSqlState, ex.getSQLState(), "SQLState不一致: " + message);
    }

    private SQLException findSqlException(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof SQLException sqlEx) {
                return sqlEx;
            }
            cur = cur.getCause();
        }
        return null;
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

    // =========================================================================
    // 証拠ディスク落盤および検証ヘルパー (第5節)
    // =========================================================================

    private void dumpHistoryToDisk(Connection conn, Path targetFile, List<String> collectionWarnings) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("installed_rank,version,description,type,script,checksum,installed_by,installed_on,execution_time,success\n");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success FROM flyway_schema_history ORDER BY installed_rank")) {
            while (rs.next()) {
                sb.append(String.format("%d,%s,%s,%s,%s,%s,%s,%s,%d,%d\n",
                        rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getInt(9), rs.getInt(10)));
            }
        } catch (SQLException e) {
            String warning = "flyway_schema_history query failed: " + e.getMessage();
            collectionWarnings.add(warning);
            sb.append(String.format("# ERROR: %s\n", warning));
        }
        Files.writeString(targetFile, sb.toString(), StandardCharsets.UTF_8);
    }

    private void dumpDdlToDisk(Connection conn, Path targetFile, List<String> collectionWarnings) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("/* === TABLE DDL DUMP AT FAULT TEARDOWN === */\n\n");
        for (String table : List.of("t_digital_invoice", "t_digital_invoice_event", "t_peppol_participant",
                "t_external_account_reference", "t_nf09_scope_repair_queue")) {
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SHOW CREATE TABLE `" + table + "`")) {
                if (rs.next()) {
                    sb.append("/* Table: ").append(table).append(" */\n");
                    sb.append(rs.getString(2)).append(";\n\n");
                }
            } catch (SQLException e) {
                String warning = "DDL dump failed for table `" + table + "`: " + e.getMessage();
                collectionWarnings.add(warning);
                sb.append("/* Table `").append(table).append("` DDL unavailable: ").append(e.getMessage()).append(" */\n\n");
            }
        }
        Files.writeString(targetFile, sb.toString(), StandardCharsets.UTF_8);
    }

    private void dumpCountsToDisk(Connection conn, Path targetFile, List<String> collectionWarnings) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        List<String> tables = List.of("t_digital_invoice", "t_digital_invoice_event",
                "t_peppol_participant", "t_external_account_reference", "t_nf09_scope_repair_queue");
        try (Statement s = conn.createStatement()) {
            for (int i = 0; i < tables.size(); i++) {
                String table = tables.get(i);
                try {
                    long total = scalar(s, "SELECT COUNT(*) FROM `" + table + "`");
                    long nullTenant = 0;
                    long nullLegal = 0;
                    if (!"t_nf09_scope_repair_queue".equals(table)) {
                        nullTenant = scalar(s, "SELECT COUNT(*) FROM `" + table + "` WHERE tenant_id IS NULL");
                        nullLegal = scalar(s, "SELECT COUNT(*) FROM `" + table + "` WHERE legal_entity_id IS NULL");
                    }
                    sb.append(String.format("  \"%s\": { \"total\": %d, \"null_tenant\": %d, \"null_legal_entity\": %d }%s\n",
                            table, total, nullTenant, nullLegal, i < tables.size() - 1 ? "," : ""));
                } catch (SQLException e) {
                    String warning = "Count query failed for table `" + table + "`: " + e.getMessage();
                    collectionWarnings.add(warning);
                    sb.append(String.format("  \"%s\": { \"error\": \"%s\" }%s\n",
                            table, e.getMessage().replace("\"", "\\\"").replace("\n", " "), i < tables.size() - 1 ? "," : ""));
                }
            }
        } catch (SQLException e) {
            String warning = "Statement creation failed for table counts: " + e.getMessage();
            collectionWarnings.add(warning);
            sb.append("  \"error\": \"").append(e.getMessage().replace("\"", "\\\"")).append("\"\n");
        }
        sb.append("}\n");
        Files.writeString(targetFile, sb.toString(), StandardCharsets.UTF_8);
    }

    private void dumpQueueToDisk(Connection conn, Path targetFile, List<String> collectionWarnings) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("id,entity_type,entity_id,reason,candidate_tenant_id,candidate_legal_entity_id,status,version,resolved_at,resolved_by,resolution_note,created_at\n");
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, entity_type, entity_id, reason, candidate_tenant_id, candidate_legal_entity_id, status, version, resolved_at, resolved_by, resolution_note, created_at FROM t_nf09_scope_repair_queue ORDER BY id")) {
            while (rs.next()) {
                sb.append(String.format("%d,%s,%d,%s,%s,%s,%s,%d,%s,%s,%s,%s\n",
                        rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getInt(8),
                        rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12)));
            }
        } catch (SQLException e) {
            String warning = "Repair queue dump failed: " + e.getMessage();
            collectionWarnings.add(warning);
            sb.append(String.format("# ERROR: %s\n", warning));
        }
        Files.writeString(targetFile, sb.toString(), StandardCharsets.UTF_8);
    }

    private void dumpDiagnosticsToDisk(Path targetFile, Throwable ex, List<String> collectionWarnings) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("=== FAILURE DIAGNOSTICS ARCHIVE ===\n");
        sb.append("Timestamp: ").append(java.time.Instant.now()).append("\n");
        if (ex != null) {
            sb.append("Exception Class: ").append(ex.getClass().getName()).append("\n");
            sb.append("Message: ").append(ex.getMessage()).append("\n\n");

            sb.append("--- EXCEPTION CAUSE CHAIN ---\n");
            Throwable cur = ex;
            int depth = 0;
            while (cur != null) {
                sb.append(String.format("[%d] %s: %s\n", depth++, cur.getClass().getName(), cur.getMessage()));
                if (cur instanceof SQLException sqlEx) {
                    sb.append(String.format("    SQLState: %s, ErrorCode: %d\n", sqlEx.getSQLState(), sqlEx.getErrorCode()));
                }
                cur = cur.getCause();
            }
        } else {
            sb.append("Exception: NONE (Migration completed without throwing an exception!)\n");
        }
        sb.append("\n");

        sb.append("--- REHEARSAL EXECUTION SUMMARY (METADATA) ---\n");
        sb.append("Target: 182.99\n");
        sb.append("Locations: classpath:db/migration, classpath:migration-test/v182-fault\n");
        sb.append("Expected Status: FAILED (Expected deterministic fault injection)\n");
        sb.append("Migration Error Summary: ").append(ex != null ? ex.getMessage() : "NO_ERROR").append("\n\n");

        sb.append("--- FORENSIC COLLECTION WARNINGS / ERRORS ---\n");
        if (collectionWarnings.isEmpty()) {
            sb.append("None (All forensic evidence files collected successfully).\n");
        } else {
            sb.append("Forensic collection encountered ").append(collectionWarnings.size()).append(" issue(s):\n");
            for (String w : collectionWarnings) {
                sb.append("  - ").append(w).append("\n");
            }
        }
        sb.append("\n");

        sb.append("--- ARCHIVAL RESPONSIBILITY & REHEARSAL SCOPE NOTICE ---\n");
        sb.append("1. Archival Responsibility: AI-1 is responsible for archiving the complete Maven execution log\n");
        sb.append("   and final Surefire XML test reports during official test execution pipeline.\n");
        sb.append("2. Rehearsal Scope Declaration: This rehearsal demonstrates isolated failure evidence capture\n");
        sb.append("   in a disposable container after V182 completion. It does NOT represent production V182\n");
        sb.append("   partial failure recovery and does NOT claim verification of forward-fix runbooks.\n");
        Files.writeString(targetFile, sb.toString(), StandardCharsets.UTF_8);
    }

    private void dumpCapturedFlywayLogs(Path targetFile, List<ILoggingEvent> events) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("=== FLYWAY EXECUTION LOG (CAPTURED LOGBACK EVENTS) ===\n");
        sb.append("Captured Log Events Count: ").append(events.size()).append("\n");
        sb.append("Logger Name Filter: org.flywaydb\n");
        sb.append("------------------------------------------------------------------------\n");
        if (events.isEmpty()) {
            sb.append("# WARNING: No log events captured for org.flywaydb during migration execution.\n");
        } else {
            for (ILoggingEvent event : events) {
                sb.append(String.format("[%s] %-5s %s - %s\n",
                        java.time.Instant.ofEpochMilli(event.getTimeStamp()),
                        event.getLevel(),
                        event.getLoggerName(),
                        event.getFormattedMessage()));
                IThrowableProxy tp = event.getThrowableProxy();
                if (tp != null) {
                    sb.append("    Exception: ").append(tp.getClassName()).append(": ").append(tp.getMessage()).append("\n");
                }
            }
        }
        sb.append("------------------------------------------------------------------------\n");
        sb.append("Notice: Complete Maven build log and final Surefire XML test reports are archived separately by AI-1.\n");
        Files.writeString(targetFile, sb.toString(), StandardCharsets.UTF_8);
    }

    private void createManifestFile(Path evidenceDir) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String file : REQUIRED_EVIDENCE_FILES) {
            if ("manifest-sha256.txt".equals(file)) continue;
            Path p = evidenceDir.resolve(file);
            if (Files.isRegularFile(p)) {
                String sha = computeFileSha256(p);
                sb.append(sha).append("  ").append(file).append("\n");
            }
        }
        Files.writeString(evidenceDir.resolve("manifest-sha256.txt"), sb.toString(), StandardCharsets.UTF_8);
    }

    private void verifyPersistedEvidence(Path evidenceDir) throws Exception {
        // 1. 必需ファイル集合がすべて実ディスク上に存在し、かつサイズ > 0 であることを検証
        for (String requiredFile : REQUIRED_EVIDENCE_FILES) {
            Path targetFile = evidenceDir.resolve(requiredFile);
            assertTrue(Files.isRegularFile(targetFile), "必需証拠ファイルがディスクに存在すること: " + requiredFile);
            assertTrue(Files.size(targetFile) > 0, "必需証拠ファイルのサイズが0より大きいこと: " + requiredFile);
        }

        // 2. マニフェストに記録されたハッシュと、実ディスク上の各ファイルのSHA-256を相互検証
        Path manifestPath = evidenceDir.resolve("manifest-sha256.txt");
        List<String> lines = Files.readAllLines(manifestPath, StandardCharsets.UTF_8);
        assertEquals(REQUIRED_EVIDENCE_FILES.size() - 1, lines.size(),
                "マニフェストにはマニフェスト自身を除く全必需ファイルの行が存在すること");

        for (String line : lines) {
            String[] parts = line.split("\\s+");
            assertEquals(2, parts.length, "マニフェスト行形式 (sha256 filename)");
            String expectedHash = parts[0];
            String filename = parts[1];
            assertTrue(REQUIRED_EVIDENCE_FILES.contains(filename), "必需ファイル集合に含まれること: " + filename);
            Path targetFile = evidenceDir.resolve(filename);
            String actualHash = computeFileSha256(targetFile);
            assertEquals(expectedHash, actualHash, "証拠ファイルのSHA-256検証一致: " + filename);
        }
    }

    // =========================================================================
    // 合成テストフィクスチャ生成 (第4節)
    // =========================================================================

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

        // t_peppol_participant (合法かつ区別可能な所有者で一意制約を遵守)
        long pepCust = insertPeppol(conn, "CUSTOMER", cust1, "pep-p1", "iso6523", "0147:c1", "VERIFIED", new java.sql.Timestamp(System.currentTimeMillis()), 0);
        long pepOther = insertPeppol(conn, "USER", 999L, "pep-p1", "iso6523", "0147:u999", "PENDING", null, 0);
        long pepDup1 = insertPeppol(conn, "CUSTOMER", cust2, "pep-dup", "iso6523", "0147:dup", "VERIFIED", new java.sql.Timestamp(System.currentTimeMillis()), 0);
        long pepDup2 = insertPeppol(conn, "CUSTOMER", cust3, "pep-dup", "iso6523", "0147:dup", "VERIFIED", new java.sql.Timestamp(System.currentTimeMillis()), 0);

        // t_external_account_reference (account_typeを除去し、system_idを付与)
        long accEng = insertExternalAccount(conn, 1L, "ENGINEER", eng1, "idem-eng-1");
        long accUser = insertExternalAccount(conn, 1L, "USER", 1L, "idem-user-1");

        return new SyntheticFixture(
                dinvSingle, dinvMultiSame, dinvConflict, dinvUnresolved,
                evtSingle, evtUnresolved,
                pepCust, pepOther, pepDup1, pepDup2,
                accEng, accUser);
    }

    private long insertCustomer(Connection conn, String companyName, String tenantId, Long legalEntityId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO m_customer (company_name, tenant_id, legal_entity_id, deleted_flag) VALUES (?, ?, ?, 0)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, companyName);
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
                              String participantId, String status, java.sql.Timestamp verifiedAt, int deletedFlag) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO t_peppol_participant (owner_type, owner_id, provider, scheme_id, participant_id, "
                        + "status, verified_at, deleted_flag) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, ownerType);
            ps.setLong(2, ownerId);
            ps.setString(3, provider);
            ps.setString(4, schemeId);
            ps.setString(5, participantId);
            ps.setString(6, status);
            ps.setTimestamp(7, verifiedAt);
            ps.setInt(8, deletedFlag);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private long insertExternalAccount(Connection conn, long systemId, String assigneeType, long assigneeId, String idempotencyKey) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO t_external_account_reference (system_id, account_identifier, assignee_type, assignee_id, "
                        + "idempotency_key, status, deleted_flag) VALUES (?, 'ext-user-1', ?, ?, ?, 'ACTIVE', 0)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, systemId);
            ps.setString(2, assigneeType);
            ps.setLong(3, assigneeId);
            ps.setString(4, idempotencyKey);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }
}
