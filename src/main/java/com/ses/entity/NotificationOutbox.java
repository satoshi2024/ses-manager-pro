package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 通知外部配信をcommit後に再送可能にするoutbox行。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_notification_outbox")
public class NotificationOutbox {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long notificationId;
    private String type;
    private String title;
    private String message;
    private String linkUrl;
    private String menuKey;
    private Long recipientUserId;
    private Long organizationId;
    private String dedupeKey;
    private String status;
    private Integer attemptCount;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime lockedAt;
    private String lastError;
    private LocalDateTime sentAt;
    private LocalDateTime createdAt;
}
