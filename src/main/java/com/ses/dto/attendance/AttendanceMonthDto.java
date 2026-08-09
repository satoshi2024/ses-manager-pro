package com.ses.dto.attendance;

import lombok.Data;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 月次勤怠のread DTO。 */
@Data
public class AttendanceMonthDto {
    private Long id;
    private Long engineerId;
    private String engineerName;
    private LocalDate workMonth;
    private Integer workedMinutes;
    private Integer regularMinutes;
    private Integer overtimeMinutes;
    private Integer holidayMinutes;
    private Integer lateNightMinutes;
    private Integer leaveMinutes;
    private String status;
    private Integer version;
    private List<AttendanceDayDto> days = new ArrayList<>();
}
