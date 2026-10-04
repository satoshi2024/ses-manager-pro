package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;
import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.service.DocumentService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;

/** validationとは独立した、exact source row由来identity candidateの登録境界。 */
@Service
public class ImportIdentityCandidateService {
    private final JdbcTemplate jdbc;
    private final ImportMetadataAuthorizationService authorization;
    private final ImportIdentityCryptoService crypto;
    private final ImportCandidateSourceReader sourceReader;
    private final DocumentService documentService;
    private final TransactionTemplate transaction;

    public ImportIdentityCandidateService(JdbcTemplate jdbc, ImportMetadataAuthorizationService authorization,
                                          ImportIdentityCryptoService crypto,
                                          ImportCandidateSourceReader sourceReader,
                                          DocumentService documentService,
                                          PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.crypto = crypto;
        this.sourceReader = sourceReader;
        this.documentService = documentService;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** requestはjob/run/row番号だけを受け、key/hash/fingerprintはtrusted readerから導出する。 */
    public long registerCandidate(long jobId, long validationRunId, long sourceRowNumber) {
        ImportAuthorizedContext context = authorization.requireCurrentAuthorization();
        CandidateSnapshot snapshot = loadSnapshot(context, jobId, validationRunId);
        var verifiedRow = sourceReader.read(jobId, sourceRowNumber,
                new ImportCandidateSourceReader.Snapshot(snapshot.entity(), snapshot.encoding(),
                        HexFormat.of().formatHex(snapshot.sourceSha256()), snapshot.mappingBytes(), snapshot.mappingSha256()));
        ImportIdentityCryptoService.EncryptedIdentity encrypted;
        try {
            encrypted = crypto.encrypt(context.tenantId(), snapshot.sourceSystemId(), snapshot.entity(),
                    snapshot.lookupKeyVersion(), verifiedRow.externalKey());
        } catch (IllegalArgumentException invalidKey) {
            throw BusinessException.of(409, "error.import.identityKeyInvalid");
        }

        for (int attempt = 0; attempt < 2; attempt++) {
            Existing existing = findExisting(context.tenantId(), snapshot.sourceSystemId(), snapshot.entity(),
                    encrypted.lookupDigest());
            if (existing != null) requireSameIdentity(existing, encrypted, context, snapshot, verifiedRow.externalKey());
            try {
                Long id = transaction.execute(status -> registerLocked(context, snapshot, validationRunId,
                        sourceRowNumber, verifiedRow, encrypted, existing));
                if (id == null) throw new IllegalStateException("candidate transaction returned no ID");
                return id;
            } catch (ExistingIdentityAppeared raced) {
                // source row lock serializes same-namespace writers. Resolve a newly committed identity
                // outside the transaction so decryption/key-provider work never holds DB locks.
                if (attempt == 1) throw BusinessException.of(409, "error.import.identityCandidateConflict");
            } catch (DuplicateKeyException racedInsert) {
                if (attempt == 1) throw BusinessException.of(409, "error.import.identityCandidateConflict");
            }
        }
        throw BusinessException.of(409, "error.import.identityCandidateConflict");
    }

    private long registerLocked(ImportAuthorizedContext originalContext, CandidateSnapshot snapshot,
                                long runId, long rowNumber,
                                ImportCandidateSourceReader.VerifiedCandidateRow verifiedRow,
                                ImportIdentityCryptoService.EncryptedIdentity encrypted, Existing prechecked) {
        ImportAuthorizedContext context = authorization.requireCurrentAuthorization();
        requireSameActor(originalContext, context);

        Source source = jdbc.query("""
                SELECT version_no, status, lookup_key_version FROM t_import_source_system
                WHERE tenant_id=? AND id=? FOR UPDATE
                """, rs -> rs.next() ? new Source(rs.getLong(1), rs.getString(2), rs.getString(3)) : null,
                context.tenantId(), snapshot.sourceSystemId());
        if (source == null || !"ACTIVE".equals(source.status()) || source.version() != snapshot.sourceVersion()
                || !snapshot.lookupKeyVersion().equals(source.lookupKeyVersion())
                || !encrypted.lookupKeyVersion().equals(source.lookupKeyVersion())) {
            throw BusinessException.of(409, "error.import.sourceVersionConflict");
        }

        Document document = jdbc.query("""
                SELECT legal_entity_id, legal_hold_flag, retention_until, deleted_flag
                FROM t_document WHERE tenant_id=? AND id=? FOR UPDATE
                """, rs -> rs.next() ? new Document(rs.getString(1), rs.getInt(2), rs.getDate(3) == null
                ? null : rs.getDate(3).toLocalDate(), rs.getInt(4)) : null,
                context.tenantId(), snapshot.documentId());
        if (document == null || document.deletedFlag() != 0
                || !Long.toString(context.legalEntityId()).equals(document.legalEntityId())
                || document.legalHoldFlag() != 0
                || (document.retentionUntil() != null && document.retentionUntil().isBefore(LocalDate.now()))) {
            throw BusinessException.of(403, "error.import.documentSnapshotMismatch");
        }

        DocumentVersion version = jdbc.query("""
                SELECT version_no, storage_key, sha256, scan_status, deleted_flag
                FROM t_document_version
                WHERE tenant_id=? AND document_id=? AND id=? FOR UPDATE
                """, rs -> rs.next() ? new DocumentVersion(rs.getInt(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getInt(5)) : null,
                context.tenantId(), snapshot.documentId(), snapshot.documentVersionId());
        if (version == null || version.deletedFlag() != 0 || version.versionNo() != snapshot.documentVersionNo()
                || !"CLEAN".equals(version.scanStatus()) || !snapshot.storageKey().equals(version.storageKey())
                || !snapshot.documentVersionSha256().equalsIgnoreCase(version.sha256())
                || !MessageDigest.isEqual(snapshot.documentSha256(), HexFormat.of().parseHex(version.sha256()))) {
            throw BusinessException.of(403, "error.import.documentSnapshotMismatch");
        }

        List<Long> links = jdbc.query("""
                SELECT id FROM t_document_link
                WHERE tenant_id=? AND document_id=? AND target_type='IMPORT_JOB' AND target_id=? AND deleted_flag=0
                ORDER BY id FOR UPDATE
                """, (rs, row) -> rs.getLong(1), context.tenantId(), snapshot.documentId(), snapshot.jobId());
        if (links.size() != 1) throw BusinessException.of(403, "error.import.documentLinkMissing");

        // DocumentService performs the live ACL/FileScope/legal-entity/scan/hold/retention check again.
        documentService.authorizeImportJobDownload(snapshot.jobId(), snapshot.documentId(),
                snapshot.documentVersionId(), snapshot.documentVersionNo(), HexFormat.of().formatHex(snapshot.documentSha256()));

        Job job = jdbc.query("""
                SELECT source_system_id, source_version, entity_type, schema_version, source_encoding, source_sha256,
                       legal_entity_id, document_id, document_version_id, document_version_no, document_sha256,
                       mapping_id, mapping_version, mapping_sha256, state, active_validation_run_id,
                       current_validation_run_id
                FROM t_import_job WHERE tenant_id=? AND id=? FOR UPDATE
                """, rs -> rs.next() ? new Job(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getBytes(6), rs.getString(7), rs.getLong(8), rs.getLong(9), rs.getInt(10),
                rs.getBytes(11), rs.getObject(12, Long.class), rs.getObject(13, Integer.class), rs.getBytes(14),
                rs.getString(15), rs.getObject(16, Long.class), rs.getObject(17, Long.class)) : null,
                context.tenantId(), snapshot.jobId());
        requireJobSnapshot(snapshot, job, context);

        Mapping mapping = jdbc.query("""
                SELECT source_system_id, entity_type, schema_version, mapping_version, canonical_bytes, mapping_sha256
                FROM t_import_mapping WHERE tenant_id=? AND id=? FOR UPDATE
                """, rs -> rs.next() ? new Mapping(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                rs.getBytes(5), rs.getBytes(6)) : null, context.tenantId(), snapshot.mappingId());
        if (mapping == null || mapping.sourceSystemId() != snapshot.sourceSystemId()
                || !snapshot.entity().name().equals(mapping.entityType())
                || !snapshot.schemaVersion().equals(mapping.schemaVersion())
                || mapping.version() != snapshot.mappingVersion()
                || !MessageDigest.isEqual(snapshot.mappingSha256(), mapping.sha256())
                || !MessageDigest.isEqual(snapshot.mappingBytes(), mapping.canonicalBytes())) {
            throw BusinessException.of(409, "error.import.mappingSnapshotMismatch");
        }

        Run run = jdbc.query("""
                SELECT state, published_slot, source_system_id, source_version, entity_type, source_sha256,
                       document_id, document_version_id, document_version_no, document_sha256,
                       mapping_id, mapping_version, mapping_sha256
                FROM t_import_validation_run WHERE tenant_id=? AND job_id=? AND id=? FOR UPDATE
                """, rs -> rs.next() ? new Run(rs.getString(1), rs.getObject(2, Integer.class), rs.getLong(3),
                rs.getLong(4), rs.getString(5), rs.getBytes(6), rs.getLong(7), rs.getLong(8), rs.getInt(9),
                rs.getBytes(10), rs.getLong(11), rs.getInt(12), rs.getBytes(13)) : null,
                context.tenantId(), snapshot.jobId(), runId);
        requirePublishedRun(snapshot, run);

        ValidationRow row = jdbc.query("""
                SELECT source_row_sha256, canonical_fingerprint, disposition
                FROM t_import_validation_row
                WHERE tenant_id=? AND job_id=? AND validation_run_id=? AND source_row_number=? FOR UPDATE
                """, rs -> rs.next() ? new ValidationRow(rs.getBytes(1), rs.getBytes(2), rs.getString(3)) : null,
                context.tenantId(), snapshot.jobId(), runId, rowNumber);
        if (row == null || !"ACCEPTED".equals(row.disposition())
                || !MessageDigest.isEqual(row.sourceRowSha256(), verifiedRow.sourceRowSha256())
                || !MessageDigest.isEqual(row.canonicalFingerprint(), verifiedRow.canonicalFingerprint())) {
            throw BusinessException.of(409, "error.import.validationRowMismatch");
        }

        ImportAuthorizedContext finalContext = authorization.requireCurrentAuthorization();
        requireSameActor(originalContext, finalContext);
        Existing locked = findExisting(context.tenantId(), snapshot.sourceSystemId(), snapshot.entity(),
                encrypted.lookupDigest(), true);
        if (locked != null) {
            if (prechecked == null) throw new ExistingIdentityAppeared();
            if (!sameRegistrySnapshot(prechecked, locked)) {
                throw BusinessException.of(409, "error.import.identityHashCollision");
            }
            return locked.id();
        }
        if (prechecked != null) throw BusinessException.of(409, "error.import.identityCandidateConflict");
        // Permission is rechecked after all source/document/run/row locks and immediately before write.
        finalContext = authorization.requireCurrentAuthorization();
        requireSameActor(originalContext, finalContext);
        return insert(context, snapshot, runId, encrypted);
    }

    private CandidateSnapshot loadSnapshot(ImportAuthorizedContext context, long jobId, long runId) {
        return jdbc.query("""
                SELECT j.source_system_id, j.source_version, s.version_no, s.status, s.lookup_key_version,
                       j.entity_type, j.schema_version, j.source_encoding, j.source_sha256,
                       j.legal_entity_id, j.document_id, j.document_version_id, j.document_version_no,
                       j.document_sha256, v.storage_key, v.sha256, j.mapping_id, j.mapping_version,
                       j.mapping_sha256, m.canonical_bytes, j.state, j.current_validation_run_id
                FROM t_import_job j
                JOIN t_import_source_system s ON s.tenant_id=j.tenant_id AND s.id=j.source_system_id
                JOIN t_import_mapping m ON m.tenant_id=j.tenant_id AND m.id=j.mapping_id
                    AND m.source_system_id=j.source_system_id
                JOIN t_document_version v ON v.tenant_id=j.tenant_id AND v.document_id=j.document_id
                    AND v.id=j.document_version_id AND v.version_no=j.document_version_no
                WHERE j.tenant_id=? AND j.id=?
                """, rs -> {
            if (!rs.next()) throw BusinessException.of(404, "error.import.jobNotFound");
            CandidateSnapshot snapshot = new CandidateSnapshot(context.tenantId(), context.actorUserId(),
                    context.legalEntityId(), jobId, rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4),
                    rs.getString(5), ImportEntity.valueOf(rs.getString(6)), rs.getString(7),
                    ImportEncoding.valueOf(rs.getString(8)), rs.getBytes(9), rs.getString(10), rs.getLong(11),
                    rs.getLong(12), rs.getInt(13), rs.getBytes(14), rs.getString(15), rs.getString(16),
                    rs.getLong(17), rs.getInt(18), rs.getBytes(19), rs.getBytes(20), rs.getString(21),
                    rs.getObject(22, Long.class));
            if (snapshot.sourceVersion() != snapshot.currentSourceVersion()
                    || !"ACTIVE".equals(snapshot.sourceStatus())
                    || !"VALIDATED".equals(snapshot.jobState())
                    || !Long.valueOf(runId).equals(snapshot.currentRunId())
                    || !Long.toString(context.legalEntityId()).equals(snapshot.jobLegalEntityId())
                    || snapshot.mappingId() == null || snapshot.mappingVersion() == null
                    || snapshot.sourceSha256() == null || snapshot.documentSha256() == null
                    || snapshot.mappingSha256() == null || snapshot.documentVersionSha256() == null) {
                throw BusinessException.of(409, "error.import.jobStateConflict");
            }
            return snapshot;
        }, context.tenantId(), jobId);
    }

    private void requireJobSnapshot(CandidateSnapshot snapshot, Job job, ImportAuthorizedContext context) {
        if (job == null || job.sourceSystemId() != snapshot.sourceSystemId()
                || job.sourceVersion() != snapshot.sourceVersion()
                || !snapshot.entity().name().equals(job.entityType())
                || !snapshot.schemaVersion().equals(job.schemaVersion())
                || !snapshot.encoding().name().equals(job.encoding())
                || !MessageDigest.isEqual(snapshot.sourceSha256(), job.sourceSha256())
                || !Long.toString(context.legalEntityId()).equals(job.legalEntityId())
                || job.documentId() != snapshot.documentId() || job.documentVersionId() != snapshot.documentVersionId()
                || job.documentVersionNo() != snapshot.documentVersionNo()
                || !MessageDigest.isEqual(snapshot.documentSha256(), job.documentSha256())
                || !snapshot.mappingId().equals(job.mappingId()) || !snapshot.mappingVersion().equals(job.mappingVersion())
                || !MessageDigest.isEqual(snapshot.mappingSha256(), job.mappingSha256())
                || !"VALIDATED".equals(job.state()) || job.activeRunId() != null
                || !Long.valueOf(snapshot.currentRunId()).equals(job.currentRunId())) {
            throw BusinessException.of(409, "error.import.jobStateConflict");
        }
    }

    private void requirePublishedRun(CandidateSnapshot snapshot, Run run) {
        if (run == null || !"COMPLETED".equals(run.state()) || !Integer.valueOf(1).equals(run.publishedSlot())
                || run.sourceSystemId() != snapshot.sourceSystemId() || run.sourceVersion() != snapshot.sourceVersion()
                || !snapshot.entity().name().equals(run.entityType())
                || !MessageDigest.isEqual(snapshot.sourceSha256(), run.sourceSha256())
                || run.documentId() != snapshot.documentId() || run.documentVersionId() != snapshot.documentVersionId()
                || run.documentVersionNo() != snapshot.documentVersionNo()
                || !MessageDigest.isEqual(snapshot.documentSha256(), run.documentSha256())
                || run.mappingId() != snapshot.mappingId() || run.mappingVersion() != snapshot.mappingVersion()
                || !MessageDigest.isEqual(snapshot.mappingSha256(), run.mappingSha256())) {
            throw BusinessException.of(409, "error.import.validationRunMismatch");
        }
    }

    private Existing findExisting(String tenantId, long sourceId, ImportEntity entity, byte[] digest) {
        return findExisting(tenantId, sourceId, entity, digest, false);
    }

    private Existing findExisting(String tenantId, long sourceId, ImportEntity entity, byte[] digest, boolean lock) {
        return jdbc.query("""
                SELECT id, lookup_key_version, lookup_digest, cipher_format_version, encryption_key_version,
                       nonce, identity_ciphertext, status
                FROM t_import_identity_registry
                WHERE tenant_id=? AND source_system_id=? AND entity_type=? AND lookup_digest=?
                """ + (lock ? " FOR UPDATE" : ""), rs -> rs.next() ? new Existing(rs.getLong(1),
                rs.getString(2), rs.getBytes(3), rs.getShort(4), rs.getString(5), rs.getBytes(6),
                rs.getBytes(7), rs.getString(8)) : null, tenantId, sourceId, entity.name(), digest);
    }

    private void requireSameIdentity(Existing existing, ImportIdentityCryptoService.EncryptedIdentity encrypted,
                                    ImportAuthorizedContext context, CandidateSnapshot snapshot, String externalKey) {
        ImportIdentityCryptoService.EncryptedIdentity stored = existing.encryptedIdentity();
        if (!"CANDIDATE".equals(existing.status())
                || !MessageDigest.isEqual(encrypted.lookupDigest(), existing.lookupDigest())
                || !crypto.matches(stored, context.tenantId(), snapshot.sourceSystemId(), snapshot.entity(), externalKey)) {
            throw BusinessException.of(409, "error.import.identityHashCollision");
        }
    }

    private boolean sameRegistrySnapshot(Existing first, Existing second) {
        return first.id() == second.id() && first.status().equals(second.status())
                && first.lookupKeyVersion().equals(second.lookupKeyVersion())
                && first.encryptionKeyVersion().equals(second.encryptionKeyVersion())
                && first.cipherFormatVersion() == second.cipherFormatVersion()
                && MessageDigest.isEqual(first.lookupDigest(), second.lookupDigest())
                && MessageDigest.isEqual(first.nonce(), second.nonce())
                && MessageDigest.isEqual(first.ciphertext(), second.ciphertext());
    }

    private long insert(ImportAuthorizedContext context, CandidateSnapshot snapshot, long runId,
                        ImportIdentityCryptoService.EncryptedIdentity identity) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update((Connection connection) -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO t_import_identity_registry
                      (tenant_id, source_system_id, entity_type, lookup_key_version, lookup_digest,
                       cipher_format_version, encryption_key_version, nonce, identity_ciphertext,
                       first_job_id, first_validation_run_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, PreparedStatement.RETURN_GENERATED_KEYS);
            statement.setString(1, context.tenantId()); statement.setLong(2, snapshot.sourceSystemId());
            statement.setString(3, snapshot.entity().name()); statement.setString(4, identity.lookupKeyVersion());
            statement.setBytes(5, identity.lookupDigest()); statement.setShort(6, identity.cipherFormatVersion());
            statement.setString(7, identity.encryptionKeyVersion()); statement.setBytes(8, identity.nonce());
            statement.setBytes(9, identity.ciphertext()); statement.setLong(10, snapshot.jobId());
            statement.setLong(11, runId);
            return statement;
        }, keyHolder);
        Number id = keyHolder.getKey();
        if (id == null) throw new IllegalStateException("identity candidate IDを採番できません");
        return id.longValue();
    }

    private void requireSameActor(ImportAuthorizedContext original, ImportAuthorizedContext current) {
        if (original.actorUserId() != current.actorUserId() || !original.tenantId().equals(current.tenantId())
                || original.legalEntityId() != current.legalEntityId()) {
            throw BusinessException.of(403, "error.forbidden");
        }
    }

    private record CandidateSnapshot(String tenantId, long actorId, long legalEntityId, long jobId,
                                     long sourceSystemId, long sourceVersion, long currentSourceVersion,
                                     String sourceStatus, String lookupKeyVersion, ImportEntity entity,
                                     String schemaVersion, ImportEncoding encoding, byte[] sourceSha256,
                                     String jobLegalEntityId, long documentId, long documentVersionId,
                                     int documentVersionNo, byte[] documentSha256, String storageKey,
                                     String documentVersionSha256, Long mappingId, Integer mappingVersion,
                                     byte[] mappingSha256, byte[] mappingBytes, String jobState, Long currentRunId) {
        private CandidateSnapshot {
            sourceSha256 = sourceSha256.clone(); documentSha256 = documentSha256.clone();
            mappingSha256 = mappingSha256.clone(); mappingBytes = mappingBytes.clone();
        }
        @Override public byte[] sourceSha256() { return sourceSha256.clone(); }
        @Override public byte[] documentSha256() { return documentSha256.clone(); }
        @Override public byte[] mappingSha256() { return mappingSha256.clone(); }
        @Override public byte[] mappingBytes() { return mappingBytes.clone(); }
    }

    private record Source(long version, String status, String lookupKeyVersion) { }
    private record Document(String legalEntityId, int legalHoldFlag, LocalDate retentionUntil, int deletedFlag) { }
    private record DocumentVersion(int versionNo, String storageKey, String sha256, String scanStatus, int deletedFlag) { }
    private record Job(long sourceSystemId, long sourceVersion, String entityType, String schemaVersion, String encoding,
                       byte[] sourceSha256, String legalEntityId, long documentId, long documentVersionId,
                       int documentVersionNo, byte[] documentSha256, Long mappingId, Integer mappingVersion,
                       byte[] mappingSha256, String state, Long activeRunId, Long currentRunId) { }
    private record Mapping(long sourceSystemId, String entityType, String schemaVersion, int version,
                           byte[] canonicalBytes, byte[] sha256) { }
    private record Run(String state, Integer publishedSlot, long sourceSystemId, long sourceVersion,
                       String entityType, byte[] sourceSha256, long documentId, long documentVersionId,
                       int documentVersionNo, byte[] documentSha256, long mappingId, int mappingVersion,
                       byte[] mappingSha256) { }
    private record ValidationRow(byte[] sourceRowSha256, byte[] canonicalFingerprint, String disposition) { }
    private record Existing(long id, String lookupKeyVersion, byte[] lookupDigest, short cipherFormatVersion,
                            String encryptionKeyVersion, byte[] nonce, byte[] ciphertext, String status) {
        private ImportIdentityCryptoService.EncryptedIdentity encryptedIdentity() {
            return new ImportIdentityCryptoService.EncryptedIdentity(lookupKeyVersion, lookupDigest,
                    cipherFormatVersion, encryptionKeyVersion, nonce, ciphertext);
        }
    }
    private static final class ExistingIdentityAppeared extends RuntimeException { }
}
