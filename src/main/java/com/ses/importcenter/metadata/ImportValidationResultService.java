package com.ses.importcenter.metadata;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.LogRedaction;
import com.ses.common.util.PageUtils;
import com.ses.common.util.SecurityUtils;
import com.ses.importcenter.foundation.CanonicalImportRow;
import com.ses.importcenter.foundation.CanonicalRowFactory;
import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.importcenter.foundation.ImportErrorCsvWriter;
import com.ses.importcenter.foundation.ImportValidationCodebook;
import com.ses.importcenter.foundation.SesCsvParser;
import com.ses.importcenter.foundation.SesImportV1Schema;
import com.ses.service.AuditLogService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * 公開済み検証run（job VALIDATED・run COMPLETED・published_slot=1）のpreviewとerror export（A1a）。
 *
 * <p>scope: 現在のtenantかつ現在の法人に属するCUSTOMER／{@code ses-import-v1}のjobだけを対象にし、それ以外は
 * 存在しないjobと同じ404（{@code error.import.jobNotFound}）で返す。認可（管理者role＋明示action）は
 * {@link ImportMetadataAuthorizationService}が操作ごとに再確認する。閲覧の前に必須監査を残し、記録できなければ返さない（503）。
 *
 * <p>preview: 行一覧はDBの検証行（PIIを持たない）から{@code PageUtils.safePage}で上限を適用して読む。手書きの
 * LIMIT/OFFSETのため、size・pageは本classで正規化する。表示値はsource原本をexact snapshot（hash・ACL再確認済みの
 * {@link ImportMetadataService#openSource}）から再parseし、page内の行だけを{@link ImportPreviewMasker}でマスクして返す。
 * 再parseは当該pageの最終行で打ち切り、各行のsource行hashが保存済みhashと一致しなければ409で拒否する。
 * 120秒のsoft上限（B-NF06-1と同じ）を超えたら503。
 *
 * <p>error export: REJECTED行のfinding codeを、source行番号・source行hashと合わせてCSVでstreamする。
 * DBからkeyset（source行番号順、{@value #EXPORT_PAGE_ROWS}行ずつ）で読み、pageごとにflushする（全件をmemoryへ載せない）。
 * 原文・値は出さないため一時fileを作らず、{@code FileReferenceProvider}・{@code FileScopeValidationService}への
 * 追加登録は不要である。各cellは{@link ImportErrorCsvWriter#sanitize}でCSV injectionを無害化する。
 * page読取の前ごとに認可を再確認し、撤権・法人変更があれば以後を出力しない（応答は途中で切れる）。
 */
@Service
public class ImportValidationResultService {
    static final long PREVIEW_DEFAULT_SIZE = 50;
    static final long PREVIEW_MAX_SIZE = 200;
    static final int EXPORT_PAGE_ROWS = 500;
    static final String PREVIEW_AUDIT_CODE = "IMPORT_PREVIEW";
    static final String EXPORT_AUDIT_CODE = "IMPORT_ERROR_EXPORT";
    private static final String FINDING_DOMAIN = "SES-IMPORT-FINDING-CODES-V1";
    private static final int MAX_FINDING_CODES = 64;
    private static final TypeReference<Map<String, String>> MAPPING_TYPE = new TypeReference<>() { };
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ImportValidationResultService.class);

    private final ImportMetadataAuthorizationService authorization;
    private final ImportMetadataService metadata;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<AuditLogService> auditProvider;
    private final LongSupplier nanoClock;
    private final Duration totalBudget;

    @Autowired
    public ImportValidationResultService(ImportMetadataAuthorizationService authorization,
                                         ImportMetadataService metadata, JdbcTemplate jdbc,
                                         ObjectMapper objectMapper, ObjectProvider<AuditLogService> auditProvider) {
        this(authorization, metadata, jdbc, objectMapper, auditProvider, System::nanoTime,
                ImportIngressDeadline.TOTAL_BUDGET);
    }

    /** test用。clockと総期限を差し替える。 */
    ImportValidationResultService(ImportMetadataAuthorizationService authorization, ImportMetadataService metadata,
                                  JdbcTemplate jdbc, ObjectMapper objectMapper,
                                  ObjectProvider<AuditLogService> auditProvider, LongSupplier nanoClock,
                                  Duration totalBudget) {
        this.authorization = Objects.requireNonNull(authorization);
        this.metadata = Objects.requireNonNull(metadata);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.auditProvider = auditProvider;
        this.nanoClock = Objects.requireNonNull(nanoClock);
        this.totalBudget = Objects.requireNonNull(totalBudget);
    }

    /**
     * @param disposition null・空・{@code ALL}なら全行、{@code ACCEPTED}・{@code REJECTED}ならその判定の行だけ
     */
    public ImportPreviewPage preview(long jobId, long page, long size, String disposition) {
        ImportIngressDeadline deadline = ImportIngressDeadline.start(nanoClock, totalBudget);
        String filter = normalizeDisposition(disposition);
        ImportAuthorizedContext context = authorization.requireCurrentAuthorization();
        PublishedRun run = requirePublishedRun(context, jobId);
        Page<Object> normalized = PageUtils.safePage(page, size, PREVIEW_DEFAULT_SIZE, PREVIEW_MAX_SIZE);
        long current = normalized.getCurrent();
        long pageSize = normalized.getSize();
        recordRequiredAudit(context, "GET", "/api/import-center/jobs/" + jobId + "/preview;run=" + run.runId()
                + ";page=" + current + ";size=" + pageSize + ";disposition=" + (filter == null ? "ALL" : filter),
                PREVIEW_AUDIT_CODE);

        String dispositionClause = filter == null ? "" : " AND disposition=?";
        Object[] countArgs = filter == null
                ? new Object[]{context.tenantId(), jobId, run.runId()}
                : new Object[]{context.tenantId(), jobId, run.runId(), filter};
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=? "
                + "AND validation_run_id=?" + dispositionClause, Long.class, countArgs);
        long totalRows = total == null ? 0 : total;
        long offset = (current - 1) * pageSize;
        if (offset >= totalRows) {
            return new ImportPreviewPage(jobId, run.runId(), current, pageSize, totalRows,
                    filter == null ? "ALL" : filter, List.of());
        }
        Object[] rowArgs = filter == null
                ? new Object[]{context.tenantId(), jobId, run.runId(), pageSize, offset}
                : new Object[]{context.tenantId(), jobId, run.runId(), filter, pageSize, offset};
        List<StoredRow> rows = jdbc.query("SELECT source_row_number, source_row_sha256, disposition, finding_codes "
                        + "FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?"
                        + dispositionClause + " ORDER BY source_row_number LIMIT ? OFFSET ?",
                (rs, n) -> new StoredRow(rs.getLong(1), HexFormat.of().formatHex(rs.getBytes(2)), rs.getString(3),
                        decodeFindingCodes(rs.getBytes(4))), rowArgs);
        Map<Long, Map<String, String>> masked = maskedValues(context, jobId, run, rows, deadline);
        List<ImportPreviewRow> result = new ArrayList<>(rows.size());
        for (StoredRow row : rows) {
            result.add(new ImportPreviewRow(row.number(), row.hashHex(), row.disposition(), row.codes(),
                    masked.get(row.number())));
        }
        requireSameContext(context);
        return new ImportPreviewPage(jobId, run.runId(), current, pageSize, totalRows,
                filter == null ? "ALL" : filter, result);
    }

    /** error exportの事前確認。認可・公開run・必須監査を済ませ、出力を始める前に失敗を確定させる。 */
    public ErrorExport prepareErrorExport(long jobId) {
        ImportAuthorizedContext context = authorization.requireCurrentAuthorization();
        PublishedRun run = requirePublishedRun(context, jobId);
        recordRequiredAudit(context, "GET", "/api/import-center/jobs/" + jobId + "/error-export;run=" + run.runId(),
                EXPORT_AUDIT_CODE);
        return new ErrorExport(context, jobId, run.runId());
    }

    /** REJECTED行のfinding codeをUTF-8（BOM付き）CSVでstreamする。読取は{@value #EXPORT_PAGE_ROWS}行ずつ。 */
    public void writeErrorExport(ErrorExport export, OutputStream output) throws IOException {
        Objects.requireNonNull(export, "export");
        Writer writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        writer.write('﻿');
        ImportErrorCsvWriter.writeRowCodeHeader(writer);
        long after = 0;
        while (true) {
            requireSameContext(export.context());
            List<StoredRow> page = jdbc.query("""
                    SELECT source_row_number, source_row_sha256, disposition, finding_codes
                    FROM t_import_validation_row
                    WHERE tenant_id=? AND job_id=? AND validation_run_id=? AND disposition='REJECTED'
                      AND source_row_number>?
                    ORDER BY source_row_number LIMIT ?
                    """, (rs, n) -> new StoredRow(rs.getLong(1), HexFormat.of().formatHex(rs.getBytes(2)),
                    rs.getString(3), decodeFindingCodes(rs.getBytes(4))),
                    export.context().tenantId(), export.jobId(), export.runId(), after, EXPORT_PAGE_ROWS);
            if (page.isEmpty()) break;
            for (StoredRow row : page) {
                if (row.number() <= after) throw BusinessException.of(409, "error.import.validationRowMismatch");
                after = row.number();
                for (String code : row.codes()) {
                    ImportErrorCsvWriter.writeRowCode(writer, row.number(), row.hashHex(), code);
                }
            }
            writer.flush();
            if (page.size() < EXPORT_PAGE_ROWS) break;
        }
        writer.flush();
    }

    private Map<Long, Map<String, String>> maskedValues(ImportAuthorizedContext context, long jobId, PublishedRun run,
                                                       List<StoredRow> rows, ImportIngressDeadline deadline) {
        if (rows.isEmpty()) return Map.of();
        Map<Long, String> expectedHashes = new HashMap<>();
        long last = 0;
        for (StoredRow row : rows) {
            expectedHashes.put(row.number(), row.hashHex());
            last = Math.max(last, row.number());
        }
        Map<String, String> mapping = loadVerifiedMapping(context, run);
        Map<Long, Map<String, String>> masked = new HashMap<>();
        long stopAfter = last;
        try (ImportSourceContent content = metadata.openSource(jobId)) {
            if (content.entity() != ImportEntity.CUSTOMER || content.encoding() != run.encoding()
                    || !content.sha256Hex().equalsIgnoreCase(run.sourceShaHex())) {
                throw BusinessException.of(409, "error.import.sourceSnapshotMismatch");
            }
            new SesCsvParser().parseMapped(content.stream(), content.originalFileName(), ImportEntity.CUSTOMER,
                    run.encoding(), mapping, row -> {
                        if (deadline.expired()) {
                            log.warn("[取込] previewの総期限を超過したため中断します: budgetSeconds={}",
                                    totalBudget.toSeconds());
                            throw BusinessException.of(503, ImportValidationRunService.DEADLINE_MESSAGE_KEY);
                        }
                        if (row.sourceRowNumber() > stopAfter) throw StopScan.INSTANCE;
                        String expected = expectedHashes.get(row.sourceRowNumber());
                        if (expected == null) return;
                        if (!expected.equalsIgnoreCase(row.sourceRowHash())) {
                            throw BusinessException.of(409, "error.import.sourceSnapshotMismatch");
                        }
                        Map<String, String> values;
                        try {
                            CanonicalImportRow canonical = CanonicalRowFactory.create(ImportEntity.CUSTOMER, row);
                            values = ImportPreviewMasker.mask(ImportEntity.CUSTOMER, row.values().get("externalKey"),
                                    canonical.fields());
                        } catch (RuntimeException conversionFailure) {
                            values = Map.of();
                        }
                        masked.put(row.sourceRowNumber(), values);
                    });
        } catch (StopScan done) {
            // page内の最終行まで読めたため、以降のsourceは読まない。
        } catch (IOException | IllegalArgumentException failure) {
            log.warn("[取込] previewのsource再読込に失敗しました: jobId={} exceptionType={}", jobId,
                    LogRedaction.exceptionType(failure));
            throw BusinessException.of(409, "error.import.sourceReadFailed");
        }
        if (masked.size() != expectedHashes.size()) {
            throw BusinessException.of(409, "error.import.sourceSnapshotMismatch");
        }
        return masked;
    }

    private Map<String, String> loadVerifiedMapping(ImportAuthorizedContext context, PublishedRun run) {
        byte[] bytes = jdbc.query("""
                SELECT canonical_bytes FROM t_import_mapping
                WHERE tenant_id=? AND id=? AND mapping_version=? AND mapping_sha256=?
                """, rs -> rs.next() ? rs.getBytes(1) : null, context.tenantId(), run.mappingId(),
                run.mappingVersion(), run.mappingSha());
        if (bytes == null || !MessageDigest.isEqual(sha256(bytes), run.mappingSha())) {
            throw BusinessException.of(409, "error.import.mappingSnapshotMismatch");
        }
        try {
            Map<String, String> decoded = objectMapper.readValue(bytes, MAPPING_TYPE);
            if (decoded == null || decoded.isEmpty()
                    || !MessageDigest.isEqual(bytes, ImportMetadataService.canonicalMapping(decoded))) {
                throw BusinessException.of(409, "error.import.mappingSnapshotMismatch");
            }
            return Map.copyOf(decoded);
        } catch (IOException invalid) {
            throw BusinessException.of(409, "error.import.mappingSnapshotMismatch");
        }
    }

    /** 公開済みrunだけを返す。別tenant・別法人・対象外entityは404、未公開（検証前・検証中）は409。 */
    private PublishedRun requirePublishedRun(ImportAuthorizedContext context, long jobId) {
        JobRow job = jdbc.query("""
                SELECT j.state, j.legal_entity_id, j.entity_type, j.schema_version, j.source_encoding,
                       j.source_sha256, j.current_validation_run_id,
                       r.state AS run_state, r.published_slot, r.mapping_id, r.mapping_version, r.mapping_sha256
                FROM t_import_job j
                LEFT JOIN t_import_validation_run r
                  ON r.tenant_id=j.tenant_id AND r.job_id=j.id AND r.id=j.current_validation_run_id
                WHERE j.tenant_id=? AND j.id=?
                """, rs -> rs.next() ? new JobRow(rs.getString("state"), rs.getString("legal_entity_id"),
                rs.getString("entity_type"), rs.getString("schema_version"), rs.getString("source_encoding"),
                rs.getBytes("source_sha256"), rs.getObject("current_validation_run_id", Long.class),
                rs.getString("run_state"), rs.getObject("published_slot", Integer.class),
                rs.getObject("mapping_id", Long.class), rs.getObject("mapping_version", Integer.class),
                rs.getBytes("mapping_sha256")) : null, context.tenantId(), jobId);
        if (job == null || !Long.toString(context.legalEntityId()).equals(job.legalEntityId())
                || !ImportEntity.CUSTOMER.name().equals(job.entity())
                || !SesImportV1Schema.VERSION.equals(job.schema())) {
            throw BusinessException.of(404, "error.import.jobNotFound");
        }
        if (!"VALIDATED".equals(job.state()) || job.runId() == null || !"COMPLETED".equals(job.runState())
                || !Integer.valueOf(1).equals(job.publishedSlot()) || job.mappingId() == null
                || job.mappingVersion() == null || job.mappingSha() == null || job.sourceSha() == null) {
            throw BusinessException.of(409, "error.import.validationNotCompleted");
        }
        ImportEncoding encoding;
        try {
            encoding = ImportEncoding.valueOf(job.encoding());
        } catch (RuntimeException invalid) {
            throw BusinessException.of(409, "error.import.validationRunMismatch");
        }
        return new PublishedRun(job.runId(), encoding, HexFormat.of().formatHex(job.sourceSha()), job.mappingId(),
                job.mappingVersion(), job.mappingSha());
    }

    private void recordRequiredAudit(ImportAuthorizedContext context, String method, String uri, String code) {
        AuditLogService audit = auditProvider == null ? null : auditProvider.getIfAvailable();
        if (audit == null) throw BusinessException.of(503, "error.import.auditUnavailable");
        try {
            audit.recordRequired(SecurityUtils.currentUsername(), method, uri.length() <= 500 ? uri : uri.substring(0, 500),
                    200, code, true);
        } catch (RuntimeException failure) {
            log.error("[取込] 閲覧の必須監査を記録できないため返しません: code={} exceptionType={}", code,
                    LogRedaction.exceptionType(failure));
            throw BusinessException.of(503, "error.import.auditUnavailable");
        }
        requireSameContext(context);
    }

    private void requireSameContext(ImportAuthorizedContext expected) {
        if (!expected.equals(authorization.requireCurrentAuthorization())) {
            throw BusinessException.of(403, "error.forbidden");
        }
    }

    private static String normalizeDisposition(String raw) {
        if (raw == null || raw.isBlank() || "ALL".equals(raw)) return null;
        if ("ACCEPTED".equals(raw) || "REJECTED".equals(raw)) return raw;
        throw BusinessException.of(400, "error.import.invalidPreviewRequest");
    }

    /** {@code ImportValidationAppendService#encodeFindingCodes}の逆変換。形式外・未知codeは409。 */
    static List<String> decodeFindingCodes(byte[] encoded) {
        if (encoded == null || encoded.length > 4096) throw BusinessException.of(409, "error.import.validationRowMismatch");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            byte[] domain = readBytes(input, 64);
            if (!FINDING_DOMAIN.equals(new String(domain, StandardCharsets.US_ASCII)) || input.readInt() != 1) {
                throw BusinessException.of(409, "error.import.validationRowMismatch");
            }
            int count = input.readInt();
            if (count < 0 || count > MAX_FINDING_CODES) throw BusinessException.of(409, "error.import.validationRowMismatch");
            List<String> codes = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String code = new String(readBytes(input, 128), StandardCharsets.US_ASCII);
                if (!ImportValidationCodebook.isFindingCode(code)) {
                    throw BusinessException.of(409, "error.import.validationRowMismatch");
                }
                codes.add(code);
            }
            if (input.available() != 0) throw BusinessException.of(409, "error.import.validationRowMismatch");
            return List.copyOf(codes);
        } catch (IOException malformed) {
            throw BusinessException.of(409, "error.import.validationRowMismatch");
        }
    }

    private static byte[] readBytes(DataInputStream input, int max) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > max) throw new IOException("finding code長が不正です");
        byte[] value = new byte[length];
        input.readFully(value);
        return value;
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** prepare済みのerror export。認可contextとrunを束縛し、呼出元はjob IDだけを参照できる。 */
    public static final class ErrorExport {
        private final ImportAuthorizedContext context;
        private final long jobId;
        private final long runId;

        private ErrorExport(ImportAuthorizedContext context, long jobId, long runId) {
            this.context = context;
            this.jobId = jobId;
            this.runId = runId;
        }

        public long jobId() { return jobId; }
        long runId() { return runId; }
        ImportAuthorizedContext context() { return context; }
    }

    private record StoredRow(long number, String hashHex, String disposition, List<String> codes) { }

    private record PublishedRun(long runId, ImportEncoding encoding, String sourceShaHex, long mappingId,
                                int mappingVersion, byte[] mappingSha) { }

    private record JobRow(String state, String legalEntityId, String entity, String schema, String encoding,
                          byte[] sourceSha, Long runId, String runState, Integer publishedSlot, Long mappingId,
                          Integer mappingVersion, byte[] mappingSha) { }

    /** page内の最終行を読み終えたことを示す内部制御。stack traceを持たない。 */
    private static final class StopScan extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private static final StopScan INSTANCE = new StopScan();

        private StopScan() {
            super("preview scan stop", null, false, false);
        }
    }
}
