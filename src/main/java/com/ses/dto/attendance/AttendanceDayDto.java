package com.ses.dto.attendance;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

/** 雇用勤怠APIのread DTO。内部entityを直接公開しない。 */
@Data
public class AttendanceDayDto {
    private Long id;
    private Long engineerId;
    private LocalDate workDate;
    @JsonFormat(pattern = "HH:mm")
    private LocalTime clockIn;
    @JsonFormat(pattern = "HH:mm")
    private LocalTime clockOut;
    private Integer breakMinutes;
    private Integer workedMinutes;
    private String workType;
    private String workplaceType;
    private String status;
    private String remarks;
    private Integer version;
}
