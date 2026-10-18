package com.ses.importcenter.metadata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.importcenter.foundation.ImportFinding;
import com.ses.importcenter.foundation.ImportValidationPipeline;
import com.ses.importcenter.foundation.ImportValidationService;
import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ReadOnlyImportResolver;
import com.ses.importcenter.foundation.ValidationContext;
import com.ses.importcenter.foundation.ValidatedRow;
import com.ses.importcenter.foundation.SesCsvParser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** F1b-2: exact sourceを先頭から検証し、privacy-safe row metadataを最大250件ずつappendする。
 * F2 sessionは最大100,000件までexternal key setを保持するため、heapはnonempty row数に対してO(n)となる。 */
@Service
public class ImportValidationAppendService {
    private static final int CHUNK_ROWS = 250;
    private static final int PREFIX_PAGE_SIZE = 250;
    private static final String ROW_COMMIT_DOMAIN = "SES-IMPORT-VALIDATION-ROW-V1";
    private static final String FINDING_DOMAIN = "SES-IMPORT-FINDING-CODES-V1";
    private static final TypeReference<Map<String, String>> MAPPING_TYPE = new TypeReference<>() { };
    private static final Set<String> FINDING_CODES = Set.of(
            "AMOUNT_UNKNOWN_OR_EMPTY", "AMOUNT_PRECISION_EXCEEDED", "AMOUNT_INVALID_OR_FRACTIONAL_YEN",
            "AMOUNT_NEGATIVE_NOT_ALLOWED", "AMOUNT_RANGE_INVALID", "AMOUNT_REQUIRED", "CANONICAL_CONVERSION_FAILED",
            "DATE_INVALID", "DATE_RANGE_INVALID", "DATETIME_INVALID", "DUPLICATE_SOURCE_KEY", "EMAIL_INVALID",
            "EXTERNAL_KEY_INVALID", "EXTERNAL_KEY_REQUIRED", "FIELD_LENGTH_EXCEEDED", "FIELD_REQUIRED",
            "INTEGER_INVALID", "REFERENCE_AMBIGUOUS", "REFERENCE_DELETED", "REFERENCE_IDENTITY_MISMATCH",
            "REFERENCE_INVALID", "REFERENCE_KEY_INVALID", "REFERENCE_LOOKUP_UNKNOWN", "REFERENCE_NOT_FOUND",
            "REFERENCE_OUT_OF_SCOPE", "REFERENCE_REQUIRED", "RELATIONSHIP_MISMATCH", "RELATIONSHIP_UNVERIFIABLE",
            "STATE_OR_ENUM_NOT_ALLOWED");

    private final JdbcTemplate jdbc;
    private final ImportMetadataService metadata;
    private final ImportValidationLifecycleService lifecycle;
    private final ObjectMapper objectMapper;
    private final ImportValidationPipeline pipeline = new ImportValidationPipeline(
            new SesCsvParser(), new ImportValidationService());
    private final ReadOnlyImportResolver failClosedResolver;

    public ImportValidationAppendService(JdbcTemplate jdbc, ImportMetadataService metadata,
                                         ImportValidationLifecycleService lifecycle, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.metadata = metadata;
        this.lifecycle = lifecycle;
        this.objectMapper = objectMapper;
        this.failClosedResolver = (type, tenant, legalEntity, key) -> null;
    }

    /** callerはrow metadataを渡せない。run leaseからexact inputを導出してsource全体を再検証する。 */
    public ImportValidationLifecycleService.RunLease appendRemaining(ImportValidationLifecycleService.RunLease lease) {
        ImportValidationLifecycleService.RunLease authorizedLease = lifecycle.renew(lease);
        var snapshot = lifecycle.appendSnapshot(authorizedLease);
        Map<String, String> mapping = loadMapping(snapshot);
        ValidationContext context = new ValidationContext(snapshot.context().tenantId(),
                Long.toString(snapshot.context().legalEntityId()), failClosedResolver);

        ScanResult verified = scan(snapshot, mapping, context, false, authorizedLease);
        if (!MessageDigest.isEqual(snapshot.sourceSha(), HexFormat.of().parseHex(verified.sourceSha256()))) conflict();

        ImportValidationLifecycleService.RunLease currentLease = lifecycle.renew(verified.lease());
        ScanResult appended = scan(snapshot, mapping, context, true, currentLease);
        if (!MessageDigest.isEqual(snapshot.sourceSha(), HexFormat.of().parseHex(appended.sourceSha256()))) conflict();
        return lifecycle.renew(appended.lease());
    }

