package com.ses.service.leave;

import com.ses.common.exception.BusinessException;
import com.ses.entity.LeaveRequest;
import com.ses.service.attendance.AttendanceCalculator;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 休暇申請の分計算（T071）。所定時間は勤務日asOfの勤務カレンダーを正とし、
 * 半休は所定の半分、時間休は時刻差、全日種別は所定分の合計で算定する。
 * カレンダー未解決・不正区間はfail-closedで拒否する。
 */
public final class LeaveMinutesCalculator {

    public static final String FULL_DAY = "full";
    public static final String HALF_DAY = "half";
    public static final String HOURLY = "hourly";

    private LeaveMinutesCalculator() {
    }

    /** 休暇種別ごとの日次計上区分。時間休のみ時刻を持つ。 */
    public static String allocationType(String leaveType) {
        return switch (leaveType) {
            case "半休" -> HALF_DAY;
            case "時間休" -> HOURLY;
            default -> FULL_DAY;
        };
    }

    /**
     * 申請の日次分（日付→分）を計算する。全日種別は所定分（NULL=休日は0）、
     * 半休は所定の半分（切り捨て）、時間休は当日の時刻差。
     */
    public static Map<LocalDate, Integer> dayMinutes(LeaveRequest leave, AttendanceCalculator calculator) {
        String type = allocationType(leave.getLeaveType());
        Map<LocalDate, Integer> result = new LinkedHashMap<>();
        if (HOURLY.equals(type)) {
            if (leave.getStartTime() == null || leave.getEndTime() == null
                    || !leave.getEndTime().isAfter(leave.getStartTime())) {
                throw BusinessException.of(400, "error.leave.invalidTime");
            }
            int minutes = (int) ChronoUnit.MINUTES.between(leave.getStartTime(), leave.getEndTime());
            result.put(leave.getStartDate(), minutes);
            return result;
        }
        for (LocalDate date = leave.getStartDate(); !date.isAfter(leave.getEndDate()); date = date.plusDays(1)) {
            Integer scheduled = calculator.scheduledMinutesOrNull(date, leave.getEngineerId(),
                    leave.getLegalEntityId(), leave.getOrganizationId());
            int minutes = HALF_DAY.equals(type) ? (scheduled == null ? 0 : scheduled) / 2
                    : (scheduled == null ? 0 : scheduled);
            result.put(date, minutes);
        }
        return result;
    }
}
