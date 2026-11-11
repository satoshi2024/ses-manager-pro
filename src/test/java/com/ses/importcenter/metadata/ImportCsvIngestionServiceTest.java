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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.anyLong;

class ImportCsvIngestionServiceTest {
    private static final ImportAuthorizedContext CONTEXT = new ImportAuthorizedContext("tenant-test", 31, 41);
    private static final ImportUploadSource SOURCE = new ImportUploadSource(51, "fixture-a", 3);
    private static final byte[] CSV = "externalKey,companyName\nC-SYN-1,架空会社\n".getBytes(StandardCharsets.UTF_8);

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
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CSV));
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
                return new ImportAdmissionManifest("fixture-key", CONTEXT.tenantId(), CONTEXT.legalEntityId(),
                        SOURCE.id(), SOURCE.version(), ImportEntity.CUSTOMER, SesImportV1Schema.VERSION,
                        ImportEncoding.UTF8, sourceHash, null, null, null, null, "A1A_CONTENT");
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
}
