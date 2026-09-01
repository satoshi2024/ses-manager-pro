package com.ses.service.servicedesk;

import java.time.LocalDateTime;

/**
 * SLA監視・アラート通知サービス
 */
public interface ServiceSlaMonitoringService {

    /**
     * SLA超過チェックおよびアラート通知の実行
     */
    void checkAndNotifyBreaches();

    /**
     * 指定基準日時におけるSLA超過判定およびアラート通知の実行
     * @return 超過判定されたクロック件数
     */
    int checkSlaBreaches(LocalDateTime asOf);
}
