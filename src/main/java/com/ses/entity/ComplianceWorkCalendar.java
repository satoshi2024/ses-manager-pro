package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 就業日/休日/休暇除外のappend-only calendar history。
 * 訂正・取消はevent_type=CORRECTED/CANCELLEDの新行INSERTで行い、旧行は不変。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_compliance_work_calendar")
public class ComplianceWorkCalendar extends BaseEntity {

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
    private String workDayCode;
    private String holidayCalendarCode;
    private LocalDate excludedDate;

    @Version
    private Integer version;
}
