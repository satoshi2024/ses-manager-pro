package com.ses.importcenter.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.importcenter.foundation.CanonicalImportRowFingerprint;
import com.ses.importcenter.foundation.CanonicalRowFactory;
import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.importcenter.foundation.SourceRow;
import com.ses.service.DocumentService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import javax.sql.DataSource;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImportIdentityCandidateServiceTest {
    private static final String TENANT = "tenant-a";
    private static final long ACTOR = 17L;
    private static final long LEGAL_ENTITY = 77L;
    private static final long JOB = 9L;
    private static final long RUN = 11L;
    private static final long SOURCE = 3L;
    private static final long DOCUMENT = 21L;
    private static final long DOCUMENT_VERSION = 22L;
    private static final byte[] INITIAL_SOURCE_BYTES = "externalKey,companyName\ncustomer-1,Example\n"
            .getBytes(StandardCharsets.UTF_8);
    private static final String INITIAL_SOURCE_SHA = sha256(INITIAL_SOURCE_BYTES);
    private static final String KEY_DIGEST = "55".repeat(32);
    private static final String ROW_HASH = rowHash("customer-1", "Example");
    private static final byte[] MAPPING = ImportMetadataService.canonicalMapping(Map.of(
            "externalKey", "externalKey", "companyName", "companyName"));

    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private ImportMetadataService metadata;
    private ImportMetadataAuthorizationService authorization;
    private ImportIdentityCryptoService crypto;
    private DocumentService documentService;
    private ImportIdentityCandidateService service;
    private ImportAuthorizedContext context;
    private byte[] currentSourceBytes;
    private String currentSourceSha;
    private Runnable afterSourceRead;
    private boolean scanChangesOnRead;
    private boolean revokeAtFinalRecheck;
    private boolean revokeAfterSourceRead;
    private int registryChangesAtAuthorizationCall;
    private Runnable registryChange;
    private int authorizationCalls;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:candidate_" + java.util.UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource = h2;
        jdbc = new JdbcTemplate(dataSource);
        createSchema();
        seedSnapshot();
        currentSourceBytes = INITIAL_SOURCE_BYTES.clone();
        currentSourceSha = INITIAL_SOURCE_SHA;

        metadata = mock(ImportMetadataService.class);
        authorization = mock(ImportMetadataAuthorizationService.class);
        crypto = mock(ImportIdentityCryptoService.class);
        documentService = mock(DocumentService.class);
        context = new ImportAuthorizedContext(TENANT, ACTOR, LEGAL_ENTITY);
        when(authorization.requireCurrentAuthorization()).thenAnswer(invocation -> {
            authorizationCalls++;
            if (registryChange != null && authorizationCalls == registryChangesAtAuthorizationCall) {
                registryChange.run();
                registryChange = null;
            }
            if (revokeAfterSourceRead && authorizationCalls == 2) {
                throw BusinessException.of(403, "error.forbidden");
            }
            return context;
        });
        when(authorization.requireCurrentAuthorizationForWrite(any())).thenAnswer(invocation -> {
            authorizationCalls++;
            if (registryChange != null && authorizationCalls == registryChangesAtAuthorizationCall) {
                registryChange.run();
                registryChange = null;
            }
            if (revokeAtFinalRecheck) throw BusinessException.of(403, "error.forbidden");
            return context;
        });
        when(crypto.encrypt(eq(TENANT), eq(SOURCE), eq(ImportEntity.CUSTOMER), eq("lookup-v1"), anyString()))
                .thenAnswer(invocation -> encryptedFor(invocation.getArgument(4)));
        when(crypto.matches(any(), eq(TENANT), eq(SOURCE), eq(ImportEntity.CUSTOMER), anyString()))
                .thenAnswer(invocation -> "customer-1".equals(invocation.getArgument(4)));
        when(metadata.openSource(JOB)).thenAnswer(invocation -> {
            if (afterSourceRead != null) afterSourceRead.run();
            if (scanChangesOnRead) {
                jdbc.update("UPDATE t_document_version SET scan_status='INFECTED' WHERE tenant_id=? AND id=?",
                        TENANT, DOCUMENT_VERSION);
            }
            return new ImportSourceContent(new java.io.ByteArrayInputStream(currentSourceBytes),
                    "source.csv", ImportEncoding.UTF8, ImportEntity.CUSTOMER, currentSourceSha);
        });

        ImportCandidateSourceReader reader = new ImportCandidateSourceReader(metadata, new ObjectMapper());
        service = new ImportIdentityCandidateService(jdbc, authorization, crypto, reader, documentService,
                new DataSourceTransactionManager(dataSource));
    }

    @Test
    void registersOnlyExactAcceptedRowIntoEncryptedCandidateRegistry() {
        long id = service.registerCandidate(JOB, RUN, 1L);

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_identity_registry WHERE tenant_id=? "
                + "AND source_system_id=? AND entity_type='CUSTOMER' AND status='CANDIDATE'", Integer.class, TENANT, SOURCE));
        assertEquals(id, jdbc.queryForObject("SELECT id FROM t_import_identity_registry WHERE tenant_id=?", Long.class, TENANT).longValue());
        verify(documentService).authorizeImportJobDownload(eq(JOB), eq(DOCUMENT), eq(DOCUMENT_VERSION), eq(1), eq(INITIAL_SOURCE_SHA));
    }

    @Test
    void repeatedSameIdentityReturnsTheSameRegistryId() {
        long first = service.registerCandidate(JOB, RUN, 1L);
        long second = service.registerCandidate(JOB, RUN, 1L);

        assertEquals(first, second);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_identity_registry", Integer.class));
    }

    @Test
    void sameDigestForDifferentIdentityIsRejected() throws Exception {
        service.registerCandidate(JOB, RUN, 1L);
        replaceValidatedSource("customer-2");

        assertThrows(BusinessException.class, () -> service.registerCandidate(JOB, RUN, 1L));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_identity_registry", Integer.class));
    }

    @Test
    void changedRegistrySnapshotAfterPrecheckIsRejectedWithoutSecondInsert() {
        service.registerCandidate(JOB, RUN, 1L);
        registryChangesAtAuthorizationCall = authorizationCalls + 2;
        registryChange = () -> updateRegistryCiphertextOnIndependentConnection((byte) 0x7f);

        assertThrows(BusinessException.class, () -> service.registerCandidate(JOB, RUN, 1L));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_identity_registry", Integer.class));
    }

    @Test
    void sameIdentityAppearingAfterPrecheckRetriesAndReturnsCommittedId() {
        registryChangesAtAuthorizationCall = authorizationCalls + 2;
        registryChange = () -> insertRegistryOnIndependentConnection(encryptedFor("customer-1"));

        long id = service.registerCandidate(JOB, RUN, 1L);

        assertEquals(id, jdbc.queryForObject("SELECT id FROM t_import_identity_registry", Long.class).longValue());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_identity_registry", Integer.class));
    }

    @Test
    void rejectsScanStateChangedAfterSourceReadAndDoesNotRegisterCandidate() {
        scanChangesOnRead = true;

        assertThrows(BusinessException.class, () -> service.registerCandidate(JOB, RUN, 1L));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_identity_registry", Integer.class));
        verify(documentService, never()).authorizeImportJobDownload(anyLong(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void rejectsPermissionRevokedAtFinalLockedRecheckBeforeInsert() {
        revokeAtFinalRecheck = true;

        assertThrows(BusinessException.class, () -> service.registerCandidate(JOB, RUN, 1L));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_identity_registry", Integer.class));
    }

    @Test
    void rejectsSourceVersionDriftAfterSourceReadAndRollsBack() {
        afterSourceRead = () -> jdbc.update("UPDATE t_import_source_system SET status='DISABLED',version_no=version_no+1 WHERE tenant_id=? AND id=?",
                TENANT, SOURCE);
        assertNoCandidateAfterReadBarrier();
    }

    @Test
    void rejectsPublishedRunDriftAfterSourceReadAndRollsBack() {
        afterSourceRead = () -> jdbc.update("UPDATE t_import_validation_run SET published_slot=NULL WHERE tenant_id=? AND job_id=? AND id=?",
                TENANT, JOB, RUN);
        assertNoCandidateAfterReadBarrier();
    }

    @Test
    void rejectsAcceptedRowDriftAfterSourceReadAndRollsBack() {
        afterSourceRead = () -> jdbc.update("UPDATE t_import_validation_row SET disposition='REJECTED' WHERE tenant_id=? AND validation_run_id=? AND source_row_number=1",
                TENANT, RUN);
        assertNoCandidateAfterReadBarrier();
    }

    @Test
    void rejectsPermissionRevocationAfterSourceReadBeforeLockedMetadataWork() {
        afterSourceRead = () -> revokeAfterSourceRead = true;
        assertNoCandidateAfterReadBarrier();
    }

    private void assertNoCandidateAfterReadBarrier() {
        assertThrows(BusinessException.class, () -> service.registerCandidate(JOB, RUN, 1L));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_identity_registry", Integer.class));
    }

    private void createSchema() {
        jdbc.execute("CREATE TABLE t_import_source_system (tenant_id VARCHAR(100), id BIGINT, version_no BIGINT, status VARCHAR(16), lookup_key_version VARCHAR(64), PRIMARY KEY(tenant_id,id))");
        jdbc.execute("CREATE TABLE t_document (tenant_id VARCHAR(100), id BIGINT, legal_entity_id VARCHAR(100), legal_hold_flag INT, retention_until DATE, deleted_flag INT, PRIMARY KEY(tenant_id,id))");
        jdbc.execute("CREATE TABLE t_document_version (tenant_id VARCHAR(100), document_id BIGINT, id BIGINT, version_no INT, storage_key VARCHAR(255), sha256 VARCHAR(64), scan_status VARCHAR(16), deleted_flag INT, PRIMARY KEY(tenant_id,id))");
        jdbc.execute("CREATE TABLE t_document_link (tenant_id VARCHAR(100), id BIGINT AUTO_INCREMENT PRIMARY KEY, document_id BIGINT, target_type VARCHAR(32), target_id BIGINT, deleted_flag INT)");
        jdbc.execute("CREATE TABLE t_import_mapping (tenant_id VARCHAR(100), id BIGINT, source_system_id BIGINT, entity_type VARCHAR(24), schema_version VARCHAR(40), mapping_version INT, canonical_bytes BLOB, mapping_sha256 BINARY(32), PRIMARY KEY(tenant_id,id))");
        jdbc.execute("CREATE TABLE t_import_job (tenant_id VARCHAR(100), id BIGINT, source_system_id BIGINT, source_version BIGINT, entity_type VARCHAR(24), schema_version VARCHAR(40), source_encoding VARCHAR(16), source_sha256 BINARY(32), legal_entity_id VARCHAR(100), document_id BIGINT, document_version_id BIGINT, document_version_no INT, document_sha256 BINARY(32), mapping_id BIGINT, mapping_version INT, mapping_sha256 BINARY(32), state VARCHAR(16), active_validation_run_id BIGINT, current_validation_run_id BIGINT, PRIMARY KEY(tenant_id,id))");
        jdbc.execute("CREATE TABLE t_import_validation_run (tenant_id VARCHAR(100), job_id BIGINT, id BIGINT, state VARCHAR(16), published_slot INT, source_system_id BIGINT, source_version BIGINT, entity_type VARCHAR(24), source_sha256 BINARY(32), document_id BIGINT, document_version_id BIGINT, document_version_no INT, document_sha256 BINARY(32), mapping_id BIGINT, mapping_version INT, mapping_sha256 BINARY(32), PRIMARY KEY(tenant_id,job_id,id))");
        jdbc.execute("CREATE TABLE t_import_validation_row (tenant_id VARCHAR(100), job_id BIGINT, validation_run_id BIGINT, source_row_number BIGINT, source_row_sha256 BINARY(32), canonical_fingerprint BINARY(32), disposition VARCHAR(8), PRIMARY KEY(tenant_id,validation_run_id,source_row_number))");
        jdbc.execute("CREATE TABLE t_import_identity_registry (tenant_id VARCHAR(100), id BIGINT AUTO_INCREMENT PRIMARY KEY, source_system_id BIGINT, entity_type VARCHAR(24), lookup_key_version VARCHAR(64), lookup_digest BINARY(32), cipher_format_version SMALLINT, encryption_key_version VARCHAR(64), nonce BINARY(12), identity_ciphertext VARBINARY(512), status VARCHAR(16) DEFAULT 'CANDIDATE', first_job_id BIGINT, first_validation_run_id BIGINT, UNIQUE(tenant_id,source_system_id,entity_type,lookup_digest))");
    }

    private void replaceValidatedSource(String externalKey) throws Exception {
        currentSourceBytes = ("externalKey,companyName\n" + externalKey + ",Example\n").getBytes(StandardCharsets.UTF_8);
        currentSourceSha = sha256(currentSourceBytes);
        byte[] digest = HexFormat.of().parseHex(currentSourceSha);
        String rowHash = rowHash(externalKey, "Example");
        SourceRow row = new SourceRow(1L, rowHash, Map.of("externalKey", externalKey, "companyName", "Example"));
        byte[] fingerprint = CanonicalImportRowFingerprint.sha256(CanonicalRowFactory.create(ImportEntity.CUSTOMER, row));
        jdbc.update("UPDATE t_document_version SET sha256=? WHERE tenant_id=? AND id=?", currentSourceSha, TENANT, DOCUMENT_VERSION);
        jdbc.update("UPDATE t_import_job SET source_sha256=?,document_sha256=? WHERE tenant_id=? AND id=?",
                digest, digest, TENANT, JOB);
        jdbc.update("UPDATE t_import_validation_run SET source_sha256=?,document_sha256=? WHERE tenant_id=? AND job_id=? AND id=?",
                digest, digest, TENANT, JOB, RUN);
        jdbc.update("UPDATE t_import_validation_row SET source_row_sha256=?,canonical_fingerprint=? WHERE tenant_id=? AND validation_run_id=? AND source_row_number=1",
                HexFormat.of().parseHex(rowHash), fingerprint, TENANT, RUN);
    }

    private ImportIdentityCryptoService.EncryptedIdentity encryptedFor(String externalKey) {
        byte[] ciphertext = HexFormat.of().parseHex(sha256(externalKey.getBytes(StandardCharsets.UTF_8)));
        return new ImportIdentityCryptoService.EncryptedIdentity("lookup-v1", HexFormat.of().parseHex(KEY_DIGEST),
                (short) 1, "enc-v2", new byte[12], ciphertext);
    }

    private void updateRegistryCiphertextOnIndependentConnection(byte value) {
        byte[] changed = new byte[32];
        changed[0] = value;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE t_import_identity_registry SET identity_ciphertext=? WHERE tenant_id=?")) {
            statement.setBytes(1, changed);
            statement.setString(2, TENANT);
            statement.executeUpdate();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void insertRegistryOnIndependentConnection(ImportIdentityCryptoService.EncryptedIdentity identity) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO t_import_identity_registry
                       (tenant_id, source_system_id, entity_type, lookup_key_version, lookup_digest,
                        cipher_format_version, encryption_key_version, nonce, identity_ciphertext,
                        first_job_id, first_validation_run_id)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, TENANT); statement.setLong(2, SOURCE); statement.setString(3, ImportEntity.CUSTOMER.name());
            statement.setString(4, identity.lookupKeyVersion()); statement.setBytes(5, identity.lookupDigest());
            statement.setShort(6, identity.cipherFormatVersion()); statement.setString(7, identity.encryptionKeyVersion());
            statement.setBytes(8, identity.nonce()); statement.setBytes(9, identity.ciphertext());
            statement.setLong(10, JOB); statement.setLong(11, RUN); statement.executeUpdate();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void seedSnapshot() throws Exception {
        byte[] sourceSha = HexFormat.of().parseHex(INITIAL_SOURCE_SHA);
        byte[] mappingSha = MessageDigest.getInstance("SHA-256").digest(MAPPING);
        SourceRow row = new SourceRow(1L, ROW_HASH, Map.of("externalKey", "customer-1", "companyName", "Example"));
        byte[] fingerprint = CanonicalImportRowFingerprint.sha256(CanonicalRowFactory.create(ImportEntity.CUSTOMER, row));
        jdbc.update("INSERT INTO t_import_source_system VALUES(?,?,?,?,?)", TENANT, SOURCE, 4L, "ACTIVE", "lookup-v1");
        jdbc.update("INSERT INTO t_document VALUES(?,?,?,?,?,?)", TENANT, DOCUMENT, Long.toString(LEGAL_ENTITY), 0, null, 0);
        jdbc.update("INSERT INTO t_document_version VALUES(?,?,?,?,?,?,?,?)", TENANT, DOCUMENT, DOCUMENT_VERSION,
                1, "storage-key", INITIAL_SOURCE_SHA, "CLEAN", 0);
        jdbc.update("INSERT INTO t_document_link(tenant_id,document_id,target_type,target_id,deleted_flag) VALUES(?,?,?,?,0)",
                TENANT, DOCUMENT, "IMPORT_JOB", JOB);
        jdbc.update("INSERT INTO t_import_mapping VALUES(?,?,?,?,?,?,?,?)", TENANT, 8L, SOURCE, "CUSTOMER",
                "ses-import-v1", 2, MAPPING, mappingSha);
        jdbc.update("INSERT INTO t_import_job VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", TENANT, JOB,
                SOURCE, 4L, "CUSTOMER", "ses-import-v1", "UTF8", sourceSha, Long.toString(LEGAL_ENTITY),
                DOCUMENT, DOCUMENT_VERSION, 1, sourceSha, 8L, 2, mappingSha, "VALIDATED", null, RUN);
        jdbc.update("INSERT INTO t_import_validation_run VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", TENANT, JOB, RUN,
                "COMPLETED", 1, SOURCE, 4L, "CUSTOMER", sourceSha, DOCUMENT, DOCUMENT_VERSION, 1, sourceSha,
                8L, 2, mappingSha);
        jdbc.update("INSERT INTO t_import_validation_row VALUES(?,?,?,?,?,?,?)", TENANT, JOB, RUN, 1L,
                HexFormat.of().parseHex(ROW_HASH), fingerprint, "ACCEPTED");
    }

    private static String rowHash(String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] value = field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(value.length).array());
                digest.update(value);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
