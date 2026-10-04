package com.ses.importcenter.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.entity.SysUser;
import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.service.DocumentService;
import com.ses.service.security.LegalEntityContextService;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.ses.importcenter.foundation.CanonicalImportRowFingerprint;
import com.ses.importcenter.foundation.CanonicalRowFactory;
import com.ses.importcenter.foundation.SourceRow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 実MySQLでcandidate登録のlock順、直列化、reader後の再認可とsnapshot fenceを検証する。 */
@Tag("mysql")
@Testcontainers
class ImportIdentityCandidateMySqlTest {
    private static final String SOURCE_BYTES_TEXT = "externalKey,companyName\ncustomer-1,Example\n";
    private static final byte[] SOURCE_BYTES = SOURCE_BYTES_TEXT.getBytes(StandardCharsets.UTF_8);
    private static final String SOURCE_SHA = sha256(SOURCE_BYTES);
    private static final String MAPPING_JSON = "{\"companyName\":\"companyName\",\"externalKey\":\"externalKey\"}";
    private static final byte[] MAPPING = MAPPING_JSON.getBytes(StandardCharsets.UTF_8);
    private static final String LOOKUP_VERSION = "lookup-test-v1";
    private static final String ENCRYPTION_VERSION = "encryption-test-v1";
    private static final byte[] TEST_KEY = new byte[32];

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("nf06_import_candidate")
            .withUsername("root")
            .withPassword("ses");

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private ImportMetadataService metadata;
    private ImportMetadataAuthorizationService authorization;
    private LegalEntityContextService legalEntityContext;
    private DocumentService documentService;
    private ImportIdentityCandidateService service;
    private String tenant;
    private long sourceId;
    private long jobId;
    private long runId;
    private long documentId;
    private long documentVersionId;
    private org.springframework.security.core.Authentication authentication;
    private Runnable afterSourceRead;
    private CountDownLatch sourceReads;

