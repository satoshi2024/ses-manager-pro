package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;
import com.ses.importcenter.foundation.ImportErrorCsvWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A1aのpreview（マスク・page上限・scope）とerror export（CSV injection・stream・scope）。 */
class ImportValidationResultServiceTest {
    private static final String CSV = """
            externalKey,companyName,contactPerson,contactEmail,contactPhone,address
            C-1,株式会社アルファ,山田太郎,taro@example.co.jp,03-1234-5678,東京都千代田区1-1
            C-1,"=HYPERLINK(""http://x"")",佐藤花子,hanako@example.co.jp,03-9999-0000,大阪府大阪市2-2
            C-3,,鈴木一郎,not-an-email,090-1111-2222,福岡県福岡市3-3
            """;

    private ImportA1aH2Fixture fixture;
    private long sourceId;
    private long mappingId;

    @BeforeEach
    void prepare() {
        fixture = new ImportA1aH2Fixture();
        sourceId = fixture.createSource("legacy-crm");
        mappingId = fixture.createMapping(sourceId, ImportA1aH2Fixture.CUSTOMER_MAPPING);
    }

    @Test
    void previewReturnsStoredDispositionsAndMasksPersonalData() {
        long jobId = validated(CSV);

        ImportPreviewPage page = fixture.resultService(System::nanoTime).preview(jobId, 1, 50, null);

        assertEquals(3, page.total());
        assertEquals("ALL", page.disposition());
        assertEquals(3, page.rows().size());
        ImportPreviewRow first = page.rows().get(0);
        assertEquals(1, first.sourceRowNumber());
        assertEquals("ACCEPTED", first.disposition());
        assertEquals(List.of(), first.findingCodes());
        assertEquals("C-1", first.fields().get("externalKey"));
        assertEquals("株式会社アルファ", first.fields().get("companyName"));
        assertEquals("山＊＊", first.fields().get("contactPerson"));
        assertEquals("t＊＊＊@example.co.jp", first.fields().get("contactEmail"));
        assertEquals("＊＊＊＊5678", first.fields().get("contactPhone"));
        assertEquals("＊＊＊", first.fields().get("address"));
        assertEquals(64, first.sourceRowHash().length());
        assertEquals(List.of("DUPLICATE_SOURCE_KEY"), page.rows().get(1).findingCodes());
        assertTrue(page.rows().get(2).findingCodes().containsAll(List.of("EMAIL_INVALID", "FIELD_REQUIRED")));
        String rendered = page.toString();
        for (String secret : List.of("山田太郎", "taro@", "1234-5678", "東京都千代田区", "佐藤花子", "福岡県")) {
            assertFalse(rendered.contains(secret), "previewへ原文の個人情報を出さない: " + secret);
        }
        verify(fixture.audit).recordRequired(any(), eq("GET"), anyString(), eq(200), eq("IMPORT_PREVIEW"), eq(true));
    }

    @Test
    void previewPageSizeIsClampedAndDispositionFilterAndOffsetsApply() {
        long jobId = validated(CSV);
        ImportValidationResultService service = fixture.resultService(System::nanoTime);

        ImportPreviewPage clamped = service.preview(jobId, 0, 100_000, "REJECTED");
        assertEquals(1, clamped.page());
        assertEquals(ImportValidationResultService.PREVIEW_MAX_SIZE, clamped.size());
        assertEquals(2, clamped.total());
        assertEquals(List.of(2L, 3L), clamped.rows().stream().map(ImportPreviewRow::sourceRowNumber).toList());

        ImportPreviewPage second = service.preview(jobId, 2, 1, "ACCEPTED");
        assertEquals(1, second.total());
        assertEquals(List.of(), second.rows(), "範囲外のpageはsourceを読まずに空で返す");

        ImportPreviewPage defaulted = service.preview(jobId, 1, -5, null);
        assertEquals(ImportValidationResultService.PREVIEW_DEFAULT_SIZE, defaulted.size());

        BusinessException invalid = assertThrows(BusinessException.class,
                () -> service.preview(jobId, 1, 10, "DROP TABLE"));
        assertEquals(400, invalid.getCode());
    }