    private ScanResult scan(ImportValidationLifecycleService.AppendSnapshot snapshot, Map<String, String> mapping,
                            ValidationContext context, boolean write, ImportValidationLifecycleService.RunLease initialLease) {
        PrefixCursor prefix = new PrefixCursor(snapshot);
        List<RowMetadata> batch = new ArrayList<>(CHUNK_ROWS);
        ImportValidationLifecycleService.RunLease[] current = {initialLease};
        long[] processedRows = {0};
        String sourceSha;
        try (ImportSourceContent content = metadata.openSource(snapshot.jobId())) {
            verifyContent(snapshot, content);
            var result = pipeline.validateMapped(content.stream(), content.originalFileName(),
                    ImportEntity.valueOf(snapshot.entity()), ImportEncoding.valueOf(snapshot.encoding()), mapping,
                    context, validated -> {
                        processedRows[0]++;
                        RowMetadata row = rowMetadata(validated);
                        boolean existingPrefix = prefix.compare(row);
                        if (write && !existingPrefix) {
                            batch.add(row);
                            if (batch.size() == CHUNK_ROWS) {
                                lifecycle.appendChunk(current[0], new ValidatedChunk(current[0],
                                        lifecycle.appendSnapshot(current[0]), batch));
                                batch.clear();
                                current[0] = lifecycle.renew(current[0]);
                            }
                        } else if (!write && processedRows[0] % CHUNK_ROWS == 0) {
                            current[0] = lifecycle.renew(current[0]);
                        }
                    });
            prefix.requireExhausted();
            sourceSha = result.source().sourceSha256();
            if (write && !batch.isEmpty()) {
                lifecycle.appendChunk(current[0], new ValidatedChunk(current[0],
                        lifecycle.appendSnapshot(current[0]), batch));
                batch.clear();
            }
        } catch (IOException | IllegalArgumentException failure) {
            throw BusinessException.of(409, "error.import.sourceReadFailed");
        }
        return new ScanResult(sourceSha, current[0]);
    }

    private void verifyContent(ImportValidationLifecycleService.AppendSnapshot snapshot, ImportSourceContent content) {
        if (!content.sha256Hex().equalsIgnoreCase(HexFormat.of().formatHex(snapshot.sourceSha()))
                || content.entity() != ImportEntity.valueOf(snapshot.entity())
                || content.encoding() != ImportEncoding.valueOf(snapshot.encoding())
                || !MessageDigest.isEqual(snapshot.sourceSha(), snapshot.documentSha())) conflict();
    }

    private Map<String, String> loadMapping(ImportValidationLifecycleService.AppendSnapshot snapshot) {
        Mapping mapping = jdbc.query("SELECT canonical_bytes,mapping_sha256 FROM t_import_mapping WHERE tenant_id=? AND id=?",
                rs -> rs.next() ? new Mapping(rs.getBytes(1), rs.getBytes(2)) : null,
                snapshot.context().tenantId(), snapshot.mappingId());
        if (mapping == null || !MessageDigest.isEqual(mapping.sha(), snapshot.mappingSha())
                || !MessageDigest.isEqual(sha256(mapping.bytes()), mapping.sha())) conflict();
        try {
            Map<String, String> decoded = objectMapper.readValue(mapping.bytes(), MAPPING_TYPE);
            if (decoded == null || decoded.isEmpty() || !MessageDigest.isEqual(mapping.bytes(),
                    ImportMetadataService.canonicalMapping(decoded))) conflict();
            ImportEntity entity = ImportEntity.valueOf(snapshot.entity());
            var definition = com.ses.importcenter.foundation.SesImportV1Schema.definition(entity);
            if (!definition.allowedHeaders().containsAll(decoded.values())
                    || decoded.values().stream().distinct().count() != decoded.size()
                    || !decoded.values().containsAll(definition.requiredHeaders())) conflict();
            return Map.copyOf(decoded);
        } catch (IOException | RuntimeException failure) {
            throw BusinessException.of(409, "error.import.mappingSnapshotMismatch");
        }
    }

