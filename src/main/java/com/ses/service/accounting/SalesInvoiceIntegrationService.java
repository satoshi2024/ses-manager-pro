package com.ses.service.accounting;

import com.ses.entity.IntegrationJob;

/**
 * 売上請求・取消連携サービス (B1 / design §4, §6.1, §6.3)。
 */
public interface SalesInvoiceIntegrationService {

    /** SESで消込を取り消したが、freeeには既に消込が反映されている（freee側で手動の取消が必要）。画面に出す message key。 */
    String RELEASE_WARNING_REFLECTED = "accounting.depositReconcile.releasedAfterSync";

    /** SESで消込を取り消したが、freeeへの反映が実行中・結果不明（反映されていればfreee側で手動の取消が必要）。 */
    String RELEASE_WARNING_UNCERTAIN = "accounting.depositReconcile.releasedWhileSyncing";

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
     * 入金消込と同じトランザクションの中で、savepoint（NESTED）として実行する。登録に失敗しても savepoint まで戻すだけで
     * 呼出側のトランザクションを rollback-only にしないため、呼出側は例外を捕まえてSESの消込を確定できる。
     * 請求書がfreeeへ送信済み（取引が取消・取消中でない）ときだけ登録し、それ以外（未送信・取引取消・会計接続なし・
     * 口座明細IDが無い・法人を決められない・同じキーで別内容のジョブがある）は登録せず null を返す。
     * 冪等キーは入金明細・SESの入金行・freee取引の組で、入金を削除して消込し直すと別のジョブになる。
     */
    IntegrationJob enqueueDepositReconcile(Long depositId, String walletTxnId, Long invoiceId, Long paymentId,
                                           java.math.BigDecimal amount);

    /**
     * 請求書のfreee送信（取引作成）が成功した後に、その請求書へ既に消込済みの入金明細の反映ジョブを登録する
     * （先に消込してから送信した場合・取引を取消して再送信した場合）。savepoint（NESTED）で実行し、失敗しても
     * 呼出側（送信結果の反映）を巻き戻さない。
     *
     * @return 登録した（または登録済みだった）反映ジョブの件数
     */
    int enqueueDepositReconcilesForInvoice(Long invoiceId);

    /**
     * SESで入金消込を取り消した（入金行を削除して入金明細を未消込へ戻した）ときの、freeeへの反映の後始末。
     * 未完了の反映ジョブ（PENDING / RETRYABLE / FAILED）は取消し、freeeへ反映済みのジョブには要対応の印を付ける。
     * savepoint（NESTED）で実行し、失敗しても呼出側（消込の取消）を巻き戻さない。
     *
     * @return freee側で手動の取消が必要（または必要かもしれない）とき、画面に出す message key
     *         （{@link #RELEASE_WARNING_REFLECTED} / {@link #RELEASE_WARNING_UNCERTAIN}）。不要なら null
     */
    String releaseDepositReconcile(Long depositId, Long paymentId);

    /**
     * 入金消込の反映ジョブを1件同期処理する（Worker実行用 / DBトランザクション外）。
     * freeeへ送る直前に、入金明細がまだこのジョブの請求書・入金行で消込済みか、取引が取消されていないかを確かめ、
     * 変わっていればfreeeへ送らずに取消す。
     */
    void processDepositReconcileJob(Long jobId);

    /**
     * 外部API実行後の結果をDBに反映する（トランザクション内）。
     */
    void handleSalesInvoiceResult(Long jobId, IntegrationJob job, com.ses.entity.IntegrationConnection conn, com.ses.dto.accounting.canonical.CanonicalDealResult result);
}
