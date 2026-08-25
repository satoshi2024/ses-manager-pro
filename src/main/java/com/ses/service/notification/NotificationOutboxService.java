package com.ses.service.notification;

import com.ses.entity.Notification;
import com.ses.entity.NotificationOutbox;
import com.ses.mapper.NotificationOutboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 通知の外部配信をcommit後に実行し、失敗時は指数backoffで再送する。 */
@Service
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
        dispatcher.recoverStaleRows();
        List<NotificationOutbox> due = outboxMapper.selectDue(limit);
        int processed = 0;
        for (NotificationOutbox row : due) {
            if (dispatcher.dispatchOne(row.getId())) {
                processed++;
            }
        }
        return processed;
    }
}
