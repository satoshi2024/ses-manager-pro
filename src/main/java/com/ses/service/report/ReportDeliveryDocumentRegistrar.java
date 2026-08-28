package com.ses.service.report;

import com.ses.dto.report.ReportDocumentArtifact;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * delivery本流TXから文書生成（file I/O）を分離する。registerは独立TXでcommitし、
 * 後続の通知失敗でdocument登録までrollbackしない。
 */
@Component
public class ReportDeliveryDocumentRegistrar {

    private final ReportDocumentService reportDocumentService;

    public ReportDeliveryDocumentRegistrar(ReportDocumentService reportDocumentService) {
        this.reportDocumentService = reportDocumentService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public ReportDocumentArtifact registerArtifact(Long runId, String format) {
        return reportDocumentService.register(runId, format);
    }
}
