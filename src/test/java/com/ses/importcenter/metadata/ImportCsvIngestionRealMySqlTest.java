package com.ses.importcenter.metadata;

import com.ses.common.enums.FileKind;
import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.entity.SysUser;
import com.ses.entity.DocumentVersion;
import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.importcenter.foundation.SesImportV1Schema;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.FileScanResult;
import com.ses.service.security.FileScanner;
import com.ses.service.storage.DocumentStorage;
import com.ses.service.impl.DocumentMetadataCommitService;
import com.ses.service.impl.StagedImportDocument;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** test専用固定manifestで実MySQLのraw upload、冪等再利用、rollback/競合補償、総期限、ACK-loss回復を確認する。 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
@Import(ImportCsvIngestionRealMySqlTest.TestBeans.class)
class ImportCsvIngestionRealMySqlTest {
    private static final String DATABASE = "nf06_import_ingress_real";
    private static final String TENANT = "nf06-import-ingress-test";
    private static final long LEGAL_ENTITY = 7_601_235L;
    private static final byte[] CSV = "externalKey,companyName\nC-SYN-1,Synthetic Company\n"
            .getBytes(StandardCharsets.UTF_8);
    private static final String CSV_HASH = sha256(CSV);

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName(DATABASE).withUsername("root").withPassword("ses");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("app.security.oidc.tenant-id", () -> TENANT);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ImportCsvIngestionService ingestion;
    @Autowired private ImportMetadataAuthorizationService authorization;
    @Autowired private ImportRawTempFileStore tempFiles;
    @SpyBean private ImportMetadataService metadata;
    @SpyBean private ImportCsvUploadCommitService commitService;
    @Autowired private com.ses.mapper.SysUserMapper users;
    @SpyBean private DocumentMetadataCommitService documentMetadataCommit;
    @SpyBean private com.ses.service.DocumentService documentService;
    @SpyBean private DocumentVersionMapper documentVersionMapper;
    @Autowired private SyntheticFixtureAdmission admission;
    @Autowired private InMemoryDocumentStorage storage;
    @Autowired private SwitchableScanner scanner;
    private SysUser actorUser;
    private long actorId;
    private long groupId;
    private long sourceId;
    private String sourceCode;
    private volatile CountDownLatch stagedRequestsBarrier;
    /** test毎に独立した照合台帳。Spring beanの台帳（upload root配下）は使わず、他testの台帳と混ざらないようにする。 */
    @org.junit.jupiter.api.io.TempDir Path stageLedgerRoot;
    private ImportStageReconciliationLedger stageLedger;

    @BeforeEach
    void prepareStageLedger() {
        stageLedger = new ImportStageReconciliationLedger(stageLedgerRoot.resolve(ImportStageReconciliationLedger.DIRECTORY),
                null, java.time.Clock.systemUTC());
    }