    @Test
    void previewOfAnotherLegalEntityIsNotFoundAndUnvalidatedJobIsConflict() {
        long foreign = fixture.uploadedJob(sourceId, CSV, 99);
        long unvalidated = fixture.uploadedJob(sourceId, CSV.replace("C-3", "C-4"), ImportA1aH2Fixture.LEGAL_ENTITY);
        ImportValidationResultService service = fixture.resultService(System::nanoTime);

        BusinessException notFound = assertThrows(BusinessException.class, () -> service.preview(foreign, 1, 10, null));
        assertEquals(404, notFound.getCode());
        assertEquals("error.import.jobNotFound", notFound.getMessageKey());
        BusinessException exportNotFound = assertThrows(BusinessException.class,
                () -> service.prepareErrorExport(foreign));
        assertEquals(404, exportNotFound.getCode());
        BusinessException pending = assertThrows(BusinessException.class,
                () -> service.preview(unvalidated, 1, 10, null));
        assertEquals(409, pending.getCode());
        assertEquals("error.import.validationNotCompleted", pending.getMessageKey());
        verify(fixture.audit, never()).recordRequired(any(), anyString(), anyString(), anyInt(), anyString(), anyBoolean());
    }

    @Test
    void previewAndExportAreRefusedWhenTheRequiredAuditCannotBeWritten() {
        long jobId = validated(CSV);
        doThrow(new IllegalStateException("audit down")).when(fixture.audit)
                .recordRequired(any(), anyString(), anyString(), anyInt(), anyString(), anyBoolean());
        ImportValidationResultService service = fixture.resultService(System::nanoTime);
        long opens = fixture.sourceOpens.get();

        BusinessException preview = assertThrows(BusinessException.class, () -> service.preview(jobId, 1, 10, null));
        BusinessException export = assertThrows(BusinessException.class, () -> service.prepareErrorExport(jobId));

        assertEquals(503, preview.getCode());
        assertEquals("error.import.auditUnavailable", preview.getMessageKey());
        assertEquals(503, export.getCode());
        assertEquals(opens, fixture.sourceOpens.get(), "監査できない閲覧ではsourceを開かない");
    }

    @Test
    void previewDetectsASourceThatNoLongerMatchesTheStoredRowHash() {
        long jobId = validated(CSV);
        // 保存済み検証行のsource行hashと、再読込したsource行が食い違う状態を再現する（原本の差替え検知）。
        fixture.jdbc.update("UPDATE t_import_validation_row SET source_row_sha256=? WHERE tenant_id=? AND job_id=? "
                        + "AND source_row_number=1", ImportA1aH2Fixture.sha256("other".getBytes(StandardCharsets.UTF_8)),
                ImportA1aH2Fixture.TENANT, jobId);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> fixture.resultService(System::nanoTime).preview(jobId, 1, 10, null));

