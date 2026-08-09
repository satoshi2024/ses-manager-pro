package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 就業時間の複数休憩（WORK_TIME_TYPEDの反復detail、append-only）。
 * 勤務開始からのoffset分整数で保持する（S11 attendance breakと同一のoffset形状）。
 * 訂正・取消はevent_type=CORRECTED/CANCELLEDの新行INSERTで行い、旧行は不変。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_compliance_break_detail")
public class ComplianceBreakDetail extends BaseEntity {

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
    private Integer breakNo;
    private Integer startOffsetMinute;
    private Integer endOffsetMinute;

    @Version
    private Integer version;
}
