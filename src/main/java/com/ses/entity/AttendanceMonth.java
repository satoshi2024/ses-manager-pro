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
import java.time.LocalDateTime;

/** 月次勤怠snapshot。締め済み行はserviceの状態CAS境界で不変にする。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("t_attendance_month")
public class AttendanceMonth extends BaseEntity {
    private Long engineerId;
    private Long legalEntityId;
    private Long organizationId;
    private LocalDate workMonth;
    private Integer scheduledMinutes;
    private Integer workedMinutes;
    private Integer regularMinutes;
    private Integer overtimeMinutes;
    private Integer holidayMinutes;
    private Integer lateNightMinutes;
    private Integer leaveMinutes;
    private String status;
    private LocalDateTime submittedAt;
    private Long submittedBy;
    private LocalDateTime approvedAt;
    private Long approvedBy;
    private LocalDateTime closedAt;
    private Long closedBy;
    private String closeReason;

    @Version
    private Integer version;
}
