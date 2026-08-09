package com.ses.dto.attendance;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalTime;

/**
 * 本人が入力する休憩区間command（方式A / R2-P1-02）。
 * 区間は当該勤務の出勤時刻を基準に解釈し、退勤時刻を跨ぐ区間は跨夜休憩として
 * サーバー側でoffsetへ変換する（design §5.1.1）。
 */
@Data
public class AttendanceBreakRequest {
    @JsonFormat(pattern = "HH:mm")
    private LocalTime startTime;

    @JsonFormat(pattern = "HH:mm")
    private LocalTime endTime;
}
