package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;
import com.ses.entity.Document;
import com.ses.entity.DocumentVersion;
import com.ses.importcenter.foundation.ImportEncoding;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.importcenter.foundation.SesImportV1Schema;
import com.ses.service.DocumentService;
import com.ses.service.impl.StagedImportDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.anyLong;

class ImportCsvIngestionServiceTest {
    private static final ImportAuthorizedContext CONTEXT = new ImportAuthorizedContext("tenant-test", 31, 41);
    private static final ImportUploadSource SOURCE = new ImportUploadSource(51, "fixture-a", 3);
    private static final ImportAuthorizedContext OTHER_CONTEXT = new ImportAuthorizedContext("tenant-test", 31, 42);
    private static final byte[] CSV = "externalKey,companyName\nC-SYN-1,架空会社\n".getBytes(StandardCharsets.UTF_8);
    private static final String CSV_HASH = sha256(CSV);

    @Test
    void defaultDenyAdmissionRejectsBeforeOpeningRequestStream() {
        ImportMetadataAuthorizationService authorization = mock(ImportMetadataAuthorizationService.class);
        ImportMetadataService metadata = mock(ImportMetadataService.class);
        DocumentService documents = mock(DocumentService.class);
        when(authorization.requireCurrentAuthorization()).thenReturn(CONTEXT);
        when(metadata.resolveActiveUploadSource("fixture-a")).thenReturn(SOURCE);
        ImportCsvIngestionService service = new ImportCsvIngestionService(authorization, metadata,
                new DefaultDenyImportAdmissionPort(), documents, mock(ImportCsvUploadCommitService.class));
        AtomicBoolean opened = new AtomicBoolean();

        BusinessException failure = assertThrows(BusinessException.class, () -> service.upload(
                new ImportRawUploadHeaders("fixture-a", ImportEncoding.UTF8), () -> {
                    opened.set(true);
                    return new ByteArrayInputStream(CSV);
                }));

        assertEquals(403, failure.getCode());
        assertFalse(opened.get());
        verify(documents, never()).stageReceivedForImport(any(), any(), anyLong(), any());
    }

    @Test
    void rawBodyHardLimitRejectsByteSixtyFourMiBPlusOne() {
        ImportMetadataAuthorizationService authorization = mock(ImportMetadataAuthorizationService.class);
        ImportMetadataService metadata = mock(ImportMetadataService.class);
        DocumentService documents = mock(DocumentService.class);
        when(authorization.requireCurrentAuthorization()).thenReturn(CONTEXT);
        when(metadata.resolveActiveUploadSource("fixture-a")).thenReturn(SOURCE);
        ImportCsvIngestionService service = new ImportCsvIngestionService(authorization, metadata,
                fixedFixtureAdmission(), documents, mock(ImportCsvUploadCommitService.class));

        BusinessException tooLarge = assertThrows(BusinessException.class, () -> service.upload(
                new ImportRawUploadHeaders("fixture-a", ImportEncoding.UTF8),
                () -> new RepeatingInputStream(64L * 1024 * 1024 + 1)));

        assertEquals(413, tooLarge.getCode());
        verify(documents, never()).stageReceivedForImport(any(), any(), anyLong(), any());
    }

    @Test
    void admissionContextMismatchRejectsBeforeOpeningRequestStream() {
        ImportMetadataAuthorizationService authorization = mock(ImportMetadataAuthorizationService.class);
        ImportMetadataService metadata = mock(ImportMetadataService.class);
        DocumentService documents = mock(DocumentService.class);
        when(authorization.requireCurrentAuthorization()).thenReturn(CONTEXT);
        when(metadata.resolveActiveUploadSource("fixture-a")).thenReturn(SOURCE);
        ImportAdmissionPort mismatched = new ImportAdmissionPort() {
            @Override public ImportAdmissionToken admitBeforeBody(ImportAuthorizedContext context,
                    ImportUploadSource source, ImportEntity entity, String schema, ImportEncoding encoding) {
                return new ImportAdmissionToken(context.tenantId(), context.actorUserId(), context.legalEntityId() + 1,
                        source.id(), source.version(), entity, schema, encoding, "fixture-key");
            }
            @Override public ImportAdmissionManifest admitContent(ImportAdmissionToken token, String hash) {
                throw new AssertionError("body must not be read");
            }
        };
        ImportCsvIngestionService service = new ImportCsvIngestionService(authorization, metadata, mismatched,
                documents, mock(ImportCsvUploadCommitService.class));
        AtomicBoolean opened = new AtomicBoolean();

        assertThrows(BusinessException.class, () -> service.upload(
                new ImportRawUploadHeaders("fixture-a", ImportEncoding.UTF8), () -> {
                    opened.set(true);
                    return new ByteArrayInputStream(CSV);
                }));

        assertFalse(opened.get());
        verify(documents, never()).stageReceivedForImport(any(), any(), anyLong(), any());
    }

