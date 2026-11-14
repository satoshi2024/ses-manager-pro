package com.ses.importcenter.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.service.AuditLogService;
import com.ses.service.DocumentService;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * A1a検証編成・preview・error exportのfast test用H2 fixture。lifecycle・append・mapping束縛は実実装、
 * 認可とsource原本の取得（{@link ImportMetadataService#openSource}）だけを差し替える。
 * 合成CSVだけを使い、実データは使わない。
 */
final class ImportA1aH2Fixture {
    static final String TENANT = "a1a-h2";
    static final long ACTOR = 41;
    static final long LEGAL_ENTITY = 77;
    static final Map<String, String> CUSTOMER_MAPPING = mapping("externalKey", "companyName", "contactPerson",
            "contactEmail", "contactPhone", "address");

    final JdbcTemplate jdbc;
    final ImportAuthorizedContext context = new ImportAuthorizedContext(TENANT, ACTOR, LEGAL_ENTITY);
    final ImportMetadataAuthorizationService authorization;
    final ImportMetadataService metadata;
    final ImportValidationLifecycleService lifecycle;
    final ImportValidationAppendService append;
    final AuditLogService audit;
    final ObjectProvider<AuditLogService> auditProvider;
    final AtomicLong sourceOpens = new AtomicLong();
    private final Map<Long, byte[]> sources = new java.util.concurrent.ConcurrentHashMap<>();
    private long sequence = 1000;

    @SuppressWarnings("unchecked")
    ImportA1aH2Fixture() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:a1a_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        schema(jdbc);
        authorization = mock(ImportMetadataAuthorizationService.class);
        when(authorization.requireCurrentAuthorization()).thenReturn(context);
        when(authorization.requireCurrentAuthorizationForWrite(context)).thenReturn(context);
        ImportIdentityKeyProvider keys = mock(ImportIdentityKeyProvider.class);
        when(keys.currentLookupKeyVersion()).thenReturn("a1a-test-v1");
        when(keys.lookupKey("a1a-test-v1")).thenAnswer(invocation -> new byte[32]);
        DocumentService documents = mock(DocumentService.class);
        metadata = spy(new ImportMetadataService(jdbc, authorization, keys, documents));
        doAnswer(invocation -> {
            long jobId = invocation.getArgument(0);
            byte[] bytes = sources.get(jobId);
            if (bytes == null) throw new IllegalStateException("synthetic source is missing");
            sourceOpens.incrementAndGet();
            return new ImportSourceContent(new ByteArrayInputStream(bytes), "customer.csv", ImportEncoding.UTF8,
                    ImportEntity.CUSTOMER, HexFormat.of().formatHex(sha256(bytes)));
        }).when(metadata).openSource(anyLong());
        lifecycle = new ImportValidationLifecycleService(jdbc, authorization, documents,
                new DataSourceTransactionManager(dataSource));
        append = new ImportValidationAppendService(jdbc, metadata, lifecycle, new ObjectMapper());
        audit = mock(AuditLogService.class);
        auditProvider = mock(ObjectProvider.class);
        when(auditProvider.getIfAvailable()).thenReturn(audit);
    }

    ImportValidationRunService runService(LongSupplier clock) {
        return new ImportValidationRunService(authorization, metadata, lifecycle, append, jdbc, clock,
                java.time.Duration.ofSeconds(120));
    }

    ImportValidationResultService resultService(LongSupplier clock) {
        return new ImportValidationResultService(authorization, metadata, jdbc, new ObjectMapper(), auditProvider,
                clock, java.time.Duration.ofSeconds(120));
    }

    long createSource(String code) {
        jdbc.update("""
                INSERT INTO t_import_source_system(tenant_id, source_code, display_name, status, version_no,
                  lookup_key_version, created_by, updated_by) VALUES(?,?,?,'ACTIVE',1,'a1a-test-v1',?,?)
                """, TENANT, code, "合成source " + code, ACTOR, ACTOR);
        return jdbc.queryForObject("SELECT id FROM t_import_source_system WHERE tenant_id=? AND source_code=?",
                Long.class, TENANT, code);
    }

    long createMapping(long sourceId, Map<String, String> mapping) {
        byte[] bytes = ImportMetadataService.canonicalMapping(mapping);
        Integer next = jdbc.queryForObject("SELECT COALESCE(MAX(mapping_version),0)+1 FROM t_import_mapping "
                + "WHERE tenant_id=? AND source_system_id=?", Integer.class, TENANT, sourceId);
        jdbc.update("""
                INSERT INTO t_import_mapping(tenant_id, source_system_id, entity_type, schema_version,
                  mapping_version, canonical_bytes, mapping_sha256, created_by)
                VALUES(?,?,'CUSTOMER','ses-import-v1',?,?,?,?)
                """, TENANT, sourceId, next, bytes, sha256(bytes), ACTOR);
        return jdbc.queryForObject("SELECT MAX(id) FROM t_import_mapping WHERE tenant_id=? AND source_system_id=?",
                Long.class, TENANT, sourceId);
    }

    /** UPLOADED（mapping未束縛）のjobを作る。{@code legalEntity}は文書とjobの法人。 */
    long uploadedJob(long sourceId, String csv, long legalEntity) {
        long jobId = ++sequence;
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        byte[] sha = sha256(bytes);
        long documentId = jobId * 10 + 1;
        long versionId = jobId * 10 + 2;
        jdbc.update("INSERT INTO t_document(tenant_id,id,legal_entity_id,legal_hold_flag,retention_until,deleted_flag) "
                + "VALUES(?,?,?,0,NULL,0)", TENANT, documentId, Long.toString(legalEntity));
        jdbc.update("INSERT INTO t_document_version(tenant_id,document_id,id,version_no,sha256,scan_status,deleted_flag) "
                + "VALUES(?,?,?,1,?,'CLEAN',0)", TENANT, documentId, versionId, HexFormat.of().formatHex(sha));
        jdbc.update("INSERT INTO t_document_link(tenant_id,document_id,target_type,target_id,deleted_flag) "
                + "VALUES(?,?,'IMPORT_JOB',?,0)", TENANT, documentId, jobId);
        jdbc.update("""
                INSERT INTO t_import_job(tenant_id,id,source_system_id,source_version,entity_type,schema_version,
                  source_encoding,source_sha256,legal_entity_id,document_id,document_version_id,document_version_no,
                  document_sha256,state,version_no,lease_generation)
                VALUES(?,?,?,1,'CUSTOMER','ses-import-v1','UTF8',?,?,?,?,1,?,'UPLOADED',1,0)
                """, TENANT, jobId, sourceId, sha, Long.toString(legalEntity), documentId, versionId, sha);
        sources.put(jobId, bytes);
        return jobId;
    }

    String jobState(long jobId) {
        return jdbc.queryForObject("SELECT state FROM t_import_job WHERE tenant_id=? AND id=?", String.class,
                TENANT, jobId);
    }

    int runCount(long jobId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_run WHERE tenant_id=? AND job_id=?",
                Integer.class, TENANT, jobId);
    }

    static Map<String, String> mapping(String... headers) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (String header : headers) mapping.put(header, header);
        return mapping;
    }

    static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 120秒の総期限に対し、{@code expireAfterCalls}回目以降の読取で期限切れになるclock。 */
    static LongSupplier clockExpiringAfter(int expireAfterCalls) {
        AtomicLong calls = new AtomicLong();
        long base = 1_000_000_000_000L;
        return () -> calls.incrementAndGet() > expireAfterCalls ? base + 200_000_000_000L : base;
    }

    private static void schema(JdbcTemplate j) {
        j.execute("CREATE TABLE t_import_source_system(id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id VARCHAR(100),"
                + "source_code VARCHAR(64),display_name VARCHAR(120),status VARCHAR(16),version_no BIGINT,"
                + "lookup_key_version VARCHAR(64),created_by BIGINT,updated_by BIGINT,"
                + "updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP,UNIQUE(tenant_id,source_code),UNIQUE(tenant_id,id))");
        j.execute("CREATE TABLE t_document(tenant_id VARCHAR(100),id BIGINT,legal_entity_id VARCHAR(100),legal_hold_flag INT,retention_until DATE,deleted_flag INT,PRIMARY KEY(tenant_id,id))");
        j.execute("CREATE TABLE t_document_version(tenant_id VARCHAR(100),document_id BIGINT,id BIGINT,version_no INT,sha256 VARCHAR(64),scan_status VARCHAR(16),deleted_flag INT,PRIMARY KEY(tenant_id,id))");
        j.execute("CREATE TABLE t_document_link(tenant_id VARCHAR(100),id BIGINT AUTO_INCREMENT PRIMARY KEY,document_id BIGINT,target_type VARCHAR(32),target_id BIGINT,deleted_flag INT)");
        j.execute("CREATE TABLE t_import_mapping(id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id VARCHAR(100),source_system_id BIGINT,entity_type VARCHAR(24),schema_version VARCHAR(40),mapping_version INT,canonical_bytes BLOB,mapping_sha256 BINARY(32),created_by BIGINT,UNIQUE(tenant_id,source_system_id,entity_type,schema_version,mapping_version))");
        j.execute("CREATE TABLE t_import_job(tenant_id VARCHAR(100),id BIGINT,source_system_id BIGINT,source_version BIGINT,entity_type VARCHAR(24),schema_version VARCHAR(40),source_encoding VARCHAR(16),source_sha256 BINARY(32),legal_entity_id VARCHAR(100),document_id BIGINT,document_version_id BIGINT,document_version_no INT,document_sha256 BINARY(32),mapping_id BIGINT,mapping_version INT,mapping_sha256 BINARY(32),state VARCHAR(16),version_no BIGINT,lease_owner VARCHAR(128),lease_generation BIGINT,lease_expires_at TIMESTAMP(6),active_validation_run_id BIGINT,current_validation_run_id BIGINT,updated_at TIMESTAMP(6),PRIMARY KEY(id),UNIQUE(tenant_id,id))");
        j.execute("CREATE TABLE t_import_validation_run(id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id VARCHAR(100),job_id BIGINT,run_no INT,state VARCHAR(16),created_lease_generation BIGINT,last_resumed_lease_generation BIGINT,active_slot INT,published_slot INT,actor_user_id BIGINT,source_system_id BIGINT,source_version BIGINT,source_code_snapshot VARCHAR(64),entity_type VARCHAR(24),schema_version VARCHAR(32),source_encoding VARCHAR(16),source_sha256 BINARY(32),document_id BIGINT,document_version_id BIGINT,document_version_no INT,document_sha256 BINARY(32),mapping_id BIGINT,mapping_version INT,mapping_sha256 BINARY(32),validation_fingerprint BINARY(32),source_row_count BIGINT,physical_row_count BIGINT,empty_row_count BIGINT,accepted_row_count BIGINT,rejected_row_count BIGINT,finding_summary_bytes VARBINARY(8192),finding_summary_sha256 BINARY(32),expected_unit_price_accepted_known_count BIGINT,expected_unit_price_accepted_sum_yen DECIMAL(38,0),expected_unit_price_rejected_known_count BIGINT,expected_unit_price_rejected_sum_yen DECIMAL(38,0),expected_unit_price_excluded_count BIGINT,unit_price_min_accepted_known_count BIGINT,unit_price_min_accepted_sum_yen DECIMAL(38,0),unit_price_min_rejected_known_count BIGINT,unit_price_min_rejected_sum_yen DECIMAL(38,0),unit_price_min_excluded_count BIGINT,unit_price_max_accepted_known_count BIGINT,unit_price_max_accepted_sum_yen DECIMAL(38,0),unit_price_max_rejected_known_count BIGINT,unit_price_max_rejected_sum_yen DECIMAL(38,0),unit_price_max_excluded_count BIGINT,selling_price_accepted_known_count BIGINT,selling_price_accepted_sum_yen DECIMAL(38,0),selling_price_rejected_known_count BIGINT,selling_price_rejected_sum_yen DECIMAL(38,0),selling_price_excluded_count BIGINT,cost_price_accepted_known_count BIGINT,cost_price_accepted_sum_yen DECIMAL(38,0),cost_price_rejected_known_count BIGINT,cost_price_rejected_sum_yen DECIMAL(38,0),cost_price_excluded_count BIGINT,amount_excluded_reason_counts VARBINARY(8192),amount_excluded_reason_sha256 BINARY(32),row_set_sha256 BINARY(32),summary_sha256 BINARY(32),started_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP,sealed_at TIMESTAMP(6) NULL,completed_at TIMESTAMP(6) NULL,UNIQUE(tenant_id,id),UNIQUE(tenant_id,job_id,id),UNIQUE(tenant_id,job_id,run_no),UNIQUE(tenant_id,job_id,active_slot),UNIQUE(tenant_id,job_id,published_slot))");
        j.execute("CREATE TABLE t_import_validation_row(tenant_id VARCHAR(100),job_id BIGINT,validation_run_id BIGINT,source_row_number BIGINT,source_row_sha256 BINARY(32),canonical_fingerprint BINARY(32),disposition VARCHAR(16),finding_codes VARBINARY(4096),row_commit_sha256 BINARY(32),PRIMARY KEY(tenant_id,validation_run_id,source_row_number))");
    }
}