    @BeforeEach
    void setUp() throws Exception {
        tenant = "candidate-" + UUID.randomUUID().toString().replace("-", "");
        sourceId = 3L; jobId = 9L; runId = 11L; documentId = 21L; documentVersionId = 22L;
        DriverManagerDataSource mysqlDataSource = new DriverManagerDataSource();
        mysqlDataSource.setDriverClassName(MYSQL.getDriverClassName());
        mysqlDataSource.setUrl(MYSQL.getJdbcUrl());
        mysqlDataSource.setUsername(MYSQL.getUsername());
        mysqlDataSource.setPassword(MYSQL.getPassword());
        dataSource = mysqlDataSource;
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("SET GLOBAL time_zone = '+09:00'");
        assertEquals("+09:00", jdbc.queryForObject("SELECT @@session.time_zone", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT CURRENT_DATE = DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+09:00'))",
                Integer.class));
        createSchema();
        seedSnapshot();

        metadata = mock(ImportMetadataService.class);
        legalEntityContext = mock(LegalEntityContextService.class);
        when(legalEntityContext.requireTenantId()).thenReturn(tenant);
        when(legalEntityContext.requireCurrentLegalEntityId()).thenReturn(77L);
        authorization = new ImportMetadataAuthorizationService(jdbc, legalEntityContext);
        documentService = mock(DocumentService.class);
        var crypto = new ImportIdentityCryptoService(new TestKeyProvider());
        var reader = new ImportCandidateSourceReader(metadata, new ObjectMapper());
        service = new ImportIdentityCandidateService(jdbc, authorization, crypto, reader, documentService,
                new DataSourceTransactionManager(dataSource));

        SysUser principalUser = new SysUser();
        principalUser.setId(17L);
        principalUser.setUsername("candidate-admin");
        principalUser.setPassword("test-only");
        principalUser.setRole("管理者");
        principalUser.setTenantId(tenant);
        principalUser.setStatus(1);
        var authority = new SimpleGrantedAuthority("ROLE_管理者");
        authentication = new UsernamePasswordAuthenticationToken(new LoginUser(principalUser, List.of(authority), tenant),
                "test-only", List.of(authority));
        SecurityContext initialContext = SecurityContextHolder.createEmptyContext();
        initialContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(initialContext);

        when(metadata.openSource(jobId)).thenAnswer(invocation -> {
            if (afterSourceRead != null) afterSourceRead.run();
            if (sourceReads != null) {
                sourceReads.countDown();
                if (!sourceReads.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("source reader barrierの待機がtimeoutしました");
                }
            }
            return new ImportSourceContent(new ByteArrayInputStream(SOURCE_BYTES), "source.csv", ImportEncoding.UTF8,
                    ImportEntity.CUSTOMER, SOURCE_SHA);
        });
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void sameNamespaceConcurrentRegistrationCommitsExactlyOneIdentityAndReturnsOneId() throws Exception {
        sourceReads = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Long> first = executor.submit(this::registerOnAuthenticatedThread);
            Future<Long> second = executor.submit(this::registerOnAuthenticatedThread);
            Long firstId = first.get(30, TimeUnit.SECONDS);
            Long secondId = second.get(30, TimeUnit.SECONDS);

            assertEquals(firstId, secondId);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_identity_registry WHERE tenant_id=?", Integer.class, tenant));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sourceVersionRevokedAfterReadIsRejectedBeforeRegistryWrite() {
        afterSourceRead = () -> jdbc.update("UPDATE t_import_source_system SET status='DISABLED',version_no=version_no+1 WHERE tenant_id=? AND id=?",
                tenant, sourceId);

        assertThrows(BusinessException.class, () -> service.registerCandidate(jobId, runId, 1L));
        assertNoRegistryRow();
    }

    @Test
    void publishedRunPointerRevokedAfterReadIsRejectedBeforeRegistryWrite() {
        afterSourceRead = () -> jdbc.update("UPDATE t_import_job SET current_validation_run_id=NULL WHERE tenant_id=? AND id=?",
                tenant, jobId);

        assertThrows(BusinessException.class, () -> service.registerCandidate(jobId, runId, 1L));
        assertNoRegistryRow();
    }

    @Test
    void acceptedRowRevokedAfterReadIsRejectedBeforeRegistryWrite() {
        afterSourceRead = () -> jdbc.update("UPDATE t_import_validation_row SET disposition='REJECTED' WHERE tenant_id=? AND validation_run_id=? AND source_row_number=1",
                tenant, runId);

        assertThrows(BusinessException.class, () -> service.registerCandidate(jobId, runId, 1L));
        assertNoRegistryRow();
    }

    @Test
    void permissionRevokedAfterReadIsRejectedByLiveAuthorizationQuery() {
        afterSourceRead = () -> jdbc.update("DELETE FROM t_user_permission_group WHERE tenant_id=? AND user_id=17", tenant);

        assertThrows(BusinessException.class, () -> service.registerCandidate(jobId, runId, 1L));
        assertNoRegistryRow();
    }

    private long registerOnAuthenticatedThread() {
        SecurityContext threadContext = SecurityContextHolder.createEmptyContext();
        threadContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(threadContext);
        try {
            return service.registerCandidate(jobId, runId, 1L);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void assertNoRegistryRow() {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_identity_registry WHERE tenant_id=?", Integer.class, tenant));
    }

    private void createSchema() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS sys_user (id BIGINT PRIMARY KEY, tenant_id VARCHAR(100), role VARCHAR(30), status INT, deleted_flag INT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS t_user_permission_group (tenant_id VARCHAR(100), user_id BIGINT, group_id BIGINT, deleted_flag INT, PRIMARY KEY(tenant_id,user_id,group_id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS m_permission_group (tenant_id VARCHAR(100), id BIGINT, enabled INT, deleted_flag INT, PRIMARY KEY(tenant_id,id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS t_permission_group_action (tenant_id VARCHAR(100), group_id BIGINT, action_key VARCHAR(100), deny_flag INT, deleted_flag INT, PRIMARY KEY(tenant_id,group_id,action_key,deny_flag)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS t_import_source_system (tenant_id VARCHAR(100), id BIGINT, version_no BIGINT, status VARCHAR(16), lookup_key_version VARCHAR(64), PRIMARY KEY(tenant_id,id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS t_document (tenant_id VARCHAR(100), id BIGINT, legal_entity_id VARCHAR(100), legal_hold_flag INT, retention_until DATE, deleted_flag INT, PRIMARY KEY(tenant_id,id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS t_document_version (tenant_id VARCHAR(100), document_id BIGINT, id BIGINT, version_no INT, storage_key VARCHAR(255), sha256 VARCHAR(64), scan_status VARCHAR(16), deleted_flag INT, PRIMARY KEY(tenant_id,id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS t_document_link (tenant_id VARCHAR(100), id BIGINT AUTO_INCREMENT PRIMARY KEY, document_id BIGINT, target_type VARCHAR(32), target_id BIGINT, deleted_flag INT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS t_import_mapping (tenant_id VARCHAR(100), id BIGINT, source_system_id BIGINT, entity_type VARCHAR(24), schema_version VARCHAR(40), mapping_version INT, canonical_bytes BLOB, mapping_sha256 BINARY(32), PRIMARY KEY(tenant_id,id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS t_import_job (tenant_id VARCHAR(100), id BIGINT, source_system_id BIGINT, source_version BIGINT, entity_type VARCHAR(24), schema_version VARCHAR(40), source_encoding VARCHAR(16), source_sha256 BINARY(32), legal_entity_id VARCHAR(100), document_id BIGINT, document_version_id BIGINT, document_version_no INT, document_sha256 BINARY(32), mapping_id BIGINT, mapping_version INT, mapping_sha256 BINARY(32), state VARCHAR(16), active_validation_run_id BIGINT, current_validation_run_id BIGINT, PRIMARY KEY(tenant_id,id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS t_import_validation_run (tenant_id VARCHAR(100), job_id BIGINT, id BIGINT, state VARCHAR(16), published_slot INT, source_system_id BIGINT, source_version BIGINT, entity_type VARCHAR(24), source_sha256 BINARY(32), document_id BIGINT, document_version_id BIGINT, document_version_no INT, document_sha256 BINARY(32), mapping_id BIGINT, mapping_version INT, mapping_sha256 BINARY(32), PRIMARY KEY(tenant_id,job_id,id)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS t_import_validation_row (tenant_id VARCHAR(100), job_id BIGINT, validation_run_id BIGINT, source_row_number BIGINT, source_row_sha256 BINARY(32), canonical_fingerprint BINARY(32), disposition VARCHAR(8), PRIMARY KEY(tenant_id,validation_run_id,source_row_number)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS t_import_identity_registry (tenant_id VARCHAR(100), id BIGINT AUTO_INCREMENT PRIMARY KEY, source_system_id BIGINT, entity_type VARCHAR(24), lookup_key_version VARCHAR(64), lookup_digest BINARY(32), cipher_format_version SMALLINT, encryption_key_version VARCHAR(64), nonce BINARY(12), identity_ciphertext VARBINARY(512), status VARCHAR(16) DEFAULT 'CANDIDATE', first_job_id BIGINT, first_validation_run_id BIGINT, UNIQUE(tenant_id,source_system_id,entity_type,lookup_digest)) ENGINE=InnoDB");
    }

    private void seedSnapshot() throws Exception {
        byte[] sourceSha = HexFormat.of().parseHex(SOURCE_SHA);
        byte[] mappingSha = MessageDigest.getInstance("SHA-256").digest(MAPPING);
        String rowHash = rowHash("customer-1", "Example");
        SourceRow row = new SourceRow(1L, rowHash, Map.of("externalKey", "customer-1", "companyName", "Example"));
        byte[] fingerprint = CanonicalImportRowFingerprint.sha256(CanonicalRowFactory.create(ImportEntity.CUSTOMER, row));
        jdbc.update("INSERT INTO sys_user VALUES(17,?,?,1,0)", tenant, "管理者");
        jdbc.update("INSERT INTO m_permission_group VALUES(?,?,1,0)", tenant, 2L);
        jdbc.update("INSERT INTO t_user_permission_group VALUES(?,?,?,0)", tenant, 17L, 2L);
        jdbc.update("INSERT INTO t_permission_group_action VALUES(?,?,?,0,0)", tenant, 2L, ImportMetadataAuthorizationService.ACTION);
        jdbc.update("INSERT INTO t_import_source_system VALUES(?,?,?,?,?)", tenant, sourceId, 4L, "ACTIVE", LOOKUP_VERSION);
        jdbc.update("INSERT INTO t_document VALUES(?,?,?,?,?,?)", tenant, documentId, "77", 0, null, 0);
        jdbc.update("INSERT INTO t_document_version VALUES(?,?,?,?,?,?,?,?)", tenant, documentId, documentVersionId,
                1, "storage-key", SOURCE_SHA, "CLEAN", 0);
        jdbc.update("INSERT INTO t_document_link(tenant_id,document_id,target_type,target_id,deleted_flag) VALUES(?,?,?,?,0)",
                tenant, documentId, "IMPORT_JOB", jobId);
        jdbc.update("INSERT INTO t_import_mapping VALUES(?,?,?,?,?,?,?,?)", tenant, 8L, sourceId, "CUSTOMER",
                "ses-import-v1", 2, MAPPING, mappingSha);
        jdbc.update("INSERT INTO t_import_job VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", tenant, jobId,
                sourceId, 4L, "CUSTOMER", "ses-import-v1", "UTF8", sourceSha, "77", documentId,
                documentVersionId, 1, sourceSha, 8L, 2, mappingSha, "VALIDATED", null, runId);
        jdbc.update("INSERT INTO t_import_validation_run VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", tenant, jobId, runId,
                "COMPLETED", 1, sourceId, 4L, "CUSTOMER", sourceSha, documentId, documentVersionId, 1,
                sourceSha, 8L, 2, mappingSha);
        jdbc.update("INSERT INTO t_import_validation_row VALUES(?,?,?,?,?,?,?)", tenant, jobId, runId, 1L,
                HexFormat.of().parseHex(rowHash), fingerprint, "ACCEPTED");
    }

    private static String rowHash(String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] value = field.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
                digest.update(value);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static final class TestKeyProvider implements ImportIdentityKeyProvider {
        @Override public String currentLookupKeyVersion() { return LOOKUP_VERSION; }
        @Override public byte[] lookupKey(String keyVersion) { return TEST_KEY.clone(); }
        @Override public String currentEncryptionKeyVersion() { return ENCRYPTION_VERSION; }
        @Override public byte[] encryptionKey(String keyVersion) { return TEST_KEY.clone(); }
    }
}
