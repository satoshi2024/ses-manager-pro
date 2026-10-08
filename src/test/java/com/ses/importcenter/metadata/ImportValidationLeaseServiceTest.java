package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;
import com.ses.service.DocumentService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionSystemException;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** H2でlease transaction境界、MAPPED fence、応答喪失後の再試行を確認する。 */
class ImportValidationLeaseServiceTest {
    private static final byte[] SOURCE_HASH = hash("exact source bytes");
    private static final byte[] MAPPING_HASH = hash("canonical mapping");
    private final ImportAuthorizedContext context = new ImportAuthorizedContext("lease-h2", 41L, 77L);
    private JdbcTemplate jdbc;
    private ImportValidationLeaseService service;
    private long jobId = 6L;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:lease_" + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        createSchema(jdbc);
        jdbc.update("INSERT INTO t_import_source_system VALUES(?,?,?,?)", context.tenantId(), 2L, 8L, "ACTIVE");
        jdbc.update("INSERT INTO t_document VALUES(?,?,?,?,?,?)", context.tenantId(), 3L, "77", 0, null, 0);
        jdbc.update("INSERT INTO t_document_version VALUES(?,?,?,?,?,?,?)", context.tenantId(), 3L, 4L, 1,
                HexFormat.of().formatHex(SOURCE_HASH), "CLEAN", 0);
        jdbc.update("INSERT INTO t_document_link(tenant_id,document_id,target_type,target_id,deleted_flag) VALUES(?,?,?,?,0)",
                context.tenantId(), 3L, "IMPORT_JOB", jobId);
        jdbc.update("INSERT INTO t_import_mapping VALUES(?,?,?,?,?,?,?)", context.tenantId(), 5L, 2L, "CUSTOMER",
                "ses-import-v1", 1, MAPPING_HASH);
        jdbc.update("""
                INSERT INTO t_import_job(tenant_id,id,source_system_id,source_version,entity_type,schema_version,
                  source_encoding,source_sha256,legal_entity_id,document_id,document_version_id,document_version_no,
                  document_sha256,mapping_id,mapping_version,mapping_sha256,state,version_no,lease_generation)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'MAPPED',1,0)
                """, context.tenantId(), jobId, 2L, 8L, "CUSTOMER", "ses-import-v1", "UTF8", SOURCE_HASH,
                "77", 3L, 4L, 1, SOURCE_HASH, 5L, 1, MAPPING_HASH);
        ImportMetadataAuthorizationService authorization = mock(ImportMetadataAuthorizationService.class);
        when(authorization.requireCurrentAuthorization()).thenReturn(context);
        service = new ImportValidationLeaseService(jdbc, authorization, mock(DocumentService.class),
                new DataSourceTransactionManager(ds));
    }

    @Test
    void acquireRenewExpiredTakeoverAndResponseLossAreFenced() {
        var lostManager = new CommitResponseLostManager(jdbc.getDataSource());
        var lost = new ImportValidationLeaseService(jdbc, mockAuthorization(), mock(DocumentService.class),
                lostManager);
        var operation = lost.beginAcquire(jobId);
        assertThrows(TransactionSystemException.class, () -> lost.acquire(operation));
        var acquiredBeforeRetry = state();
        var grant = lost.acquire(operation);
        assertEquals(acquiredBeforeRetry, state());
        assertEquals(1L, generation());
        assertThrows(BusinessException.class, () -> service.renew(grant));
        assertThrows(BusinessException.class, () -> lost.acquire(lost.beginAcquire(jobId)));
        assertTrue(jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND,CURRENT_TIMESTAMP(6),lease_expires_at) BETWEEN 25 AND 30 FROM t_import_job WHERE tenant_id=? AND id=?", Boolean.class, context.tenantId(), jobId));

        lostManager.loseNextCommit();
        assertThrows(TransactionSystemException.class, () -> lost.renew(grant));
        var renewedBeforeRetry = state();
        var renewed = lost.renew(grant);
        assertEquals(renewedBeforeRetry, state());
        assertEquals(1L, generation());
        assertTrue(jdbc.queryForObject("SELECT lease_expires_at > CURRENT_TIMESTAMP(6) FROM t_import_job WHERE tenant_id=? AND id=?", Boolean.class, context.tenantId(), jobId));
        jdbc.update("UPDATE t_import_job SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE tenant_id=? AND id=?", context.tenantId(), jobId);
        var expiredState = state();
        assertThrows(BusinessException.class, () -> lost.renew(renewed));
        assertEquals(expiredState, state());
        var restarted = new ImportValidationLeaseService(jdbc, mockAuthorization(), mock(DocumentService.class),
                new CommitResponseLostManager(jdbc.getDataSource()));
        var takeover = restarted.beginTakeover(jobId);
        assertThrows(TransactionSystemException.class, () -> restarted.takeOver(takeover));
        var takeoverBeforeRetry = state();
        restarted.takeOver(takeover);
        assertEquals(takeoverBeforeRetry, state());
        restarted.takeOver(takeover);
        assertEquals(2L, generation());
        var takenOverState = state();
        assertThrows(BusinessException.class, () -> lost.renew(renewed));
        assertEquals(takenOverState, state());
    }

    @Test
    void nonMappedJobOrRunPointerCannotAcquire() {
        jdbc.update("UPDATE t_import_job SET state='BLOCKED' WHERE tenant_id=? AND id=?", context.tenantId(), jobId);
        assertEquals(state(), assertRejectedWithoutWrite(() -> service.beginAcquire(jobId)));
        jdbc.update("UPDATE t_import_job SET state='MAPPED',active_validation_run_id=12 WHERE tenant_id=? AND id=?", context.tenantId(), jobId);
        assertEquals(state(), assertRejectedWithoutWrite(() -> service.beginAcquire(jobId)));
        jdbc.update("UPDATE t_import_job SET active_validation_run_id=NULL,current_validation_run_id=12 WHERE tenant_id=? AND id=?", context.tenantId(), jobId);
        assertEquals(state(), assertRejectedWithoutWrite(() -> service.beginAcquire(jobId)));
    }

    @Test
    void sourceAndDocumentSnapshotDriftAreRejectedWithoutLeaseWrites() {
        var op = service.beginAcquire(jobId);
        var before = state();
        jdbc.update("UPDATE t_import_source_system SET version_no=version_no+1 WHERE tenant_id=? AND id=2", context.tenantId());
        var sourceOp = op;
        assertThrows(BusinessException.class, () -> service.acquire(sourceOp));
        assertEquals(before, state());

        setUp();
        op = service.beginAcquire(jobId);
        before = state();
        jdbc.update("UPDATE t_import_source_system SET status='DISABLED' WHERE tenant_id=? AND id=2", context.tenantId());
        var inactiveSourceOp = op;
        var inactiveSourceState = state();
        assertEquals(inactiveSourceState, assertRejectedWithoutWrite(() -> service.acquire(inactiveSourceOp)));

        setUp();
        op = service.beginAcquire(jobId);
        before = state();
        jdbc.update("UPDATE t_document SET legal_hold_flag=1 WHERE tenant_id=? AND id=3", context.tenantId());
        var holdOp = op;
        var holdBefore = before;
        assertEquals(holdBefore, assertRejectedWithoutWrite(() -> service.acquire(holdOp)));

        setUp();
        op = service.beginAcquire(jobId);
        before = state();
        jdbc.update("UPDATE t_document_version SET sha256=? WHERE tenant_id=? AND id=4", HexFormat.of().formatHex(hash("different")), context.tenantId());
        var versionOp = op;
        var versionBefore = before;
        assertEquals(versionBefore, assertRejectedWithoutWrite(() -> service.acquire(versionOp)));

        setUp();
        op = service.beginAcquire(jobId);
        before = state();
        jdbc.update("UPDATE t_import_job SET document_sha256=? WHERE tenant_id=? AND id=?", hash("different document hash"), context.tenantId(), jobId);
        var documentHashOp = op;
        var documentHashBefore = state();
        assertEquals(documentHashBefore, assertRejectedWithoutWrite(() -> service.acquire(documentHashOp)));
        assertNotEquals(before, documentHashBefore);

        setUp();
        op = service.beginAcquire(jobId);
        before = state();
        jdbc.update("DELETE FROM t_document_link WHERE tenant_id=? AND target_id=?", context.tenantId(), jobId);
        var linkOp = op;
        var linkBefore = before;
        assertEquals(linkBefore, assertRejectedWithoutWrite(() -> service.acquire(linkOp)));

        setUp();
        op = service.beginAcquire(jobId);
        before = state();
        jdbc.update("UPDATE t_import_job SET version_no=version_no+1 WHERE tenant_id=? AND id=?", context.tenantId(), jobId);
        var jobOp = op;
        var jobBefore = state();
        assertEquals(jobBefore, assertRejectedWithoutWrite(() -> service.acquire(jobOp)));
        assertNotEquals(before.version(), jobBefore.version());
    }

    @Test
    void authRevocationAfterLocksAndImmediatelyBeforeCasWritesNothing() {
        for (int deniedCall : new int[]{3, 4}) {
            var auth = mock(ImportMetadataAuthorizationService.class);
            var calls = new AtomicInteger();
            when(auth.requireCurrentAuthorization()).thenAnswer(invocation -> {
                if (calls.incrementAndGet() == deniedCall) throw BusinessException.of(403, "error.forbidden");
                return context;
            });
            var guarded = new ImportValidationLeaseService(jdbc, auth, mock(DocumentService.class),
                    new DataSourceTransactionManager(jdbc.getDataSource()));
            var op = guarded.beginAcquire(jobId);
            var before = state();
            assertThrows(BusinessException.class, () -> guarded.acquire(op));
            assertEquals(deniedCall, calls.get());
            assertEquals(before, state());
            setUp();
        }
    }

    @Test
    void failedLeaseUpdateRollsBackOwnerGenerationExpiryAndVersion() {
        jdbc.execute("ALTER TABLE t_import_job ADD CONSTRAINT ck_test_reject_lease CHECK (lease_owner IS NULL)");
        var op = service.beginAcquire(jobId);
        var before = state();
        assertThrows(DataIntegrityViolationException.class, () -> service.acquire(op));
        assertEquals(before, state());
    }

    @Test
    void unexpiredLeaseCannotBeTakenOver() {
        var grant = service.acquire(service.beginAcquire(jobId));
        var before = state();
        assertThrows(BusinessException.class, () -> service.beginTakeover(jobId));
        assertEquals(before, state());
        assertNotNull(grant);
    }

    private ImportMetadataAuthorizationService mockAuthorization() {
        var authorization = mock(ImportMetadataAuthorizationService.class);
        when(authorization.requireCurrentAuthorization()).thenReturn(context);
        return authorization;
    }

    private long generation() {
        return jdbc.queryForObject("SELECT lease_generation FROM t_import_job WHERE tenant_id=? AND id=?", Long.class, context.tenantId(), jobId);
    }

    private LeaseState state() {
        return jdbc.query("SELECT lease_owner,lease_generation,lease_expires_at,version_no FROM t_import_job WHERE tenant_id=? AND id=?",
                rs -> rs.next() ? new LeaseState(rs.getString(1), rs.getLong(2), rs.getTimestamp(3), rs.getLong(4)) : null,
                context.tenantId(), jobId);
    }

    private LeaseState assertRejectedWithoutWrite(Runnable action) {
        var before = state();
        assertThrows(BusinessException.class, action::run);
        assertEquals(before, state());
        return before;
    }

    private record LeaseState(String owner, long generation, java.sql.Timestamp expiresAt, long version) { }

    private static void createSchema(JdbcTemplate j) {
        j.execute("CREATE TABLE t_import_source_system(tenant_id VARCHAR(100),id BIGINT,version_no BIGINT,status VARCHAR(16),PRIMARY KEY(tenant_id,id))");
        j.execute("CREATE TABLE t_document(tenant_id VARCHAR(100),id BIGINT,legal_entity_id VARCHAR(100),legal_hold_flag INT,retention_until DATE,deleted_flag INT,PRIMARY KEY(tenant_id,id))");
        j.execute("CREATE TABLE t_document_version(tenant_id VARCHAR(100),document_id BIGINT,id BIGINT,version_no INT,sha256 VARCHAR(64),scan_status VARCHAR(16),deleted_flag INT,PRIMARY KEY(tenant_id,id))");
        j.execute("CREATE TABLE t_document_link(tenant_id VARCHAR(100),id BIGINT AUTO_INCREMENT PRIMARY KEY,document_id BIGINT,target_type VARCHAR(32),target_id BIGINT,deleted_flag INT)");
        j.execute("CREATE TABLE t_import_mapping(tenant_id VARCHAR(100),id BIGINT,source_system_id BIGINT,entity_type VARCHAR(24),schema_version VARCHAR(40),mapping_version INT,mapping_sha256 BINARY(32),PRIMARY KEY(tenant_id,id))");
        j.execute("CREATE TABLE t_import_job(tenant_id VARCHAR(100),id BIGINT,source_system_id BIGINT,source_version BIGINT,entity_type VARCHAR(24),schema_version VARCHAR(40),source_encoding VARCHAR(16),source_sha256 BINARY(32),legal_entity_id VARCHAR(100),document_id BIGINT,document_version_id BIGINT,document_version_no INT,document_sha256 BINARY(32),mapping_id BIGINT,mapping_version INT,mapping_sha256 BINARY(32),state VARCHAR(16),version_no BIGINT,lease_owner VARCHAR(128),lease_generation BIGINT,lease_expires_at TIMESTAMP(6),active_validation_run_id BIGINT,current_validation_run_id BIGINT,updated_at TIMESTAMP(6),PRIMARY KEY(tenant_id,id))");
    }

    private static byte[] hash(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static final class CommitResponseLostManager extends DataSourceTransactionManager {
        private final AtomicBoolean lose = new AtomicBoolean(true);
        private CommitResponseLostManager(javax.sql.DataSource source) { super(source); }
        private void loseNextCommit() { lose.set(true); }
        @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
            super.doCommit(status);
            if (lose.compareAndSet(true, false)) throw new TransactionSystemException("commit response lost (test)");
        }
    }
}