    @Test
    void approvedSyntheticCustomerUploadStagesExactBytesAndReturnsRedactedUploadedDetail() throws Exception {
        ImportMetadataAuthorizationService authorization = mock(ImportMetadataAuthorizationService.class);
        ImportMetadataService metadata = mock(ImportMetadataService.class);
        DocumentService documents = mock(DocumentService.class);
        ImportCsvUploadCommitService commit = mock(ImportCsvUploadCommitService.class);
        when(authorization.requireCurrentAuthorization()).thenReturn(CONTEXT);
        when(metadata.resolveActiveUploadSource("fixture-a")).thenReturn(SOURCE);
        when(metadata.findOrReuseExistingUpload(eq(SOURCE), eq(ImportEncoding.UTF8), any())).thenReturn(null);
        String hash = CSV_HASH;
        ImportAdmissionToken token = new ImportAdmissionToken(CONTEXT.tenantId(), CONTEXT.actorUserId(),
                CONTEXT.legalEntityId(), SOURCE.id(), SOURCE.version(), ImportEntity.CUSTOMER,
                SesImportV1Schema.VERSION, ImportEncoding.UTF8, "fixture-key");
        ImportAdmissionPort fixture = new ImportAdmissionPort() {
            @Override public ImportAdmissionToken admitBeforeBody(ImportAuthorizedContext context,
                    ImportUploadSource source, ImportEntity entity, String schema, ImportEncoding encoding) {
                assertEquals(CONTEXT, context);
                assertEquals(ImportEntity.CUSTOMER, entity);
                return token;
            }
            @Override public ImportAdmissionManifest admitContent(ImportAdmissionToken value, String sourceHash) {
                assertEquals(token, value);
                if (!CSV_HASH.equals(sourceHash)) throw BusinessException.of(403, "error.import.fixtureContentMismatch");
                return new ImportAdmissionManifest("fixture-key", CONTEXT.tenantId(), CONTEXT.legalEntityId(),
                        SOURCE.id(), SOURCE.version(), ImportEntity.CUSTOMER, SesImportV1Schema.VERSION,
                        ImportEncoding.UTF8, CSV_HASH, null, null, null, null, "A1A_CONTENT");
            }
        };
        Document document = new Document();
        document.setId(81L);
        document.setTenantId(CONTEXT.tenantId());
        document.setLegalEntityId(Long.toString(CONTEXT.legalEntityId()));
        document.setDocumentType("IMPORT");
        DocumentVersion version = new DocumentVersion();
        version.setId(91L);
        version.setDocumentId(81L);
        version.setTenantId(CONTEXT.tenantId());
        version.setVersionNo(1);
        version.setStorageKey("tenant-test/import/unique-stage");
        version.setSizeBytes((long) CSV.length);
        version.setSha256(hash);
        version.setScanStatus("CLEAN");
        StagedImportDocument staged = new StagedImportDocument(document, version, version.getStorageKey(), "operation-test");
        when(documents.stageReceivedForImport(any(), any(), eq((long) CSV.length), eq(hash))).thenReturn(staged);
        ImportUploadJobDetail expected = new ImportUploadJobDetail(101, "UPLOADED", "CUSTOMER",
                hash.substring(0, 12), 1, "UTF8");
        when(commit.commit(token, new ImportAdmissionManifest("fixture-key", CONTEXT.tenantId(),
                CONTEXT.legalEntityId(), SOURCE.id(), SOURCE.version(), ImportEntity.CUSTOMER,
                SesImportV1Schema.VERSION, ImportEncoding.UTF8, hash, null, null, null, null, "A1A_CONTENT"), staged))
                .thenReturn(expected);
        ImportCsvIngestionService service = new ImportCsvIngestionService(authorization, metadata, fixture, documents, commit);

        ImportUploadJobDetail result = service.upload(new ImportRawUploadHeaders("fixture-a", ImportEncoding.UTF8),
                () -> new ByteArrayInputStream(CSV));

        assertEquals(expected, result);
        assertEquals("UPLOADED", result.state());
        assertEquals("CUSTOMER", result.entity());
        assertEquals(hash.substring(0, 12), result.sourceHashPrefix());
        verify(documents).stageReceivedForImport(any(), any(), eq((long) CSV.length), eq(hash));
        verify(documents, never()).discardImportStage(staged);
    }

