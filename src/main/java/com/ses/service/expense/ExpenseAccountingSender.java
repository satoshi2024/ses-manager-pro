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
     * 外部側の処理待ちを表すerrorCode。失敗ではないため、schedulerは試行回数を消費せずに再確認する
     * （freeeは会計・支払連携の送信ジョブが非同期に取引を作るため、作成前はこの結果を返す）。
     */
    String PENDING = "ACCOUNTING_JOB_PENDING";

    /**
     * 管理者が会計側の送信ジョブを取消したことを表すerrorCode。終端であり、schedulerは再試行しない
     * （自動で作り直すと取消が1分で無効になり、結果不明のジョブの取消なら取引を二重に作り得る）。
     * 再送信は会計・支払連携の画面から手動で行う。
     */
    String CANCELLED = "ACCOUNTING_JOB_CANCELLED";

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
