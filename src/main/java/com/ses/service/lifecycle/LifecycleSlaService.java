package com.ses.service.lifecycle;

import java.time.LocalDate;

/**
 * ライフサイクル SLA 監視サービス
 */
public interface LifecycleSlaService {

    /**
     * 指定基準日にて期日接近・超過タスクを検知し通知を発行する
     *
     * @param asOf 評価基準日
     * @return 検知・通知処理件数
     */
    int processSlaCheck(LocalDate asOf);
}
