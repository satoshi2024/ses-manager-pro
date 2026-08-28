package com.ses.service.report;

import com.ses.dto.report.ReportDeliveryResult;
import com.ses.dto.report.ReportDownload;
import com.ses.entity.ReportDelivery;

import java.util.List;

/** 管理レポート配布・再認証・DLQ replay境界。 */
public interface ReportDeliveryService {
    ReportDeliveryResult deliver(Long runId, String previewHash);
    void reauthenticate(Long deliveryId, String password);
    ReportDownload download(Long deliveryId, String token, String format);
    /** delivery tokenと再認証を通るインラインpreview。run直結のraw previewは提供しない。 */
    ReportDownload preview(Long deliveryId, String token, String format);
    void retry(Long deliveryId);
    void manualReplay(Long deliveryId);
    /** 誤配布時にlinkを失効させる。管理者のみ。 */
    void cancel(Long deliveryId);
    List<ReportDelivery> listByRun(Long runId);
}
