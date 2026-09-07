package com.ses.service.report;

import com.ses.dto.report.ReportDocumentArtifact;
import com.ses.dto.report.ReportRecipientPreview;
import com.ses.entity.ReportDelivery;
import com.ses.entity.ReportRun;

/** 単一recipientのdelivery行とnotification outboxを同一トランザクションで発行する境界。 */
public interface ReportDeliveryIssueService {

    ReportDelivery issue(ReportRun run, ReportDelivery existing, ReportRecipientPreview recipient,
                         ReportDocumentArtifact artifact);

    void markDownloaded(Long deliveryId);
}
