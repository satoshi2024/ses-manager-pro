package com.ses.service.accounting;

import com.ses.entity.IntegrationJob;

/**
 * BP仕入・経費・支払実績同期連携サービス (B2 / design §4, §6.1, §6.3, G9)。
 */
public interface PurchaseExpensePaymentIntegrationService {

    /**
     * BP支払の仕入（外注費）連携ジョブを生成・キューイングする。
     */
    IntegrationJob triggerBpPurchaseSync(Long bpPaymentId, Long triggeredByUserId);

    /** BP仕入の送信プレビュー（副作用なし）。BP支払が無ければ404。 */
    com.ses.dto.accounting.AccountingSendPreviewDto<com.ses.dto.accounting.canonical.CanonicalPurchaseDeal> previewBpPurchaseSync(Long bpPaymentId);

    /** 要員経費の送信プレビュー（副作用なし）。現tenantの経費申請が無ければ404。 */
    com.ses.dto.accounting.AccountingSendPreviewDto<com.ses.dto.accounting.canonical.CanonicalExpenseDeal> previewExpenseSync(Long expenseRequestId);

    /**
     * 外部決済情報の同期ジョブを生成・キューイングする。
     */
    IntegrationJob triggerPaymentSync(Long bpPaymentId, Long triggeredByUserId);

    /**
     * 要員経費申請の連携ジョブを生成・キューイングする。
     */
    IntegrationJob triggerExpenseSync(Long expenseRequestId, Long triggeredByUserId);

    /**
     * BP仕入連携ジョブを1件同期処理する（Worker実行用 / DBトランザクション外）。
     */
    void processBpPurchaseJob(Long jobId);

    /**
     * 支払実績同期ジョブを1件同期処理する（外部決済情報の照合と内部paid更新）。
     */
    void processPaymentSyncJob(Long jobId);

    /**
     * 要員経費連携ジョブを1件同期処理する。
     */
    void processExpenseJob(Long jobId);
}
