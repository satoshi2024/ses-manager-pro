package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.SesImportV1Schema;
import com.ses.service.DocumentService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** F1a source/mapping/job metadataのtransaction境界。HTTP/UIを提供しない。 */
@Service
public class ImportMetadataService {
    private static final Pattern SOURCE_CODE = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private final JdbcTemplate jdbc;
    private final ImportMetadataAuthorizationService authorization;
    private final ImportIdentityKeyProvider keys;
    private final DocumentService documentService;

    public ImportMetadataService(JdbcTemplate jdbc, ImportMetadataAuthorizationService authorization,
                                 ImportIdentityKeyProvider keys, DocumentService documentService) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.keys = keys;
        this.documentService = documentService;
    }

    @Transactional(rollbackFor = Exception.class)
    public long createSource(ImportSourceRequest request) {
        ImportAuthorizedContext context = authorization.requireCurrentAuthorization();
        if (request == null || request.sourceCode() == null || !SOURCE_CODE.matcher(request.sourceCode()).matches()
                || request.displayName() == null || request.displayName().isBlank() || request.displayName().length() > 120) {
            throw BusinessException.of(400, "error.import.invalidSource");
        }
        String normalizedCode = request.sourceCode().toLowerCase(java.util.Locale.ROOT);
        String lookupKeyVersion = keys.currentLookupKeyVersion();
        byte[] key = keys.lookupKey(lookupKeyVersion);
        java.util.Arrays.fill(key, (byte) 0);
        String sql = "INSERT INTO t_import_source_system (tenant_id, source_code, display_name, status, version_no, lookup_key_version, created_by, updated_by) VALUES (?, ?, ?, 'ACTIVE', 1, ?, ?, ?)";
        try {
            authorization.requireCurrentAuthorizationForWrite(context);
            return insertId(sql, context.tenantId(), normalizedCode, request.displayName().strip(),
                    lookupKeyVersion, context.actorUserId(), context.actorUserId());
        } catch (DuplicateKeyException duplicate) {
            throw BusinessException.of(409, "error.import.sourceAlreadyExists");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public long createMapping(ImportMappingRequest request) {
        ImportAuthorizedContext context = authorization.requireCurrentAuthorization();
        validateMappingRequest(request);
        SourceSnapshot source = lockSource(context.tenantId(), request.sourceSystemId());
        requireActiveSource(source);
        byte[] canonical = canonicalMapping(request.headerToCanonicalField());
        if (canonical.length > 32768) {
            throw BusinessException.of(400, "error.import.invalidMapping");
        }
        byte[] digest = sha256(canonical);
        ExistingMapping existingVersion = jdbc.query("""
                SELECT mapping_version, canonical_bytes FROM t_import_mapping
                WHERE tenant_id=? AND source_system_id=? AND entity_type=? AND schema_version=? AND mapping_sha256=?
                ORDER BY mapping_version DESC LIMIT 1
                """, rs -> rs.next() ? new ExistingMapping(rs.getInt(1), rs.getBytes(2)) : null,
                context.tenantId(), source.id(), request.entity().name(),
                request.schemaVersion(), digest);
        if (existingVersion != null) {
            if (!MessageDigest.isEqual(canonical, existingVersion.canonicalBytes())) {
                throw BusinessException.of(409, "error.import.mappingHashCollision");
            }
            authorization.requireCurrentAuthorizationForWrite(context);
            return jdbc.queryForObject("""
                    SELECT id FROM t_import_mapping
                    WHERE tenant_id=? AND source_system_id=? AND entity_type=? AND schema_version=?
                      AND mapping_version=? AND mapping_sha256=?
                    """, Long.class, context.tenantId(), source.id(), request.entity().name(), request.schemaVersion(),
                    existingVersion.version(), digest);
        }
        Integer next = jdbc.query("""
                SELECT COALESCE(MAX(mapping_version), 0) + 1 FROM t_import_mapping
                WHERE tenant_id=? AND source_system_id=? AND entity_type=? AND schema_version=?
                """, rs -> rs.next() ? rs.getInt(1) : 1, context.tenantId(), source.id(), request.entity().name(), request.schemaVersion());
        authorization.requireCurrentAuthorizationForWrite(context);
        return insertId("INSERT INTO t_import_mapping (tenant_id, source_system_id, entity_type, schema_version, mapping_version, canonical_bytes, mapping_sha256, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                context.tenantId(), source.id(), request.entity().name(), request.schemaVersion(), next,
                canonical, digest, context.actorUserId());
    }

    @Transactional(rollbackFor = Exception.class)
    public long registerJob(ImportJobRegistrationRequest request) {
        ImportAuthorizedContext context = authorization.requireCurrentAuthorization();
        validateJobRequest(request);
        SourceSnapshot source = lockSource(context.tenantId(), request.sourceSystemId());
        requireActiveSource(source);
        byte[] sourceHash = HexFormat.of().parseHex(request.sourceSha256Hex());
        Long alreadyRegistered = findIdempotentJob(context.tenantId(), source.id(), request, sourceHash,
                sourceHash, context.legalEntityId(), source.version());
        if (alreadyRegistered != null) {
            documentService.authorizeImportJobDownload(alreadyRegistered, request.documentId(),
                    request.documentVersionId(), request.documentVersionNo(), request.sourceSha256Hex());
            authorization.requireCurrentAuthorizationForWrite(context);
            ensureJobLink(context.tenantId(), request.documentId(), alreadyRegistered);
            return alreadyRegistered;
        }
        var version = documentService.authorizeImportSourceRegistration(request.documentId(), request.documentVersionId(),
                request.documentVersionNo(), request.sourceSha256Hex(), context.legalEntityId());
        if (!context.tenantId().equals(version.getTenantId()) || request.documentId() != version.getDocumentId()
                || !request.sourceSha256Hex().equalsIgnoreCase(version.getSha256())) {
            throw BusinessException.of(403, "error.import.documentSnapshotMismatch");
        }
        byte[] documentHash = HexFormat.of().parseHex(version.getSha256());
        String sql = """
                INSERT INTO t_import_job (tenant_id, source_system_id, source_version, entity_type, schema_version,
                  source_encoding, source_sha256, legal_entity_id, document_id, document_version_id,
                  document_version_no, document_sha256, state, version_no, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'UPLOADED', 1, ?)
                """;
        long jobId;
        try {
            authorization.requireCurrentAuthorizationForWrite(context);
            jobId = insertId(sql, context.tenantId(), source.id(), source.version(), request.entity().name(),
                    request.schemaVersion(), request.encoding().name(), sourceHash, Long.toString(context.legalEntityId()),
                    request.documentId(), request.documentVersionId(), request.documentVersionNo(), documentHash,
                    context.actorUserId());
        } catch (DuplicateKeyException duplicate) {
            Long existing = findIdempotentJob(context.tenantId(), source.id(), request, sourceHash, documentHash,
                    context.legalEntityId(), source.version());
            if (existing != null) {
                authorization.requireCurrentAuthorizationForWrite(context);
                ensureJobLink(context.tenantId(), request.documentId(), existing);
                return existing;
            }
            throw BusinessException.of(409, "error.import.jobIdentityConflict");
        }
        documentService.link(request.documentId(), "IMPORT_JOB", jobId);
        Integer linkCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM t_document_link
                WHERE tenant_id=? AND document_id=? AND target_type='IMPORT_JOB' AND target_id=? AND deleted_flag=0
                """, Integer.class, context.tenantId(), request.documentId(), jobId);
        if (linkCount == null || linkCount != 1) {
            throw BusinessException.of(409, "error.import.documentLinkMissing");
        }
        return jobId;
    }

    /** raw upload用。source selectorはcurrent tenant内でcodeから解決し、client IDは受け取らない。 */
    @Transactional(readOnly = true)
    public ImportUploadSource resolveActiveUploadSource(String sourceCode) {
        ImportAuthorizedContext context = authorization.requireCurrentAuthorization();
        if (sourceCode == null || !SOURCE_CODE.matcher(sourceCode).matches()) {
            throw BusinessException.of(400, "error.import.invalidSource");
        }
        ImportUploadSource source = jdbc.query("""
                SELECT id, source_code, version_no FROM t_import_source_system
                WHERE tenant_id=? AND source_code=? AND status='ACTIVE'
                """, rs -> rs.next() ? new ImportUploadSource(rs.getLong(1), rs.getString(2), rs.getLong(3)) : null,
                context.tenantId(), sourceCode.toLowerCase(java.util.Locale.ROOT));
        if (source == null) throw BusinessException.of(404, "error.import.sourceUnavailable");
        return source;
    }

    /**
     * hashとserver scopeが一致する既存jobだけを再利用し、exact document ACLを再確認する。
     *
     * <p>同一identityのjobが別法人に属する場合は、他の不一致より先に判定して
     * {@link ImportIdentityOwnedByOtherLegalEntityException}を投げる。別法人のjobのsource version・encoding等で
     * 応答が変わらないようにするためで、呼出元はこれを汎用403へ変換する（内容存在のoracleにしない）。
     * 同一法人内の条件不一致（encoding・source version・document hash）は従来どおり409とする。
     */
    public ImportUploadJobDetail findOrReuseExistingUpload(ImportUploadSource source, ImportEncoding encoding,
                                                            String sourceSha256) {
        ImportAuthorizedContext context = authorization.requireCurrentAuthorization();
        if (source == null || encoding == null || sourceSha256 == null || !SHA256.matcher(sourceSha256).matches()) {
            throw BusinessException.of(400, "error.import.invalidJob");
        }
        byte[] hash = HexFormat.of().parseHex(sourceSha256);
        ExistingUpload existing = jdbc.query("""
                SELECT id, source_version, source_encoding, legal_entity_id, document_id,
                       document_version_id, document_version_no, document_sha256, state
                FROM t_import_job
                WHERE tenant_id=? AND source_system_id=? AND entity_type='CUSTOMER'
                  AND schema_version=? AND source_sha256=?
                """, rs -> rs.next() ? new ExistingUpload(rs.getLong("id"), rs.getLong("source_version"),
                rs.getString("source_encoding"), rs.getString("legal_entity_id"), rs.getLong("document_id"),
                rs.getLong("document_version_id"), rs.getInt("document_version_no"),
                rs.getBytes("document_sha256"), rs.getString("state")) : null,
                context.tenantId(), source.id(), SesImportV1Schema.VERSION, hash);
        if (existing == null) return null;
        if (!Long.toString(context.legalEntityId()).equals(existing.legalEntityId())) {
            throw new ImportIdentityOwnedByOtherLegalEntityException();
        }
        if (existing.sourceVersion() != source.version() || !encoding.name().equals(existing.encoding())
                || !MessageDigest.isEqual(hash, existing.documentSha256())) {
            throw BusinessException.of(409, "error.import.jobIdentityConflict");
        }
        documentService.verifyImportJobStoredBytes(existing.id(), existing.documentId(), existing.versionId(),
                existing.versionNo(), sourceSha256);
        ImportAuthorizedContext after = authorization.requireCurrentAuthorization();
        if (!context.equals(after)) throw BusinessException.of(403, "error.forbidden");
        return new ImportUploadJobDetail(existing.id(), existing.state(), ImportEntity.CUSTOMER.name(),
                sourceSha256.substring(0, 12), existing.versionNo(), existing.encoding());
    }

    /** transaction ACKが不確かな場合、stage自身のexact documentを指すjobがcommit済みか読み直す。 */
    public ImportUploadJobDetail findCommittedUploadForStage(ImportUploadSource source, ImportEncoding encoding,
                                                             String sourceSha256,
                                                             com.ses.service.impl.StagedImportDocument staged) {
        ImportAuthorizedContext context = authorization.requireCurrentAuthorization();
        if (source == null || encoding == null || staged == null || staged.document() == null
                || staged.version() == null || staged.document().getId() == null || staged.version().getId() == null) {
            return null;
        }
        byte[] hash = HexFormat.of().parseHex(sourceSha256);
        ExistingUpload existing = jdbc.query("""
                SELECT id, source_version, source_encoding, legal_entity_id, document_id,
                       document_version_id, document_version_no, document_sha256, state
                FROM t_import_job
                WHERE tenant_id=? AND source_system_id=? AND entity_type='CUSTOMER' AND schema_version=?
                  AND source_sha256=? AND document_id=? AND document_version_id=?
                """, rs -> rs.next() ? new ExistingUpload(rs.getLong("id"), rs.getLong("source_version"),
                rs.getString("source_encoding"), rs.getString("legal_entity_id"), rs.getLong("document_id"),
                rs.getLong("document_version_id"), rs.getInt("document_version_no"),
                rs.getBytes("document_sha256"), rs.getString("state")) : null,
                context.tenantId(), source.id(), SesImportV1Schema.VERSION, hash,
                staged.document().getId(), staged.version().getId());
        if (existing == null) return null;
        if (existing.sourceVersion() != source.version() || !encoding.name().equals(existing.encoding())
                || !Long.toString(context.legalEntityId()).equals(existing.legalEntityId())
                || existing.versionNo() != staged.version().getVersionNo()
                || !MessageDigest.isEqual(hash, existing.documentSha256())) {
            throw BusinessException.of(409, "error.import.jobIdentityConflict");
        }
        String sha = HexFormat.of().formatHex(hash);
        documentService.verifyImportJobStoredBytes(existing.id(), existing.documentId(), existing.versionId(),
                existing.versionNo(), sha);
        return new ImportUploadJobDetail(existing.id(), existing.state(), ImportEntity.CUSTOMER.name(),
                sha.substring(0, 12), existing.versionNo(), existing.encoding());
    }

    /** reuse readとcommit ACK-loss readbackの間に現れたjobが当該stage自身かを識別する。 */
    public boolean isUploadJobForStage(long jobId, ImportUploadSource source, String sourceSha256,
                                       com.ses.service.impl.StagedImportDocument staged,
                                       ImportAuthorizedContext expectedContext) {
        ImportAuthorizedContext context = authorization.requireCurrentAuthorization();
        if (expectedContext == null || !expectedContext.equals(context)) {
            throw BusinessException.of(403, "error.import.readbackContextChanged");
        }
        if (source == null || staged == null || staged.document() == null || staged.version() == null
                || staged.document().getId() == null || staged.version().getId() == null) {
            throw BusinessException.of(500, "error.import.jobReadbackUnknown");
        }
        ExistingUploadIdentity actual = jdbc.query("""
                SELECT source_system_id, source_version, entity_type, schema_version, source_sha256,
                       legal_entity_id, document_id, document_version_id, document_version_no, document_sha256
                FROM t_import_job WHERE tenant_id=? AND id=?
                """, rs -> rs.next() ? new ExistingUploadIdentity(rs.getLong("source_system_id"),
                rs.getLong("source_version"), rs.getString("entity_type"), rs.getString("schema_version"),
                rs.getBytes("source_sha256"), rs.getString("legal_entity_id"), rs.getLong("document_id"),
                rs.getLong("document_version_id"), rs.getInt("document_version_no"),
                rs.getBytes("document_sha256")) : null, context.tenantId(), jobId);
        byte[] expectedHash = HexFormat.of().parseHex(sourceSha256);
        if (actual == null || actual.sourceSystemId() != source.id() || actual.sourceVersion() != source.version()
                || !ImportEntity.CUSTOMER.name().equals(actual.entity())
                || !SesImportV1Schema.VERSION.equals(actual.schemaVersion())
                || !Long.toString(context.legalEntityId()).equals(actual.legalEntityId())
                || actual.documentVersionNo() != staged.version().getVersionNo()
                || !MessageDigest.isEqual(expectedHash, actual.sourceSha256())
                || !MessageDigest.isEqual(expectedHash, actual.documentSha256())) {
            throw BusinessException.of(500, "error.import.jobReadbackUnknown");
        }
        return actual.documentId() == staged.document().getId()
                && actual.documentVersionId() == staged.version().getId();
    }

    @Transactional(readOnly = true)
    public ImportUploadJobDetail getUploadedJobDetail(long jobId) {
        ImportAuthorizedContext context = authorization.requireCurrentAuthorization();
        ExistingUploadDetail detail = jdbc.query("""
                SELECT id, source_version, source_encoding, legal_entity_id, document_id,
                       document_version_id, document_version_no, document_sha256, state
                FROM t_import_job
                WHERE tenant_id=? AND id=? AND entity_type='CUSTOMER' AND schema_version=?
                """, rs -> rs.next() ? new ExistingUploadDetail(rs.getLong("id"), rs.getLong("source_version"),
                rs.getString("source_encoding"), rs.getString("legal_entity_id"), rs.getLong("document_id"),
                rs.getLong("document_version_id"), rs.getInt("document_version_no"),
                rs.getBytes("document_sha256"), rs.getString("state")) : null,
                context.tenantId(), jobId, SesImportV1Schema.VERSION);
        if (detail == null || !Long.toString(context.legalEntityId()).equals(detail.legalEntityId())) {
            throw BusinessException.of(404, "error.import.jobNotFound");
        }
        String hash = HexFormat.of().formatHex(detail.documentSha256());
        documentService.authorizeImportJobDownload(detail.id(), detail.documentId(), detail.versionId(),
                detail.versionNo(), hash);
        return new ImportUploadJobDetail(detail.id(), detail.state(), ImportEntity.CUSTOMER.name(),
                hash.substring(0, 12), detail.versionNo(), detail.encoding());
    }

    /** import専用のmetadata insert。呼出元ImportCsvUploadCommitServiceの短いREQUIRED txに参加する。 */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRED,
            rollbackFor = Exception.class)
    public long registerImportJobFromStaged(ImportJobRegistrationRequest request, long expectedSourceVersion,
                                            ImportAdmissionManifest manifest,
                                            com.ses.service.impl.StagedImportDocument staged) {
        ImportAuthorizedContext context = authorization.requireCurrentAuthorization();
        validateJobRequest(request);
        if (request.entity() != ImportEntity.CUSTOMER || !SesImportV1Schema.VERSION.equals(request.schemaVersion())) {
            throw BusinessException.of(422, "error.import.unsupportedEntity");
        }
        if (manifest == null || !"A1A_UPLOADED".equals(manifest.phase())
                || !context.tenantId().equals(manifest.tenantId())
                || context.legalEntityId() != manifest.legalEntityId()
                || manifest.sourceSystemId() != request.sourceSystemId()
                || manifest.sourceVersion() != expectedSourceVersion
                || manifest.entity() != ImportEntity.CUSTOMER
                || !SesImportV1Schema.VERSION.equals(manifest.schemaVersion())
                || manifest.encoding() != request.encoding()
                || !request.sourceSha256Hex().equals(manifest.sourceSha256())
                || !java.util.Objects.equals(manifest.documentId(), request.documentId())
                || !java.util.Objects.equals(manifest.documentVersionId(), request.documentVersionId())
                || !java.util.Objects.equals(manifest.documentVersionNo(), request.documentVersionNo())
                || !request.sourceSha256Hex().equals(manifest.documentSha256())
                || staged == null || staged.document() == null || staged.version() == null
                || !java.util.Objects.equals(manifest.documentId(), staged.document().getId())
                || !java.util.Objects.equals(manifest.documentVersionId(), staged.version().getId())
                || !java.util.Objects.equals(manifest.documentVersionNo(), staged.version().getVersionNo())
                || !java.util.Objects.equals(manifest.documentSha256(), staged.version().getSha256())
                || !java.util.Objects.equals(staged.storageKey(), staged.version().getStorageKey())) {
            throw BusinessException.of(403, "error.import.admissionMismatch");
        }
        SourceSnapshot source = lockSource(context.tenantId(), request.sourceSystemId());
        requireActiveSource(source);
        if (source.version() != expectedSourceVersion) {
            throw BusinessException.of(409, "error.import.sourceVersionChanged");
        }
        byte[] hash = HexFormat.of().parseHex(request.sourceSha256Hex());
        StagedVersionSnapshot stored = jdbc.query("""
                SELECT UNHEX(v.sha256), v.storage_key, v.size_bytes FROM t_document d JOIN t_document_version v
                  ON v.tenant_id=d.tenant_id AND v.document_id=d.id
                WHERE d.tenant_id=? AND d.id=? AND d.deleted_flag=0 AND d.document_type='IMPORT'
                  AND d.legal_entity_id=? AND d.legal_hold_flag=0
                  AND v.id=? AND v.version_no=? AND v.scan_status='CLEAN'
                FOR UPDATE
                """, rs -> rs.next() ? new StagedVersionSnapshot(rs.getBytes(1), rs.getString(2), rs.getLong(3)) : null,
                context.tenantId(), request.documentId(),
                Long.toString(context.legalEntityId()), request.documentVersionId(), request.documentVersionNo());
        if (stored == null || !MessageDigest.isEqual(hash, stored.sha256())
                || !staged.storageKey().equals(stored.storageKey())
                || staged.version().getSizeBytes() == null || staged.version().getSizeBytes() != stored.sizeBytes()) {
            throw BusinessException.of(403, "error.import.documentSnapshotMismatch");
        }
        byte[] documentHash = stored.sha256();
        if (findIdempotentJob(context.tenantId(), source.id(), request, hash, documentHash,
                context.legalEntityId(), source.version()) != null) {
            throw BusinessException.of(409, "error.import.jobIdentityConflict");
        }
        long jobId;
        try {
            authorization.requireCurrentAuthorizationForWrite(context);
            jobId = insertId("""
                    INSERT INTO t_import_job (tenant_id, source_system_id, source_version, entity_type, schema_version,
                      source_encoding, source_sha256, legal_entity_id, document_id, document_version_id,
                      document_version_no, document_sha256, state, version_no, created_by)
                    VALUES (?, ?, ?, 'CUSTOMER', ?, ?, ?, ?, ?, ?, ?, ?, 'UPLOADED', 1, ?)
                    """, context.tenantId(), source.id(), source.version(), request.schemaVersion(),
                    request.encoding().name(), hash, Long.toString(context.legalEntityId()), request.documentId(),
                    request.documentVersionId(), request.documentVersionNo(), documentHash, context.actorUserId());
        } catch (DuplicateKeyException duplicate) {
            throw BusinessException.of(409, "error.import.jobIdentityConflict");
        }
        documentService.link(request.documentId(), "IMPORT_JOB", jobId);
        Integer links = jdbc.queryForObject("""
                SELECT COUNT(*) FROM t_document_link
                WHERE tenant_id=? AND document_id=? AND target_type='IMPORT_JOB' AND target_id=? AND deleted_flag=0
                """, Integer.class, context.tenantId(), request.documentId(), jobId);
        if (links == null || links != 1) throw BusinessException.of(409, "error.import.documentLinkMissing");
        authorization.requireCurrentAuthorizationForWrite(context);
        return jobId;
    }

    private record ExistingUpload(long id, long sourceVersion, String encoding, String legalEntityId,
                                  long documentId, long versionId, int versionNo,
                                  byte[] documentSha256, String state) { }

    private record ExistingUploadDetail(long id, long sourceVersion, String encoding, String legalEntityId,
                                        long documentId, long versionId, int versionNo,
                                        byte[] documentSha256, String state) { }

    private record StagedVersionSnapshot(byte[] sha256, String storageKey, long sizeBytes) { }

    private record ExistingUploadIdentity(long sourceSystemId, long sourceVersion, String entity,
                                          String schemaVersion, byte[] sourceSha256, String legalEntityId,
                                          long documentId, long documentVersionId, int documentVersionNo,
                                          byte[] documentSha256) { }

    private void ensureJobLink(String tenantId, long documentId, long jobId) {
        documentService.link(documentId, "IMPORT_JOB", jobId);
        Integer linkCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM t_document_link
                WHERE tenant_id=? AND document_id=? AND target_type='IMPORT_JOB' AND target_id=? AND deleted_flag=0
                """, Integer.class, tenantId, documentId, jobId);
        if (linkCount == null || linkCount != 1) throw BusinessException.of(409, "error.import.documentLinkMissing");
    }

    /** 現在のsource/job/document/permissionを再確認してからのみ原本streamを開く。 */
    public ImportSourceContent openSource(long jobId) {
        ImportAuthorizedContext context = authorization.requireCurrentAuthorization();
        Long sourceId = jdbc.query("SELECT source_system_id FROM t_import_job WHERE tenant_id=? AND id=?",
                rs -> rs.next() ? rs.getLong(1) : null, context.tenantId(), jobId);
        if (sourceId == null) throw BusinessException.of(404, "error.import.jobNotFound");
        SourceSnapshot lockedSource = findSource(context.tenantId(), sourceId);
        requireActiveSource(lockedSource);
        SourceContentSnapshot snapshot = jdbc.query("""
                SELECT j.source_version, j.document_id, j.document_version_id, j.document_version_no,
                       j.document_sha256, j.source_sha256, j.source_encoding, j.entity_type,
                       j.version_no AS job_version, j.state AS job_state, v.original_name
                FROM t_import_job j
                JOIN t_document_version v ON v.tenant_id=j.tenant_id AND v.document_id=j.document_id
                    AND v.id=j.document_version_id AND v.version_no=j.document_version_no
                WHERE j.tenant_id=? AND j.id=?
                """, rs -> rs.next() ? new SourceContentSnapshot(rs.getLong("document_id"),
                rs.getLong("document_version_id"), rs.getInt("document_version_no"),
                rs.getBytes("document_sha256"), rs.getBytes("source_sha256"), rs.getString("source_encoding"),
                rs.getString("entity_type"), rs.getLong("source_version"), rs.getLong("job_version"),
                rs.getString("job_state"),
                rs.getString("original_name")) : null, context.tenantId(), jobId);
        if (snapshot == null) {
            throw BusinessException.of(404, "error.import.jobNotFound");
        }
        if (snapshot.sourceVersion() != lockedSource.version()) {
            throw BusinessException.of(409, "error.import.sourceVersionConflict");
        }
        String documentHash = HexFormat.of().formatHex(snapshot.documentHash());
        documentService.authorizeImportJobDownload(jobId, snapshot.documentId(), snapshot.documentVersionId(),
                snapshot.documentVersionNo(), documentHash);
        Long currentJobVersion = jdbc.query("SELECT version_no FROM t_import_job WHERE tenant_id=? AND id=?",
                rs -> rs.next() ? rs.getLong(1) : null, context.tenantId(), jobId);
        if (currentJobVersion == null || currentJobVersion != snapshot.jobVersion()) {
            throw BusinessException.of(409, "error.import.jobStateConflict");
        }
        var stream = documentService.download(snapshot.documentId(), snapshot.documentVersionNo());
        return new ImportSourceContent(stream, snapshot.originalName(),
                com.ses.importcenter.foundation.ImportEncoding.valueOf(snapshot.encoding()),
                ImportEntity.valueOf(snapshot.entity()), HexFormat.of().formatHex(snapshot.sourceHash()));
    }

    @Transactional(rollbackFor = Exception.class)
    public long disableSource(long sourceSystemId, long expectedVersion, boolean disabled) {
        ImportAuthorizedContext context = authorization.requireCurrentAuthorization();
        SourceSnapshot source = lockSource(context.tenantId(), sourceSystemId);
        if (source == null) {
            throw BusinessException.of(404, "error.import.sourceNotFound");
        }
        if (source.version() != expectedVersion) {
            throw BusinessException.of(409, "error.import.sourceVersionConflict");
        }
        authorization.requireCurrentAuthorizationForWrite(context);
        String target = disabled ? "DISABLED" : "ACTIVE";
        if (target.equals(source.status())) {
            return source.version();
        }
        int updated = jdbc.update("""
                UPDATE t_import_source_system
                SET status=?, version_no=version_no+1, updated_by=?, updated_at=CURRENT_TIMESTAMP(6)
                WHERE tenant_id=? AND id=? AND version_no=? AND status=?
                """, target, context.actorUserId(), context.tenantId(), source.id(), expectedVersion, source.status());
        if (updated != 1) {
            throw BusinessException.of(409, "error.import.sourceVersionConflict");
        }
        return expectedVersion + 1;
    }

    @Transactional(rollbackFor = Exception.class)
    public long bindMapping(long jobId, long expectedJobVersion, long mappingId) {
        ImportAuthorizedContext context = authorization.requireCurrentAuthorization();
        Long sourceId = jdbc.query("SELECT source_system_id FROM t_import_job WHERE tenant_id=? AND id=?",
                rs -> rs.next() ? rs.getLong(1) : null, context.tenantId(), jobId);
        if (sourceId == null) {
            throw BusinessException.of(404, "error.import.jobNotFound");
        }
        SourceSnapshot source = lockSource(context.tenantId(), sourceId);
        requireActiveSource(source);
        JobSnapshot job = jdbc.query("""
                SELECT id, source_system_id, source_version, entity_type, schema_version, state, version_no,
                       mapping_id, mapping_version, mapping_sha256
                FROM t_import_job WHERE tenant_id=? AND id=? FOR UPDATE
                """, rs -> rs.next() ? new JobSnapshot(rs.getLong("id"), rs.getLong("source_system_id"),
                rs.getLong("source_version"), rs.getString("entity_type"), rs.getString("schema_version"),
                rs.getString("state"), rs.getLong("version_no"), rs.getObject("mapping_id", Long.class),
                rs.getObject("mapping_version", Integer.class), rs.getBytes("mapping_sha256")) : null,
                context.tenantId(), jobId);
        if (job.sourceId() != sourceId || job.sourceVersion() != source.version()) {
            throw BusinessException.of(409, "error.import.sourceVersionConflict");
        }
        if (job == null || !"UPLOADED".equals(job.state()) || job.version() != expectedJobVersion) {
            if (job != null && job.mappingId() != null && job.mappingId() == mappingId) {
                authorization.requireCurrentAuthorizationForWrite(context);
                return job.version();
            }
            throw BusinessException.of(409, "error.import.jobStateConflict");
        }
        MappingSnapshot mapping = jdbc.query("""
                SELECT id, entity_type, schema_version, mapping_version, mapping_sha256
                FROM t_import_mapping WHERE tenant_id=? AND id=? AND source_system_id=? FOR UPDATE
                """, rs -> rs.next() ? new MappingSnapshot(rs.getLong("id"), rs.getString("entity_type"),
                rs.getString("schema_version"), rs.getInt("mapping_version"), rs.getBytes("mapping_sha256")) : null,
                context.tenantId(), mappingId, job.sourceId());
        if (mapping == null || !job.entityType().equals(mapping.entityType()) || !job.schemaVersion().equals(mapping.schemaVersion())) {
            throw BusinessException.of(409, "error.import.mappingSnapshotMismatch");
        }
        authorization.requireCurrentAuthorizationForWrite(context);
        int updated = jdbc.update("""
                UPDATE t_import_job SET mapping_id=?, mapping_version=?, mapping_sha256=?, state='MAPPED',
                    version_no=version_no+1, updated_at=CURRENT_TIMESTAMP(6)
                WHERE tenant_id=? AND id=? AND version_no=? AND state='UPLOADED' AND mapping_id IS NULL
                """, mapping.id(), mapping.version(), mapping.sha256(), context.tenantId(), jobId, expectedJobVersion);
        if (updated != 1) {
            throw BusinessException.of(409, "error.import.jobStateConflict");
        }
        return expectedJobVersion + 1;
    }

    private Long findIdempotentJob(String tenantId, long sourceId, ImportJobRegistrationRequest request,
                                   byte[] sourceHash, byte[] documentHash, long legalEntityId, long sourceVersion) {
        return jdbc.query("""
                SELECT id, source_version, source_encoding, legal_entity_id, document_id, document_version_id,
                       document_version_no, document_sha256
                FROM t_import_job WHERE tenant_id=? AND source_system_id=? AND entity_type=? AND schema_version=? AND source_sha256=?
                """, rs -> {
            if (!rs.next()) return null;
            boolean same = sourceVersion == rs.getLong("source_version")
                    && request.documentId() == rs.getLong("document_id")
                    && request.documentVersionId() == rs.getLong("document_version_id")
                    && request.documentVersionNo() == rs.getInt("document_version_no")
                    && request.encoding().name().equals(rs.getString("source_encoding"))
                    && Long.toString(legalEntityId).equals(rs.getString("legal_entity_id"))
                    && MessageDigest.isEqual(documentHash, rs.getBytes("document_sha256"));
            return same ? rs.getLong("id") : null;
        }, tenantId, sourceId, request.entity().name(), request.schemaVersion(), sourceHash);
    }

    private SourceSnapshot lockSource(String tenantId, long sourceSystemId) {
        return jdbc.query("""
                SELECT id, source_code, status, version_no, lookup_key_version
                FROM t_import_source_system WHERE tenant_id=? AND id=? FOR UPDATE
                """, rs -> rs.next() ? new SourceSnapshot(rs.getLong("id"), rs.getString("source_code"),
                rs.getString("status"), rs.getLong("version_no"), rs.getString("lookup_key_version")) : null,
                tenantId, sourceSystemId);
    }

    private SourceSnapshot findSource(String tenantId, long sourceSystemId) {
        return jdbc.query("""
                SELECT id, source_code, status, version_no, lookup_key_version
                FROM t_import_source_system WHERE tenant_id=? AND id=?
                """, rs -> rs.next() ? new SourceSnapshot(rs.getLong("id"), rs.getString("source_code"),
                rs.getString("status"), rs.getLong("version_no"), rs.getString("lookup_key_version")) : null,
                tenantId, sourceSystemId);
    }

    private void requireActiveSource(SourceSnapshot source) {
        if (source == null || !"ACTIVE".equals(source.status())) {
            throw BusinessException.of(409, "error.import.sourceUnavailable");
        }
    }

    private void validateMappingRequest(ImportMappingRequest request) {
        if (request == null || request.sourceSystemId() < 1 || request.entity() == null
                || !SesImportV1Schema.VERSION.equals(request.schemaVersion())
                || request.headerToCanonicalField().isEmpty() || request.headerToCanonicalField().size() > 200) {
            throw BusinessException.of(400, "error.import.invalidMapping");
        }
        Set<String> allowedFields = Set.copyOf(SesImportV1Schema.definition(request.entity()).allowedHeaders());
        Set<String> mappedFields = new java.util.HashSet<>();
        for (Map.Entry<String, String> entry : request.headerToCanonicalField().entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getKey().length() > 256
                    || entry.getValue() == null || !allowedFields.contains(entry.getValue())
                    || !mappedFields.add(entry.getValue())) {
                throw BusinessException.of(400, "error.import.invalidMapping");
            }
        }
        if (!request.headerToCanonicalField().values().containsAll(SesImportV1Schema.definition(request.entity()).requiredHeaders())) {
            throw BusinessException.of(400, "error.import.invalidMapping");
        }
    }

    private void validateJobRequest(ImportJobRegistrationRequest request) {
        if (request == null || request.sourceSystemId() < 1 || request.entity() == null || request.encoding() == null
                || !SesImportV1Schema.VERSION.equals(request.schemaVersion()) || request.documentId() < 1
                || request.documentVersionId() < 1 || request.documentVersionNo() < 1 || request.sourceSha256Hex() == null
                || !SHA256.matcher(request.sourceSha256Hex()).matches()) {
            throw BusinessException.of(400, "error.import.invalidJob");
        }
    }

    static byte[] canonicalMapping(Map<String, String> mapping) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : new TreeMap<>(mapping).entrySet()) {
            if (!first) json.append(',');
            first = false;
            json.append('"').append(escapeJson(entry.getKey())).append("\":\"")
                    .append(escapeJson(entry.getValue())).append('"');
        }
        return json.append('}').toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String escapeJson(String input) {
        StringBuilder result = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char value = input.charAt(i);
            switch (value) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (value < 0x20) result.append(String.format("\\u%04x", (int) value));
                    else result.append(value);
                }
            }
        }
        return result.toString();
    }

    private byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256を利用できません");
        }
    }

    private long insertId(String sql, Object... args) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update((Connection connection) -> {
            PreparedStatement statement = connection.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("取込metadata IDを採番できません");
        return key.longValue();
    }

    private record SourceSnapshot(long id, String code, String status, long version, String lookupKeyVersion) { }
    private record SourceContentSnapshot(long documentId, long documentVersionId, int documentVersionNo,
                                         byte[] documentHash, byte[] sourceHash, String encoding,
                                         String entity, long sourceVersion, long jobVersion, String jobState,
                                         String originalName) { }
    private record ExistingMapping(int version, byte[] canonicalBytes) { }
    private record MappingSnapshot(long id, String entityType, String schemaVersion, int version, byte[] sha256) { }
    private record JobSnapshot(long id, long sourceId, long sourceVersion, String entityType, String schemaVersion,
                               String state, long version, Long mappingId, Integer mappingVersion, byte[] mappingSha256) { }
}
