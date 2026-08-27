package com.ses.service.notification;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ses.entity.Notification;
import com.ses.entity.NotificationOutbox;
import com.ses.mapper.NotificationOutboxMapper;
import com.ses.mapper.ReportDeliveryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 通知outboxの1件処理を独立transactionで実行するworker。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationOutboxDispatcher {

    private static final String STATUS_RETRY = "RETRY";
    private static final String STATUS_FAILED = "FAILED";
    private static final int MAX_ATTEMPTS = 5;

    private final NotificationOutboxMapper outboxMapper;
    private final WebhookNotifier webhookNotifier;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ReportDeliveryMapper reportDeliveryMapper;

    /** 30分以上claimされたままの行を再送可能へ戻す。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recoverStaleRows() {
        LocalDateTime now = LocalDateTime.now();
        outboxMapper.update(null, new UpdateWrapper<NotificationOutbox>()
                .eq("status", "PROCESSING")
                .lt("locked_at", now.minusMinutes(30))
                .set("status", STATUS_RETRY)
                .set("locked_at", null)
                .set("next_attempt_at", now));
    }

    /** claimからWebhook送信、結果更新までを1件単位のtransactionで実行する。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean dispatchOne(Long outboxId) {
        NotificationOutbox beforeClaim = outboxMapper.selectByIdForDispatch(outboxId);
        if (beforeClaim == null || outboxMapper.claim(outboxId) == 0) {
            return false;
        }
        NotificationOutbox row = outboxMapper.selectByIdForDispatch(outboxId);
        if (row == null) {
            return false;
        }

        boolean delivered = webhookNotifier.notifyNow(toNotification(row));
        if (delivered) {
            outboxMapper.markSent(outboxId);
            syncReportDelivery(outboxId, "SENT", null, null);
            return true;
        }

        int attempts = row.getAttemptCount() == null ? 1 : row.getAttemptCount();
        String status = attempts >= MAX_ATTEMPTS ? STATUS_FAILED : STATUS_RETRY;
        long backoffMinutes = Math.min(60L, 1L << Math.min(Math.max(attempts - 1, 0), 6));
        String error = "Webhook通知に失敗しました（attempt=" + attempts + "）";
        int updated = outboxMapper.markResult(outboxId, status, LocalDateTime.now().plusMinutes(backoffMinutes), error);
        if (updated > 0) {
            syncReportDelivery(outboxId, status, attempts >= MAX_ATTEMPTS ? "DELIVERY_DLQ" : "DELIVERY_FAILED", error);
        }
        return false;
    }

    private void syncReportDelivery(Long outboxId, String status, String errorCode, String errorMessage) {
        if (reportDeliveryMapper != null) {
            reportDeliveryMapper.syncOutboxStatus(outboxId, status, errorCode, errorMessage);
        }
    }

    private Notification toNotification(NotificationOutbox row) {
        Notification notification = new Notification();
        notification.setId(row.getNotificationId());
        notification.setType(row.getType());
        notification.setTitle(row.getTitle());
        notification.setMessage(row.getMessage());
        notification.setLinkUrl(row.getLinkUrl());
        notification.setMenuKey(row.getMenuKey());
        notification.setRecipientUserId(row.getRecipientUserId());
        notification.setOrganizationId(row.getOrganizationId());
        notification.setDedupeKey(row.getDedupeKey());
        notification.setCreatedAt(row.getCreatedAt());
        return notification;
    }
}
