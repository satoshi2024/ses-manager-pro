package com.ses.report;

import com.ses.common.util.PdfFontUtils;
import com.ses.dto.report.ReportDocumentArtifact;
import com.ses.entity.Document;
import com.ses.entity.DocumentVersion;
import com.ses.entity.ReportRun;
import com.ses.entity.ReportSectionSnapshot;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.service.DocumentService;
import com.ses.service.report.ReportSnapshotService;
import com.ses.service.report.impl.ReportDocumentServiceImpl;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReportDocumentServiceImplTest {

    private ReportSnapshotService snapshotService;
    private DocumentService documentService;
    private DocumentVersionMapper documentVersionMapper;
    private PdfFontUtils pdfFontUtils;
    private ReportDocumentServiceImpl service;

    @BeforeEach
    void setUp() {
        snapshotService = mock(ReportSnapshotService.class);
        documentService = mock(DocumentService.class);
        documentVersionMapper = mock(DocumentVersionMapper.class);
        pdfFontUtils = mock(PdfFontUtils.class);
        service = new ReportDocumentServiceImpl(snapshotService, documentService, documentVersionMapper, pdfFontUtils);

        ReportRun run = new ReportRun();
        run.setId(10L);
        run.setStatus("SUCCEEDED");
        run.setPeriodFrom(LocalDate.of(2026, 8, 1));
        run.setPeriodTo(LocalDate.of(2026, 8, 31));
        run.setCutoffKind("GENERATED_AT");
        run.setTimezoneId("Asia/Tokyo");
        run.setDataAsOfAt(LocalDateTime.of(2026, 8, 31, 23, 59));
        run.setSnapshotSchemaVersion("report-1.0");
        run.setSourcePolicyHash("policy");
        ReportSectionSnapshot section = new ReportSectionSnapshot();
        section.setSectionKey("sales");
        section.setSectionStatus("SUCCEEDED");
        section.setFactType("実績");
        section.setConfirmation("速報");
        section.setDataAsOfAt(run.getDataAsOfAt());
        section.setSnapshotHash("snapshot-hash");
        section.setValueJson("=SUM(A1:A2)");
        when(snapshotService.findRun(10L)).thenReturn(run);
        when(snapshotService.listSections(10L)).thenReturn(List.of(section));
    }

    @Test
    void rendersAllFormatsFromTheSameSectionSnapshot() throws Exception {
        byte[] csv = service.render(10L, "CSV");
        byte[] xlsx = service.render(10L, "XLSX");

        assertThat(new String(csv, java.nio.charset.StandardCharsets.UTF_8)).contains("sales", "'=SUM(A1:A2)");
        assertThat(xlsx).startsWith(new byte[]{0x50, 0x4b});
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(workbook.getSheetAt(0).getRow(4).getCell(6).getStringCellValue())
                    .isEqualTo("'=SUM(A1:A2)");
        }
    }

    @Test
    void registersGeneratedDocumentAndConfirmsRetentionThroughDocumentService() {
        Document document = new Document();
        document.setId(20L);
        document.setStatus("DRAFT");
        DocumentVersion version = new DocumentVersion();
        version.setDocumentId(20L);
        version.setVersionNo(1);
        when(documentService.registerGenerated(any(), any())).thenReturn(document);
        when(documentVersionMapper.findLatestByDocumentId(20L)).thenReturn(version);

        ReportDocumentArtifact artifact = service.register(10L, "CSV");

        assertThat(artifact.getContentHash()).hasSize(64);
        assertThat(artifact.getVersion().getVersionNo()).isEqualTo(1);
        verify(documentService).confirm(20L);
    }
}
