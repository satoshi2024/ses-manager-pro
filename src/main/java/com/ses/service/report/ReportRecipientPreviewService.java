package com.ses.service.report;

import com.ses.dto.report.ReportRecipientPreviewResult;
import com.ses.entity.ReportRun;

import java.time.YearMonth;

/** generation前のrecipient scope評価。 */
public interface ReportRecipientPreviewService {
    ReportRecipientPreviewResult preview(Long templateVersionId, YearMonth period);

    ReportRecipientPreviewResult previewForRun(ReportRun run);
}
