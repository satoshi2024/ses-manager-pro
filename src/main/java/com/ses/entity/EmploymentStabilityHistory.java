package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 雇用安定措置の依頼・回答・実施のappend-only history（EMPLOYMENT_STABILITY_HISTORY）。
 * 訂正・取消はevent_type=CORRECTED/CANCELLEDの新行INSERTで行い、旧行は不変。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_employment_stability_history")
public class EmploymentStabilityHistory extends BaseEntity {

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
    private LocalDate requestAt;
    private String requestMethod;
    private LocalDate responseAt;
    private String responseContent;
    private String action;
    private String outcome;

    @Version
    private Integer version;
}
