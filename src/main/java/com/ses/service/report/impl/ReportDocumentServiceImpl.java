package com.ses.service.report.impl;

import com.ses.common.exception.BusinessException;
import com.ses.common.util.PdfFontUtils;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.document.DocumentRegisterRequest;
import com.ses.dto.report.ReportDocumentArtifact;
import com.ses.entity.Document;
import com.ses.entity.DocumentVersion;
import com.ses.entity.ReportRun;
import com.ses.entity.ReportSectionSnapshot;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.service.DocumentService;
import com.ses.service.report.ReportDocumentService;
import com.ses.service.report.ReportSnapshotService;
import com.lowagie.text.Chunk;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfWriter;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * rendererはReportSectionSnapshot.valueJsonだけを読み、正本DBを再集計しない。
 * CSV/XLSXの文字列セルはformula injectionを防止し、PDFは既存の日本語font utilityを利用する。
 */
@Service
@RequiredArgsConstructor
public class ReportDocumentServiceImpl implements ReportDocumentService {

    private static final int MAX_ARTIFACT_BYTES = 25 * 1024 * 1024;
    private static final String TENANT_ID = "default";
    private final ReportSnapshotService snapshotService;
    private final DocumentService documentService;
    private final DocumentVersionMapper documentVersionMapper;
    private final PdfFontUtils pdfFontUtils;

    @Override
    public byte[] render(Long runId, String format) {
        ReportRun run = snapshotService.findRun(runId);
        if (!"SUCCEEDED".equals(run.getStatus())) {
            throw BusinessException.of(400, "error.managementReport.documentRunNotReady");
        }
        List<ReportSectionSnapshot> sections = snapshotService.listSections(runId);
        if (sections.stream().anyMatch(section -> !"SUCCEEDED".equals(section.getSectionStatus()))) {
            throw BusinessException.of(400, "error.managementReport.documentSectionFailed");
        }
        byte[] bytes = switch (normalizeFormat(format)) {
            case "PDF" -> renderPdf(run, sections);
            case "XLSX" -> renderXlsx(run, sections);
            case "CSV" -> renderCsv(run, sections);
            default -> throw BusinessException.of(400, "error.managementReport.formatInvalid");
        };
        if (bytes.length == 0 || bytes.length > MAX_ARTIFACT_BYTES) {
            throw BusinessException.of(400, "error.managementReport.documentTooLarge");
        }
        return bytes;
    }

    @Override
    public ReportDocumentArtifact register(Long runId, String format) {
        String normalized = normalizeFormat(format);
        ReportRun run = snapshotService.findRun(runId);
        byte[] bytes = render(runId, normalized);
        String hash = sha256(bytes);
        String extension = normalized.toLowerCase(java.util.Locale.ROOT);
        String contentType = switch (normalized) {
            case "PDF" -> "application/pdf";
            case "XLSX" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            default -> "text/csv; charset=UTF-8";
        };
        DocumentRegisterRequest request = DocumentRegisterRequest.builder()
                .documentType("MANAGEMENT_REPORT")
                .title("月次管理レポート " + run.getPeriodFrom().toString().substring(0, 7) + " (" + normalized + ")")
                .transactionDate(run.getPeriodTo())
                .direction("INTERNAL")
                .sourceType("GENERATED")
                .businessKey("MANAGEMENT_REPORT:" + runId + ":" + normalized)
                .versionDiscriminator(run.getSourcePolicyHash() + ":" + normalized)
                .originalName("management-report-" + run.getPeriodFrom().toString().substring(0, 7) + "." + extension)
                .contentType(contentType)
                .createdBy(SecurityUtils.currentUserId())
                .build();
        Document document = documentService.registerGenerated(request, new java.io.ByteArrayInputStream(bytes));
        if (document != null && "DRAFT".equals(document.getStatus())) {
            documentService.confirm(document.getId());
        }
        DocumentVersion version = documentVersionMapper.findLatestByDocumentId(document.getId());
        return new ReportDocumentArtifact(runId, normalized, hash, document, version);
    }

