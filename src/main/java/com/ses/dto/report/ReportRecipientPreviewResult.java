package com.ses.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/** recipient previewの固定結果。generationはこのhashを受け取って再検証する。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReportRecipientPreviewResult {
    private String previewHash;
    private String status;
    private LocalDateTime evaluatedAt;
    private List<ReportRecipientPreview> recipients;
}
