package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;
import com.ses.importcenter.foundation.ImportEntity;
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
            jobId = insertId(sql, context.tenantId(), source.id(), source.version(), request.entity().name(),
                    request.schemaVersion(), request.encoding().name(), sourceHash, Long.toString(context.legalEntityId()),
                    request.documentId(), request.documentVersionId(), request.documentVersionNo(), documentHash,
                    context.actorUserId());
        } catch (DuplicateKeyException duplicate) {
            Long existing = findIdempotentJob(context.tenantId(), source.id(), request, sourceHash, documentHash,
                    context.legalEntityId(), source.version());
            if (existing != null) {
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
