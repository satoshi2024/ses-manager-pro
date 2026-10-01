package com.ses.service.report;

import com.ses.service.NotificationService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * レポート配布と通知outbox登録を同一transactionで確定する。
 * 通知登録の失敗は結果式で返し、配布側が安全なretry状態を保存できるようにする。
 */
@Component
public class ReportDeliveryNotificationBridge {

    private final NotificationService notificationService;

    public ReportDeliveryNotificationBridge(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public Long publish(Long userId, String type, String title, String message,
                        String linkUrl, String dedupeKey, String menuKey) {
        try {
            return notificationService.publishToUserAndGetOutboxIdWithoutDispatch(
                    userId, type, title, message, linkUrl, dedupeKey, menuKey);
        } catch (Exception ignored) {
            // 例外をtransaction interceptorまで伝播させず、呼出側にretry結果として返す。
            // 例外本文には通知本文・action URL・識別子が含まれ得るため記録しない。
            return null;
        }
    }
}
