package com.ses.service.notification;

import com.ses.common.audit.ExecutionActorContext;
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
import java.util.List;

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
        ExecutionActorContext.runAsSystem("notification-outbox-recovery", "SCHEDULER_POLL",
                this::recoverStaleRowsInternal);
    }

    private void recoverStaleRowsInternal() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleBefore = now.minusMinutes(30);
        List<NotificationOutbox> staleRows = outboxMapper.selectStale(staleBefore, 100);
        for (NotificationOutbox stale : staleRows) {
            int updated = outboxMapper.recoverStale(stale.getId(), staleBefore, now);
            if (updated == 1) {
                syncReportDelivery(stale.getId(), STATUS_RETRY, "DELIVERY_STALE_RECOVERED",
                        "staleなprocessingを再送可能状態へ戻しました");
            }
        }
    }

    /** outbox確定後にdeliveryの未同期行を再照合する永続reconciliation。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public int reconcilePending() {
        return ExecutionActorContext.runAsSystem("notification-outbox-reconciliation", "SCHEDULER_POLL",
                () -> {
                    int repaired = 0;
                    for (NotificationOutbox row : outboxMapper.selectReconciliationDue(100)) {
                        try {
                            if (syncReportDelivery(row.getId(), row.getStatus(),
                                    "DELIVERY_RECONCILIATION", row.getLastError())) {
                                int cleared = outboxMapper.clearReconciliationRequired(row.getId());
                                if (cleared > 0) {
                                    repaired++;
                                } else {
                                    NotificationOutbox current = outboxMapper.selectById(row.getId());
                                    if (current == null || !Integer.valueOf(1).equals(current.getReconciliationRequired())) {
                                        repaired++;
                                    } else {
                                        log.error("[通知outbox] 再照合フラグの解除結果が0件のため未解決として保持しました: outboxId={}",
                                                row.getId());
                                    }
                                }
                            }
                        } catch (Exception e) {
                            // 1件の再照合失敗で同一batchの後続行を止めない。
                            log.error("[通知outbox] 再照合に失敗したため次回へ繰り越しました: outboxId={} exceptionClass={}",
                                    row.getId(), e.getClass().getName());
                        }
                    }
                    return repaired;
                });
    }

    /** claimからWebhook送信、結果更新までを1件単位のtransactionで実行する。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean dispatchOne(Long outboxId) {
        return ExecutionActorContext.runAsSystem("notification-outbox-dispatcher", "SCHEDULER_POLL",
                () -> dispatchOneInternal(outboxId));
    }

    private boolean dispatchOneInternal(Long outboxId) {
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
            int updated = outboxMapper.markSent(outboxId);
            if (updated == 0) {
                handleOutboxStateConflict(outboxId, "OUTBOX_SENT_UPDATE_CONFLICT");
                return false;
            }
            // outboxはSENTでもdelivery同期が失敗した場合は、reconciliation済みとして成功扱いにしない。
            return syncReportDelivery(outboxId, "SENT", null, null);
        }

        int attempts = row.getAttemptCount() == null ? 1 : row.getAttemptCount();
        String status = attempts >= MAX_ATTEMPTS ? STATUS_FAILED : STATUS_RETRY;
        long backoffMinutes = Math.min(60L, 1L << Math.min(Math.max(attempts - 1, 0), 6));
        String error = "Webhook通知に失敗しました（attempt=" + attempts + "）";
        int updated = outboxMapper.markResult(outboxId, status, LocalDateTime.now().plusMinutes(backoffMinutes), error);
        if (updated > 0) {
            syncReportDelivery(outboxId, status, attempts >= MAX_ATTEMPTS ? "DELIVERY_DLQ" : "DELIVERY_FAILED", error);
        } else {
            handleOutboxStateConflict(outboxId, "OUTBOX_RESULT_UPDATE_CONFLICT");
        }
        return false;
    }

    private boolean syncReportDelivery(Long outboxId, String status, String errorCode, String errorMessage) {
        if (reportDeliveryMapper == null) {
            return true;
        }
        try {
            com.ses.entity.ReportDelivery delivery = reportDeliveryMapper.selectByNotificationOutboxId(outboxId);
            if (delivery == null) {
                return true;
            }
            NotificationOutbox currentOutbox = outboxMapper.selectById(outboxId);
            if (currentOutbox == null || !java.util.Objects.equals(currentOutbox.getStatus(), status)) {
                markReconciliation(outboxId, "REPORT_DELIVERY_SYNC_STALE_OUTBOX");
                log.error("[通知outbox] outbox状態が変化したため配布状態を同期せず再照合へ移行しました: outboxId={} status={}",
                        outboxId, status);
                return false;
            }
            int updated = reportDeliveryMapper.syncOutboxStatus(outboxId, status, errorCode, errorMessage);
            if (updated > 0 || status.equals(delivery.getDeliveryStatus())) {
                return true;
            }
            markReconciliation(outboxId, "REPORT_DELIVERY_SYNC_UPDATE_COUNT_ZERO");
            log.error("[通知outbox] レポート配布状態の同期結果が0件のため再照合へ移行しました: outboxId={} status={}",
                    outboxId, status);
            return false;
        } catch (Exception e) {
            markReconciliation(outboxId, "REPORT_DELIVERY_SYNC_FAILED");
            log.error("[通知outbox] レポート配布状態の同期に失敗したため再照合へ移行しました: outboxId={} status={} exceptionClass={}",
                    outboxId, status, e.getClass().getName());
            return false;
        }
    }

    private void handleOutboxStateConflict(Long outboxId, String error) {
        markReconciliation(outboxId, error);
        log.error("[通知outbox] outbox状態更新が0件のため再照合へ移行しました: outboxId={} errorCode={}",
                outboxId, error);
    }

    private void markReconciliation(Long outboxId, String error) {
        try {
            if (outboxMapper.markReconciliationRequired(outboxId, error) == 0) {
                throw new IllegalStateException("通知outbox再照合フラグの保存に失敗しました");
            }
        } catch (Exception markerFailure) {
            throw new IllegalStateException("通知outbox状態の永続reconciliationに失敗しました", markerFailure);
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
