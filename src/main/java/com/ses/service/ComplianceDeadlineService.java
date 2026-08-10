package com.ses.service;

import java.time.LocalDateTime;

/**
 * compliance期限・リスク運用（T065 B2、R3.3/R3.4）。
 *  - 90/60/30日前のdeadline通知（finding.due_date基準。同一期限・同一段階で1回＝冪等）
 *  - 通知宛先は担当営業（契約sales_user_id）とHRユーザーの個人指定（design §5.3。組織一斉にしない）
 *  - EXCEPTION_APPROVEDの有効期限超過をOPENへ戻す
 */
public interface ComplianceDeadlineService {

    /**
     * 期限通知と例外失効を処理する（schedulerと同じ経路。テスト/Demoは明示asOfで呼ぶ）。
     *
     * @return 通知した通知数
     */
    int process(LocalDateTime asOf);
}
