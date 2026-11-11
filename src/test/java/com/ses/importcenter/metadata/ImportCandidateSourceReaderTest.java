package com.ses.importcenter.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.importcenter.foundation.CanonicalImportRowFingerprint;
import com.ses.importcenter.foundation.CanonicalRowFactory;
import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.importcenter.foundation.SourceRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ImportCandidateSourceReaderTest {
    @Mock private ImportMetadataService metadata;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ImportCandidateSourceReader reader;

    @BeforeEach
    void setUp() {
        reader = new ImportCandidateSourceReader(metadata, objectMapper);
    }

    @Test
    void exactSourceReaderDerivesKeyAndSharedFingerprintFromRequestedRow() throws Exception {
        byte[] source = "externalKey,companyName\ncustomer-1,Example\ncustomer-2,Second\n"
                .getBytes(StandardCharsets.UTF_8);
        String sourceHash = sha256(source);
        byte[] mapping = ImportMetadataService.canonicalMapping(Map.of(
                "externalKey", "externalKey", "companyName", "companyName"));
        byte[] mappingHash = MessageDigest.getInstance("SHA-256").digest(mapping);
        when(metadata.openSource(9L)).thenReturn(new ImportSourceContent(new ByteArrayInputStream(source),
                "source.csv", ImportEncoding.UTF8, ImportEntity.CUSTOMER, sourceHash));

        var actual = reader.read(9L, 2L, new ImportCandidateSourceReader.Snapshot(ImportEntity.CUSTOMER,
                ImportEncoding.UTF8, sourceHash, mapping, mappingHash));

        SourceRow expectedSourceRow = new SourceRow(2L, hashRow("customer-2", "Second"),
                Map.of("externalKey", "customer-2", "companyName", "Second"));
        var expectedCanonicalRow = CanonicalRowFactory.create(ImportEntity.CUSTOMER, expectedSourceRow);
        assertEquals("customer-2", actual.externalKey());
        assertArrayEquals(HexFormat.of().parseHex(expectedSourceRow.sourceRowHash()), actual.sourceRowSha256());
        assertArrayEquals(CanonicalImportRowFingerprint.sha256(expectedCanonicalRow), actual.canonicalFingerprint());
    }

    @Test
    void rejectsReaderContentWhoseSnapshotHashDiffersBeforeReturningAKey() throws Exception {
        byte[] source = "externalKey,companyName\ncustomer-1,Example\n".getBytes(StandardCharsets.UTF_8);
        byte[] mapping = ImportMetadataService.canonicalMapping(Map.of("externalKey", "externalKey", "companyName", "companyName"));
        byte[] mappingHash = MessageDigest.getInstance("SHA-256").digest(mapping);
        when(metadata.openSource(9L)).thenReturn(new ImportSourceContent(new ByteArrayInputStream(source),
                "source.csv", ImportEncoding.UTF8, ImportEntity.CUSTOMER, "00".repeat(32)));

        assertThrows(RuntimeException.class, () -> reader.read(9L, 1L,
                new ImportCandidateSourceReader.Snapshot(ImportEntity.CUSTOMER, ImportEncoding.UTF8,
                        "11".repeat(32), mapping, mappingHash)));
    }

    @Test
    void rejectsNonCanonicalMappingBytesIncludingDuplicateJsonKeys() throws Exception {
        byte[] mapping = "{\"companyName\":\"companyName\",\"externalKey\":\"externalKey\",\"externalKey\":\"externalKey\"}"
                .getBytes(StandardCharsets.UTF_8);
        byte[] mappingHash = MessageDigest.getInstance("SHA-256").digest(mapping);

        assertThrows(RuntimeException.class, () -> reader.read(9L, 1L,
                new ImportCandidateSourceReader.Snapshot(ImportEntity.CUSTOMER, ImportEncoding.UTF8,
                        "11".repeat(32), mapping, mappingHash)));
        verifyNoInteractions(metadata);
    }

    @Test
    void appliesExplicitAliasMappingBeforeCanonicalRowConversionAndRedactsCandidateKey() throws Exception {
        byte[] source = "顧客ID,会社名\ncustomer 1,Example\n".getBytes(StandardCharsets.UTF_8);
        String sourceHash = sha256(source);
        Map<String, String> mappingValues = Map.of("顧客ID", "externalKey", "会社名", "companyName");
        byte[] mapping = ImportMetadataService.canonicalMapping(mappingValues);
        byte[] mappingHash = MessageDigest.getInstance("SHA-256").digest(mapping);
        when(metadata.openSource(9L)).thenReturn(new ImportSourceContent(new ByteArrayInputStream(source),
                "source.csv", ImportEncoding.UTF8, ImportEntity.CUSTOMER, sourceHash));

        var actual = reader.read(9L, 1L, new ImportCandidateSourceReader.Snapshot(ImportEntity.CUSTOMER,
                ImportEncoding.UTF8, sourceHash, mapping, mappingHash));

        assertEquals("customer 1", actual.externalKey());
        assertEquals("VerifiedCandidateRow[redacted]", actual.toString());
    }

    @Test
    void rejectsDuplicateCanonicalTargetsBeforeOpeningSource() throws Exception {
        Map<String, String> duplicateTargets = Map.of("externalKey", "externalKey", "alternateKey", "externalKey",
                "companyName", "companyName");
        byte[] mapping = ImportMetadataService.canonicalMapping(duplicateTargets);
        byte[] mappingHash = MessageDigest.getInstance("SHA-256").digest(mapping);

        assertThrows(RuntimeException.class, () -> reader.read(9L, 1L,
                new ImportCandidateSourceReader.Snapshot(ImportEntity.CUSTOMER, ImportEncoding.UTF8,
                        "11".repeat(32), mapping, mappingHash)));
        verifyNoInteractions(metadata);
    }

    @Test
    void rejectsInvalidIdentityKeyInsteadOfReturningUnnormalizedValue() throws Exception {
        byte[] source = "externalKey,companyName\n bad,Example\n".getBytes(StandardCharsets.UTF_8);
        String sourceHash = sha256(source);
        byte[] mapping = ImportMetadataService.canonicalMapping(Map.of("externalKey", "externalKey",
                "companyName", "companyName"));
        byte[] mappingHash = MessageDigest.getInstance("SHA-256").digest(mapping);
        when(metadata.openSource(9L)).thenReturn(new ImportSourceContent(new ByteArrayInputStream(source),
                "source.csv", ImportEncoding.UTF8, ImportEntity.CUSTOMER, sourceHash));

        assertThrows(RuntimeException.class, () -> reader.read(9L, 1L,
                new ImportCandidateSourceReader.Snapshot(ImportEntity.CUSTOMER, ImportEncoding.UTF8,
                        sourceHash, mapping, mappingHash)));
    }

    @Test
    void readsToEofAndRejectsActualBytesWhoseDigestDiffersFromSnapshot() throws Exception {
        byte[] source = "externalKey,companyName\ncustomer-1,Changed\n".getBytes(StandardCharsets.UTF_8);
        String registeredHash = "11".repeat(32);
        byte[] mapping = ImportMetadataService.canonicalMapping(Map.of("externalKey", "externalKey",
                "companyName", "companyName"));
        byte[] mappingHash = MessageDigest.getInstance("SHA-256").digest(mapping);
        when(metadata.openSource(9L)).thenReturn(new ImportSourceContent(new ByteArrayInputStream(source),
                "source.csv", ImportEncoding.UTF8, ImportEntity.CUSTOMER, registeredHash));

        assertThrows(RuntimeException.class, () -> reader.read(9L, 1L,
                new ImportCandidateSourceReader.Snapshot(ImportEntity.CUSTOMER, ImportEncoding.UTF8,
                        registeredHash, mapping, mappingHash)));
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static String hashRow(String key, String companyName) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : new String[]{key, companyName}) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
