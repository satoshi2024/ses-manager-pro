package com.ses.service.report;

import com.ses.dto.report.ReportRecipientPreviewResult;
import com.ses.dto.report.ReportScopeSnapshot;
import com.ses.entity.ReportRun;

import java.time.YearMonth;

/** generation前のrecipient scope評価。 */
public interface ReportRecipientPreviewService {
    ReportRecipientPreviewResult preview(Long templateVersionId, YearMonth period);

    ReportRecipientPreviewResult previewForRun(ReportRun run);

    /** schedulerが保存済みschedule scopeで生成直前に再評価する。 */
    ReportRecipientPreviewResult previewForScope(Long templateVersionId, java.time.YearMonth period,
                                                 ReportScopeSnapshot scope);
}
