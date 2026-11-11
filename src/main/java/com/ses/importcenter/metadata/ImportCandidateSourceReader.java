package com.ses.importcenter.metadata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.importcenter.foundation.CanonicalImportRow;
import com.ses.importcenter.foundation.CanonicalImportRowFingerprint;
import com.ses.importcenter.foundation.CanonicalRowFactory;
import com.ses.importcenter.foundation.ExternalKeyNormalizer;
import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.importcenter.foundation.SesCsvParser;
import com.ses.importcenter.foundation.SesImportV1Schema;
import com.ses.importcenter.foundation.SourceRow;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** source I/Oをmetadata transaction外で完了し、対象rowの一時的な検証材料だけを返す。 */
@Component
final class ImportCandidateSourceReader {
    private static final TypeReference<Map<String, String>> MAPPING_TYPE = new TypeReference<>() { };
    private final ImportMetadataService metadata;
    private final SesCsvParser parser = new SesCsvParser();
    private final ObjectMapper objectMapper;

    ImportCandidateSourceReader(ImportMetadataService metadata, ObjectMapper objectMapper) {
        this.metadata = metadata;
        this.objectMapper = objectMapper;
    }

    VerifiedCandidateRow read(long jobId, long sourceRowNumber, Snapshot snapshot) {
        if (sourceRowNumber < 1) throw BusinessException.of(400, "error.import.sourceRowInvalid");
        Map<String, String> mapping = decodeMapping(snapshot);
        String externalKeyField = mapping.values().stream()
                .filter("externalKey"::equals).findFirst()
                .orElseThrow(() -> BusinessException.of(409, "error.import.mappingSnapshotMismatch"));
        if (mapping.values().stream().filter("externalKey"::equals).count() != 1) {
            throw BusinessException.of(409, "error.import.mappingSnapshotMismatch");
        }

        CandidateRowBuilder candidate = new CandidateRowBuilder(sourceRowNumber, externalKeyField, snapshot.entity());
        String actualSourceHash;
        try (ImportSourceContent content = metadata.openSource(jobId)) {
            if (content.entity() != snapshot.entity() || content.encoding() != snapshot.encoding()
                    || !MessageDigest.isEqual(HexFormat.of().parseHex(snapshot.sourceSha256()),
                    HexFormat.of().parseHex(content.sha256Hex()))) {
                throw BusinessException.of(409, "error.import.sourceSnapshotMismatch");
            }
            var parsed = parser.parseMapped(content.stream(), content.originalFileName(), content.entity(),
                    content.encoding(), mapping, candidate::accept);
            actualSourceHash = parsed.sourceSha256();
        } catch (IOException | IllegalArgumentException failure) {
            throw BusinessException.of(409, "error.import.sourceReadFailed");
        }
        if (!MessageDigest.isEqual(HexFormat.of().parseHex(snapshot.sourceSha256()),
                HexFormat.of().parseHex(actualSourceHash))) {
            throw BusinessException.of(409, "error.import.sourceSnapshotMismatch");
        }
        return candidate.result();
    }

    private Map<String, String> decodeMapping(Snapshot snapshot) {
        byte[] canonicalBytes = snapshot.canonicalMappingBytes();
        try {
            byte[] actual = MessageDigest.getInstance("SHA-256").digest(canonicalBytes);
            if (!MessageDigest.isEqual(actual, snapshot.mappingSha256())) {
                throw BusinessException.of(409, "error.import.mappingSnapshotMismatch");
            }
            Map<String, String> mapping = objectMapper.readValue(canonicalBytes, MAPPING_TYPE);
            if (mapping == null || mapping.isEmpty() || mapping.size() > 200
                    || mapping.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getKey().isBlank()
                    || entry.getValue() == null || entry.getValue().isBlank())) {
                throw BusinessException.of(409, "error.import.mappingSnapshotMismatch");
            }
            if (!MessageDigest.isEqual(canonicalBytes, ImportMetadataService.canonicalMapping(mapping))) {
                throw BusinessException.of(409, "error.import.mappingSnapshotMismatch");
            }
            var definition = SesImportV1Schema.definition(snapshot.entity());
            if (!definition.allowedHeaders().containsAll(mapping.values())
                    || mapping.values().stream().distinct().count() != mapping.size()
                    || !mapping.values().containsAll(definition.requiredHeaders())) {
                throw BusinessException.of(409, "error.import.mappingSnapshotMismatch");
            }
            return Map.copyOf(mapping);
        } catch (IOException | java.security.NoSuchAlgorithmException failure) {
            throw BusinessException.of(409, "error.import.mappingSnapshotMismatch");
        }
    }

    record Snapshot(ImportEntity entity, ImportEncoding encoding, String sourceSha256,
                    byte[] canonicalMappingBytes, byte[] mappingSha256) {
        Snapshot {
            canonicalMappingBytes = canonicalMappingBytes.clone();
            mappingSha256 = mappingSha256.clone();
        }
        @Override public byte[] canonicalMappingBytes() { return canonicalMappingBytes.clone(); }
        @Override public byte[] mappingSha256() { return mappingSha256.clone(); }
    }

    record VerifiedCandidateRow(String externalKey, byte[] sourceRowSha256, byte[] canonicalFingerprint) {
        VerifiedCandidateRow {
            sourceRowSha256 = sourceRowSha256.clone();
            canonicalFingerprint = canonicalFingerprint.clone();
        }
        @Override public byte[] sourceRowSha256() { return sourceRowSha256.clone(); }
        @Override public byte[] canonicalFingerprint() { return canonicalFingerprint.clone(); }
        @Override public String toString() { return "VerifiedCandidateRow[redacted]"; }
    }

    private static final class CandidateRowBuilder {
        private final long requestedRowNumber;
        private final String externalKeyField;
        private final ImportEntity entity;
        private VerifiedCandidateRow result;

        private CandidateRowBuilder(long requestedRowNumber, String externalKeyField, ImportEntity entity) {
            this.requestedRowNumber = requestedRowNumber;
            this.externalKeyField = externalKeyField;
            this.entity = entity;
        }

        private void accept(SourceRow sourceRow) {
            if (sourceRow.sourceRowNumber() != requestedRowNumber) return;
            if (result != null) throw BusinessException.of(409, "error.import.sourceRowAmbiguous");
            Map<String, String> canonicalValues = new LinkedHashMap<>(sourceRow.values());
            final String normalizedExternalKey;
            try {
                normalizedExternalKey = ExternalKeyNormalizer.normalize(canonicalValues.get(externalKeyField));
            } catch (IllegalArgumentException invalidKey) {
                throw BusinessException.of(409, "error.import.identityKeyInvalid");
            }
            SourceRow canonicalSourceRow = new SourceRow(sourceRow.sourceRowNumber(), sourceRow.sourceRowHash(), canonicalValues);
            CanonicalImportRow canonical = CanonicalRowFactory.create(entity, canonicalSourceRow);
            String key = canonical.metadata().externalKey();
            if (key == null || !normalizedExternalKey.equals(key)) {
                throw BusinessException.of(409, "error.import.identityKeyInvalid");
            }
            result = new VerifiedCandidateRow(key, HexFormat.of().parseHex(sourceRow.sourceRowHash()),
                    CanonicalImportRowFingerprint.sha256(canonical));
        }

        private VerifiedCandidateRow result() {
            if (result == null) throw BusinessException.of(404, "error.import.sourceRowNotFound");
            return result;
        }
    }
}
