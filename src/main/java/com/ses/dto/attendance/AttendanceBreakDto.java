package com.ses.dto.attendance;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalTime;

/** 雇用勤怠APIの休憩区間read DTO（方式A / R2-P1-02）。offset→時刻へ戻して表示する。 */
@Data
public class AttendanceBreakDto {
    @JsonFormat(pattern = "HH:mm")
    private LocalTime startTime;

    @JsonFormat(pattern = "HH:mm")
    private LocalTime endTime;
}
