package com.ses.service.accounting;

import com.ses.dto.accounting.canonical.*;
import com.ses.entity.IntegrationConnection;

import java.time.LocalDate;
import java.util.List;

/**
 * 会計・支払連携 Provider インターフェース (freee / CSV / mock)。
 * DB transaction 外で実行される (platform-invariants §3.3)。
 */
public interface AccountingProvider {

    /**
     * プロバイダ識別名 (freee / csv / mock 等)
     */
    String providerName();

    /**
     * 売上取引を登録/更新する。
     */
    CanonicalDealResult upsertSalesInvoice(IntegrationConnection connection, CanonicalSalesInvoice invoice);

    /**
     * 売上取引を取り消す。
     */
    CanonicalDealResult cancelSalesInvoice(IntegrationConnection connection, String externalDealId, String reason);

    /**
     * BP仕入取引を登録/更新する。
     */
    CanonicalDealResult upsertPurchaseDeal(IntegrationConnection connection, CanonicalPurchaseDeal purchase);

    /**
     * 要員経費取引を登録/更新する。
     */
    CanonicalDealResult upsertExpenseDeal(IntegrationConnection connection, CanonicalExpenseDeal expense);

    /**
     * 取引を作成する前に、同じ管理番号の取引が外部に既にあるかを確認する方法。
     * 送信ジョブの状態から {@link #of(Boolean, Integer)} で決める。
     */
    enum DealCreateGuard {
        /** 確認しない（そのジョブの初回の試行）。 */
        NONE,
        /** 同じ管理番号の取引があれば作成しない（前の世代が外部へ送信した後に取消された再送信）。 */
        REFUSE_IF_EXISTS,
        /**
         * 同じジョブの再試行（タイムアウト・5xx・滞留回収・手動再試行の後）。前回の試行で作成済みの取引
         * （管理番号・金額・事業所が一致する1件）は採用し、それ以外の同じ管理番号の取引があれば作成しない。
         */
        ADOPT_OWN_OR_REFUSE;

        /**
         * @param verifyNoExistingDeal 送信スナップショットの指定（前の世代が送信後に取消された）
         * @param attemptCount         claim後の試行回数（1 = 初回）
         */
        public static DealCreateGuard of(Boolean verifyNoExistingDeal, Integer attemptCount) {
            if (Boolean.TRUE.equals(verifyNoExistingDeal)) {
                // 前の世代の取引が残っている可能性があるため、再試行でも採用せず拒否する
                // （補償取消が後から削除する取引を採用すると、送信済みのまま取引が消える）
                return REFUSE_IF_EXISTS;
            }
            return attemptCount != null && attemptCount > 1 ? ADOPT_OWN_OR_REFUSE : NONE;
        }
    }

    /** 既存取引の確認方法を指定して売上取引を登録する。未対応providerは確認しない。 */
    default CanonicalDealResult upsertSalesInvoice(IntegrationConnection connection, CanonicalSalesInvoice invoice,
                                                   DealCreateGuard guard) {
        return upsertSalesInvoice(connection, invoice);
    }

    /** 既存取引の確認方法を指定してBP仕入取引を登録する。未対応providerは確認しない。 */
    default CanonicalDealResult upsertPurchaseDeal(IntegrationConnection connection, CanonicalPurchaseDeal purchase,
                                                   DealCreateGuard guard) {
        return upsertPurchaseDeal(connection, purchase);
    }

    /** 既存取引の確認方法を指定して要員経費取引を登録する。未対応providerは確認しない。 */
    default CanonicalDealResult upsertExpenseDeal(IntegrationConnection connection, CanonicalExpenseDeal expense,
                                                  DealCreateGuard guard) {
        return upsertExpenseDeal(connection, expense);
    }

    /**
     * 外部システムの支払・決済実績を取得する。
     * 50ページ上限到達・重複ID・取得途中障害は結果オブジェクトで fail-closed 通知する (P1-09)。
     */
    com.ses.dto.accounting.PaymentFetchResult fetchPayments(IntegrationConnection connection, LocalDate fromDate, LocalDate toDate);

    /**
     * SESで入金消込した入金（外部の口座明細）で、外部の未決済取引を消込む。
     * 同じ内容の再実行は冪等（freeeは同一パラメータに同じ応答を返す）。未対応providerは再試行しない失敗を返す。
     *
     * @param walletTxnId    口座明細ID（SESの入金明細の freee_deposit_id）
     * @param externalDealId 消込む取引ID（請求書を送信した取引）
     * @param amount         決済金額（SESで請求書へ登録した入金額）
     */
    default CanonicalDealResult reconcileDeposit(IntegrationConnection connection, String walletTxnId,
                                                 String externalDealId, java.math.BigDecimal amount) {
        return CanonicalDealResult.builder()
                .success(false)
                .errorCode("UNSUPPORTED_PROVIDER")
                .errorMessageSafe("error.accounting.depositReconcileUnsupported")
                .retryable(false)
                .build();
    }

    /**
     * 特定の取引IDの決済実績を取得する。
     */
    CanonicalPaymentSync fetchDealPayment(IntegrationConnection connection, String externalDealId);

    /**
     * 月次照合用: 送信済み取引の存在と取引金額を1件照会する。
     * 照合の一覧取得（決済済み全件＋対象月に発生した未決済）に現れない送信済み取引（前月以前に発生した未決済、
     * 2回の取得の間に決済済みになった取引など）はこれで確認する。
     * 未対応providerは UNKNOWN を返す。
     */
    default DealLookup lookupDeal(IntegrationConnection connection, String externalDealId) {
        return DealLookup.unknown();
    }

    /** 単一取引の照会結果。status は FOUND / NOT_FOUND / UNKNOWN（照会失敗・未対応）。 */
    record DealLookup(String status, java.math.BigDecimal amount) {
        public static DealLookup found(java.math.BigDecimal amount) {
            return new DealLookup("FOUND", amount);
        }

        public static DealLookup notFound() {
            return new DealLookup("NOT_FOUND", null);
        }

        public static DealLookup unknown() {
            return new DealLookup("UNKNOWN", null);
        }
    }

    /**
     * 接続が有効か検証する。
     */
    boolean validateConnection(IntegrationConnection connection);

    /**
     * 外部マスタ (取引先、勘定科目、税区分、部門) の存在・整合性を検証する (P1-05)。
     */
    boolean verifyMaster(IntegrationConnection connection, String objectType, String externalId, String externalCode);

}
