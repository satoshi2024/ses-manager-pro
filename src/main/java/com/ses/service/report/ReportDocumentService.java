package com.ses.service.report;

import com.ses.dto.report.ReportDocumentArtifact;

/** immutable report snapshotをPDF/XLSX/CSVへ変換し、DocumentServiceへ登録する境界。 */
public interface ReportDocumentService {
    byte[] render(Long runId, String format);
    ReportDocumentArtifact register(Long runId, String format);
}
