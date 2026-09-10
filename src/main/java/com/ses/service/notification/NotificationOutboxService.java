package com.ses.service.notification;

import com.ses.entity.Notification;
import com.ses.entity.NotificationOutbox;
import com.ses.mapper.NotificationOutboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;

/** 通知の外部配信をcommit後に実行し、失敗時は指数backoffで再送する。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationOutboxService {

    private final NotificationOutboxMapper outboxMapper;
    private final NotificationOutboxDispatcher dispatcher;

    /** 通知生成transaction内で外部配信イベントを保存する。 */
    @Transactional(rollbackFor = Exception.class)
    public Long enqueue(Notification notification) {
        if (notification == null || notification.getDedupeKey() == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        NotificationOutbox row = NotificationOutbox.builder()
                .notificationId(notification.getId())
                .type(notification.getType())
                .title(notification.getTitle())
                .message(notification.getMessage())
                .linkUrl(notification.getLinkUrl())
                .menuKey(notification.getMenuKey())
                .recipientUserId(notification.getRecipientUserId())
                .organizationId(notification.getOrganizationId())
                .dedupeKey(notification.getDedupeKey())
                .status("PENDING")
                .attemptCount(0)
                .nextAttemptAt(now)
                .reconciliationRequired(0)
                .createdAt(now)
                .build();
        try {
            outboxMapper.insert(row);
            return row.getId();
        } catch (DuplicateKeyException e) {
            // 同一通知の再登録は既存outboxへ収束させる。
            return null;
        }
    }

    /** afterCommit callbackから1件を配信する。worker側で独立transactionを開始する。 */
    public boolean dispatchOne(Long outboxId) {
        return dispatcher.dispatchOne(outboxId);
    }

    /** schedulerからdue行をまとめて処理する。各行のclaim・送信・更新は独立transactionで行う。 */
    public int dispatchDue(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        try {
            dispatcher.recoverStaleRows();
        } catch (Exception e) {
            // stale recoveryの障害でdue行全体を止めず、次回recoveryへ繰り越す。
            log.error("通知outboxのstale recoveryに失敗しました: exceptionClass={}", e.getClass().getName());
        }
        try {
            dispatcher.reconcilePending();
        } catch (Exception e) {
            // reconciliationの障害で同一batchの新規due行を止めない。
            log.error("通知outboxのreconciliationに失敗しました: exceptionClass={}", e.getClass().getName());
        }
        List<NotificationOutbox> due = outboxMapper.selectDue(limit);
        int processed = 0;
        for (NotificationOutbox row : due) {
            try {
                if (dispatcher.dispatchOne(row.getId())) {
                    processed++;
                }
            } catch (Exception e) {
                // 1行の状態障害で同一batchの他行を止めず、対象行はtransaction rollback後に再試行させる。
                log.error("通知outboxの1行処理に失敗しました: outboxId={} exceptionClass={}",
                        row.getId(), e.getClass().getName());
            }
        }
        return processed;
    }
}
