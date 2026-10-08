package com.ses.importcenter.metadata;

import com.ses.service.DocumentService;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionSystemException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 実MySQLのlease CAS/lock競合と応答喪失回復を検証する。 */
@Tag("mysql")
@Testcontainers
class ImportValidationLeaseMySqlTest {
    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("nf06_lease")
            .withUsername("root").withPassword("ses");

    private static final byte[] SOURCE_HASH = hash("exact source bytes");
    private static final byte[] MAPPING_BYTES = "canonical mapping".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] MAPPING_HASH = hash(MAPPING_BYTES);
    private final ImportAuthorizedContext context = new ImportAuthorizedContext("lease-test", 41L, 77L);
    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private ImportValidationLeaseService service;
    private ImportMetadataAuthorizationService authorization;
    private long jobId;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName(MYSQL.getDriverClassName()); ds.setUrl(MYSQL.getJdbcUrl());
        ds.setUsername(MYSQL.getUsername()); ds.setPassword(MYSQL.getPassword());
        dataSource = ds;
        configure(ds);
    }

    private void configure(DataSource ds) throws Exception {
        jdbc = new JdbcTemplate(ds);
        createSchema(jdbc);
        String tenant = context.tenantId();
        jdbc.update("DELETE FROM t_import_job WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM t_import_mapping WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM t_document_link WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM t_document_version WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM t_document WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM t_import_source_system WHERE tenant_id=?", tenant);
        long source = 2L, document = 3L, version = 4L, mapping = 5L;
        jobId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        if (jobId < 1) jobId = 6L;
        jdbc.update("INSERT INTO t_import_source_system VALUES(?,?,?,?)", tenant, source, 8L, "ACTIVE");
        jdbc.update("INSERT INTO t_document VALUES(?,?,?,?,?,?)", tenant, document, "77", 0, null, 0);
        jdbc.update("INSERT INTO t_document_version VALUES(?,?,?,?,?,?,?)", tenant, document, version, 1,
                HexFormat.of().formatHex(SOURCE_HASH), "CLEAN", 0);
        jdbc.update("INSERT INTO t_document_link(tenant_id,document_id,target_type,target_id,deleted_flag) VALUES(?,?,?,?,0)",
                tenant, document, "IMPORT_JOB", jobId);
        jdbc.update("INSERT INTO t_import_mapping VALUES(?,?,?,?,?,?,?)", tenant, mapping, source, "CUSTOMER",
                "ses-import-v1", 1, MAPPING_HASH);
        jdbc.update("""
                INSERT INTO t_import_job(tenant_id,id,source_system_id,source_version,entity_type,schema_version,
                  source_encoding,source_sha256,legal_entity_id,document_id,document_version_id,document_version_no,
                  document_sha256,mapping_id,mapping_version,mapping_sha256,state,version_no,lease_generation)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'MAPPED',1,0)
                """, tenant, jobId, source, 8L, "CUSTOMER", "ses-import-v1", "UTF8", SOURCE_HASH,
                "77", document, version, 1, SOURCE_HASH, mapping, 1, MAPPING_HASH);
        authorization = mock(ImportMetadataAuthorizationService.class);
        when(authorization.requireCurrentAuthorization()).thenReturn(context);
        DocumentService documents = mock(DocumentService.class);
        service = new ImportValidationLeaseService(jdbc, authorization, documents, new DataSourceTransactionManager(ds));
    }

    @Test
    void mysqlLeaseExpiryRenewTakeoverAndSameOperationResponseLossRecovery() {
        var lostManager = new CommitResponseLostManager(dataSource);
        var lost = new ImportValidationLeaseService(jdbc, authorization, mock(DocumentService.class), lostManager);
        var operation = lost.beginAcquire(jobId);
        assertThrows(TransactionSystemException.class, () -> lost.acquire(operation));
        var recovered = lost.acquire(operation);
        assertEquals(1L, jdbc.queryForObject("SELECT lease_generation FROM t_import_job WHERE tenant_id=? AND id=?", Long.class, context.tenantId(), jobId));
        assertTrue(jdbc.queryForObject("SELECT lease_expires_at > CURRENT_TIMESTAMP(6) FROM t_import_job WHERE tenant_id=? AND id=?", Boolean.class, context.tenantId(), jobId));
        assertThrows(RuntimeException.class, () -> lost.acquire(lost.beginAcquire(jobId)));

        var oldGrant = lost.renew(recovered);
        assertEquals(1L, jdbc.queryForObject("SELECT lease_generation FROM t_import_job WHERE tenant_id=? AND id=?", Long.class, context.tenantId(), jobId));
        jdbc.update("UPDATE t_import_job SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE tenant_id=? AND id=?", context.tenantId(), jobId);
        var takeover = lost.beginTakeover(oldGrant);
        var newGrant = lost.takeOver(takeover);
        lost.takeOver(takeover);
        assertEquals(2L, jdbc.queryForObject("SELECT lease_generation FROM t_import_job WHERE tenant_id=? AND id=?", Long.class, context.tenantId(), jobId));
        assertThrows(RuntimeException.class, () -> lost.renew(oldGrant));
        assertNotNull(newGrant);
    }

    @Test
    void mysqlConcurrentAcquireHasOneWinnerAndOneGeneration() throws Exception {
        var first = service.beginAcquire(jobId);
        var second = service.beginAcquire(jobId);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = pool.submit(() -> acquireAtBarrier(first, ready, start));
            Future<Boolean> b = pool.submit(() -> acquireAtBarrier(second, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS), "workerがbarrierへ到達しません");
            start.countDown();
            assertNotEquals(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            assertEquals(1L, jdbc.queryForObject("SELECT lease_generation FROM t_import_job WHERE tenant_id=? AND id=?", Long.class, context.tenantId(), jobId));
        } finally {
            start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void sourceRevocationBeforeTheLeaseTransactionRejectsWithoutWrite() {
        jdbc.update("UPDATE t_import_source_system SET status='DISABLED',version_no=version_no+1 WHERE tenant_id=? AND id=2",
                context.tenantId());
        assertThrows(RuntimeException.class, () -> service.beginAcquire(jobId));
        assertEquals(0L, jdbc.queryForObject("SELECT lease_generation FROM t_import_job WHERE tenant_id=? AND id=?",
                Long.class, context.tenantId(), jobId));
    }

    private boolean acquireAtBarrier(ImportValidationLeaseService.AcquireOperation operation,
                                     CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("bounded barrier timeout");
        try { service.acquire(operation); return true; }
        catch (RuntimeException rejected) { return false; }
    }

    private static void createSchema(JdbcTemplate j) {
        j.execute("CREATE TABLE IF NOT EXISTS t_import_source_system(tenant_id VARCHAR(100),id BIGINT,version_no BIGINT,status VARCHAR(16),PRIMARY KEY(tenant_id,id))");
        j.execute("CREATE TABLE IF NOT EXISTS t_document(tenant_id VARCHAR(100),id BIGINT,legal_entity_id VARCHAR(100),legal_hold_flag INT,retention_until DATE,deleted_flag INT,PRIMARY KEY(tenant_id,id))");
        j.execute("CREATE TABLE IF NOT EXISTS t_document_version(tenant_id VARCHAR(100),document_id BIGINT,id BIGINT,version_no INT,sha256 VARCHAR(64),scan_status VARCHAR(16),deleted_flag INT,PRIMARY KEY(tenant_id,id))");
        j.execute("CREATE TABLE IF NOT EXISTS t_document_link(tenant_id VARCHAR(100),id BIGINT AUTO_INCREMENT PRIMARY KEY,document_id BIGINT,target_type VARCHAR(32),target_id BIGINT,deleted_flag INT)");
        j.execute("CREATE TABLE IF NOT EXISTS t_import_mapping(tenant_id VARCHAR(100),id BIGINT,source_system_id BIGINT,entity_type VARCHAR(24),schema_version VARCHAR(40),mapping_version INT,mapping_sha256 BINARY(32),PRIMARY KEY(tenant_id,id))");
        j.execute("CREATE TABLE IF NOT EXISTS t_import_job(tenant_id VARCHAR(100),id BIGINT,source_system_id BIGINT,source_version BIGINT,entity_type VARCHAR(24),schema_version VARCHAR(40),source_encoding VARCHAR(16),source_sha256 BINARY(32),legal_entity_id VARCHAR(100),document_id BIGINT,document_version_id BIGINT,document_version_no INT,document_sha256 BINARY(32),mapping_id BIGINT,mapping_version INT,mapping_sha256 BINARY(32),state VARCHAR(16),version_no BIGINT,lease_owner VARCHAR(128),lease_generation BIGINT,lease_expires_at TIMESTAMP(6),active_validation_run_id BIGINT,current_validation_run_id BIGINT,updated_at TIMESTAMP(6),PRIMARY KEY(tenant_id,id))");
    }

    private static byte[] hash(String value) { return hash(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static byte[] hash(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static final class CommitResponseLostManager extends DataSourceTransactionManager {
        private final AtomicBoolean lose = new AtomicBoolean(true);
        private CommitResponseLostManager(DataSource source) { super(source); }
        @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
            super.doCommit(status);
            if (lose.compareAndSet(true, false)) throw new TransactionSystemException("commit response lost (test)");
        }
    }
}