    @Test
    void legalEntityContextChangeAfterStagePreventsCommitAndDiscardsOnlyThatStage() {
        ImportMetadataAuthorizationService authorization = mock(ImportMetadataAuthorizationService.class);
        ImportMetadataService metadata = mock(ImportMetadataService.class);
        DocumentService documents = mock(DocumentService.class);
        ImportCsvUploadCommitService commit = mock(ImportCsvUploadCommitService.class);
        when(authorization.requireCurrentAuthorization()).thenReturn(CONTEXT, CONTEXT, CONTEXT, CONTEXT, OTHER_CONTEXT);
        when(metadata.resolveActiveUploadSource("fixture-a")).thenReturn(SOURCE);
        when(metadata.findOrReuseExistingUpload(SOURCE, ImportEncoding.UTF8, CSV_HASH)).thenReturn(null);
        StagedImportDocument staged = stagedFixture();
        when(documents.stageReceivedForImport(any(), any(), eq((long) CSV.length), eq(CSV_HASH))).thenReturn(staged);

        ImportCsvIngestionService service = new ImportCsvIngestionService(authorization, metadata, fixedFixtureAdmission(),
                documents, commit);

        assertThrows(BusinessException.class, () -> service.upload(
                new ImportRawUploadHeaders("fixture-a", ImportEncoding.UTF8), () -> new ByteArrayInputStream(CSV)));

        verify(documents).discardImportStage(staged);
        verify(commit, never()).commit(any(), any(), any());
    }

    @Test
    void cleanupFailureIsReportedInsteadOfReturningOrHidingSuccess() {
        ImportMetadataAuthorizationService authorization = mock(ImportMetadataAuthorizationService.class);
        ImportMetadataService metadata = mock(ImportMetadataService.class);
        DocumentService documents = mock(DocumentService.class);
        ImportCsvUploadCommitService commit = mock(ImportCsvUploadCommitService.class);
        when(authorization.requireCurrentAuthorization()).thenReturn(CONTEXT);
        when(metadata.resolveActiveUploadSource("fixture-a")).thenReturn(SOURCE);
        when(metadata.findOrReuseExistingUpload(SOURCE, ImportEncoding.UTF8, CSV_HASH)).thenReturn(null);
        StagedImportDocument staged = stagedFixture();
        when(documents.stageReceivedForImport(any(), any(), eq((long) CSV.length), eq(CSV_HASH))).thenReturn(staged);
        when(commit.commit(any(), any(), eq(staged))).thenThrow(BusinessException.of(500, "error.test.commitFailure"));
        doThrow(BusinessException.of(500, "error.test.cleanupFailure")).when(documents).discardImportStage(staged);
        ImportCsvIngestionService service = new ImportCsvIngestionService(authorization, metadata, fixedFixtureAdmission(),
                documents, commit);

        BusinessException failure = assertThrows(BusinessException.class, () -> service.upload(
                new ImportRawUploadHeaders("fixture-a", ImportEncoding.UTF8), () -> new ByteArrayInputStream(CSV)));

        assertEquals(500, failure.getCode());
        assertEquals("error.test.cleanupFailure", failure.getMessageKey());
        verify(documents).discardImportStage(staged);
    }

