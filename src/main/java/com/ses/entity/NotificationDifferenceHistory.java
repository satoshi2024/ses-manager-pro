package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 契約内容と明示内容の差異のappend-only history（NOTIFICATION_DIFFERENCE_HISTORY）。
 * 差異発生時だけ反復行を追加。訂正・取消は新event INSERTで行い、旧行は不変。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_notification_difference_history")
public class NotificationDifferenceHistory extends BaseEntity {

    private String tenantId;
    private Long contractId;
    private Long workerId;
    private String eventId;
    private String eventType;
    private String supersedesEventId;
    private String correctionReason;
    private Long actorUserId;
    private LocalDateTime occurredAt;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private String differenceType;
    private Long contractSnapshotId;
    private Long noticeSnapshotId;
    private String differenceDetail;

    @Version
    private Integer version;
}
