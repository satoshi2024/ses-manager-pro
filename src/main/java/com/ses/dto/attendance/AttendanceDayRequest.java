package com.ses.dto.attendance;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** 本人が入力する雇用勤怠の日次command。sourceは常にmanualでサーバー側が設定する。 */
@Data
public class AttendanceDayRequest {
    private LocalDate workDate;
    @JsonFormat(pattern = "HH:mm")
    private LocalTime clockIn;
    @JsonFormat(pattern = "HH:mm")
    private LocalTime clockOut;
    /**
     * 休憩区間（方式A / R2-P1-02）。区間合計がbreakMinutesの正となる。
     * breakMinutesは区間から導出して保存するため、指定値と不一致の場合は400で拒否する。
     */
    private List<AttendanceBreakRequest> breaks;
    /** 互換性のため残すが、区間合計と不一致の場合は400で拒否する（design §5.1.1）。 */
    private Integer breakMinutes;
    private String workType;
    private String workplaceType;
    private String remarks;
}
