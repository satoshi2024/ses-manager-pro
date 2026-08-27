package com.ses.service.servicedesk;

import java.time.LocalDateTime;

/**
 * サービスデスク SLA 監視サービス
 */
public interface ServiceSlaMonitoringService {

    /**
     * SLA 違反・期限前警告を検出し、フラグ更新と Dedupe 通知を発行する。
     * @param asOf 基準日時
     * @return 検出・処理されたリクエスト件数
     */
    int checkSlaBreaches(LocalDateTime asOf);
}