    private byte[] renderPdf(ReportRun run, List<ReportSectionSnapshot> sections) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            com.lowagie.text.Document document = new com.lowagie.text.Document(PageSize.A4, 36, 36, 36, 36);
            PdfWriter.getInstance(document, output);
            BaseFont baseFont = pdfFontUtils.resolveCjkFont();
            Font font = new Font(baseFont, 9, Font.NORMAL);
            Font titleFont = new Font(baseFont, 15, Font.BOLD);
            document.open();
            document.add(new Paragraph("月次管理レポート", titleFont));
            document.add(new Paragraph("対象期間: " + run.getPeriodFrom() + " ～ " + run.getPeriodTo()
                    + " / cutoff: " + run.getCutoffKind() + " / timezone: " + run.getTimezoneId(), font));
            document.add(new Paragraph("dataAsOf: " + run.getDataAsOfAt() + " / snapshot schema: "
                    + run.getSnapshotSchemaVersion(), font));
            for (ReportSectionSnapshot section : sections) {
                document.add(new Paragraph("", font));
                document.add(new Paragraph(section.getSectionKey() + " [" + section.getFactType()
                        + "/" + section.getConfirmation() + "]", new Font(baseFont, 11, Font.BOLD)));
                document.add(new Paragraph("snapshotHash: " + section.getSnapshotHash()
                        + " / dataAsOf: " + section.getDataAsOfAt(), font));
                Paragraph value = new Paragraph();
                value.setFont(font);
                value.add(new Chunk(section.getValueJson() == null ? "" : section.getValueJson(), font));
                document.add(value);
            }
            document.close();
            return output.toByteArray();
        } catch (Exception ex) {
            throw BusinessException.of(500, "error.managementReport.pdfGenerationFailed");
        }
    }

    private byte[] renderXlsx(ReportRun run, List<ReportSectionSnapshot> sections) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("管理レポート");
            Row metadata = sheet.createRow(0);
            metadata.createCell(0).setCellValue("対象期間");
            metadata.createCell(1).setCellValue(run.getPeriodFrom() + " ～ " + run.getPeriodTo());
            Row cutoff = sheet.createRow(1);
            cutoff.createCell(0).setCellValue("cutoff / dataAsOf / timezone");
            cutoff.createCell(1).setCellValue(run.getCutoffKind() + " / " + run.getDataAsOfAt() + " / " + run.getTimezoneId());
            String[] headers = {"section", "status", "factType", "confirmation", "dataAsOf", "snapshotHash", "valueJson"};
            writeXlsxRow(sheet.createRow(3), headers);
            int rowIndex = 4;
            for (ReportSectionSnapshot section : sections) {
                writeXlsxRow(sheet.createRow(rowIndex++), new String[]{section.getSectionKey(), section.getSectionStatus(),
                        section.getFactType(), section.getConfirmation(), String.valueOf(section.getDataAsOfAt()),
                        section.getSnapshotHash(), sanitize(section.getValueJson())});
            }
            workbook.write(output);
            workbook.dispose();
            return output.toByteArray();
        } catch (IOException ex) {
            throw BusinessException.of(500, "error.managementReport.xlsxGenerationFailed");
        }
    }

    private byte[] renderCsv(ReportRun run, List<ReportSectionSnapshot> sections) {
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("対象期間,").append(csv(run.getPeriodFrom() + " ～ " + run.getPeriodTo())).append("\r\n");
        csv.append("cutoff,dataAsOf,timezone\r\n");
        csv.append(csv(run.getCutoffKind())).append(',').append(csv(String.valueOf(run.getDataAsOfAt())))
                .append(',').append(csv(run.getTimezoneId())).append("\r\n");
        csv.append("section,status,factType,confirmation,dataAsOf,snapshotHash,valueJson\r\n");
        for (ReportSectionSnapshot section : sections) {
            csv.append(csv(section.getSectionKey())).append(',').append(csv(section.getSectionStatus())).append(',')
                    .append(csv(section.getFactType())).append(',').append(csv(section.getConfirmation())).append(',')
                    .append(csv(String.valueOf(section.getDataAsOfAt()))).append(',').append(csv(section.getSnapshotHash())).append(',')
                    .append(csv(section.getValueJson())).append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void writeXlsxRow(Row row, String[] values) {
        for (int i = 0; i < values.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(sanitize(values[i]));
        }
    }

    private String csv(String value) {
        String safe = sanitize(value == null ? "" : value);
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private String sanitize(String value) {
        if (value == null) return "";
        if (!value.isEmpty() && "=+-@\t".indexOf(value.charAt(0)) >= 0) return "'" + value;
        return value;
    }

    private String normalizeFormat(String format) {
        if (format == null) throw BusinessException.of(400, "error.managementReport.formatInvalid");
        String normalized = format.trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("PDF", "XLSX", "CSV").contains(normalized)) {
            throw BusinessException.of(400, "error.managementReport.formatInvalid");
        }
        return normalized;
    }

    private String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder result = new StringBuilder();
            for (byte b : digest) result.append(String.format("%02x", b));
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256を利用できません", ex);
        }
    }
}
