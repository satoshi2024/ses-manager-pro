package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;
import com.ses.service.DocumentService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImportValidationLifecycleServiceTest {
    private static final String TENANT = "lifecycle-h2";
    private static final long JOB = 11L;
    private static final byte[] SOURCE = hash("source");
    private static final byte[] MAPPING = "{\"externalKey\":\"externalKey\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] MAPPING_HASH = hash(MAPPING);
    private final ImportAuthorizedContext context = new ImportAuthorizedContext(TENANT, 41, 77);
    private JdbcTemplate jdbc;
    private ImportValidationLifecycleService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:lifecycle_" + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        schema(jdbc);
        jdbc.update("INSERT INTO t_import_source_system VALUES(?,?,?,?,?)", TENANT, 2L, 3L, "source", "ACTIVE");
        jdbc.update("INSERT INTO t_document VALUES(?,?,?,?,?,?)", TENANT, 3L, "77", 0, null, 0);
        jdbc.update("INSERT INTO t_document_version VALUES(?,?,?,?,?,?,?)", TENANT, 3L, 4L, 1,
                HexFormat.of().formatHex(SOURCE), "CLEAN", 0);
        jdbc.update("INSERT INTO t_document_link(tenant_id,document_id,target_type,target_id,deleted_flag) VALUES(?,?,?,?,0)",
                TENANT, 3L, "IMPORT_JOB", JOB);
        jdbc.update("INSERT INTO t_import_mapping VALUES(?,?,?,?,?,?,?,?)", TENANT, 5L, 2L, "CUSTOMER",
                "ses-import-v1", 1, MAPPING_HASH, MAPPING);
        jdbc.update("""
                INSERT INTO t_import_job(tenant_id,id,source_system_id,source_version,entity_type,schema_version,
                  source_encoding,source_sha256,legal_entity_id,document_id,document_version_id,document_version_no,
                  document_sha256,mapping_id,mapping_version,mapping_sha256,state,version_no,lease_generation)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'MAPPED',1,0)
                """, TENANT, JOB, 2L, 3L, "CUSTOMER", "ses-import-v1", "UTF8", SOURCE, "77", 3L, 4L, 1,
                SOURCE, 5L, 1, MAPPING_HASH);
        service = new ImportValidationLifecycleService(jdbc, mockAuthorization(), mock(DocumentService.class),
                new DataSourceTransactionManager(ds));
    }

    @Test
    void startRetryRunAwareTakeoverRenewAndAbortAreAtomicAndFenced() {
        var operation = service.beginStart(JOB);
        var lease = service.start(operation);
        var started = state();
        assertEquals("VALIDATING", started.state);
        assertEquals(1L, started.generation);
        assertEquals(lease.runId(), started.activeRun);
        assertNull(started.currentRun);
        assertEquals(lease.runId(), service.start(operation).runId(), "同operation retryは同じrunへ収束する");
        assertEquals(started, state());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_run WHERE tenant_id=? AND job_id=?", Integer.class, TENANT, JOB));

        var renewed = service.renew(lease);
        assertEquals(1L, renewed.generation());
        assertTrue(renewed.expiresAt().after(lease.expiresAt()));
        jdbc.update("UPDATE t_import_job SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE tenant_id=? AND id=?", TENANT, JOB);
        var takeover = service.beginTakeover(JOB, lease.runId());
        var taken = service.takeOver(takeover);
        assertEquals(2L, taken.generation());
        assertEquals(2L, state().generation);
        assertEquals(2L, jdbc.queryForObject("SELECT last_resumed_lease_generation FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?", Long.class, TENANT, JOB, lease.runId()));
        assertThrows(BusinessException.class, () -> service.abort(renewed));

        service.abort(taken);
        var aborted = state();
        assertEquals("MAPPED", aborted.state);
        assertNull(aborted.owner);
        assertNull(aborted.expiresAt);
        assertNull(aborted.activeRun);
        assertNull(aborted.currentRun);
        assertEquals(2L, aborted.generation);
        assertEquals("ABORTED", jdbc.queryForObject("SELECT state FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=?", String.class, TENANT, JOB, lease.runId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_run WHERE tenant_id=? AND job_id=?", Integer.class, TENANT, JOB));
        assertThrows(BusinessException.class, () -> service.abort(taken));
    }

    @Test
    void validatedRestartUnpublishesOldResultAndCreatesNewRunInSameTransaction() {
        long oldRun = insertCompletedRun();
        jdbc.update("UPDATE t_import_job SET state='VALIDATED',current_validation_run_id=?,version_no=version_no+1 WHERE tenant_id=? AND id=?",
                oldRun, TENANT, JOB);
        var lease = service.start(service.beginStart(JOB));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_run WHERE tenant_id=? AND job_id=?", Integer.class, TENANT, JOB));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=? AND state='COMPLETED' AND published_slot IS NULL", Integer.class, TENANT, JOB, oldRun));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_job WHERE tenant_id=? AND id=? AND state='VALIDATING' AND current_validation_run_id IS NULL AND active_validation_run_id=?", Integer.class, TENANT, JOB, lease.runId()));
        service.abort(lease);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=? AND state='ABORTED'", Integer.class, TENANT, JOB, lease.runId()));
    }

    @Test
    void authorizationGateDenialLeavesJobAndRunUnchanged() {
        var deniedAuthorization = mock(ImportMetadataAuthorizationService.class);
        when(deniedAuthorization.requireCurrentAuthorization()).thenReturn(context);
        when(deniedAuthorization.requireCurrentAuthorizationForWrite(any()))
                .thenThrow(BusinessException.of(403, "error.forbidden"));
        var denied = new ImportValidationLifecycleService(jdbc, deniedAuthorization, mock(DocumentService.class),
                new DataSourceTransactionManager(jdbc.getDataSource()));
        var operation = denied.beginStart(JOB);
        var before = state();
        assertThrows(BusinessException.class, () -> denied.start(operation));
        assertEquals(before, state());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_run WHERE tenant_id=? AND job_id=?", Integer.class, TENANT, JOB));
    }

    @Test
    void lostCommitResponseRetryReturnsTheCreatedRunWithoutAnotherGeneration() {
        var manager = new LostCommitManager(jdbc.getDataSource());
        var lostResponseService = new ImportValidationLifecycleService(jdbc, mockAuthorization(), mock(DocumentService.class), manager);
        var operation = lostResponseService.beginStart(JOB);
        assertThrows(TransactionSystemException.class, () -> lostResponseService.start(operation));
        var afterCommit = state();
        var retried = lostResponseService.start(operation);
        assertEquals(afterCommit.activeRun, retried.runId());
        assertEquals(afterCommit, state());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_run WHERE tenant_id=? AND job_id=?", Integer.class, TENANT, JOB));
    }

    private long insertCompletedRun() {
        byte[] fingerprint = hash("validator");
        jdbc.update("""
                INSERT INTO t_import_validation_run(tenant_id,job_id,run_no,state,created_lease_generation,
                  last_resumed_lease_generation,active_slot,published_slot,actor_user_id,source_system_id,source_version,
                  source_code_snapshot,entity_type,schema_version,source_encoding,source_sha256,document_id,
                  document_version_id,document_version_no,document_sha256,mapping_id,mapping_version,mapping_sha256,
                  validation_fingerprint)
                VALUES(?,?,1,'COMPLETED',1,1,NULL,1,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, TENANT, JOB, 41L, 2L, 3L, "source", "CUSTOMER", "ses-import-v1", "UTF8", SOURCE,
                3L, 4L, 1, SOURCE, 5L, 1, MAPPING_HASH, fingerprint);
        long id = jdbc.queryForObject("SELECT id FROM t_import_validation_run WHERE tenant_id=? AND job_id=?", Long.class, TENANT, JOB);
        return id;
    }

    private ImportMetadataAuthorizationService mockAuthorization() {
        var auth = mock(ImportMetadataAuthorizationService.class);
        when(auth.requireCurrentAuthorization()).thenReturn(context);
        when(auth.requireCurrentAuthorizationForWrite(any())).thenReturn(context);
        return auth;
    }

    private State state() {
        return jdbc.query("SELECT state,version_no,lease_owner,lease_generation,lease_expires_at,active_validation_run_id,current_validation_run_id FROM t_import_job WHERE tenant_id=? AND id=?",
                rs -> rs.next() ? new State(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getLong(4),
                        rs.getTimestamp(5), rs.getObject(6, Long.class), rs.getObject(7, Long.class)) : null, TENANT, JOB);
    }

    private static void schema(JdbcTemplate j) {
        j.execute("CREATE TABLE t_import_source_system(tenant_id VARCHAR(100),id BIGINT,version_no BIGINT,source_code VARCHAR(64),status VARCHAR(16),PRIMARY KEY(tenant_id,id))");
        j.execute("CREATE TABLE t_document(tenant_id VARCHAR(100),id BIGINT,legal_entity_id VARCHAR(100),legal_hold_flag INT,retention_until DATE,deleted_flag INT,PRIMARY KEY(tenant_id,id))");
        j.execute("CREATE TABLE t_document_version(tenant_id VARCHAR(100),document_id BIGINT,id BIGINT,version_no INT,sha256 VARCHAR(64),scan_status VARCHAR(16),deleted_flag INT,PRIMARY KEY(tenant_id,id))");
        j.execute("CREATE TABLE t_document_link(tenant_id VARCHAR(100),id BIGINT AUTO_INCREMENT PRIMARY KEY,document_id BIGINT,target_type VARCHAR(32),target_id BIGINT,deleted_flag INT)");
        j.execute("CREATE TABLE t_import_mapping(tenant_id VARCHAR(100),id BIGINT,source_system_id BIGINT,entity_type VARCHAR(24),schema_version VARCHAR(40),mapping_version INT,mapping_sha256 BINARY(32),canonical_bytes BLOB,PRIMARY KEY(tenant_id,id))");
        j.execute("CREATE TABLE t_import_job(tenant_id VARCHAR(100),id BIGINT,source_system_id BIGINT,source_version BIGINT,entity_type VARCHAR(24),schema_version VARCHAR(40),source_encoding VARCHAR(16),source_sha256 BINARY(32),legal_entity_id VARCHAR(100),document_id BIGINT,document_version_id BIGINT,document_version_no INT,document_sha256 BINARY(32),mapping_id BIGINT,mapping_version INT,mapping_sha256 BINARY(32),state VARCHAR(16),version_no BIGINT,lease_owner VARCHAR(128),lease_generation BIGINT,lease_expires_at TIMESTAMP(6),active_validation_run_id BIGINT,current_validation_run_id BIGINT,updated_at TIMESTAMP(6),PRIMARY KEY(id),UNIQUE(tenant_id,id))");
        j.execute("CREATE TABLE t_import_validation_run(id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id VARCHAR(100),job_id BIGINT,run_no INT,state VARCHAR(16),created_lease_generation BIGINT,last_resumed_lease_generation BIGINT,active_slot INT,published_slot INT,actor_user_id BIGINT,source_system_id BIGINT,source_version BIGINT,source_code_snapshot VARCHAR(64),entity_type VARCHAR(24),schema_version VARCHAR(32),source_encoding VARCHAR(16),source_sha256 BINARY(32),document_id BIGINT,document_version_id BIGINT,document_version_no INT,document_sha256 BINARY(32),mapping_id BIGINT,mapping_version INT,mapping_sha256 BINARY(32),validation_fingerprint BINARY(32),source_row_count BIGINT,physical_row_count BIGINT,empty_row_count BIGINT,accepted_row_count BIGINT,rejected_row_count BIGINT,finding_summary_bytes VARBINARY(8192),finding_summary_sha256 BINARY(32),expected_unit_price_accepted_known_count BIGINT,expected_unit_price_accepted_sum_yen DECIMAL(38,0),expected_unit_price_rejected_known_count BIGINT,expected_unit_price_rejected_sum_yen DECIMAL(38,0),expected_unit_price_excluded_count BIGINT,unit_price_min_accepted_known_count BIGINT,unit_price_min_accepted_sum_yen DECIMAL(38,0),unit_price_min_rejected_known_count BIGINT,unit_price_min_rejected_sum_yen DECIMAL(38,0),unit_price_min_excluded_count BIGINT,unit_price_max_accepted_known_count BIGINT,unit_price_max_accepted_sum_yen DECIMAL(38,0),unit_price_max_rejected_known_count BIGINT,unit_price_max_rejected_sum_yen DECIMAL(38,0),unit_price_max_excluded_count BIGINT,selling_price_accepted_known_count BIGINT,selling_price_accepted_sum_yen DECIMAL(38,0),selling_price_rejected_known_count BIGINT,selling_price_rejected_sum_yen DECIMAL(38,0),selling_price_excluded_count BIGINT,cost_price_accepted_known_count BIGINT,cost_price_accepted_sum_yen DECIMAL(38,0),cost_price_rejected_known_count BIGINT,cost_price_rejected_sum_yen DECIMAL(38,0),cost_price_excluded_count BIGINT,amount_excluded_reason_counts VARBINARY(8192),amount_excluded_reason_sha256 BINARY(32),row_set_sha256 BINARY(32),summary_sha256 BINARY(32),started_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP,UNIQUE(tenant_id,id),UNIQUE(tenant_id,job_id,id),UNIQUE(tenant_id,job_id,run_no),UNIQUE(tenant_id,job_id,active_slot),UNIQUE(tenant_id,job_id,published_slot),FOREIGN KEY(tenant_id,job_id) REFERENCES t_import_job(tenant_id,id),CHECK((state IN('BUILDING','SEALED') AND active_slot=1 AND published_slot IS NULL) OR (state='COMPLETED' AND active_slot IS NULL AND (published_slot IS NULL OR published_slot=1)) OR (state='ABORTED' AND active_slot IS NULL AND published_slot IS NULL)))");
        j.execute("ALTER TABLE t_import_job ADD CONSTRAINT fk_active_run FOREIGN KEY(tenant_id,id,active_validation_run_id) REFERENCES t_import_validation_run(tenant_id,job_id,id)");
        j.execute("ALTER TABLE t_import_job ADD CONSTRAINT fk_current_run FOREIGN KEY(tenant_id,id,current_validation_run_id) REFERENCES t_import_validation_run(tenant_id,job_id,id)");
    }

    private static byte[] hash(String value) { return hash(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static byte[] hash(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private record State(String state, long version, String owner, long generation, Timestamp expiresAt,
                         Long activeRun, Long currentRun) { }

    private static final class LostCommitManager extends DataSourceTransactionManager {
        private final AtomicBoolean lose = new AtomicBoolean(true);
        private LostCommitManager(DataSource source) { super(source); }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            super.doCommit(status);
            if (lose.compareAndSet(true, false)) throw new TransactionSystemException("commit response lost (test)");
        }
    }
}
