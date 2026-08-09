package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 紹介予定派遣の予定労働条件（PLANNED_INTRODUCTION_TERMS）。
 * current-conditionのsub-field列（t_planned_introduction_terms）。単一一括構造化データに圧縮しない。
 * 紹介時期・採否・非採用理由は t_planned_introduction_history が保持する。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_planned_introduction_terms")
public class PlannedIntroductionTerms extends BaseEntity {

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
    private LocalDate contractPeriodFrom;
    private LocalDate contractPeriodTo;
    private String renewalRule;
    private Integer renewalLimit;
    private String workChangeScope;
    private String trialPeriod;
    private String wageDetail;
    private String insuranceDetail;
    private String smokingMeasure;
    private String employerName;

    @Version
    private Integer version;
}
