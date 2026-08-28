package com.ses.dto.report;

import com.ses.entity.Document;
import com.ses.entity.DocumentVersion;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** snapshotから生成してDocumentServiceへ登録したartifact。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReportDocumentArtifact {
    private Long runId;
    private String format;
    private String contentHash;
    private Document document;
    private DocumentVersion version;
}
