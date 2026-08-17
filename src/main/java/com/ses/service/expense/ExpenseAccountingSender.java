package com.ses.service.expense;

import com.ses.entity.ExpenseAccountingJob;
import com.ses.entity.ExpenseRequest;

/**
 * 経費の会計連携sender（provider: mock/freee）。
 * 外部APIはDB transaction外で呼ぶ（platform-invariants §3.3）。
 * job.payload_hashを冪等キーとし、同一hashの再送は成功扱いで重複呼出ししない。
 */
public interface ExpenseAccountingSender {

    /**
     * このsenderが担当するprovider名（m_system_configのexpense.accounting.providerと一致）。
     * mock/freee。DB設定を正としてschedulerが選定する。
     */
    String providerName();

    /** 送信結果。success=falseのときerrorCodeはPIIを含まない分類code。 */
    record SendResult(boolean success, String correlationId, String errorCode) {
    }

    /**
     * 経費を会計へ連携する。
     *
     * @param expense 連携対象の経費
     * @param job     claim済みのjob（payload_hashは作成時に固定済み）
     * @return 送信結果
     */
    SendResult send(ExpenseRequest expense, ExpenseAccountingJob job);
}