    private RowMetadata rowMetadata(ValidatedRow validated) {
        TreeSet<String> codes = new TreeSet<>();
        for (ImportFinding finding : validated.findings()) {
            if (finding.code() == null || !FINDING_CODES.contains(finding.code())) conflict();
            codes.add(finding.code());
        }
        byte[] findingBytes = encodeFindingCodes(codes);
        if (findingBytes.length > 4096) conflict();
        byte[] sourceHash;
        try { sourceHash = HexFormat.of().parseHex(validated.row().metadata().sourceRowHash()); }
        catch (IllegalArgumentException invalidHash) { throw BusinessException.of(409, "error.import.sourceSnapshotMismatch"); }
        byte[] fingerprint = validated.canonicalFingerprint();
        String disposition = codes.isEmpty() ? "ACCEPTED" : "REJECTED";
        byte[] commitment = rowCommit(validated.row().metadata().sourceRowNumber(), sourceHash,
                fingerprint, disposition, findingBytes);
        return new RowMetadata(validated.row().metadata().sourceRowNumber(), sourceHash, fingerprint,
                disposition, findingBytes, commitment);
    }

    static byte[] encodeFindingCodes(Set<String> codes) {
        if (codes == null) throw BusinessException.of(409, "error.import.jobStateConflict");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            writeBytes(out, FINDING_DOMAIN.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            out.writeInt(1);
            TreeSet<String> sorted = new TreeSet<>(codes);
            out.writeInt(sorted.size());
            for (String code : sorted) {
                if (!FINDING_CODES.contains(code)) throw BusinessException.of(409, "error.import.jobStateConflict");
                writeBytes(out, code.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
            out.flush();
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > 4096) throw BusinessException.of(409, "error.import.jobStateConflict");
            return encoded;
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] rowCommit(long rowNumber, byte[] sourceHash, byte[] canonicalFingerprint,
                                    String disposition, byte[] findingBytes) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            writeBytes(out, ROW_COMMIT_DOMAIN.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            out.writeInt(1); out.writeLong(rowNumber);
            writeBytes(out, sourceHash); writeBytes(out, canonicalFingerprint);
            writeBytes(out, disposition.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            writeBytes(out, findingBytes);
            out.flush();
            return sha256(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] value) throws IOException {
        out.writeInt(value.length); out.write(value);
    }

    private static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private void conflict() { throw BusinessException.of(409, "error.import.jobStateConflict"); }

    private record Mapping(byte[] bytes, byte[] sha) { }
    private record ScanResult(String sourceSha256, ImportValidationLifecycleService.RunLease lease) { }

    private final class PrefixCursor {
        private final ImportValidationLifecycleService.AppendSnapshot snapshot;
        private final long total;
        private long consumed;
        private long after;
        private List<RowMetadata> page = List.of();
        private int index;

        private PrefixCursor(ImportValidationLifecycleService.AppendSnapshot snapshot) {
            this.snapshot = snapshot;
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?",
                    Long.class, snapshot.context().tenantId(), snapshot.jobId(), snapshot.runId());
            this.total = count == null ? 0 : count;
        }

        private boolean compare(RowMetadata actual) {
            if (consumed >= total) return false;
            if (index >= page.size()) loadPage();
            if (page.isEmpty()) conflict();
            RowMetadata expected = page.get(index++);
            if (actual.sourceRowNumber() != expected.sourceRowNumber() || !actual.sameMetadata(expected)) conflict();
            consumed++;
            after = expected.sourceRowNumber();
            return true;
        }

        private void loadPage() {
            page = jdbc.query("""
                    SELECT source_row_number,source_row_sha256,canonical_fingerprint,disposition,finding_codes,row_commit_sha256
                    FROM t_import_validation_row WHERE tenant_id=? AND job_id=? AND validation_run_id=?
                      AND source_row_number>? ORDER BY source_row_number LIMIT ?
                    """, (rs, row) -> new RowMetadata(rs.getLong(1), rs.getBytes(2), rs.getBytes(3),
                    rs.getString(4), rs.getBytes(5), rs.getBytes(6)), snapshot.context().tenantId(),
                    snapshot.jobId(), snapshot.runId(), after, PREFIX_PAGE_SIZE);
            index = 0;
        }

        private void requireExhausted() {
            if (consumed != total) conflict();
        }
    }

    static final class ValidatedChunk {
        private final ImportValidationLifecycleService.RunLease lease;
        private final ImportValidationLifecycleService.AppendSnapshot snapshot;
        private final List<RowMetadata> rows;

        private ValidatedChunk(ImportValidationLifecycleService.RunLease lease,
                               ImportValidationLifecycleService.AppendSnapshot snapshot, List<RowMetadata> rows) {
            this.lease = lease;
            this.snapshot = snapshot;
            this.rows = List.copyOf(rows);
        }

        ImportValidationLifecycleService.RunLease lease() { return lease; }
        List<RowMetadata> rows() { return rows; }

        boolean matches(ImportValidationLifecycleService.AppendSnapshot expected) {
            return snapshot.context().equals(expected.context())
                    && snapshot.jobId() == expected.jobId()
                    && snapshot.sourceId() == expected.sourceId()
                    && snapshot.sourceVersion() == expected.sourceVersion()
                    && snapshot.sourceCode().equals(expected.sourceCode())
                    && snapshot.entity().equals(expected.entity())
                    && snapshot.schema().equals(expected.schema())
                    && snapshot.encoding().equals(expected.encoding())
                    && snapshot.documentId() == expected.documentId()
                    && snapshot.documentVersionId() == expected.documentVersionId()
                    && snapshot.documentVersionNo() == expected.documentVersionNo()
                    && Objects.equals(snapshot.mappingId(), expected.mappingId())
                    && snapshot.mappingVersion() == expected.mappingVersion()
                    && snapshot.runId() == expected.runId()
                    && snapshot.generation() == expected.generation()
                    && snapshot.jobVersion() == expected.jobVersion()
                    && snapshot.workerId().equals(expected.workerId())
                    && Objects.equals(snapshot.expiresAt(), expected.expiresAt())
                    && MessageDigest.isEqual(snapshot.sourceSha(), expected.sourceSha())
                    && MessageDigest.isEqual(snapshot.documentSha(), expected.documentSha())
                    && MessageDigest.isEqual(snapshot.mappingSha(), expected.mappingSha())
                    && MessageDigest.isEqual(snapshot.validatorFingerprint(), expected.validatorFingerprint());
        }
    }

    static final class RowMetadata {
        private final long sourceRowNumber;
        private final byte[] sourceRowSha256, canonicalFingerprint, findingCodes, rowCommitSha256;
        private final String disposition;

        RowMetadata(long sourceRowNumber, byte[] sourceRowSha256, byte[] canonicalFingerprint,
                    String disposition, byte[] findingCodes, byte[] rowCommitSha256) {
            if (sourceRowNumber < 1 || sourceRowSha256 == null || sourceRowSha256.length != 32
                    || canonicalFingerprint == null || canonicalFingerprint.length != 32
                    || !Set.of("ACCEPTED", "REJECTED").contains(disposition)
                    || findingCodes == null || findingCodes.length > 4096
                    || rowCommitSha256 == null || rowCommitSha256.length != 32) {
                throw BusinessException.of(409, "error.import.jobStateConflict");
            }
            this.sourceRowNumber = sourceRowNumber; this.sourceRowSha256 = sourceRowSha256.clone();
            this.canonicalFingerprint = canonicalFingerprint.clone(); this.disposition = disposition;
            this.findingCodes = findingCodes.clone(); this.rowCommitSha256 = rowCommitSha256.clone();
        }
        long sourceRowNumber() { return sourceRowNumber; }
        byte[] sourceRowSha256() { return sourceRowSha256.clone(); }
        byte[] canonicalFingerprint() { return canonicalFingerprint.clone(); }
        String disposition() { return disposition; }
        byte[] findingCodes() { return findingCodes.clone(); }
        byte[] rowCommitSha256() { return rowCommitSha256.clone(); }

        boolean sameMetadata(RowMetadata other) {
            return sourceRowNumber == other.sourceRowNumber && disposition.equals(other.disposition)
                    && MessageDigest.isEqual(sourceRowSha256, other.sourceRowSha256)
                    && MessageDigest.isEqual(canonicalFingerprint, other.canonicalFingerprint)
                    && MessageDigest.isEqual(findingCodes, other.findingCodes)
                    && MessageDigest.isEqual(rowCommitSha256, other.rowCommitSha256);
        }
    }
}
