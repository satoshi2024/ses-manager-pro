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
     * 送信プレビュー。送信ジョブへ保存するのと同じ標準DTOと、送信ガードが要求するマッピングの状態を返す。
     * 接続の作成・ジョブ登録などの副作用は持たない。請求書が無ければ404。
     */
    com.ses.dto.accounting.AccountingSendPreviewDto<com.ses.dto.accounting.canonical.CanonicalSalesInvoice> previewSalesSync(Long invoiceId);

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

    /**
     * 請求書から送信標準DTOを構築する（プレビュー用）。
     */
    com.ses.dto.accounting.canonical.CanonicalSalesInvoice buildCanonicalInvoice(Long invoiceId);

    /**
     * SESで入金消込した入金明細（freeeの口座明細）を、freeeの取引の消込として反映するジョブを登録する（Outbox）。
     * 入金消込と同じトランザクションで呼ぶ。請求書がfreeeへ送信済み（取引が取消されていない）ときだけ登録し、
     * それ以外（未送信・取引取消済み・会計接続なし・口座明細IDが無い）は登録せず null を返す。
     */
    IntegrationJob enqueueDepositReconcile(Long depositId, String walletTxnId, Long invoiceId, Long paymentId,
                                           java.math.BigDecimal amount);

    /**
     * 入金消込の反映ジョブを1件同期処理する（Worker実行用 / DBトランザクション外）。
     */
    void processDepositReconcileJob(Long jobId);

    /**
     * 外部API実行後の結果をDBに反映する（トランザクション内）。
     */
    void handleSalesInvoiceResult(Long jobId, IntegrationJob job, com.ses.entity.IntegrationConnection conn, com.ses.dto.accounting.canonical.CanonicalDealResult result);
}