    @BeforeEach
    void prepareAuthorizedSource() {
        AccountingTenantContextHolder.setTenantId(TENANT);
        scanner.set(SwitchableScanner.Mode.CLEAN);
        sourceCode = "nf06_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        actorUser = SysUser.builder().username("nf06-" + sourceCode).password("test-only")
                .realName("取込テスト管理者").role("管理者").status(1).tenantId(TENANT).build();
        users.insert(actorUser);
        actorId = actorUser.getId();
        jdbc.update("""
                INSERT INTO m_organization_unit(legal_entity_id, code, name, type, valid_from, status, deleted_flag)
                VALUES(?, ?, ?, '法人', CURRENT_DATE, '有効', 0)
                """, LEGAL_ENTITY, "NF06-" + TENANT, "取込テスト法人");
        jdbc.update("""
                INSERT INTO m_permission_group(tenant_id, group_key, group_name, enabled, deleted_flag)
                VALUES(?, ?, ?, 1, 0)
                """, TENANT, "nf06-import-" + sourceCode, "取込アップロードテスト");
        groupId = jdbc.queryForObject("SELECT id FROM m_permission_group WHERE tenant_id=? AND group_key=?",
                Long.class, TENANT, "nf06-import-" + sourceCode);
        jdbc.update("""
                INSERT INTO t_user_permission_group(tenant_id, user_id, group_id, assigned_by, deleted_flag)
                VALUES(?, ?, ?, ?, 0)
                """, TENANT, actorId, groupId, actorId);
        jdbc.update("""
                INSERT INTO t_permission_group_action(tenant_id, group_id, action_key, deny_flag, deleted_flag)
                VALUES(?, ?, ?, 0, 0)
                """, TENANT, groupId, ImportMetadataAuthorizationService.ACTION);
        var authority = new SimpleGrantedAuthority("ROLE_管理者");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(actorUser, List.of(authority), TENANT), "test-only", List.of(authority)));
        sourceId = metadata.createSource(new ImportSourceRequest(sourceCode, "合成source"));
        admission.bind(new ImportAuthorizedContext(TENANT, actorId, LEGAL_ENTITY),
                new ImportUploadSource(sourceId, sourceCode.toLowerCase(java.util.Locale.ROOT), 1), CSV_HASH);
        jdbc.update("""
                INSERT IGNORE INTO m_document_type
                  (code, name, direction, retention_years, retention_start_rule, legal_hold_supported, deleted_flag)
                VALUES ('IMPORT', '合成取込CSV', 'INCOMING', 7, 'CREATED_AT', 1, 0)
                """);
    }

    @AfterEach
    void cleanupFixture() {
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
        scanner.set(SwitchableScanner.Mode.CLEAN);
        jdbc.update("DELETE FROM t_import_job WHERE tenant_id=? AND source_system_id=?", TENANT, sourceId);
        jdbc.update("DELETE FROM t_document_link WHERE tenant_id=? AND document_id IN "
                + "(SELECT id FROM t_document WHERE tenant_id=? AND document_type='IMPORT')", TENANT, TENANT);
        jdbc.update("DELETE FROM t_document_access_log WHERE document_id IN "
                + "(SELECT id FROM t_document WHERE tenant_id=? AND document_type='IMPORT')", TENANT);
        jdbc.update("DELETE FROM t_document_version WHERE tenant_id=? AND document_id IN "
                + "(SELECT id FROM t_document WHERE tenant_id=? AND document_type='IMPORT')", TENANT, TENANT);
        jdbc.update("DELETE FROM t_document WHERE tenant_id=? AND document_type='IMPORT'", TENANT);
        if (sourceId > 0) jdbc.update("DELETE FROM t_import_source_system WHERE tenant_id=? AND id=?", TENANT, sourceId);
        jdbc.update("DELETE FROM m_document_type WHERE code='IMPORT' AND NOT EXISTS "
                + "(SELECT 1 FROM t_document WHERE document_type='IMPORT')");
        storage.clear();
        if (groupId > 0) {
            jdbc.update("DELETE FROM t_permission_group_action WHERE tenant_id=? AND group_id=?", TENANT, groupId);
            jdbc.update("DELETE FROM t_user_permission_group WHERE tenant_id=? AND group_id=?", TENANT, groupId);
            jdbc.update("DELETE FROM m_permission_group WHERE tenant_id=? AND id=?", TENANT, groupId);
        }
        if (actorId > 0) jdbc.update("DELETE FROM sys_user WHERE id=?", actorId);
        jdbc.update("DELETE FROM m_organization_unit WHERE legal_entity_id=? AND code=?",
                LEGAL_ENTITY, "NF06-" + TENANT);
    }

    @Test
    void realMySqlUploadCommitsExactCleanDocumentVersionJobAndLinkAndIsIdempotent() {
        AtomicBoolean bodyOpened = new AtomicBoolean();
        Map<String, String> domainBefore = snapshotNoDomainWrites();
        Set<String> tempBefore = rawTempFiles();

        ImportRawUploadHeaders headers = new ImportRawUploadHeaders(sourceCode, ImportEncoding.UTF8);
        ImportUploadJobDetail created = ingestion.upload(headers, () -> {
                    bodyOpened.set(true);
                    return new ByteArrayInputStream(CSV);
                });

        ImportUploadJobDetail reused = ingestion.upload(headers, () -> new ByteArrayInputStream(CSV));

        assertEquals("UPLOADED", created.state());
        assertEquals("CUSTOMER", created.entity());
        assertEquals(CSV_HASH.substring(0, 12), created.sourceHashPrefix());
        assertEquals(created.jobId(), reused.jobId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_document WHERE tenant_id=? "
                + "AND document_type='IMPORT' AND legal_entity_id=?", Integer.class, TENANT,
                Long.toString(LEGAL_ENTITY)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_document_version v JOIN t_document d "
                + "ON d.tenant_id=v.tenant_id AND d.id=v.document_id WHERE d.tenant_id=? "
                + "AND d.document_type='IMPORT' AND v.scan_status='CLEAN' AND v.size_bytes=? "
                + "AND UNHEX(v.sha256)=UNHEX(?)", Integer.class, TENANT, CSV.length, CSV_HASH));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_job WHERE tenant_id=? AND id=? "
                + "AND source_system_id=? AND source_version=1 AND state='UPLOADED' "
                + "AND entity_type='CUSTOMER' AND legal_entity_id=? AND source_sha256=UNHEX(?)",
                Integer.class, TENANT, created.jobId(), sourceId, Long.toString(LEGAL_ENTITY), CSV_HASH));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_document_link WHERE tenant_id=? "
                + "AND target_type='IMPORT_JOB' AND target_id=? AND deleted_flag=0", Integer.class,
                TENANT, created.jobId()));
        assertEquals(1, storage.putCount());
        assertEquals(CSV_HASH, sha256(storage.onlyObject()));
        assertEquals(created, metadata.getUploadedJobDetail(created.jobId()));
        assertFalse(created.toString().contains("Synthetic Company"));
        assertTrue(bodyOpened.get());
        assertEquals(domainBefore, snapshotNoDomainWrites());
        assertEquals(tempBefore, rawTempFiles(), "成功・再利用の両経路でraw CSV一時ファイルを残さない");
    }

    @Test
    void failureAtVersionInsertRollsBackTheEarlierDocumentInsert() {
        doThrow(BusinessException.of(500, "error.test.injectedVersionInsertFailure"))
                .when(documentVersionMapper).insert(any(DocumentVersion.class));

        assertThrows(BusinessException.class, () -> ingestion.upload(
                new ImportRawUploadHeaders(sourceCode, ImportEncoding.UTF8), () -> new ByteArrayInputStream(CSV)));

        assertNoCommittedRows();
    }

    @Test
    void failureAfterDocumentAndVersionInsertRollsBackAndCompensatesOwnedStage() {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw BusinessException.of(500, "error.test.injectedDocumentVersionFailure");
        }).when(documentMetadataCommit).commitImportDocument(any(StagedImportDocument.class));

        assertThrows(BusinessException.class, () -> ingestion.upload(
                new ImportRawUploadHeaders(sourceCode, ImportEncoding.UTF8), () -> new ByteArrayInputStream(CSV)));

        assertNoCommittedRows();
    }

    @Test
    void failureAfterJobInsertBeforeLinkRollsBackAllMetadataAndCompensatesOwnedStage() {
        doThrow(BusinessException.of(500, "error.test.injectedJobInsertFailure"))
                .when(documentService).link(anyLong(), eq("IMPORT_JOB"), anyLong());

        assertThrows(BusinessException.class, () -> ingestion.upload(
                new ImportRawUploadHeaders(sourceCode, ImportEncoding.UTF8), () -> new ByteArrayInputStream(CSV)));

        assertNoCommittedRows();
    }

    @Test
    void failureAfterLinkInsertRollsBackAllMetadataAndCompensatesOwnedStage() {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw BusinessException.of(500, "error.test.injectedLinkInsertFailure");
        }).when(documentService).link(anyLong(), eq("IMPORT_JOB"), anyLong());

        assertThrows(BusinessException.class, () -> ingestion.upload(
                new ImportRawUploadHeaders(sourceCode, ImportEncoding.UTF8), () -> new ByteArrayInputStream(CSV)));

        assertNoCommittedRows();
    }

    // ---------------------------------------------------------------- 認可拒否

    @Test
    void revokedImportGrantRejectsBeforeBodyAndWritesNothing() {
        jdbc.update("UPDATE t_permission_group_action SET deleted_flag=1 WHERE tenant_id=? AND group_id=?",
                TENANT, groupId);
        AtomicBoolean bodyOpened = new AtomicBoolean();
        Set<String> tempBefore = rawTempFiles();

        BusinessException denied = assertThrows(BusinessException.class, () -> ingestion.upload(
                new ImportRawUploadHeaders(sourceCode, ImportEncoding.UTF8), () -> {
                    bodyOpened.set(true);
                    return new ByteArrayInputStream(CSV);
                }));

        assertEquals(403, denied.getCode());
        assertFalse(bodyOpened.get());
        assertEquals(0, storage.putCount());
        verify(commitService, never()).commit(any(), any(), any(), anyInt());
        assertNoCommittedRows();
        assertEquals(tempBefore, rawTempFiles());
    }

    @Test
    void grantRevokedAtCommitWriteGateRollsBackAndDiscardsOwnedStage() {
        doAnswer(invocation -> {
            jdbc.update("UPDATE t_permission_group_action SET deleted_flag=1 WHERE tenant_id=? AND group_id=?",
                    TENANT, groupId);
            return invocation.callRealMethod();
        }).when(commitService).commit(any(), any(), any(), anyInt());
        Set<String> tempBefore = rawTempFiles();

        BusinessException denied = assertThrows(BusinessException.class, () -> ingestion.upload(
                new ImportRawUploadHeaders(sourceCode, ImportEncoding.UTF8), () -> new ByteArrayInputStream(CSV)));

        assertEquals(403, denied.getCode());
        verify(commitService).commit(any(), any(), any(), anyInt());
        verify(documentService).discardImportStage(any());
        assertEquals(1, storage.putCount());
        assertNoCommittedRows();
        assertEquals(tempBefore, rawTempFiles());
    }

    // ---------------------------------------------------------------- scanner error

    @Test
    void scannerOutageAndInfectedVerdictLeaveNoStorageMetadataOrTempFile() {
        Set<String> tempBefore = rawTempFiles();
        for (SwitchableScanner.Mode mode : List.of(SwitchableScanner.Mode.THROW, SwitchableScanner.Mode.INFECTED)) {
            scanner.set(mode);

            BusinessException rejected = assertThrows(BusinessException.class, () -> ingestion.upload(
                    new ImportRawUploadHeaders(sourceCode, ImportEncoding.UTF8), () -> new ByteArrayInputStream(CSV)));

            assertEquals(400, rejected.getCode());
            assertEquals("error.file.scanRejected", rejected.getMessageKey());
        }

        assertEquals(0, storage.putCount());
        verify(commitService, never()).commit(any(), any(), any(), anyInt());
        assertNoCommittedRows();
        assertEquals(tempBefore, rawTempFiles());
    }

    // ---------------------------------------------------------------- commit失敗の分類 / ACK-loss / readback

    @Test
    void commitAckLossAfterDurableCommitReturnsOwnJobFromReadback() {
        // 実COMMIT成功後に応答だけ失われた状態（afterCompletion=COMMITTED）をtransaction manager境界で再現する。
        FaultInjectingTransactionManager ackLoss = new FaultInjectingTransactionManager(transactionManager,
                false, () -> { }, true);
        Set<String> tempBefore = rawTempFiles();

        ImportUploadJobDetail recovered = ingestionWith(commitServiceWith(ackLoss), System::nanoTime).upload(
                new ImportRawUploadHeaders(sourceCode, ImportEncoding.UTF8), () -> new ByteArrayInputStream(CSV));

        assertEquals("UPLOADED", recovered.state());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_job WHERE tenant_id=? AND id=? "
                + "AND source_system_id=? AND state='UPLOADED'", Integer.class, TENANT, recovered.jobId(), sourceId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_document WHERE tenant_id=? "
                + "AND document_type='IMPORT'", Integer.class, TENANT));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_document_link WHERE tenant_id=? "
                + "AND target_type='IMPORT_JOB' AND target_id=? AND deleted_flag=0", Integer.class,
                TENANT, recovered.jobId()));
        assertEquals(1, storage.objectCount());
        assertEquals(CSV_HASH, sha256(storage.onlyObject()));
        assertEquals(recovered, metadata.getUploadedJobDetail(recovered.jobId()));
        verify(metadata).findCommittedUploadForStage(any(), any(), any(), any());
        verify(documentService, never()).discardImportStage(any());
        assertEquals(tempBefore, rawTempFiles());
    }

    @Test
    void commitAckUncertainWithoutVisibleRowRetainsStageForReconciliation() {
        // COMMIT送信前にserver側で接続を切断し、client側ではCOMMIT結果が判定不能（afterCompletion=UNKNOWN）な状態を作る。
        // serverは切断でtransactionをrollbackするため行は見えず、storage補償もROLLED_BACKではないので走らない。
        FaultInjectingTransactionManager severed = new FaultInjectingTransactionManager(transactionManager,
                false, this::killTransactionConnection, false);
        Set<String> tempBefore = rawTempFiles();

        assertThrows(ImportCommitOutcomeUnknownException.class, () -> ingestionWith(commitServiceWith(severed),
                System::nanoTime).upload(new ImportRawUploadHeaders(sourceCode, ImportEncoding.UTF8),
                () -> new ByteArrayInputStream(CSV)));

        verify(metadata).findCommittedUploadForStage(any(), any(), any(), any());
        verify(documentService, never()).discardImportStage(any());
        assertNoMetadataRows();
        assertEquals(1, storage.objectCount(), "COMMIT結果を確定できないstageだけは照合用に保全する");
        assertEquals(tempBefore, rawTempFiles());

        // A-NF06-2：保全したstageは照合台帳に載り、safety window経過後の照合で実MySQLの参照有無から回収される。
        List<ImportStageReconciliationLedger.Entry> retained = stageLedger.entries();
        assertEquals(1, retained.size());
        assertEquals(ImportStageReconciliationLedger.Reason.OUTCOME_UNKNOWN, retained.get(0).reason());
        assertEquals(TENANT, retained.get(0).tenantId());

        ImportStageReconciler.Result early = reconcilerAt(java.time.Clock.systemUTC()).reconcileDue();
        assertEquals(1, early.pending(), "safety window内は判定せず残す");
        assertEquals(1, storage.objectCount());

        ImportStageReconciler.Result due = reconcilerAt(java.time.Clock.offset(java.time.Clock.systemUTC(),
                ImportStageReconciler.SAFETY_WINDOW.plusMinutes(1))).reconcileDue();
        assertEquals(1, due.reclaimed());
        assertEquals(0, due.failed());
        assertEquals(0, storage.objectCount(), "t_document_versionから参照されないstage objectは回収する");
        assertEquals(List.of(), stageLedger.entries());
        verify(documentService).discardImportStage(any());
        assertNoMetadataRows();
    }

    @Test
    void reconcilerKeepsAnObjectWhoseStorageKeyIsReferencedByACommittedVersion() {
        ImportUploadJobDetail committed = ingestion.upload(new ImportRawUploadHeaders(sourceCode, ImportEncoding.UTF8),
                () -> new ByteArrayInputStream(CSV));
        String committedKey = jdbc.queryForObject("""
                SELECT v.storage_key FROM t_import_job j JOIN t_document_version v
                  ON v.tenant_id=j.tenant_id AND v.id=j.document_version_id
                WHERE j.tenant_id=? AND j.id=?
                """, String.class, TENANT, committed.jobId());
        com.ses.entity.Document document = new com.ses.entity.Document();
        document.setDocumentType("IMPORT");
        DocumentVersion version = new DocumentVersion();
        version.setStorageKey(committedKey);
        // COMMIT結果不明で保全したがcommitは成立していた、という状態を台帳だけで再現する。
        assertTrue(stageLedger.recordRetained(TENANT,
                new StagedImportDocument(document, version, committedKey, UUID.randomUUID().toString())));

        ImportStageReconciler.Result due = reconcilerAt(java.time.Clock.offset(java.time.Clock.systemUTC(),
                ImportStageReconciler.SAFETY_WINDOW.plusMinutes(1))).reconcileDue();

        assertEquals(1, due.committed());
        assertEquals(0, due.reclaimed());
        assertEquals(1, storage.objectCount(), "commit済みの版が参照するobjectは消さない");
        assertEquals(List.of(), stageLedger.entries());
        verify(documentService, never()).discardImportStage(any());
        assertEquals(committed, metadata.getUploadedJobDetail(committed.jobId()));
    }

    @Test
    void transactionBeginFailureIsNotCommittedAndDiscardsOwnedStageWithoutReadback() {
        FaultInjectingTransactionManager exhausted = new FaultInjectingTransactionManager(transactionManager,
                true, () -> { }, false);
        Set<String> tempBefore = rawTempFiles();

        assertThrows(CannotCreateTransactionException.class, () -> ingestionWith(commitServiceWith(exhausted),
                System::nanoTime).upload(new ImportRawUploadHeaders(sourceCode, ImportEncoding.UTF8),
                () -> new ByteArrayInputStream(CSV)));

        verify(metadata, never()).findCommittedUploadForStage(any(), any(), any(), any());
        verify(documentService).discardImportStage(any());
        assertEquals(1, storage.putCount());
        assertNoCommittedRows();
        assertEquals(tempBefore, rawTempFiles());
    }

    // ---------------------------------------------------------------- 120秒総期限

    @Test
    void deadlineExpiredAfterStageDiscardsOwnedStageAndCommitsNothing() {
        AtomicLong nanos = new AtomicLong();
        doAnswer(invocation -> {
            Object staged = invocation.callRealMethod();
            nanos.set(ImportIngressDeadline.TOTAL_BUDGET.plusSeconds(1).toNanos());
            return staged;
        }).when(documentService).stageReceivedForImport(any(), any(), anyLong(), any(), any());
        Set<String> tempBefore = rawTempFiles();

        BusinessException expired = assertThrows(BusinessException.class, () -> ingestionWithClock(nanos).upload(
                new ImportRawUploadHeaders(sourceCode, ImportEncoding.UTF8), () -> new ByteArrayInputStream(CSV)));

        assertEquals(503, expired.getCode());
        assertEquals(ImportIngressDeadline.EXCEEDED_MESSAGE_KEY, expired.getMessageKey());
        verify(commitService, never()).commit(any(), any(), any(), anyInt());
        verify(documentService).discardImportStage(any());
        assertEquals(1, storage.putCount());
        assertNoCommittedRows();
        assertEquals(tempBefore, rawTempFiles());
    }

    @Test
    void commitTransactionTimeoutDerivedFromDeadlineCutsLockWaitAndReportsDeadline() throws Exception {
        AtomicLong nanos = new AtomicLong();
        long budgetNanos = ImportIngressDeadline.TOTAL_BUDGET.toNanos();
        doAnswer(invocation -> {
            Object staged = invocation.callRealMethod();
            // commit開始時点の残りを1.5秒にし、transaction timeoutを1秒へ縮める。
            nanos.set(budgetNanos - Duration.ofMillis(1_500).toNanos());
            return staged;
        }).when(documentService).stageReceivedForImport(any(), any(), anyLong(), any(), any());
        Set<String> tempBefore = rawTempFiles();

        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement lock = blocker.prepareStatement("SELECT id FROM sys_user WHERE id=? FOR UPDATE")) {
                lock.setLong(1, actorId);
                lock.executeQuery().close();
            }
            try {
                long started = System.nanoTime();
                BusinessException expired = assertThrows(BusinessException.class, () -> ingestionWithClock(nanos)
                        .upload(new ImportRawUploadHeaders(sourceCode, ImportEncoding.UTF8),
                                () -> new ByteArrayInputStream(CSV)));
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

                assertEquals(503, expired.getCode());
                assertEquals(ImportIngressDeadline.EXCEEDED_MESSAGE_KEY, expired.getMessageKey());
                assertTrue(elapsedMillis < 20_000,
                        "commitのlock待ちは総期限由来のtransaction timeoutで打ち切られなければなりません: " + elapsedMillis);
            } finally {
                blocker.rollback();
            }
        }

        verify(commitService).commit(any(), any(), any(), eq(1));
        // 書込gateのtimeoutはコールバック内（COMMIT未発行）で起きるため未commitが確定し、stageは破棄される。
        verify(documentService).discardImportStage(any());
        verify(metadata, never()).findCommittedUploadForStage(any(), any(), any(), any());
        assertEquals(1, storage.putCount());
        assertNoCommittedRows();
        assertEquals(tempBefore, rawTempFiles());
    }

    private ImportCsvIngestionService ingestionWithClock(AtomicLong nanos) {
        return new ImportCsvIngestionService(authorization, metadata, admission, documentService, commitService,
                tempFiles, stageLedger, nanos::get, ImportIngressDeadline.TOTAL_BUDGET);
    }

    private ImportCsvIngestionService ingestionWith(ImportCsvUploadCommitService faultCommit, LongSupplier clock) {
        return new ImportCsvIngestionService(authorization, metadata, admission, documentService, faultCommit,
                tempFiles, stageLedger, clock, ImportIngressDeadline.TOTAL_BUDGET);
    }

    /** 実MySQL（JdbcTemplate）と実DocumentServiceで照合する。時刻だけ差し替えてsafety windowを越えさせる。 */
    private ImportStageReconciler reconcilerAt(java.time.Clock clock) {
        return new ImportStageReconciler(stageLedger, jdbc, documentService, null, clock);
    }

    private ImportCsvUploadCommitService commitServiceWith(PlatformTransactionManager manager) {
        return new ImportCsvUploadCommitService(authorization, documentMetadataCommit, metadata, manager);
    }

    /** 現threadのtransaction接続をserver側でKILLする。COMMIT前に呼び、COMMIT応答を判定不能にする。 */
    private void killTransactionConnection() {
        Connection bound = DataSourceUtils.getConnection(dataSource);
        try {
            long connectionId;
            try (Statement statement = bound.createStatement();
                 ResultSet result = statement.executeQuery("SELECT CONNECTION_ID()")) {
                assertTrue(result.next());
                connectionId = result.getLong(1);
            }
            try (Connection killer = dataSource.getConnection(); Statement kill = killer.createStatement()) {
                kill.execute("KILL " + connectionId);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("transaction接続のKILLに失敗しました", failure);
        } finally {
            DataSourceUtils.releaseConnection(bound, dataSource);
        }
    }

    /** 実transaction managerへ委譲し、開始失敗・COMMIT直前の処理・COMMIT成功後の応答喪失だけを注入する。 */
    static final class FaultInjectingTransactionManager implements PlatformTransactionManager {
        private final PlatformTransactionManager delegate;
        private final boolean failBegin;
        private final Runnable beforeCommit;
        private final boolean failAfterDurableCommit;

        FaultInjectingTransactionManager(PlatformTransactionManager delegate, boolean failBegin,
                                         Runnable beforeCommit, boolean failAfterDurableCommit) {
            this.delegate = delegate;
            this.failBegin = failBegin;
            this.beforeCommit = beforeCommit;
            this.failAfterDurableCommit = failAfterDurableCommit;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            if (failBegin) throw new CannotCreateTransactionException("synthetic connection pool exhaustion");
            return delegate.getTransaction(definition);
        }

        @Override
        public void commit(TransactionStatus status) {
            beforeCommit.run();
            delegate.commit(status);
            if (failAfterDurableCommit) {
                throw new TransactionSystemException("synthetic commit acknowledgement loss");
            }
        }

        @Override
        public void rollback(TransactionStatus status) {
            delegate.rollback(status);
        }
    }

    private void assertNoCommittedRows() {
        assertNoMetadataRows();
        assertEquals(0, storage.objectCount());
    }

    private void assertNoMetadataRows() {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_document WHERE tenant_id=? "
                + "AND document_type='IMPORT'", Integer.class, TENANT));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_document_version WHERE tenant_id=? "
                + "AND original_name='upload.csv'", Integer.class, TENANT));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_job WHERE tenant_id=? "
                + "AND source_system_id=?", Integer.class, TENANT, sourceId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_document_link WHERE tenant_id=? "
                + "AND target_type='IMPORT_JOB'", Integer.class, TENANT));
    }

    private Set<String> rawTempFiles() {
        Path directory = tempFiles.directory();
        if (!Files.isDirectory(directory)) return Set.of();
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> name.startsWith(ImportRawTempFileStore.PREFIX))
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Map<String, String> snapshotNoDomainWrites() {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (String table : List.of("m_customer", "t_engineer", "t_project", "t_project_position",
                "t_proposal", "t_contract", "t_engineer_sales", "m_cost_center",
                "t_organization_relation_history", "t_engineer_accounting_history",
                "t_service_request_sequence", "t_lifecycle_case_sequence", "t_import_identity_registry")) {
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            String fingerprint = jdbc.query("SELECT * FROM " + table + " ORDER BY 1", rows -> {
                java.sql.ResultSetMetaData columns = rows.getMetaData();
                while (rows.next()) {
                    for (int column = 1; column <= columns.getColumnCount(); column++) {
                        Object value = rows.getObject(column);
                        if (value == null) {
                            digest.update((byte) 0);
                        } else {
                            digest.update((byte) 1);
                            byte[] bytes = value instanceof byte[] binary ? binary
                                    : value.toString().getBytes(StandardCharsets.UTF_8);
                            digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                            digest.update(bytes);
                        }
                    }
                    digest.update((byte) '\n');
                }
                return HexFormat.of().formatHex(digest.digest());
            });
            result.put(table, fingerprint);
        }
        return Map.copyOf(result);
    }

    @Test
    void alteredBytesCannotReuseTheFixtureManifestOrCreateMetadata() {
        byte[] altered = "externalKey,companyName\nC-SYN-1,Altered Company\n".getBytes(StandardCharsets.UTF_8);

        BusinessException denied = assertThrows(BusinessException.class, () -> ingestion.upload(
                new ImportRawUploadHeaders(sourceCode, ImportEncoding.UTF8), () -> new ByteArrayInputStream(altered)));

        assertEquals(403, denied.getCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_document WHERE tenant_id=? "
                + "AND document_type='IMPORT'", Integer.class, TENANT));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_job WHERE tenant_id=? "
                + "AND source_system_id=?", Integer.class, TENANT, sourceId));
        assertEquals(0, storage.objectCount());
    }

    @Test
    void concurrentIdenticalUploadsKeepOneReadableWinnerAndDeleteOnlyLoserStage() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        stagedRequestsBarrier = new CountDownLatch(2);
        doAnswer(invocation -> {
            StagedImportDocument staged = (StagedImportDocument) invocation.callRealMethod();
            CountDownLatch barrier = stagedRequestsBarrier;
            if (barrier != null) {
                barrier.countDown();
                if (!barrier.await(25, TimeUnit.SECONDS)) {
                    throw new AssertionError("両requestのstaging完了前にcommitへ進めてはいけません");
                }
            }
            return staged;
        }).when(documentService).stageReceivedForImport(any(), any(), anyLong(), any(), any());
        try {
            Future<ImportUploadJobDetail> first = executor.submit(() -> uploadWhenReleased(start));
            Future<ImportUploadJobDetail> second = executor.submit(() -> uploadWhenReleased(start));
            start.countDown();
            ImportUploadJobDetail a = first.get(30, TimeUnit.SECONDS);
            ImportUploadJobDetail b = second.get(30, TimeUnit.SECONDS);

            assertEquals(a.jobId(), b.jobId());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_document WHERE tenant_id=? "
                    + "AND document_type='IMPORT'", Integer.class, TENANT));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_job WHERE tenant_id=? "
                    + "AND source_system_id=? AND state='UPLOADED'", Integer.class, TENANT, sourceId));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_document_link WHERE tenant_id=? "
                    + "AND target_type='IMPORT_JOB' AND target_id=? AND deleted_flag=0", Integer.class,
                    TENANT, a.jobId()));
            assertEquals(1, storage.objectCount());
            assertEquals(2, storage.putCount());
            assertEquals(CSV_HASH, sha256(storage.onlyObject()));
            assertEquals(a, metadata.getUploadedJobDetail(a.jobId()));
        } finally {
            stagedRequestsBarrier = null;
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private ImportUploadJobDetail uploadWhenReleased(CountDownLatch start) throws Exception {
        start.await();
        AccountingTenantContextHolder.setTenantId(TENANT);
        var authority = new SimpleGrantedAuthority("ROLE_管理者");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(actorUser, List.of(authority), TENANT), "test-only", List.of(authority)));
        try {
            return ingestion.upload(new ImportRawUploadHeaders(sourceCode, ImportEncoding.UTF8),
                    () -> new ByteArrayInputStream(CSV));
        } finally {
            SecurityContextHolder.clearContext();
            AccountingTenantContextHolder.clear();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean @Primary
        SyntheticFixtureAdmission syntheticFixtureAdmission() { return new SyntheticFixtureAdmission(); }

        @Bean @Primary
        InMemoryDocumentStorage inMemoryDocumentStorage() { return new InMemoryDocumentStorage(); }

        @Bean @Primary
        SwitchableScanner syntheticSwitchableScanner() { return new SwitchableScanner(); }

        @Bean @Primary
        ImportIdentityKeyProvider importIdentityKeyProvider() {
            return new ImportIdentityKeyProvider() {
                @Override public String currentLookupKeyVersion() { return "nf06-test-v1"; }
                @Override public byte[] lookupKey(String keyVersion) { return new byte[32]; }
                @Override public String currentEncryptionKeyVersion() { return "nf06-test-v1"; }
                @Override public byte[] encryptionKey(String keyVersion) { return new byte[32]; }
            };
        }
    }

    /** 既定はCLEAN。testごとにscanner障害・感染判定へ切り替える。 */
    static class SwitchableScanner implements FileScanner {
        enum Mode { CLEAN, INFECTED, THROW }

        private volatile Mode mode = Mode.CLEAN;

        void set(Mode next) { mode = next; }

        @Override public FileScanResult scan(Path quarantinedFile, FileKind kind) {
            return switch (mode) {
                case CLEAN -> FileScanResult.clean("test-clean");
                case INFECTED -> FileScanResult.infected("test-signature");
                case THROW -> throw new IllegalStateException("synthetic scanner outage");
            };
        }
    }

    static class SyntheticFixtureAdmission implements ImportAdmissionPort {
        private volatile FixtureTuple allowed;

        void bind(ImportAuthorizedContext context, ImportUploadSource source, String sha256) {
            allowed = new FixtureTuple(context, source, sha256,
                    "mysql-fixture-" + UUID.randomUUID().toString().replace("-", ""));
        }

        @Override public ImportAdmissionToken admitBeforeBody(ImportAuthorizedContext context,
                ImportUploadSource source, ImportEntity entity, String schema, ImportEncoding encoding) {
            FixtureTuple tuple = allowed;
            if (tuple == null || !tuple.context().equals(context) || !tuple.source().equals(source)
                    || entity != ImportEntity.CUSTOMER || !SesImportV1Schema.VERSION.equals(schema)
                    || encoding != ImportEncoding.UTF8) {
                throw BusinessException.of(403, "error.import.fixtureNotAllowed");
            }
            return new ImportAdmissionToken(context.tenantId(), context.actorUserId(), context.legalEntityId(),
                    source.id(), source.version(), entity, schema, encoding, tuple.fixtureKey());
        }

        @Override public ImportAdmissionManifest admitContent(ImportAdmissionToken token, String sourceSha256) {
            FixtureTuple tuple = allowed;
            if (tuple == null || !tuple.fixtureKey().equals(token.fixtureKey())
                    || !tuple.sha256().equals(sourceSha256)) {
                throw BusinessException.of(403, "error.import.fixtureContentMismatch");
            }
            return new ImportAdmissionManifest(token.fixtureKey(), token.tenantId(), token.legalEntityId(),
                    token.sourceSystemId(), token.sourceVersion(), ImportEntity.CUSTOMER,
                    SesImportV1Schema.VERSION, ImportEncoding.UTF8, tuple.sha256(), null, null, null, null,
                    "A1A_CONTENT");
        }

        private record FixtureTuple(ImportAuthorizedContext context, ImportUploadSource source, String sha256,
                                    String fixtureKey) { }
    }

    static class InMemoryDocumentStorage implements DocumentStorage {
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        private final AtomicInteger putCount = new AtomicInteger();

        @Override public void put(String key, InputStream content, boolean quarantine) {
            try { objects.put(key, content.readAllBytes()); putCount.incrementAndGet(); }
            catch (IOException failure) { throw new IllegalStateException(failure); }
        }
        @Override public void put(String key, byte[] content, boolean quarantine) {
            objects.put(key, content.clone()); putCount.incrementAndGet();
        }
        @Override public void promote(String key) { }
        @Override public InputStream open(String key) {
            byte[] bytes = objects.get(key);
            if (bytes == null) throw new IllegalStateException("missing synthetic object");
            return new ByteArrayInputStream(bytes.clone());
        }
        @Override public byte[] readAll(String key) throws IOException {
            byte[] bytes = objects.get(key);
            if (bytes == null) throw new IOException("missing synthetic object");
            return bytes.clone();
        }
        @Override public void delete(String key) { objects.remove(key); }
        @Override public void deleteStrict(String key) { objects.remove(key); }
        int putCount() { return putCount.get(); }
        int objectCount() { return objects.size(); }
        byte[] onlyObject() { return objects.values().stream().findFirst().orElseThrow().clone(); }
        void clear() { objects.clear(); putCount.set(0); }
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
