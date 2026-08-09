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

/** 雇用勤怠の日次原簿。時間はすべて分の整数で保持する。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("t_employee_attendance")
public class EmployeeAttendance extends BaseEntity {
    private Long engineerId;
    private Long legalEntityId;
    private Long organizationId;
    private Long workCalendarId;
    private LocalDate workDate;
    private LocalTime clockIn;
    private LocalTime clockOut;
    private Integer breakMinutes;
    private Integer regularMinutes;
    private Integer overtimeMinutes;
    private Integer holidayMinutes;
    private Integer lateNightMinutes;
    private String workType;
    private String workplaceType;
    private String source;
    private String sourceExternalId;
    private String status;
    private String remarks;

    @Version
    private Integer version;
}
