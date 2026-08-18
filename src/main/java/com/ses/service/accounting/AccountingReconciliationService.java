package com.ses.service.accounting;

import com.ses.dto.accounting.AccountingReconciliationSummaryDto;

/**
 * 会計・支払月次照合サービス (B3 / design §5, §6.1, §6.3)。
 */
public interface AccountingReconciliationService {

    /**
     * 指定月の内部データ（売上・仕入・決済）と外部取引を照合しサマリーと明細を返す。
     */
    AccountingReconciliationSummaryDto reconcileMonth(String month);

    /**
     * 照合差異を理由付きで無視（承認済み除外）として記録する。
     */
    void ignoreDiscrepancy(String month, String category, String externalDealId, Long internalId, String reason, Long userId);

    /**
     * 月次締め前の照合ガード。重大不一致（未解消の金額不一致または未送信）があれば例外をスローする。
     */
    void assertReconciledForClosing(String month);
}
