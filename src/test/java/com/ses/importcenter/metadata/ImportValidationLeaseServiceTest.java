package com.ses.importcenter.metadata;

import com.ses.service.DocumentService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionSystemException;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

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
        var lost = new ImportValidationLeaseService(jdbc, mockAuthorization(), mock(DocumentService.class),
                new CommitResponseLostManager(jdbc.getDataSource()));
        var operation = lost.beginAcquire(jobId);
        assertThrows(TransactionSystemException.class, () -> lost.acquire(operation));
        var grant = lost.acquire(operation);
        assertEquals(1L, generation());
        assertThrows(RuntimeException.class, () -> service.renew(grant));
        assertThrows(RuntimeException.class, () -> lost.acquire(lost.beginAcquire(jobId)));

        var renewed = lost.renew(grant);
        assertEquals(1L, generation());
        assertTrue(jdbc.queryForObject("SELECT lease_expires_at > CURRENT_TIMESTAMP(6) FROM t_import_job WHERE tenant_id=? AND id=?", Boolean.class, context.tenantId(), jobId));
        jdbc.update("UPDATE t_import_job SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE tenant_id=? AND id=?", context.tenantId(), jobId);
        var takeover = lost.beginTakeover(renewed);
        lost.takeOver(takeover);
        lost.takeOver(takeover);
        assertEquals(2L, generation());
        assertThrows(RuntimeException.class, () -> lost.renew(renewed));
    }

    @Test
    void nonMappedJobOrRunPointerCannotAcquire() {
        jdbc.update("UPDATE t_import_job SET state='BLOCKED' WHERE tenant_id=? AND id=?", context.tenantId(), jobId);
        assertThrows(RuntimeException.class, () -> service.beginAcquire(jobId));
        jdbc.update("UPDATE t_import_job SET state='MAPPED',current_validation_run_id=12 WHERE tenant_id=? AND id=?", context.tenantId(), jobId);
        assertThrows(RuntimeException.class, () -> service.beginAcquire(jobId));
        assertEquals(0L, generation());
    }

    private ImportMetadataAuthorizationService mockAuthorization() {
        var authorization = mock(ImportMetadataAuthorizationService.class);
        when(authorization.requireCurrentAuthorization()).thenReturn(context);
        return authorization;
    }

    private long generation() {
        return jdbc.queryForObject("SELECT lease_generation FROM t_import_job WHERE tenant_id=? AND id=?", Long.class, context.tenantId(), jobId);
    }

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
        @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
            super.doCommit(status);
            if (lose.compareAndSet(true, false)) throw new TransactionSystemException("commit response lost (test)");
        }
    }
}
