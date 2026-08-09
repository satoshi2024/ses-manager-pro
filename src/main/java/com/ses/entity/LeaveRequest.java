package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/** 休暇申請。残数の正本はsource-of-truth決定に従い後続taskで扱う。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("t_leave_request")
public class LeaveRequest extends BaseEntity {
    private Long engineerId;
    private Long legalEntityId;
    private Long organizationId;
    private String leaveType;
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private Integer requestedMinutes;
    private String reason;
    private String status;
    private Long approvalRequestId;

    @Version
    private Integer version;

    private Long createdBy;
}
