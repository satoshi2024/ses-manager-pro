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
     * 外部システムの支払・決済実績を取得する。
     * 50ページ上限到達・重複ID・取得途中障害は結果オブジェクトで fail-closed 通知する (P1-09)。
     */
    com.ses.dto.accounting.PaymentFetchResult fetchPayments(IntegrationConnection connection, LocalDate fromDate, LocalDate toDate);

    /**
     * 特定の取引IDの決済実績を取得する。
     */
    CanonicalPaymentSync fetchDealPayment(IntegrationConnection connection, String externalDealId);

    /**
     * 接続が有効か検証する。
     */
    boolean validateConnection(IntegrationConnection connection);

    /**
     * 外部マスタ (取引先、勘定科目、税区分、部門) の存在・整合性を検証する (P1-05)。
     */
    boolean verifyMaster(IntegrationConnection connection, String objectType, String externalId, String externalCode);
}
