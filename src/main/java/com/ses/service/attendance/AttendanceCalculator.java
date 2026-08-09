package com.ses.service.attendance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.WorkCalendar;
import com.ses.entity.WorkCalendarDay;
import com.ses.mapper.WorkCalendarDayMapper;
import com.ses.mapper.WorkCalendarMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;

/**
 * 雇用勤怠の日次計算を一箇所へ閉じ込める。休日区分はclient inputではなくcalendarを正とする。
 * 日8時間・週40時間・深夜・跨夜・休憩の口径はここから外へ出さない。
 */
@Service
@RequiredArgsConstructor
public class AttendanceCalculator {

    private static final int MINUTES_PER_DAY = 24 * 60;
    private static final int LEGAL_DAILY_MINUTES = 8 * 60;
    private static final int LEGAL_WEEKLY_MINUTES = 40 * 60;

    private final WorkCalendarMapper workCalendarMapper;
    private final WorkCalendarDayMapper workCalendarDayMapper;

    public AttendanceCalculation calculate(LocalDate workDate, Long engineerId, Long legalEntityId,
                                           Long organizationId, LocalTime clockIn, LocalTime clockOut,
                                           Integer breakMinutes, int priorWeekMinutes) {
        WorkCalendar calendar = resolveCalendar(workDate, engineerId, legalEntityId, organizationId);
        WorkCalendarDay day = workCalendarDayMapper.selectOne(new LambdaQueryWrapper<WorkCalendarDay>()
                .eq(WorkCalendarDay::getCalendarId, calendar.getId())
                .eq(WorkCalendarDay::getCalendarDate, workDate)
                .last("LIMIT 1"));
        if (day == null || day.getDayType() == null || day.getDayType().isBlank()) {
            throw BusinessException.of(400, "error.attendance.calendarDayUnknown");
        }
        if (!List.of("通常", "所定日", "所定休日", "法定休日").contains(day.getDayType())) {
            throw BusinessException.of(400, "error.attendance.calendarDayUnknown");
        }
        int breaks = breakMinutes == null ? 0 : breakMinutes;
        int start = toMinute(clockIn);
        int end = toEndMinute(clockIn, clockOut);
        int gross = end - start;
        if (gross < 0 || gross > MINUTES_PER_DAY || breaks < 0 || breaks > gross) {
            throw BusinessException.of(400, "error.attendance.invalidTime");
        }
        int effectiveEnd = end - breaks;
        int worked = effectiveEnd - start;
        int lateNight = nightOverlap(start, effectiveEnd);

        int regular = 0;
        int overtime = 0;
        int holiday = 0;
        if ("法定休日".equals(day.getDayType())) {
            holiday = worked;
        } else if ("所定休日".equals(day.getDayType())) {
            overtime = worked;
        } else {
            int dailyLegal = Math.min(worked, LEGAL_DAILY_MINUTES);
            int dailyOvertime = Math.max(0, worked - LEGAL_DAILY_MINUTES);
            int weeklyOvertime = incrementalWeeklyOvertime(priorWeekMinutes, dailyLegal);
            regular = dailyLegal - weeklyOvertime;
            overtime = dailyOvertime + weeklyOvertime;
        }
        return new AttendanceCalculation(calendar.getId(), day.getScheduledMinutes(), day.getDayType(),
                worked, regular, overtime, holiday, lateNight);
    }

    private WorkCalendar resolveCalendar(LocalDate workDate, Long engineerId, Long legalEntityId,
                                         Long organizationId) {
        if (workDate == null || engineerId == null || legalEntityId == null) {
            throw BusinessException.of(400, "error.attendance.calendarUnknown");
        }
        List<WorkCalendar> candidates = workCalendarMapper.selectList(new LambdaQueryWrapper<WorkCalendar>()
                .eq(WorkCalendar::getStatus, "有効")
                .le(WorkCalendar::getValidFrom, workDate)
                .and(w -> w.isNull(WorkCalendar::getValidTo).or().ge(WorkCalendar::getValidTo, workDate))
                .and(w -> w.eq(WorkCalendar::getEngineerId, engineerId)
                        .or().eq(WorkCalendar::getOrganizationId, organizationId)
                        .or().eq(WorkCalendar::getLegalEntityId, legalEntityId)));
        return candidates.stream()
                .filter(calendar -> engineerId.equals(calendar.getEngineerId())
                        || organizationId.equals(calendar.getOrganizationId())
                        || legalEntityId.equals(calendar.getLegalEntityId()))
                .sorted(Comparator
                        .comparingInt((WorkCalendar c) -> specificity(c, engineerId, organizationId, legalEntityId))
                        .thenComparing(WorkCalendar::getValidFrom, Comparator.reverseOrder())
                        .thenComparing(WorkCalendar::getId, Comparator.reverseOrder()))
                .findFirst()
                .orElseThrow(() -> BusinessException.of(400, "error.attendance.calendarUnknown"));
    }

    private int specificity(WorkCalendar calendar, Long engineerId, Long organizationId, Long legalEntityId) {
        if (engineerId.equals(calendar.getEngineerId())) return 0;
        if (organizationId.equals(calendar.getOrganizationId())) return 1;
        if (legalEntityId.equals(calendar.getLegalEntityId())) return 2;
        return 3;
    }

    private int incrementalWeeklyOvertime(int priorWeekMinutes, int dailyLegalMinutes) {
        int prior = Math.max(0, priorWeekMinutes);
        return Math.max(0, prior + dailyLegalMinutes - LEGAL_WEEKLY_MINUTES)
                - Math.max(0, prior - LEGAL_WEEKLY_MINUTES);
    }

    private int nightOverlap(int start, int end) {
        if (end <= start) return 0;
        return overlap(start, end, 0, 5 * 60) + overlap(start, end, 22 * 60, 29 * 60);
    }

    private int overlap(int start, int end, int rangeStart, int rangeEnd) {
        return Math.max(0, Math.min(end, rangeEnd) - Math.max(start, rangeStart));
    }

    private int toMinute(LocalTime value) {
        return value == null ? 0 : value.getHour() * 60 + value.getMinute();
    }

    private int toEndMinute(LocalTime clockIn, LocalTime clockOut) {
        if (clockIn == null && clockOut == null) return 0;
        if (clockIn == null || clockOut == null) {
            throw BusinessException.of(400, "error.attendance.invalidTime");
        }
        int start = toMinute(clockIn);
        int end = toMinute(clockOut);
        if (end < start) end += MINUTES_PER_DAY;
        return end;
    }
}
