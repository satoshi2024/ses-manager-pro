package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * キャリア・コンサルティングのappend-only history（CAREER_HISTORY）。内容は個人情報。
 * 訂正・取消はevent_type=CORRECTED/CANCELLEDの新行INSERTで行い、旧行は不変。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_career_consulting_history")
public class CareerConsultingHistory extends BaseEntity {

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
    private LocalDate consultingDate;
    private String content;

    @Version
    private Integer version;
}
