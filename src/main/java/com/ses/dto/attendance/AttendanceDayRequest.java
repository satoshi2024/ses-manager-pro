package com.ses.dto.attendance;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

/** 本人が入力する雇用勤怠の日次command。sourceは常にmanualでサーバー側が設定する。 */
@Data
public class AttendanceDayRequest {
    private LocalDate workDate;
    @JsonFormat(pattern = "HH:mm")
    private LocalTime clockIn;
    @JsonFormat(pattern = "HH:mm")
    private LocalTime clockOut;
    private Integer breakMinutes;
    private String workType;
    private String workplaceType;
    private String remarks;
}
