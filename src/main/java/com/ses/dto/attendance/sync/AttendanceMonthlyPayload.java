package com.ses.dto.attendance.sync;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 本システムから外部へ冪等送信する月次勤怠payload。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceMonthlyPayload {
    private Long engineerId;
    private String engineerName;
    /** 対象月（yyyy-MM-01） */
    private String workMonth;
    /** 月次状態（承認済/締め済） */
    private String status;
    private Integer scheduledMinutes;
    private Integer workedMinutes;
    private Integer regularMinutes;
    private Integer overtimeMinutes;
    private Integer holidayMinutes;
    private Integer lateNightMinutes;
    private Integer leaveMinutes;
    private List<Day> days;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Day {
        private String workDate;
        private String clockIn;
        private String clockOut;
        private Integer breakMinutes;
        private Integer regularMinutes;
        private Integer overtimeMinutes;
        private Integer holidayMinutes;
        private Integer lateNightMinutes;
        private String workType;
    }
}