    @Test
    void uncertainCommitReadbackRetainsThePossiblyCommittedStage() {
        ImportMetadataAuthorizationService authorization = mock(ImportMetadataAuthorizationService.class);
        ImportMetadataService metadata = mock(ImportMetadataService.class);
        DocumentService documents = mock(DocumentService.class);
        ImportCsvUploadCommitService commit = mock(ImportCsvUploadCommitService.class);
        when(authorization.requireCurrentAuthorization()).thenReturn(CONTEXT);
        when(metadata.resolveActiveUploadSource("fixture-a")).thenReturn(SOURCE);
        when(metadata.findOrReuseExistingUpload(SOURCE, ImportEncoding.UTF8, CSV_HASH)).thenReturn(null);
        StagedImportDocument staged = stagedFixture();
        when(documents.stageReceivedForImport(any(), any(), eq((long) CSV.length), eq(CSV_HASH))).thenReturn(staged);
        when(commit.commit(any(), any(), eq(staged))).thenThrow(new IllegalStateException("commit acknowledgement lost"));
        when(metadata.findCommittedUploadForStage(SOURCE, ImportEncoding.UTF8, CSV_HASH, staged)).thenReturn(null);
        ImportCsvIngestionService service = new ImportCsvIngestionService(authorization, metadata, fixedFixtureAdmission(),
                documents, commit);

        assertThrows(IllegalStateException.class, () -> service.upload(
                new ImportRawUploadHeaders("fixture-a", ImportEncoding.UTF8), () -> new ByteArrayInputStream(CSV)));

        verify(documents, never()).discardImportStage(staged);
    }

    private static ImportAdmissionPort fixedFixtureAdmission() {
        ImportAdmissionToken token = new ImportAdmissionToken(CONTEXT.tenantId(), CONTEXT.actorUserId(),
                CONTEXT.legalEntityId(), SOURCE.id(), SOURCE.version(), ImportEntity.CUSTOMER,
                SesImportV1Schema.VERSION, ImportEncoding.UTF8, "unit-fixed-fixture");
        return new ImportAdmissionPort() {
            @Override public ImportAdmissionToken admitBeforeBody(ImportAuthorizedContext context,
                    ImportUploadSource source, ImportEntity entity, String schema, ImportEncoding encoding) {
                return context.equals(CONTEXT) && source.equals(SOURCE) && entity == ImportEntity.CUSTOMER
                        && SesImportV1Schema.VERSION.equals(schema) && encoding == ImportEncoding.UTF8 ? token : null;
            }
            @Override public ImportAdmissionManifest admitContent(ImportAdmissionToken candidate, String hash) {
                if (!token.equals(candidate) || !CSV_HASH.equals(hash)) {
                    throw BusinessException.of(403, "error.import.fixtureContentMismatch");
                }
                return new ImportAdmissionManifest(token.fixtureKey(), CONTEXT.tenantId(), CONTEXT.legalEntityId(),
                        SOURCE.id(), SOURCE.version(), ImportEntity.CUSTOMER, SesImportV1Schema.VERSION,
                        ImportEncoding.UTF8, CSV_HASH, null, null, null, null, "A1A_CONTENT");
            }
        };
    }

    private static StagedImportDocument stagedFixture() {
        Document document = new Document();
        document.setId(81L);
        document.setTenantId(CONTEXT.tenantId());
        document.setLegalEntityId(Long.toString(CONTEXT.legalEntityId()));
        document.setDocumentType("IMPORT");
        DocumentVersion version = new DocumentVersion();
        version.setId(91L);
        version.setDocumentId(81L);
        version.setTenantId(CONTEXT.tenantId());
        version.setVersionNo(1);
        version.setStorageKey("unit-fixed-fixture/object");
        version.setSizeBytes((long) CSV.length);
        version.setSha256(CSV_HASH);
        version.setScanStatus("CLEAN");
        return new StagedImportDocument(document, version, version.getStorageKey(), "unit-fixed-operation");
    }

    private static final class RepeatingInputStream extends InputStream {
        private long remaining;

        private RepeatingInputStream(long size) { remaining = size; }

        @Override public int read() {
            if (remaining == 0) return -1;
            remaining--;
            return 'x';
        }

        @Override public int read(byte[] bytes, int offset, int length) {
            if (remaining == 0) return -1;
            int count = (int) Math.min(remaining, length);
            java.util.Arrays.fill(bytes, offset, offset + count, (byte) 'x');
            remaining -= count;
            return count;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
