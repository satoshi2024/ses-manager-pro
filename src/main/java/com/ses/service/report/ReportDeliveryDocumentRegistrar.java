package com.ses.service.report;

import com.ses.dto.report.ReportDocumentArtifact;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * delivery本流TXへ文書登録を参加させる。通知・outboxが失敗した場合に
 * documentだけが先にcommitされる孤児を作らない。
 */
@Component
public class ReportDeliveryDocumentRegistrar {

    private final ReportDocumentService reportDocumentService;

    public ReportDeliveryDocumentRegistrar(ReportDocumentService reportDocumentService) {
        this.reportDocumentService = reportDocumentService;
    }

    @Transactional(rollbackFor = Exception.class)
    public ReportDocumentArtifact registerArtifact(Long runId, String format) {
        return reportDocumentService.register(runId, format);
    }
}
