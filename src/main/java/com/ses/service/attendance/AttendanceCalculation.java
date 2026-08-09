package com.ses.service.attendance;

/** 勤務日1件の計算結果。すべて分単位で保持する。 */
public record AttendanceCalculation(
        Long workCalendarId,
        Integer scheduledMinutes,
        String workType,
        int workedMinutes,
        int regularMinutes,
        int overtimeMinutes,
        int holidayMinutes,
        int lateNightMinutes
) {
}
