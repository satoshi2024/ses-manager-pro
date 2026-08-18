package com.ses.service.accounting;

import com.ses.entity.IntegrationJob;

/**
 * 売上請求・取消連携サービス (B1 / design §4, §6.1, §6.3)。
 */
public interface SalesInvoiceIntegrationService {

    /**
     * 請求書のfreee連携ジョブを生成・キューイングする（Outboxパターン）。
     */
    IntegrationJob triggerSalesSync(Long invoiceId, Long triggeredByUserId);

    /**
     * 請求書の取消連携ジョブを生成・キューイングする。
     */
    IntegrationJob triggerSalesCancel(Long invoiceId, String cancelReason, Long triggeredByUserId);

    /**
     * 売上連携ジョブを1件同期処理する（Worker実行用 / DBトランザクション外）。
     */
    void processSalesInvoiceJob(Long jobId);

    /**
     * 売上取消連携ジョブを1件同期処理する。
     */
    void processSalesCancelJob(Long jobId);
}
