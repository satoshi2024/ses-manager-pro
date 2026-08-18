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
     */
    List<CanonicalPaymentSync> fetchPayments(IntegrationConnection connection, LocalDate fromDate, LocalDate toDate);

    /**
     * 特定の取引IDの決済実績を取得する。
     */
    CanonicalPaymentSync fetchDealPayment(IntegrationConnection connection, String externalDealId);

    /**
     * 接続が有効か検証する。
     */
    boolean validateConnection(IntegrationConnection connection);
}