        assertEquals(409, failure.getCode());
        assertEquals("error.import.sourceSnapshotMismatch", failure.getMessageKey());
    }

    @Test
    void errorExportStreamsOnlyRowNumbersHashesAndCodes() throws Exception {
        long jobId = validated(CSV);
        ImportValidationResultService service = fixture.resultService(System::nanoTime);
        long opens = fixture.sourceOpens.get();

        ImportValidationResultService.ErrorExport export = service.prepareErrorExport(jobId);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        service.writeErrorExport(export, bytes);
        String csv = bytes.toString(StandardCharsets.UTF_8);

        assertTrue(csv.startsWith("﻿sourceRowNumber,sourceRowHash,code\r\n"));
        String[] lines = csv.substring(1).split("\r\n");
        assertEquals(1 + 1 + 2, lines.length, "header + 行2の1件 + 行3の2件");
        assertTrue(lines[1].startsWith("2,") && lines[1].endsWith(",DUPLICATE_SOURCE_KEY"));
        assertTrue(csv.contains(",EMAIL_INVALID\r\n") && csv.contains(",FIELD_REQUIRED\r\n"));
        for (String secret : List.of("HYPERLINK", "山田", "佐藤", "example.co.jp", "株式会社")) {
            assertFalse(csv.contains(secret), "error exportへ原文を出さない: " + secret);
        }
        assertEquals(opens, fixture.sourceOpens.get(), "error exportはsource原本を開かない");
        verify(fixture.audit).recordRequired(any(), eq("GET"), anyString(), eq(200), eq("IMPORT_ERROR_EXPORT"), eq(true));
    }

    @Test
    void errorExportIsWrittenPageByPageWithoutBufferingTheWholeResult() throws Exception {
        StringBuilder large = new StringBuilder("externalKey,companyName,contactPerson,contactEmail,contactPhone,address\n");
        for (int i = 1; i <= 1_200; i++) {
            large.append("K-").append(i).append(",,担当").append(i).append(",,,\n");
        }
        long jobId = validated(large.toString());
        ImportValidationResultService service = fixture.resultService(System::nanoTime);
        ImportValidationResultService.ErrorExport export = service.prepareErrorExport(jobId);
        CountingOutputStream output = new CountingOutputStream();

        service.writeErrorExport(export, output);

        String csv = output.text();
        assertEquals(1 + 1_200, csv.substring(1).split("\r\n").length);
        assertTrue(output.flushesWithData.get() >= 3, "500行pageごとにflushする: " + output.flushesWithData.get());
    }

    @Test
    void errorExportStopsWhenTheAuthorizationChangesBetweenPages() throws Exception {
        long jobId = validated(CSV);
        ImportValidationResultService service = fixture.resultService(System::nanoTime);
        ImportValidationResultService.ErrorExport export = service.prepareErrorExport(jobId);
        when(fixture.authorization.requireCurrentAuthorization())
                .thenReturn(new ImportAuthorizedContext(ImportA1aH2Fixture.TENANT, ImportA1aH2Fixture.ACTOR, 78));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.writeErrorExport(export, new ByteArrayOutputStream()));

        assertEquals(403, failure.getCode());
    }

    @Test
    void csvInjectionSanitizerNeutralizesFormulaLeadersAndKeepsPlainNegatives() throws IOException {
        assertEquals("'=1+1", ImportErrorCsvWriter.sanitize("=1+1"));
        assertEquals("'+cmd", ImportErrorCsvWriter.sanitize("+cmd"));
        assertEquals("'-1+cmd", ImportErrorCsvWriter.sanitize("-1+cmd"));
        assertEquals("'@SUM(A1)", ImportErrorCsvWriter.sanitize("@SUM(A1)"));
        assertEquals("'\tvalue", ImportErrorCsvWriter.sanitize("\tvalue"));
        assertEquals("'\rvalue", ImportErrorCsvWriter.sanitize("\rvalue"));
        assertEquals("'  =1", ImportErrorCsvWriter.sanitize("  =1"));
        assertEquals("-50000", ImportErrorCsvWriter.sanitize("-50000"));
        assertEquals("-1.5", ImportErrorCsvWriter.sanitize("-1.5"));
        assertEquals("FIELD_REQUIRED", ImportErrorCsvWriter.sanitize("FIELD_REQUIRED"));
        StringWriter writer = new StringWriter();
        ImportErrorCsvWriter.writeRowCode(writer, 3, "=HYPERLINK(\"x\")", "@CODE");
        assertEquals("3,\"'=HYPERLINK(\"\"x\"\")\",'@CODE\r\n", writer.toString());
    }

    @Test
    void findingCodeDecodingRejectsUnknownOrMalformedPayloads() {
        byte[] encoded = ImportValidationAppendService.encodeFindingCodes(java.util.Set.of("FIELD_REQUIRED"));
        assertEquals(List.of("FIELD_REQUIRED"), ImportValidationResultService.decodeFindingCodes(encoded));
        byte[] truncated = java.util.Arrays.copyOf(encoded, encoded.length - 2);
        assertThrows(BusinessException.class, () -> ImportValidationResultService.decodeFindingCodes(truncated));
        assertThrows(BusinessException.class, () -> ImportValidationResultService.decodeFindingCodes(new byte[]{0, 0}));
        assertNull(ImportPreviewMasker.apply(ImportPreviewMasker.Rule.FULL, null));
        assertEquals("＊＊＊", ImportPreviewMasker.apply(ImportPreviewMasker.Rule.EMAIL, "no-at-mark"));
        assertEquals("＊＊＊", ImportPreviewMasker.apply(ImportPreviewMasker.Rule.PHONE, "12"));
        assertEquals(ImportPreviewMasker.Rule.FULL,
                ImportPreviewMasker.rule(com.ses.importcenter.foundation.ImportEntity.CUSTOMER, "unknownField"));
        assertEquals(ImportPreviewMasker.Rule.FULL,
                ImportPreviewMasker.rule(com.ses.importcenter.foundation.ImportEntity.ENGINEER, "fullName"));
    }

    private long validated(String csv) {
        long jobId = fixture.uploadedJob(sourceId, csv, ImportA1aH2Fixture.LEGAL_ENTITY);
        fixture.runService(System::nanoTime).validate(jobId, mappingId);
        return jobId;
    }

    /** 書込量とflush回数を数える。flush時点で前回から書込があった回数を記録する。 */
    private static final class CountingOutputStream extends OutputStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final AtomicInteger flushesWithData = new AtomicInteger();
        private int lastFlushedSize;

        @Override public void write(int b) { buffer.write(b); }
        @Override public void write(byte[] b, int off, int len) { buffer.write(b, off, len); }
        @Override public void flush() {
            if (buffer.size() > lastFlushedSize) {
                flushesWithData.incrementAndGet();
                lastFlushedSize = buffer.size();
            }
        }
        String text() { return buffer.toString(StandardCharsets.UTF_8); }
    }
}
