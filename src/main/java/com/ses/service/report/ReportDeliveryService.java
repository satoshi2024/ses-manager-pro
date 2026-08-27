package com.ses.service.report;

import com.ses.dto.report.ReportDeliveryResult;
import com.ses.dto.report.ReportDownload;

/** 管理レポート配布・再認証・DLQ replay境界。 */
public interface ReportDeliveryService {
    ReportDeliveryResult deliver(Long runId, String previewHash);
    void reauthenticate(Long deliveryId, String password);
    ReportDownload download(Long deliveryId, String token, String format);
    void retry(Long deliveryId);
    void manualReplay(Long deliveryId);
}
