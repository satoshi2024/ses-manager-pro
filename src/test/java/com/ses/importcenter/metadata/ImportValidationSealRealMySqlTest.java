package com.ses.importcenter.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.service.DocumentService;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F1b-3B: 実MySQL上でのSeal CAS、リソースロック順序、並行競合直列化（requester->blocker待ち証拠付き）、
 * および期限・保持境界の統合試験。
 */
@Tag("mysql")
@Testcontainers
class ImportValidationSealRealMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("nf06_seal")
            .withUsername("root").withPassword("ses");

    private static final String TENANT = "real-mysql-seal";

    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private ImportValidationLifecycleService service;
    private ImportValidationAppendService appendService;
    private ImportMetadataAuthorizationService authorization;
    private ImportMetadataService metadataService;
    private TrackingTransactionManager trackingManager;
    private final ImportAuthorizedContext context = new ImportAuthorizedContext(TENANT, 41L, 77L);

    private record Fixture(
            long jobId,
            long runId,
            ImportValidationLifecycleService.RunLease lease,
            ImportValidationAppendService.SealCapability capability
    ) {}

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName(MYSQL.getDriverClassName());
        ds.setUrl(MYSQL.getJdbcUrl());
        ds.setUsername(MYSQL.getUsername());
        ds.setPassword(MYSQL.getPassword());
        this.dataSource = ds;
        this.jdbc = new JdbcTemplate(ds);

        createSchema(jdbc);
        cleanTables();

        authorization = mock(ImportMetadataAuthorizationService.class);
        when(authorization.requireCurrentAuthorization()).thenReturn(context);
        when(authorization.requireCurrentAuthorizationForWrite(context)).thenReturn(context);

        trackingManager = new TrackingTransactionManager(ds);
        service = new ImportValidationLifecycleService(jdbc, authorization, mock(DocumentService.class), trackingManager);
        metadataService = mock(ImportMetadataService.class);
        appendService = new ImportValidationAppendService(jdbc, metadataService, service, new ObjectMapper());
    }

    private Fixture setupFixture(String csvContent) {
        long targetJobId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        if (targetJobId < 1) targetJobId = 100L;

        byte[] csvBytes = csvContent.getBytes(StandardCharsets.UTF_8);
        byte[] sourceSha = digest().digest(csvBytes);

        // 正準な mapping JSON を ImportMetadataService.canonicalMapping から生成し、キー順序（TreeMap）とハッシュを確定
        Map<String, String> mapping = Map.of("externalKey", "externalKey", "companyName", "companyName");
        byte[] mappingBytes = ImportMetadataService.canonicalMapping(mapping);
        byte[] mappingHash = hash(mappingBytes);

        long sourceId = 2L, documentId = 3L, versionId = 4L, mappingId = 5L;

        jdbc.update("DELETE FROM t_import_validation_row WHERE tenant_id=? AND job_id=?", TENANT, targetJobId);
        jdbc.update("DELETE FROM t_import_validation_run WHERE tenant_id=? AND job_id=?", TENANT, targetJobId);
        jdbc.update("DELETE FROM t_import_job WHERE tenant_id=? AND id=?", TENANT, targetJobId);

        jdbc.update("INSERT INTO t_import_source_system VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE status='ACTIVE'",
                TENANT, sourceId, 8L, "source", "ACTIVE");
        jdbc.update("INSERT INTO t_document VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE legal_hold_flag=0, retention_until=NULL",
                TENANT, documentId, "77", 0, null, 0);
        jdbc.update("INSERT INTO t_document_version VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE sha256=VALUES(sha256)",
                TENANT, documentId, versionId, 1, HexFormat.of().formatHex(sourceSha), "CLEAN", 0);
        jdbc.update("INSERT INTO t_document_link(tenant_id,document_id,target_type,target_id,deleted_flag) VALUES(?,?,?,?,0)",
                TENANT, documentId, "IMPORT_JOB", targetJobId);
        jdbc.update("INSERT INTO t_import_mapping VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE canonical_bytes=VALUES(canonical_bytes), mapping_sha256=VALUES(mapping_sha256)",
                TENANT, mappingId, sourceId, "CUSTOMER", "ses-import-v1", 1, mappingHash, mappingBytes);
        jdbc.update("""
                INSERT INTO t_import_job(tenant_id,id,source_system_id,source_version,entity_type,schema_version,
                  source_encoding,source_sha256,legal_entity_id,document_id,document_version_id,document_version_no,
                  document_sha256,mapping_id,mapping_version,mapping_sha256,state,version_no,lease_generation)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'MAPPED',1,0)
                """, TENANT, targetJobId, sourceId, 8L, "CUSTOMER", "ses-import-v1", "UTF8", sourceSha,
                "77", documentId, versionId, 1, sourceSha, mappingId, 1, mappingHash);

        when(metadataService.openSource(targetJobId)).thenAnswer(inv ->
                new ImportSourceContent(new ByteArrayInputStream(csvBytes), "customer.csv",
                        ImportEncoding.UTF8, ImportEntity.CUSTOMER, HexFormat.of().formatHex(sourceSha)));

        var startOp = service.beginStart(targetJobId);
        var startLease = service.start(startOp);
        var completion = appendService.appendRemainingWithSealCapability(startLease);
        return new Fixture(targetJobId, startLease.runId(), completion.lease(), completion.capability());
    }

    @Test
    void posSeal01_retentionUntilNullAllowsSealToComplete() {
        // POS-SEAL-01: retention_until IS NULL の無期限保持ドキュメントでの正常 seal
        String csv = "externalKey,companyName\ncust-1,株式会社A\ncust-2,株式会社B\n";
        var fixture = setupFixture(csv);

        jdbc.update("UPDATE t_document SET retention_until = NULL WHERE tenant_id=? AND id=3", TENANT);

        var sealedLease = service.seal(fixture.capability);
        assertNotNull(sealedLease);

        String state = jdbc.queryForObject(
                "SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?",
                String.class, TENANT, fixture.jobId, fixture.runId);
        assertEquals("SEALED", state);
    }

    @Test
    void negSeal16_concurrentAppendCommitsBeforeSealCausesDiscrepancy() throws Exception {
        // NEG-SEAL-16: seal 待機中に並行 append が先にコミット -> 行セット不一致により 409
        String csv = "externalKey,companyName\ncust-1,株式会社A\n";
        var fixture = setupFixture(csv);

        trackingManager.reset();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch blockerHolding = new CountDownLatch(1);
        Connection blockerConn = null;

        try {
            blockerConn = dataSource.getConnection();
            blockerConn.setAutoCommit(false);
            long blockerPid;
            try (Statement s = blockerConn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT CONNECTION_ID()")) {
                assertTrue(rs.next(), "Blocker CONNECTION_ID() 取得成功");
                blockerPid = rs.getLong(1);
            }

            try (PreparedStatement ps = blockerConn.prepareStatement("SELECT * FROM t_import_job WHERE tenant_id=? AND id=? FOR UPDATE")) {
                ps.setString(1, TENANT);
                ps.setLong(2, fixture.jobId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "Blocker 行ロック取得成功");
                }
            }
            blockerHolding.countDown();

            Future<?> sealFuture = pool.submit(() -> service.seal(fixture.capability));

            awaitLockWait(trackingManager, blockerPid, sealFuture);

            // blocker の同一トランザクション内で追加行をコミット（行セット変更を先行コミット）
            try (Statement stmt = blockerConn.createStatement()) {
                stmt.executeUpdate("INSERT INTO t_import_validation_row(tenant_id,job_id,validation_run_id,source_row_number," +
                        "source_row_sha256,canonical_fingerprint,disposition,finding_codes,row_commit_sha256) " +
                        "VALUES('" + TENANT + "'," + fixture.jobId + "," + fixture.runId + ",99," +
                        "X'0101010101010101010101010101010101010101010101010101010101010101'," +
                        "X'0202020202020202020202020202020202020202020202020202020202020202'," +
                        "'ACCEPTED',X'00'," +
                        "X'0303030303030303030303030303030303030303030303030303030303030303')");
            }
            blockerConn.commit();

            ExecutionException ex = assertThrows(ExecutionException.class, () -> sealFuture.get(10, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof BusinessException, "元の BusinessException が保持されること");
            assertEquals(409, ((BusinessException) ex.getCause()).getCode());
        } finally {
            if (blockerConn != null) {
                try { blockerConn.rollback(); } catch (Exception ignored) {}
                try { blockerConn.close(); } catch (Exception ignored) {}
            }
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "Worker pool 有界回収完了");
        }
    }

    @Test
    void negSeal17_delayedAppendAfterSealIsRejectedBecauseStateNotBuilding_stateRejection() {
        // NEG-SEAL-17（状態拒絶検証）: seal 完了後に遅延 append が試行 -> state <> 'BUILDING' により append 拒否
        String csv = "externalKey,companyName\ncust-1,株式会社A\n";
        var fixture = setupFixture(csv);

        service.seal(fixture.capability);

        var row = new ImportValidationAppendService.RowMetadata(
                99L, hash("s99"), hash("c99"), "ACCEPTED", new byte[0], hash("commit99"));
        var chunk = createChunk(fixture.lease, List.of(row));

        var ex = assertThrows(BusinessException.class, () -> service.appendChunk(fixture.lease, chunk));
        assertEquals(409, ex.getCode());
    }

    @Test
    void negSeal18_concurrentRenewCommitsBeforeSealCausesVersionMismatch() throws Exception {
        // NEG-SEAL-18: seal 待機中に並行 renew が先にコミット -> job version 不一致により 409
        String csv = "externalKey,companyName\ncust-1,株式会社A\n";
        var fixture = setupFixture(csv);

        trackingManager.reset();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch blockerHolding = new CountDownLatch(1);
        Connection blockerConn = null;

        try {
            blockerConn = dataSource.getConnection();
            blockerConn.setAutoCommit(false);
            long blockerPid;
            try (Statement s = blockerConn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT CONNECTION_ID()")) {
                assertTrue(rs.next(), "Blocker CONNECTION_ID() 取得成功");
                blockerPid = rs.getLong(1);
            }

            try (PreparedStatement ps = blockerConn.prepareStatement("SELECT * FROM t_import_job WHERE tenant_id=? AND id=? FOR UPDATE")) {
                ps.setString(1, TENANT);
                ps.setLong(2, fixture.jobId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "Blocker 行ロック取得成功");
                }
            }
            blockerHolding.countDown();

            Future<?> sealFuture = pool.submit(() -> service.seal(fixture.capability));

            awaitLockWait(trackingManager, blockerPid, sealFuture);

            // blocker 側で version_no をインクリメントしてコミット
            try (Statement stmt = blockerConn.createStatement()) {
                stmt.executeUpdate("UPDATE t_import_job SET version_no = version_no + 1 WHERE tenant_id='" + TENANT + "' AND id=" + fixture.jobId);
            }
            blockerConn.commit();

            ExecutionException ex = assertThrows(ExecutionException.class, () -> sealFuture.get(10, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof BusinessException, "元の BusinessException が保持されること");
            assertEquals(409, ((BusinessException) ex.getCause()).getCode());
        } finally {
            if (blockerConn != null) {
                try { blockerConn.rollback(); } catch (Exception ignored) {}
                try { blockerConn.close(); } catch (Exception ignored) {}
            }
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "Worker pool 有界回収完了");
        }
    }

    @Test
    void negSeal19_concurrentTakeoverCommitsBeforeSealCausesGenerationMismatch() throws Exception {
        // NEG-SEAL-19: seal 待機中に並行 takeover が先にコミット -> generation 不一致により 409
        String csv = "externalKey,companyName\ncust-1,株式会社A\n";
        var fixture = setupFixture(csv);

        trackingManager.reset();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch blockerHolding = new CountDownLatch(1);
        Connection blockerConn = null;

        try {
            blockerConn = dataSource.getConnection();
            blockerConn.setAutoCommit(false);
            long blockerPid;
            try (Statement s = blockerConn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT CONNECTION_ID()")) {
                assertTrue(rs.next(), "Blocker CONNECTION_ID() 取得成功");
                blockerPid = rs.getLong(1);
            }

            try (PreparedStatement ps = blockerConn.prepareStatement("SELECT * FROM t_import_job WHERE tenant_id=? AND id=? FOR UPDATE")) {
                ps.setString(1, TENANT);
                ps.setLong(2, fixture.jobId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "Blocker 行ロック取得成功");
                }
            }
            blockerHolding.countDown();

            Future<?> sealFuture = pool.submit(() -> service.seal(fixture.capability));

            awaitLockWait(trackingManager, blockerPid, sealFuture);

            // blocker 側で lease_generation をインクリメントしてコミット
            try (Statement stmt = blockerConn.createStatement()) {
                stmt.executeUpdate("UPDATE t_import_job SET lease_generation = lease_generation + 1 WHERE tenant_id='" + TENANT + "' AND id=" + fixture.jobId);
            }
            blockerConn.commit();

            ExecutionException ex = assertThrows(ExecutionException.class, () -> sealFuture.get(10, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof BusinessException, "元の BusinessException が保持されること");
            assertEquals(409, ((BusinessException) ex.getCause()).getCode());
        } finally {
            if (blockerConn != null) {
                try { blockerConn.rollback(); } catch (Exception ignored) {}
                try { blockerConn.close(); } catch (Exception ignored) {}
            }
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "Worker pool 有界回収完了");
        }
    }

    @Test
    void negSeal20_concurrentAbortCommitsBeforeSealCausesStateConflict() throws Exception {
        // NEG-SEAL-20: seal 待機中に並行 abort が先にコミット -> run ABORTED 検知により 409
        String csv = "externalKey,companyName\ncust-1,株式会社A\n";
        var fixture = setupFixture(csv);

        trackingManager.reset();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch blockerHolding = new CountDownLatch(1);
        Connection blockerConn = null;

        try {
            blockerConn = dataSource.getConnection();
            blockerConn.setAutoCommit(false);
            long blockerPid;
            try (Statement s = blockerConn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT CONNECTION_ID()")) {
                assertTrue(rs.next(), "Blocker CONNECTION_ID() 取得成功");
                blockerPid = rs.getLong(1);
            }

            try (PreparedStatement ps = blockerConn.prepareStatement("SELECT * FROM t_import_job WHERE tenant_id=? AND id=? FOR UPDATE")) {
                ps.setString(1, TENANT);
                ps.setLong(2, fixture.jobId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "Blocker 行ロック取得成功");
                }
            }
            blockerHolding.countDown();

            Future<?> sealFuture = pool.submit(() -> service.seal(fixture.capability));

            awaitLockWait(trackingManager, blockerPid, sealFuture);

            // blocker 側で run を ABORTED に更新してコミット
            try (Statement stmt = blockerConn.createStatement()) {
                stmt.executeUpdate("UPDATE t_import_validation_run SET state = 'ABORTED' WHERE tenant_id='" + TENANT + "' AND job_id=" + fixture.jobId + " AND id=" + fixture.runId);
            }
            blockerConn.commit();

            ExecutionException ex = assertThrows(ExecutionException.class, () -> sealFuture.get(10, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof BusinessException, "元の BusinessException が保持されること");
            assertEquals(409, ((BusinessException) ex.getCause()).getCode());
        } finally {
            if (blockerConn != null) {
                try { blockerConn.rollback(); } catch (Exception ignored) {}
                try { blockerConn.close(); } catch (Exception ignored) {}
            }
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "Worker pool 有界回収完了");
        }
    }

    @Test
    void negSeal21_expiryDuringLockWaitCausesRejectionOnFreshDbNow() throws Exception {
        // NEG-SEAL-21: ロック待機中に実時間が経過しリース有効期限を跨ぐ -> fresh DB現在時刻判定で 409 拒絶。
        // DB と capability の expiry を同一の短期限（現在時刻 + 2000ms）に設定し、待機開始時は未期限であることを確認。
        // awaitLockWait 成功直後に独立接続から DB 時刻を取得し、observedWaitTime < shortExpiry（待機突入時点で未期限かつ有効）を厳格断言。
        // その後 2500ms 待機して同一期限を確実に跨ぎ、blocker 解放前に DB 時刻 >= shortExpiry を確認。
        String csv = "externalKey,companyName\ncust-1,株式会社A\n";
        var fixture = setupFixture(csv);

        Timestamp dbNow = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class);
        assertNotNull(dbNow);
        Timestamp shortExpiry = new Timestamp(dbNow.getTime() + 2000);

        // DB の lease_expires_at を shortExpiry に設定
        jdbc.update("UPDATE t_import_job SET lease_expires_at = ? WHERE tenant_id=? AND id=?",
                shortExpiry, TENANT, fixture.jobId);

        // capability の lease_expires_at も同一の shortExpiry に設定（不一致による拒絶ではなく同一期限での実時間跨ぎを保証）
        var shortExpiryCapability = reflectedCapabilityWithExpiry(fixture.capability, shortExpiry);
        assertEquals(shortExpiry, shortExpiryCapability.expiresAt(), "DB と capability の expiry が完全一致していること");
        assertTrue(shortExpiry.after(dbNow), "テスト開始時点では未期限であることの証明");

        trackingManager.reset();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch blockerHolding = new CountDownLatch(1);
        Connection blockerConn = null;

        try {
            blockerConn = dataSource.getConnection();
            blockerConn.setAutoCommit(false);
            long blockerPid;
            try (Statement s = blockerConn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT CONNECTION_ID()")) {
                assertTrue(rs.next(), "Blocker CONNECTION_ID() 取得成功");
                blockerPid = rs.getLong(1);
            }

            try (PreparedStatement ps = blockerConn.prepareStatement("SELECT * FROM t_import_job WHERE tenant_id=? AND id=? FOR UPDATE")) {
                ps.setString(1, TENANT);
                ps.setLong(2, fixture.jobId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "Blocker 行ロック取得成功");
                }
            }
            blockerHolding.countDown();

            Future<?> sealFuture = pool.submit(() -> service.seal(shortExpiryCapability));

            awaitLockWait(trackingManager, blockerPid, sealFuture);

            // awaitLockWait 成功直後、独立観測接続から DB 現在時刻を読み取り、待機中時点で未期限（尚且つ有効）であることを厳格断言
            Timestamp observedWaitTime = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class);
            assertNotNull(observedWaitTime);
            assertTrue(observedWaitTime.before(shortExpiry),
                    "ロック待機突入が確認された時点（observedWaitTime=" + observedWaitTime + "）で、リース期限（shortExpiry=" + shortExpiry + "）内であり有効であることの厳格証明");

            // 同一期限（2000ms）を確実に実時間で跨ぐよう 2500ms 待機
            Thread.sleep(2500);

            // blocker 解放前に DB 現在時刻が shortExpiry 以上（期限超過）となったことを確認
            Timestamp beforeReleaseTime = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class);
            assertNotNull(beforeReleaseTime);
            assertFalse(beforeReleaseTime.before(shortExpiry),
                    "ブロック解放前のDB時刻（beforeReleaseTime=" + beforeReleaseTime + "）がリース期限（shortExpiry=" + shortExpiry + "）以上であり、実時間で期限を跨いだことの証明");

            blockerConn.commit();

            ExecutionException ex = assertThrows(ExecutionException.class, () -> sealFuture.get(10, TimeUnit.SECONDS));
            assertTrue(ex.getCause() instanceof BusinessException, "元の BusinessException が保持されること");
            assertEquals(409, ((BusinessException) ex.getCause()).getCode(), "fresh DB timeにより待機中期限切れが検知されること");
        } finally {
            if (blockerConn != null) {
                try { blockerConn.rollback(); } catch (Exception ignored) {}
                try { blockerConn.close(); } catch (Exception ignored) {}
            }
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "Worker pool 有界回収完了");
        }
    }

    @Test
    void negSeal22_permissionRevokedDuringLockWaitCauses403() {
        // NEG-SEAL-22（状態拒絶検証）: 認可ゲート待機中にアクター権限が revoke される -> 403 Forbidden
        String csv = "externalKey,companyName\ncust-1,株式会社A\n";
        var fixture = setupFixture(csv);

        when(authorization.requireCurrentAuthorizationForWrite(context))
                .thenThrow(BusinessException.of(403, "error.forbidden"));

        var ex = assertThrows(BusinessException.class, () -> service.seal(fixture.capability));
        assertEquals(403, ex.getCode());
    }

    @Test
    void negSeal23_sourceDisabledDuringLockWaitCausesRejection_v184Compliant() {
        // NEG-SEAL-23（状態拒絶検証）: source system が DISABLED（V184 CHECK制約に準拠）化 -> 409
        String csv = "externalKey,companyName\ncust-1,株式会社A\n";
        var fixture = setupFixture(csv);

        jdbc.update("UPDATE t_import_source_system SET status = 'DISABLED' WHERE tenant_id=? AND id=2", TENANT);

        var ex = assertThrows(BusinessException.class, () -> service.seal(fixture.capability));
        assertEquals(409, ex.getCode());
    }

    @Test
    void negSeal24_retentionExpiredDocumentCausesRejection_stateRejection() {
        // NEG-SEAL-24（状態拒絶検証）: retention_until < CURRENT_DATE（保管期限切れドキュメント）-> 409
        String csv = "externalKey,companyName\ncust-1,株式会社A\n";
        var fixture = setupFixture(csv);

        LocalDate yesterday = LocalDate.now().minusDays(1);
        jdbc.update("UPDATE t_document SET retention_until = ? WHERE tenant_id=? AND id=3", yesterday, TENANT);

        var ex = assertThrows(BusinessException.class, () -> service.seal(fixture.capability));
        assertEquals(409, ex.getCode());
    }

    @Test
    void negSeal25_legalHoldDuringLockWaitCausesRejection_stateRejection() {
        // NEG-SEAL-25（状態拒絶検証）: document に legal_hold が設定 -> 409
        String csv = "externalKey,companyName\ncust-1,株式会社A\n";
        var fixture = setupFixture(csv);

        jdbc.update("UPDATE t_document SET legal_hold_flag = 1 WHERE tenant_id=? AND id=3", TENANT);

        var ex = assertThrows(BusinessException.class, () -> service.seal(fixture.capability));
        assertEquals(409, ex.getCode());
    }

    static final class TrackingTransactionManager extends DataSourceTransactionManager {
        private final AtomicReference<Long> lastConnectionId = new AtomicReference<>();

        TrackingTransactionManager(DataSource ds) {
            super(ds);
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            super.doBegin(transaction, definition);
            ConnectionHolder holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(obtainDataSource());
            if (holder != null && holder.getConnection() != null) {
                try (Statement s = holder.getConnection().createStatement();
                     ResultSet rs = s.executeQuery("SELECT CONNECTION_ID()")) {
                    if (rs.next()) {
                        lastConnectionId.set(rs.getLong(1));
                    } else {
                        throw new IllegalStateException("Failed to read CONNECTION_ID() from transaction connection");
                    }
                } catch (SQLException e) {
                    throw new RuntimeException("Failed to capture transaction connection ID", e);
                }
            }
        }

        Long getLastConnectionId() {
            return lastConnectionId.get();
        }

        void reset() {
            lastConnectionId.set(null);
        }
    }

    private void awaitLockWait(TrackingTransactionManager trackingManager, long blockerPid, Future<?> workerFuture) throws Exception {
        long deadline = System.currentTimeMillis() + 8000;
        Long requesterPid = null;
        Exception lastQueryEx = null;
        while (System.currentTimeMillis() < deadline) {
            if (workerFuture.isDone()) {
                fail("Worker thread exited prematurely before entering lock wait");
            }
            if (requesterPid == null) {
                requesterPid = trackingManager.getLastConnectionId();
            }
            if (requesterPid != null) {
                try {
                    if (isLockWaiting(requesterPid, blockerPid)) {
                        return; // 双端（requester -> blocker）の実際の行ロック待機関係が確認された
                    }
                } catch (Exception e) {
                    lastQueryEx = e;
                }
            }
            Thread.sleep(30);
        }
        if (requesterPid == null) {
            fail("Timed out waiting to capture requester connection ID" + (lastQueryEx != null ? ", lastEx=" + lastQueryEx : ""));
        } else {
            fail("Timed out waiting for requesterPid=" + requesterPid + " to be blocked by blockerPid=" + blockerPid +
                    (lastQueryEx != null ? ", lastEx=" + lastQueryEx : ""));
        }
    }

    private boolean isLockWaiting(long requesterPid, long blockerPid) throws Exception {
        try {
            Integer count = jdbc.queryForObject(
                    """
                    SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                    JOIN performance_schema.threads r ON w.REQUESTING_THREAD_ID = r.THREAD_ID
                    JOIN performance_schema.threads b ON w.BLOCKING_THREAD_ID = b.THREAD_ID
                    WHERE r.PROCESSLIST_ID = ? AND b.PROCESSLIST_ID = ?
                    """,
                    Integer.class, requesterPid, blockerPid);
            if (count != null && count > 0) return true;
        } catch (Exception e1) {
            // 予備クエリ（sys.innodb_lock_waits）も必ず requester と blocker の双端をバインドする
            // （※単端 requester LOCK WAIT のみの降格クエリは排除し、証拠取得不可時は例外を伝播）
            try {
                Integer count = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM sys.innodb_lock_waits WHERE waiting_pid=? AND blocking_pid=?",
                        Integer.class, requesterPid, blockerPid);
                if (count != null && count > 0) return true;
            } catch (Exception e2) {
                e1.addSuppressed(e2);
                throw e1;
            }
        }
        return false;
    }

    private ImportValidationAppendService.SealCapability reflectedCapabilityWithExpiry(
            ImportValidationAppendService.SealCapability base, Timestamp newExpiry) {
        var newLease = createLeaseWithExpiry(base.lease(), newExpiry);
        return reflectedCapability(newLease, base.counters(), base.sourceSha256(), base.rowSetSha256());
    }

    private ImportValidationAppendService.SealCapability reflectedCapability(
            ImportValidationLifecycleService.RunLease targetLease,
            com.ses.importcenter.foundation.ValidationCounterAccumulator.Snapshot counters,
            String sourceSha256, String rowSetSha256) {
        try {
            var ctor = ImportValidationAppendService.SealCapability.class.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            return (ImportValidationAppendService.SealCapability) ctor.newInstance(
                    targetLease, service.appendSnapshot(targetLease), sourceSha256, rowSetSha256, counters);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private ImportValidationLifecycleService.RunLease createLeaseWithExpiry(
            ImportValidationLifecycleService.RunLease base, Timestamp newExpiry) {
        try {
            var leaseCtor = base.getClass().getDeclaredConstructors()[0];
            leaseCtor.setAccessible(true);
            return (ImportValidationLifecycleService.RunLease) leaseCtor.newInstance(
                    getField(base, "instanceId"), getField(base, "input"), base.runId(), base.workerId(),
                    base.generation(), base.jobVersion(), newExpiry);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static Object getField(Object target, String fieldName) throws Exception {
        var f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.get(target);
    }

    private ImportValidationAppendService.ValidatedChunk createChunk(
            ImportValidationLifecycleService.RunLease chunkLease,
            List<ImportValidationAppendService.RowMetadata> rows) {
        try {
            var ctor = ImportValidationAppendService.ValidatedChunk.class.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            return (ImportValidationAppendService.ValidatedChunk) ctor.newInstance(
                    chunkLease, service.appendSnapshot(chunkLease), rows);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void cleanTables() {
        jdbc.update("DELETE FROM t_import_validation_row WHERE tenant_id=?", TENANT);
        jdbc.update("DELETE FROM t_import_validation_run WHERE tenant_id=?", TENANT);
        jdbc.update("DELETE FROM t_import_job WHERE tenant_id=?", TENANT);
        jdbc.update("DELETE FROM t_import_mapping WHERE tenant_id=?", TENANT);
        jdbc.update("DELETE FROM t_document_link WHERE tenant_id=?", TENANT);
        jdbc.update("DELETE FROM t_document_version WHERE tenant_id=?", TENANT);
        jdbc.update("DELETE FROM t_document WHERE tenant_id=?", TENANT);
        jdbc.update("DELETE FROM t_import_source_system WHERE tenant_id=?", TENANT);
    }

    private static void createSchema(JdbcTemplate j) {
        j.execute("CREATE TABLE IF NOT EXISTS t_import_source_system(tenant_id VARCHAR(100),id BIGINT,version_no BIGINT,source_code VARCHAR(64),status VARCHAR(16),PRIMARY KEY(tenant_id,id),CONSTRAINT chk_import_source_status CHECK (status IN ('ACTIVE', 'DISABLED'))) ENGINE=InnoDB");
        j.execute("CREATE TABLE IF NOT EXISTS t_document(tenant_id VARCHAR(100),id BIGINT,legal_entity_id VARCHAR(100),legal_hold_flag INT,retention_until DATE,deleted_flag INT,PRIMARY KEY(tenant_id,id)) ENGINE=InnoDB");
        j.execute("CREATE TABLE IF NOT EXISTS t_document_version(tenant_id VARCHAR(100),document_id BIGINT,id BIGINT,version_no INT,sha256 VARCHAR(64),scan_status VARCHAR(16),deleted_flag INT,PRIMARY KEY(tenant_id,id)) ENGINE=InnoDB");
        j.execute("CREATE TABLE IF NOT EXISTS t_document_link(tenant_id VARCHAR(100),id BIGINT AUTO_INCREMENT PRIMARY KEY,document_id BIGINT,target_type VARCHAR(32),target_id BIGINT,deleted_flag INT) ENGINE=InnoDB");
        j.execute("CREATE TABLE IF NOT EXISTS t_import_mapping(tenant_id VARCHAR(100),id BIGINT,source_system_id BIGINT,entity_type VARCHAR(24),schema_version VARCHAR(40),mapping_version INT,mapping_sha256 BINARY(32),canonical_bytes BLOB,PRIMARY KEY(tenant_id,id)) ENGINE=InnoDB");
        j.execute("CREATE TABLE IF NOT EXISTS t_import_job(tenant_id VARCHAR(100),id BIGINT,source_system_id BIGINT,source_version BIGINT,entity_type VARCHAR(24),schema_version VARCHAR(40),source_encoding VARCHAR(16),source_sha256 BINARY(32),legal_entity_id VARCHAR(100),document_id BIGINT,document_version_id BIGINT,document_version_no INT,document_sha256 BINARY(32),mapping_id BIGINT,mapping_version INT,mapping_sha256 BINARY(32),state VARCHAR(16),version_no BIGINT,lease_owner VARCHAR(128),lease_generation BIGINT,lease_expires_at DATETIME(6),active_validation_run_id BIGINT,current_validation_run_id BIGINT,updated_at DATETIME(6),PRIMARY KEY(id),UNIQUE(tenant_id,id)) ENGINE=InnoDB");
        j.execute("""
                CREATE TABLE IF NOT EXISTS t_import_validation_run(
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  tenant_id VARCHAR(100) NOT NULL,
                  job_id BIGINT NOT NULL,
                  run_no INT NOT NULL,
                  state VARCHAR(16) NOT NULL DEFAULT 'BUILDING',
                  created_lease_generation BIGINT NOT NULL,
                  last_resumed_lease_generation BIGINT NOT NULL,
                  active_slot INT NULL,
                  published_slot INT NULL,
                  actor_user_id BIGINT NOT NULL,
                  source_system_id BIGINT NOT NULL,
                  source_version BIGINT NOT NULL,
                  source_code_snapshot VARCHAR(64) NOT NULL,
                  entity_type VARCHAR(24) NOT NULL,
                  schema_version VARCHAR(32) NOT NULL,
                  source_encoding VARCHAR(16) NOT NULL,
                  source_sha256 BINARY(32) NOT NULL,
                  document_id BIGINT NOT NULL,
                  document_version_id BIGINT NOT NULL,
                  document_version_no INT NOT NULL,
                  document_sha256 BINARY(32) NOT NULL,
                  mapping_id BIGINT NOT NULL,
                  mapping_version INT NOT NULL,
                  mapping_sha256 BINARY(32) NOT NULL,
                  validation_fingerprint BINARY(32) NOT NULL,
                  source_row_count BIGINT NULL,
                  physical_row_count BIGINT NULL,
                  empty_row_count BIGINT NULL,
                  accepted_row_count BIGINT NULL,
                  rejected_row_count BIGINT NULL,
                  finding_summary_bytes VARBINARY(8192) NULL,
                  finding_summary_sha256 BINARY(32) NULL,
                  expected_unit_price_accepted_known_count BIGINT NULL,
                  expected_unit_price_accepted_sum_yen DECIMAL(38,0) NULL,
                  expected_unit_price_rejected_known_count BIGINT NULL,
                  expected_unit_price_rejected_sum_yen DECIMAL(38,0) NULL,
                  expected_unit_price_excluded_count BIGINT NULL,
                  unit_price_min_accepted_known_count BIGINT NULL,
                  unit_price_min_accepted_sum_yen DECIMAL(38,0) NULL,
                  unit_price_min_rejected_known_count BIGINT NULL,
                  unit_price_min_rejected_sum_yen DECIMAL(38,0) NULL,
                  unit_price_min_excluded_count BIGINT NULL,
                  unit_price_max_accepted_known_count BIGINT NULL,
                  unit_price_max_accepted_sum_yen DECIMAL(38,0) NULL,
                  unit_price_max_rejected_known_count BIGINT NULL,
                  unit_price_max_rejected_sum_yen DECIMAL(38,0) NULL,
                  unit_price_max_excluded_count BIGINT NULL,
                  selling_price_accepted_known_count BIGINT NULL,
                  selling_price_accepted_sum_yen DECIMAL(38,0) NULL,
                  selling_price_rejected_known_count BIGINT NULL,
                  selling_price_rejected_sum_yen DECIMAL(38,0) NULL,
                  selling_price_excluded_count BIGINT NULL,
                  cost_price_accepted_known_count BIGINT NULL,
                  cost_price_accepted_sum_yen DECIMAL(38,0) NULL,
                  cost_price_rejected_known_count BIGINT NULL,
                  cost_price_rejected_sum_yen DECIMAL(38,0) NULL,
                  cost_price_excluded_count BIGINT NULL,
                  amount_excluded_reason_counts VARBINARY(8192) NULL,
                  amount_excluded_reason_sha256 BINARY(32) NULL,
                  row_set_sha256 BINARY(32) NULL,
                  summary_sha256 BINARY(32) NULL,
                  started_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
                  sealed_at TIMESTAMP(6) NULL,
                  completed_at TIMESTAMP(6) NULL,
                  UNIQUE KEY uq_import_run_tenant_id (tenant_id, id),
                  UNIQUE KEY uq_import_run_job_id (tenant_id, job_id, id),
                  UNIQUE KEY uq_import_run_active_slot (tenant_id, job_id, active_slot),
                  UNIQUE KEY uq_import_run_published_slot (tenant_id, job_id, published_slot)
                ) ENGINE=InnoDB
                """);
        j.execute("""
                CREATE TABLE IF NOT EXISTS t_import_validation_row(
                  tenant_id VARCHAR(100) NOT NULL,
                  job_id BIGINT NOT NULL,
                  validation_run_id BIGINT NOT NULL,
                  source_row_number BIGINT NOT NULL,
                  source_row_sha256 BINARY(32) NOT NULL,
                  canonical_fingerprint BINARY(32) NOT NULL,
                  disposition VARCHAR(16) NOT NULL,
                  finding_codes VARBINARY(4096) NOT NULL,
                  row_commit_sha256 BINARY(32) NOT NULL,
                  PRIMARY KEY (tenant_id, validation_run_id, source_row_number)
                ) ENGINE=InnoDB
                """);
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private static byte[] hash(String value) { return hash(value.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] hash(byte[] value) {
        return digest().digest(value);
    }
}
