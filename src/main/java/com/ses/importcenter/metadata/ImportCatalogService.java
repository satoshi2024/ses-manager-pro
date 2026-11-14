package com.ses.importcenter.metadata;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.PageUtils;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.importcenter.foundation.SesImportV1Schema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 取込元（source）とmapping版の管理API用の窓口（A1a）。登録・更新は既存の{@link ImportMetadataService}
 * （tenant境界・version CAS・書込gate）へ委ね、本classは一覧と入力の正規化だけを持つ。
 *
 * <p>権限: 全操作で{@link ImportMetadataAuthorizationService}が管理者role＋明示action
 * （{@value ImportMetadataAuthorizationService#ACTION}）を再確認する。一覧はtenant内だけを返し、別tenantの
 * sourceは存在しないものと同じ404にする。sourceは法人をまたいで共有するtenant単位の名前空間である
 * （t_import_source_systemに法人列は無い）。
 *
 * <p>sourceの「更新」は有効・停止の切替だけで、source codeは不変（名前空間）、表示名の変更は提供しない
 * （versionが上がり、進行中のjobが{@code sourceVersionConflict}になるため）。mappingは版ごとに不変で、
 * 「更新」は同じsource・entity・schemaの新しい版の登録になる。
 */
@Service
public class ImportCatalogService {
    static final long DEFAULT_PAGE_SIZE = 50;
    static final long MAX_PAGE_SIZE = 200;
    private static final TypeReference<Map<String, String>> MAPPING_TYPE = new TypeReference<>() { };

    private final ImportMetadataAuthorizationService authorization;
    private final ImportMetadataService metadata;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ImportCatalogService(ImportMetadataAuthorizationService authorization, ImportMetadataService metadata,
                                JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.authorization = Objects.requireNonNull(authorization);
        this.metadata = Objects.requireNonNull(metadata);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public ImportCatalogPage<ImportSourceView> listSources(long page, long size) {
        ImportAuthorizedContext context = authorization.requireCurrentAuthorization();
        Page<Object> normalized = PageUtils.safePage(page, size, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM t_import_source_system WHERE tenant_id=?",
                Long.class, context.tenantId());
        long offset = (normalized.getCurrent() - 1) * normalized.getSize();
        List<ImportSourceView> items = total == null || offset >= total ? List.of() : jdbc.query("""
                SELECT id, source_code, display_name, status, version_no FROM t_import_source_system
                WHERE tenant_id=? ORDER BY id LIMIT ? OFFSET ?
                """, (rs, n) -> new ImportSourceView(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getLong(5)), context.tenantId(), normalized.getSize(), offset);
        return new ImportCatalogPage<>(normalized.getCurrent(), normalized.getSize(), total == null ? 0 : total, items);
    }

    public ImportSourceView createSource(ImportSourceRequest request) {
        long id = metadata.createSource(request);
        return requireSource(authorization.requireCurrentAuthorization(), id);
    }

    /** 有効・停止の切替。{@code expectedVersion}は一覧で得たversion（CAS）。 */
    public ImportSourceView changeSourceStatus(long sourceId, long expectedVersion, String status) {
        if (sourceId < 1 || expectedVersion < 1 || (!"ACTIVE".equals(status) && !"DISABLED".equals(status))) {
            throw BusinessException.of(400, "error.import.invalidSource");
        }
        metadata.disableSource(sourceId, expectedVersion, "DISABLED".equals(status));
        return requireSource(authorization.requireCurrentAuthorization(), sourceId);
    }

    public ImportCatalogPage<ImportMappingView> listMappings(long sourceId, long page, long size) {
        ImportAuthorizedContext context = authorization.requireCurrentAuthorization();
        requireSource(context, sourceId);
        Page<Object> normalized = PageUtils.safePage(page, size, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_import_mapping WHERE tenant_id=? AND source_system_id=?",
                Long.class, context.tenantId(), sourceId);
        long offset = (normalized.getCurrent() - 1) * normalized.getSize();
        List<ImportMappingView> items = total == null || offset >= total ? List.of() : jdbc.query("""
                SELECT id, source_system_id, entity_type, schema_version, mapping_version, mapping_sha256,
                       canonical_bytes
                FROM t_import_mapping WHERE tenant_id=? AND source_system_id=?
                ORDER BY entity_type, schema_version, mapping_version, id LIMIT ? OFFSET ?
                """, (rs, n) -> new ImportMappingView(rs.getLong(1), rs.getLong(2), rs.getString(3),
                rs.getString(4), rs.getInt(5), HexFormat.of().formatHex(rs.getBytes(6)).substring(0, 12),
                decodeMapping(rs.getBytes(7))), context.tenantId(), sourceId, normalized.getSize(), offset);
        return new ImportCatalogPage<>(normalized.getCurrent(), normalized.getSize(), total == null ? 0 : total, items);
    }

    /** 新しいmapping版を登録する（同じ内容の版が既にあればその版を返す）。 */
    public ImportMappingView createMapping(long sourceId, ImportEntity entity, String schemaVersion,
                                           Map<String, String> headerToCanonicalField) {
        ImportAuthorizedContext context = authorization.requireCurrentAuthorization();
        requireSource(context, sourceId);
        if (entity == null) throw BusinessException.of(400, "error.import.invalidMapping");
        String schema = schemaVersion == null || schemaVersion.isBlank() ? SesImportV1Schema.VERSION : schemaVersion;
        long id = metadata.createMapping(new ImportMappingRequest(sourceId, entity, schema, headerToCanonicalField));
        ImportMappingView view = jdbc.query("""
                SELECT id, source_system_id, entity_type, schema_version, mapping_version, mapping_sha256,
                       canonical_bytes
                FROM t_import_mapping WHERE tenant_id=? AND id=? AND source_system_id=?
                """, rs -> rs.next() ? new ImportMappingView(rs.getLong(1), rs.getLong(2), rs.getString(3),
                rs.getString(4), rs.getInt(5), HexFormat.of().formatHex(rs.getBytes(6)).substring(0, 12),
                decodeMapping(rs.getBytes(7))) : null, context.tenantId(), id, sourceId);
        if (view == null) throw BusinessException.of(409, "error.import.mappingSnapshotMismatch");
        return view;
    }

    private ImportSourceView requireSource(ImportAuthorizedContext context, long sourceId) {
        ImportSourceView source = jdbc.query("""
                SELECT id, source_code, display_name, status, version_no FROM t_import_source_system
                WHERE tenant_id=? AND id=?
                """, rs -> rs.next() ? new ImportSourceView(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getLong(5)) : null, context.tenantId(), sourceId);
        if (source == null) throw BusinessException.of(404, "error.import.sourceNotFound");
        return source;
    }

    private Map<String, String> decodeMapping(byte[] canonical) {
        try {
            Map<String, String> decoded = objectMapper.readValue(canonical, MAPPING_TYPE);
            return decoded == null ? Map.of() : decoded;
        } catch (IOException invalid) {
            throw BusinessException.of(409, "error.import.mappingSnapshotMismatch");
        }
    }
}
